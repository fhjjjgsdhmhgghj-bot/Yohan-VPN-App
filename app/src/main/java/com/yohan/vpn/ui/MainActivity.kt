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
import com.yohan.vpn.data.Profile
import com.yohan.vpn.data.ServerInfo
import com.yohan.vpn.vpn.YohanVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var servers: List<ServerInfo> = emptyList()
    private val profiles = Profile.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerProfile.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.label }
        )

        // default servers offline list
        servers = listOf(
            ServerInfo("de97", "ألمانيا E97"),
            ServerInfo("de105", "ألمانيا E105"),
            ServerInfo("fr113", "فرنسا E113"),
            ServerInfo("fr220", "فرنسا E220"),
            ServerInfo("uk17", "بريطانيا E17"),
            ServerInfo("ae25", "الإمارات E25")
        )
        refreshServerSpinner()

        binding.btnConnect.setOnClickListener { startConnectFlow() }
        binding.btnDisconnect.setOnClickListener { disconnect() }

        YohanVpnService.onStatus = { msg ->
            runOnUiThread {
                binding.tvStatus.text = msg
                binding.tvLog.append("$msg\n")
            }
        }
        binding.tvStatus.text = YohanVpnService.statusText
    }

    private fun refreshServerSpinner() {
        binding.spinnerServer.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            servers.map { it.name }
        )
    }

    private fun startConnectFlow() {
        val apiUrl = binding.etApiUrl.text.toString().trim()
        if (apiUrl.isEmpty()) {
            toast("ضع رابط API من Railway")
            return
        }
        if (servers.isEmpty()) {
            toast("لا توجد سيرفرات")
            return
        }
        val server = servers[binding.spinnerServer.selectedItemPosition]
        val profile = profiles[binding.spinnerProfile.selectedItemPosition]

        binding.btnConnect.isEnabled = false
        log("طلب حساب من API على ${server.key}...")

        lifecycleScope.launch {
            try {
                // try refresh servers from API
                val remote = withContext(Dispatchers.IO) {
                    try { ApiClient.listServers(apiUrl) } catch (_: Exception) { emptyList() }
                }
                if (remote.isNotEmpty()) {
                    servers = remote
                    refreshServerSpinner()
                }

                val account = withContext(Dispatchers.IO) {
                    val uid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        ?: "android-user"
                    ApiClient.createAccount(apiUrl, server.key, uid)
                }

                if (!account.ok) {
                    log("فشل API: ${account.error}")
                    toast(account.error ?: "فشل")
                    binding.btnConnect.isEnabled = true
                    return@launch
                }

                log("حساب: ${account.username}@${account.host}")
                log("بروكسي ${account.proxyHost}:${account.proxyPort} | ${profile.label}")

                // VPN permission
                val prepare = VpnService.prepare(this@MainActivity)
                if (prepare != null) {
                    pendingAccount = account
                    pendingProfile = profile
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

    private var pendingAccount: com.yohan.vpn.data.AccountResult? = null
    private var pendingProfile: Profile? = null

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

    companion object {
        private const val REQ_VPN = 1001
    }
}
