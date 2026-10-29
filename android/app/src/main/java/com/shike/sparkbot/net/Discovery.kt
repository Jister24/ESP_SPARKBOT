package com.shike.sparkbot.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 扫描本机所在 /24 网段，找监听 8080 且 /status 返回 ok 的设备。 */
object Discovery {

    data class Found(val ip: String, val status: SparkApi.Status)

    fun localPrefix(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        for (nif in Collections.list(interfaces)) {
            if (!nif.isUp || nif.isLoopback) continue
            // Wi-Fi / 有线优先，避免拿到蜂窝网的 10.x 网段
            val rank = when {
                nif.name.startsWith("wlan") -> 0
                nif.name.startsWith("en") || nif.name.startsWith("eth") -> 1
                else -> 2
            }
            for (addr in Collections.list(nif.inetAddresses)) {
                if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLinkLocalAddress) {
                    val b = addr.address
                    candidates.add(
                        rank to "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}"
                    )
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /**
     * 两轮扫描。
     *
     * 第一轮用短超时快速排除大部分地址；第二轮对第一轮没应答的地址放宽超时再看一次。
     * ESP32 在 WiFi 省电模式下单次往返可能到 800~1000ms，只用短超时会整台设备都扫不到
     * （实测机器人 ping 900ms 时 260ms 的 connect 必然失败）。
     */
    suspend fun scan(
        prefix: String,
        onFound: (Found) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Found> = coroutineScope {
        val hits = Collections.synchronizedList(mutableListOf<Found>())
        val missed = Collections.synchronizedList(mutableListOf<Int>())
        val progress = AtomicInteger(0)

        suspend fun round(hosts: List<Int>, connectMs: Int, statusMs: Int, concurrency: Int,
                          countForProgress: Boolean) {
            val semaphore = Semaphore(concurrency)
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val ip = "$prefix.$host"
                        val status = probe(ip, connectMs, statusMs)
                        if (status != null) {
                            val found = Found(ip, status)
                            hits.add(found)
                            onFound(found)
                        } else if (connectMs < 1000) {
                            missed.add(host)
                        }
                        if (countForProgress) {
                            onProgress(progress.incrementAndGet(), TOTAL_HOSTS)
                        }
                    }
                }
            }.awaitAll()
        }

        round((1..254).toList(), connectMs = 300, statusMs = 900, concurrency = 64,
              countForProgress = true)

        val retry = missed.toList()
        if (retry.isNotEmpty()) {
            onProgress(TOTAL_HOSTS, TOTAL_HOSTS)
            round(retry, connectMs = 1400, statusMs = 3000, concurrency = 96,
                  countForProgress = false)
        }
        hits.distinctBy { it.ip }.sortedBy { it.ip }
    }

    private fun probe(ip: String, connectMs: Int, statusMs: Int): SparkApi.Status? {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, SparkApi.CTRL_PORT), connectMs)
            }
        } catch (_: Exception) {
            return null
        }
        return runCatching { SparkApi.status(ip, statusMs) }.getOrNull()
    }

    private const val TOTAL_HOSTS = 254
}
