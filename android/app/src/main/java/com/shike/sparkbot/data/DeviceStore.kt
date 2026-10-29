package com.shike.sparkbot.data

import android.content.Context
import com.shike.sparkbot.net.LinkMode
import org.json.JSONArray
import org.json.JSONObject

data class Device(
    val ip: String,
    val name: String,
    val lastSeen: Long = 0L
)

class DeviceStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("sparkbot", Context.MODE_PRIVATE)

    fun devices(): List<Device> {
        val raw = sp.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(
                    ip = o.getString("ip"),
                    name = o.optString("name", defaultName(o.getString("ip"))),
                    lastSeen = o.optLong("lastSeen", 0L)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveDevices(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("ip", d.ip)
                put("name", d.name)
                put("lastSeen", d.lastSeen)
            })
        }
        sp.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun currentIp(): String? = sp.getString(KEY_CURRENT, null)

    fun setCurrentIp(ip: String?) {
        sp.edit().apply {
            if (ip == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, ip)
        }.apply()
    }

    fun speed(): Float = sp.getFloat(KEY_SPEED, 1.0f)

    fun setSpeed(v: Float) = sp.edit().putFloat(KEY_SPEED, v).apply()

    // ---------------- 连接方式 ----------------

    fun mode(): LinkMode =
        if (sp.getString(KEY_MODE, "lan") == "remote") LinkMode.REMOTE else LinkMode.LAN

    fun setMode(mode: LinkMode) {
        sp.edit().putString(KEY_MODE, if (mode == LinkMode.REMOTE) "remote" else "lan").apply()
    }

    fun remoteHost(): String = sp.getString(KEY_REMOTE_HOST, DEFAULT_REMOTE_HOST)
        ?: DEFAULT_REMOTE_HOST

    fun remoteUser(): String = sp.getString(KEY_REMOTE_USER, DEFAULT_REMOTE_USER)
        ?: DEFAULT_REMOTE_USER

    fun remotePass(): String = sp.getString(KEY_REMOTE_PASS, DEFAULT_REMOTE_PASS)
        ?: DEFAULT_REMOTE_PASS

    fun setRemote(host: String, user: String, pass: String) {
        sp.edit()
            .putString(KEY_REMOTE_HOST, host.trim())
            .putString(KEY_REMOTE_USER, user.trim())
            .putString(KEY_REMOTE_PASS, pass.trim())
            .apply()
    }

    companion object {
        private const val KEY_LIST = "devices"
        private const val KEY_CURRENT = "current_ip"
        private const val KEY_SPEED = "speed"
        private const val KEY_MODE = "link_mode"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_REMOTE_USER = "remote_user"
        private const val KEY_REMOTE_PASS = "remote_pass"

        /** 默认值就是已经部署好的那套（见 NAS 上 Docker/frpc/access.txt），改过就在界面上改。 */
        const val DEFAULT_REMOTE_HOST = "bot.example.com"
        const val DEFAULT_REMOTE_USER = "sparkbot"
        const val DEFAULT_REMOTE_PASS = ""

        fun defaultName(ip: String): String = "SparkBot ${ip.substringAfterLast('.')}"
    }
}
