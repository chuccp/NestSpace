package com.coke.nestspace.proxy

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProxyRelayTest {
    @Test
    fun forwardsHttpRequestsToConfiguredProxy() {
        val upstream = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                assertTrue(readHeaders(socket).contains("GET http://example.test/"))
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                socket.getOutputStream().flush()
            }
        }.apply { start() }
        val relay = AppProxyRelay(
            AppProxySettings.Config(
                enabled = true,
                host = "127.0.0.1",
                port = upstream.localPort
            ),
            AppProxySettings.TYPE_HTTP
        )
        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().write(
                    (
                        "GET http://example.test/ HTTP/1.1\r\n" +
                            "Host: example.test\r\nConnection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                )
                client.getOutputStream().flush()
                val response = client.getInputStream()
                    .readBytes()
                    .toString(StandardCharsets.US_ASCII)
                assertTrue(response.startsWith("HTTP/1.1 200 OK"))
                assertTrue(response.endsWith("ok"))
            }
        } finally {
            relay.close()
            upstream.close()
            upstreamThread.join(5_000)
        }
    }

    @Test
    fun forwardsConnectTunnelsForHttpsRequests() {
        val upstream = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        val upstreamThread = Thread {
            upstream.accept().use { socket ->
                socket.soTimeout = 5_000
                assertTrue(readHeaders(socket).contains("CONNECT example.test:443"))
                socket.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                socket.getOutputStream().flush()
                val payload = socket.getInputStream().readNBytes(4)
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            }
        }.apply { start() }
        val relay = AppProxyRelay(
            AppProxySettings.Config(
                enabled = true,
                host = "127.0.0.1",
                port = upstream.localPort
            ),
            AppProxySettings.TYPE_HTTP
        )
        try {
            val relayPort = relay.start()
            Socket(InetAddress.getLoopbackAddress(), relayPort).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().write(
                    (
                        "CONNECT example.test:443 HTTP/1.1\r\n" +
                            "Host: example.test:443\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                )
                client.getOutputStream().flush()
                assertTrue(
                    readHeaders(client).startsWith(
                        "HTTP/1.1 200 Connection Established"
                    )
                )
                client.getOutputStream().write("ping".toByteArray(StandardCharsets.US_ASCII))
                client.getOutputStream().flush()
                val echoed = client.getInputStream().readNBytes(4)
                    .toString(StandardCharsets.US_ASCII)
                assertEquals("ping", echoed)
            }
        } finally {
            relay.close()
            upstream.close()
            upstreamThread.join(5_000)
        }
    }

    private fun readHeaders(socket: Socket): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = socket.getInputStream().read()
            if (next < 0) break
            output.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
        }
        return output.toString(StandardCharsets.US_ASCII.name())
    }
}
