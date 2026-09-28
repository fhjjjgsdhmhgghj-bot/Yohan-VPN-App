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
import com.yohan.vpn.data.ApiClient
import com.yohan.vpn.data.LocalCache
import com.yohan.vpn.data.Profile
import com.yohan.vpn.data.ServerInfo
import com.yohan.vpn.vpn.YohanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val API = "https://bot-production-8b57.up.railway.app"
        private const val REQ_VPN = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private var servers: List<ServerInfo> = emptyList()
    private val profiles = Profile.values()
    private var pendingAccount: com.yohan.vpn.data.AccountResult? = null
    private var pendingProfile: Profile? = null

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
                appendLog(msg)
            }
        }
        binding.tvStatus.text = YohanVpnService.statusText

        // تحميل من الكاش فوراً ثم تحديث من الشبكة إن أمكن
        val cached = LocalCache.loadServers(this)
        if (cached.isNotEmpty()) {
            servers = cached
            refreshServerSpinner()
            binding.tvStatus.text = "جاهز"
            appendLog("تم تحميل ${cached.size} سيرفر من الذاكرة")
        }
        loadServers()
    }

    private fun loadServers() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    ApiClient.listServers(API)
                }
                if (list.isNotEmpty()) {
                    servers = list
                    LocalCache.saveServers(this@MainActivity, list)
                    refreshServerSpinner()
                    binding.tvStatus.text = "جاهز"
                    appendLog("تم تحديث قائمة السيرفرات (${list.size})")
                } else if (servers.isEmpty()) {
                    servers = fallbackServers()
                    LocalCache.saveServers(this@MainActivity, servers)
                    refreshServerSpinner()
                    binding.tvStatus.text = "جاهز"
                }
            } catch (e: Exception) {
                if (servers.isEmpty()) {
                    servers = LocalCache.loadServers(this@MainActivity).ifEmpty { fallbackServers() }
                    refreshServerSpinner()
                }
                binding.tvStatus.text = "جاهز"
                appendLog("استخدام السيرفرات المحفوظة")
            }
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
        appendLog("السيرفر: ${server.name}")
        appendLog("البروفايل: ${profile.label}")

        lifecycleScope.launch {
            try {
                // حساب محفوظ لنفس السيرفر؟
                var account = LocalCache.loadValidAccount(
                    this@MainActivity, server.key, profile.name
                )
                if (account != null) {
                    appendLog("استخدام الحساب المحفوظ (${account.username})")
                } else {
                    appendLog("إنشاء حساب جديد...")
                    account = withContext(Dispatchers.IO) {
                        val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                            ?: "android-user"
                        ApiClient.createAccount(API, server.key, uid)
                    }
                    if (!account.ok) {
                        appendLog("تعذر إنشاء الحساب: ${account.error}")
                        toast(account.error ?: "فشل")
                        return@launch
                    }
                    if (account.host.isBlank() || account.host.equals("unknown", true)) {
                        appendLog("السيرفر لم يُرجع عنواناً صالحاً")
                        toast("فشل: عنوان السيرفر غير معروف")
                        return@launch
                    }
                    LocalCache.saveAccount(this@MainActivity, account, server.key, profile.name)
                    appendLog("تم حفظ الحساب محلياً")
                }

                appendLog("المضيف: ${account.host}")
                appendLog("المستخدم: ${account.username}")

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
                appendLog("خطأ: ${e.message}")
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
            appendLog("تم رفض صلاحية VPN")
        }
    }

    private fun launchVpn(account: com.yohan.vpn.data.AccountResult, profile: Profile) {
        val i = Intent(this, YohanVpnService::class.java).apply {
            action = YohanVpnService.ACTION_CONNECT
            putExtra(YohanVpnService.EXTRA_HOST, account.host)
            putExtra(YohanVpnService.EXTRA_USER, account.username)
            putExtra(YohanVpnService.EXTRA_PASS, account.password)
            putExtra(YohanVpnService.EXTRA_PROXY_HOST, account.proxyHost)
            putExtra(YohanVpnService.EXTRA_PROXY_PORT, account.proxyPort)
            putExtra(YohanVpnService.EXTRA_PAYLOAD_HOST, profile.payloadHost)
            putExtra(YohanVpnService.EXTRA_PROFILE, profile.name)
        }
        startForegroundService(i)
        appendLog("بدء الاتصال...")
    }

    private fun disconnect() {
        startService(Intent(this, YohanVpnService::class.java).apply {
            action = YohanVpnService.ACTION_DISCONNECT
        })
        appendLog("قطع الاتصال...")
    }

    private fun appendLog(m: String) {
        binding.tvLog.append("• $m\n")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
