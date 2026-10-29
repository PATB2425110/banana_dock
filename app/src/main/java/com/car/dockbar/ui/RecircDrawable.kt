package com.car.dockbar.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * 内外循环图标：
 *  inner = true  → 内循环（车厢内环形箭头，强调色）
 *  inner = false → 外循环（外部箭头进入车厢，白色）
 */
class RecircDrawable(
    private val inner: Boolean,
    private val accent: Int = 0xFF4EA1FF.toInt(),
    private val dim: Int = 0x55FFFFFF,
    private val fg: Int = Color.WHITE
) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val rh = h * 0.30f

        // 车厢轮廓
        stroke.color = dim
        c.drawRoundRect(cx - w * 0.30f, cy - rh, cx + w * 0.30f, cy + rh, w * 0.10f, w * 0.10f, stroke)

        if (inner) {
            // 内循环：车厢内环形箭头
            stroke.color = accent
            val r = h * 0.24f
            val start = -120f
            val sweep = 290f
            c.drawArc(cx - r, cy - r, cx + r, cy + r, start, sweep, false, stroke)
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = (cx + r * cos(end)).toFloat()
            val ey = (cy + r * sin(end)).toFloat()
            val ang = end + Math.PI / 2
            val ah = h * 0.11f
            val dx = cos(ang).toFloat()
            val dy = sin(ang).toFloat()
            c.drawLine(ex, ey, ex - dx * ah - dy * ah * 0.6f, ey - dy * ah + dx * ah * 0.6f, stroke)
            c.drawLine(ex, ey, ex - dx * ah + dy * ah * 0.6f, ey - dy * ah - dx * ah * 0.6f, stroke)
        } else {
            // 外循环：外部箭头进入车厢
            stroke.color = fg
            val y = cy
            val x0 = cx - w * 0.42f
            val x1 = cx + w * 0.16f
            c.drawLine(x0, y, x1, y, stroke)
            val ah = h * 0.13f
            c.drawLine(x1, y, x1 - ah * 0.7f, y - ah * 0.7f, stroke)
            c.drawLine(x1, y, x1 - ah * 0.7f, y + ah * 0.7f, stroke)
        }
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = INTRINSIC
    override fun getIntrinsicHeight() = INTRINSIC

    companion object {
        private const val INTRINSIC = 48
    }
}
