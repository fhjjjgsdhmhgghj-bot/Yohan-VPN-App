package com.yohan.vpn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LocalCache {
    private const val PREF = "yohan_vpn_cache"
    private const val KEY_SERVERS = "servers_json"
    private const val KEY_ACCOUNT = "account_json"
    private const val KEY_ACCOUNT_TS = "account_ts"
    private const val ACCOUNT_TTL_MS = 4L * 24 * 60 * 60 * 1000

    fun saveServers(ctx: Context, servers: List<ServerInfo>) {
        val arr = JSONArray()
        servers.forEach {
            arr.put(JSONObject().put("key", it.key).put("name", it.name))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun loadServers(ctx: Context): List<ServerInfo> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ServerInfo(o.getString("key"), o.getString("name"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccount(ctx: Context, account: AccountResult, serverKey: String) {
        if (!account.ok) return
        val o = JSONObject()
            .put("host", account.host)
            .put("username", account.username)
            .put("password", account.password)
            .put("sshPort", account.sshPort)
            .put("proxyHost", account.proxyHost)
            .put("proxyPort", account.proxyPort)
            .put("serverKey", serverKey)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT, o.toString())
            .putLong(KEY_ACCOUNT_TS, System.currentTimeMillis())
            .apply()
    }

    fun clearAccount(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCOUNT)
            .remove(KEY_ACCOUNT_TS)
            .apply()
    }

    fun loadValidAccount(ctx: Context, serverKey: String): AccountResult? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ts = sp.getLong(KEY_ACCOUNT_TS, 0L)
        if (ts == 0L || System.currentTimeMillis() - ts > ACCOUNT_TTL_MS) return null
        val raw = sp.getString(KEY_ACCOUNT, null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (o.optString("serverKey") != serverKey) return null
            val host = o.optString("host")
            val user = o.optString("username")
            if (host.isBlank() || host.equals("unknown", true)) return null
            // reject domain-like usernames from old bad cache
            if (user.contains("vpn.com") || user.contains("09vpn") || user.startsWith("ov-")) {
                clearAccount(ctx)
                return null
            }
            AccountResult(
                ok = true,
                host = host,
                username = user,
                password = o.optString("password"),
                sshPort = o.optInt("sshPort", 109),
                proxyHost = o.optString("proxyHost", "34.43.46.91"),
                proxyPort = o.optInt("proxyPort", 443)
            )
        } catch (_: Exception) {
            null
        }
    }
}
