package com.yohan.vpn.ssh

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Matches DarkTunnel injector payload:
 * CONNECT [host_port] [protocol][crlf]Host: api.Snapchat.com[crlf][crlf]
 * → CONNECT host:port HTTP/1.1\r\nHost: payloadHost\r\n\r\n
 */
object ProxyPayloadSocket {

    fun connectViaProxy(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payloadHost: String,
        timeoutMs: Int = 20000
    ): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
        socket.soTimeout = timeoutMs

        // Exact DarkTunnel expansion
        val payload =
            "CONNECT $sshHost:$sshPort HTTP/1.1\r\n" +
            "Host: $payloadHost\r\n" +
            "\r\n"

        val out = socket.getOutputStream()
        out.write(payload.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val header = readHttpHeader(socket.getInputStream())
        val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!first.contains("200")) {
            socket.close()
            throw Exception("البروكسي: $first")
        }
        socket.soTimeout = 0
        return socket
    }

    fun connectDirect(sshHost: String, sshPort: Int, timeoutMs: Int = 15000): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(sshHost, sshPort), timeoutMs)
        socket.soTimeout = 0
        return socket
    }

    private fun readHttpHeader(input: InputStream): String {
        val buf = StringBuilder()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code && buf.endsWith("\r\n\r\n")) break
            prev = b
            if (buf.length > 8192) break
        }
        return buf.toString()
    }
}
