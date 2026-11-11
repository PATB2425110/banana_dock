package com.car.dockbar.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/** Transparent base + adjustable gray, with independently adjustable compositing transparency. */
internal class GrayOverlayDrawable(grayLevel: Int, transparency: Int, private val cornerPx: Float) : Drawable() {
    private val gray = (grayLevel.coerceIn(0, 100) * 255f / 100f).roundToInt()
    private val opacity = ((100 - transparency.coerceIn(0, 100)) * 255f / 100f).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var drawableAlpha = 255

    override fun onBoundsChange(bounds: Rect) { rect.set(bounds) }

    override fun draw(canvas: Canvas) {
        val alpha = (opacity * drawableAlpha / 255f).roundToInt()
        if (alpha == 0 || rect.isEmpty) return
        // A separate transparent paint cannot erase gray underneath it; apply transparency
        // to the gray layer's compositing Alpha instead. Dock children remain unaffected.
        paint.color = Color.argb(alpha, gray, gray, gray)
        val radius = cornerPx.coerceIn(0f, rect.height() / 2f)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
