package com.yohan.vpn.data

data class ServerInfo(val key: String, val name: String)

data class AccountResult(
    val ok: Boolean,
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val sshPort: Int = 109,
    val proxyHost: String = "34.43.46.91",
    val proxyPort: Int = 443,
    val error: String? = null
)

enum class Profile(val label: String, val payloadHost: String) {
    YOUTUBE("YouTube", "youtube.com"),
    SNAPCHAT("Snapchat", "api.Snapchat.com")
}
