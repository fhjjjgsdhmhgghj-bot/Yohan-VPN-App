package com.yohan.vpn.vpn

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * TUN -> SOCKS5 relay for IPv4 TCP (+ DNS UDP outside tunnel).
 * Traffic path: App -> TUN -> SOCKS(127.0.0.1) -> SSH -> Proxy+Payload -> Internet
 */
class Tun2Socks(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int,
    private val protectSocket: ((DatagramSocket) -> Boolean)? = null
) {
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, TcpRelay>()

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
                    Thread.sleep(2)
                    continue
                }
                handlePacket(buf, n)
            } catch (e: Exception) {
                if (running.get()) Log.w("Tun2Socks", "read: ${e.message}")
                break
            }
        }
    }

    private fun handlePacket(raw: ByteArray, len: Int) {
        if (len < 20) return
        if (((raw[0].toInt() ushr 4) and 0x0f) != 4) return
        val ihl = (raw[0].toInt() and 0x0f) * 4
        if (len < ihl) return
        val proto = raw[9].toInt() and 0xff
        val srcIp = ipToString(raw, 12)
        val dstIp = ipToString(raw, 16)
        when (proto) {
            6 -> handleTcp(raw, len, ihl, srcIp, dstIp)
            17 -> handleDns(raw, len, ihl, srcIp, dstIp)
        }
    }

    private fun handleTcp(raw: ByteArray, len: Int, ihl: Int, srcIp: String, dstIp: String) {
        if (len < ihl + 20) return
        val srcPort = u16(raw, ihl)
        val dstPort = u16(raw, ihl + 2)
        val seq = u32(raw, ihl + 4)
        val flags = raw[ihl + 13].toInt() and 0xff
        val doff = ((raw[ihl + 12].toInt() and 0xff) ushr 4) * 4
        val payloadOff = ihl + doff
        val payload = if (len > payloadOff) raw.copyOfRange(payloadOff, len) else ByteArray(0)

        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0

        val key = "$srcIp:$srcPort->$dstIp:$dstPort"

        if (rst) {
            sessions.remove(key)?.close()
            return
        }

        var relay = sessions[key]
        if (relay == null) {
            if (syn && !ack) {
                relay = TcpRelay(
                    clientIp = srcIp,
                    clientPort = srcPort,
                    destIp = dstIp,
                    destPort = dstPort,
                    clientIsn = seq,
                    socksHost = socksHost,
                    socksPort = socksPort,
                    tunOut = tunOut
                )
                sessions[key] = relay
                relay.connectAndRun()
            }
            return
        }

        if (payload.isNotEmpty()) {
            relay.sendFromClient(payload)
        }
        if (fin) {
            relay.close()
            sessions.remove(key)
        }
    }

    private fun handleDns(raw: ByteArray, len: Int, ihl: Int, srcIp: String, dstIp: String) {
        if (len < ihl + 8) return
        val srcPort = u16(raw, ihl)
        val dstPort = u16(raw, ihl + 2)
        if (dstPort != 53) return
        val query = raw.copyOfRange(ihl + 8, len)
        thread(name = "dns", isDaemon = true) {
            try {
                val ds = DatagramSocket()
                protectSocket?.invoke(ds)
                ds.soTimeout = 4000
                // Prefer public DNS even if packet dest differs
                val target = InetSocketAddress("8.8.8.8", 53)
                ds.send(DatagramPacket(query, query.size, target))
                val respBuf = ByteArray(4096)
                val resp = DatagramPacket(respBuf, respBuf.size)
                ds.receive(resp)
                ds.close()
                writeUdpPacket(dstIp, 53, srcIp, srcPort, resp.data.copyOf(resp.length))
            } catch (e: Exception) {
                Log.w("Tun2Socks", "dns fail: ${e.message}")
            }
        }
    }

    private fun writeUdpPacket(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray) {
        val ihl = 20
        val udpLen = 8 + payload.size
        val total = ihl + udpLen
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte()
        pkt[2] = ((total ushr 8) and 0xff).toByte()
        pkt[3] = (total and 0xff).toByte()
        pkt[8] = 64.toByte()
        pkt[9] = 17.toByte()
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        checksumIp(pkt)
        pkt[20] = ((srcPort ushr 8) and 0xff).toByte()
        pkt[21] = (srcPort and 0xff).toByte()
        pkt[22] = ((dstPort ushr 8) and 0xff).toByte()
        pkt[23] = (dstPort and 0xff).toByte()
        pkt[24] = ((udpLen ushr 8) and 0xff).toByte()
        pkt[25] = (udpLen and 0xff).toByte()
        System.arraycopy(payload, 0, pkt, 28, payload.size)
        synchronized(tunOut) {
            tunOut.write(pkt)
            tunOut.flush()
        }
    }

    companion object {
        fun ipToString(b: ByteArray, off: Int) =
            "${b[off].toInt() and 0xff}.${b[off + 1].toInt() and 0xff}." +
                "${b[off + 2].toInt() and 0xff}.${b[off + 3].toInt() and 0xff}"

        fun u16(b: ByteArray, off: Int) =
            ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

        fun u32(b: ByteArray, off: Int) =
            ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)

        fun checksumIp(pkt: ByteArray) {
            pkt[10] = 0
            pkt[11] = 0
            var sum = 0
            var i = 0
            while (i < 20) {
                sum += ((pkt[i].toInt() and 0xff) shl 8) or (pkt[i + 1].toInt() and 0xff)
                i += 2
            }
            while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
            val c = sum.inv() and 0xffff
            pkt[10] = ((c ushr 8) and 0xff).toByte()
            pkt[11] = (c and 0xff).toByte()
        }
    }
}

