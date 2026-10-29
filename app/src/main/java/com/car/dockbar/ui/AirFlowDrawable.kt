package com.car.dockbar.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 风向复合图标：用三个分区分别表示 吹脸 / 吹脚 / 吹前挡，
 * 激活的分区高亮、未激活灰显，从而把 7 种组合（含"吹脚+吹脸+吹前挡"等）都画成各自不同的样子。
 *
 * mode 位掩码：1 = 吹脸(FACE)  2 = 吹脚(FOOT)  4 = 吹前挡(DEFROST)
 */
class AirFlowDrawable(
    private val mode: Int,
    private val accent: Int = 0xFF4EA1FF.toInt(),
    private val dim: Int = 0x45FFFFFF,
    private val fg: Int = Color.WHITE
) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val face get() = mode and 0x1 != 0
    private val foot get() = mode and 0x2 != 0
    private val defrost get() = mode and 0x4 != 0

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val cx = w / 2f
        val top = h * 0.10f
        val bottom = h * 0.90f
        val segH = (bottom - top) / 3f

        // —— 吹前挡：顶部风挡弧 + 两道波纹 ——
        val dy = top + segH * 0.5f
        stroke.color = if (defrost) accent else dim
        c.drawArc(
            cx - w * 0.24f, dy - segH * 0.34f,
            cx + w * 0.24f, dy + segH * 0.34f,
            200f, 140f, false, stroke
        )
        if (defrost) {
            val wy = segH * 0.14f
            c.drawLine(cx - w * 0.16f, dy + wy, cx + w * 0.16f, dy + wy, stroke)
            c.drawLine(cx - w * 0.12f, dy + wy * 2.4f, cx + w * 0.12f, dy + wy * 2.4f, stroke)
        }

        // —— 吹脸：中间上箭头 ——
        stroke.color = if (face) fg else dim
        drawArrow(c, cx, top + segH * 1.5f + segH * 0.30f, cx, top + segH * 1.5f - segH * 0.30f)

        // —— 吹脚：底部下箭头 ——
        stroke.color = if (foot) fg else dim
        drawArrow(c, cx, top + segH * 2.5f - segH * 0.30f, cx, top + segH * 2.5f + segH * 0.30f)
    }

    /** (x1,y1)=尾  (x2,y2)=头，自动画箭头 */
    private fun drawArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, stroke)
        val head = (bounds.height() * 0.12f).coerceAtLeast(3f)
        val dir = if (y2 < y1) -1 else 1
        c.drawLine(x2, y2, x2 - head * 0.8f, y2 - dir * head, stroke)
        c.drawLine(x2, y2, x2 + head * 0.8f, y2 - dir * head, stroke)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = INTRINSIC
    override fun getIntrinsicHeight() = INTRINSIC

    companion object {
        private const val INTRINSIC = 48
        // 位掩码常量（与 VehicleController 保持一致）
        const val FACE = 1
        const val FOOT = 2
        const val DEFROST = 4
        /** 7 种组合，循环切换顺序 */
        val MODES = listOf(1, 3, 2, 6, 4, 5, 7)
        /** mode → 中文标签 */
        fun label(mode: Int): String {
            val f = mode and FACE != 0
            val t = mode and FOOT != 0
            val d = mode and DEFROST != 0
            return buildList {
                if (f) add("吹脸")
                if (t) add("吹脚")
                if (d) add("吹前挡")
            }.joinToString("+").ifEmpty { "关" }
        }
    }
}
