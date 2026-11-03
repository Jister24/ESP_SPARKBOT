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

    /**
     * 单帧 `/imu` 的往返上限。20Hz 拉流时正常值只有 20~80ms，
     * 留到 400ms 是给"偶发一次重传"的余量；再长就等于让用户干等。
     */
    const val IMU_TIMEOUT_MS = 400

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

    /**
     * `/imu` 的一帧。四元数是 body→world 的旋转（世界系 z 朝上），
     * 所以机体顶点乘 [q] 就得到它现在的真实姿态。
     */
    data class Imu(
        val ready: Boolean,
        val mode: String,
        val hz: Int,
        val seq: Long,
        val accG: List<Float>,
        val gyrDps: List<Float>,
        /** w, x, y, z；老固件没有这个字段时为 null。 */
        val q: List<Float>?,
        val roll: Float,
        val pitch: Float,
        val yaw: Float,
        val tiltX: Float,
        val tiltY: Float,
        /** 在线估计出的陀螺零偏（dps）。z 轴不可观测，别当真。 */
        val gbias: List<Float>
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

    /**
     * 读一帧 IMU。固件 100Hz 采样，走 HTTP 拉的上限只有 20Hz 左右
     * （每次都是新建连接，单次 20~80ms），做姿态显示够用，
     * 想做低延迟手柄得等固件改成 UDP 推流。
     *
     * 超时只给 [IMU_TIMEOUT_MS]：实测手机侧 2.4G 空口丢包 14%、TCP 一次
     * 重传退避就是 1 秒起步，超时设成 1 秒以上等于让姿态画面**跟着重传一起
     * 冻结**，用户看到的就是"卡一下，时延跳到 1000ms+"。宁可丢掉这一帧、
     * 下一轮立刻用新连接重试，也不要把界面钉在一条卡住的连接上。
     */
    fun imu(endpoint: Endpoint, timeoutMs: Int = IMU_TIMEOUT_MS): Imu {
        val j = JSONObject(getText(endpoint, "/imu", timeoutMs))
        return Imu(
            ready = j.optBoolean("ready", false),
            mode = j.optString("mode", ""),
            hz = j.optInt("hz", 0),
            seq = j.optLong("seq", 0L),
            accG = j.optJSONArray("acc_g").toFloatList(3),
            gyrDps = j.optJSONArray("gyr_dps").toFloatList(3),
            q = j.optJSONArray("q")?.toFloatList(4),
            roll = j.optDouble("roll", 0.0).toFloat(),
            pitch = j.optDouble("pitch", 0.0).toFloat(),
            yaw = j.optDouble("yaw", 0.0).toFloat(),
            tiltX = j.optDouble("tilt_x", 0.0).toFloat(),
            tiltY = j.optDouble("tilt_y", 0.0).toFloat(),
            gbias = j.optJSONArray("gbias").toFloatList(3)
        )
    }

    /** 把「现在这个姿态」设成零点，之后 tilt_x / tilt_y 相对它算。 */
    fun zeroImu(endpoint: Endpoint, timeoutMs: Int = 2000): String =
        getText(endpoint, "/imu/zero", timeoutMs)

    private fun org.json.JSONArray?.toFloatList(n: Int): List<Float> =
        if (this == null) List(n) { 0f }
        else List(n) { optDouble(it, 0.0).toFloat() }

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
