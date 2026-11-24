package com.coke.nestspace.container.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetPlaybackUiBridgeTest {

    @Test
    fun formatsPlayerClockValues() {
        assertEquals("00:00", TargetPlaybackUiBridge.formatPosition(0))
        assertEquals("00:41", TargetPlaybackUiBridge.formatPosition(41_999))
        assertEquals("04:06", TargetPlaybackUiBridge.formatPosition(246_019))
        assertEquals("1:01:01", TargetPlaybackUiBridge.formatPosition(3_661_000))
    }
}
