package com.nebulaai.app.data

import com.nebulaai.app.BuildConfig

/**
 * Central place for API key management.
 * Keys come from (in priority order):
 *   1) Runtime values set by the user in Settings (stored in DataStore)
 *   2) BuildConfig defaults (from local.properties or env vars at build time)
 *
 * The ViewModel will read from DataStore and pass keys to the repository.
 * This object just holds BuildConfig defaults as fallbacks.
 */
object ApiKeyDefaults {
    val defaultChatKey: String get() = BuildConfig.DEFAULT_CHATKEY
    val defaultPicKey: String get() = BuildConfig.DEFAULT_PICKEY
}
