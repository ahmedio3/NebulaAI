package com.nebulaai.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nebulaai.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ImageGenViewModel(application: Application) : AndroidViewModel(application) {

    private val keyStore = KeyStore(application)

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generatedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val generatedImages: StateFlow<List<GeneratedImage>> = _generatedImages.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentPicKey = MutableStateFlow("")
    val picKey: StateFlow<String> = _currentPicKey.asStateFlow()

    init {
        viewModelScope.launch {
            keyStore.picKey.collect { _currentPicKey.value = it }
        }
        viewModelScope.launch {
            keyStore.imageHistory.collect { history ->
                _generatedImages.value = history
            }
        }
    }

    fun generateImage(
        prompt: String,
        negativePrompt: String = "",
        aspectRatio: String = "1:1",
    ) {
        if (prompt.isBlank() || _isGenerating.value) return

        viewModelScope.launch {
            val apiKey = _currentPicKey.value
            if (apiKey.isBlank()) {
                _error.value = "No API key set. Go to Settings → enter your pickey (NVIDIA NIM API key for image generation)."
                return@launch
            }

            _isGenerating.value = true
            _error.value = null

            val client = ImageApiClient(apiKey)
            val result = client.generateImage(prompt, negativePrompt, aspectRatio)

            result.onSuccess { image ->
                _generatedImages.update { listOf(image) + it }
                keyStore.addImageToHistory(image)
            }.onFailure { e ->
                _error.value = e.message ?: "Image generation failed"
            }

            _isGenerating.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearHistory() {
        _generatedImages.value = emptyList()
        viewModelScope.launch { keyStore.clearImageHistory() }
    }
}
