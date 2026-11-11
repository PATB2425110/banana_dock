package com.car.dockbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Prefs

/**
 * Dock 上方的「状态小字」—— 把通过长按实现的功能当前状态显示出来
 * （目前是双温同步开关 + AC 开关；以后长按新增的开关态都可往这里追加）。
 *
 * 与 [HvacControlPopup] 同一套 WindowManager 方案，flags 用
 * NOT_FOCUSABLE + NOT_TOUCH_MODAL，不拦截 Dock 上的触摸。
 *
 * ⚠️ 行为：**显示 5 秒后自动淡出消失**（不再常驻）。
 *    每次 show() 会重置倒计时，所以连续切换时会一直保持可见，松手 5 秒后收起。
 */
class DockStatusLabel(private val ctx: Context) {

    /** 显示多久后自动消失（ms） */
    companion object {
        private const val AUTO_DISMISS_MS = 5000L
        /** 文字尺寸基数 sp（已按需求放大 3 倍：12sp → 36sp） */
        private const val TEXT_SIZE_SP = 36f
    }

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var textView: TextView? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { fadeOutAndRemove() }

    val isShowing: Boolean
        get() = content != null

    /** 显示 / 刷新状态小字。active=true 用强调色，false 用次级前景色。5 秒后自动消失。 */
    fun show(text: String, active: Boolean) {
        main.post {
            if (content == null) build()
            textView?.text = text
            textView?.setTextColor(if (active) DockIconView.accentColor else DockIconView.fgColorDim)
            textView?.alpha = if (active) 1f else 0.8f
            content?.alpha = 1f
            // 每次刷新都重置倒计时
            main.removeCallbacks(dismissRunnable)
            main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
        }
    }

    /** 淡出后移除窗口 */
    private fun fadeOutAndRemove() {
        val box = content ?: return
        box.animate().alpha(0f).setDuration(200L).withEndAction { destroy() }.start()
    }

    /** 仅刷新文字（窗口已存在时） */
    fun setText(text: String, active: Boolean) = show(text, active)

    private fun build() {
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dock_popup_card)
            val pad = dp(12)
            setPadding(pad, dp(5), pad, dp(5))
        }
        val tv = TextView(ctx).apply {
            textSize = TEXT_SIZE_SP
            typeface = AppFont.bold(ctx)
            includeFontPadding = false
        }
        card.addView(tv)
        textView = tv

        val atBottom = Prefs.dockAtBottom(ctx)
        val dockH = Prefs.dockHeightPx(ctx)
        val margin = Prefs.dockMarginPx(ctx)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (atBottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 紧贴 Dock 内侧上方（与 Dock 之间留 6dp 间隙）
            y = (dockH + margin + dp(6)).coerceAtLeast(0)
            x = 0
        }
        content = card
        card.alpha = 0f
        runCatching { wm?.addView(card, p) }
            .onFailure {
                content = null
                textView = null
            }
        card.animate().alpha(1f).setDuration(180L).start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        val box = content
        content = null
        textView = null
        if (box != null) runCatching { wm?.removeViewImmediate(box) }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
