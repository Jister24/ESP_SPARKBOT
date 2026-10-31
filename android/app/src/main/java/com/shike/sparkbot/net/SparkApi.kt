package com.shike.sparkbot.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与固件 local_control 模块通信。
 * 8080：控制（毫秒级）；8081：图传 / 抓图（每帧 300~700ms）。
 *
 * 地址由 [Endpoint] 决定：局域网直连设备，远程走 NAS 隧道 + 服务器 Nginx。
 */
object SparkApi {

    const val CTRL_PORT = 8080
    const val CAM_PORT = 8081

    data class Status(
        val ip: String,
        val x: Float,
        val y: Float,
        val uptimeSec: Long,
        val camera: Boolean,
        val rot: Boolean,
        /** 主体板电量百分比；老固件的 /status 没有这个字段，为 null 时不显示。 */
        val batt: Int? = null,
        /** 主体板电芯电压（mV），同样可能缺失。 */
        val battMv: Int? = null,
        val charging: Boolean = false,
        /**
         * 履带底盘电量百分比。底盘那颗 C2 自己采样，主体板通过 UART 问来再塞进
         * /status；没改过的底盘固件不会应答，所以这三个字段经常是 null。
         */
        val chassisBatt: Int? = null,
        val chassisBattMv: Int? = null,
        val chassisCharging: Boolean = false
    )

    private fun connect(
        endpoint: Endpoint,
        path: String,
        timeoutMs: Int,
        cam: Boolean = false
    ): HttpURLConnection {
        val url = if (cam) endpoint.camUrl(path) else endpoint.ctrlUrl(path)
        val conn = URL(url).openConnection() as HttpURLConnection
        val timeout = endpoint.timeout(timeoutMs)
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        endpoint.authorization?.let { conn.setRequestProperty("Authorization", it) }
        return conn
    }

    private fun getText(
        endpoint: Endpoint,
        path: String,
        timeoutMs: Int = 2500,
        cam: Boolean = false
    ): String {
        val conn = connect(endpoint, path, timeoutMs, cam)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error(describe(code))
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 401/403 在远程模式下几乎都是账号密码不对，直接说清楚，别让人去查隧道。 */
    private fun describe(code: Int): String = when (code) {
        401, 403 -> "HTTP $code：远程账号或密码不对"
        else -> "HTTP $code"
    }

    fun status(endpoint: Endpoint, timeoutMs: Int = 2500): Status {
        val j = JSONObject(getText(endpoint, "/status", timeoutMs))
        require(j.optBoolean("ok", false)) { "设备返回异常" }
        return Status(
            ip = j.optString("ip", endpoint.label),
            x = j.optDouble("x", 0.0).toFloat(),
            y = j.optDouble("y", 0.0).toFloat(),
            uptimeSec = j.optLong("uptime_s", 0L),
            camera = j.optBoolean("camera", false),
            rot = j.optBoolean("rot", true),
            batt = if (j.has("batt")) j.optInt("batt") else null,
            battMv = if (j.has("batt_mv")) j.optInt("batt_mv") else null,
            charging = j.optBoolean("charging", false),
            chassisBatt = if (j.has("chassis_batt")) j.optInt("chassis_batt") else null,
            chassisBattMv = if (j.has("chassis_batt_mv")) j.optInt("chassis_batt_mv") else null,
            chassisCharging = j.optBoolean("chassis_charging", false)
        )
    }

    fun move(endpoint: Endpoint, x: Float, y: Float, timeoutMs: Int = 1500) {
        getText(endpoint, "/move?x=${fmt(x)}&y=${fmt(y)}", timeoutMs)
    }

    fun stop(endpoint: Endpoint, timeoutMs: Int = 1500) {
        getText(endpoint, "/stop", timeoutMs)
    }

    fun raw(endpoint: Endpoint, command: String, timeoutMs: Int = 2000) {
        getText(
            endpoint,
            "/cmd?c=${java.net.URLEncoder.encode(command, "UTF-8")}",
            timeoutMs
        )
    }

    fun setRotation(endpoint: Endpoint, on: Boolean, timeoutMs: Int = 2000): String =
        getText(endpoint, "/rot?on=${if (on) 1 else 0}", timeoutMs)

    fun toggleRotation(endpoint: Endpoint, timeoutMs: Int = 2000): String =
        getText(endpoint, "/rot", timeoutMs)

    fun snapshot(endpoint: Endpoint, timeoutMs: Int = 8000): ByteArray {
        val conn = connect(endpoint, "/snapshot", timeoutMs, cam = true)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error(describe(code))
            val out = ByteArrayOutputStream(64 * 1024)
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
