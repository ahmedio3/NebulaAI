package com.nebulaai.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * API client for image generation via NVIDIA NIM.
 * Uses the Qwen-Image model.
 *
 * Tries multiple endpoint formats:
 *   1) OpenAI-compatible: POST https://integrate.api.nvidia.com/v1/images/generations
 *   2) NVIDIA GenAI:       POST https://ai.api.nvidia.com/v1/genai/qwen/qwen-image
 *   3) NVIDIA VLM:         POST https://ai.api.nvidia.com/v1/vlm/qwen/qwen-image
 *
 * Response parsing handles multiple formats:
 *   - { "data": [{"b64_json": "..."}] }
 *   - { "data": [{"url": "..."}] }
 *   - { "image": "<base64>" }
 */
class ImageApiClient(
    private val apiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateImage(
        prompt: String,
        negativePrompt: String = "",
        aspectRatio: String = "1:1",
    ): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        try {
            // Try endpoints in order
            val endpoints = listOf(
                // 1) OpenAI-compatible format
                "${BASE_INTEGRATE}/images/generations",
                // 2) NVIDIA GenAI
                "${BASE_GENAI}/qwen/qwen-image",
                // 3) NVIDIA VLM
                "${BASE_VLM}/qwen/qwen-image",
            )

            var lastError: String? = null

            for (endpoint in endpoints) {
                val result = tryEndpoint(endpoint, prompt, negativePrompt, aspectRatio)
                if (result != null) return@withContext Result.success(result)
            }

            Result.failure(Exception("All endpoints failed. Last error: ${lastError ?: "Unknown"}"))

        } catch (e: Exception) {
            Log.e(TAG, "Image generation failed", e)
            Result.failure(e)
        }
    }

    private suspend fun tryEndpoint(
        endpoint: String,
        prompt: String,
        negativePrompt: String,
        aspectRatio: String,
    ): GeneratedImage? = withContext(Dispatchers.IO) {
        try {
            // Build the request body according to endpoint type
            val body = if (endpoint.contains("images/generations")) {
                // OpenAI-compatible format
                buildJsonObject {
                    put("model", JsonPrimitive(MODEL_NAME))
                    put("prompt", JsonPrimitive(prompt))
                    put("n", JsonPrimitive(1))
                    // Map our aspect ratio to a size
                    put("size", JsonPrimitive(aspectRatioToSize(aspectRatio)))
                }.toString().toRequestBody(JSON_MEDIA_TYPE)
            } else {
                // NVIDIA genai format
                buildJsonObject {
                    put("prompt", JsonPrimitive(prompt))
                    if (negativePrompt.isNotBlank()) {
                        put("negative_prompt", JsonPrimitive(negativePrompt))
                    }
                    put("aspect_ratio", JsonPrimitive(aspectRatio))
                }.toString().toRequestBody(JSON_MEDIA_TYPE)
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            Log.d(TAG, "Trying endpoint: $endpoint")
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.w(TAG, "Endpoint $endpoint returned HTTP ${response.code}: ${errorBody.take(200)}")
                return@withContext null
            }

            val responseBody = response.body?.string()
                ?: return@withContext null

            val jsonElement = json.parseToJsonElement(responseBody)
            val jsonObj = jsonElement.jsonObject

            // Try multiple response formats
            val imageData: String
            val isBase64: Boolean

            // Format 1: OpenAI-style { "data": [{"b64_json": "..."}] }
            val dataArr = jsonObj["data"]?.jsonArray
            if (dataArr != null && dataArr.isNotEmpty()) {
                val firstItem = dataArr[0].jsonObject
                val b64 = firstItem["b64_json"]?.jsonPrimitive?.content
                val url = firstItem["url"]?.jsonPrimitive?.content
                if (!b64.isNullOrEmpty()) {
                    imageData = b64; isBase64 = true
                } else if (!url.isNullOrEmpty()) {
                    imageData = url; isBase64 = false
                } else {
                    return@withContext null
                }
            }
            // Format 2: Direct { "image": "<base64>" }
            else if (jsonObj.containsKey("image")) {
                imageData = jsonObj["image"]?.jsonPrimitive?.content ?: return@withContext null
                isBase64 = true
            }
            // Format 3: { "url": "..." }
            else if (jsonObj.containsKey("url")) {
                imageData = jsonObj["url"]?.jsonPrimitive?.content ?: return@withContext null
                isBase64 = false
            } else {
                Log.w(TAG, "Unknown response format from $endpoint. Keys: ${jsonObj.keys}")
                return@withContext null
            }

            GeneratedImage(
                id = UUID.randomUUID().toString(),
                prompt = prompt,
                negativePrompt = negativePrompt,
                aspectRatio = aspectRatio,
                imageData = imageData,
                timestamp = System.currentTimeMillis(),
                isBase64 = isBase64,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Endpoint $endpoint failed", e)
            null
        }
    }

    private fun aspectRatioToSize(ratio: String): String {
        return when (ratio) {
            "1:1" -> "1024x1024"
            "16:9" -> "1920x1080"
            "9:16" -> "1080x1920"
            "4:3" -> "1024x768"
            "3:4" -> "768x1024"
            "3:2" -> "1200x800"
            "2:3" -> "800x1200"
            else -> "1024x1024"
        }
    }

    companion object {
        private const val TAG = "ImageApiClient"
        private const val MODEL_NAME = "qwen/qwen-image"
        private const val BASE_INTEGRATE = "https://integrate.api.nvidia.com/v1"
        private const val BASE_GENAI = "https://ai.api.nvidia.com/v1/genai"
        private const val BASE_VLM = "https://ai.api.nvidia.com/v1/vlm"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
