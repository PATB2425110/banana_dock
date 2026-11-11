// ┌──────────────────────────────────────────────────────────────┐
// │ ★ 本弹窗的可见元素编号（纯注释，不影响任何运行逻辑）★           │
// │  P09 起 —— 空调控制弹窗                                    │
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
 * Dock 内嵌的空调控制浮窗 —— 点击「主驾温度 / 副驾温度 / 风速」时弹出，
 * 在 Dock 之上提供一个可交互的空调控制面板（双温都走同一面板，满足「双温都需要这个逻辑」）。
 *
 * 遵循 [DockPopupChrome] 统一规范：
 *  - TYPE_APPLICATION_OVERLAY + PixelFormat.TRANSLUCENT，
 *    flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
 *  - 位置贴着触发它的 Dock 图标；点击面板外部立即关闭；不提供 × 按钮
 *  - 超时（默认 6s）自动收起
 *  - 订阅 [VehicleController] 状态，数值随车机实时刷新
 */
class HvacControlPopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    /** 自动收起超时（毫秒）。每次操作都会把倒计时重新计满。 */
    private val AUTO_DISMISS_MS = 6000L

    // --- 各数值 / 按钮控件引用 ---
    private var tvDriverTemp: TextView? = null
    private var tvPassengerTemp: TextView? = null
    private var tvFan: TextView? = null
    private var btnRecirc: TextView? = null
    private var btnAir: TextView? = null
    private var btnVent: TextView? = null
    private var btnHeat: TextView? = null

    /** 消失回调（用于让服务知道控制面板已收起，从而恢复状态浮窗等） */
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    private val listener: (VehicleController.State) -> Unit = { st ->
        main.post { render(st) }
    }

    /**
     * 显示 / 刷新控制面板。
     * 已显示时仅重置自动收起倒计时（不重建，避免闪断）。
     *
     * @param anchor 触发面板的 Dock 图标，用于把面板贴在它附近
     */
    fun show(anchor: View? = null) {
        main.post {
            if (content != null) {
                resetTimer()
                return@post
            }
            // 刚被「点外面」关掉 → 忽略同一手势 up 又触发的 show（否则关不掉）
            if (DockPopupChrome.shouldSuppressShow()) return@post
            runCatching { buildAndShow(anchor) }
                .onFailure {
                    Logx.e("HvacControl：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dock_popup_card)
            val pad = dp(16)
            setPadding(pad, dp(12), pad, dp(12))
        }

        // 标题行（统一规范：不放 × 关闭按钮）
        card.addView(TextView(ctx).apply {
            // ── [P09] 标题 ───────────────────────
            text = "空调控制"
            setTextColor(DockIconView.popupFg)
            textSize = 16f
            typeface = AppFont.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })

        // 主驾温度行
        tvDriverTemp = valueText()
        card.addView(row(
            // ── [P10] 主驾温度区 ────────────────────
            label = "主驾",
            minus = { VehicleController.tempStep(ctx, true, -1) },
            plus = { VehicleController.tempStep(ctx, true, 1) },
            valueView = tvDriverTemp!!
        ))

        // 副驾温度行
        tvPassengerTemp = valueText()
        card.addView(row(
            // ── [P11] 副驾温度区 ────────────────────
            label = "副驾",
            minus = { VehicleController.tempStep(ctx, false, -1) },
            plus = { VehicleController.tempStep(ctx, false, 1) },
            valueView = tvPassengerTemp!!
        ))

        card.addView(divider())

        // 风速行：− 值 +（值点击 = 切换 AUTO）
        tvFan = valueText()
        val fanRow = row(
            // ── [P12] 风速区 ──────────────────────
            label = "风速",
            minus = { VehicleController.fanStep(ctx, -1) },
            plus = { VehicleController.fanStep(ctx, 1) },
            valueView = tvFan!!
        )
        tvFan!!.setOnClickListener { VehicleController.toggleFanAuto(ctx) }
        card.addView(fanRow)

        // 底部一排快捷开关：循环 / 风向 / 通风 / 加热
        val quickRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        btnRecirc = makeBtn("内/外").apply {
            setOnClickListener { VehicleController.toggleRecirc(ctx) }
        }
        btnAir = makeBtn("风向").apply {
            setOnClickListener { VehicleController.cycleAirDirection(ctx) }
        }
        btnVent = makeBtn("通风").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatVent
                val next = (cur + 1) % (VehicleController.SEAT_LEVEL_MAX + 1)
                VehicleController.setSeatVentLevel(ctx, next)
            }
        }
        btnHeat = makeBtn("加热").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatHeat
                val next = (cur + 1) % (VehicleController.SEAT_LEVEL_MAX + 1)
                VehicleController.setSeatHeatLevel(ctx, next)
            }
        }
        quickRow.addView(btnRecirc, quickParams())
        quickRow.addView(btnAir, quickParams())
        quickRow.addView(btnVent, quickParams())
        quickRow.addView(btnHeat, quickParams())
        card.addView(quickRow)

        // 提示行
        val hint = TextView(ctx).apply {
            // ── [P13] 关闭提示 ─────────────────────
            text = "点击空白处关闭"
            setTextColor(DockIconView.popupFgDim)
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(hint)

        // --- 窗口参数：统一规范 —— 贴着触发图标（DockPopupChrome 负责位置 + flags）---
        val p = DockPopupChrome.anchoredParams(ctx, anchor, card)

        content = card
        params = p
        // 点击面板外部（FLAG_WATCH_OUTSIDE_TOUCH）立即关闭（统一规范）
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()

        // 订阅车控状态，实时刷新数值
        VehicleController.addListener(listener)
        render(VehicleController.state)
        resetTimer()
        Logx.d("HvacControl：显示（双温 + 风速控制面板）")
    }

    /** 把所有控件按当前状态刷新 */
    private fun render(st: VehicleController.State) {
        tvDriverTemp?.text = tempLabel(st.driverTempC)
        tvPassengerTemp?.text = tempLabel(st.passengerTempC)
        tvFan?.text = fanLabel(st.fanLevel, st.fanAuto)
        tvFan?.setTextColor(
            if (st.fanAuto) DockIconView.accentColor else DockIconView.popupFg
        )

        btnRecirc?.text = if (st.recircInside) "内循环" else "外循环"
        btnRecirc?.setTextColor(
            if (st.recircInside) DockIconView.accentColor else DockIconView.popupFg
        )

        btnAir?.text = "风向 ${airShort(st.airDirection)}"

        btnVent?.text = "通风 ${if (st.seatVent > 0) st.seatVent else "关"}"
        btnVent?.setTextColor(
            if (st.seatVent > 0) DockIconView.accentColor else DockIconView.popupFg
        )
        btnHeat?.text = "加热 ${if (st.seatHeat > 0) st.seatHeat else "关"}"
        btnHeat?.setTextColor(
            if (st.seatHeat > 0) DockIconView.accentColor else DockIconView.popupFg
        )
    }

    /** 淡出并移除窗口 */
    fun hide() {
        main.removeCallbacks(dismissRunnable)
        val box = content ?: return
        VehicleController.removeListener(listener)
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("HvacControl：移除失败", it) }
            if (content === box) {
                content = null
                params = null
            }
            onDismissed?.invoke()
        }.start()
    }

    /** 服务销毁时调用，立刻清掉窗口，避免泄漏 */
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
    // 小工具
    // =====================================================================

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    /** 一行：标签 [−] 值 [+] */
    private fun row(
        label: String,
        minus: () -> Unit,
        plus: () -> Unit,
        valueView: TextView
    ): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }
        val lab = TextView(ctx).apply {
            text = label
            setTextColor(DockIconView.popupFgDim)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }
        val minusBtn = makeBtn("−", sp = 22f).apply {
            minWidth = dp(52)
            setOnClickListener { minus(); resetTimer() }
        }
        val plusBtn = makeBtn("+", sp = 22f).apply {
            minWidth = dp(52)
            setOnClickListener { plus(); resetTimer() }
        }
        valueView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER
        }
        r.addView(lab)
        r.addView(minusBtn)
        r.addView(valueView)
        r.addView(plusBtn)
        return r
    }

    /** 通用圆角按钮（用 bg_item_press 提供点击水波纹） */
    private fun makeBtn(text: String, sp: Float = 15f): TextView {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(DockIconView.popupFg)
            textSize = sp
            typeface = AppFont.bold(ctx)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
            minHeight = dp(48)
            minWidth = dp(64)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
        }
    }

    /** 底部快捷开关的等宽布局参数（可拉伸、留间隙） */
    private fun quickParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    /** 大号数值显示（温度 / 风速） */
    private fun valueText(): TextView = TextView(ctx).apply {
        setTextColor(DockIconView.popupFg)
        textSize = 22f
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        minWidth = dp(80)
        includeFontPadding = false
    }

    private fun divider(): View = View(ctx).apply {
        setBackgroundColor(0x1FFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(6); bottomMargin = dp(6) }
    }

    private fun tempLabel(t: Float?): String {
        if (t == null) return "OFF"
        val max = Prefs.tempMaxC(ctx)
        val min = Prefs.tempMinC(ctx)
        if (t >= max) return "HI"
        if (t <= min) return "LO"
        return String.format("%.1f°", t)
    }

    private fun fanLabel(level: Int, auto: Boolean): String = when {
        auto -> "AUTO"
        level <= 0 -> "OFF"
        else -> "$level 档"
    }

    /** 风向位掩码 → 紧凑中文（1=脸 2=脚 4=霜） */
    private fun airShort(bits: Int): String = when (bits and 0x07) {
        1 -> "脸"
        2 -> "脚"
        4 -> "霜"
        3 -> "脸脚"
        5 -> "脸霜"
        6 -> "脚霜"
        7 -> "全"
        else -> "—"
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
