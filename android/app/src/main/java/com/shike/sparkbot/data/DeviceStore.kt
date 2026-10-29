package com.shike.sparkbot.data

import android.content.Context
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

    companion object {
        private const val KEY_LIST = "devices"
        private const val KEY_CURRENT = "current_ip"
        private const val KEY_SPEED = "speed"

        fun defaultName(ip: String): String = "SparkBot ${ip.substringAfterLast('.')}"
    }
}
