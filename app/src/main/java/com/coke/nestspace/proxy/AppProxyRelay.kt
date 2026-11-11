package com.coke.nestspace.proxy

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
            InetAddress.getLoopbackAddress()
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
        clients.toList().forEach(::closeQuietly)
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
                write(header)
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
                    copy(
                        upstreamSocket.getInputStream(),
                        client.getOutputStream()
                    )
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

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val HEADER_TIMEOUT_MILLIS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val BUFFER_SIZE = 16 * 1024
    }
}
