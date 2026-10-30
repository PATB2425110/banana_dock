// ┌──────────────────────────────────────────────────────────────┐
// │ ★ 本弹窗的可见元素编号（纯注释，不影响任何运行逻辑）★           │
// │  P20 起 —— 风向弹窗                                    │
// └──────────────────────────────────────────────────────────────┘

package com.car.dockbar.ui

import android.content.Context
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
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController

/**
 * 风向独立开关小弹窗 —— 点击 Dock 上的「风向」按钮弹出。
 *
 * 与 [HvacControlPopup] 同一套 WindowManager 方案，但更小、更轻：
 *  - 三个独立开关：吹脸 / 吹脚 / 吹前挡，每个单独开 / 关（位掩码组合，至少保留一项）
 *    ★ v3.13.13：按钮改成「**系统风向矢量图标 + 文字**」——
 *      图标直接用系统 Dock（com.geely.dockbar）的 ic_blowing_face / _foot / _window。
 *  - 5 秒自动消失；点击面板外部立即关闭（统一规范 [DockPopupChrome]，无 × 按钮）
 *  - 位置贴着触发它的「风向」图标
 *  - 订阅 [VehicleController] 状态，开关实时反映当前风向组合
 */
class AirDirectionPopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    private val AUTO_DISMISS_MS = 5000L

    private var btnFace: IconBtn? = null
    private var btnFoot: IconBtn? = null
    private var btnDefrost: IconBtn? = null

    /**
     * ★ v3.13.13：图标按钮（系统风向矢量）。
     * ★ v3.13.14：按用户要求**去掉下面的文字标签**，只留图标。
     */
    private class IconBtn(
        val root: LinearLayout,
        val img: android.widget.ImageView
    ) {
        /** 激活态：图标染强调色，未激活用普通前景色 + 0.6 透明度 */
        fun setActive(on: Boolean) {
            val c = if (on) DockIconView.accentColor else DockIconView.popupFg
            img.setColorFilter(c)
            root.alpha = if (on) 1f else 0.6f
        }
    }

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    private val listener: (VehicleController.State) -> Unit = { st ->
        main.post { render(st.airDirection) }
    }

    /**
     * @param anchor 触发弹窗的 Dock「风向」图标，用于把面板贴在它附近
     */
    fun show(anchor: View? = null) {
        main.post {
            // ★★ v1.4.67：抑制「同一手势的收尾」。
            //   弹窗带 FLAG_NOT_TOUCH_MODAL：点 Dock 图标时，down 让弹窗收到
            //   ACTION_OUTSIDE 而关闭，up 却照样落到图标上再触发一次 show()。
            //   不压制的话 = 弹窗关了又立刻弹回来，表现为「再点图标关不掉」。
            //   （原先只有 HvacControlPopup 做了这个压制，其余弹窗都漏了。）
            if (DockPopupChrome.shouldSuppressShow()) return@post
            if (content != null) {
                resetTimer()
                return@post
            }
            runCatching { buildAndShow(anchor) }
                .onFailure {
                    Logx.e("AirDirection：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        // 未放大时内容总高约 104dp（内边距 20 + 标题 24 + 图标按钮行 42 + 间距 8）
        // ★ v3.13.14：去掉按钮文字标签后，按钮行从 58dp 降到 42dp
        computeScale(ctx, 104)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            val pad = dp(14)
            setPadding(pad, dp(10), pad, dp(10))
        }

        // 标题行（统一规范：不放 × 关闭按钮）
        card.addView(TextView(ctx).apply {
            // ── [P20] 标题 ───────────────────────
            text = "风向"
            setTextColor(DockIconView.popupFg)
            textSize = sp(16f)
            typeface = AppFont.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })

        // 三个独立开关
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnFace = makeIconBtn(R.drawable.ic_blowing_face).apply {
            root.setOnClickListener { toggle(AirFlowDrawable.FACE) }
        }
        btnFoot = makeIconBtn(R.drawable.ic_blowing_foot).apply {
            root.setOnClickListener { toggle(AirFlowDrawable.FOOT) }
        }
        btnDefrost = makeIconBtn(R.drawable.ic_blowing_window).apply {
            root.setOnClickListener { toggle(AirFlowDrawable.DEFROST) }
        }
        // 顺序：① 吹前挡 ② 吹脸 ③ 吹脚
        row.addView(btnDefrost?.root, quickParams())
        row.addView(btnFace?.root, quickParams())
        row.addView(btnFoot?.root, quickParams())
        card.addView(row)

        // 窗口参数：统一规范 —— 贴着触发图标（DockPopupChrome 负责位置 + flags）
        val p = DockPopupChrome.anchoredParams(ctx, anchor, card)

        content = card
        params = p
        // 点击面板外部（FLAG_WATCH_OUTSIDE_TOUCH）立即关闭（统一规范）
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()

        VehicleController.addListener(listener)
        render(VehicleController.state.airDirection)
        resetTimer()
        Logx.d("AirDirection：显示（3 个独立风向开关）")
    }

    /** 单独开 / 关某个风向位（异或）；全部关掉时忽略，至少保留一项 */
    private fun toggle(bit: Int) {
        val cur = VehicleController.state.airDirection
        val next = cur xor bit
        if (next == 0) {
            // 不允许全部关闭：保持当前状态，仅把倒计时重新计满
            resetTimer()
            return
        }
        VehicleController.setAirDirection(ctx, next)
        resetTimer()
    }

    private fun render(bits: Int) {
        btnFace?.setActive(bits and AirFlowDrawable.FACE != 0)
        btnFoot?.setActive(bits and AirFlowDrawable.FOOT != 0)
        btnDefrost?.setActive(bits and AirFlowDrawable.DEFROST != 0)
    }
    fun hide() {
        main.removeCallbacks(dismissRunnable)
        val box = content ?: return
        // ★ 立即置空：快速连点时 isShowing 能马上反映「已关」
        content = null
        params = null
        VehicleController.removeListener(listener)
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("AirDirection：移除失败", it) }
            onDismissed?.invoke()
        }.start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        val box = content
        content = null
        params = null
        if (box != null) {
            VehicleController.removeListener(listener)
            runCatching { wm?.removeViewImmediate(box) }
        }
    }

    // =====================================================================
    // 小工具（与 HvacControlPopup 保持一致）
    // =====================================================================

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    private fun makeBtn(text: String, sizeSp: Float = 15f): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(DockIconView.popupFg)
            textSize = sp(sizeSp)
            typeface = AppFont.bold(ctx)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
            minHeight = dp(48)
            minWidth = dp(72)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
        }
    }

    /**
     * ★ v3.13.13：图标按钮。
     * ★ v3.13.14：按用户要求去掉文字标签，只留图标。
     * @param iconRes 系统风向矢量（ic_blowing_face / _foot / _window）
     */
    private fun makeIconBtn(iconRes: Int): IconBtn {
        val img = android.widget.ImageView(ctx).apply {
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isClickable = true
            addView(img)
        }
        return IconBtn(root, img)
    }

    /** 高亮激活态：开启用强调色，关闭用普通前景色 */
    private fun TextView.setActive(on: Boolean) {
        setTextColor(if (on) DockIconView.accentColor else DockIconView.popupFg)
        alpha = if (on) 1f else 0.6f
    }


    private fun quickParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    // ★ v1.4.50：整体放大 2 倍（用户反馈原尺寸在车机上偏小、不好点）
    private var SCALE = 2.0f

    /**
     * 按屏幕可用高度收敛放大倍数，避免放大后超出屏幕。
     * @param naturalHdp 未放大时的内容总高估算（dp）
     */
    private fun computeScale(context: Context, naturalHdp: Int) {
        val dm = context.resources.displayMetrics
        val dockPx = Prefs.dockHeightPx(context) + Prefs.dockMarginPx(context)
        val avail = (dm.heightPixels - dockPx - 40 * dm.density).coerceAtLeast(dm.heightPixels / 2f)
        val naturalPx = naturalHdp * dm.density
        SCALE = minOf(2.0f, avail / naturalPx).coerceAtLeast(1f)
    }

    private fun dp(v: Int): Int = (v * SCALE * ctx.resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = v * SCALE
}
