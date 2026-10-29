package com.car.dockbar.ui.state

import android.os.Handler

/**
 * Dock 显示/隐藏状态机 —— 对齐吉利 oneOS Dock 的 dockstate 包设计：
 *
 *   Showing / ShowingImmediate / ShowingTemp / DragShowing
 *   Hiding / DragHiding / Hidden
 *
 * 本实现把它压成 6 个状态 + 一个定时器，行为一致：
 *
 *      HIDDEN ──requestShowImmediate()──▶ SHOWING ──onShowAnimEnd()──▶ SHOWN
 *      HIDDEN ──requestTempShow(d)──▶ TEMP_SHOW ──(d 到点)──▶ HIDING ──onHideAnimEnd()──▶ HIDDEN
 *      SHOWN  ──requestTempShow(d)──▶ TEMP_SHOW（开始倒计时）
 *      SHOWN  ──requestDragShow()──▶ DRAG_SHOW（拖拽/交互期间常驻，取消倒计时）
 *      任意态 ──requestHide()──▶ HIDING
 *
 * 真正的淡入淡出动画由调用方（DockOverlayService）执行，
 * 动画结束回调 onShowAnimEnd() / onHideAnimEnd() 后状态机才推进，
 * 保证"动画中"和"已隐藏"不会重入。
 */
