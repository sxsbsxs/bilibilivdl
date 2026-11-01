package cc.kafuu.bilidownload.common.jni

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap

object QrCodeNativeLib {
    fun generateQrBitmap(text: String, scale: Int = 10): Bitmap {
        val (data, width) = NativeLib.generateQrCode(text)
        val bitmapSize = width * scale
        val bitmap = createBitmap(bitmapSize, bitmapSize)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        for (y in 0 until width) {
            for (x in 0 until width) {
                val index = y * width + x
                paint.color = if (data[index]) Color.BLACK else Color.WHITE
                val left = x * scale.toFloat()
                val top = y * scale.toFloat()
                val right = left + scale
                val bottom = top + scale
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }

        return bitmap
    }

}