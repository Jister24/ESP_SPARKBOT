package com.shike.sparkbot.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * 图像方向处理。
 *
 * 板载 GC2145 装反了，正解是**仅上下翻转**（垂直镜像），而不是旋转 180°。
 * 180° = 上下翻转 + 左右镜像，会把画面左右也颠倒。
 *
 * 固件只有一个「180°」开关，没有单独的垂直翻转，所以这里按组合来做：
 * 水平翻转和垂直翻转都是对合运算（做两次等于没做），
 * 任意方向都能用「是否水平 + 是否垂直」两位表示，一次 Matrix 搞定。
 */
object ImageOps {

    /** 按需水平 / 垂直翻转。两位都 false 时直接返回原对象。 */
    fun transform(src: Bitmap, flipH: Boolean, flipV: Boolean): Bitmap {
        if (!flipH && !flipV) return src
        val matrix = Matrix().apply {
            postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
    }

    /** 解码 → 翻转 → 重新编码为 JPEG。失败返回 null。 */
    fun transformJpeg(
        bytes: ByteArray,
        flipH: Boolean,
        flipV: Boolean,
        quality: Int = 92
    ): ByteArray? {
        if (!flipH && !flipV) return bytes
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotated = runCatching { transform(original, flipH, flipV) }.getOrNull()
        if (rotated == null) {
            original.recycle()
            return null
        }
        return ByteArrayOutputStream(128 * 1024).use { out ->
            val ok = rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (rotated !== original) rotated.recycle()
            original.recycle()
            if (ok) out.toByteArray() else null
        }
    }
}
