package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Settings-only illustration of each background mode; never draws the actual Dock. */
internal class BackgroundModeTile(context: Context, val mode: BgMode) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private fun dp(n: Float) = n * density

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "背景形态：${mode.label}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumHeight = dp(142f).toInt()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(1f)
        rect.set(inset, inset, width - inset, height - inset)
        paint.style = Paint.Style.FILL
        paint.color = if (isSelected || isPressed) 0xFF192538.toInt() else 0xFF15171C.toInt()
        canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (isSelected) 2f else 1f)
        paint.color = if (isSelected) 0xFF3D8BFF.toInt() else 0xFF30343D.toInt()
        canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
        paint.style = Paint.Style.FILL
        rect.set(dp(18f), dp(18f), width - dp(18f), dp(86f))
        paint.color = 0xFF30343B.toInt()
        canvas.drawRoundRect(rect, dp(8f), dp(8f), paint)
        val saved = canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, dp(8f), dp(8f), android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        when (mode) {
            BgMode.SOLID -> {
                paint.color = 0xFFE0E2E5.toInt()
                canvas.drawRect(rect.left, rect.centerY(), rect.right, rect.bottom, paint)
            }
            BgMode.TRANSPARENT -> {
                val cell = dp(8f)
                var y = rect.top
                var row = 0
                while (y < rect.bottom) {
                    var x = rect.left
                    var col = 0
                    while (x < rect.right) {
                        paint.color = if ((row + col) % 2 == 0) 0xFF242830.toInt() else 0xFF30353E.toInt()
                        canvas.drawRect(x, y, x + cell, y + cell, paint)
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            }
            BgMode.GRADIENT -> {
                paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                    Color.TRANSPARENT, 0xD1FFFFFF.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
                paint.shader = null
            }
            BgMode.BLUR -> {
                paint.color = 0x80808080.toInt()
                canvas.drawRect(rect, paint)
            }
        }
        canvas.restoreToCount(saved)
        paint.typeface = AppFont.regular(context)
        paint.textSize = dp(17f)
        paint.color = if (isSelected) 0xFF8CBAFF.toInt() else 0xFFEDEFF3.toInt()
        canvas.drawText(mode.label, dp(19f), height - dp(23f), paint)
        if (isSelected) {
            val cx = width - dp(29f)
            val cy = dp(29f)
            paint.color = 0xFF3D8BFF.toInt()
            canvas.drawCircle(cx, cy, dp(10f), paint)
            paint.color = Color.WHITE
            paint.strokeWidth = dp(1.8f)
            canvas.drawLine(cx - dp(4f), cy, cx - dp(1f), cy + dp(3f), paint)
            canvas.drawLine(cx - dp(1f), cy + dp(3f), cx + dp(5f), cy - dp(3f), paint)
        }
        // Keep the caption visible when selected; the checkmark lives in the preview corner.
        paint.textSize = dp(11f * 1.5f)
        paint.color = 0xFF9AA1AE.toInt()
        val caption = when (mode) {
            BgMode.SOLID -> "统一底色"
            BgMode.TRANSPARENT -> "不绘制底色"
            BgMode.GRADIENT -> "上下渐变"
            BgMode.BLUR -> "（测试中）"
        }
        canvas.drawText(caption, width - dp(19f) - paint.measureText(caption), height - dp(24f), paint)
    }
}
