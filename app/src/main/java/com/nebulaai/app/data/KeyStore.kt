package com.nebulaai.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent key-value store for user preferences and API keys.
 * Keys are stored securely in DataStore (not shared prefs for security).
 */
class KeyStore(private val context: Context) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "nebula_prefs"
    )

    // --- Keys ---
    private val chatKeyPref = stringPreferencesKey("chatkey")
    private val picKeyPref = stringPreferencesKey("pickey")
    private val selectedModelPref = stringPreferencesKey("selected_model")

    // --- Chat API Key ---
    val chatKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[chatKeyPref] ?: ApiKeyDefaults.defaultChatKey
    }

    suspend fun setChatKey(key: String) {
        context.dataStore.edit { it[chatKeyPref] = key }
    }

    // --- Image API Key ---
    val picKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[picKeyPref] ?: ApiKeyDefaults.defaultPicKey
    }

    suspend fun setPicKey(key: String) {
        context.dataStore.edit { it[picKeyPref] = key }
    }

    // --- Selected Model ---
    val selectedModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[selectedModelPref] ?: ChatModels.DEFAULT.id
    }

    suspend fun setSelectedModel(modelId: String) {
        context.dataStore.edit { it[selectedModelPref] = modelId }
    }

    // --- Image Generation History (stored as JSON string) ---
    private val imageHistoryPref = stringPreferencesKey("image_history")

    val imageHistory: Flow<List<GeneratedImage>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[imageHistoryPref] ?: "[]"
        try {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { element ->
                try {
                    kotlinx.serialization.json.Json.decodeFromJsonElement(
                        GeneratedImage.serializer(),
                        element,
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun addImageToHistory(image: GeneratedImage) {
        context.dataStore.edit { prefs ->
            val current = prefs[imageHistoryPref] ?: "[]"
            val arr = try {
                kotlinx.serialization.json.Json.parseToJsonElement(current).jsonArray
            } catch (_: Exception) { kotlinx.serialization.json.buildJsonArray { } }

            val newList = kotlinx.serialization.json.buildJsonArray {
                for (i in 0 until arr.size) add(arr[i])
                add(kotlinx.serialization.json.Json.encodeToJsonElement(
                    GeneratedImage.serializer(), image
                ))
                // Keep last 50 max
            }.let { arr2 ->
                if (arr2.size > 50) {
                    kotlinx.serialization.json.buildJsonArray {
                        for (i in (arr2.size - 50) until arr2.size) add(arr2[i])
                    }
                } else arr2
            }
            prefs[imageHistoryPref] = newList.toString()
        }
    }

    suspend fun clearImageHistory() {
        context.dataStore.edit { it[imageHistoryPref] = "[]" }
    }
}
