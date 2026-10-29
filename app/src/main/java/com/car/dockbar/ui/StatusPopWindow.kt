package com.car.dockbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Logx

/**
 * 空调 / 车辆状态提示浮窗 —— 对齐吉利 oneOS Dock 的 `window/BasePopWindow`：
 *
 * - 独立 WindowManager 窗口：`TYPE_APPLICATION_OVERLAY` + `PixelFormat.TRANSLUCENT`
 * - flags = `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH`（0x40028）
 * - `autoDisMissTime` 到期后自动淡出消失（默认 3000ms，对应 BasePopWindow.autoDisMissTime）
 * - 弹窗期间再触发（继续调空调）→ 刷新内容并把倒计时重新计满，不会闪断
 * - 点击弹窗外部（FLAG_WATCH_OUTSIDE_TOUCH）立即消失
 */
class StatusPopWindow(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var content: LinearLayout? = null
    private var textView: TextView? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    /** 消失回调（用于让服务知道弹窗已收起） */
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    /**
     * 显示 / 刷新状态弹窗。
     *
     * @param text   状态文案，如「主驾 22.5°　风速 3 档　内循环」
     * @param autoDismissMs 自动消失时间，0 = 不自动消失
     */
    fun show(text: String, autoDismissMs: Long) {
        main.post {
            if (content != null) {
                textView?.text = text
                resetTimer(autoDismissMs)
                Logx.d("StatusPop：刷新 $text")
                return@post
            }
            runCatching {
                wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
                    // ★ v1.4.91：整体放大 1.5 倍（用户要求）。
                    //   padding 与字号同步放大，视觉上整个弹窗正好是 1.5 倍，
                    //   不会出现"字大字边小"的失衡感。
                    val padX = dp(30)
                    val padY = dp(18)
                    setPadding(padX, padY, padX, padY)
                }
                val tv = TextView(ctx).apply {
                    this.text = text
                    setTextColor(0xFFEDEFF3.toInt())
                    // ★ 用户要求：状态文字放大 1 倍（15sp → 30sp），车机上一眼可读；
                    //   ★ v1.4.91：再放大 1.5 倍 → **45sp**；允许两行（AC 拆到第二行），整体居中
                    textSize = 45f
                    maxLines = 2
                    gravity = Gravity.CENTER
                }
                box.addView(tv)
                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // ★ v1.4.43 加 FLAG_NOT_TOUCHABLE：弹窗纯展示，触摸完全透传到下层，
                    //   显示期间不再挡住用户操作（对齐吉利 BasePopWindow 的纯提示定位）
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    // y 由服务在 show() 前设置（贴着 Dock 内侧）
                    y = popupY
                    x = popupX
                }
                content = box
                textView = tv
                params = p
                box.alpha = 0f
                wm?.addView(box, p)
                // 淡入
                box.animate().alpha(1f).setDuration(180L).start()
                resetTimer(autoDismissMs)
                Logx.d("StatusPop：显示 $text（${autoDismissMs}ms 后自动消失）")
            }.onFailure {
                Logx.e("StatusPop：添加失败", it)
                content = null
                textView = null
            }
        }
    }

    /** 淡出并移除窗口 */
    fun hide() {
        main.removeCallbacks(dismissRunnable)
        val box = content ?: return
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("StatusPop：移除失败", it) }
            if (content === box) {
                content = null
                textView = null
            }
            onDismissed?.invoke()
        }.start()
    }

    fun dismissNow() = hide()

    /** 由服务设置弹窗在屏幕上的位置 */
    var popupY: Int = 0
    var popupX: Int = 0

    private fun resetTimer(delayMs: Long) {
        main.removeCallbacks(dismissRunnable)
        if (delayMs > 0) main.postDelayed(dismissRunnable, delayMs)
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 服务销毁时调用，立刻清掉窗口，避免泄漏 */
    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        val box = content
        content = null
        textView = null
        if (box != null) runCatching { wm?.removeViewImmediate(box) }
    }
}
