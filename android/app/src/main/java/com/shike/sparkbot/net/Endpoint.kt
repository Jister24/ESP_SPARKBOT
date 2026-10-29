package com.shike.sparkbot.net

import android.util.Base64

/** 连接方式：局域网直连，或经 NAS 隧道 + 服务器 Nginx 的远程访问。 */
enum class LinkMode { LAN, REMOTE }

/**
 * 设备访问入口。
 *
 * - [Lan]：设备自己的 httpd，`http://ip:8080` 控制、`http://ip:8081` 图传，无鉴权。
 * - [Remote]：设备的 8080/8081 由 NAS 上的 frpc 隧道到服务器回环端口，
 *   Nginx 再把它们收敛成两个 HTTPS 路径并套 Basic 鉴权：
 *   `/bot/` 对 18080、`/botcam/` 对 18081。
 *
 * 业务层只关心「路径」，URL 拼装和鉴权头都收在这里，换连接方式不用改调用方。
 */
sealed interface Endpoint {

    data class Lan(val ip: String) : Endpoint

    data class Remote(
        val host: String,
        val user: String,
        val pass: String
    ) : Endpoint

    val isRemote: Boolean get() = this is Remote

    fun ctrlUrl(path: String): String = when (this) {
        is Lan -> "http://$ip:${SparkApi.CTRL_PORT}$path"
        is Remote -> "https://$host/bot$path"
    }

    fun camUrl(path: String): String = when (this) {
        is Lan -> "http://$ip:${SparkApi.CAM_PORT}$path"
        is Remote -> "https://$host/botcam$path"
    }

    /** 只有远程需要 Basic 头；局域网设备本来就没有鉴权。 */
    val authorization: String? get() = when (this) {
        is Lan -> null
        is Remote ->
            if (user.isEmpty() && pass.isEmpty()) null
            else "Basic " + Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
    }

    /** 界面上显示的地址。远程不暴露内网 IP。 */
    val label: String get() = when (this) {
        is Lan -> ip
        is Remote -> host
    }

    /**
     * 远程要多经两跳（隧道 + Nginx），TLS 握手也比明文慢，
     * 所有超时按这个系数放宽，否则远程会出现「连得上但总超时」。
     */
    fun timeout(baseMs: Int): Int = if (isRemote) (baseMs * 2.5f).toInt() else baseMs
}
