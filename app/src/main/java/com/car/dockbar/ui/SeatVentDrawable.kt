package com.car.dockbar.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 座椅通风图标：座椅轮廓 + 蓝色波纹（数量 = 档位）。
 * level: 0 = 关闭（仅灰座椅）  1 = 弱  2 = 中  3 = 强
 */
class SeatVentDrawable(
    private val level: Int,
    private val accent: Int = 0xFF4EA1FF.toInt(),
    private val dim: Int = 0x55FFFFFF,
    private val fg: Int = Color.WHITE
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val on get() = level > 0

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()

        // 座椅：靠背 + 坐垫
        fill.color = if (on) fg else dim
        val bx = w * 0.46f
        val bw = w * 0.30f
        val r = w * 0.10f
        c.drawRoundRect(bx, h * 0.12f, bx + bw, h * 0.60f, r, r, fill)          // 靠背
        c.drawRoundRect(w * 0.20f, h * 0.54f, w * 0.74f, h * 0.78f, r, r, fill) // 坐垫

        // 通风波纹（左侧，蓝），数量 = level
        if (on) {
            stroke.color = accent
            val lines = level.coerceIn(1, 3)
            val x0 = w * 0.04f
            val x1 = w * 0.44f
            val midY = h * 0.45f
            val gap = h * 0.22f
            val amp = h * 0.14f
            for (i in 0 until lines) {
                val y = midY + (i - (lines - 1) / 2f) * gap
                val p = Path()
                val seg = (x1 - x0) / 4f
                p.moveTo(x0, y)
                p.lineTo(x0 + seg, y - amp)
                p.lineTo(x0 + 2 * seg, y + amp)
                p.lineTo(x0 + 3 * seg, y - amp)
                p.lineTo(x1, y + amp)
                c.drawPath(p, stroke)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        fill.colorFilter = cf
        stroke.colorFilter = cf
    }

    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = INTRINSIC
    override fun getIntrinsicHeight() = INTRINSIC

    companion object {
        private const val INTRINSIC = 48
        fun label(level: Int): String = when (level) {
            0 -> "关闭"
            1 -> "弱"
            2 -> "中"
            3 -> "强"
            else -> level.toString()
        }
    }
}
