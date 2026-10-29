package com.shike.sparkbot.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 消费固件 8081 端口的 MJPEG 流。
 * 只按 JPEG 的 SOI(FFD8)/EOI(FFD9) 切帧，不依赖 multipart 边界，容错性更好。
 */
class MjpegStreamer(
    private val host: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (connected: Boolean, message: String?) -> Unit
) {

    @Volatile private var running = true
    private var connection: HttpURLConnection? = null
    private var thread: Thread? = null
    @Volatile private var lastDecodeAt = 0L

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "sparkbot-mjpeg").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { connection?.disconnect() }
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var failures = 0
        while (running) {
            try {
                val conn = (URL("http://$host:${SparkApi.CAM_PORT}/stream").openConnection()
                        as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 10_000
                    useCaches = false
                    setRequestProperty("Connection", "close")
                }
                connection = conn
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
                BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                    onState(true, null)
                    failures = 0
                    readFrames(input)
                }
            } catch (e: Exception) {
                if (!running) break
                failures += 1
                onState(false, e.message ?: e.javaClass.simpleName)
                if (failures > 20) break
                runCatching { Thread.sleep(700) }
            } finally {
                runCatching { connection?.disconnect() }
                connection = null
            }
        }
        onState(false, null)
    }

    private fun readFrames(input: InputStream) {
        val chunk = ByteArray(16 * 1024)
        var frame: ByteArrayOutputStream? = null
        var prev = -1

        while (running) {
            val n = input.read(chunk)
            if (n < 0) break
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xFF
                if (frame == null) {
                    if (prev == 0xFF && b == 0xD8) {
                        frame = ByteArrayOutputStream(160 * 1024).apply {
                            write(0xFF)
                            write(0xD8)
                        }
                    }
                } else {
                    frame.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        val bytes = frame.toByteArray()
                        frame = null
                        emit(bytes)
                    }
                }
                prev = b
            }
        }
    }

    private fun emit(jpeg: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastDecodeAt < 66) return   // 上限 ~15fps，解码跟不上时丢帧
        lastDecodeAt = now
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) }
            .getOrNull() ?: return
        if (running) onFrame(bitmap)
    }
}
