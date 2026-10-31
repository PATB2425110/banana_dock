// ┌──────────────────────────────────────────────────────────────┐
// │ ★ 本弹窗的可见元素编号（纯注释，不影响任何运行逻辑）★           │
// │  P01 起 —— 温度弹窗                                    │
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.SensorBridge
import com.car.dockbar.vehicle.VehicleController

/**
 * 温度弹窗（主驾 / 副驾）—— 点击 Dock 温度组合按钮的中间值弹出（不再进系统空调页面）。
 *
 * 布局（按用户示意图）：
 *  左栏：温度档位竖排列表（15.5~28.5℃，步进 0.5），当前温度对应档位蓝字高亮，点某项直接设温；
 *  右栏 主驾：前挡加热 / 后挡加热 / 极速降温 / 极速升温 / 方向盘加热（档位）
 *  右栏 副驾：双温同步 / 车内温度 / 高于25°通风 / 低于15°加热
 *
 * 遵循 [DockPopupChrome] 统一规范，风格与 [AirDirectionPopup] 一致：
 *  bg_card 圆角卡、贴着触发图标、点弹窗外面关闭、无 × 按钮、5 秒自动消失。
 * 激活状态一律「字体变蓝」（[DockIconView.accentColor]）。
 */
class TemperaturePopup(
    private val ctx: Context,
    /** "driver" = 主驾弹窗 / "passenger" = 副驾弹窗 */
    val zone: String
) {
    companion object {
        const val ZONE_DRIVER = "driver"
        const val ZONE_PASSENGER = "passenger"
    }

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val AUTO_DISMISS_MS = 8000L
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshSensors()
            main.postDelayed(this, 1000L)
        }
    }

    // 左栏：标准 ScrollView 滚轮 —— 完整温度档位（15.5~28.5 步进 0.5），滚动停止后居中项生效
    // ★ v1.4.41 高亮改「固定居中半透明遮罩」：颜色不再跟字走（滑快了会错位），
    //   可视区正中盖一层半透明条，文字在遮罩下滚过
    private val tempCells = mutableListOf<Pair<Float, TextView>>()
    private var tempScroll: ScrollView? = null
    private var lastScrollY = -1
    /**
     * ★ v1.4.77：滚动停止检测（**固定间隔自检**，不被滚动事件取消）。
     *
     * 旧实现的毛病：每次 onScrollChanged 都 `removeCallbacks + postDelayed(300)`，
     *   而快速甩动时 ScrollView 会**持续惯性滚动**几百毫秒，每次滚动都把定时器往后推
     *   → settle 迟迟不触发 → 用户感觉「快速滑动不会选中」。
     *
     * 现在：定时器每 150ms 自己检查一次「scrollY 还在不在变」，
     *   还在变就再等 150ms（最多 8 轮 / 1.2s），停了就立刻 settle。
     *   滚动事件**不再取消**这个定时器，所以响应最快 150ms。
     */
    private val scrollIdleCheck: Runnable = Runnable {
        val sc = tempScroll
        if (sc != null && lastScrollY != sc.scrollY && settleWaitCount < 8) {
            settleWaitCount++
            lastScrollY = sc.scrollY
            main.postDelayed(scrollIdleCheck, 150L)
            return@Runnable
        }
        settleWaitCount = 0
        onScrollSettled()
    }

    /** ★ v1.4.77：settle 等待轮次计数 */
    private var settleWaitCount = 0
    /** 本弹窗对应温区（滚动回调里用） */
    private var isDriverZone = true
    /** 滚轮上下占位高度（让首/尾项能滚到居中） */
    private var wheelPadY = 0
    /** 用户正在滚动（滚动引发的温度同步不再反向滚列表） */
    private var userScrolling = false
    /** ★ v1.4.76：程序侧自动滚动中（避免把自己的滚动当成用户手势） */
    private var autoScrolling = false
    /** 上次渲染到的温度（判断是否需要程序侧滚动对齐） */
    private var lastRenderedTemp: Float? = null

    // 右栏 主驾
    private var btnDefrostFront: TextView? = null
    private var btnDefrostRear: TextView? = null
    private var btnSteerHeat: TextView? = null

    // 右栏 副驾
    private var btnDualSync: TextView? = null
    private var tvCabinTemp: TextView? = null
    private var btnVentHot: TextView? = null
    private var btnHeatCold: TextView? = null

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
                    Logx.e("温度弹窗：添加失败", it)
                    content = null
                }
        }
    }

    private fun buildAndShow(anchor: View?) {
        computeScale(ctx, 270)
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        SensorBridge.init(ctx)
        val isDriver = zone == ZONE_DRIVER
        isDriverZone = isDriver

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            val pad = dp(12)
            setPadding(pad, dp(8), pad, dp(8))
        }

        // 标题行（统一规范：不放 × 关闭按钮）
        card.addView(TextView(ctx).apply {
            text = if (isDriver) "主驾温度" else "副驾温度"
            setTextColor(DockIconView.popupFg)
            textSize = sp(15f)
            typeface = AppFont.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        })

        // 两栏主体
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // ---------- 左栏：ScrollView 温度滚轮（29 项完整档位） ----------
        // ★ 用户要求：滑动列表，滚动停止后「居中那一格」的温度设为当前温度；
        //   最高温显示 HI、最低温显示 LO；首尾加占位，保证 HI/LO 也能滚到居中。不点击设温。
        val itemH = dp(44)
        val viewH = 5 * itemH                       // 可视区 5 格
        val padY = viewH / 2 - itemH / 2            // 上下占位：让首/尾项能滚到正中
        wheelPadY = padY
        val tempCol = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isClickable = true
            clipToPadding = false
        }
        val tempList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tempCells.clear()
        // ★★ v1.4.76：**倒序** —— 最高温在【最上面】、最低温在【最下面】。
        //   用户反馈「lo 在最上面 hi 在最下面」是反的。改成和车机空调面板一致：
        //   往上滑 = 更热（HI 在顶），往下滑 = 更冷（LO 在底）。
        //   注意：倒序只改 tempCells 的**顺序**，每个元素携带的温度值仍然正确，
        //   所以「索引 → 滚动位置 → 温度」的换算公式全部不用动。
        var t = VehicleController.TEMP_MAX_C
        var isFirst = true
        var isLast = false
        while (t >= VehicleController.TEMP_MIN_C - 0.01f) {
            val value = t
            isLast = value - 0.5f < VehicleController.TEMP_MIN_C - 0.01f
            val item = TextView(ctx).apply {
                text = when {
                    isFirst -> "HI"      // ★ 最高温显示 HI（最上面）
                    isLast -> "LO"       // ★ 最低温显示 LO（最下面）
                    else -> fmtTemp(value)
                }
                textSize = sp(16f)
                typeface = AppFont.bold(ctx)
                gravity = Gravity.CENTER
                setTextColor(DockIconView.popupFg)
            }
            isFirst = false
            tempCells.add(value to item)
            tempList.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, itemH
            ))
            t -= 0.5f
        }
        tempList.setPadding(0, padY, 0, padY)
        tempCol.addView(tempList)
        tempScroll = tempCol
        // 滚动监听：静止判定（停止滚动即生效）+ 标记用户滚动中
        tempCol.viewTreeObserver.addOnScrollChangedListener {
            val sc = tempScroll ?: return@addOnScrollChangedListener
            onTempScrolled(sc.scrollY)
        }
        // ★ v1.4.41 高亮条：FrameLayout 双层 —— 底层 ScrollView，顶层固定居中的半透明遮罩。
        //   遮罩不随内容滚动，滑多快都不会错位
        val wheelWrap = android.widget.FrameLayout(ctx).apply {
            clipChildren = false
        }
        wheelWrap.addView(tempCol, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, viewH
        ))
        val mask = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x264EA1FF.toInt())                      // 半透明蓝
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), 0x554EA1FF)
            }
            isClickable = false
            isFocusable = false
        }
        wheelWrap.addView(mask, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, itemH, Gravity.CENTER
        ))
        body.addView(wheelWrap, LinearLayout.LayoutParams(dp(96), viewH).apply {
            marginEnd = dp(8)
        })

        // ---------- 右栏 ----------
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (isDriver) {
            // ── [P01] 前挡加热 ─────────────────────
            btnDefrostFront = funcBtn("前挡加热") { VehicleController.toggleDefrostFront(ctx); resetTimer() }
            // ── [P02] 后挡加热 ─────────────────────
            btnDefrostRear = funcBtn("后挡加热") { VehicleController.toggleDefrostRear(ctx); resetTimer() }
            // ── [P03] 极速降温 ─────────────────────
            val fastCool = funcBtn("极速降温") { VehicleController.fastCool(ctx); resetTimer() }
            // ── [P04] 极速升温（★ v3.13.12 新增，紧跟极速降温下面）──
            val fastHeat = funcBtn("极速升温") { VehicleController.fastHeat(ctx); resetTimer() }
            // ── [P05] 方向盘加热 ────────────────────
            btnSteerHeat = funcBtn("方向盘加热 关") { VehicleController.steeringHeatCycle(ctx); resetTimer() }
            rightCol.addView(btnDefrostFront, funcLp())
            rightCol.addView(btnDefrostRear, funcLp())
            rightCol.addView(fastCool, funcLp())
            rightCol.addView(fastHeat, funcLp())
            rightCol.addView(btnSteerHeat, funcLp())
        } else {
            // ── [P05] 双温同步 ─────────────────────
            btnDualSync = funcBtn("双温同步") {
                val next = !Prefs.syncTemp(ctx)
                Prefs.setSyncTemp(ctx, next)
                // ★ v1.4.86：写车机（原来只改本地开关 → 车机收不到命令，按钮等于坏的）
                runCatching { VehicleController.setTempDual(ctx, next) }
                    .onFailure { Logx.e("双温同步：写车机失败", it) }
                render(VehicleController.state)
                resetTimer()
            }
            // ── [P06] 车内温度 ─────────────────────
            tvCabinTemp = valueBox("车内温度")
            // ── [P07] 高于 25℃ 自动开主驾通风 ────────
            btnVentHot = funcBtn("高于25°通风") {
                Prefs.setAutoSeatVentHot(ctx, !Prefs.autoSeatVentHot(ctx))
                render(VehicleController.state)
                resetTimer()
            }
            // ── [P08] 低于 15℃ 自动开主/副驾加热 ────
            btnHeatCold = funcBtn("低于15°加热") {
                Prefs.setAutoSeatHeatCold(ctx, !Prefs.autoSeatHeatCold(ctx))
                render(VehicleController.state)
                resetTimer()
            }
            rightCol.addView(btnDualSync, funcLp())
            rightCol.addView(tvCabinTemp, funcLp())
            rightCol.addView(btnVentHot, funcLp())
            rightCol.addView(btnHeatCold, funcLp())
        }
        body.addView(rightCol, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(body)

        // 窗口参数：统一规范 —— 贴着触发图标
        val p = DockPopupChrome.anchoredParams(ctx, anchor, card)

        content = card
        params = p
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()

        VehicleController.addListener(listener)
        render(VehicleController.state)
        // 打开时滚到当前温度居中
        card.post { scrollToCurrentTemp() }
        refreshSensors()
        main.postDelayed(refreshRunnable, 1000L)
        resetTimer()
        Logx.d("温度弹窗：显示（${if (isDriver) "主驾" else "副驾"}）")
    }

    /** 右栏功能按钮（激活时字体变蓝） */
    private fun funcBtn(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = sp(14f)
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        setTextColor(DockIconView.popupFg)
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
        minHeight = dp(40)
        minWidth = dp(116)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 右栏数值方框（数字蓝字显示实际读数） */
    private fun valueBox(label: String): TextView = TextView(ctx).apply {
        text = "$label --"
        textSize = sp(14f)
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        setTextColor(DockIconView.accentColor)
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_field)
        minHeight = dp(40)
        minWidth = dp(116)
    }

    private fun funcLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }

    private fun TextView.setActive(on: Boolean) {
        setTextColor(if (on) DockIconView.accentColor else DockIconView.popupFg)
    }

    private fun render(st: VehicleController.State) {
        val isDriver = zone == ZONE_DRIVER
        val current = if (isDriver) st.driverTempC else st.passengerTempC
        // ★ 程序侧温度变化（± 按钮 / 双温同步 / 极速降温）且用户没在滚动 → 滚轮平滑对齐到新温度；
        //   用户滚动中则不打断（静止后吸附，遮罩与蓝字自然对准）
        if (current != lastRenderedTemp) {
            if (!userScrolling && current != null) {
                val itemH = dp(44)
                val idx = tempCells.indexOfFirst { kotlin.math.abs(it.first - current) < 0.24f }
                if (idx >= 0) {
                    // ★★ v1.4.76：改用 **scrollTo（瞬时定位）**，不用 smoothScrollTo。
                    //   原因：smoothScrollTo 是动画滚动，每一帧都触发 onScrollChanged →
                    //     → onTempScrolled 把 userScrolling 置 true → 300ms 后
                    //       onScrollSettled 又 smoothScrollTo 吸附 → 又是滚动事件
                    //     → **无限循环**（表现：弹窗自己一直往上/往下滚）。
                    //   而且原来的目标公式 `(idx * itemH - wheelPadY)` 与吸附公式
                    //   `centerIdx * itemH` 差了一个 wheelPadY，两处打架 → 抖动。
                    //   现在统一成 `idx * itemH`（与 onScrollSettled 一致）。
                    val sc = tempScroll
                    if (sc != null) {
                        val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
                        val targetY = (idx * itemH).coerceIn(0, maxScroll)
                        if (sc.scrollY != targetY) {
                            autoScrolling = true
                            sc.scrollTo(0, targetY)
                            lastScrollY = targetY
                            autoScrolling = false
                        }
                    }
                }
                highlightCentered(current)
            }
            lastRenderedTemp = current
        }
        if (isDriver) {
            btnDefrostFront?.let {
                it.text = if (st.defrostFront) "前挡加热 开" else "前挡加热"
                it.setActive(st.defrostFront)
            }
            btnDefrostRear?.let {
                it.text = if (st.defrostRear) "后挡加热 开" else "后挡加热"
                it.setActive(st.defrostRear)
            }
            btnSteerHeat?.let {
                it.text = if (st.steeringHeat > 0) "方向盘加热 ${st.steeringHeat} 档" else "方向盘加热 关"
                it.setActive(st.steeringHeat > 0)
            }
        } else {
            val sync = Prefs.syncTemp(ctx)
            btnDualSync?.let {
                it.text = if (sync) "双温同步 开" else "双温同步 关"
                it.setActive(sync)
            }
            btnVentHot?.let {
                val on = Prefs.autoSeatVentHot(ctx)
                it.text = if (on) "高于25°通风 开" else "高于25°通风 关"
                it.setActive(on)
            }
            btnHeatCold?.let {
                val on = Prefs.autoSeatHeatCold(ctx)
                it.text = if (on) "低于15°加热 开" else "低于15°加热 关"
                it.setActive(on)
            }
        }
    }

    /** 传感器类读数（车内温度 / AQI），拉取式，每秒刷新；每秒顺带重试 init（车控可能迟连） */
    private fun refreshSensors() {
        if (zone == ZONE_DRIVER) return
        SensorBridge.init(ctx)
        val cabin = SensorBridge.readFloat(SensorBridge.TEMP_INDOOR)
        tvCabinTemp?.text = "车内温度 " + if (cabin == null) "--" else String.format("%.1f°", cabin)
    }

    /** 档位显示：超出可调范围显示 "--" */
    private fun fmtTemp(t: Float): String =
        if (t < VehicleController.TEMP_MIN_C || t > VehicleController.TEMP_MAX_C) "--"
        else String.format("%.1f°", t)

    /** 滚动中：标记用户滚动 + 实时给遮罩下的格子染蓝（纯几何计算，跟随滚动位置不会错位）+ 静止判定 */
    private fun onTempScrolled(scrollY: Int) {
        // ★ v1.4.76：程序自己的滚动（autoScrolling）不算「用户滚动」，
        //   否则会把自己的定位当成用户手势 → 触发 settle → 又滚 → 死循环。
        if (autoScrolling) {
            lastScrollY = scrollY
            return
        }
        if (lastScrollY != scrollY) {
            lastScrollY = scrollY
            userScrolling = true
            highlightCentered(null)
            // ★ v1.4.77：**不再 removeCallbacks 取消定时器** ——
            //   取消会让惯性滚动无限推迟 settle（快速滑动不选中的根因）。
            //   定时器每 150ms 自检一次「还在不在滚」，最快 150ms 内就 settle。
            settleWaitCount = 0
            main.removeCallbacks(scrollIdleCheck)
            main.postDelayed(scrollIdleCheck, 150L)
        }
        resetTimer()
    }

    /** 滚动静止：先吸附对齐整格（文字精确停在遮罩中间），再把居中项温度设为当前 */
    private fun onScrollSettled() {
        val sc = tempScroll ?: return
        if (tempCells.isEmpty()) return
        settleWaitCount = 0
        val itemH = dp(44)
        val centerIdx = ((sc.scrollY + sc.height / 2 - wheelPadY) / itemH).toInt()
            .coerceIn(0, tempCells.size - 1)
        // ★ 吸附：项 i 居中时 scrollY = i×itemH（推导：pad + i×itemH + itemH/2 = scrollY + h/2，
        //   而 pad = wheelPadY = h/2 − itemH/2）—— v1.4.42 旧公式多减了 wheelPadY，
        //   每次吸附都会往上拉 2 格（「一直往上吸」的根因）；并钳制在滚动范围内
        val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
        val targetY = (centerIdx * itemH).coerceIn(0, maxScroll)
        Logx.d(
            "温度滚轮吸附：scrollY=${sc.scrollY} h=${sc.height} pad=$wheelPadY " +
                "idx=$centerIdx target=$targetY（max=$maxScroll）"
        )
        // ★ v1.4.76：吸附也改成瞬时 scrollTo —— smoothScrollTo 的动画会再触发
        //   onScrollChanged → onTempScrolled → settle → 又吸附 → 循环。
        if (sc.scrollY != targetY) {
            autoScrolling = true
            sc.scrollTo(0, targetY)
            lastScrollY = targetY
            autoScrolling = false
        }
        userScrolling = false
        val value = tempCells[centerIdx].first
        val current = if (isDriverZone) VehicleController.state.driverTempC
        else VehicleController.state.passengerTempC
        if (current != null && kotlin.math.abs(current - value) < 0.24f) {
            lastRenderedTemp = current
            highlightCentered(current)
            return // 没变，不写
        }
        VehicleController.setTempC(ctx, isDriverZone, value)
        Logx.d("温度滚轮：静止生效 ${"%.1f".format(value)}℃（${if (isDriverZone) "主驾" else "副驾"}）")
    }

    /** 实时给遮罩下的格子染蓝（纯几何：按滚动位置算居中项，与温度值解耦，滑动再快也不会错位）；
     *  指定 [current] 时把该温度项也一并染蓝（打开/程序侧对齐用） */
    private fun highlightCentered(current: Float?) {
        val sc = tempScroll ?: return
        val itemH = dp(44)
        val centerIdx = ((sc.scrollY + sc.height / 2 - wheelPadY) / itemH).toInt()
            .coerceIn(0, tempCells.size - 1)
        for ((index, pair) in tempCells.withIndex()) {
            val selected = index == centerIdx ||
                    (current != null && kotlin.math.abs(current - pair.first) < 0.24f)
            pair.second.setTextColor(
                if (selected) DockIconView.accentColor else DockIconView.popupFg
            )
        }
    }

    /** 打开时把列表滚到当前温度居中（scrollY = idx×itemH，同吸附公式） */
    private fun scrollToCurrentTemp() {
        val sc = tempScroll ?: return
        val current = if (isDriverZone) VehicleController.state.driverTempC
        else VehicleController.state.passengerTempC
        val itemH = dp(44)
        val idx = if (current == null) tempCells.size / 2
        else tempCells.indexOfFirst { kotlin.math.abs(it.first - current) < 0.24f }
        if (idx >= 0) {
            val maxScroll = (tempCells.size * itemH + 2 * wheelPadY - sc.height).coerceAtLeast(0)
            sc.scrollTo(0, (idx * itemH).coerceIn(0, maxScroll))
        }
        lastScrollY = sc.scrollY
        lastRenderedTemp = current
    }

    fun hide() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        val box = content ?: return
        // ★ 立即置空：快速连点时 isShowing 能马上反映「已关」，toggle 语义才正确
        content = null
        params = null
        VehicleController.removeListener(listener)
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("温度弹窗：移除失败", it) }
            onDismissed?.invoke()
        }.start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
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

    private fun dp(v: Int): Int = (v * SCALE * ctx.resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = v * SCALE
}