private class TcpRelay(
    private val clientIp: String,
    private val clientPort: Int,
    private val destIp: String,
    private val destPort: Int,
    private val clientIsn: Int,
    private val socksHost: String,
    private val socksPort: Int,
    private val tunOut: FileOutputStream
) {
    private val alive = AtomicBoolean(true)
    private var sock: Socket? = null
    // our seq starts after SYN-ACK
    private var ourSeq = (System.nanoTime() and 0x7fffffff).toInt()
    private var ourAck = clientIsn + 1

    fun connectAndRun() {
        thread(name = "relay-$destIp:$destPort", isDaemon = true) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(socksHost, socksPort), 12000)
                socks5Connect(s, destIp, destPort)
                sock = s
                // SYN-ACK to phone
                emitTcp(flags = 0x12, payload = ByteArray(0), synConsumes = true)
                // stream remote -> phone
                val input = s.getInputStream()
                val buf = ByteArray(16384)
                while (alive.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        emitTcp(flags = 0x18, payload = buf.copyOf(n), synConsumes = false)
                    }
                }
            } catch (e: Exception) {
                Log.w("TcpRelay", "$destIp:$destPort ${e.message}")
            } finally {
                close()
            }
        }
    }

    fun sendFromClient(data: ByteArray) {
        try {
            sock?.getOutputStream()?.let {
                it.write(data)
                it.flush()
            }
            ourAck += data.size
            emitTcp(flags = 0x10, payload = ByteArray(0), synConsumes = false)
        } catch (_: Exception) {
            close()
        }
    }

    private fun socks5Connect(s: Socket, host: String, port: Int) {
        val inp = s.getInputStream()
        val out = s.getOutputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        if (inp.read() != 0x05 || inp.read() != 0x00) throw Exception("SOCKS greeting")

        val ip = try { InetAddress.getByName(host).address } catch (_: Exception) { null }
        if (ip != null && ip.size == 4) {
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3],
                ((port ushr 8) and 0xff).toByte(), (port and 0xff).toByte()))
        } else {
            val hb = host.toByteArray()
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hb.size.toByte()))
            out.write(hb)
            out.write(byteArrayOf(((port ushr 8) and 0xff).toByte(), (port and 0xff).toByte()))
        }
        out.flush()
        val hdr = ByteArray(4)
        if (inp.read(hdr) < 4 || hdr[1].toInt() != 0) throw Exception("SOCKS refused ${hdr[1]}")
        when (hdr[3].toInt() and 0xff) {
            1 -> repeat(6) { inp.read() }
            3 -> { val l = inp.read(); repeat(l + 2) { inp.read() } }
            4 -> repeat(18) { inp.read() }
        }
    }

    private fun emitTcp(flags: Int, payload: ByteArray, synConsumes: Boolean) {
        if (!alive.get()) return
        try {
            val ihl = 20
            val tcpH = 20
            val total = ihl + tcpH + payload.size
            val pkt = ByteArray(total)
            pkt[0] = 0x45.toByte()
            pkt[2] = ((total ushr 8) and 0xff).toByte()
            pkt[3] = (total and 0xff).toByte()
            pkt[6] = 0x40.toByte()
            pkt[8] = 64.toByte()
            pkt[9] = 6.toByte()
            val src = InetAddress.getByName(destIp).address
            val dst = InetAddress.getByName(clientIp).address
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)
            Tun2Socks.checksumIp(pkt)

            val t = 20
            pkt[t] = ((destPort ushr 8) and 0xff).toByte()
            pkt[t + 1] = (destPort and 0xff).toByte()
            pkt[t + 2] = ((clientPort ushr 8) and 0xff).toByte()
            pkt[t + 3] = (clientPort and 0xff).toByte()
            writeU32(pkt, t + 4, ourSeq)
            writeU32(pkt, t + 8, ourAck)
            pkt[t + 12] = 0x50.toByte()
            pkt[t + 13] = flags.toByte()
            pkt[t + 14] = 0xff.toByte()
            pkt[t + 15] = 0xff.toByte()
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, pkt, t + 20, payload.size)
            }
            val csum = tcpChecksum(pkt, t, tcpH + payload.size, src, dst)
            pkt[t + 16] = ((csum ushr 8) and 0xff).toByte()
            pkt[t + 17] = (csum and 0xff).toByte()

            if (synConsumes) ourSeq += 1
            if (payload.isNotEmpty()) ourSeq += payload.size

            synchronized(tunOut) {
                tunOut.write(pkt)
                tunOut.flush()
            }
        } catch (e: Exception) {
            Log.w("TcpRelay", "emit: ${e.message}")
        }
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }

    private fun tcpChecksum(buf: ByteArray, off: Int, len: Int, src: ByteArray, dst: ByteArray): Int {
        var sum = 0
        sum += ((src[0].toInt() and 0xff) shl 8) or (src[1].toInt() and 0xff)
        sum += ((src[2].toInt() and 0xff) shl 8) or (src[3].toInt() and 0xff)
        sum += ((dst[0].toInt() and 0xff) shl 8) or (dst[1].toInt() and 0xff)
        sum += ((dst[2].toInt() and 0xff) shl 8) or (dst[3].toInt() and 0xff)
        sum += 6
        sum += len
        var i = off
        val end = off + len
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
