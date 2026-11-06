package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sxsbsxs.bilivdl.data.DanmakuItem
import kotlin.math.max
import org.json.JSONArray

class DanmakuOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 2f, 0xCC000000.toInt())
    }
    private var items: List<DanmakuItem> = emptyList()
    private var positionProvider: () -> Long = { 0L }
    private var enabled = true
    private var playing = false
    private val laneHeight = 48f
    private val duration = 9000L

    fun setDanmaku(value: List<DanmakuItem>) {
        items = value
        invalidate()
    }
    fun setPlaybackPositionProvider(provider: () -> Long) { positionProvider = provider }
    fun setDanmakuEnabled(value: Boolean) {
        enabled = value
        visibility = if (value) VISIBLE else GONE
        invalidate()
    }
    fun setPlaying(value: Boolean) {
        playing = value
        if (value) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || items.isEmpty() || width == 0 || height == 0) return
        val now = positionProvider()
        val lanes = max(1, (height / laneHeight).toInt())
        val active = items.asSequence()
            .filter { it.mode in setOf(1, 2, 3, 4, 5, 6, 7) }
            .filter { now >= it.timeMs && now - it.timeMs < duration }
            .take(100)
        active.forEach { item ->
            val elapsed = now - item.timeMs
            val displayText = if (item.mode == 7) {
                runCatching { JSONArray(item.text).optString(4).ifBlank { item.text } }.getOrDefault(item.text)
            } else item.text
            if (displayText.isBlank()) return@forEach
            val lane = ((item.timeMs / 1300L) % lanes).toInt()
            paint.textSize = ((item.fontSize.coerceIn(18, 48) * if (item.mode == 2) 0.78f else 1f) * resources.displayMetrics.scaledDensity).coerceAtLeast(22f)
            val sourceColor = (0xFF shl 24) or (item.color and 0xFFFFFF)
            val hsv = FloatArray(3)
            Color.colorToHSV(sourceColor, hsv)
            // Very dark authored danmaku colors disappear on dark video frames.
            // Lift their brightness while preserving hue, then draw a dark outline.
            hsv[2] = max(hsv[2], 0.76f)
            val readableColor = Color.HSVToColor(0xFF, hsv)
            val textWidth = paint.measureText(displayText)
            val topY = lane * laneHeight + paint.textSize
            val y = when (item.mode) {
                4 -> height - lane * laneHeight - 4f
                7 -> height * 0.5f
                else -> topY
            }.coerceIn(paint.textSize, height - 4f)
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val x = when (item.mode) {
                4, 5, 7 -> (width - textWidth) / 2f
                6 -> -textWidth + (width + textWidth) * progress
                else -> width - (width + textWidth) * progress
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = 0xD9000000.toInt()
            paint.clearShadowLayer()
            canvas.drawText(displayText, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = readableColor
            paint.setShadowLayer(3f, 1f, 2f, 0xCC000000.toInt())
            canvas.drawText(item.text, x, y, paint)
        }
        if (playing) postInvalidateDelayed(32L)
    }
}