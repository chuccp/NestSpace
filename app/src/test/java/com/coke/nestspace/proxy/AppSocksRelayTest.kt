package com.coke.nestspace.proxy

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSocksRelayTest {
    @Test
    fun authenticatesUpstreamAndRelaysConnectTunnel() {
        val upstream = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                assertEquals(5, input.read())
                val methodCount = input.read()
                val methods = readBytes(input, methodCount)
                assertTrue(methods.contains(2.toByte()))
                output.write(byteArrayOf(5, 2))
                output.flush()

                assertEquals(1, input.read())
                val username = readBytes(input, input.read())
                    .toString(StandardCharsets.UTF_8)
                val password = readBytes(input, input.read())
                    .toString(StandardCharsets.UTF_8)
                assertEquals("user", username)
                assertEquals("pass", password)
                output.write(byteArrayOf(1, 0))
                output.flush()

                val request = readBytes(input, 4)
                assertArrayEquals(byteArrayOf(5, 1, 0, 3), request)
                val domain = readBytes(input, input.read())
                    .toString(StandardCharsets.UTF_8)
                assertEquals("example.test", domain)
                assertEquals(443, readBytes(input, 2).let {
                    (it[0].toInt() and 0xff shl 8) or (it[1].toInt() and 0xff)
                })
                output.write(
                    byteArrayOf(
                        5, 0, 0, 1, 127, 0, 0, 1, 0, 80
                    )
                )
                output.flush()

                val payload = readBytes(input, 4)
                output.write(payload)
                output.flush()
            }
        }.apply { start() }

        val relay = AppSocksRelay(
            AppProxySettings.Config(
                enabled = true,
                type = AppProxySettings.TYPE_SOCKS,
                host = "127.0.0.1",
                port = upstream.localPort,
                authenticationEnabled = true,
                username = "user",
                password = "pass"
            )
        )
        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val output = client.getOutputStream()
                output.write(byteArrayOf(5, 1, 0))
                output.flush()
                assertArrayEquals(byteArrayOf(5, 0), readBytes(input, 2))

                val domain = "example.test".toByteArray(StandardCharsets.UTF_8)
                output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()))
                output.write(domain)
                output.write(byteArrayOf(1, 0xbb.toByte()))
                output.flush()

                val reply = readBytes(input, 10)
                assertEquals(5, reply[0].toInt())
                assertEquals(0, reply[1].toInt())
                output.write("ping".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                assertEquals(
                    "ping",
                    readBytes(input, 4).toString(StandardCharsets.US_ASCII)
                )
            }
        } finally {
            relay.close()
            upstream.close()
            upstreamThread.join(5_000)
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) error("Unexpected end of stream")
            offset += read
        }
        return result
    }
}
