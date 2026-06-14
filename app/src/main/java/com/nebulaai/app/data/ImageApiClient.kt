package com.nebulaai.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * Uses the Qwen-Image model through the genai endpoint.
 *
 * The endpoint follows NVIDIA's generative AI pattern:
 *   POST https://ai.api.nvidia.com/v1/genai/qwen/qwen-image
 *
 * Request body:
 *   { "prompt": "...", "negative_prompt": "...", "aspect_ratio": "1:1" }
 *
 * Response may include:
 *   - base64 image data under "data[0].b64_json"
 *   - or a URL under "data[0].url"
 *   - or direct base64 under "image" (some models)
 *
 * We try to parse any of these formats.
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
            val jsonBuilder = kotlinx.serialization.json.buildJsonObject {
                put("prompt", prompt)
                if (negativePrompt.isNotBlank()) {
                    put("negative_prompt", negativePrompt)
                }
                put("aspect_ratio", aspectRatio)
            }

            val body = jsonBuilder.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(IMAGE_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${errorBody.take(500)}")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

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
                    imageData = b64
                    isBase64 = true
                } else if (!url.isNullOrEmpty()) {
                    imageData = url
                    isBase64 = false
                } else {
                    return@withContext Result.failure(
                        Exception("No image data in response. Keys: ${firstItem.keys}")
                    )
                }
            }
            // Format 2: Direct { "image": "<base64>" }
            else if (jsonObj.containsKey("image")) {
                imageData = jsonObj["image"]?.jsonPrimitive?.content ?: ""
                isBase64 = true
            }
            // Format 3: { "url": "..." }
            else if (jsonObj.containsKey("url")) {
                imageData = jsonObj["url"]?.jsonPrimitive?.content ?: ""
                isBase64 = false
            }
            else {
                return@withContext Result.failure(
                    Exception("Unexpected response format. Keys: ${jsonObj.keys}\n${responseBody.take(500)}")
                )
            }

            Result.success(
                GeneratedImage(
                    id = UUID.randomUUID().toString(),
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    aspectRatio = aspectRatio,
                    imageData = imageData,
                    timestamp = System.currentTimeMillis(),
                    isBase64 = isBase64,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Image generation failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ImageApiClient"
        private const val IMAGE_ENDPOINT =
            "https://ai.api.nvidia.com/v1/genai/qwen/qwen-image"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
