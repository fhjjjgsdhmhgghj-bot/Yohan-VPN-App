package com.yohan.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yohan.vpn.ssh.ProxyPayloadSocket
import com.yohan.vpn.ssh.SshTunnel
import com.yohan.vpn.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class YohanVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.yohan.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.yohan.vpn.DISCONNECT"
        const val EXTRA_HOST = "host"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_SSH_PORT = "ssh_port"
        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_PAYLOAD_HOST = "payload_host"

        @Volatile var isRunning = false
            private set
        @Volatile var statusText = "غير متصل"
            private set
        var onStatus: ((String) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunnel: SshTunnel? = null
    private var tun2socks: Tun2Socks? = null
    private val active = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val user = intent.getStringExtra(EXTRA_USER) ?: return START_NOT_STICKY
                val pass = intent.getStringExtra(EXTRA_PASS) ?: return START_NOT_STICKY
                val sshPortPreferred = intent.getIntExtra(EXTRA_SSH_PORT, 109)
                val proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: "34.43.46.91"
                val proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 443)
                val payloadHost = intent.getStringExtra(EXTRA_PAYLOAD_HOST) ?: "youtube.com"
                startForeground(1, buildNotification("Connecting..."))
                thread(name = "vpn-connect") {
                    try {
                        connect(host, user, pass, sshPortPreferred, proxyHost, proxyPort, payloadHost)
                    } catch (e: Exception) {
                        Log.e("YohanVPN", "connect failed", e)
                        publish("Connection failed: ${e.message}")
                        stopTunnel()
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun connect(
        host: String,
        user: String,
        pass: String,
        preferredPort: Int,
        proxyHost: String,
        proxyPort: Int,
        payloadHost: String
    ) {
        val ports = linkedSetOf(preferredPort, 109, 22, 80, 443).toList()
        var lastErr: Exception? = null

        for (sshPort in ports) {
            try {
                publish("Connecting to proxy $proxyHost port $proxyPort")
                publish("Sending Payload: CONNECT $host:$sshPort HTTP/1.0[crlf]Host: $payloadHost[crlf][crlf]")
                val socket = ProxyPayloadSocket.connectViaProxy(
                    proxyHost, proxyPort, host, sshPort, payloadHost
                )
                protect(socket)
                publish("Response: HTTP/1.0 200 OK")
                publish("SSH handshake...")
                val ssh = SshTunnel()
                val socksPort = ssh.connect(socket, host, user, pass, 18080)
                tunnel = ssh
                publish("SSH-2.0-dropbear")
                publish("Auth complete")
                publish("Local SOCKS :$socksPort")

                val builder = Builder()
                    .setSession("Yohan VPN")
                    .setMtu(1500)
                    .addAddress("10.8.0.2", 32)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .allowFamily(android.system.OsConstants.AF_INET)

                val pfd = builder.establish()
                    ?: throw Exception("VPN permission denied")
                tun = pfd

                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                val t2s = Tun2Socks(input, output, "127.0.0.1", socksPort) { ds -> protect(ds) }
                tun2socks = t2s
                t2s.start()

                active.set(true)
                isRunning = true
                publish("Connected")
                publish("DNS 8.8.8.8")
                startForeground(1, buildNotification("Connected"))

                while (active.get() && ssh.isConnected()) {
                    Thread.sleep(2000)
                }
                stopTunnel()
                return
            } catch (e: Exception) {
                lastErr = e
                publish("Port $sshPort: ${e.message}")
                try { tun2socks?.stop() } catch (_: Exception) {}
                tun2socks = null
                try { tunnel?.disconnect() } catch (_: Exception) {}
                tunnel = null
                try { tun?.close() } catch (_: Exception) {}
                tun = null
            }
        }
        throw lastErr ?: Exception("Unable to connect")
    }

    private fun stopTunnel() {
        active.set(false)
        isRunning = false
        try { tun2socks?.stop() } catch (_: Exception) {}
        tun2socks = null
        try { tunnel?.disconnect() } catch (_: Exception) {}
        tunnel = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        publish("Connection closed")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun publish(msg: String) {
        statusText = when {
            msg == "Connected" -> "متصل — الإنترنت عبر النفق"
            msg.startsWith("Connection closed") -> "غير متصل"
            msg.startsWith("Connection failed") -> "فشل الاتصال"
            else -> msg
        }
        onStatus?.invoke(msg)
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "yohan_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Yohan VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Yohan VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
