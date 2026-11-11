package com.car.dockbar.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.car.dockbar.R
import com.car.dockbar.model.AppDrawerApp
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx

/**
 * 半透明 2×3 应用快捷面板，作为独立 overlay 显示在 Dock 上方。
 *
 * 遵循 [DockPopupChrome] 统一规范：
 *  1. 位置贴着触发它的「快捷应用」Dock 图标（[show] 传入锚点 View）；
 *  2. 点击面板外部立即关闭；
 *  3. 没有右上角 × 关闭按钮。
 */
class AppDrawerPopup(
    private val context: Context,
    private val onInteraction: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var content: LinearLayout? = null
    var isShowing: Boolean = false
        private set

    // ===== ★ v1.4.97 #11：长按拖动换位（6 个槽位） =====
    /** 6 个槽位当前放的应用（null = 空位） */
    private val slotApps = MutableList<AppDrawerApp?>(AppDrawerApps.MAX_APPS) { null }
    /** 6 个槽位对应的 View，用于换算手指落在哪个槽 */
    private val slotViews = arrayOfNulls<View>(AppDrawerApps.MAX_APPS)
    /** 2×3 网格的宿主容器（换位是局部重建，不重建整个窗口） */
    private var gridHost: LinearLayout? = null
    private var dragFrom = -1
    private var dragTo = -1

    /** 弹窗关闭回调（v1.4.67：用于释放「Dock 常驻」标志） */
    var onDismissed: (() -> Unit)? = null

    /**
     * @param anchor 触发面板的 Dock 图标，用于把面板贴在它附近；为空时退回 Dock 上方居中
     */
    fun show(anchor: View? = null) {
        computeScale(context, 230)
        // ★★ v1.4.67：抑制「同一手势的收尾」——
        //   弹窗带 FLAG_NOT_TOUCH_MODAL：点 Dock 图标时 down 让弹窗收到 ACTION_OUTSIDE
        //   而关闭，up 却照样落到图标上再触发一次 show() → 弹窗关了又立刻弹回来。
        if (DockPopupChrome.shouldSuppressShow()) return
        if (isShowing) {
            dismiss()
            return
        }
        val view = buildContent()
        // 点面板外面立即关闭（规范第 2 条）
        view.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { dismiss() } }
        // ★ 放大 2.5 倍后可能超屏宽：收敛到屏幕内（留边距）
        val screenW = context.resources.displayMetrics.widthPixels
        val wantW = dp(510)
        val finalW = minOf(wantW, screenW - dp(12))
        val params = DockPopupChrome.anchoredParams(context, anchor, view, finalW)
        runCatching {
            windowManager.addView(view, params)
            content = view
            isShowing = true
        }.onFailure {
            Logx.e("应用面板显示失败：${it.javaClass.simpleName} ${it.message}", it)
        }
    }

    fun dismiss() {
        val view = content ?: return
        content = null
        isShowing = false
        dragFrom = -1
        dragTo = -1
        gridHost = null
        for (i in slotViews.indices) slotViews[i] = null
        runCatching { windowManager.removeView(view) }
        onDismissed?.invoke()
    }

    private fun buildContent(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xD91B2029.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x55FFFFFF)
            }
            elevation = dp(16).toFloat()
        }

        // 标题行（规范第 3 条：不放 × 关闭按钮）
        panel.addView(TextView(context).apply {
            text = "快捷应用"
            textSize = sp(15f)
            setTextColor(Color.WHITE)
            typeface = AppFont.bold(context)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
        ))

        val selected = AppDrawerApps.loadSlots(context)
        for (i in 0 until AppDrawerApps.MAX_APPS) slotApps[i] = selected.getOrNull(i)

        gridHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(gridHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        renderGrid()
        return panel
    }

    /** 重画 2×3 网格（换位是局部重建，不重建整个窗口） */
    private fun renderGrid() {
        val host = gridHost ?: return
        host.removeAllViews()
        repeat(2) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(3) { columnIndex ->
                val index = rowIndex * 3 + columnIndex
                row.addView(appCell(index), LinearLayout.LayoutParams(0, dp(86), 1f).apply {
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                })
            }
            host.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(90)
            ))
        }
    }

    /**
     * ★ v1.4.97 #11：长按某个应用 → 进入拖动态，手指移到哪个槽就高亮哪个槽，
     * 松手落到那个槽，立刻保存。
     *
     * ★ v1.4.98：落位改成**两个槽对调**（A ↔ B），不再是「抽出来插进去」把中间的应用
     *   整体挤开一位 —— 只有被拖的和落点这两个位置的应用互换，其它槽保持不动。
     *   拖到空位上就是单纯搬过去（空位那个槽变成空的）。
     */
    private fun setupSlotDrag(v: View, index: Int) {
        v.setOnLongClickListener {
            if (dragFrom != -1) return@setOnLongClickListener true
            dragFrom = index
            dragTo = index
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            highlightDrag()
            Logx.d("快捷应用：长按槽位 $index（${slotApps[index]?.label}），进入拖动")
            true
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragFrom != index) return@setOnTouchListener false
                    val hit = slotAt(ev.rawX, ev.rawY)
                    if (hit != -1 && hit != dragTo) {
                        dragTo = hit
                        highlightDrag()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragFrom != index) return@setOnTouchListener false
                    finishDrag()
                    true
                }
                else -> false
            }
        }
    }

    /** 手指当前落在第几个槽（-1 = 不在网格内） */
    private fun slotAt(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        for (i in 0 until AppDrawerApps.MAX_APPS) {
            val v = slotViews[i] ?: continue
            v.getLocationOnScreen(loc)
            val x = loc[0]; val y = loc[1]
            if (rawX >= x && rawX <= x + v.width && rawY >= y && rawY <= y + v.height) return i
        }
        return -1
    }

    /** 拖动中：被拖的槽浮起，落点槽全亮，其余淡显 */
    private fun highlightDrag() {
        for (i in 0 until AppDrawerApps.MAX_APPS) {
            val v = slotViews[i] ?: continue
            v.alpha = when (i) {
                dragFrom -> 0.9f
                dragTo -> 1f
                else -> 0.5f
            }
            v.elevation = if (i == dragFrom) dp(8).toFloat() else 0f
        }
    }

    /**
     * 松手：**两个槽对调**并保存。
     * 只动 from / to 这两个位置，其它槽原样不动（不再整体移位）。
     */
    private fun finishDrag() {
        val from = dragFrom
        val to = dragTo
        dragFrom = -1
        dragTo = -1
        if (from in 0 until AppDrawerApps.MAX_APPS && to in 0 until AppDrawerApps.MAX_APPS && from != to) {
            val a = slotApps[from]
            val b = slotApps[to]
            slotApps[from] = b
            slotApps[to] = a
            AppDrawerApps.saveSlots(context, slotApps)
            val whoA = a?.label ?: "空位"
            val whoB = b?.label ?: "空位"
            Logx.d("快捷应用：槽位 $from（$whoA）↔ 槽位 $to（$whoB）互换，已保存")
        }
        renderGrid()
    }

    /** @param index 槽位 0..5（[slotApps] 里为 null 就是空位） */
    private fun appCell(index: Int): View {
        val app = slotApps.getOrNull(index)
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(4), dp(3), dp(3))
            background = context.getDrawable(R.drawable.bg_item_press)
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val title = TextView(context).apply {
            textSize = sp(11f)
            setTextColor(0xF2FFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (app == null) {
            icon.setImageResource(R.drawable.ic_apps)
            icon.alpha = 0.30f
            title.text = "空位"
            title.alpha = 0.55f
        } else {
            runCatching { icon.setImageDrawable(context.packageManager.getApplicationIcon(app.packageName)) }
                .onFailure { icon.setImageResource(R.drawable.ic_car) }
            title.text = app.label
            cell.setOnClickListener {
                // ★ 拖动刚结束的那一次 UP 已被 onTouch 消费，不会走到这里
                dismiss()
                onInteraction()
                IntentLauncher.launch(
                    context,
                    app.label,
                    IntentLauncher.Target(pkg = app.packageName, cls = app.activityName),
                    emptyList()
                )
            }
            setupSlotDrag(cell, index)
        }
        cell.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        cell.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        slotViews[index] = cell
        return cell
    }

    // ★ v1.4.48：弹窗整体放大（用户要求 2.5 倍）——但车机屏（1920×1080）高度有限，
    //   固定 2.5 倍会超出屏幕（日志实锤）。改为自适应：min(2.5, 可用高 / 内容自然高)
    private var SCALE = 2.5f

    /** 按屏幕可用高度收敛放大倍数（[naturalHdp] = 未放大时的内容总高估算，dp） */
    private fun computeScale(context: Context, naturalHdp: Int) {
        val dm = context.resources.displayMetrics
        val dockPx = com.car.dockbar.util.Prefs.dockHeightPx(context) +
                com.car.dockbar.util.Prefs.dockMarginPx(context)
        val avail = (dm.heightPixels - dockPx - 40 * dm.density).coerceAtLeast(dm.heightPixels / 2f)
        val naturalPx = naturalHdp * dm.density
        SCALE = minOf(2.5f, avail / naturalPx).coerceAtLeast(1f)
    }

    private fun dp(value: Int): Int =
        (value * SCALE * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun sp(v: Float): Float = v * SCALE
}
