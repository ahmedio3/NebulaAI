package com.nebulaai.app.data

/**
 * Available chat models on NVIDIA NIM.
 * The `id` must match the model identifier accepted by the API.
 */
data class ChatModel(
    val id: String,
    val displayName: String,
    val provider: String,
)

object ChatModels {
    val ALL = listOf(
        ChatModel(
            id = "deepseek-ai/deepseek-v4-flash",
            displayName = "DeepSeek V4 Flash",
            provider = "DeepSeek",
        ),
        ChatModel(
            id = "stepfun-ai/step-3.7-flash",
            displayName = "Step 3.7 Flash",
            provider = "StepFun",
        ),
        ChatModel(
            id = "minimaxai/minimax-m3",
            displayName = "MiniMax M3",
            provider = "MiniMax",
        ),
        ChatModel(
            id = "google/gemma-4-31b-it",
            displayName = "Gemma 4 31B",
            provider = "Google",
        ),
        ChatModel(
            id = "nvidia/nemotron-3-super-120b-a12b",
            displayName = "Nemotron 3 Super",
            provider = "NVIDIA",
        ),
    )

    val DEFAULT = ALL[0]
}
