package com.nebulaai.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Low-level API client for NVIDIA NIM chat completions (OpenAI-compatible).
 * Supports both streaming (SSE) and non-streaming modes.
 */
class ChatApiClient(
    private val apiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Stream chat completion tokens as a Flow.
     * Emits individual content tokens. After the stream completes,
     * emits "[[DONE]]" so the ViewModel knows to stop streaming.
     * On error, emits "[ERROR] ...".
     */
    fun streamChatCompletion(
        modelId: String,
        messages: List<ChatMessagePayload>,
        temperature: Double = 0.6,
        maxTokens: Int = 2048,
    ): Flow<String> = flow {
        val requestPayload = ChatCompletionRequest(
            model = modelId,
            messages = messages,
            stream = true,
            temperature = temperature,
            max_tokens = maxTokens,
        )
        val body = json.encodeToString(
            ChatCompletionRequest.serializer(),
            requestPayload,
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                // Try to parse a meaningful error from response
                val msg = try {
                    val errJson = json.parseToJsonElement(errorBody).jsonObject
                    errJson["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        ?: "HTTP ${response.code}"
                } catch (_: Exception) {
                    "HTTP ${response.code}: ${errorBody.take(200)}"
                }
                emit("[ERROR] $msg")
                return@flow
            }

            // Check if response is actually SSE or just JSON (non-streaming fallback)
            val contentType = response.header("Content-Type", "") ?: ""
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                // Non-streaming response — parse as JSON directly
                val bodyStr = response.body?.string()
                if (bodyStr != null) {
                    try {
                        val parsed = json.decodeFromString(
                            ChatCompletionResponse.serializer(),
                            bodyStr,
                        )
                        val content = parsed.choices.firstOrNull()?.message?.content
                        if (!content.isNullOrBlank()) {
                            emit(content)
                        } else {
                            emit("[ERROR] Empty response from model")
                        }
                    } catch (e: Exception) {
                        emit("[ERROR] Failed to parse response: ${e.message}")
                    }
                } else {
                    emit("[ERROR] Empty response body")
                }
                emit("[[DONE]]")
                return@flow
            }

            // SSE streaming
            val reader = response.body?.byteStream()?.bufferedReader()
                ?: run {
                    emit("[ERROR] Empty response body")
                    emit("[[DONE]]")
                    return@flow
                }

            var hasContent = false
            reader.use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.isBlank()) continue

                    // Handle both "data: [DONE]" and "data: [DONE]" variants
                    if (currentLine.startsWith("data: [DONE]")) break
                    if (!currentLine.startsWith("data:")) continue

                    val jsonStr = currentLine.removePrefix("data:").trim()
                    if (jsonStr.isBlank() || jsonStr == "[DONE]") continue

                    try {
                        // Try parsing as ChatCompletionChunk first (preferred)
                        val chunk = json.decodeFromString(
                            ChatCompletionChunk.serializer(),
                            jsonStr,
                        )
                        val choice = chunk.choices.firstOrNull()
                        if (choice != null) {
                            // Check for finish_reason — stream is done
                            if (choice.finish_reason != null && !choice.finish_reason.isNullOrEmpty()) {
                                // Emit any remaining content then break
                                val content = choice.delta?.content
                                if (!content.isNullOrEmpty()) {
                                    hasContent = true
                                    emit(content)
                                }
                                break
                            }

                            val content = choice.delta?.content
                            if (!content.isNullOrEmpty()) {
                                hasContent = true
                                emit(content)
                            } else if (choice.delta?.role == "assistant") {
                                // Role-only delta, skip it
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE chunk: $jsonStr", e)
                    }
                }
            }

            // If no content was emitted at all, the model may have returned without output
            if (!hasContent) {
                Log.w(TAG, "Stream completed with no content for model=$modelId")
            }

            emit("[[DONE]]")
        } catch (e: Exception) {
            Log.e(TAG, "Stream request failed", e)
            emit("[ERROR] ${e.message ?: "Network error"}")
            emit("[[DONE]]")
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming chat completion — returns the full message at once (fallback). */
    suspend fun chatCompletion(
        modelId: String,
        messages: List<ChatMessagePayload>,
        temperature: Double = 0.6,
        maxTokens: Int = 2048,
    ): String {
        val requestPayload = ChatCompletionRequest(
            model = modelId,
            messages = messages,
            stream = false,
            temperature = temperature,
            max_tokens = maxTokens,
        )
        val body = json.encodeToString(
            ChatCompletionRequest.serializer(),
            requestPayload,
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return "[ERROR] HTTP ${response.code}: ${errorBody.take(500)}"
            }
            val responseBody = response.body?.string() ?: return "[ERROR] Empty response"
            val parsed = json.decodeFromString(
                ChatCompletionResponse.serializer(),
                responseBody,
            )
            parsed.choices.firstOrNull()?.message?.content ?: "[ERROR] No content in response"
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion failed", e)
            "[ERROR] ${e.message ?: "Network error"}"
        }
    }

    companion object {
        private const val TAG = "ChatApiClient"
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
