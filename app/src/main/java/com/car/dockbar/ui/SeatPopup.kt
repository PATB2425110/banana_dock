// ┌──────────────────────────────────────────────────────────────┐
// │ ★ 本弹窗的可见元素编号（纯注释，不影响任何运行逻辑）★           │
// │  P14 起 —— 座椅弹窗                                    │
// └──────────────────────────────────────────────────────────────┘

package com.car.dockbar.ui

import android.content.Context
import android.graphics.Typeface
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
import com.car.dockbar.vehicle.VehicleController

/**
 * 座椅弹窗 —— 点击 Dock「座椅」按钮弹出（不再进座椅设置 App）。
 *
 * 布局（按用户示意图）：
 *  第一行：座椅1 / 座椅2 / 座椅3（座位选择，选中项字体变蓝）
 *  第二行：主驾通风（档位）/ 副驾通风（档位）
 *  第三行：主驾加热（档位）/ 副驾加热（档位）
 *
 * 遵循 [DockPopupChrome] 统一规范，风格与 [AirDirectionPopup] 一致：
 *  bg_card 圆角卡、贴着触发图标、点弹窗外面关闭、无 × 按钮、8 秒自动消失。
 * 激活（档位 > 0 / 被选中）= 字体变蓝（[DockIconView.accentColor]）。
 */
class SeatPopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val AUTO_DISMISS_MS = 8000L

    private val seatButtons = mutableListOf<TextView>()
    /** 当前座椅记忆位置（1/2/3），读不到为 null —— 由 SEAT_NUM 0x2d410f00 主动读取 */
    private var currentSeat: Int? = null

    private var btnVentDriver: TextView? = null
    private var btnVentPassenger: TextView? = null
    private var btnHeatDriver: TextView? = null
    private var btnHeatPassenger: TextView? = null

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    private val listener: (VehicleController.State) -> Unit = { st ->
        main.post { render(st) }
    }

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
                    Logx.e("座椅弹窗：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        computeScale(ctx, 240)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dock_popup_card)
            val pad = dp(12)
            setPadding(pad, dp(8), pad, dp(8))
        }

        // 标题行（统一规范：不放 × 关闭按钮）
        card.addView(TextView(ctx).apply {
            // ── [P14] 标题 ───────────────────────
            text = "座椅"
            setTextColor(DockIconView.popupFg)
            textSize = sp(15f)
            typeface = AppFont.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        })

        // 第一行：座椅1 / 座椅2 / 座椅3 —— ★ 座椅记忆的三个位置，点击应用对应记忆位
        val seatRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        seatButtons.clear()
        for (i in 0 until 3) {
            // ── [P19] 记忆位按钮 ×3 ─────────────────
            val btn = funcBtn("座椅${i + 1}").apply {
                setOnClickListener { v ->
                    VehicleController.applySeatMemory(ctx, i + 1)
                    // 视觉反馈：点击后短暂高亮；1.5 秒后重新按「当前实际位置」渲染
                    (v as? TextView)?.setActive(true)
                    main.postDelayed({
                        currentSeat = VehicleController.readSeatMemoryIndex(ctx)
                        render(VehicleController.state)
                    }, 1500L)
                    resetTimer()
                }
            }
            seatButtons.add(btn)
            seatRow.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        card.addView(seatRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        // 第二行：主驾通风 / 副驾通风
        val ventRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // ── [P15] 主驾座椅通风 ───────────────────
        btnVentDriver = funcBtn("主驾通风 关").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatVent
                // ★ 用户要求：从高档位往低调（关 → 最高档 → … → 关）
                VehicleController.setSeatVentLevel(
                    ctx, (cur + VehicleController.SEAT_LEVEL_MAX) % (VehicleController.SEAT_LEVEL_MAX + 1)
                )
                resetTimer()
            }
        }
        // ── [P17] 副驾座椅通风 ───────────────────
        btnVentPassenger = funcBtn("副驾通风 关").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatVentPassenger
                VehicleController.setSeatVentLevelPassenger(
                    ctx, (cur + VehicleController.SEAT_LEVEL_MAX) % (VehicleController.SEAT_LEVEL_MAX + 1)
                )
                resetTimer()
            }
        }
        ventRow.addView(btnVentDriver, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        ventRow.addView(btnVentPassenger, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        card.addView(ventRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })

        // 第三行：主驾加热 / 副驾加热
        val heatRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // ── [P16] 主驾座椅加热 ───────────────────
        btnHeatDriver = funcBtn("主驾加热 关").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatHeat
                // ★ 从高档位往低调
                VehicleController.setSeatHeatLevel(
                    ctx, (cur + VehicleController.SEAT_LEVEL_MAX) % (VehicleController.SEAT_LEVEL_MAX + 1)
                )
                resetTimer()
            }
        }
        // ── [P18] 副驾座椅加热 ───────────────────
        btnHeatPassenger = funcBtn("副驾加热 关").apply {
            setOnClickListener {
                val cur = VehicleController.state.seatHeatPassenger
                VehicleController.setSeatHeatLevelPassenger(
                    ctx, (cur + VehicleController.SEAT_LEVEL_MAX) % (VehicleController.SEAT_LEVEL_MAX + 1)
                )
                resetTimer()
            }
        }
        heatRow.addView(btnHeatDriver, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        heatRow.addView(btnHeatPassenger, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        card.addView(heatRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 窗口参数：统一规范 —— 贴着触发图标
        val p = DockPopupChrome.anchoredParams(ctx, anchor, card)

        content = card
        params = p
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()

        VehicleController.addListener(listener)
        // ★ 打开时先读一次当前座椅记忆位置，让对应按钮保持高亮
        currentSeat = VehicleController.readSeatMemoryIndex(ctx)
        render(VehicleController.state)
        resetTimer()
        Logx.d("座椅弹窗：显示（当前记忆位=$currentSeat；座椅选择 + 主副驾通风/加热档位）")
    }

    private fun funcBtn(text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sp(14f)
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        setTextColor(DockIconView.popupFg)
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
        minHeight = dp(40)
        minWidth = dp(96)
        isClickable = true
    }

    /** 激活（档位 > 0 / 被选中）= 字体变蓝，与风向弹窗一致 */
    private fun TextView.setActive(on: Boolean) {
        setTextColor(if (on) DockIconView.accentColor else DockIconView.popupFg)
        alpha = if (on) 1f else 0.85f
    }

    private fun render(st: VehicleController.State) {
        // ★ 座椅记忆按钮：读出当前位置就高亮它；读不到才全部普通色
        //   （旧实现只能「点了才亮 1.5 秒」，看不出当前究竟在哪个位置）
        val cur = currentSeat
        seatButtons.forEachIndexed { i, btn -> btn.setActive(cur != null && cur == i + 1) }

        btnVentDriver?.let {
            it.text = if (st.seatVent > 0) "主驾通风 ${st.seatVent} 档" else "主驾通风 关"
            it.setActive(st.seatVent > 0)
        }
        btnVentPassenger?.let {
            it.text = if (st.seatVentPassenger > 0) "副驾通风 ${st.seatVentPassenger} 档" else "副驾通风 关"
            it.setActive(st.seatVentPassenger > 0)
        }
        btnHeatDriver?.let {
            it.text = if (st.seatHeat > 0) "主驾加热 ${st.seatHeat} 档" else "主驾加热 关"
            it.setActive(st.seatHeat > 0)
        }
        btnHeatPassenger?.let {
            it.text = if (st.seatHeatPassenger > 0) "副驾加热 ${st.seatHeatPassenger} 档" else "副驾加热 关"
            it.setActive(st.seatHeatPassenger > 0)
        }
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
                .onFailure { Logx.e("座椅弹窗：移除失败", it) }
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

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    }

    // ★ v1.4.48：弹窗整体放大 2.5 倍
    private var SCALE = 2.5f

    /** 按屏幕可用高度收敛放大倍数（naturalHdp = 未放大时的内容总高估算，dp） */
    private fun computeScale(context: Context, naturalHdp: Int) {
        val dm = context.resources.displayMetrics
        val dockPx = com.car.dockbar.util.Prefs.dockHeightPx(context) +
                com.car.dockbar.util.Prefs.dockMarginPx(context)
        val avail = (dm.heightPixels - dockPx - 40 * dm.density).coerceAtLeast(dm.heightPixels / 2f)
        val naturalPx = naturalHdp * dm.density
        SCALE = minOf(2.5f, avail / naturalPx).coerceAtLeast(1f)
    }

    private fun dp(v: Int): Int = (v * SCALE * ctx.resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = v * SCALE
}
