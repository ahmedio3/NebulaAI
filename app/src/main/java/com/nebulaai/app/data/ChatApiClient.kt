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

    /** Stream chat completion tokens as a Flow of content deltas. */
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
                emit("[ERROR] HTTP ${response.code}: ${errorBody.take(500)}")
                return@flow
            }

            val reader = response.body?.byteStream()?.bufferedReader()
                ?: run {
                    emit("[ERROR] Empty response body")
                    return@flow
                }

            reader.use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (currentLine.isBlank()) continue
                    if (currentLine.startsWith("data: [DONE]")) break
                    if (!currentLine.startsWith("data:")) continue

                    val jsonStr = currentLine.removePrefix("data:").trim()
                    if (jsonStr.isBlank() || jsonStr == "[DONE]") continue

                    try {
                        val chunk = json.decodeFromString(
                            ChatCompletionChunk.serializer(),
                            jsonStr,
                        )
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            emit(content)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE chunk: $jsonStr", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream request failed", e)
            emit("[ERROR] ${e.message ?: "Network error"}")
        }
    }.flowOn(Dispatchers.IO)

    /** Non-streaming chat completion — returns the full message at once. */
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
