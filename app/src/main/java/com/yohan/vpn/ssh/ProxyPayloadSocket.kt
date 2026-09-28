package com.yohan.vpn.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object ProxyPayloadSocket {

    /**
     * Tries several payload styles used by Dark Tunnel / HTTP Injector.
     * Returns an open socket ready for SSH, or throws with last error.
     */
    fun connect(
        proxyHost: String,
        proxyPort: Int,
        sshHost: String,
        sshPort: Int,
        payloadHost: String,
        timeoutMs: Int = 15000
    ): Socket {
        val payloads = listOf(
            // Standard HTTP CONNECT with payload Host
            "CONNECT $sshHost:$sshPort HTTP/1.1\r\nHost: $payloadHost\r\nProxy-Connection: Keep-Alive\r\n\r\n",
            // Injector style: host_port + protocol placeholders expanded
            "CONNECT $sshHost:$sshPort HTTP/1.1\r\nHost: $payloadHost\r\n\r\n",
            // Some injectors want target in Host only
            "CONNECT $payloadHost:443 HTTP/1.1\r\nHost: $payloadHost\r\n\r\n",
            // Split style
            "CONNECT $sshHost:$sshPort HTTP/1.0\r\nHost: $payloadHost\r\n\r\n"
        )

        var lastError: Exception = Exception("proxy failed")
        for (payload in payloads) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                out.write(payload.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val header = readHttpHeader(socket.getInputStream())
                val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
                if (first.contains("200")) {
                    socket.soTimeout = 0
                    return socket
                }
                socket.close()
                lastError = Exception("البروكسي رفض: $first")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    /** Direct TCP to SSH host (no proxy) — fallback */
    fun connectDirect(sshHost: String, sshPort: Int = 22, timeoutMs: Int = 15000): Socket {
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
