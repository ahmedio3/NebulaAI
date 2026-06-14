package com.nebulaai.app.data

import kotlinx.serialization.Serializable

/** A single message in the chat conversation. */
@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

/** Model we can send to the OpenAI-compatible chat endpoint. */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessagePayload>,
    val stream: Boolean = true,
    val temperature: Double = 0.6,
    val max_tokens: Int = 2048,
)

@Serializable
data class ChatMessagePayload(
    val role: String,
    val content: String,
)

// --- Streaming response models ---

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta,
    val finish_reason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
)

// --- Non-streaming response (fallback) ---

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ResponseChoice> = emptyList(),
)

@Serializable
data class ResponseChoice(
    val index: Int = 0,
    val message: ResponseMessage,
    val finish_reason: String? = null,
)

@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
)

// --- Image generation models ---

@Serializable
data class ImageGenRequest(
    val prompt: String,
    val negative_prompt: String = "",
    val aspect_ratio: String = "1:1",
    val n: Int = 1,
)

@Serializable
data class ImageGenResponse(
    val data: List<ImageData>? = null,
    val image: String? = null,       // Some endpoints return base64 directly
    val url: String? = null,         // Some return a URL
)

@Serializable
data class ImageData(
    val b64_json: String? = null,
    val url: String? = null,
)

/** A saved image generation record. */
@Serializable
data class GeneratedImage(
    val id: String,
    val prompt: String,
    val negativePrompt: String,
    val aspectRatio: String,
    val imageData: String,  // Base64 or URL
    val timestamp: Long = System.currentTimeMillis(),
    val isBase64: Boolean = true,
)
