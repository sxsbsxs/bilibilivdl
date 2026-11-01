package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sxsbsxs.bilivdl.data.DanmakuItem
import kotlin.math.max

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
            .filter { it.mode == 1 || it.mode == 4 || it.mode == 5 || it.mode == 6 }
            .filter { now >= it.timeMs && now - it.timeMs < duration }
            .take(100)
        active.forEach { item ->
            val elapsed = now - item.timeMs
            val lane = ((item.timeMs / 1300L) % lanes).toInt()
            paint.textSize = (item.fontSize.coerceIn(18, 48) * resources.displayMetrics.scaledDensity).coerceAtLeast(22f)
            paint.color = (0xFF shl 24) or (item.color and 0xFFFFFF)
            val textWidth = paint.measureText(item.text)
            val topY = lane * laneHeight + paint.textSize
            val y = when (item.mode) {
                4 -> height - lane * laneHeight - 4f
                else -> topY
            }.coerceIn(paint.textSize, height - 4f)
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val x = when (item.mode) {
                5, 4 -> (width - textWidth) / 2f
                6 -> -textWidth + (width + textWidth) * progress
                else -> width - (width + textWidth) * progress
            }
            canvas.drawText(item.text, x, y, paint)
        }
        if (playing) postInvalidateDelayed(32L)
    }
}