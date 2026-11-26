package com.coke.nestspace.integrations.proxy

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A loopback-only SOCKS5 relay. Clients connect without credentials, while the
 * relay authenticates to the configured upstream SOCKS server with RFC 1929.
 * This also lets WebView inherit authenticated SOCKS5 settings, because the
 * WebView proxy API cannot carry credentials itself.
 */
internal class AppSocksRelay(
    private val config: AppProxySettings.Config
) : Closeable {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start(): Int {
        val socket = ServerSocket(
            0,
            50,
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        )
        serverSocket = socket
        acceptThread = Thread(
            { acceptLoop(socket) },
            "nestspace-app-socks"
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
        clients.forEach { runCatching { it.close() } }
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
                "nestspace-app-socks-client"
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
            val clientInput = client.getInputStream()
            val clientOutput = client.getOutputStream()

            val version = clientInput.read()
            val methodCount = clientInput.read()
            if (version != SOCKS_VERSION || methodCount < 0) return
            val methods = ByteArray(methodCount)
            readFully(clientInput, methods)
            if (NO_AUTH.toByte() !in methods) {
                clientOutput.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xff.toByte()))
                clientOutput.flush()
                return
            }
            clientOutput.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
            clientOutput.flush()

            val requestHeader = ByteArray(4)
            readFully(clientInput, requestHeader)
            if (requestHeader[0].toInt() != SOCKS_VERSION ||
                requestHeader[1].toInt() != CONNECT
            ) {
                writeFailure(clientOutput, COMMAND_NOT_SUPPORTED)
                return
            }
            val requestAddress = readAddressBlock(
                clientInput,
                requestHeader[3].toInt() and 0xff
            )

            val nextUpstream = Socket()
            nextUpstream.connect(
                InetSocketAddress(config.host.trim(), config.port),
                CONNECT_TIMEOUT_MILLIS
            )
            nextUpstream.soTimeout = HEADER_TIMEOUT_MILLIS
            upstream = nextUpstream
            val upstreamInput = nextUpstream.getInputStream()
            val upstreamOutput = nextUpstream.getOutputStream()

            val offeredMethods = if (config.authenticationEnabled) {
                byteArrayOf(
                    SOCKS_VERSION.toByte(),
                    2,
                    NO_AUTH.toByte(),
                    USER_PASSWORD.toByte()
                )
            } else {
                byteArrayOf(SOCKS_VERSION.toByte(), 1, NO_AUTH.toByte())
            }
            upstreamOutput.write(offeredMethods)
            upstreamOutput.flush()
            val upstreamReply = ByteArray(2)
            readFully(upstreamInput, upstreamReply)
            if (upstreamReply[0].toInt() != SOCKS_VERSION) {
                throw IOException("Invalid SOCKS5 greeting reply from upstream")
            }
            when (upstreamReply[1].toInt() and 0xff) {
                NO_AUTH -> Unit
                USER_PASSWORD -> authenticateUpstream(upstreamInput, upstreamOutput)
                else -> throw IOException("SOCKS5 upstream rejected authentication methods")
            }

            upstreamOutput.write(requestHeader)
            upstreamOutput.write(requestAddress)
            upstreamOutput.flush()

            val connectReply = ByteArray(4)
            readFully(upstreamInput, connectReply)
            val replyAddress = readAddressBlock(
                upstreamInput,
                connectReply[3].toInt() and 0xff
            )
            clientOutput.write(connectReply)
            clientOutput.write(replyAddress)
            clientOutput.flush()
            if (connectReply[1].toInt() != SUCCESS) return

            client.soTimeout = 0
            nextUpstream.soTimeout = 0
            Thread(
                {
                    runCatching {
                        copy(upstreamInput, clientOutput)
                    }.onFailure {
                        Log.d("AppSocksRelay", "downstream tunnel closed", it)
                    }
                    closeQuietly(client)
                    closeQuietly(nextUpstream)
                },
                "nestspace-app-socks-downstream"
            ).apply {
                isDaemon = true
                start()
            }
            copy(clientInput, upstreamOutput)
        } catch (error: Throwable) {
            Log.w("AppSocksRelay", "SOCKS connection ended with an error", error)
        } finally {
            closeQuietly(client)
            upstream?.let(::closeQuietly)
            clients -= client
        }
    }

    private fun authenticateUpstream(
        input: InputStream,
        output: OutputStream
    ) {
        val username = config.username.toByteArray(Charsets.UTF_8)
        val password = config.password.toByteArray(Charsets.UTF_8)
        require(username.size in 1..255 && password.size <= 255) {
            "SOCKS5 username or password is too long"
        }
        output.write(USER_PASSWORD_VERSION)
        output.write(username.size)
        output.write(username)
        output.write(password.size)
        output.write(password)
        output.flush()
        val reply = ByteArray(2)
        readFully(input, reply)
        if (reply[1].toInt() != SUCCESS) {
            throw IOException("SOCKS5 username/password authentication failed")
        }
    }

    private fun readAddressBlock(input: InputStream, addressType: Int): ByteArray {
        return when (addressType) {
            ADDRESS_IPV4 -> ByteArray(4 + 2).also { readFully(input, it) }
            ADDRESS_DOMAIN -> {
                val length = input.read()
                if (length < 0) throw IOException("Missing SOCKS5 domain length")
                ByteArray(1 + length + 2).also {
                    it[0] = length.toByte()
                    readFully(input, it, 1, length + 2)
                }
            }
            ADDRESS_IPV6 -> ByteArray(16 + 2).also { readFully(input, it) }
            else -> throw IOException("Unsupported SOCKS5 address type: $addressType")
        }
    }

    private fun writeFailure(output: OutputStream, code: Int) {
        runCatching {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(),
                    code.toByte(),
                    0,
                    ADDRESS_IPV4.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                )
            )
            output.flush()
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        readFully(input, target, 0, target.size)
    }

    private fun readFully(
        input: InputStream,
        target: ByteArray,
        offset: Int,
        length: Int
    ) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count < 0) throw IOException("Unexpected end of stream")
            read += count
        }
    }

    private fun closeQuietly(socket: Socket?) {
        runCatching { socket?.close() }
    }

    companion object {
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0
        private const val USER_PASSWORD = 2
        private const val USER_PASSWORD_VERSION = 1
        private const val CONNECT = 1
        private const val SUCCESS = 0
        private const val COMMAND_NOT_SUPPORTED = 7
        private const val ADDRESS_IPV4 = 1
        private const val ADDRESS_DOMAIN = 3
        private const val ADDRESS_IPV6 = 4
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val HEADER_TIMEOUT_MILLIS = 30_000
        private const val BUFFER_SIZE = 16 * 1024
    }
}
