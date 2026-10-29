package com.shike.sparkbot.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与固件 local_control 模块通信。
 * 8080：控制（毫秒级）；8081：图传 / 抓图（每帧 300~700ms）。
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
        val rot: Boolean
    )

    private fun connect(host: String, port: Int, path: String, timeoutMs: Int): HttpURLConnection {
        val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    private fun getText(host: String, port: Int, path: String, timeoutMs: Int = 2500): String {
        val conn = connect(host, port, path, timeoutMs)
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun status(host: String, timeoutMs: Int = 2500): Status {
        val j = JSONObject(getText(host, CTRL_PORT, "/status", timeoutMs))
        require(j.optBoolean("ok", false)) { "设备返回异常" }
        return Status(
            ip = j.optString("ip", host),
            x = j.optDouble("x", 0.0).toFloat(),
            y = j.optDouble("y", 0.0).toFloat(),
            uptimeSec = j.optLong("uptime_s", 0L),
            camera = j.optBoolean("camera", false),
            rot = j.optBoolean("rot", true)
        )
    }

    fun move(host: String, x: Float, y: Float, timeoutMs: Int = 1500) {
        getText(host, CTRL_PORT, "/move?x=${fmt(x)}&y=${fmt(y)}", timeoutMs)
    }

    fun stop(host: String, timeoutMs: Int = 1500) {
        getText(host, CTRL_PORT, "/stop", timeoutMs)
    }

    fun raw(host: String, command: String, timeoutMs: Int = 2000) {
        getText(host, CTRL_PORT, "/cmd?c=${java.net.URLEncoder.encode(command, "UTF-8")}", timeoutMs)
    }

    fun setRotation(host: String, on: Boolean, timeoutMs: Int = 2000): String =
        getText(host, CTRL_PORT, "/rot?on=${if (on) 1 else 0}", timeoutMs)

    fun toggleRotation(host: String, timeoutMs: Int = 2000): String =
        getText(host, CTRL_PORT, "/rot", timeoutMs)

    fun snapshot(host: String, timeoutMs: Int = 8000): ByteArray {
        val conn = connect(host, CAM_PORT, "/snapshot", timeoutMs)
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
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
