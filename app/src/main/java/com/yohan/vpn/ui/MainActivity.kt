package com.yohan.vpn.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yohan.vpn.databinding.ActivityMainBinding
import com.yohan.vpn.data.AccountResult
import com.yohan.vpn.data.ApiClient
import com.yohan.vpn.data.LocalCache
import com.yohan.vpn.data.Profile
import com.yohan.vpn.data.ServerInfo
import com.yohan.vpn.vpn.YohanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val API = "https://bot-production-8b57.up.railway.app"
        private const val REQ_VPN = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private var servers: List<ServerInfo> = emptyList()
    private val profiles = Profile.values()
    private var pendingAccount: AccountResult? = null
    private var pendingProfile: Profile? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.label }
        )

        binding.btnConnect.setOnClickListener { startConnectFlow() }
        binding.btnDisconnect.setOnClickListener { disconnect() }

        YohanVpnService.onStatus = { msg ->
            runOnUiThread {
                binding.tvStatus.text = msg
                injectorLog(msg)
            }
        }

        // عرض الحالة
        binding.tvStatus.text = if (YohanVpnService.isRunning) YohanVpnService.statusText else "جاهز"

        // تحميل سيرفرات + تجهيز حساب بصمت
        bootstrap()
    }

    private fun bootstrap() {
        lifecycleScope.launch {
            // 1) سيرفرات من الكاش فوراً
            val cached = LocalCache.loadServers(this@MainActivity)
            if (cached.isNotEmpty()) {
                servers = cached
                refreshServerSpinner()
            }

            // 2) تحديث من الشبكة
            try {
                val list = withContext(Dispatchers.IO) { ApiClient.listServers(API) }
                if (list.isNotEmpty()) {
                    servers = list
                    LocalCache.saveServers(this@MainActivity, list)
                    refreshServerSpinner()
                }
            } catch (_: Exception) {
                if (servers.isEmpty()) {
                    servers = fallbackServers()
                    LocalCache.saveServers(this@MainActivity, servers)
                    refreshServerSpinner()
                }
            }

            binding.tvStatus.text = "تم تحديث السيرفرات"
            injectorLog("Servers updated (${servers.size})")

            // 3) إنشاء/تجهيز حساب للسيرفر الأول بصمت (بدون رسائل إنشاء)
            if (servers.isNotEmpty()) {
                prepareAccountSilent(servers[0].key)
            }
        }
    }

    /** تجهيز حساب في الخلفية — بدون "جاري إنشاء حساب" */
    private suspend fun prepareAccountSilent(serverKey: String) {
        val existing = LocalCache.loadValidAccount(this, serverKey)
        if (existing != null) return
        try {
            withContext(Dispatchers.IO) {
                val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "android-user"
                val acc = ApiClient.createAccount(API, serverKey, uid)
                if (acc.ok) {
                    LocalCache.saveAccount(this@MainActivity, acc, serverKey)
                }
            }
        } catch (_: Exception) {
            // صامت
        }
    }

    private fun fallbackServers() = listOf(
        ServerInfo("de97", "ألمانيا - فرانكفورت (E97)"),
        ServerInfo("de105", "ألمانيا - فرانكفورت (E105)"),
        ServerInfo("fr113", "فرنسا (E113)"),
        ServerInfo("fr220", "فرنسا (E220)"),
        ServerInfo("fr228", "فرنسا (E228)"),
        ServerInfo("md9", "مولدوفا (E9)"),
        ServerInfo("uk17", "المملكة المتحدة (E17)"),
        ServerInfo("uk171", "المملكة المتحدة (E171)"),
        ServerInfo("pl57", "بولندا (E57)"),
        ServerInfo("pl129", "بولندا (E129)"),
        ServerInfo("ae25", "الإمارات (E25)"),
        ServerInfo("ae33", "الإمارات (E33)"),
        ServerInfo("kz65", "كازاخستان (E65)")
    )

    private fun refreshServerSpinner() {
        binding.spinnerServer.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            servers.map { it.name }
        )
    }

    private fun startConnectFlow() {
        if (servers.isEmpty()) {
            toast("لا توجد سيرفرات")
            return
        }
        val server = servers[binding.spinnerServer.selectedItemPosition]
        val profile = profiles[binding.spinnerProfile.selectedItemPosition]

        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                injectorLog("SSH · Performance Mode · Proxy -> Target")
                injectorLog("Profile: ${profile.label}")

                var account = LocalCache.loadValidAccount(this@MainActivity, server.key)
                if (account == null) {
                    // صامت — بدون رسالة إنشاء
                    account = withContext(Dispatchers.IO) {
                        val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                            ?: "android-user"
                        ApiClient.createAccount(API, server.key, uid)
                    }
                    if (!account.ok) {
                        injectorLog("Failed: ${account.error}")
                        toast(account.error ?: "فشل")
                        return@launch
                    }
                    LocalCache.saveAccount(this@MainActivity, account, server.key)
                }

                injectorLog("Target: ${account.username}:@${account.host}:${account.sshPort}")
                injectorLog("Proxy: ${account.proxyHost}:${account.proxyPort}")
                injectorLog(
                    "Payload: CONNECT [host_port] [protocol][crlf]Host: ${profile.payloadHost}[crlf][crlf]"
                )

                val prepare = VpnService.prepare(this@MainActivity)
                if (prepare != null) {
                    pendingAccount = account
                    pendingProfile = profile
                    @Suppress("DEPRECATION")
                    startActivityForResult(prepare, REQ_VPN)
                } else {
                    launchVpn(account, profile)
                }
            } catch (e: Exception) {
                injectorLog("Error: ${e.message}")
                toast(e.message ?: "error")
            } finally {
                binding.btnConnect.isEnabled = true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) {
            val a = pendingAccount
            val p = pendingProfile
            if (a != null && p != null) launchVpn(a, p)
        } else if (requestCode == REQ_VPN) {
            injectorLog("VPN permission denied")
        }
    }

    private fun launchVpn(account: AccountResult, profile: Profile) {
        val i = Intent(this, YohanVpnService::class.java).apply {
            action = YohanVpnService.ACTION_CONNECT
            putExtra(YohanVpnService.EXTRA_HOST, account.host)
            putExtra(YohanVpnService.EXTRA_USER, account.username)
            putExtra(YohanVpnService.EXTRA_PASS, account.password)
            putExtra(YohanVpnService.EXTRA_SSH_PORT, account.sshPort)
            putExtra(YohanVpnService.EXTRA_PROXY_HOST, account.proxyHost)
            putExtra(YohanVpnService.EXTRA_PROXY_PORT, account.proxyPort)
            putExtra(YohanVpnService.EXTRA_PAYLOAD_HOST, profile.payloadHost)
        }
        startForegroundService(i)
        injectorLog("Connecting...")
    }

    private fun disconnect() {
        startService(Intent(this, YohanVpnService::class.java).apply {
            action = YohanVpnService.ACTION_DISCONNECT
        })
        injectorLog("Disconnecting...")
    }

    /** سجل بأسلوب HTTP Injector / DarkTunnel */
    private fun injectorLog(msg: String) {
        val t = timeFmt.format(Date())
        binding.tvLog.append("[$t] $msg\n")
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