class DockStateMachine(
    private val main: Handler,
    private val cb: Listener
) {

    interface Listener {
        /** 开始淡入（含从哪个状态进来） */
        fun onShowStart(from: DockState)
        /** 淡入结束，Dock 已完全可见 */
        fun onShown()
        /** 开始淡出，reason 说明为什么隐藏 */
        fun onHideStart(reason: HideReason)
        /** 淡出结束，Dock 已完全隐藏（窗口可能已 removeView） */
        fun onHidden()
    }

    enum class HideReason { MANUAL, AUTO, STOP }

    /**
     * 初始状态：HIDDEN。
     * 服务建好 Dock 后会调 [requestShowImmediate] 走一遍淡入，
     * 与吉利"开机后 Dock 淡入出现"一致。
     */
    var state: DockState = DockState.HIDDEN
        private set

    /**
     * ★★ v1.4.71：true = 前台应用命中「**打开时隐藏 Dock**」名单 → Dock 强制隐藏。
     *
     * v1.4.71 之前这里是 `keepShownByApp`（语义相反：命中就常驻），
     * 且还配着一整套「空闲自动隐藏」倒计时 —— 两者都按用户要求删掉了。
     */
    var hiddenByApp: Boolean = false

    /**
     * ★ v1.4.73：**临时显示**的倒计时（只对 TEMP_SHOW 生效）。
     *
     * ⚠ 这**不是**已删除的「空闲自动隐藏」：
     *   - 空闲自动隐藏：常驻态（SHOWN）也会因无操作而消失 → 已按用户要求删除
     *   - 临时显示倒计时：从底部上滑唤出 Dock 后，5 秒自动收起 → 本次新增
     * 状态为 SHOWN（常驻）时**永远不倒计时**，只有 requestTempShow 进来的才倒。
     */
    private val hideRunnable = Runnable { onTempShowTimeout() }
    private var hideAtMs: Long = 0L

    // ------------------------------------------------------------------
    // 对外事件
    // ------------------------------------------------------------------

    /** 立即显示（如开机、权限刚授予、主界面开关打开） */
    fun requestShowImmediate() {
        if (state == DockState.SHOWN) return
        val from = state
        state = DockState.SHOWING
        cb.onShowStart(from)
    }

    /**
     * 临时显示并倒计时自动消失（空调/音量等状态提示，对齐吉利的 autoDisMissTime）。
     */
    fun requestTempShow(delayMs: Long) {
        val from = state
        if (state == DockState.SHOWN) {
            // 已显示：只切到 TEMP_SHOW，不重播动画
            state = DockState.TEMP_SHOW
            scheduleTempHide(delayMs)
            return
        }
        state = DockState.SHOWING
        cb.onShowStart(from)
        // 动画结束后再进入 TEMP_SHOW
        pendingTempDelayMs = delayMs
    }

    /** 拖拽/长按这类交互期间常驻显示，不自动消失 */
    fun requestDragShow() {
        val from = state
        if (state == DockState.SHOWN) {
            state = DockState.DRAG_SHOW
            return
        }
        state = DockState.SHOWING
        cb.onShowStart(from)
        pendingTempDelayMs = 0L
    }

    /** 隐藏（手动关闭 / 自动 / 服务销毁） */
    fun requestHide(reason: HideReason = HideReason.MANUAL) {
        if (state == DockState.HIDDEN) {
            cb.onHidden()
            return
        }
        if (state == DockState.HIDING) return
        state = DockState.HIDING
        cb.onHideStart(reason)
    }

    /**
     * 用户与 Dock 交互了（点击图标、拖拽、调空调）。
     * 临时显示态会刷新倒计时；空闲自动隐藏开启时也会顺延。
     */
    fun onUserInteraction() {
        // ★ v1.4.73：只有「临时显示」态才刷新倒计时（常驻态永不自动消失）
        if (state == DockState.TEMP_SHOW && hideAtMs > 0L) {
            scheduleTempHide(hideAtMs - System.currentTimeMillis())
        }
    }

    // ------------------------------------------------------------------
    // 动画结束回调（由调用方驱动动画后回填）
    // ------------------------------------------------------------------

    fun onShowAnimEnd() {
        when (state) {
            DockState.SHOWING -> {
                state = if (pendingTempDelayMs > 0L) {
                    val d = pendingTempDelayMs
                    pendingTempDelayMs = 0L
                    DockState.TEMP_SHOW.also { scheduleTempHide(d) }
                } else {
                    DockState.SHOWN
                }
                cb.onShown()
            }
            DockState.HIDING -> { /* 用户中途又点了显示，交给外层处理 */ }
            else -> {}
        }
    }

    fun onHideAnimEnd() {
        state = DockState.HIDDEN
        cb.onHidden()
    }

    /**
     * ★★ v1.4.71：按「打开时隐藏 Dock 的应用名单」切换显隐。
     *
     * @param on true = 前台应用在隐藏名单里 → **立刻隐藏 Dock**；
     *              false = 前台应用不在名单里 → **恢复显示 Dock**。
     *
     * 语义翻转自 v1.4.71（之前是「命中就常驻」，与用户预期相反）。
     */
    fun applyHiddenByApp(on: Boolean) {
        if (hiddenByApp == on) {
            // 状态没变也要保证一致性（首次进入时 hiddenByApp 已是 false）
            if (on && state != DockState.HIDDEN && state != DockState.HIDING) {
                requestHide(HideReason.AUTO)
            }
            return
        }
        hiddenByApp = on
        if (on) {
            main.removeCallbacks(hideRunnable)
            hideAtMs = 0L
            requestHide(HideReason.AUTO)
        } else {
            if (state == DockState.HIDDEN) requestShowImmediate()
        }
    }

    fun dispose() {
        main.removeCallbacks(hideRunnable)
    }

    // ------------------------------------------------------------------
    // 临时显示倒计时（v1.4.73）
    // ------------------------------------------------------------------

    private fun scheduleTempHide(delayMs: Long) {
        val d = delayMs.coerceAtLeast(0L)
        hideAtMs = System.currentTimeMillis() + d
        main.removeCallbacks(hideRunnable)
        if (d == 0L) {
            hideRunnable.run()
        } else {
            main.postDelayed(hideRunnable, d)
        }
    }

    private fun onTempShowTimeout() {
        hideAtMs = 0L
        if (hiddenByApp) return
        if (state == DockState.TEMP_SHOW) {
            state = DockState.HIDING
            cb.onHideStart(HideReason.AUTO)
        }
    }

    /** 临时显示态还剩多少毫秒（<=0 表示没有倒计时） */
    fun remainingMs(): Long =
        if (hideAtMs > 0L) (hideAtMs - System.currentTimeMillis()).coerceAtLeast(0L) else 0L

    // ------------------------------------------------------------------

    private var pendingTempDelayMs: Long = 0L
}

/** Dock 的六个显示态 */
enum class DockState { HIDDEN, SHOWING, SHOWN, TEMP_SHOW, DRAG_SHOW, HIDING }
