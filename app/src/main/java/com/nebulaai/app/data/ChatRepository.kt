package com.nebulaai.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Repository for chat interactions.
 * Manages the in-memory conversation and delegates to ChatApiClient.
 */
class ChatRepository {

    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    fun addMessage(message: ChatMessage) {
        _messages.add(message)
    }

    fun updateMessage(id: String, content: String, isStreaming: Boolean = false) {
        val idx = _messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _messages[idx] = _messages[idx].copy(
                content = content,
                isStreaming = isStreaming,
            )
        }
    }

    fun setMessageError(id: String, errorContent: String) {
        val idx = _messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _messages[idx] = _messages[idx].copy(
                content = errorContent,
                isStreaming = false,
                isError = true,
            )
        }
    }

    fun removeMessage(id: String) {
        _messages.removeAll { it.id == id }
    }

    fun clearMessages() {
        _messages.clear()
    }

    /** Build the payload for the API from conversation history. */
    fun buildPayloads(): List<ChatMessagePayload> {
        return _messages
            .filter { !it.isStreaming && !it.isError }
            .map { msg ->
                ChatMessagePayload(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                        MessageRole.SYSTEM -> "system"
                    },
                    content = msg.content,
                )
            }
    }

    /**
     * Stream a chat completion, appending tokens to the assistant message in real time.
     * Returns a Flow of the *full* accumulated content after each token.
     */
    fun streamResponse(
        client: ChatApiClient,
        modelId: String,
        assistantMessageId: String,
    ): Flow<String> = flow {
        val payloads = buildPayloads()
        var accumulated = ""
        client.streamChatCompletion(modelId, payloads).collect { token ->
            if (token.startsWith("[ERROR]")) {
                emit(token)
            } else {
                accumulated += token
                updateMessage(assistantMessageId, accumulated, isStreaming = true)
                emit(accumulated)
            }
        }
        // Mark streaming complete
        updateMessage(assistantMessageId, accumulated, isStreaming = false)
    }
}
