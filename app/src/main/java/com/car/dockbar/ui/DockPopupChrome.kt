package com.car.dockbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.car.dockbar.util.Prefs

/**
 * Dock 弹窗统一规范 —— 所有从 Dock 按钮弹出的窗口（现在的和将来的）都必须遵守：
 *
 *  1. **贴近触发图标**：弹窗位置贴着触发它的那个 Dock 图标
 *     （Dock 在底部 → 弹窗浮在图标上方；水平方向以图标中心对准弹窗中心，
 *     并收敛在屏幕范围内，不越界）。
 *  2. **点外面关闭**：点击弹窗以外的任何区域立即关闭
 *     （FLAG_WATCH_OUTSIDE_TOUCH + ACTION_OUTSIDE）。
 *  3. **不放 ×**：弹窗不提供右上角关闭按钮；关闭途径只有
 *     「点弹窗外面 / 再点一次触发图标 / 超时自动收起」。
 */
object DockPopupChrome {

    /**
     * 被「点外面」关掉后，片刻之内的 show() 一律忽略。
     *
     * 原因：弹窗带 FLAG_NOT_TOUCH_MODAL，同一根手指的 down 会让弹窗收到
     * ACTION_OUTSIDE 而关闭，up 却照样落到 Dock 图标上再触发一次 show()，
     * 不压制的话弹窗会立刻重新弹出来 —— 表现为「点了外面 / 再点图标关不掉」。
     *
     * ★ v1.5.8：500ms → **120ms**。
     *   500ms 太长 —— 用户「快速连点」Dock 按钮时（两次点击间隔通常 200~400ms），
     *   第二次点击正好落在抑制窗口里，被整条忽略 → 表现为「点了没反应」。
     *   要压制的其实只是**同一根手指的 down→up**（一般 100ms 内），
     *   120ms 足够吞掉它，又不会误伤正常的连点。
     */
    private const val SUPPRESS_MS = 120L

    private var lastOutsideDismissAt = 0L

    /** 本次 show() 是否应该忽略（刚被点外面关掉，处于同一手势的收尾阶段） */
    fun shouldSuppressShow(): Boolean =
        SystemClock.elapsedRealtime() - lastOutsideDismissAt < SUPPRESS_MS

    /**
     * 弹窗内容 View 的 setOnTouchListener 里调用：
     * 是窗外点击（ACTION_OUTSIDE）就记抑制戳并执行关闭，返回 true。
     */
    fun onOutsideTouch(ev: MotionEvent, close: () -> Unit): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) {
            noteOutsideDismiss()
            close()
            return true
        }
        return false
    }

    private fun noteOutsideDismiss() {
        lastOutsideDismissAt = SystemClock.elapsedRealtime()
    }

    /**
     * 生成「贴近锚点图标」的窗口参数（含统一的 flags / format）。
     *
     * @param anchor  触发弹窗的 Dock 图标 View；拿不到或尚未布局时退回旧行为（Dock 上方居中）
     * @param content 弹窗内容 View，仅用于预估 wrap_content 的自然宽度做水平收敛
     * @param widthPx 弹窗宽度，默认 WRAP_CONTENT
     */
    fun anchoredParams(
        context: Context,
        anchor: View?,
        content: View?,
        widthPx: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val gap = dp(context, 10)
        val edge = dp(context, 8)

        if (anchor == null || anchor.width == 0 || anchor.height == 0) {
            // 兜底：保持旧行为（Dock 内侧居中）
            p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            p.x = 0
            p.y = Prefs.dockHeightPx(context) + Prefs.dockMarginPx(context) + dp(context, 24)
            return p
        }

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorCenterX = loc[0] + anchor.width / 2

        if (Prefs.dockAtBottom(context)) {
            // BOTTOM 重力的 y 自屏幕底边向上算：弹窗底边落在图标顶边上方 gap 处，
            // 不依赖弹窗自身高度（WRAP_CONTENT 时高度事前不可知）。
            p.gravity = Gravity.BOTTOM or Gravity.START
            p.y = (dm.heightPixels - loc[1] + gap).coerceAtLeast(0)
        } else {
            // Dock 在顶部：弹窗改到图标下方
            p.gravity = Gravity.TOP or Gravity.START
            p.y = (loc[1] + anchor.height + gap).coerceAtLeast(0)
        }

        val w = when (widthPx) {
            WindowManager.LayoutParams.MATCH_PARENT -> screenW
            WindowManager.LayoutParams.WRAP_CONTENT -> naturalWidth(content)
            else -> widthPx
        }
        p.x = if (w > 0) {
            // 以图标中心对准弹窗中心，再收敛回屏幕内
            (anchorCenterX - w / 2)
                .coerceIn(edge, (screenW - w - edge).coerceAtLeast(edge))
        } else {
            // 宽度预估失败：贴图标左侧摆放
            (loc[0] - edge).coerceAtLeast(edge)
        }
        return p
    }

    /** 预估 wrap_content 弹窗的自然宽度（拿不到返回 0，调用方退回贴图标左侧） */
    private fun naturalWidth(content: View?): Int {
        if (content == null) return 0
        return runCatching {
            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            content.measuredWidth
        }.getOrDefault(0)
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
