package com.car.dockbar.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * 安卓 9 上没有跨窗口模糊。壁纸是 Dock 背后能安全拿到的像素，
 * 在小图上做盒式模糊（近似高斯），再放大铺满 Dock。不截屏。
 */
object WallpaperFrost {

    fun build(ctx: Context, outW: Int, outH: Int, radius: Int): Bitmap {
        val w = outW.coerceAtLeast(1)
        val h = outH.coerceAtLeast(1)
        val sw = (w / 6).coerceIn(48, 360)
        val sh = (h / 2).coerceIn(16, 80)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val src = wallpaper(ctx)
        if (src != null) {
            val scale = maxOf(sw.toFloat() / src.intrinsicWidth.coerceAtLeast(1),
                sh.toFloat() / src.intrinsicHeight.coerceAtLeast(1))
            val dw = src.intrinsicWidth * scale
            val dh = src.intrinsicHeight * scale
            val left = ((sw - dw) / 2f).toInt()
            val top = (sh - dh).toInt()
            src.setBounds(left, top, (left + dw).toInt(), (top + dh).toInt())
            src.draw(canvas)
        } else {
            canvas.drawColor(wallpaperColor(ctx))
        }
        if (radius > 0) boxBlur(small, radius.coerceIn(1, 24))
        val big = Bitmap.createScaledBitmap(small, w, h, true)
        if (big !== small) small.recycle()
        return big
    }

    private fun wallpaper(ctx: Context): Drawable? = runCatching {
        val d = WallpaperManager.getInstance(ctx).drawable ?: return null
        if (d is BitmapDrawable && d.bitmap == null) return null
        d.mutate()
    }.getOrNull()

    private fun wallpaperColor(ctx: Context): Int = runCatching {
        val c = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        c?.primaryColor?.toArgb() ?: 0xFF2A2E36.toInt()
    }.getOrDefault(0xFF2A2E36.toInt())

    /** 三遍盒式模糊，观感接近高斯。在小图上跑。 */
    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(pix.size)
        repeat(3) {
            blurH(pix, tmp, w, h, radius)
            blurV(tmp, pix, w, h, radius)
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
    }

    private fun blurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (y in 0 until h) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            val row = y * w
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (x in 0 until w) {
                dst[row + x] = Color.argb(a / win, rr / win, g / win, b / win)
                val c0 = src[row + (x - r).coerceIn(0, w - 1)]
                val c1 = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += Color.alpha(c1) - Color.alpha(c0)
                rr += Color.red(c1) - Color.red(c0)
                g += Color.green(c1) - Color.green(c0)
                b += Color.blue(c1) - Color.blue(c0)
            }
        }
    }

    private fun blurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (x in 0 until w) {
            var a = 0
            var rr = 0
            var g = 0
            var b = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (y in 0 until h) {
                dst[y * w + x] = Color.argb(a / win, rr / win, g / win, b / win)
                val c0 = src[(y - r).coerceIn(0, h - 1) * w + x]
                val c1 = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += Color.alpha(c1) - Color.alpha(c0)
                rr += Color.red(c1) - Color.red(c0)
                g += Color.green(c1) - Color.green(c0)
                b += Color.blue(c1) - Color.blue(c0)
            }
        }
    }
}
