package com.linkbrowser

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * LocalProxyServer — запускает HTTP/HTTPS прокси на localhost.
 *
 * WebView настраивается на localhost:localPort.
 * Сервер пересылает запросы через внешний прокси (upstreamHost:upstreamPort).
 *
 * HTTP  → меняем заголовки и шлём на внешний прокси напрямую
 * HTTPS → шлём CONNECT на внешний прокси, затем туннелируем байты
 */
class LocalProxyServer(
    private val localPort: Int = 8888
) {
    private var serverSocket: ServerSocket? = null
    private var running = false
    private val pool = Executors.newCachedThreadPool()

    var upstreamHost: String = ""
    var upstreamPort: Int = 8080

    fun start() {
        if (running) return
        running = true
        serverSocket = ServerSocket(localPort)
        pool.execute { acceptLoop() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            try {
                val client = ss.accept()
                pool.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15000
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // Read first line: e.g. "CONNECT host:443 HTTP/1.1" or "GET http://... HTTP/1.1"
            val firstLine = readLine(inp) ?: return
            val headers = mutableListOf<String>()
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                headers.add(h)
            }

            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                handleHttps(client, out, target)
            } else {
                handleHttp(client, inp, out, firstLine, headers, target)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── HTTPS: send CONNECT to upstream, then pipe bytes ────────────────────
    private fun handleHttps(client: Socket, clientOut: OutputStream, target: String) {
        if (upstreamHost.isEmpty()) {
            // No upstream — connect directly
            val (host, port) = parseHostPort(target, 443)
            val remote = Socket()
            remote.connect(InetSocketAddress(host, port), 10000)
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            pipe(client, remote)
            return
        }

        // Connect to upstream proxy
        val upstream = Socket()
        upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 10000)
        val upIn  = upstream.getInputStream()
        val upOut = upstream.getOutputStream()

        // Send CONNECT to upstream
        upOut.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray())
        upOut.flush()

        // Read upstream response
        val response = readLine(upIn) ?: ""
        // Drain upstream headers
        while (true) { if ((readLine(upIn) ?: "").isEmpty()) break }

        if (response.contains("200")) {
            // Tell client tunnel is ready
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            pipe(client, upstream)
        } else {
            // Upstream refused CONNECT — try direct
            try { upstream.close() } catch (_: Exception) {}
            val (host, port) = parseHostPort(target, 443)
            try {
                val direct = Socket()
                direct.connect(InetSocketAddress(host, port), 10000)
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()
                pipe(client, direct)
            } catch (_: Exception) {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                clientOut.flush()
            }
        }
    }

    // ── HTTP: forward request to upstream proxy ──────────────────────────────
    private fun handleHttp(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        firstLine: String,
        headers: List<String>,
        target: String
    ) {
        if (upstreamHost.isEmpty()) {
            // No upstream — connect directly to host from URL
            val url = java.net.URL(target)
            val host = url.host
            val port = if (url.port == -1) 80 else url.port
            val path = if (url.file.isEmpty()) "/" else url.file

            val remote = Socket()
            remote.connect(InetSocketAddress(host, port), 10000)
            val remOut = remote.getOutputStream()

            // Rewrite first line to relative path
            val newFirstLine = "${firstLine.split(" ")[0]} $path ${firstLine.split(" ").last()}"
            val sb = StringBuilder()
            sb.append("$newFirstLine\r\n")
            for (h in headers) {
                if (h.startsWith("Proxy-", ignoreCase = true)) continue
                sb.append("$h\r\n")
            }
            sb.append("\r\n")
            remOut.write(sb.toString().toByteArray())

            // Forward remaining body bytes from client, then pipe response back
            pipeOnce(clientIn, remOut, remote.getOutputStream())
            pipeOnce(remote.getInputStream(), clientOut, clientOut)
            remote.close()
            return
        }

        // Forward entire original request to upstream proxy as-is
        val upstream = Socket()
        upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 10000)
        val upOut = upstream.getOutputStream()

        val sb = StringBuilder()
        sb.append("$firstLine\r\n")
        for (h in headers) sb.append("$h\r\n")
        sb.append("\r\n")
        upOut.write(sb.toString().toByteArray())
        upOut.flush()

        // Copy remaining request body
        val contentLength = headers
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = clientIn.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            upOut.write(buf, 0, read)
            upOut.flush()
        }

        // Pipe upstream response back to client
        pipeStream(upstream.getInputStream(), clientOut)
        upstream.close()
    }

    // ── Bidirectional pipe (for CONNECT tunnels) ─────────────────────────────
    private fun pipe(a: Socket, b: Socket) {
        val t1 = Thread { pipeStream(a.getInputStream(), b.getOutputStream()) }
        val t2 = Thread { pipeStream(b.getInputStream(), a.getOutputStream()) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
    }

    private fun pipeStream(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    private fun pipeOnce(inp: InputStream, out1: OutputStream, out2: OutputStream) {
        val buf = ByteArray(8192)
        try {
            val n = inp.read(buf)
            if (n > 0) { out1.write(buf, 0, n); out1.flush() }
        } catch (_: Exception) {}
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        try {
            var prev = -1
            while (true) {
                val c = inp.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code && prev == '\r'.code) {
                    return sb.dropLast(1).toString()
                }
                sb.append(c.toChar())
                prev = c
            }
        } catch (_: Exception) {
            return if (sb.isEmpty()) null else sb.toString()
        }
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val idx = target.lastIndexOf(':')
        return if (idx >= 0) {
            val host = target.substring(0, idx)
            val port = target.substring(idx + 1).toIntOrNull() ?: defaultPort
            host to port
        } else {
            target to defaultPort
        }
    }
}
