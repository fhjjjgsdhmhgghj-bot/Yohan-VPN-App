package com.yohan.vpn.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yohan.vpn.databinding.ActivityMainBinding
import com.yohan.vpn.data.ApiClient
import com.yohan.vpn.data.Profile
import com.yohan.vpn.data.ServerInfo
import com.yohan.vpn.vpn.YohanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_API = "https://bot-production-8b57.up.railway.app"
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

        // API URL ثابت من Railway — يُحمّل السيرفرات فور الدخول
        binding.etApiUrl.setText(DEFAULT_API)
        binding.etApiUrl.isEnabled = false
        binding.etApiUrl.visibility = View.GONE

        binding.spinnerProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.label }
        )

        binding.btnConnect.setOnClickListener { startConnectFlow() }
        binding.btnDisconnect.setOnClickListener { disconnect() }

        YohanVpnService.onStatus = { msg ->
            runOnUiThread {
                binding.tvStatus.text = msg
                binding.tvLog.append("$msg\n")
            }
        }
        binding.tvStatus.text = YohanVpnService.statusText

        // سحب السيرفرات مباشرة عند فتح التطبيق
        loadServersFromApi()
    }

    private fun loadServersFromApi() {
        binding.tvStatus.text = "جاري جلب السيرفرات..."
        log("الاتصال بـ $DEFAULT_API ...")
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    ApiClient.listServers(DEFAULT_API)
                }
                if (list.isEmpty()) {
                    log("لم تُرجع الـ API سيرفرات — استخدام القائمة الاحتياطية")
                    servers = fallbackServers()
                    binding.tvStatus.text = "غير متصل (قائمة احتياطية)"
                } else {
                    servers = list
                    log("تم جلب ${list.size} سيرفر")
                    binding.tvStatus.text = "جاهز — ${list.size} سيرفر"
                }
                refreshServerSpinner()
            } catch (e: Exception) {
                log("خطأ جلب السيرفرات: ${e.message}")
                servers = fallbackServers()
                refreshServerSpinner()
                binding.tvStatus.text = "غير متصل (وضع احتياطي)"
                toast("تعذر الاتصال بالـ API — قائمة احتياطية")
            }
        }
    }

    private fun fallbackServers() = listOf(
        ServerInfo("de97", "ألمانيا E97"),
        ServerInfo("de105", "ألمانيا E105"),
        ServerInfo("fr113", "فرنسا E113"),
        ServerInfo("fr220", "فرنسا E220"),
        ServerInfo("fr228", "فرنسا E228"),
        ServerInfo("md9", "مولدوفا E9"),
        ServerInfo("uk17", "بريطانيا E17"),
        ServerInfo("uk171", "بريطانيا E171"),
        ServerInfo("pl57", "بولندا E57"),
        ServerInfo("pl129", "بولندا E129"),
        ServerInfo("ae25", "الإمارات E25"),
        ServerInfo("ae33", "الإمارات E33"),
        ServerInfo("kz65", "كازاخستان E65")
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
        log("إنشاء حساب على ${server.name}...")

        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        ?: "android-user"
                    ApiClient.createAccount(DEFAULT_API, server.key, uid)
                }

                if (!account.ok) {
                    log("فشل: ${account.error}")
                    toast(account.error ?: "فشل")
                    return@launch
                }

                log("حساب: ${account.username}@${account.host}")
                log("بروكسي ${account.proxyHost}:${account.proxyPort} | ${profile.label}")

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
                log("خطأ: ${e.message}")
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
            log("رفض صلاحية VPN")
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
        log("بدء خدمة VPN...")
    }

    private fun disconnect() {
        startService(Intent(this, YohanVpnService::class.java).apply {
            action = YohanVpnService.ACTION_DISCONNECT
        })
        log("قطع الاتصال...")
    }

    private fun log(m: String) {
        binding.tvLog.append("$m\n")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
