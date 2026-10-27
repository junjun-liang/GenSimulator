package com.flyme.auto.gensimulator.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalHttpBridgeServer(
    private val ingress: CreateIngress,
    private val port: Int = 39091,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "gensimulator-bridge-http-client").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        }
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "gensimulator-bridge-http").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (running.get()) {
                val socket = server.accept()
                clientExecutor.execute { handle(socket) }
            }
        } catch (exception: Exception) {
            if (running.get()) throw exception
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val request = runCatching { readRequest(client) }.getOrElse {
                writeResponse(client, 400, "{\"error\":\"INVALID_REQUEST\"}")
                return
            }
            val response = when {
                request.method == "GET" && request.path == "/health" -> BridgeProtocol.health(ingress)
                request.method == "POST" && request.path == "/v1/create" -> BridgeProtocol.create(request.body, ingress)
                request.path == "/health" || request.path == "/v1/create" ->
                    HttpPayload(405, "{\"error\":\"METHOD_NOT_ALLOWED\"}")
                else -> HttpPayload(404, "{\"error\":\"NOT_FOUND\"}")
            }
            writeResponse(client, response.status, response.json)
        }
    }

    private fun readRequest(socket: Socket): HttpRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = ArrayList<Byte>(1024)
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (headerBytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            require(value >= 0) { "Unexpected EOF in headers" }
            val byte = value.toByte()
            headerBytes += byte
            matched = if (byte == delimiter[matched]) matched + 1 else if (byte == delimiter[0]) 1 else 0
            if (matched == delimiter.size) break
        }
        require(matched == delimiter.size) { "HTTP headers too large" }

        val headerText = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.first().split(' ')
        require(requestLine.size >= 2) { "Invalid request line" }
        val headers = lines.drop(1)
            .filter { it.contains(':') }
            .associate { line ->
                val index = line.indexOf(':')
                line.substring(0, index).trim().lowercase(Locale.ROOT) to line.substring(index + 1).trim()
            }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        require(contentLength in 0..MAX_BODY_BYTES) { "Invalid content length" }
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            require(count > 0) { "Unexpected EOF in body" }
            offset += count
        }
        return HttpRequest(
            method = requestLine[0].uppercase(Locale.ROOT),
            path = requestLine[1].substringBefore('?'),
            body = String(bodyBytes, StandardCharsets.UTF_8),
        )
    }

    private fun writeResponse(socket: Socket, status: Int, body: String) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        BufferedOutputStream(socket.getOutputStream()).use { output ->
            output.write(head)
            output.write(payload)
            output.flush()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        clientExecutor.shutdownNow()
        serverSocket = null
        acceptThread = null
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private companion object {
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_BODY_BYTES = 512 * 1024
    }
}
