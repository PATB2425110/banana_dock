package com.car.dockbar.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.car.dockbar.App
import com.car.dockbar.R
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.model.DockEntries
import com.car.dockbar.model.DockEntry
import com.car.dockbar.ui.state.DockState
import com.car.dockbar.ui.state.DockStateMachine
// HideReason 是 DockStateMachine 的内部枚举，必须从嵌套类名导入
import com.car.dockbar.ui.state.DockStateMachine.HideReason
import com.car.dockbar.ui.AirFlowDrawable
import com.car.dockbar.ui.AirDirectionPopup
import com.car.dockbar.ui.DockStatusLabel
import com.car.dockbar.ui.HvacControlPopup
import com.car.dockbar.ui.RecircDrawable
import com.car.dockbar.ui.SeatVentDrawable
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.SensorBridge
import com.car.dockbar.hvac.HvacApp
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController

/**
 * Dock 悬浮栏前台服务。
 *
 * 悬浮窗 = 一条全透明窗口（PixelFormat.TRANSLUCENT，窗体级每像素 Alpha），
 * 里面放一个 [DockLayout]，条目由 [DockEntries] 动态配置（支持自定义增删）。
 *
 * 对齐吉利 oneOS Dock（com.geely.dockbar）的关键设计：
 *  - 窗体级透明：LayoutParams.format = PixelFormat.TRANSLUCENT（对应吉利 format = -3）
 *  - flags：FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH（0x40028）
 *  - 显示/隐藏由 [DockStateMachine] 状态机驱动，淡入淡出用 alpha + translationY
 *  - 调空调时弹 [StatusPopWindow] 状态浮窗，到期自动消失（对应 BasePopWindow.autoDisMissTime）
 *  - 底色跟随日夜模式切换（对应 BaseDockLayout.setDockBg 的日夜切换）
 */
class DockOverlayService : Service() {

    companion object {
        const val ACTION_RELOAD = "com.car.dockbar.action.RELOAD"
        const val ACTION_START = "com.car.dockbar.action.START"
        const val ACTION_STOP = "com.car.dockbar.action.STOP"
        /** 图标管理页改完条目后发这个广播 */
        const val ACTION_ENTRIES_CHANGED = "com.car.dockbar.action.ENTRIES_CHANGED"
        /** 「打开时隐藏 Dock 的应用名单」改动后发这个广播，服务立刻按当前前台应用重算 */
        const val ACTION_APPS_CHANGED = "com.car.dockbar.action.APPS_CHANGED"

        /** ★ v1.4.71：当前是否因「打开时隐藏 Dock 名单」而隐藏（主界面状态栏用） */
        @Volatile
        @JvmStatic
        var hiddenByApp: Boolean = false
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "dock_overlay"
        private const val REFRESH_INTERVAL_MS = 2500L

        /** 反色采样间隔：每秒截取 dock 条带算亮度，屏幕内容变了最多 1 秒内换色 */
        private const val CONTRAST_INTERVAL_MS = 1000L

        /** Dock 悬浮窗服务是否正在运行（供启动心跳判断是否已启动 Dock） */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 服务是否真的在跑（与 Activity 同进程，可直接读，用于主界面显示真实状态） */
        @Volatile
        var alive: Boolean = false
            private set

        /** 悬浮窗是否真的挂上去了（挂不上时大概率是没给悬浮窗权限） */
        @Volatile
        var attached: Boolean = false
            private set

        /** 最后一次失败原因，主界面直接展示 */
        @Volatile
        var lastError: String = ""
            private set
    }

    private lateinit var wm: WindowManager
    private var root: DockLayout? = null
    /** 纯色模式下，系统桌面前台时底色已切成透明。 */
    private var solidHomeClear: Boolean? = null
    private var homePkg: String? = null
    private var params: WindowManager.LayoutParams? = null
    /** View 当前是否已 addView 到 WindowManager */
    private var viewAttached = false

    private val main = Handler(Looper.getMainLooper())

    // --- 各控件引用（同一控件可能被用户重复添加，用列表保存） ---
    private val tempDriverViews = mutableListOf<DockValueView>()
    private val tempPassengerViews = mutableListOf<DockValueView>()
    private val fanViews = mutableListOf<DockValueView>()
    private val musicViews = mutableListOf<DockMusicView>()
    private val lyricViews = mutableListOf<DockIconView>()
    private val recircViews = mutableListOf<DockIconView>()
    private val ventViews = mutableListOf<DockIconView>()
    private val seatHeatViews = mutableListOf<DockIconView>()
    private val airViews = mutableListOf<DockIconView>()
    /** ★ v1.5.11 #13：前挡加热开关（默认不在 Dock，用户手动添加才有） */
    private val defrostFrontViews = mutableListOf<DockIconView>()
    /** ★ v3.1：后挡除霜 / 方向盘加热（同样默认不在 Dock） */
    private val defrostRearViews = mutableListOf<DockIconView>()
    private val steeringHeatViews = mutableListOf<DockIconView>()

    // --- 显示/隐藏状态机（对齐吉利 oneOS Dock 的 dockstate 包） ---
    private var stateMachine: DockStateMachine? = null

    // --- 空调 / 车辆状态弹窗（对齐吉利 window/BasePopWindow，到期自动消失） ---
    private var statusPop: StatusPopWindow? = null

    // --- 空调控制浮窗（点击温度 / 风速弹出，双温共用） ---
    private var hvacPop: HvacControlPopup? = null

    // ★ v1.4.73：底部手势条（Dock 隐藏时挂载，用上滑手势临时唤出 Dock）
    private var gestureView: View? = null
    private var gestureAttached = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureTracking = false
    private var gestureFromExtension = false
    private var gestureSwiped = false
    private var gestureForwarding = false
    private var gestureParams: WindowManager.LayoutParams? = null

    // --- 风向独立开关小弹窗（点击风向按钮弹出，5s 自动消失） ---
    private var airDirPop: AirDirectionPopup? = null

    // --- 车机设置旁的 2×3 快捷应用面板 ---
    private var appDrawerPop: AppDrawerPopup? = null
    private var airQualityPop: TemperaturePopup? = null
    private var seatPopup: SeatPopup? = null

    // --- Dock 上方常驻状态小字（显示长按类功能的当前状态，如双温同步） ---
    private var statusLabel: DockStatusLabel? = null
    /** 上一次观测到的 AC 开关状态（用于只在真正变化时弹一次状态小字） */
    // （v1.4.44 已移除 lastAcOn：AC 变化不再触发 Dock 上方状态小字）

    /** 上一次的空调相关状态签名，用于识别"用户刚调了空调" → 弹状态浮窗 */
    private var lastAcState: VehicleController.State? = null

    /** 淡入淡出进行中标记，避免动画重入 */
    private var animating = false

    private var receiver: BroadcastReceiver? = null
    private var running = false

    // --- 自动反色（真实背景采样） ---
    private var contrastThread: HandlerThread? = null
    private var contrastHandler: Handler? = null
    /** 当前是否"白字"（暗背景）。默认白字，首次采样后按真实背景纠正 */
    private var darkText = true
    /** 截屏特权是否可用（可用则用真实屏幕内容；不可用则退回墙纸底色，仍每秒轮询） */
    private var screenCaptureOk = false
    /** 截屏权限不足时是否已提示过（只提示一次） */
    private var contrastHintShown = false

