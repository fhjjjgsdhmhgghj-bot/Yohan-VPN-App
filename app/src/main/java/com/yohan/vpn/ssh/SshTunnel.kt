package com.yohan.vpn.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Real SSH over pre-connected proxy socket.
 * Runs a local SOCKS5 server that opens SSH direct-tcpip channels.
 */
class SshTunnel {

    @Volatile private var session: Session? = null
    @Volatile private var socksServer: ServerSocket? = null
    @Volatile private var running = false
    var localSocksPort: Int = 0
        private set

    fun connect(
        tunnelSocket: Socket,
        sshHost: String,
        username: String,
        password: String,
        preferredSocksPort: Int = 18080
    ): Int {
        val jsch = JSch()
        val sess = jsch.getSession(username, sshHost, 22)
        sess.setPassword(password)
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        sess.setConfig("MaxAuthTries", "3")
        sess.timeout = 45000
        sess.setServerAliveInterval(15000)

        sess.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String?, port: Int): Socket = tunnelSocket
            override fun getInputStream(s: Socket): InputStream = s.getInputStream()
            override fun getOutputStream(s: Socket): OutputStream = s.getOutputStream()
        })

        sess.connect(45000)
        if (!sess.isConnected) throw Exception("SSH session not connected")

        session = sess

        // Bind local SOCKS5
        val server = ServerSocket(preferredSocksPort, 50, InetAddress.getByName("127.0.0.1"))
        socksServer = server
        localSocksPort = server.localPort
        running = true

        thread(name = "socks5-accept", isDaemon = true) {
            while (running) {
                try {
                    val client = server.accept()
                    thread(name = "socks5-client", isDaemon = true) {
                        handleSocksClient(client)
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
        return localSocksPort
    }

    private fun handleSocksClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 greeting
            val ver = input.read()
            if (ver != 0x05) { client.close(); return }
            val nMethods = input.read()
            if (nMethods < 0) { client.close(); return }
            repeat(nMethods) { input.read() }
            // no auth
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // request
            val req = ByteArray(4)
            if (input.read(req) < 4) { client.close(); return }
            if (req[0].toInt() != 0x05 || req[1].toInt() != 0x01) {
                // only CONNECT
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }
            val atyp = req[3].toInt() and 0xff
            val destHost: String
            val destPort: Int
            when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.read(addr)
                    destHost = addr.joinToString(".") { (it.toInt() and 0xff).toString() }
                    destPort = (input.read() shl 8) or input.read()
                }
                0x03 -> { // domain
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    destHost = String(domain)
                    destPort = (input.read() shl 8) or input.read()
                }
                else -> {
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    client.close(); return
                }
            }

            val sess = session
            if (sess == null || !sess.isConnected) {
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }

            val channel = sess.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(0)
            channel.connect(15000)

            // success reply
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            val remoteIn = channel.inputStream
            val remoteOut = channel.outputStream

            val t1 = thread(isDaemon = true) { pump(input, remoteOut) }
            pump(remoteIn, output)
            t1.join(1000)
            try { channel.disconnect() } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        }
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun disconnect() {
        running = false
        try { socksServer?.close() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        socksServer = null
        session = null
    }
}
