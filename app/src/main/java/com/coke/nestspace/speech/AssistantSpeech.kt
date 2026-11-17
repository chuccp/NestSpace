package com.coke.nestspace.speech

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared state for automatic final-result speech and the per-message voice
 * button. SpeechGateway remains the only playback implementation.
 */
object AssistantSpeech {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeKey = MutableStateFlow<Int?>(null)
    val activeKey: StateFlow<Int?> = _activeKey.asStateFlow()

    fun key(text: String): Int = speakableText(text).hashCode()

    suspend fun speak(
        context: Context,
        text: String,
        providerId: String,
        interrupt: Boolean = true
    ): SpeechEnqueueResult? {
        val value = speakableText(text)
        if (value.isBlank()) return null
        val key = value.hashCode()
        val result = SpeechGateway.speak(
            context = context.applicationContext,
            text = value,
            providerId = providerId,
            interrupt = interrupt
        )
        _activeKey.value = key
        scope.launch {
            var seenActive = false
            val expectedMs = result.durationMs.takeIf { it > 0 }
                ?: estimatedDurationMs(value)
            val deadline = System.currentTimeMillis() +
                expectedMs.coerceAtLeast(1_000L) + 1_500L
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                val active = SpeechGateway.status().activeRequestId == result.requestId
                if (active) seenActive = true
                if (seenActive && !active) {
                    _activeKey.compareAndSet(key, null)
                    return@launch
                }
            }
            _activeKey.compareAndSet(key, null)
        }
        return result
    }

    fun stop() {
        SpeechGateway.stopAll()
        _activeKey.value = null
    }

    fun speakableText(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("[*_~`>#]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(TextToSpeechLimit)
    }

    private fun estimatedDurationMs(text: String): Long {
        val chinese = text.count { it.code in 0x3400..0x9FFF }
        val other = (text.length - chinese).coerceAtLeast(0)
        return (chinese * 240L + other * 70L).coerceIn(900L, 180_000L)
    }

    private const val TextToSpeechLimit = 20_000
}
