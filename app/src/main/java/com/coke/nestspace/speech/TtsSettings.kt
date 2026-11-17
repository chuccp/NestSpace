package com.coke.nestspace.speech

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

data class TtsConfig(
    /** [TtsSettings.SYSTEM_PROVIDER_ID] or an enabled plugin speech provider id. */
    val providerId: String = TtsSettings.SYSTEM_PROVIDER_ID,
    val enabled: Boolean = false
)

object TtsSettings {

    const val SYSTEM_PROVIDER_ID = "system"
    const val SYSTEM_PROVIDER_NAME = "System TTS"
    private const val PREFS = "tts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER_ID = "provider_id"

    fun load(context: Context): TtsConfig {
        val prefs = preferences(context)
        val storedProviderId = prefs.getString(KEY_PROVIDER_ID, null)
        val providerId = storedProviderId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SYSTEM_PROVIDER_ID
        return TtsConfig(
            providerId = providerId,
            enabled = prefs.getBoolean(KEY_ENABLED, storedProviderId != null)
        )
    }

    fun save(context: Context, config: TtsConfig) {
        val providerId = config.providerId.trim()
        require(providerId.isNotEmpty()) { "TTS provider id is required" }
        check(
            preferences(context).edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_PROVIDER_ID, providerId)
                .commit()
        ) { "TTS settings could not be saved" }
    }

    fun isSystemProviderAvailable(context: Context): Boolean =
        context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0
        ).isNotEmpty()

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
}

internal object TtsProviderSelection {

    /**
     * An explicit tool argument must resolve to the requested provider. The
     * saved global choice falls back to system TTS when its plugin is missing.
     */
    fun resolve(
        explicitProviderId: String?,
        savedProviderId: String,
        pluginProviders: Set<String>
    ): String {
        val explicit = explicitProviderId?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            return if (explicit == TtsSettings.SYSTEM_PROVIDER_ID ||
                explicit in pluginProviders
            ) {
                explicit
            } else {
                throw SpeechException(
                    "SPEECH_PROVIDER_NOT_FOUND",
                    "Speech provider is not available: $explicit"
                )
            }
        }
        val saved = savedProviderId.trim()
        return when {
            saved == TtsSettings.SYSTEM_PROVIDER_ID -> saved
            saved in pluginProviders -> saved
            else -> TtsSettings.SYSTEM_PROVIDER_ID
        }
    }
}
