// 票 41：云端上传前的图片压缩。截图多为 1080×2400 PNG，原图 1-3MB；
// 长边 1568 + JPEG 85 约 200-400KB，base64 后仍远小于多数接口上限。
// 订单截图是数字渲染图，无 EXIF 方向问题（相册照片不在本应用主路径）。
package io.github.pnickzhangq.photoledger.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object ImagePreprocessor {

    fun toJpeg(bytes: ByteArray, maxEdge: Int = 1568, quality: Int = 85): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > maxEdge) {
            val ratio = maxEdge.toFloat() / longest
            val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
            }
        } finally {
            scaled.recycle()
        }
    }
}
