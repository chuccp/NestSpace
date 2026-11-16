package com.coke.nestspace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TtsProviderSelectionTest {

    @Test
    fun `system tts is the default and saved plugin selection is honored`() {
        assertEquals(
            TtsSettings.SYSTEM_PROVIDER_ID,
            TtsProviderSelection.resolve(
                explicitProviderId = null,
                savedProviderId = TtsSettings.SYSTEM_PROVIDER_ID,
                pluginProviders = setOf("plugin.tts")
            )
        )
        assertEquals(
            "plugin.tts",
            TtsProviderSelection.resolve(
                explicitProviderId = null,
                savedProviderId = "plugin.tts",
                pluginProviders = setOf("plugin.tts")
            )
        )
    }

    @Test
    fun `explicit provider must exist but stale saved provider falls back to system`() {
        assertEquals(
            "plugin.tts",
            TtsProviderSelection.resolve(
                explicitProviderId = "plugin.tts",
                savedProviderId = TtsSettings.SYSTEM_PROVIDER_ID,
                pluginProviders = setOf("plugin.tts")
            )
        )
        assertEquals(
            TtsSettings.SYSTEM_PROVIDER_ID,
            TtsProviderSelection.resolve(
                explicitProviderId = null,
                savedProviderId = "missing.plugin",
                pluginProviders = emptySet()
            )
        )
        assertThrows(SpeechException::class.java) {
            TtsProviderSelection.resolve(
                explicitProviderId = "missing.plugin",
                savedProviderId = TtsSettings.SYSTEM_PROVIDER_ID,
                pluginProviders = emptySet()
            )
        }
    }
}
