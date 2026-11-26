package com.coke.nestspace.core.io

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LimitedStreamsTest {

    @Test
    fun readsExactlyToLimit() {
        val bytes = ByteArrayInputStream("12345".toByteArray())
            .readBytesLimited(5) { error("unexpected limit") }

        assertArrayEquals("12345".toByteArray(), bytes)
    }

    @Test
    fun invokesLimitHandlerBeforeExceedingBuffer() {
        val stream = ByteArrayInputStream("123456".toByteArray())

        val error = assertThrows(IllegalStateException::class.java) {
            stream.readBytesLimited(5) { error("too large") }
        }

        assertEquals("too large", error.message)
    }
}
