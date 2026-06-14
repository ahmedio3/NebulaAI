package com.nebulaai.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nebulaai.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val keyStore = KeyStore(application)

    val selectedModel: StateFlow<String> = keyStore.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatModels.DEFAULT.id)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentChatKey = MutableStateFlow("")
    val chatKey: StateFlow<String> = _currentChatKey.asStateFlow()

    init {
        viewModelScope.launch {
            keyStore.chatKey.collect { _currentChatKey.value = it }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch { keyStore.setSelectedModel(modelId) }
    }

    private var currentStreamingJob: kotlinx.coroutines.Job? = null

    fun sendMessage(text: String) {
        if (text.isBlank() || _isStreaming.value) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text.trim(),
        )

        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
        )

        _messages.update { it + userMsg + assistantMsg }
        _isStreaming.value = true

        currentStreamingJob = viewModelScope.launch {
            val apiKey = _currentChatKey.value
            if (apiKey.isBlank()) {
                _messages.update { msgs ->
                    msgs.map { msg ->
                        if (msg.id == assistantId) msg.copy(
                            content = "No API key set. Go to Settings → enter your chatkey (NVIDIA NIM API key).",
                            isStreaming = false,
                            isError = true,
                        )
                        else msg
                    }
                }
                _isStreaming.value = false
                currentStreamingJob = null
                return@launch
            }

            val chatClient = ChatApiClient(apiKey)
            val payloads = (_messages.value - assistantMsg)
                .filter { !it.isStreaming }
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

            try {
                chatClient.streamChatCompletion(
                    modelId = selectedModel.value,
                    messages = payloads,
                ).collect { token ->
                    when {
                        token == "[[DONE]]" -> {
                            // Stream completed normally — stop collecting
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantId) msg.copy(isStreaming = false)
                                    else msg
                                }
                            }
                            _isStreaming.value = false
                        }
                        token.startsWith("[ERROR]") -> {
                            val errMsg = token.removePrefix("[ERROR] ").removePrefix("[ERROR]")
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantId) msg.copy(
                                        content = errMsg,
                                        isStreaming = false,
                                        isError = true,
                                    )
                                    else msg
                                }
                            }
                            _isStreaming.value = false
                        }
                        else -> {
                            _messages.update { msgs ->
                                msgs.map { msg ->
                                    if (msg.id == assistantId && msg.isStreaming) {
                                        msg.copy(content = msg.content + token)
                                    } else msg
                                }
                            }
                        }
                    }
                }

                // If streaming is still true, mark complete (safety net)
                if (_isStreaming.value) {
                    val currentContent = _messages.value.find { it.id == assistantId }?.content
                    if (currentContent.isNullOrBlank()) {
                        // No content was produced — try non-streaming fallback
                        _messages.update { msgs ->
                            msgs.map { msg ->
                                if (msg.id == assistantId) msg.copy(
                                    content = "The model returned an empty response. Try again or switch models.",
                                    isStreaming = false,
                                    isError = true,
                                )
                                else msg
                            }
                        }
                    } else {
                        _messages.update { msgs ->
                            msgs.map { msg ->
                                if (msg.id == assistantId) msg.copy(isStreaming = false)
                                else msg
                            }
                        }
                    }
                    _isStreaming.value = false
                }
            } catch (e: Exception) {
                _messages.update { msgs ->
                    msgs.map { msg ->
                        if (msg.id == assistantId) msg.copy(
                            content = "Error: ${e.message ?: "Unknown error"}",
                            isStreaming = false,
                            isError = true,
                        )
                        else msg
                    }
                }
                _isStreaming.value = false
            } finally {
                currentStreamingJob = null
            }
        }
    }

    fun deleteMessage(id: String) {
        _messages.update { msgs -> msgs.filter { it.id != id } }
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun stopStreaming() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        _isStreaming.value = false
        _messages.update { msgs ->
            msgs.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false)
                else msg
            }
        }
    }
}