    private val ticker = object : Runnable {
        override fun run() {
            // 车控 / 媒体读取都是 binder IPC，放主线程会掉帧甚至 ANR。
            // 结果通过 VehicleController/MediaBridge 内部的 main.post 回到主线程渲染。
            work?.post {
                runCatching { VehicleController.refresh(this@DockOverlayService) }
                    .onFailure { Logx.e("车控刷新异常", it) }
                runCatching { MediaBridge.refresh(this@DockOverlayService) }
                    .onFailure { Logx.e("媒体刷新异常", it) }
            }
            refreshLyricIcons()
            main.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    // --- 后台工作线程（车控 / 媒体 IO） ---
    private var workThread: HandlerThread? = null
    private var work: Handler? = null

    private fun startWork() {
        if (workThread != null) return
        workThread = HandlerThread("dock-vehicle").apply { start() }
        work = Handler(workThread!!.looper)
    }

    private fun stopWork() {
        work?.removeCallbacksAndMessages(null)
        workThread?.quitSafely()
        workThread = null
        work = null
    }

    /** 「歌词」按钮点亮状态跟随歌词悬浮窗的实际运行状态 */
    private fun refreshLyricIcons() {
        if (lyricViews.isEmpty()) return
        val on = LyricOverlayService.alive && LyricOverlayService.attached
        lyricViews.forEach { it.setActive(on) }
    }

    private val vehicleListener: (VehicleController.State) -> Unit = { st ->
        main.post { bindVehicle(st) }
    }

    private val mediaListener: (MediaBridge.MediaInfo) -> Unit = { mi ->
        main.post { bindMedia(mi) }
    }

    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Logx.d("DockOverlayService onCreate")
        alive = true
        lastError = ""

        // Android 14+ 要求前台服务声明类型；声明缺失/不被允许时这里会抛异常。
        // 捕获后降级成普通服务，至少保证 Dock 能显示出来。
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
            .onFailure {
                lastError = "startForeground 失败：${it.javaClass.simpleName} ${it.message}"
                Logx.e("$lastError → 降级为普通服务继续运行（可能被系统回收）")
            }
        registerReceiver()
        VehicleController.addListener(vehicleListener)
        MediaBridge.addListener(mediaListener)

        if (!canDrawOverlay()) {
            lastError = "没有悬浮窗权限（Settings.canDrawOverlays=false）"
            Logx.e("$lastError —— 请在主界面点「授权悬浮窗权限」，打开后回到本应用会自动显示")
            return
        }

        buildDock()
        startWork()
        work?.post { initVehicleAndMedia() }
        main.postDelayed(ticker, REFRESH_INTERVAL_MS)
        // 活过 15 秒 → 认为本次启动「稳定」，下次启动据此把连续崩溃计数清零
        main.postDelayed({
            runCatching { LogUploader.markStable(this) }
            Logx.d("已稳定运行 15 秒，连续崩溃计数将清零")
        }, LogUploader.STABLE_MS)
        running = true
        isRunning = true
    }

    /** 车控 / 媒体初始化：放到后台线程，避免主线程 binder IPC（结果会 post 回主线程渲染） */
    private fun initVehicleAndMedia() {
        val safe = Prefs.safeLevel(this)
        // ★ 车控始终初始化：读取是安全通道（拿不到只返回 0xff 哨兵），
        //   崩溃源是 watcher 回调（已按通道关掉温度）和写入（由 vehicleWriteEnabled 熔断），
        //   不需要靠「不初始化车控」来保命 —— 那样只会让用户看到一片空白的空调状态。
        runCatching { VehicleController.init(this) }
            .onFailure { Logx.e("车控初始化异常", it) }
        // 安全等级 >= 3：跳过媒体接口
        if (safe < 3 && Prefs.mediaEnabled(this)) {
            runCatching { MediaBridge.start(this) }
                .onFailure { Logx.e("媒体初始化异常", it) }
        } else {
            Logx.d("安全模式 L$safe / 媒体开关：跳过 MediaSession 绑定")
        }
        // 车控就绪后上传一条诊断记录：远程确认「通道通不通 / 数值对不对」最有效的一条
        runCatching { uploadDiag() }.onFailure { Logx.e("诊断上传异常", it) }
    }

    /**
     * 上传车控诊断记录。
     *
     * 内容：后端类型、读写方法签名、温度通道、各通道当前 functionId/zone、
     * 首帧解析结果、最近日志尾部。
     * 用户只要装上新包、开一次 Dock，我这边就能判断车控是否真的通了。
     */
    private fun uploadDiag() {
        if (!LogUploader.isEnabled(this)) return
        val st = VehicleController.state
        val chans = VehicleChannels.ALL.joinToString("\n") { ch ->
            "  ${ch.key}: func=0x${VehicleChannels.funcOf(this, ch.key).toString(16)}" +
                    " zone=${VehicleChannels.zoneOf(this, ch.key)}"
        }
        val body = buildString {
            append("===== 车控诊断 =====\n")
            append("安全等级: ").append(Prefs.safeLevel(this@DockOverlayService))
            append("（").append(App.safeLevelDesc(Prefs.safeLevel(this@DockOverlayService))).append("）\n")
            append("后端: ").append(VehicleBridge.backend.label).append('\n')
            append("温度读通道: ").append(VehicleBridge.floatChannelName).append('\n')
            // ★ v1.4.72：点火状态 + 空气质量（事件量通道）
            append("点火状态: ").append(VehicleController.ignitionLabel()).append('\n')
            append("空气质量(车内/车外): ").append(
                SensorBridge.readAirQuality(true)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append(" / ").append(
                SensorBridge.readAirQuality(false)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append('\n')
            append("温度写通道: ").append(VehicleBridge.floatWriteChannelName).append('\n')
            append("连接: connected=").append(st.connected).append('\n')
            append("解析: 主驾温=").append(st.driverTempC)
            append(" 副驾温=").append(st.passengerTempC)
            append(" 风速=").append(st.fanLevel).append(if (st.fanAuto) "(AUTO)" else "")
            append(" 循环=").append(if (st.recircInside) "内" else "外")
            append(" 风向=").append(st.airDirection)
            append(" 座椅通风=").append(st.seatVent)
            append(" 座椅加热=").append(st.seatHeat).append('\n')
            append("通道配置:\n").append(chans).append("\n\n")
            append("===== 最近日志 =====\n")
            append(Logx.snapshot().takeLast(200).joinToString("\n"))
        }
        LogUploader.upload(this, "diag", body, "车控初始化完成")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logx.d("收到关闭指令")
                // 用户主动关闭 = 正常退出，打标记让下次启动不要把这次当成闪退
                LogUploader.markCleanExit(this)
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD, ACTION_ENTRIES_CHANGED -> {
                main.post { reload() }
            }
        }
        if (!running && canDrawOverlay()) {
            buildDock()
            startWork()
            work?.post { initVehicleAndMedia() }
            main.postDelayed(ticker, REFRESH_INTERVAL_MS)
            running = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        Logx.d("DockOverlayService onDestroy")
        teardown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 日夜模式切换 → 换底色 / 渐变（对齐吉利 setDockBg 的日夜切换）
        runCatching { root?.let { applyBackground(it) } }
    }

    private fun teardown() {
        running = false
        isRunning = false
        alive = false
        attached = false
        main.removeCallbacks(ticker)
        stopContrast()
        stopWork()
        VehicleController.removeListener(vehicleListener)
        MediaBridge.removeListener(mediaListener)
        MediaBridge.stop()
        runCatching {
            if (receiver != null) {
                unregisterReceiver(receiver)
                receiver = null
            }
        }
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        viewAttached = false
        root = null
        clearBindings()
        statusPop?.destroy()
        statusPop = null
        hvacPop?.destroy()
        hvacPop = null
        airDirPop?.destroy()
        airDirPop = null
        appDrawerPop?.dismiss()
        appDrawerPop = null
        airQualityPop?.destroy()
        airQualityPop = null
        seatPopup?.destroy()
        seatPopup = null
        statusLabel?.destroy()
        statusLabel = null
        stateMachine?.dispose()
        stateMachine = null
        ForegroundTracker.listener = null
    }

    private fun clearBindings() {
        tempDriverViews.clear()
        tempPassengerViews.clear()
        fanViews.clear()
        musicViews.clear()
        lyricViews.clear()
        recircViews.clear()
        ventViews.clear()
        seatHeatViews.clear()
        airViews.clear()
        defrostFrontViews.clear()
        defrostRearViews.clear()
        steeringHeatViews.clear()
        lastAcState = null   // 重建后重置首帧判定，避免旧状态误触发弹窗
        animating = false
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    // =====================================================================
    // 构建
    // =====================================================================

    private fun reload() {
        Logx.d("重载 Dock 配置")
        val hadRoot = root != null
        stateMachine?.requestHide(HideReason.MANUAL)
        // 取消旧 View 上未完成的动画，否则它的 withEndAction 会在重建后
        // 把"新"窗口当成旧窗口摘掉
        root?.animate()?.cancel()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        viewAttached = false
        root = null
        clearBindings()
        if (!canDrawOverlay()) {
            Logx.e("重载失败：没有悬浮窗权限")
            return
        }
        buildDock()
        startWork()
        if (!hadRoot) {
            work?.post { initVehicleAndMedia() }
        }
        runCatching { bindVehicle(VehicleController.state) }
        runCatching { bindMedia(MediaBridge.info) }
    }

    private fun buildDock() {
        if (!canDrawOverlay()) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 重建前先清掉上一次的常驻状态小字，避免 reload 时叠多个窗口
        statusLabel?.destroy()
        statusLabel = null
        val ctx = this

        val iconScale = Prefs.iconScale(ctx)

        // 自动反色：先按壁纸给初始调色板（首帧用），随后由真实背景采样校正
        applyContrast(ctx)

        val layout = DockLayout(ctx).apply {
            cornerRadius = Prefs.cornerRadiusPx(ctx).toFloat()
            showSeparators = Prefs.showSeparators(ctx)
            separatorColor = if (darkText) 0x33FFFFFF else 0x33000000
            DockIconView.accentColor = Prefs.accentColor(ctx)
            onPressChanged = { down ->
                if (down) stateMachine?.pauseAutoHide() else stateMachine?.onUserIdle()
            }
        }
        // 背景：渐变优先；未启用渐变则用纯色（可跟随日夜模式）
        applyBackground(layout)

        // 按用户配置的条目动态构建（支持自定义应用增删、排序）
        val factory = DockEntryViewFactory(
            ctx,
            onInteraction = { onUserInteraction() },
            // 风速点击同样打开系统原生空调窗口（与温度一致，不再用自绘面板）
            onShowHvac = { toggleSystemHvacWindow() },
            onToggleHvac = { toggleSystemHvacWindow() },
            onShowAirDirection = { v -> showAirDirectionPopup(v) },
            onToggleDualSync = { toggleDualSync() },
            onShowAppDrawer = { v -> showAppDrawer(v) },
            // ★ v1.4.97 #10：长按 D05 → 直接打开设置里的「选择快捷应用」界面
            onOpenAppDrawerSettings = { openAppDrawerSettings() },
            onShowAirQuality = { v, isDriver -> showAirQualityPopup(v, isDriver) },
            onToggleAcLong = { toggleAcFromDock() },
            onShowSeatPopup = { v -> showSeatPopup(v) },
            iconScale = iconScale
        )
        val entries: List<DockEntry> = DockEntries.load(ctx)
        for (e in entries) {
            val built = factory.create(e)
            layout.addItem(
                built.view, built.span, built.separator,
                built.fixedWidthPx, built.flexMinPx, built.flexMaxPx
            )
            when (built.bind) {
                // ★ 组合控件（− 值 +）的数值 View 在 built.valueView 里；
                //   独立值条目兜底直接取 built.view
                BindKind.TEMP_DRIVER -> (built.valueView ?: built.view as? DockValueView)?.let { tempDriverViews += it }
                BindKind.TEMP_PASSENGER -> (built.valueView ?: built.view as? DockValueView)?.let { tempPassengerViews += it }
                BindKind.FAN -> (built.valueView ?: built.view as? DockValueView)?.let { fanViews += it }
                BindKind.MUSIC -> musicViews += built.view as DockMusicView
                BindKind.LYRIC -> lyricViews += built.view as DockIconView
                BindKind.RECIRC -> recircViews += built.view as DockIconView
                BindKind.VENT -> ventViews += built.view as DockIconView
                BindKind.SEAT_HEAT -> seatHeatViews += built.view as DockIconView
                BindKind.AIR -> airViews += built.view as DockIconView
                BindKind.DEFROST_FRONT -> defrostFrontViews += built.view as DockIconView
                BindKind.DEFROST_REAR -> defrostRearViews += built.view as DockIconView
                BindKind.STEERING_HEAT -> steeringHeatViews += built.view as DockIconView
                BindKind.NONE -> {}
            }
        }
        Logx.d("Dock 条目数：${entries.size}（含自定义应用）")

        // --- 窗口参数：窗体级透明（PixelFormat.TRANSLUCENT = 每像素 Alpha） ---
        val dw = Prefs.dockWidthPx(ctx)
        val dh = Prefs.dockHeightPx(ctx)
        val p = WindowManager.LayoutParams(
            if (dw > 0) dw else WindowManager.LayoutParams.MATCH_PARENT,
            dh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 对齐吉利 dock 的 0x40028：不抢焦点 + 非模态 + 监听窗外点击
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = computeY(dh)
        }
        logWindowGeometry(dh, p.y)

        root = layout
        params = p
        layout.alpha = 0f
        runCatching {
            wm.addView(layout, p)
        }.onSuccess {
            viewAttached = true
            attached = true
            lastError = ""
            Logx.d("Dock 已挂载：${if (dw > 0) dw else "MATCH_PARENT"}×$dh @ y=${p.y}")
            // ★ v1.4.93：反射截屏已删除，反色只按墙纸底色算（不再受安全等级影响）
            startContrast()
            setupStateMachine()
            refreshLyricIcons()
            // 记录初始空调状态，避免开机就弹一次状态浮窗
            lastAcState = VehicleController.state
            // Dock 上方状态小字：只在长按切换时弹一次（5s 后自动消失），这里不预显示
            statusLabel = DockStatusLabel(this)
            runCatching { bindVehicle(VehicleController.state) }
            runCatching { bindMedia(MediaBridge.info) }
        }.onFailure {
            attached = false
            viewAttached = false
            root = null
            lastError = "挂载悬浮窗失败：${it.javaClass.simpleName} ${it.message}"
            Logx.e("Dock 挂载失败：$lastError")
            Logx.e("如果是 'permission denied for window type 2038'，说明悬浮窗权限没给或车机有签名白名单限制")
        }
    }

    // =====================================================================
    // 状态机 + 淡入淡出（对齐吉利 animSlideAlpha：alpha + translationY）
    // =====================================================================

    private fun setupStateMachine() {
        stateMachine?.dispose()
        val sm = DockStateMachine(main, object : DockStateMachine.Listener {
            override fun onShowStart(from: DockState) {
                Logx.d("状态机：显示（from=$from）")
                fadeIn()
            }

            override fun onShown() {
                Logx.d("状态机：已显示 state=${stateMachine?.state}")
                // Dock 可见 → 撤掉手势条（避免挡住点击）
                detachGestureStrip()
            }

            override fun onHideStart(reason: HideReason) {
                Logx.d("状态机：隐藏（reason=$reason）")
                fadeOut()
            }

            override fun onHidden() {
                attached = false
                Logx.d("状态机：已隐藏")
                // Dock 藏起来了 → 挂上手势条，等用户从底部上滑
                attachGestureStrip()
            }
        })
        stateMachine = sm
        // 首次进来是 HIDDEN → 触发淡入
        sm.requestShowImmediate()
        // ★ v1.4.71：按「打开时隐藏 Dock 的应用名单」决定显隐（需无障碍服务已在跑）
        attachForegroundTracking()
        // ★ v1.4.73：若一启动就处于隐藏态（前台应用在隐藏名单里），先挂上手势条
        main.postDelayed({ if (!attached) attachGestureStrip() }, 800)
    }

    /** 一次操作结束（点击抬手）：从现在开始按设定时长计时 */
    private fun onUserInteraction() {
        stateMachine?.onUserIdle()
    }

    /** 弹窗还开着，或手指按住：先停表 */
    private fun pauseAutoHide() {
        stateMachine?.pauseAutoHide()
    }

    /**
     * 按「当前前台应用」决定 Dock 是否常驻显示：
     * 命中「常驻显示名单」→ 不自动隐藏、若已隐藏则显示出来；
     * 未命中或拿不到前台应用（无障碍未开启）→ 交还全局空闲自动隐藏规则。
     */
    private fun applyKeepShownForForeground() {
        val pkg = ForegroundTracker.currentPackage
        val hide = if (pkg.isNullOrBlank()) {
            false
        } else {
            val h = Prefs.isDockHideApp(this, pkg)
            if (h) Logx.d("前台 $pkg → Dock 隐藏（名单里该应用开关为关 / 属系统全屏应用）")
            else Logx.d("前台 $pkg → Dock 保持显示（常驻名单命中或未要求隐藏）")
            h
        }
        hiddenByApp = hide
        stateMachine?.applyHiddenByApp(hide)
        syncSolidWithForeground()
    }

    /** 开始监听前台应用变化（由 ForegroundAppService 无障碍服务写入） */
    private fun attachForegroundTracking() {
        ForegroundTracker.listener = { _ -> applyKeepShownForForeground() }
        applyKeepShownForForeground()
    }

    /** 系统桌面包名：默认 HOME，再加上这台车的 launcher3。 */
    private fun systemHomePackage(): String? {
        homePkg?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        if (!pkg.isNullOrBlank() && pkg != "android") homePkg = pkg
        return homePkg
    }

    private fun onSystemHome(): Boolean {
        val pkg = ForegroundTracker.currentPackage ?: return false
        if (pkg == "com.android.launcher3") return true
        return pkg == systemHomePackage()
    }

    /**
     * 纯色模式下，只有系统桌面在前台时把底色改成透明。
     * 其它应用在前台、或还没识别到前台时，保持纯色。
     */
    private fun syncSolidWithForeground() {
        if (Prefs.bgMode(this) != BgMode.SOLID) {
            solidHomeClear = null
            return
        }
        val clear = onSystemHome()
        if (solidHomeClear == clear) return
        solidHomeClear = clear
        val layout = root ?: return
        applyBackground(layout)
        applyContrast(this)
        Logx.d(if (clear) "纯色：系统桌面前台 → 底色透明" else "纯色：离开桌面 → 恢复纯色")
    }

    private fun slideOffset(): Float {
        val base = Prefs.slideOffsetPx(this).toFloat()
        return if (Prefs.dockAtBottom(this)) base else -base
    }

    /** 隐藏时一律向下移动至少一个屏幕高度，确保 Dock 完全离开可视区域。 */
    private fun slideOutOffset(v: View): Float {
        val screenHeight = maxOf(Prefs.screenHeightPx(this), resources.displayMetrics.heightPixels)
        val dockHeight = maxOf(v.height, Prefs.dockHeightPx(this))
        return (screenHeight + dockHeight).toFloat()
    }

    private fun fadeIn() {
        val v = root ?: return
        if (!ensureViewAttached()) {
            // 挂不上就别卡在 SHOWING，直接回 HIDDEN
            animating = false
            stateMachine?.onHideAnimEnd()
            return
        }
        val dur = Prefs.fadeDurationMs(this).coerceAtLeast(0L)
        animating = true
        v.translationY = slideOffset()
        v.alpha = 0f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(dur)
            .withEndAction {
                animating = false
                // 只有"这个 View 还是当前根"才算数，避免 reload 后旧动画打新窗口
                if (root === v) stateMachine?.onShowAnimEnd()
            }
            .start()
        attached = true
    }

    private fun fadeOut() {
        val v = root
        val dur = Prefs.fadeDurationMs(this).coerceAtLeast(0L)
        animating = true
        if (v == null) {
            stateMachine?.onHideAnimEnd()
            return
        }
        v.animate()
            .alpha(0f)
            .translationY(slideOutOffset(v))
            .setDuration(dur)
            .withEndAction {
                animating = false
                if (root === v) detachView()
                stateMachine?.onHideAnimEnd()
            }
            .start()
    }

    private fun ensureViewAttached(): Boolean {
        val v = root ?: return false
        if (viewAttached) return true
        val p = params ?: return false
        return runCatching {
            v.translationY = 0f
            wm.addView(v, p)
            viewAttached = true
            attached = true
            true
        }.getOrElse {
            attached = false
            lastError = "挂载悬浮窗失败：${it.javaClass.simpleName} ${it.message}"
            Logx.e("重新挂载 Dock 失败：$lastError")
            false
        }
    }

    private fun detachView() {
        val v = root ?: return
        if (viewAttached) runCatching { wm.removeViewImmediate(v) }
        viewAttached = false
    }

    // =====================================================================
    // ★ v1.4.73：底部上滑手势条
    // =====================================================================
    //
    // 需求：Dock 被「打开时隐藏名单」里的应用藏起来后，
    //       从屏幕底部往上滑 → 临时显示 Dock 5 秒 → 自动再藏起来。
    //
    // 为什么需要独立窗口：Dock 隐藏时窗口被 removeViewImmediate 摘掉了，
    //   屏幕上已经没有我们的 View，**收不到任何触摸**。
    //   所以要在屏幕最底部常驻一条**透明的手势条**窗口专门接这个上滑。
    //
    // ⚠ 代价：这条手势条会挡住它覆盖区域内的点击。
    //   所以只在 **Dock 隐藏时**才挂载，且高度尽量小（默认 64px）。
    //

    /** 挂载手势条（Dock 隐藏时） */
    private fun attachGestureStrip() {
        if (gestureAttached) return
        if (!Prefs.swipeShowDockEnabled(this)) return
        val density = resources.displayMetrics.density
        val pillH = (12 * density).toInt().coerceAtLeast(8)
        // 96dp 的基础上连续加宽两次 1.5 倍
        val pillW = (216 * density).toInt()
        // 底部偏移：先上移 30px，再下移 10px，再下移 5px。横条外观仍停在这个位置。
        val yOff = (6 * density).toInt() + 15
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0x99FFFFFF.toInt())
                setStroke((density).toInt().coerceAtLeast(1), android.graphics.Color.BLACK)
                cornerRadius = pillH / 2f
            }
            isClickable = false
        }
        // 窗口从屏幕底边拉到横条上沿。横条画在窗口顶部，下面 yOff 像素完全透明。
        val v = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                onGestureTouch(ev, pillH)
                return true
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(pill, FrameLayout.LayoutParams(pillW, pillH, Gravity.TOP))
        }
        val p = WindowManager.LayoutParams(
            pillW,
            pillH + yOff,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 窗口以外的触摸交给下面的应用。延伸区里的单击在松手后另交回去。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        gestureView = v
        gestureParams = p
        runCatching { wm.addView(v, p) }
            .onSuccess {
                gestureAttached = true
                Logx.d("底部手势条已挂载 横条 ${pillW}×${pillH} 距底 ${yOff}px，延伸到底边的区域透明")
            }
            .onFailure {
                Logx.e("底部手势条挂载失败：${it.javaClass.simpleName} ${it.message}")
                gestureAttached = false
                gestureParams = null
            }
    }

    private fun onGestureTouch(ev: MotionEvent, pillH: Int) {
        if (gestureForwarding) return
        val threshold = Prefs.swipeShowDockStripPx(this).coerceAtLeast(24)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = ev.rawX
                gestureDownY = ev.rawY
                gestureTracking = true
                gestureSwiped = false
                gestureFromExtension = ev.y >= pillH
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureTracking || gestureSwiped) return
                val dy = gestureDownY - ev.rawY
                val dx = ev.rawX - gestureDownX
                if (dy > threshold * 1.2f && dy > kotlin.math.abs(dx) * 1.5f) {
                    gestureTracking = false
                    gestureSwiped = true
                    Logx.d("底部上滑手势 → 临时显示 Dock ${Prefs.swipeShowDockMs(this)}ms")
                    stateMachine?.requestTempShow(Prefs.swipeShowDockMs(this))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.rawX - gestureDownX
                val dy = ev.rawY - gestureDownY
                val tap = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) <
                    ViewConfiguration.get(this).scaledTouchSlop
                val forward = ev.actionMasked == MotionEvent.ACTION_UP &&
                    gestureFromExtension && tap && !gestureSwiped
                gestureTracking = false
                if (forward) passThroughTap(gestureDownX, gestureDownY)
                else stateMachine?.onUserIdle()
            }
        }
    }

    /** 延伸区上的单击。窗口先退出触摸，再把这一下交回下面的应用。 */
    private fun passThroughTap(x: Float, y: Float) {
        val svc = ForegroundAppService.instance
        if (svc == null) {
            Logx.d("点击透传失败：前台应用检测没开，延伸区上的单击送不下去")
            return
        }
        gestureForwarding = true
        setGestureTouchable(false)
        val ok = svc.tap(x, y)
        Logx.d("点击透传 ${x.toInt()},${y.toInt()} → $ok")
        main.postDelayed({
            setGestureTouchable(true)
            gestureForwarding = false
        }, 180)
    }

    private fun setGestureTouchable(touchable: Boolean) {
        val p = gestureParams ?: return
        val v = gestureView ?: return
        val bit = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) p.flags and bit.inv() else p.flags or bit
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 卸载手势条（Dock 显示时） */
    private fun detachGestureStrip() {
        if (!gestureAttached) return
        gestureView?.let { runCatching { wm.removeViewImmediate(it) } }
        gestureView = null
        gestureParams = null
        gestureAttached = false
        gestureForwarding = false
    }

    // =====================================================================
    // 空调状态弹窗（自动消失）—— ★ v1.4.40 改成「改了什么就显示什么」
    // =====================================================================

    /**
     * 空调状态浮窗里的温度文案。
     * ★ v3.2：15.5 / 28.5 这两个极值改成 **LO / HI**，跟 Dock 栏温度、
     *   温度滚轮、HvacControlPopup 的显示保持一致（车机 HvacConstants 也是这个约定：
     *   TEMP_C_MIN="15.5" / MIN_SHOW_TAMP="LO"，TEMP_C_MAX="28.5" / MAX_SHOW_TAMP="HI"）。
     */
    private fun tempText(t: Float?): String {
        if (t == null) return "OFF"
        val max = Prefs.tempMaxC(this)
        val min = Prefs.tempMinC(this)
        if (t >= max) return "HI"
        if (t <= min) return "LO"
        return String.format("%.1f°", t)
    }

    /** 空调相关状态变化 → 弹状态浮窗，到期自动消失（首次/服务启动不弹） */
    private fun onAcStateChanged(st: VehicleController.State) {
        if (!Prefs.popupEnabled(this)) return
        // 安全等级 >= 1 时禁用状态弹窗（它会再创建一个 overlay 窗口）
        if (Prefs.safeLevel(this) >= 1) return
        // 空调控制面板正开着时，不再叠加快照状态浮窗（避免挡住操作）
        if (hvacPop?.isShowing == true) return

        val prev = lastAcState
        lastAcState = st
        if (prev == null) return   // 首帧只记录，不弹

        // ★ 用户要求：改了什么就显示什么——逐字段对比，只列本次变化的项
        val parts = mutableListOf<String>()
        if (prev.driverTempC != st.driverTempC) parts += "主驾 ${tempText(st.driverTempC)}"
        if (prev.passengerTempC != st.passengerTempC) parts += "副驾 ${tempText(st.passengerTempC)}"
        if (prev.fanAuto != st.fanAuto || prev.fanLevel != st.fanLevel) {
            // ★ v1.4.85：AUTO 也显示档位（"AUTO 3 档"）
            parts += "风速 " + if (st.fanAuto) {
                if (st.fanLevel > 0) "AUTO ${st.fanLevel} 档" else "AUTO"
            } else "${st.fanLevel} 档"
        }
        if (prev.recircInside != st.recircInside) {
            parts += if (st.recircInside) "内循环" else "外循环"
        }
        // ★ AC 变化不弹（用户要求：长按主驾温度切 AC 时不要弹窗）
        if (prev.seatVent != st.seatVent) {
            parts += if (st.seatVent > 0) "座椅通风 ${st.seatVent} 档" else "座椅通风 关"
        }
        if (prev.seatHeat != st.seatHeat) {
            parts += if (st.seatHeat > 0) "座椅加热 ${st.seatHeat} 档" else "座椅加热 关"
        }
        if (prev.seatVentPassenger != st.seatVentPassenger) {
            parts += if (st.seatVentPassenger > 0) "副驾通风 ${st.seatVentPassenger} 档" else "副驾通风 关"
        }
        if (prev.seatHeatPassenger != st.seatHeatPassenger) {
            parts += if (st.seatHeatPassenger > 0) "副驾加热 ${st.seatHeatPassenger} 档" else "副驾加热 关"
        }
        if (prev.defrostFront != st.defrostFront) {
            parts += if (st.defrostFront) "前挡加热 开" else "前挡加热 关"
        }
        if (prev.defrostRear != st.defrostRear) {
            parts += if (st.defrostRear) "后挡加热 开" else "后挡加热 关"
        }
        if (prev.steeringHeat != st.steeringHeat) {
            parts += if (st.steeringHeat > 0) "方向盘加热 ${st.steeringHeat} 档" else "方向盘加热 关"
        }
        if (parts.isEmpty()) return   // 没有可见变化（如风向变化等不弹的项）

        val delay = Prefs.popupAutoDismissMs(this)
        val pop = statusPop ?: StatusPopWindow(this).also { statusPop = it }
        val h = Prefs.dockHeightPx(this)
        val y = computeY(h)
        pop.popupX = 0
        // ★ 用户要求：状态弹窗再往上移一个 Dock 的高度（150px），避免贴太低被 Dock 挡住
        pop.popupY = if (Prefs.dockAtBottom(this))
            (y - dp(56) - Prefs.dockHeightPx(this) - Prefs.dockMarginPx(this)).coerceAtLeast(0)
        else (y + h + dp(8))
        pop.show(parts.joinToString("　"), delay)
        // Dock 若处于隐藏态（开了空闲自动隐藏），弹窗期间顺带临时显示一下
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(delay)
            stateMachine?.onUserIdle()
        }
    }

    /**
     * 点击 Dock 上的「主驾温度 / 副驾温度 / 风速」时弹出空调控制面板。
     * 双温共用同一面板（满足「双温都需要这个逻辑」）。
     * 面板开启期间临时显示 Dock（若开了空闲自动隐藏），收起后恢复。
     */
    /**
     * 点击 Dock 上的「主驾温度 / 副驾温度」时触发：开关**系统原生的空调窗口**
     * （com.geely.hvac，即车机原本那个），而不是我们自绘的面板。
     *
     * 逻辑与之前一致：没开就打开主面板；已经开着时再点一次就关闭（回桌面收起）。
     * 长按温度仍走各自功能：主驾=AC 开关，副驾=双温同步。
     */
    private fun toggleSystemHvacWindow() {
        onUserInteraction()
        if (isSystemHvacOpen()) {
            IntentLauncher.goHome(this)
            Logx.d("温度点击：系统空调窗口已开 → 关闭")
        } else {
            HvacApp.openPanel(this, HvacApp.PAGE_MAIN)
            Logx.d("温度点击：打开系统空调窗口")
        }
    }

    /**
     * 系统空调窗口当前是否打开。
     * 优先用 HVAC App 自己的 getVisibility 接口（ContentProvider），
     * 查不到（App 进程未起 / provider 不可用）就退回前台包名判断。
     */
    private fun isSystemHvacOpen(): Boolean {
        HvacApp.isPanelVisible(this)?.let { return it }
        return ForegroundTracker.currentPackage == HvacApp.PACKAGE
    }

    private fun showHvacPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = hvacPop ?: HvacControlPopup(this).also { p ->
            p.onDismissed = {
                // 控制面板收起后，若 Dock 仍应隐藏则交还给状态机
                if (stateMachine?.state == DockState.HIDDEN) {
                    stateMachine?.requestTempShow(0)
                }
                stateMachine?.onUserIdle()
            }
            hvacPop = p
        }
        pop.show(anchor)
        // Dock 若处于隐藏态（开了空闲自动隐藏），面板期间临时显示一下
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(8000L)
        }
    }

    /**
     * 点击 Dock 上的「风向」按钮弹出风向独立开关小弹窗（吹脸 / 吹脚 / 吹前挡 三个独立开关）。
     * 5 秒自动消失，点击面板外立即关闭（统一规范，无 × 按钮）。
     *
     * @param anchor 触发弹窗的「风向」图标，弹窗位置贴着它
     */
    private fun showAirDirectionPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = airDirPop ?: AirDirectionPopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            airDirPop = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("风向弹窗：刚被点外面关掉，忽略本次点击")
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    /**
     * 打开「快捷应用」独立按钮（v1.4.20 起与车机设置并列）弹的 2×3 快捷应用面板。
     * 位置贴着触发图标、点外面关闭、无 × 按钮（统一规范见 [DockPopupChrome]）。
     */
    private fun showAppDrawer(anchor: View? = null) {
        pauseAutoHide()
        val pop = appDrawerPop ?: AppDrawerPopup(this) { pauseAutoHide() }
            .also {
                it.onDismissed = { stateMachine?.onUserIdle() }
                appDrawerPop = it
            }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.dismiss() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("快捷应用面板：刚被点外面关掉，忽略本次点击")
    }

    /**
     * ★ v1.4.97 #10：长按 D05（快捷应用）→ 打开设置 App 的「选择快捷应用」界面。
     * 走 Activity（悬浮窗里没法直接开设置页），带 FLAG_ACTIVITY_NEW_TASK。
     */
    private fun openAppDrawerSettings() {
        Logx.d("D05 长按 → 打开设置并直接弹出「选择快捷应用」")
        runCatching {
            val it = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_PICK_APP_DRAWER, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(it)
        }.onFailure { Logx.e("打开快捷应用设置页失败", it) }
    }

    /**
     * ★ v1.4.34：温度组合按钮中间值单击 → 温度弹窗。
     * 主驾 / 副驾是两个不同布局（左栏温度档位 + 右栏功能），zone 变化时重建实例。
     * 不再进系统空调页面。位置贴着触发图标、点外面关闭、无 × 按钮（统一规范）。
     */
    private fun showAirQualityPopup(anchor: View? = null, isDriver: Boolean = true) {
        pauseAutoHide()
        val zone = if (isDriver) TemperaturePopup.ZONE_DRIVER else TemperaturePopup.ZONE_PASSENGER
        val existing = airQualityPop
        if (existing != null && existing.zone != zone) {
            existing.destroy()
            airQualityPop = null
        }
        val pop = airQualityPop ?: TemperaturePopup(this, zone).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            airQualityPop = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        //   （否则 = 点图标 → 弹窗 outside 关闭 → 同一手势 up 又把它弹回来 → 关不掉）
        if (pop.isShowing) {
            pop.hide()
            Logx.d("温度弹窗：再次点击 → 关闭")
        } else if (!DockPopupChrome.shouldSuppressShow()) {
            pop.show(anchor)
        } else {
            Logx.d("温度弹窗：刚被点外面关掉，忽略本次点击")
        }
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    /**
     * ★ v1.4.34：座椅按钮单击 → 座椅弹窗（主驾通风/加热档位，激活蓝色）。
     * 不再进座椅设置 App。位置贴着触发图标、点外面关闭、无 × 按钮（统一规范）。
     */
    private fun showSeatPopup(anchor: View? = null) {
        pauseAutoHide()
        val pop = seatPopup ?: SeatPopup(this).also {
            it.onDismissed = { stateMachine?.onUserIdle() }
            seatPopup = it
        }
        // ★ toggle：开着就关（快速连点不会「出不来」）
        // ★★ v1.4.67：被「点外面」关掉后的同一手势收尾不再重弹
        if (pop.isShowing) pop.hide() else if (!DockPopupChrome.shouldSuppressShow()) pop.show(anchor)
        else Logx.d("座椅弹窗：刚被点外面关掉，忽略本次点击")
        if (stateMachine?.state == DockState.HIDDEN) {
            stateMachine?.requestTempShow(5000L)
        }
    }

    /** 长按「副驾温度」切换双温同步 → 弹「双温同步」状态弹窗（用户要求单项） */
    private fun toggleDualSync() {
        onUserInteraction()
        val next = !Prefs.syncTemp(this)
        Prefs.setSyncTemp(this, next)
        Logx.d("双温同步 → ${if (next) "开" else "关"}（长按副驾温度切换）")
        // ★ v1.4.52：除了本地开关，也把车机自身的 DUAL 通道写一次（原来从来没告诉车机）
        runCatching { VehicleController.setTempDual(this, next) }
        // ★ v1.4.47：只显示「双温同步」这一项；Dock 上方状态小字已全部移除
        if (Prefs.popupEnabled(this) && Prefs.safeLevel(this) < 1) {
            val pop = statusPop ?: StatusPopWindow(this).also { statusPop = it }
            pop.popupX = 0
            pop.popupY = if (Prefs.dockAtBottom(this))
                (computeY(Prefs.dockHeightPx(this)) - dp(56) - Prefs.dockHeightPx(this) - Prefs.dockMarginPx(this)).coerceAtLeast(0)
            else (Prefs.dockHeightPx(this) + dp(8))
            pop.show("双温同步 ${if (next) "开" else "关"}", 2000L)
        }
    }

    /** 长按「主驾温度」切 AC → 弹「AC」状态弹窗（用户要求） */
    private fun toggleAcFromDock() {
        onUserInteraction()
        VehicleController.toggleAc(this)
        // toggleAc 乐观更新：state.acOn 已是目标值
        val ac = VehicleController.state.acOn
        Logx.d("AC → ${if (ac) "开" else "关"}（长按主驾温度切换）")
        if (Prefs.popupEnabled(this) && Prefs.safeLevel(this) < 1) {
            val pop = statusPop ?: StatusPopWindow(this).also { statusPop = it }
            pop.popupX = 0
            pop.popupY = if (Prefs.dockAtBottom(this))
                (computeY(Prefs.dockHeightPx(this)) - dp(56) - Prefs.dockHeightPx(this) - Prefs.dockMarginPx(this)).coerceAtLeast(0)
            else (Prefs.dockHeightPx(this) + dp(8))
            pop.show("AC ${if (ac) "开" else "关"}", 2000L)
        }
    }

    /** 双温同步状态小字文案 */
    private fun syncStatusText(on: Boolean): String = "双温同步：${if (on) "开" else "关"}"

    // =====================================================================
    // 背景（日夜模式 + 渐变）
    // =====================================================================

    /**
     * 把背景配置应用到 [DockLayout]：
     *  - 启用了渐变 → 设置三色渐变 + 方向，同时把纯色字段设成渐变平均色
     *    （供反色采样 / 兜底判断使用，视觉上以渐变为准）
     *  - 未启用渐变 → 纯色，跟随系统日夜模式切换颜色/不透明度
     *    （对齐吉利 [BaseDockLayout.setDockBg] 的日夜切换）
     */
    /**
     * 底色本身完全透明，外观完全由渐变呈现。
     * 已取消独立的「不透明度 / 透明度」调节（窗口整体不透明度、底色不透明度）：
     * dock 窗口不再有任何纯色半透明填充，只靠 [DockLayout] 的渐变着色器出图。
     * 渐变关闭时窗口即全透明（与「仅渐变」设定一致）。
     */
    private fun applyBackground(layout: DockLayout) {
        // ★ v3.13.16：**背景形态四选一互斥**（纯色 / 透明 / 渐变 / 毛玻璃）
        when (Prefs.bgMode(this)) {
            BgMode.SOLID -> {
                BehindBlur.clear(layout)
                layout.bgColors = null
                layout.bgColor = if (onSystemHome()) 0 else Prefs.solidBgColor(this)
                layout.blurBitmap = null
            }
            BgMode.TRANSPARENT -> {
                BehindBlur.clear(layout)
                layout.bgColors = null
                layout.bgColor = 0
                layout.blurBitmap = null
            }
            BgMode.GRADIENT -> {
                BehindBlur.clear(layout)
                layout.bgColors = Prefs.currentGradientColors(this)
                layout.gradientAngle = 0
                layout.bgColor = 0
                layout.blurBitmap = null
            }
            BgMode.BLUR -> {
                layout.bgColors = null
                layout.bgColor = 0
                layout.blurBitmap = null
                val night = Prefs.bgFollowNight(this) && Prefs.isNightMode(this)
                val tint = if (night) 0x33000000 else 0x33FFFFFF
                BehindBlur.apply(
                    layout,
                    Prefs.blurRadius(this) * 4,
                    Prefs.cornerRadiusPx(this).toFloat(),
                    tint
                )
            }
        }
    }

    /** 取一组颜色的平均值（RGB 平均 + alpha 平均），把渐变近似成纯色用于亮度判断 */
    private fun averageColor(colors: IntArray): Int {
        var a = 0; var r = 0; var g = 0; var b = 0
        for (c in colors) {
            a += Color.alpha(c); r += Color.red(c)
            g += Color.green(c); b += Color.blue(c)
        }
        val n = colors.size.coerceAtLeast(1)
        return ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** 当前实际生效的底色（渐变时取平均色），供反色采样使用 */
    private fun effectiveBgColor(ctx: Context): Int {
        // ★ v3.13.1：纯色模式直接用纯色底做反色采样
        if (Prefs.bgMode(ctx) == BgMode.SOLID) {
            return if (onSystemHome()) 0 else Prefs.solidBgColor(ctx)
        }
        if (Prefs.bgSolidMode(ctx)) return Prefs.solidBgColor(ctx)
        val colors = Prefs.currentGradientColors(ctx)
        return averageColor(colors)
    }

    /**
     * 计算贴边 y 坐标。
     * 车机屏幕固定 1920×1080，屏幕高直接用配置值（默认 1080），不查询系统显示参数。
     *
     * ⚠ 这里**不要打日志**：反色采样每秒都会调一次 computeY()，日志会把有用信息全冲掉。
     * 窗口几何信息改到 [logWindowGeometry] 里，只在建窗时打一次。
     */
    private fun computeY(height: Int): Int {
        val screenH = Prefs.screenHeightPx(this)
        val margin = Prefs.dockMarginPx(this)
        return if (Prefs.dockAtBottom(this)) {
            (screenH - height - margin).coerceAtLeast(0)
        } else {
            margin
        }
    }

    /** 建窗时打一次窗口几何信息（调试用，不参与高频轮询） */
    private fun logWindowGeometry(height: Int, y: Int) {
        val dw = Prefs.dockWidthPx(this)
        Logx.d(
            "Dock：宽${if (dw > 0) dw else "MATCH_PARENT"} 高$height　" +
                    "屏幕高${Prefs.screenHeightPx(this)} 距边${Prefs.dockMarginPx(this)} y=$y"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    // 自动反色：根据 dock 实际盖住的屏幕条带亮度，自动选黑 / 白字
    // =====================================================================

    /** 读取系统墙纸主色（取不到则按深色处理 → 白字）。仅作截屏不可用时的兜底 */
    private fun wallpaperPrimary(ctx: Context): Int {
        return runCatching {
            val wm = WallpaperManager.getInstance(ctx)
            val wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            wc?.primaryColor?.toArgb() ?: Color.BLACK
        }.getOrDefault(Color.BLACK)
    }

    /** 把前景色 fg 以透明度 a 叠加到背景 bg 上，得到 dock 实际生效的背景色 */
    private fun blend(fg: Int, bg: Int, a: Float): Int {
        val fr = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fb = fg and 0xFF
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF
        val r = (fr * a + br * (1 - a)).toInt()
        val g = (fgG * a + bgG * (1 - a)).toInt()
        val b = (fb * a + bb * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 相对亮度（0=黑 1=白）。>0.5 视为亮背景 → 用黑字，否则白字 */
    private fun luminance(c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** 根据 dock 实际背景色设置前景调色板（黑/白 + 各级半透明） */
    private fun applyPaletteColors(darkText: Boolean) {
        DockIconView.fgColor = if (darkText) Color.WHITE else Color.BLACK
        DockIconView.fgColorDim = if (darkText) 0xB3FFFFFF.toInt() else 0xB3000000.toInt()
        DockIconView.offColor = if (darkText) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        DockIconView.dimColor = if (darkText) 0x45FFFFFF else 0x45000000
    }

    /** 统一换色入口：只在实际背景跨越阈值时才动视图，避免临界背景来回闪 */
    private fun setPalette(darkText: Boolean) {
        if (darkText == this.darkText) return
        this.darkText = darkText
        applyPaletteColors(darkText)
        root?.applyPalette(light = !darkText)
        // 真实车控数据可能带来异常值，渲染层必须兜底，绝不能让前台服务闪退
        runCatching { bindVehicle(VehicleController.state) }
        runCatching { bindMedia(MediaBridge.info) }
        Logx.d("反色：${if (darkText) "白字（暗背景）" else "黑字（亮背景）"}")
    }

    // ---------------- 真实背景采样（截屏） ----------------

    private fun startContrast() {
        if (contrastThread != null) return
        contrastThread = HandlerThread("dock-contrast").apply { start() }
        contrastHandler = Handler(contrastThread!!.looper)
        contrastHandler?.postDelayed(contrastTicker, 400)
    }

    private fun stopContrast() {
        contrastHandler?.removeCallbacksAndMessages(null)
        contrastThread?.quitSafely()
        contrastThread = null
        contrastHandler = null
    }

    private val contrastTicker = object : Runnable {
        override fun run() {
            // 采样失败（权限/异常）只跳过本轮，绝不能把前台服务一起带崩
            runCatching { sampleContrast() }
                .onFailure { Logx.e("反色采样异常", it) }
            contrastHandler?.postDelayed(this, CONTRAST_INTERVAL_MS)
        }
    }

    /**
     * 计算 dock 条带亮度，回到主线程统一换色。
     *
     * ★★ v1.4.93：**反射截屏功能已整体删除**（用户要求）。
     *   原实现会反射调用 `SurfaceControl.screenshot` 截取 dock 条带的真实像素，
     *   是 2026-10-20 那次 SIGABRT 闪退的真凶；而且普通 debug 签名根本拿不到
     *   截屏特权，实际一直走的是下面的墙纸底色分支。
     *   现在：**只按墙纸底色反色**，不再碰任何隐藏 API。
     */
    /**
     * 反色采样（每秒一次）。
     *
     * ★ v3.4.2：**日夜跟随开启时直接按系统深浅色判定**，不再用「渐变平均色 + 墙纸」猜。
     *   原因：白天渐变的平均 alpha 只有 0x66（40% 白），盖在暗墙纸上亮度会掉到 0.45~0.55
     *   的迟滞带里甚至更低 → 被误判成暗背景 → 该用黑字/深色图标的时候用了白字
     *   （用户实锤：「白天座椅通风图标还是白色的」）。
     *   底色本身就是按 `isNightMode()` 选的，图标颜色必须跟同一个信号，否则必然对不上。
     */
    private fun sampleContrast() {
        if (Prefs.bgFollowNight(this)) {
            val wantDark = Prefs.isNightMode(this)
            main.post { setPalette(wantDark) }
            return
        }
        val bg = effectiveBgColor(this)
        val eff = blend(bg, wallpaperPrimary(this), Color.alpha(bg) / 255f)
        main.post { onSampledLuminance(luminance(eff), viaScreen = false) }
    }

    private fun onSampledLuminance(lum: Float, viaScreen: Boolean) {
        if (!viaScreen && !contrastHintShown) {
            contrastHintShown = true
            Logx.w("反色：实时截屏不可用（需 platform 签名重签 APK，与车控白名单同一套办法），已退回按墙纸底色反色，仍每秒轮询。车载 launcher 多以墙纸作 dock 背景，体验一致。")
        }
        // 迟滞：亮度 0.45~0.55 之间保持现状，避免临界背景来回闪
        val wantDark = when {
            lum <= 0.45f -> true
            lum >= 0.55f -> false
            else -> return
        }
        setPalette(wantDark)
    }

    /** 兜底：按系统墙纸亮度给初始调色板（首帧 / 截屏不可用时用） */
    private fun applyContrast(ctx: Context) {
        // ★ v3.4.2：日夜跟随开启时直接用系统深浅色，跟底色选择保持同一个信号
        val light = if (Prefs.bgFollowNight(ctx)) {
            !Prefs.isNightMode(ctx)
        } else {
            val wall = wallpaperPrimary(ctx)
            val bg = effectiveBgColor(ctx)
            val a = Color.alpha(bg) / 255f
            luminance(blend(bg, wall, a)) > 0.5f
        }
        darkText = !light
        applyPaletteColors(darkText)
        Logx.d("反色：${if (Prefs.bgFollowNight(ctx)) "跟随系统" else "按墙纸"} → ${if (light) "黑字" else "白字"}（等待真实背景采样校正）")
    }

    // =====================================================================
    // 数据绑定
    // =====================================================================

    private fun bindVehicle(st: VehicleController.State) {
        // 空调关闭时仍显示最后一次有效设定温度，只将整组温度字样灰显。
        // State 尚未从车机读取（或安全模式跳过车控初始化）时直接从本地历史值恢复。
        val driverTemp = st.driverTempC ?: Prefs.lastDriverTempC(this)
        val passengerTemp = st.passengerTempC ?: Prefs.lastPassengerTempC(this)
        tempDriverViews.forEach { it.renderTemp(driverTemp, muted = !st.acOn) }
        tempPassengerViews.forEach { it.renderTemp(passengerTemp, muted = !st.acOn) }
        fanViews.forEach { it.renderFan(st.fanLevel, st.fanAuto) }
        // 内外循环：内/外两种图标（车内环形箭头 / 外部箭头进车厢）
        // ★ v1.5.13：改用系统 Dock 的矢量（ic_ac_cycle_inner / _outer），不再自绘
        recircViews.forEach {
            it.setIcon(
                if (st.recircInside) R.drawable.ic_ac_cycle_inner else R.drawable.ic_ac_cycle_outer
            )
        }
        // ★ v3.1：主驾座椅通风 —— 改用系统 Dock 的 ic_seat_feng_left_01/02/03_normal + _off_normal
        // ★ v3.3：白天模式（亮背景）换深色版，否则白座椅掉在白底上看不见
        ventViews.forEach {
            val dark = !darkText
            it.setIcon(
                when (st.seatVent) {
                    1 -> if (dark) R.drawable.ic_seat_feng_01_dark else R.drawable.ic_seat_feng_01
                    2 -> if (dark) R.drawable.ic_seat_feng_02_dark else R.drawable.ic_seat_feng_02
                    3 -> if (dark) R.drawable.ic_seat_feng_03_dark else R.drawable.ic_seat_feng_03
                    else -> if (dark) R.drawable.ic_seat_feng_off_dark else R.drawable.ic_seat_feng_off
                }
            )
            it.setActive(st.seatVent > 0)
        }
        // ★ v3.1：主驾座椅加热 —— 改成**按档位换图**（热浪数量 1/2/3 跟着档位走），
        //   之前是固定一张 3 档图，所以热浪永远只显示 3 条。
        // ★ v3.3：白天模式换深色版
        seatHeatViews.forEach {
            val dark = !darkText
            it.setIcon(
                when (st.seatHeat) {
                    1 -> if (dark) R.drawable.ic_seat_hot_01_dark else R.drawable.ic_seat_hot_01
                    2 -> if (dark) R.drawable.ic_seat_hot_02_dark else R.drawable.ic_seat_hot_02
                    3 -> if (dark) R.drawable.ic_seat_hot_03_dark else R.drawable.ic_seat_hot_03
                    else -> if (dark) R.drawable.ic_seat_hot_off_dark else R.drawable.ic_seat_hot_off
                }
            )
            it.setActive(st.seatHeat > 0)
        }
        // 风向：7 种组合（吹脸/吹脚/吹前挡 及其组合）各自点亮对应箭头
        // ★ v1.5.13：改用系统 Dock 的 ic_blowing_* 静态矢量（按位掩码选图），不再自绘
        airViews.forEach { it.setIcon(airResOf(st.airDirection)) }
        // ★ v1.5.11 #13：前挡加热 —— 开启时点亮强调色
        defrostFrontViews.forEach { it.setActive(st.defrostFront) }
        // ★ v3.1：后挡除霜 —— 开启时点亮强调色
        defrostRearViews.forEach { it.setActive(st.defrostRear) }
        // ★ v3.1：方向盘加热 —— 按档位换图（0=关 1/2/3 档图标不同）
        // ★ v3.3：白天模式换深色版
        steeringHeatViews.forEach {
            val dark = !darkText
            it.setIcon(
                when (st.steeringHeat) {
                    1 -> if (dark) R.drawable.ic_sw_heat_01_dark else R.drawable.ic_sw_heat_01
                    2 -> if (dark) R.drawable.ic_sw_heat_02_dark else R.drawable.ic_sw_heat_02
                    3 -> if (dark) R.drawable.ic_sw_heat_03_dark else R.drawable.ic_sw_heat_03
                    else -> if (dark) R.drawable.ic_sw_heat_off_dark else R.drawable.ic_sw_heat_off
                }
            )
            it.setActive(st.steeringHeat > 0)
        }
        onAcStateChanged(st)
        // ★ v1.4.44：长按主驾温度（切 AC）时不再显示 Dock 上方的状态小字（用户要求取消）；
        //   状态小字只保留「长按副驾温度切双温同步」这一处触发
    }

    /**
     * ★ v1.5.13：风向位掩码 → 系统 Dock 的 `ic_blowing_*` 矢量。
     * 掩码：1 = 吹脸(FACE)  2 = 吹脚(FOOT)  4 = 吹前挡(DEFROST)（与旧 AirFlowDrawable 一致）。
     */
    private fun airResOf(mode: Int): Int {
        val face = mode and 0x1 != 0
        val foot = mode and 0x2 != 0
        val win = mode and 0x4 != 0
        return when {
            face && foot && win -> R.drawable.ic_blowing_all
            face && foot -> R.drawable.ic_blowing_facefoot
            face && win -> R.drawable.ic_blowing_face_and_window
            foot && win -> R.drawable.ic_blowing_foot_and_window
            face -> R.drawable.ic_blowing_face
            foot -> R.drawable.ic_blowing_foot
            win -> R.drawable.ic_blowing_window
            else -> R.drawable.ic_blowing_off
        }
    }

    // （v1.4.46：Dock 上方状态小字已全部移除——refreshLongPressLabel 连同触发点一并删除）

    private fun bindMedia(mi: MediaBridge.MediaInfo) {
        if (musicViews.isEmpty()) return
        musicViews.forEach { m ->
            if (!mi.hasSession) m.renderEmpty()
            else m.render(mi.title, mi.artist, mi.art, mi.isPlaying)
        }
    }

    // =====================================================================
    // 广播
    // =====================================================================

    private fun registerReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    ACTION_RELOAD, ACTION_ENTRIES_CHANGED -> reload()
                    ACTION_APPS_CHANGED -> main.post { applyKeepShownForForeground() }
                    Intent.ACTION_SCREEN_ON -> main.post {
                        runCatching { VehicleController.refresh(this@DockOverlayService) }
                        runCatching { MediaBridge.refresh(this@DockOverlayService) }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(ACTION_RELOAD)
            addAction(ACTION_ENTRIES_CHANGED)
            addAction(ACTION_APPS_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Android 13+ 必须显式声明接收器导出性；本广播全部是应用内目标，用 NOT_EXPORTED
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
        }.onFailure { Logx.e("注册广播接收器失败", it) }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dock 悬浮栏", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val pkgLabel = runCatching {
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault("Dock")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(pkgLabel)
            .setContentText("Dock 悬浮栏运行中")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    @Suppress("unused")
    private fun channelCountRef(): Int = VehicleChannels.ALL.size
}
