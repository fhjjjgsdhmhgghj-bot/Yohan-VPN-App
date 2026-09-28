package com.yohan.vpn.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun listServers(baseUrl: String): List<ServerInfo> {
        val url = baseUrl.trimEnd('/') + "/servers"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val arr = json.optJSONArray("servers") ?: return emptyList()
            val list = mutableListOf<ServerInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(ServerInfo(o.getString("key"), o.getString("name")))
            }
            return list
        }
    }

    fun createAccount(baseUrl: String, serverKey: String, userId: String): AccountResult {
        val url = baseUrl.trimEnd('/') + "/create"
        val payload = JSONObject()
            .put("server_key", serverKey)
            .put("user_id", userId)
            .toString()
        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return AccountResult(false, error = "empty response")
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                return AccountResult(
                    false,
                    error = json.optString("error", "failed") +
                        if (json.has("retry_after")) " (${json.optString("retry_after")})" else ""
                )
            }
            val host = json.optString("host").trim()
            val user = json.optString("username").trim()
            val pass = json.optString("password").trim()
            val port = json.optInt("port", 109).let { if (it in 1..65535) it else 109 }

            // Reject clearly wrong fields
            if (host.isEmpty() || host.equals("unknown", true)) {
                return AccountResult(false, error = "عنوان السيرفر غير صالح")
            }
            if (user.isEmpty() || user.contains("vpn.com", ignoreCase = true) || user.count { it == '.' } >= 2) {
                return AccountResult(false, error = "اسم المستخدم غير صالح: $user")
            }
            if (pass.isEmpty()) {
                return AccountResult(false, error = "كلمة المرور فارغة")
            }

            return AccountResult(
                ok = true,
                host = host,
                username = user,
                password = pass,
                sshPort = port,
                proxyHost = json.optString("proxy_host", "34.43.46.91"),
                proxyPort = json.optInt("proxy_port", 443)
            )
        }
    }
}
