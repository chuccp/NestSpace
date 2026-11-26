package com.coke.nestspace.integrations.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import android.util.Log

/**
 * A loopback-only HTTP relay used so both HTTP and TLS proxy endpoints can be
 * consumed by Android's ProxySelector, which only exposes Proxy.Type.HTTP.
 */
internal class AppProxyRelay(
    private val config: AppProxySettings.Config,
    private val type: String
) : Closeable {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start(): Int {
        val socket = ServerSocket(
            0,
            50,
            LOOPBACK_ADDRESS
        )
        serverSocket = socket
        acceptThread = Thread(
            { acceptLoop(socket) },
            "nestspace-app-proxy"
        ).apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        // Relay threads remove closed clients concurrently; a size-snapshot
        // iteration can otherwise fail when a client disappears mid-copy.
        clients.forEach(::closeQuietly)
        clients.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            clients += client
            Thread(
                { relay(client) },
                "nestspace-app-proxy-client"
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun relay(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = HEADER_TIMEOUT_MILLIS
            val header = readHeaders(client) ?: return
            upstream = connectUpstream()
            upstream.soTimeout = HEADER_TIMEOUT_MILLIS
            upstream.getOutputStream().apply {
                write(withProxyAuthorization(header))
                flush()
            }

            val responseHeader = readHeaders(upstream) ?: return
            client.getOutputStream().apply {
                write(responseHeader)
                flush()
            }

            client.soTimeout = 0
            upstream.soTimeout = 0
            val upstreamSocket = upstream
            Thread(
                {
                    runCatching {
                        copy(
                            upstreamSocket.getInputStream(),
                            client.getOutputStream()
                        )
                    }.onFailure {
                        // A proxy tunnel can close concurrently from either side.
                        // This background thread must never take down the target app.
                        Log.d("AppProxyRelay", "downstream tunnel closed", it)
                    }
                    closeQuietly(client)
                    closeQuietly(upstreamSocket)
                },
                "nestspace-app-proxy-downstream"
            ).apply {
                isDaemon = true
                start()
            }
            copy(client.getInputStream(), upstreamSocket.getOutputStream())
        } catch (error: Throwable) {
            Log.w("AppProxyRelay", "proxy connection ended with an error", error)
        } finally {
            closeQuietly(client)
            upstream?.let(::closeQuietly)
            clients -= client
        }
    }

    private fun connectUpstream(): Socket {
        val rawSocket = Socket()
        rawSocket.connect(
            InetSocketAddress(config.host.trim(), config.port),
            CONNECT_TIMEOUT_MILLIS
        )
        if (type != AppProxySettings.TYPE_HTTPS) return rawSocket
        return try {
            val host = config.host.trim()
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tlsSocket = factory
                .createSocket(rawSocket, host, config.port, true) as SSLSocket
            tlsSocket.sslParameters = SSLParameters().apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tlsSocket.startHandshake()
            tlsSocket
        } catch (error: Throwable) {
            closeQuietly(rawSocket)
            throw error
        }
    }

    private fun readHeaders(socket: Socket): ByteArray? {
        val input = socket.getInputStream()
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            output.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return output.toByteArray()
        }
        return null
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Either side closing is the normal way a proxy tunnel ends.
        }
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun withProxyAuthorization(header: ByteArray): ByteArray {
        if (!config.authenticationEnabled) return header
        val text = header.toString(Charsets.ISO_8859_1)
        if (text.lineSequence().any {
                it.trimStart().startsWith("Proxy-Authorization:", ignoreCase = true)
            }
        ) {
            return header
        }
        val headersEnd = text.indexOf("\r\n\r\n")
        if (headersEnd < 0) return header
        val authorization = "\r\nProxy-Authorization: " +
            basicAuthorizationHeader(config.username, config.password)
        return (
            text.substring(0, headersEnd) +
                authorization +
                text.substring(headersEnd)
            ).toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        internal fun basicAuthorizationHeader(username: String, password: String): String {
            val credentials = "$username:$password".toByteArray(Charsets.UTF_8)
            return "Basic ${base64Encode(credentials)}"
        }

        private fun base64Encode(input: ByteArray): String {
            val alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val output = StringBuilder((input.size + 2) / 3 * 4)
            var index = 0
            while (index + 2 < input.size) {
                val value = (input[index].toInt() and 0xff shl 16) or
                    (input[index + 1].toInt() and 0xff shl 8) or
                    (input[index + 2].toInt() and 0xff)
                output.append(alphabet[value ushr 18 and 0x3f])
                output.append(alphabet[value ushr 12 and 0x3f])
                output.append(alphabet[value ushr 6 and 0x3f])
                output.append(alphabet[value and 0x3f])
                index += 3
            }
            when (input.size - index) {
                1 -> {
                    val value = input[index].toInt() and 0xff shl 16
                    output.append(alphabet[value ushr 18 and 0x3f])
                    output.append(alphabet[value ushr 12 and 0x3f])
                    output.append("==")
                }

                2 -> {
                    val value = (input[index].toInt() and 0xff shl 16) or
                        (input[index + 1].toInt() and 0xff shl 8)
                    output.append(alphabet[value ushr 18 and 0x3f])
                    output.append(alphabet[value ushr 12 and 0x3f])
                    output.append(alphabet[value ushr 6 and 0x3f])
                    output.append('=')
                }
            }
            return output.toString()
        }

        // Some apps replace OkHttp's DNS resolver, but still try to resolve the
        // proxy address by name. Their resolvers may reject the IPv6 literal
        // "::1", so keep the local relay on IPv4 for compatibility.
        internal val LOOPBACK_ADDRESS: InetAddress =
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val HEADER_TIMEOUT_MILLIS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val BUFFER_SIZE = 16 * 1024
    }
}
