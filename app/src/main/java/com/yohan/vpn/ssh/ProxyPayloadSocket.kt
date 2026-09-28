package com.yohan.vpn.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connects to HTTP proxy and sends custom CONNECT payload
 * (Host: youtube.com / api.Snapchat.com style) then returns the tunnelled socket.
 */
object ProxyPayloadSocket {

    fun connect(
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

        val out: OutputStream = socket.getOutputStream()
        val payload = buildString {
            append("CONNECT $sshHost:$sshPort HTTP/1.1\r\n")
            append("Host: $payloadHost\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            append("\r\n")
        }
        out.write(payload.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val input: InputStream = socket.getInputStream()
        val header = readHttpHeader(input)
        if (!header.contains("200")) {
            socket.close()
            throw Exception("Proxy CONNECT failed: ${header.lines().firstOrNull() ?: header}")
        }
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
            if (prev == '\r'.code && b == '\n'.code) {
                // end of headers: \r\n\r\n
                if (buf.endsWith("\r\n\r\n")) break
            }
            prev = b
            if (buf.length > 8192) break
        }
        return buf.toString()
    }
}
