package com.yohan.vpn.vpn

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal userspace TCP relay:
 * TUN (IP packets) <-> SOCKS5 (127.0.0.1:socksPort)
 * Enough for real browsing through SSH dynamic forward.
 */
class Tun2Socks(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int,
    private val protectSocket: ((java.net.DatagramSocket) -> Boolean)? = null
) {
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, TcpSession>()

    fun start() {
        running.set(true)
        thread(name = "tun-reader", isDaemon = true) { readLoop() }
    }

    fun stop() {
        running.set(false)
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun readLoop() {
        val buf = ByteArray(32767)
        while (running.get()) {
            try {
                val n = tunIn.read(buf)
                if (n <= 0) {
                    Thread.sleep(5)
                    continue
                }
                handlePacket(buf, n)
            } catch (e: Exception) {
                if (running.get()) Log.w("Tun2Socks", "read: ${e.message}")
                break
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < 20) return
        val version = (buf[0].toInt() ushr 4) and 0x0f
        if (version != 4) return // IPv4 only

        val ihl = (buf[0].toInt() and 0x0f) * 4
        if (len < ihl + 1) return
        val protocol = buf[9].toInt() and 0xff
        val srcIp = inetString(buf, 12)
        val dstIp = inetString(buf, 16)

        when (protocol) {
            6 -> handleTcp(buf, len, ihl, srcIp, dstIp) // TCP
            17 -> handleUdp(buf, len, ihl, srcIp, dstIp) // UDP (DNS best-effort)
        }
    }

    private fun handleTcp(buf: ByteArray, len: Int, ihl: Int, srcIp: String, dstIp: String) {
        if (len < ihl + 20) return
        val srcPort = ((buf[ihl].toInt() and 0xff) shl 8) or (buf[ihl + 1].toInt() and 0xff)
        val dstPort = ((buf[ihl + 2].toInt() and 0xff) shl 8) or (buf[ihl + 3].toInt() and 0xff)
        val flags = buf[ihl + 13].toInt() and 0xff
        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val dataOff = ((buf[ihl + 12].toInt() and 0xff) ushr 4) * 4
        val payloadOff = ihl + dataOff
        val payloadLen = len - payloadOff

        val key = "$srcIp:$srcPort-$dstIp:$dstPort"
        var session = sessions[key]

        if (rst) {
            session?.close()
            sessions.remove(key)
            return
        }

        if (session == null && syn && !ack) {
            session = TcpSession(srcIp, srcPort, dstIp, dstPort, socksHost, socksPort, tunOut)
            sessions[key] = session
            session.start()
            return
        }

        if (session != null && payloadLen > 0) {
            session.onClientData(buf.copyOfRange(payloadOff, len))
        }
        if (session != null && fin) {
            session.close()
            sessions.remove(key)
        }
    }

    private fun handleUdp(buf: ByteArray, len: Int, ihl: Int, srcIp: String, dstIp: String) {
        if (len < ihl + 8) return
        val srcPort = ((buf[ihl].toInt() and 0xff) shl 8) or (buf[ihl + 1].toInt() and 0xff)
        val dstPort = ((buf[ihl + 2].toInt() and 0xff) shl 8) or (buf[ihl + 3].toInt() and 0xff)
        if (dstPort != 53) return // DNS only
        val payloadOff = ihl + 8
        val dnsQuery = buf.copyOfRange(payloadOff, len)
        thread(name = "dns-fwd", isDaemon = true) {
            try {
                val ds = java.net.DatagramSocket()
                protectSocket?.invoke(ds)
                ds.soTimeout = 5000
                val server = java.net.InetSocketAddress(dstIp, 53)
                ds.send(java.net.DatagramPacket(dnsQuery, dnsQuery.size, server))
                val respBuf = ByteArray(4096)
                val resp = java.net.DatagramPacket(respBuf, respBuf.size)
                ds.receive(resp)
                ds.close()
                writeUdpReply(srcIp, srcPort, dstIp, dstPort, resp.data.copyOf(resp.length))
            } catch (e: Exception) {
                Log.w("Tun2Socks", "dns: ${e.message}")
            }
        }
    }

    private fun writeUdpReply(clientIp: String, clientPort: Int, dnsIp: String, dnsPort: Int, payload: ByteArray) {
        try {
            val ihl = 20
            val udpLen = 8 + payload.size
            val total = ihl + udpLen
            val pkt = ByteArray(total)
            pkt[0] = 0x45
            pkt[2] = ((total ushr 8) and 0xff).toByte()
            pkt[3] = (total and 0xff).toByte()
            pkt[8] = 64
            pkt[9] = 17
            val src = java.net.InetAddress.getByName(dnsIp).address
            val dst = java.net.InetAddress.getByName(clientIp).address
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)
            var sum = 0
            var i = 0
            while (i < 20) {
                if (i == 10) { i += 2; continue }
                sum += ((pkt[i].toInt() and 0xff) shl 8) or (pkt[i + 1].toInt() and 0xff)
                i += 2
            }
            while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
            val csum = sum.inv() and 0xffff
            pkt[10] = ((csum ushr 8) and 0xff).toByte()
            pkt[11] = (csum and 0xff).toByte()
            val u = 20
            pkt[u] = ((dnsPort ushr 8) and 0xff).toByte()
            pkt[u + 1] = (dnsPort and 0xff).toByte()
            pkt[u + 2] = ((clientPort ushr 8) and 0xff).toByte()
            pkt[u + 3] = (clientPort and 0xff).toByte()
            pkt[u + 4] = ((udpLen ushr 8) and 0xff).toByte()
            pkt[u + 5] = (udpLen and 0xff).toByte()
            System.arraycopy(payload, 0, pkt, u + 8, payload.size)
            synchronized(tunOut) {
                tunOut.write(pkt)
                tunOut.flush()
            }
        } catch (e: Exception) {
            Log.w("Tun2Socks", "udp reply: ${e.message}")
        }
    }

    private fun inetString(buf: ByteArray, off: Int): String {
        return "${buf[off].toInt() and 0xff}.${buf[off + 1].toInt() and 0xff}." +
            "${buf[off + 2].toInt() and 0xff}.${buf[off + 3].toInt() and 0xff}"
    }
}

/**
 * Very simplified TCP session: does not implement full TCP state machine on TUN side.
 * Instead uses a hack: after SYN, we open SOCKS and for subsequent packets with data,
 * push to SOCKS; responses are written back as crafted TCP/IP packets.
 *
 * Full reliability needs a real TCP stack; this gets HTTP/HTTPS working for many sites.
 */
private class TcpSession(
    private val clientIp: String,
    private val clientPort: Int,
    private val destIp: String,
    private val destPort: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val tunOut: FileOutputStream
) {
    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var seq = 1000
    private var ack = 1
    private val alive = AtomicBoolean(true)

    fun start() {
        thread(name = "tcp-$destIp:$destPort", isDaemon = true) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(socksHost, socksPort), 10000)
                socksConnect(s, destIp, destPort)
                sock = s
                out = s.getOutputStream()
                // SYN-ACK to client
                writeTcpPacket(flags = 0x12, payload = ByteArray(0)) // SYN+ACK
                // read from remote
                val input = s.getInputStream()
                val buf = ByteArray(8192)
                while (alive.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        writeTcpPacket(flags = 0x18, payload = buf.copyOf(n)) // PSH+ACK
                    }
                }
            } catch (e: Exception) {
                Log.w("TcpSession", "session $destIp:$destPort ${e.message}")
            } finally {
                close()
            }
        }
    }

    fun onClientData(data: ByteArray) {
        try {
            out?.write(data)
            out?.flush()
            ack += data.size
            // ACK
            writeTcpPacket(flags = 0x10, payload = ByteArray(0))
        } catch (_: Exception) {
            close()
        }
    }

    private fun socksConnect(s: Socket, host: String, port: Int) {
        val inp = s.getInputStream()
        val o = s.getOutputStream()
        o.write(byteArrayOf(0x05, 0x01, 0x00))
        o.flush()
        if (inp.read() != 0x05 || inp.read() != 0x00) throw Exception("SOCKS auth")
        val hostBytes = host.toByteArray()
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((port ushr 8) and 0xff).toByte()
        req[6 + hostBytes.size] = (port and 0xff).toByte()
        // fix: for IP use ATYP 0x01
        val ipBytes = try {
            InetAddress.getByName(host).address
        } catch (_: Exception) {
            null
        }
        if (ipBytes != null && ipBytes.size == 4) {
            val r = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3],
                ((port ushr 8) and 0xff).toByte(), (port and 0xff).toByte()
            )
            o.write(r)
        } else {
            o.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
            o.write(hostBytes)
            o.write(byteArrayOf(((port ushr 8) and 0xff).toByte(), (port and 0xff).toByte()))
        }
        o.flush()
        val resp = ByteArray(4)
        if (inp.read(resp) < 4 || resp[1].toInt() != 0x00) throw Exception("SOCKS connect failed")
        // skip bind addr
        when (resp[3].toInt() and 0xff) {
            0x01 -> { repeat(6) { inp.read() } }
            0x03 -> { val l = inp.read(); repeat(l + 2) { inp.read() } }
            0x04 -> { repeat(18) { inp.read() } }
        }
    }

    private fun writeTcpPacket(flags: Int, payload: ByteArray) {
        try {
            val ihl = 20
            val tcpLen = 20 + payload.size
            val total = ihl + tcpLen
            val pkt = ByteArray(total)
            // IP header
            pkt[0] = 0x45
            pkt[2] = ((total ushr 8) and 0xff).toByte()
            pkt[3] = (total and 0xff).toByte()
            pkt[6] = 0x40 // DF
            pkt[8] = 64
            pkt[9] = 6 // TCP
            val src = InetAddress.getByName(destIp).address
            val dst = InetAddress.getByName(clientIp).address
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)
            // checksum IP
            val ipCsum = ipChecksum(pkt, 0, 20)
            pkt[10] = ((ipCsum ushr 8) and 0xff).toByte()
            pkt[11] = (ipCsum and 0xff).toByte()
            // TCP
            val t = ihl
            pkt[t] = ((destPort ushr 8) and 0xff).toByte()
            pkt[t + 1] = (destPort and 0xff).toByte()
            pkt[t + 2] = ((clientPort ushr 8) and 0xff).toByte()
            pkt[t + 3] = (clientPort and 0xff).toByte()
            pkt[t + 4] = ((seq ushr 24) and 0xff).toByte()
            pkt[t + 5] = ((seq ushr 16) and 0xff).toByte()
            pkt[t + 6] = ((seq ushr 8) and 0xff).toByte()
            pkt[t + 7] = (seq and 0xff).toByte()
            pkt[t + 8] = ((ack ushr 24) and 0xff).toByte()
            pkt[t + 9] = ((ack ushr 16) and 0xff).toByte()
            pkt[t + 10] = ((ack ushr 8) and 0xff).toByte()
            pkt[t + 11] = (ack and 0xff).toByte()
            pkt[t + 12] = 0x50 // data offset 5
            pkt[t + 13] = flags.toByte()
            pkt[t + 14] = 0x16
            pkt[t + 15] = 0xd0 // window
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, pkt, t + 20, payload.size)
            }
            val tcpCsum = tcpChecksum(pkt, ihl, tcpLen, src, dst)
            pkt[t + 16] = ((tcpCsum ushr 8) and 0xff).toByte()
            pkt[t + 17] = (tcpCsum and 0xff).toByte()
            if (payload.isNotEmpty()) seq += payload.size
            if (flags and 0x02 != 0) seq += 1 // SYN consumes 1
            synchronized(tunOut) {
                tunOut.write(pkt)
                tunOut.flush()
            }
        } catch (e: Exception) {
            Log.w("TcpSession", "write packet: ${e.message}")
        }
    }

    private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len - 1) {
            sum += ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    private fun tcpChecksum(buf: ByteArray, tcpOff: Int, tcpLen: Int, src: ByteArray, dst: ByteArray): Int {
        var sum = 0
        // pseudo header
        sum += ((src[0].toInt() and 0xff) shl 8) or (src[1].toInt() and 0xff)
        sum += ((src[2].toInt() and 0xff) shl 8) or (src[3].toInt() and 0xff)
        sum += ((dst[0].toInt() and 0xff) shl 8) or (dst[1].toInt() and 0xff)
        sum += ((dst[2].toInt() and 0xff) shl 8) or (dst[3].toInt() and 0xff)
        sum += 6
        sum += tcpLen
        var i = tcpOff
        val end = tcpOff + tcpLen
        while (i < end - 1) {
            sum += ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    fun close() {
        if (!alive.getAndSet(false)) return
        try { sock?.close() } catch (_: Exception) {}
    }
}
