package com.car.dockbar.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.App
import com.car.dockbar.R
import com.car.dockbar.config.VehicleChannel
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.media.LyricMonitor
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.model.AppDrawerApp
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.CrashHandler
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.LogUploader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.car.dockbar.util.Logx
import com.car.dockbar.util.NativeCrashHandler
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.PrivilegeProbe
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController
import android.provider.Settings
import android.content.res.Configuration

class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * 设置界面整体缩放倍数：1.2 = 所有尺寸/文字放大 20%（车机上更好点、更好读）。
         *
         * 做法：只改 [Configuration.densityDpi]（×1.2）。
         * dp→px 走 density（= densityDpi/160），sp→px 走 scaledDensity（= density × fontScale），
         * 二者都随 densityDpi 同比变化，所以 dp 与 sp 会**同步**放大 1.2 倍；
         * 若再单独改 fontScale 就会叠乘成 1.44 倍，故这里刻意不动 fontScale。
         */
        private const val UI_SCALE = 1.2f

        /**
         * 子界面模式：只显示某一个分区（由主设置页的入口按钮带 extra 进来）。
         *
         * ★ v1.5.00：设置页改成「左侧入口 + 右侧内容」两栏，这里的取值就是左栏的 6 个入口。
         */
        const val EXTRA_SECTION = "section"
        const val SECTION_OVERLAY = "overlay"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_ICONS = "icons"
        const val SECTION_LYRIC = "lyric"
        const val SECTION_APPS = "apps"
        const val SECTION_LOGS = "logs"

        /** ★ v1.4.97 #10：D05 长按进来时直接弹出「选择快捷应用」（跳到增加应用那一步） */
        const val EXTRA_PICK_APP_DRAWER = "pick_app_drawer"

        /**
         * ★ v1.5.5：应用图标的**内存缓存**（跨 Activity 实例复用，转屏也不丢）。
         * value 为 null = 这台车上取不到这个包的图标，走兜底图标（也缓存下来，不再重试）。
         */
        private val iconCache = HashMap<String, Drawable?>()

        /** 图标加载专用单线程池（避免为 59 个包名开 59 条线程） */
        private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        /** 生成放大后的 Context（仅作用于本 Activity，不影响悬浮窗等其他界面） */
        fun scaledContext(base: Context): Context {
            val res = base.resources
            val config = Configuration(res.configuration)
            config.densityDpi = (res.displayMetrics.densityDpi * UI_SCALE).toInt()
            return base.createConfigurationContext(config)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(scaledContext(newBase))
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayPerm: TextView
    private lateinit var tvNotifPerm: TextView
    private lateinit var tvNotifHint: TextView
    private lateinit var tvAccPerm: TextView
    private lateinit var tvAccHint: TextView
    private lateinit var tvBackend: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var llChannels: LinearLayout
    /** 「打开时隐藏 Dock 的应用名单」容器 */
    private lateinit var llDockApps: LinearLayout
    /** 「打开时隐藏 Dock 的应用」整张卡片（主设置页隐藏，子界面才显示） */
    private var cardDockApps: LinearLayout? = null
    /** 运行日志与日志上传共用的子页面容器 */
    private var cardLogs: LinearLayout? = null
    /** 当前子界面模式（★ v1.5.00 起恒为左栏 6 个入口之一，不再是 null） */
    private var section: String? = null

    // ===== ★ v1.5.00 左栏入口 / 右栏内容 =====
    /** 「悬浮 Dock 栏」卡片 */
    private var cardOverlay: LinearLayout? = null
    /** 「Dock 图标设置」卡片（动态创建，需要记下来做页面切换） */
    private var cardDockIcon: LinearLayout? = null
    /** 左栏入口行：page → 图标行，用于高亮当前页 */
    private val navButtons = LinkedHashMap<String, NavRow>()
    /** 当前显示的页（左栏入口 key） */
    private var currentPage: String = SECTION_OVERLAY
    // ★ v1.4.97 #9：添加应用改为从已安装应用列表选择，包名输入框已删除
    private lateinit var tvAccStatus: TextView
    private lateinit var tvCorner: TextView
    private lateinit var tvIconScale: TextView
    private lateinit var tvTextScale: TextView

    /** 渐变配置区的实时预览条 */
    private var gradientPreview: GradientPreviewView? = null

    /**
     * ★ v3.13.16：随背景形态显隐的控件行（两个「底色透明度」滑杆）。
     * 只在「纯色 / 渐变」形态下显示；透明 / 毛玻璃时隐藏。
     */
    private var bgColorControlRows: List<View> = emptyList()
    private var blurRadiusRow: View? = null
    private var tvAppDrawerSummary: TextView? = null
    private var btnAppDrawerSelection: Button? = null
    /** 歌词悬浮窗配置区控件 */
    private var tvLyricStatus: TextView? = null
    private var btnLyricToggle: Button? = null
    private var lyricSourceButtons: List<Pair<Int, Button>> = emptyList()
    private var lyricMapButtons: List<Pair<Int, Button>> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val logListener: (String) -> Unit = { line -> main.post { appendLog(line) } }
    private var logBuffer = StringBuilder()
    /** 日志行数 / 滚动合并标记（appendLog 用） */
    private var logLineCount = 0
    private var scrollPending = false
    private val MAX_LOG_LINES = 200

    private class ChannelRow(
        val channel: VehicleChannel,
        val etFunc: EditText,
        val etZone: EditText,
        val tvResult: TextView,
        val etWrite: EditText
    )

    private data class AppDrawerCandidate(
        val app: AppDrawerApp,
        val isSystem: Boolean
    )

    private val rows = mutableListOf<ChannelRow>()

    /** 诊断结果输出区（扫描 / 提权 / 竞品取证都往这里写）；buildChannelRows 里也能用 */
    private var tvStateRef: TextView? = null

    /** 上一次 onResume 时「通知使用权」的状态，用于检测"刚授权" */
    private var notifWasOk = false

    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ★ 设置页底部让出 Dock 高度：Dock 是 TYPE_APPLICATION_OVERLAY 悬浮窗，
        //   会盖在页面最后几项上导致点不到。抬高量跟随 Dock 配置
        //   （dockHeightPx + dockMarginPx，默认 150px）+ 一点余量；
        //   Dock 在顶部时不影响底部，无需处理。
        if (Prefs.dockAtBottom(this)) {
            val root = findViewById<View>(R.id.llRoot)
            val clear = Prefs.dockHeightPx(this) + Prefs.dockMarginPx(this) + dp(12)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom + clear)
        }

        bindViews()
        loadPrefs()
        buildChannelRows()
        wireActions()
        addDockBehaviorSection()
        addLogUploadSection()

        // ★ v1.5.10：后台预热「系统应用」包名集合，别让第一次点「常驻应用」时去等一次大 Binder
        prewarmSystemPkgs()

        // ★ v1.5.00：左栏入口 + 右栏内容。先建左栏，再按 extra 选中对应页
        buildNav()
        val target = intent?.getStringExtra(EXTRA_SECTION)
        showPage(if (target.isNullOrBlank()) SECTION_OVERLAY else target)

        Logx.addListener(logListener)
        Logx.snapshot().forEach { appendLog(it) }
        appendLog("—— 打开配置页 ——")

        // ★ v1.4.97 #10：从 D05 长按进来 → 直接弹出「选择快捷应用」，省一次点击
        if (intent?.getBooleanExtra(EXTRA_PICK_APP_DRAWER, false) == true && target.isNullOrBlank()) {
            main.postDelayed({ chooseAppDrawerApps() }, 300)
        }

        // 上次闪退的堆栈（车机没 adb 也能定位）
        val crash = CrashHandler.read(this)
        if (crash != null) {
            appendLog("⚠⚠ 上次闪退堆栈：\n$crash")
            CrashHandler.clear(this)
            Logx.e("检测到上次闪退，已把堆栈显示在下方日志区")
        }

        // 车控初始化 / 媒体刷新都含 binder 读取，放后台线程，避免主线程卡顿
        Thread {
            runCatching { VehicleController.init(this) }
                .onFailure { Logx.e("车控初始化异常", it) }
            runCatching { MediaBridge.refresh(this) }
        }.start()

        // 关键：默认就是启用状态，所以打开应用就要把服务拉起来，
        // 否则用户看到的只是"开关是开的"，但 Dock 其实从未创建
        ensureServiceStarted()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        // 从「无障碍设置」返回后刷新前台应用检测状态（以及名单里的当前前台应用）
        runCatching { refreshAccStatus() }
        ensureServiceStarted()
        // 歌词悬浮窗开着但服务被系统杀了 → 拉起来（权限已授过不会重复弹窗）
        if (Prefs.lyricEnabled(this) && !LyricOverlayService.alive) {
            Logx.d("歌词悬浮窗开关是开的但服务没跑，重新拉起")
            ensureLyricStarted()
        }
        // 从「授权通知使用权」页返回：权限刚由 未授权→已授权 时立刻重绑媒体会话，
        // 否则要等服务 2.5s 轮询才会去 start()（装了新 APK 后该授权常被系统重置，
        // 这是"音乐卡片读不到信息"最常见的原因）
        val notifOk = MediaBridge.hasPermission(this)
        if (notifOk && !notifWasOk) {
            Logx.d("检测到通知使用权刚被授予 → 立即重绑媒体会话")
            runCatching { MediaBridge.rebind(this) }
        }
        notifWasOk = notifOk
        updateStatus()
        updateLyricStatus()
        // 系统深浅色可能已切换 → 渐变预览要跟着换成另一套色标
        refreshGradientPreview()
        main.postDelayed({ updateStatus(); ensureServiceStarted() }, 1500)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshGradientPreview()
    }

    /**
     * 保证 Dock 服务处于应该处于的状态。
     * 这是"安装完没反应"的第一顺位修复：开关默认开，但服务默认不启动。
     */
    private fun ensureServiceStarted() {
        if (!Prefs.overlayEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) {
            Logx.e("悬浮窗权限未授予 → Dock 不会显示。请点「授权悬浮窗权限」，打开后回到本页会自动出现")
            toastOnce("请先授予悬浮窗权限，回来后 Dock 会自动出现")
            return
        }

        if (DockOverlayService.alive) {
            // 服务在跑就不再无条件重挂：
            //   attached=false 也可能是「空闲自动隐藏」把 Dock 正常淡出了，
            //   旧实现在这里一律 reload，导致一进主界面 Dock 就被强行拉回来，
            //   与「空闲自动隐藏」功能互相打架。
            if (!DockOverlayService.attached) {
                Logx.d("服务在运行但悬浮窗未挂载，重试挂载…")
                reloadService()
            }
            return
        }
        startService()
    }

    private var lastToastMs = 0L
    private fun toastOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastMs < 4000) return
        lastToastMs = now
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        Logx.removeListener(logListener)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =====================================================================

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvOverlayPerm = findViewById(R.id.tvOverlayPerm)
        tvNotifPerm = findViewById(R.id.tvNotifPerm)
        tvNotifHint = findViewById(R.id.tvNotifHint)
        tvAccPerm = findViewById(R.id.tvAccPerm)
        tvAccHint = findViewById(R.id.tvAccHint)
        tvBackend = findViewById(R.id.tvBackend)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        llChannels = findViewById(R.id.llChannels)
        llDockApps = findViewById(R.id.llDockApps)
        cardDockApps = findViewById(R.id.cardDockApps)
        cardLogs = findViewById(R.id.cardLogs)
        cardOverlay = findViewById(R.id.cardOverlay)
        tvAccStatus = findViewById(R.id.tvAccStatus)
        tvCorner = findViewById(R.id.tvCorner)
        tvIconScale = findViewById(R.id.tvIconScale)
        tvTextScale = findViewById(R.id.tvTextScale)
    }

    private fun loadPrefs() {
        findViewById<SwitchCompat>(R.id.swEnabled).isChecked = Prefs.overlayEnabled(this)
        findViewById<SwitchCompat>(R.id.swSimulate).isChecked = Prefs.simulate(this)
        findViewById<SwitchCompat>(R.id.swSeparators).isChecked = Prefs.showSeparators(this)

        findViewById<EditText>(R.id.etWidth).setText(Prefs.dockWidthPx(this).toString())
        findViewById<EditText>(R.id.etHeight).setText(Prefs.dockHeightPx(this).toString())
        findViewById<EditText>(R.id.etMargin).setText(Prefs.dockMarginPx(this).toString())
        findViewById<EditText>(R.id.etScreenH).setText(Prefs.screenHeightPx(this).toString())

        findViewById<SeekBar>(R.id.sbCorner).progress = Prefs.cornerRadiusPx(this)
        findViewById<SeekBar>(R.id.sbIconScale).progress =
            ((Prefs.iconScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.sbTextScale).progress =
            ((Prefs.textScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)

        findViewById<EditText>(R.id.etSettingsPkg).setText(Prefs.settingsPkg(this))
        findViewById<EditText>(R.id.etSettingsCls).setText(Prefs.settingsCls(this))
        findViewById<EditText>(R.id.etSettingsAction).setText(Prefs.settingsAction(this))
        findViewById<EditText>(R.id.etSeatPkg).setText(Prefs.seatPkg(this))
        findViewById<EditText>(R.id.etSeatCls).setText(Prefs.seatCls(this))
        findViewById<EditText>(R.id.etSeatAction).setText(Prefs.seatAction(this))
        findViewById<EditText>(R.id.etAirPkg).setText(Prefs.airDirPkg(this))
        findViewById<EditText>(R.id.etAirCls).setText(Prefs.airDirCls(this))
        findViewById<EditText>(R.id.etMusicPkg).setText(Prefs.musicPkg(this))
        // ── [S34] 最近任务包名（D01 双击打开）────────
        findViewById<EditText>(R.id.etRecentsPkg).setText(Prefs.recentsPkg(this))


        refreshAppearanceLabels()
        renderDockAppList()
    }

    private fun refreshAppearanceLabels() {
        tvCorner.text = "圆角半径：${findViewById<SeekBar>(R.id.sbCorner).progress} px"
        val isc = 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f
        tvIconScale.text = "图标缩放：${"%.2f".format(isc)}×"
        val tsc = 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f
        tvTextScale.text = "文字缩放：${"%.2f".format(tsc)}×"
    }

    private fun wireActions() {
        findViewById<SeekBar>(R.id.sbCorner).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbIconScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbTextScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })

        findViewById<SwitchCompat>(R.id.swEnabled).setOnCheckedChangeListener { _, checked ->
            Prefs.setOverlayEnabled(this, checked)
            if (checked) startService() else stopServiceNow()
        }

        findViewById<Button>(R.id.btnApplyWindow).setOnClickListener {
            saveWindow()
            ensureServiceStarted()
            reloadService()
            main.postDelayed({ updateStatus() }, 800)
        }

        findViewById<Button>(R.id.btnApplyAppearance).setOnClickListener {
            saveAppearance()
            reloadService()
        }

        findViewById<Button>(R.id.btnApplyEntries).setOnClickListener {
            saveEntries()
            Logx.d("入口配置已保存")
        }


        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            IntentLauncher.requestOverlayPermission(this)
        }
        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener {
            IntentLauncher.requestNotificationAccess(this)
        }
        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnGrantAccessibilityDock).setOnClickListener { openAccessibilitySettings() }

        // 子界面入口：应用名单、歌词与日志分别单独开页，主设置页只留入口按钮
        findViewById<Button>(R.id.btnOpenApps).setOnClickListener {
            // ★ 原地切换子页：之前 startActivity 重启自己，onCreate 全量重跑（UI 重建 +
            //   车控/媒体初始化 + 诊断上传），表现为点击后「顿一下」才进去
            enterSection(SECTION_APPS)
        }
        findViewById<Button>(R.id.btnOpenLyric).setOnClickListener {
            enterSection(SECTION_LYRIC)
        }
        findViewById<Button>(R.id.btnOpenLog).setOnClickListener {
            enterSection(SECTION_LOGS)
        }
        // ★ v1.4.97 #9：不再手输包名，改为从已安装应用列表里选
        findViewById<Button>(R.id.btnAddApp).setOnClickListener { pickInstalledAppForDockList() }

        findViewById<Button>(R.id.btnReadAll).setOnClickListener { readAllChannels() }
        findViewById<Button>(R.id.btnResetChannels).setOnClickListener {
            VehicleChannels.ALL.forEach { ch ->
                VehicleChannels.save(this, ch.key, ch.defFunc, ch.defZone)
            }
            rows.forEach { r ->
                r.etFunc.setText(hex(r.channel.defFunc))
                r.etZone.setText(r.channel.defZone.toString())
            }
            Logx.d("通道已恢复默认值")
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Logx.clear()
            logBuffer = StringBuilder()
            logLineCount = 0
            tvLog.text = ""
        }
    }

    private fun seek(after: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = after()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // =====================================================================
    // Dock 图标管理 + 行为（状态机 / 淡入淡出 / 空调弹窗 / 日夜背景）
    // =====================================================================

    /**
     * 在主界面追加一段配置区：
     *  - 「管理 Dock 图标」按钮 → DockEntriesActivity（增删 / 排序）
     *  - 空调状态弹窗开关 + 自动消失时长
     *  - 淡入淡出时长
     *  - 空闲自动隐藏开关 + 时长
     *  - 日夜模式背景跟随
     */
    private fun addDockBehaviorSection() {
        val pageRoot = findViewById<LinearLayout>(R.id.llRoot)
        val appearance = findViewById<LinearLayout>(R.id.cardAppearance)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        // ★ v1.5.00：这张卡片是动态加的，记下来给左栏「Dock 图标」入口做页面切换
        cardDockIcon = root

        root.addView(TextView(this).apply {
            text = "Dock图标设置"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 16f
            typeface = AppFont.bold(this@MainActivity)
        })

        root.addView(Button(this).apply {
            text = "管理 Dock 图标（增删 / 排序）"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DockEntriesActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        tvAppDrawerSummary = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvAppDrawerSummary)
        btnAppDrawerSelection = Button(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setOnClickListener { chooseAppDrawerApps() }
        }
        root.addView(btnAppDrawerSelection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        refreshAppDrawerSelectionLabel()

        // --- 空调状态弹窗（自动消失） ---
        // ── [S00] 空调状态弹窗（调空调时显示） ───────────────
        addSwitchRow(
            root, "空调状态弹窗（调空调时显示）",
            Prefs.popupEnabled(this)
        ) { Prefs.setPopupEnabled(this, it) }
        // ★ 用户要求：精确到秒（1 秒步进，不再 0.1 秒）
        // ── [S01] 弹窗自动消失 ───────────────────────
        addSeekRow(
            root, "弹窗自动消失",
            (Prefs.popupAutoDismissMs(this) / 1000L).toInt(), 20,
            { "${it} 秒" },
            onChange = { Prefs.setPopupAutoDismissMs(this, it * 1000L) }
        )

        // --- 淡入淡出（时长滑块用于调节显示/隐藏动画快慢）---
        // 0.1 秒粒度（10ms 步进太细不好调）
        // ── [S02] 淡入淡出时长 ───────────────────────
        addSeekRow(
            root, "淡入淡出时长",
            (Prefs.fadeDurationMs(this) / 100L).toInt(), 10,
            { "%.1f 秒".format(it / 10f) },
            onChange = { Prefs.setFadeDurationMs(this, it * 100L) }
        )

        val overlaySide = findViewById<LinearLayout>(R.id.colOverlaySide)
        addSwitchRow(
            overlaySide, "底部上滑临时显示 Dock",
            Prefs.swipeShowDockEnabled(this)
        ) { Prefs.setSwipeShowDockEnabled(this, it) }
        addSeekRow(
            overlaySide, "上滑后 Dock 显示时长",
            (Prefs.swipeShowDockMs(this) / 1000L).toInt(), 30,
            { "%d 秒".format(it) },
            onChange = { Prefs.setSwipeShowDockMs(this, it * 1000L) }
        )

        // --- ★ v1.4.82：AUTO 风道档位（这台车有 5 档 AUTO 风道） ---
        // ── [S05] AUTO 风道档位 ────────────────────
        addSeekRow(
            root, "AUTO 风道档位",
            Prefs.fanAutoLevel(this), 5,
            { "%d 档".format(it) },
            onChange = {
                // ★ v1.4.84：滑条必须**直接写车机**（之前只存本地 Pref → 车机没反应）
                com.car.dockbar.vehicle.VehicleController.setFanAutoLevel(this, it)
            }
        )

        // --- 日夜模式背景（与外观/渐变设置放在一起） ---
        // ── [S06] 底色跟随系统日夜模式 ───────────────────
        addSwitchRow(
            appearance, "底色跟随系统日夜模式",
            Prefs.bgFollowNight(this)
        ) { Prefs.setBgFollowNight(this, it) }

        // （「空调温度」分区已按用户要求从设置页移除：
        //   双温联动改由长按副驾温度切换、HI/LO 阈值用车机默认 15.5~28.5℃）

        // --- 底色（形态 + 参数）────────────────
        appearance.addView(TextView(this).apply {
            text = "底色"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 13f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── ★ v3.13.16：背景形态（四选一互斥）────────────────
        //   纯色 / 透明 / 渐变 / 毛玻璃
        //   ★ 毛玻璃是实验特性（PixelCopy 抓屏）：失败或崩溃会自动退回「渐变」。
        //   ★ 两个「底色透明度」滑杆**只属于纯色 / 渐变**，
        //     切到透明或毛玻璃时自动隐藏（毛玻璃的色调是固定的淡色）。
        addBgModeRow(appearance)
        blurRadiusRow = addSeekRow(
            appearance, "模糊度",
            Prefs.blurRadius(this), 24,
            { if (it == 0) "无" else "$it" },
            onChange = { Prefs.setBlurRadius(this, it) }
        )
        blurRadiusRow?.visibility =
            if (Prefs.bgMode(this) == BgMode.BLUR) View.VISIBLE else View.GONE

        // 实时预览条：按当前配置（含系统深浅色）画一条一模一样的渐变
        val preview = GradientPreviewView(this).apply {
            setPadding(0, dp(4), 0, 0)
        }
        gradientPreview = preview
        appearance.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(8) })

        // ── 白天底色透明度 ───────────────────────
        val dayTransparencyRow = addSeekRow(
            appearance, "白天底色透明度",
            Prefs.dayBgTransparency(this), 100,
            format = { "$it%" },
            onChange = { Prefs.setDayBgTransparency(this, it) },
            onProgress = { p -> Prefs.setDayBgTransparency(this, p); refreshGradientPreview() }
        )
        // ── 夜间底色透明度 ───────────────────────
        val nightTransparencyRow = addSeekRow(
            appearance, "夜间底色透明度",
            Prefs.nightBgTransparency(this), 100,
            format = { "$it%" },
            onChange = { Prefs.setNightBgTransparency(this, it) },
            onProgress = { p -> Prefs.setNightBgTransparency(this, p); refreshGradientPreview() }
        )
        // ★ v3.13.16：按当前形态显隐相关控件（滑杆/预览条只属于纯色与渐变）
        bgColorControlRows = listOf(dayTransparencyRow, nightTransparencyRow)

        refreshGradientPreview()

        // Dock 图标设置卡放在「更多设置」之前，行为选项与图标管理归为一处。
        val moreSettings = pageRoot.findViewById<View>(R.id.cardMoreSettings)
        val insertAt = if (moreSettings != null) pageRoot.indexOfChild(moreSettings) else pageRoot.childCount
        pageRoot.addView(root, insertAt, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    private fun refreshAppDrawerSelectionLabel() {
        val count = AppDrawerApps.load(this).size
        tvAppDrawerSummary?.text = "从系统应用和个人应用中选择，最多 ${AppDrawerApps.MAX_APPS} 个；当前已选 $count 个。"
        btnAppDrawerSelection?.text = "选择快捷应用（$count/${AppDrawerApps.MAX_APPS}）"
    }

    private fun chooseAppDrawerApps() {
        val loading = AlertDialog.Builder(this)
            .setTitle("正在读取可启动应用…")
            .setCancelable(true)
            .create()
        loading.show()
        loading.applyDockSafe()
        Thread {
            val pm = packageManager
            val items = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).asSequence()
                    .mapNotNull { it.activityInfo }
                    .map { info ->
                        val appInfo = info.applicationInfo
                        AppDrawerCandidate(
                            app = AppDrawerApp(
                                label = info.loadLabel(pm).toString(),
                                packageName = info.packageName,
                                activityName = info.name
                            ),
                            isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .distinctBy { it.app.packageName }
                    .sortedBy { it.app.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
            main.post {
                runCatching { loading.dismiss() }
                showAppDrawerPicker(items)
            }
        }.start()
    }

    private fun showAppDrawerPicker(items: List<AppDrawerCandidate>) {
        if (items.isEmpty()) {
            toastOnce("没有读到可启动的应用")
            return
        }
        val availablePackages = items.map { it.app.packageName }.toSet()
        val selected = LinkedHashMap<String, AppDrawerApp>()
        AppDrawerApps.load(this)
            .filter { it.packageName in availablePackages }
            .take(AppDrawerApps.MAX_APPS)
            .forEach { selected[it.packageName] = it }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val countLabel = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
        }
        list.addView(countLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        fun refreshCount() {
            countLabel.text = "已选 ${selected.size}/${AppDrawerApps.MAX_APPS}（面板按 2 行 × 3 列排列）"
        }
        refreshCount()

        // ★ v1.4.98：左右两栏 —— 左=系统应用，右=个人应用
        val twoCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        list.addView(twoCol)

        fun addCategory(title: String, categoryItems: List<AppDrawerCandidate>) {
            val (col, body) = appListColumn(title)
            twoCol.addView(col)
            if (categoryItems.isEmpty()) {
                body.addView(TextView(this).apply {
                    text = "暂无应用"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    setPadding(dp(4), dp(5), 0, dp(5))
                })
                return
            }
            categoryItems.forEach { candidate ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
                    setPadding(dp(6), dp(3), dp(8), dp(3))
                }
                row.addView(ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    runCatching {
                        setImageDrawable(packageManager.getApplicationIcon(candidate.app.packageName))
                    }.onFailure { setImageResource(R.drawable.ic_car) }
                })
                val check = CheckBox(this).apply {
                    text = "${candidate.app.label}\n${candidate.app.packageName}"
                    textSize = 11f
                    setTextColor(0xFFEDEFF3.toInt())
                    isChecked = candidate.app.packageName in selected
                }
                lateinit var listener: android.widget.CompoundButton.OnCheckedChangeListener
                listener = android.widget.CompoundButton.OnCheckedChangeListener { button, checked ->
                    if (checked && selected.size >= AppDrawerApps.MAX_APPS) {
                        button.setOnCheckedChangeListener(null)
                        button.isChecked = false
                        button.setOnCheckedChangeListener(listener)
                        toastOnce("快捷应用最多选择 ${AppDrawerApps.MAX_APPS} 个")
                        return@OnCheckedChangeListener
                    }
                    if (checked) selected[candidate.app.packageName] = candidate.app
                    else selected.remove(candidate.app.packageName)
                    refreshCount()
                }
                check.setOnCheckedChangeListener(listener)
                row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) })
            }
        }

        addCategory("系统应用", items.filter { it.isSystem })
        addCategory("个人应用", items.filterNot { it.isSystem })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        AlertDialog.Builder(this)
            .setTitle("选择快捷应用")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                AppDrawerApps.save(this, selected.values.toList())
                refreshAppDrawerSelectionLabel()
                Logx.d("快捷应用面板已更新：${selected.size} 个应用")
            }
            // ★ v1.5.8：不再套 applyDockSafe() —— 它会在窗口底部垫 132dp 空白给 Dock 让位，
            //   窗口缩到 0.7 倍后这块空白占了近 1/3。窗口现在屏幕居中、高 434dp，
            //   底边远在 Dock 上方，不需要让位。
            .show()
            // ★ v1.5.6：窗口缩小到 0.7 倍 —— 原来 0.94×620dp 时「保存/取消」被顶到屏幕最下面，点不到
            .window?.setLayout((resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(434))
    }

    // =====================================================================
    // 歌词悬浮窗（融合自 LyricFloat「车机歌词悬浮窗」）
    // =====================================================================

    /**
     * 歌词悬浮窗配置区：
     *  ① 总开关 / 锁定 / 开机自启
     *  ② 外观：透明度、宽、高、字号、行数、偏移、普通行/高亮行颜色
     *  ③ 歌词获取：联网开关、歌词源、自定义接口、立即获取、手动导入
     *  ④ 数据源：元数据字段映射、追加广播 action
     */
    private fun addLyricSection() {
        val root = findViewById<LinearLayout>(R.id.llRoot)

        root.addView(TextView(this).apply {
            text = "歌词悬浮窗"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(0, dp(20), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 当前状态一行
        root.addView(TextView(this).apply {
            text = ""
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
        }.also { tvLyricStatus = it }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // ① 总开关
        root.addView(Button(this).apply {
            text = if (Prefs.lyricEnabled(this@MainActivity)) "关闭歌词悬浮窗" else "启动歌词悬浮窗"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setOnClickListener {
                if (Prefs.lyricEnabled(this@MainActivity)) {
                    Prefs.setLyricEnabled(this@MainActivity, false)
                    stopLyricNow()
                    text = "启动歌词悬浮窗"
                    Logx.d("歌词悬浮窗已关闭")
                } else {
                    Prefs.setLyricEnabled(this@MainActivity, true)
                    if (ensureLyricStarted()) text = "关闭歌词悬浮窗"
                }
                updateLyricStatus()
            }
        }.also { btnLyricToggle = it }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ── [S10] 锁定窗口（点窗口=点它下面的内容） ────────────
        addSwitchRow(
            root, "锁定窗口（点窗口=点它下面的内容）",
            Prefs.lyricLocked(this), reloadDock = false
        ) {
            Prefs.setLyricLocked(this, it)
            sendLyricUpdate()
        }
        // ── [S11] 开机自启歌词悬浮窗 ────────────────────
        addSwitchRow(
            root, "开机自启歌词悬浮窗",
            Prefs.lyricBoot(this), reloadDock = false
        ) { Prefs.setLyricBoot(this, it) }

        // ② 外观（全部「拖动即生效」：onProgress 里写 Prefs + 发广播热更新歌词窗）
        root.addView(TextView(this).apply {
            text = "② 外观（拖动即生效；若歌词窗未启动，请先点上面的启动按钮）"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── [S12] 背景不透明度 ───────────────────────
        addSeekRow(
            root, "背景不透明度",
            Prefs.lyricAlpha(this), 100,
            { "$it%" }, reloadDock = false,
            onChange = { Prefs.setLyricAlpha(this, it) },
            onProgress = { p -> Prefs.setLyricAlpha(this, p); sendLyricUpdate() }
        )
        // ── [S13] 窗口宽度 ─────────────────────────
        addSeekRow(
            root, "窗口宽度",
            Prefs.lyricWinW(this), 1200,
            { "$it dp" }, reloadDock = false,
            onChange = { Prefs.setLyricWinW(this, it) },
            onProgress = { p -> Prefs.setLyricWinW(this, p); sendLyricUpdate() }
        )
        // ── [S14] 窗口高度（最左端=自适应） ────────────────
        addSeekRow(
            root, "窗口高度（最左端=自适应）",
            Prefs.lyricWinH(this), 800,
            { if (it == 0) "自适应" else "$it dp" }, reloadDock = false,
            onChange = { Prefs.setLyricWinH(this, it) },
            onProgress = { p -> Prefs.setLyricWinH(this, p); sendLyricUpdate() }
        )
        // ── [S15] 歌词字号 ─────────────────────────
        addSeekRow(
            root, "歌词字号",
            // ★ 修正：滑块 progress 与真实字号之间差 14（format/onChange 都做 +14），
            //   旧代码传的是原始值 → 打开设置页标签显示「36 sp」而实际生效 22 sp。
            (Prefs.lyricTextSize(this) - 14).coerceIn(0, 26), 26,
            { "${it + 14} sp" }, reloadDock = false,
            onChange = { Prefs.setLyricTextSize(this, it + 14) },
            onProgress = { p -> Prefs.setLyricTextSize(this, p + 14); sendLyricUpdate() }
        )
        // ── [S16] 显示行数 ─────────────────────────
        addSeekRow(
            root, "显示行数",
            Prefs.lyricLineCount(this) - 1, 9,
            { "${it + 1} 行" }, reloadDock = false,
            onChange = { Prefs.setLyricLineCount(this, it + 1) },
            onProgress = { p -> Prefs.setLyricLineCount(this, p + 1); sendLyricUpdate() }
        )
        // ── [S17] 歌词偏移（负=提前，正=延后） ──────────────
        addSeekRow(
            root, "歌词偏移（负=提前，正=延后）",
            (Prefs.lyricOffsetMs(this) + 3000) / 200, 30,
            { "${it * 200 - 3000} ms" }, reloadDock = false,
            onChange = { Prefs.setLyricOffsetMs(this, it * 200 - 3000) },
            onProgress = { p -> Prefs.setLyricOffsetMs(this, p * 200 - 3000); sendLyricUpdate() }
        )

        addColorPresetRow(root, "普通行颜色", Prefs.lyricTextColor(this)) {
            Prefs.setLyricTextColor(this, it)
        }
        addColorPresetRow(root, "当前行颜色", Prefs.lyricHlColor(this)) {
            Prefs.setLyricHlColor(this, it)
        }

        // ③ 歌词获取
        // ── [S18] 允许联网获取歌词（无歌词音源时按歌名抓） ─────────
        addSwitchRow(
            root, "允许联网获取歌词（无歌词音源时按歌名抓）",
            Prefs.lyricOnline(this), reloadDock = false
        ) {
            Prefs.setLyricOnline(this, it)
        }

        // 歌词源 4 选 1
        root.addView(TextView(this).apply {
            text = "歌词源"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val srcLabels = listOf("自动", "网易云", "LRCLIB", "自定义", "QQ音乐")
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val srcButtons = mutableListOf<Pair<Int, Button>>()
        for ((i, lb) in srcLabels.withIndex()) {
            val b = Button(this).apply {
                text = lb
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    Prefs.setLyricSource(this@MainActivity, i)
                    updateSourceButtons()
                    sendLyricUpdate()
                }
            }
            srcButtons += i to b
            srcRow.addView(b, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(4) })
        }
        lyricSourceButtons = srcButtons
        updateSourceButtons()
        root.addView(srcRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        updateLyricStatus()
    }

    private fun updateSourceButtons() {
        val cur = Prefs.lyricSource(this)
        lyricSourceButtons.forEach { (i, btn) ->
            val selected = i == cur
            btn.background = ContextCompat.getDrawable(
                this,
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_field
            )
            btn.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF9AA1AE.toInt())
        }
    }

    private fun updateMapButtons() {
        val cur = Prefs.lyricMapMode(this)
        lyricMapButtons.forEach { (mode, btn) ->
            val selected = mode == cur
            btn.background = ContextCompat.getDrawable(
                this,
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_field
            )
            btn.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF9AA1AE.toInt())
        }
    }

    /** 一行色板（6 个预设色），点击即写入并热更新歌词窗 */
    private fun addColorPresetRow(
        parent: LinearLayout,
        label: String,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        val tv = TextView(this).apply {
            text = "$label：#${Integer.toHexString(current).uppercase()}"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
        parent.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in Prefs.LYRIC_COLOR_PRESETS) {
            val b = Button(this).apply {
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(c)
                    setStroke(dp(2), if (c == current) 0xFF3D8BFF.toInt() else 0x33FFFFFF)
                }
                setOnClickListener {
                    onPick(c)
                    tv.text = "$label：#${Integer.toHexString(c).uppercase()}"
                    sendLyricUpdate()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(
                0, dp(28), 1f
            ).apply { marginStart = dp(4) })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
    }

    // =====================================================================
    // 日志联网上传
    // =====================================================================

    /**
     * 日志上传配置区。
     *
     * 背景：车是用户的、人不在车上、没有 adb，所以让 App 自己把日志 POST 到一个
     * 可被远程读取的收集端点。上传内容只含 App 日志与车控原始值，不含任何账号信息。
     */
    private fun addLogUploadSection() {
        val root = findViewById<LinearLayout>(R.id.cardLogs)

        root.addView(TextView(this).apply {
            text = "日志上传（远程排查）"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(0, dp(16), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val tvHint = TextView(this).apply {
            text = "有网时日志自动上传（大约每 1.5 秒一批），不用点按钮。\n" +
                    "没网先记在本机，恢复网络后补传。闪退仍会单独上传。\n" +
                    "只含 App 日志、崩溃堆栈与车控原始值，不含账号。"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(tvHint)

        val urlRow = addEditRow(
            root, "上传地址（POST JSON）",
            Prefs.logUploadUrl(this).ifBlank { LogUploader.DEFAULT_URL }
        ) { v ->
            Prefs.setLogUploadUrl(this, v)
            tvHint.text = "已保存地址：${LogUploader.endpoint(this)}"
        }

        // ── [S19] 启用日志上传 ───────────────────────
        addSwitchRow(
            root, "启用日志上传",
            Prefs.logUploadEnabled(this),
            reloadDock = false
        ) {
            Prefs.setLogUploadEnabled(this, it)
            tvHint.text = if (it) {
                "已启用 → ${LogUploader.endpoint(this)}"
            } else {
                "已关闭：不会再上传任何内容"
            }
        }

        // 默认打开。DCY11 的设定温度只有这条 float 回调能读到。
        // 真的闪退过（有上次会话，且没活过 15 秒）才会被 App.onCreate 关回去。
        // 首次安装没有上次会话，不能因为缺少 clean_exit 就关。
        // ── [S20] ⚠ 温度事件监听（实验，可能闪退） ────────────
        addSwitchRow(
            root, "⚠ 温度事件监听（实验，可能闪退）",
            Prefs.vehicleTempWatcherEnabled(this),
            reloadDock = false
        ) {
            Prefs.setVehicleTempWatcherEnabled(this, it)
            Logx.w(if (it) "温度事件监听 → 开启（若闪退会自动关回）" else "温度事件监听 → 关闭")
        }

        // ★ v1.4.51 闪退二分开关：关掉监听/写入/温度之后 CX11 仍在闪退，
        //   所以要把「车控通道」和「反射截屏」整个摘掉才能定性。
        //   连续崩溃 2 / 3 次时 App 会自动把这两个关掉（见 App.onCreate）。
        // ★ 车控写入总开关。连续崩溃会被 App 自动熔断关掉（见 App.onCreate），
        //   之前主界面没有入口，用户一旦被熔断就再也开不回来（只能重装）→ 补上。
        // ── [S21] ★ 车控写入（关=只能读状态，调不动空调） ────────
        addSwitchRow(
            root, "★ 车控写入（关=只能读状态，调不动空调）",
            Prefs.vehicleWriteEnabled(this),
            reloadDock = false
        ) {
            Prefs.setVehicleWriteEnabled(this, it)
            Logx.w(if (it) "车控写入 → 开启（若闪退会自动熔断关回）" else "车控写入 → 关闭：只能读取状态")
        }

        // ── [S22] ★ 车控通道（关=完全不调厂商接口，排查闪退） ──────
        addSwitchRow(
            root, "★ 车控通道（关=完全不调厂商接口，排查闪退）",
            Prefs.vehicleApiEnabled(this),
            reloadDock = true
        ) {
            Prefs.setVehicleApiEnabled(this, it)
            Logx.w(if (it) "车控通道 → 开启" else "车控通道 → 关闭：不调用任何厂商接口，空调状态不再刷新")
        }

        val tvState = TextView(this).apply {
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        tvStateRef = tvState
        root.addView(tvState)

        fun refreshState() {
            val crashFile = LogUploader.javaCrashFile(this)
            val nativeFile = LogUploader.nativeCrashFile(this)
            val lvl = Prefs.safeLevel(this)
            tvState.text = buildString {
                append("设备 ").append(LogUploader.deviceId(this@MainActivity)).append('\n')
                append("Native 崩溃捕获：").append(NativeCrashHandler.statusText()).append('\n')
                append("安全等级：L").append(lvl).append("（").append(App.safeLevelDesc(lvl)).append("）\n")
                append("待补传崩溃：")
                append(
                    when {
                        LogUploader.hasPendingCrash(this@MainActivity) -> "有 ⚠"
                        crashFile.exists() || nativeFile.exists() -> "无（已上传）"
                        else -> "无"
                    }
                ).append('\n')
                append("上次结果：").append(LogUploader.lastResult.ifBlank { "尚无上传记录" })
            }
        }
        refreshState()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // ── [S23] 测试上传 ─────────────────────────
        row.addView(smallBtn("测试上传") { btn ->
            Prefs.setLogUploadUrl(this, urlRow.text.toString().trim())
            tvState.text = "正在测试上传…（最长 10 秒，完成后自动刷新状态）"
            btn.isEnabled = false
            btn.alpha = 0.45f
            LogUploader.testUpload(this)
            main.postDelayed({
                btn.isEnabled = true
                btn.alpha = 1f
                refreshState()
            }, 3000)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // ── [S24] 立即上传日志 ───────────────────────
        row.addView(smallBtn("立即上传日志") { btn ->
            Prefs.setLogUploadUrl(this, urlRow.text.toString().trim())
            tvState.text = "正在上传日志…（最长 10 秒，完成后自动刷新状态）"
            btn.isEnabled = false
            btn.alpha = 0.45f
            LogUploader.uploadLogTail(this, "manual", "主界面手动上传")
            main.postDelayed({
                btn.isEnabled = true
                btn.alpha = 1f
                refreshState()
            }, 3000)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        // ── [S25] 诊断提权环境 ───────────────────────
        row.addView(smallBtn("诊断提权环境") { btn ->
            tvState.text = "正在探测 adb / root / 车控权限…（约 10 秒）"
            btn.isEnabled = false
            btn.alpha = 0.45f
            Thread {
                val r = runCatching { PrivilegeProbe.report(this@MainActivity) }
                    .getOrElse { "探测失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(r)
                main.post {
                    tvState.text = r
                    btn.isEnabled = true
                    btn.alpha = 1f
                    LogUploader.upload(this@MainActivity, "privilege_probe", r, "提权环境诊断")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        // ── [S26] 一键提权(adb) ────────────────────
        row.addView(smallBtn("一键提权(adb)") { btn ->
            tvState.text = "正在连本机 adbd 并授予车控权限…（约 20 秒）"
            btn.isEnabled = false
            btn.alpha = 0.45f
            Thread {
                val r = runCatching { PrivilegeProbe.grantCarPermissionsViaAdb(this@MainActivity) }
                    .getOrElse { "提权失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(r)
                main.post {
                    tvState.text = r
                    btn.isEnabled = true
                    btn.alpha = 1f
                    LogUploader.upload(this@MainActivity, "adb_grant", r, "adb 自授权尝试")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })

        // ── [S27] 关闭 ADB（安全加固）─────────────────
        //   ★ v3.13.15：**按用户要求隐藏该按钮**（不再在界面上暴露）。
        //   这台 userdebug 车机 adbd 常开且 service.adb.tcp.port=5555 免 RSA 认证
        //   → 本机任何 App 都能直接拿到 adb shell。
        //   ⚠ 底层实现 PrivilegeProbe.disableAdb() **保留未删**（随时可恢复入口）。
        //   补充事实：disableAdb 只关 TCP 端口、stop adbd 后又 ctl.restart adbd，
        //   **USB adb 仍然可用**，并非「不可逆」。
        //
        // row.addView(smallBtn("关闭 ADB（安全加固）") { btn ->
        //     tvState.text = "正在关闭 ADB…（约 5 秒）"
        //     btn.isEnabled = false
        //     btn.alpha = 0.45f
        //     Thread {
        //         val r = runCatching { PrivilegeProbe.disableAdb(this@MainActivity) }
        //             .getOrElse { "关闭失败：${it.javaClass.simpleName}: ${it.message}" }
        //         Logx.d(r)
        //         main.post {
        //             tvState.text = r
        //             btn.isEnabled = true
        //             btn.alpha = 1f
        //             LogUploader.upload(this@MainActivity, "adb_disable", r, "关闭 ADB")
        //         }
        //     }.apply { isDaemon = true }.start()
        // }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        //     marginStart = dp(6)
        // })
        // ── [S28] 清除崩溃记录 ───────────────────────
        row.addView(smallBtn("清除崩溃记录") {
            LogUploader.clearCrashEvidence(this)
            CrashHandler.clear(this)
            Logx.d("崩溃取证已清除")
            refreshState()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ── [S30] ★ v4.0：扫描车控通道 ────────────────────────
        //   暴力扫 0x10000000~0x100A0000 × zone(0x8/1/4)，找出「哪些 functionId
        //   真的有值」—— 用来在**新车型**（如 DCY11）上定位真实通道 ID。
        //   （原按钮在「车控通道」区里，但那个区整块 visibility=gone，
        //     是按用户要求隐藏的 —— 所以在这里另开一个可见入口。）
        root.addView(smallBtn("★ 扫描车控通道（找真实通道 ID）") { btn ->
            tvState.text = "正在扫描车控通道（约 7680 次读，10~40 秒）…"
            btn.isEnabled = false
            btn.alpha = 0.45f
            Thread {
                val r = runCatching { VehicleController.scanChannels(this@MainActivity) }
                    .getOrElse { "扫描失败：${it.javaClass.simpleName}: ${it.message}" }
                Logx.d(r)
                main.post {
                    tvState.text = r
                    btn.isEnabled = true
                    btn.alpha = 1f
                    LogUploader.upload(this@MainActivity, "channel_scan", r, "车控通道扫描")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ── [S29] ★ v3.6：内外循环专项取证 ───────────────────
        //   抓**系统 Dock / 系统空调 App** 切内外循环时的真实 HAL 调用，
        //   用来定论这台车到底认哪个值（裸值 0 / 0x10030101 / 10-11 枚举）。
        //   这是唯一能拿到「别人怎么写」的途径。
        //
        // ★ v3.7：**默认关闭，按下才开 2 分钟窗口**。
        //   · 平时（窗口关闭）不注册任何额外 watcher 通道、不打任何取证日志、
        //     不多发一次 binder 调用 → **对日常运行零风险**。
        //   · 窗口内才做主动轮询取证（读循环 / AUTO 总开关的原始值）。
        //   · 抓完立刻关闭窗口。
        root.addView(smallBtn("抓循环取证（系统 Dock/空调 App）") { btn ->
            tvState.text = "取证窗口已开启（2 分钟），请操作系统的内外循环…"
            btn.isEnabled = false
            btn.alpha = 0.45f
            // ★ 开窗口：2 分钟（覆盖 90 秒的 logcat 抓取窗口 + 余量）
            VehicleController.openEvidence(120_000L)
            Thread {
                val r = runCatching { PrivilegeProbe.grabRecircEvidence(90_000) }
                    .getOrElse { "取证失败：${it.javaClass.simpleName}: ${it.message}" }
                // ★ 立刻关窗口 —— 取证结束即恢复正常，不留任何常驻开销
                VehicleController.closeEvidence()
                Logx.d(r)
                main.post {
                    tvState.text = r
                    btn.isEnabled = true
                    btn.alpha = 1f
                    LogUploader.upload(this@MainActivity, "recirc_evidence", r, "内外循环专项取证")
                }
            }.apply { isDaemon = true }.start()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ★ v1.4.65（实验）：温度走 Customize 浮点通道。
        //   温度 int 通道实测只读（1362 次写入 0 成功），而系统空调的温度是 float。
        //   Customize 通道 1.4.47 曾被禁（「调用即 SIGSEGV」），但那个结论很可能是误诊：
        //   当时 dock-contrast 截屏线程每秒在跑（后来证实的 SIGABRT 真凶），
        //   且当时取证只有 maps 原文。现在取证齐全，崩了能直接定位。
        // ── [S28] ★ 实验：温度走 Customize 浮点通道（1.4.47 曾误禁） ─
        addSwitchRow(
            root, "★ 实验：温度走 Customize 浮点通道（1.4.47 曾误禁）",
            Prefs.tempCustomizeWriteEnabled(this),
            reloadDock = true
        ) {
            Prefs.setTempCustomizeWriteEnabled(this, it)
            Logx.w(if (it) "温度 Customize 写入 → 开启（实验）。若崩溃，取证会给出真凶并自动熔断"
                   else "温度 Customize 写入 → 关闭")
        }

        // 退出安全模式：恢复全功能（再崩会自动重新降级）
        // ── [S30] 退出安全模式（恢复全功能） ────────────────
        root.addView(smallBtn("退出安全模式（恢复全功能）") {
            Prefs.setSafeLevel(this, 0)
            Logx.d("已手动退出安全模式，恢复全功能")
            reloadService()
            refreshState()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    /** 一行「标签 + 输入框」，返回输入框方便取值 */
    private fun addEditRow(
        parent: LinearLayout,
        label: String,
        value: String,
        onCommit: (String) -> Unit
    ): EditText {
        val tv = TextView(this).apply {
            text = label
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
        parent.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val et = EditText(this).apply {
            setText(value)
            setTextColor(0xFFEDEFF3.toInt())
            setHintTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) onCommit(et.text.toString().trim())
        }
        parent.addView(et, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        return et
    }

    /** 手动导入 / 粘贴歌词 */
    private fun showManualLyricDialog() {
        val title = LyricMonitor.currentTitle
        if (title.isNullOrEmpty()) {
            toastOnce("还没读到歌曲信息，先放一首歌")
            return
        }
        val et = EditText(this).apply {
            hint = "粘贴 LRC 歌词（[mm:ss.xx] 格式）"
            setTextColor(0xFFEDEFF3.toInt())
            setHintTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
            minLines = 6
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导入歌词：$title")
            .setView(et)
            .setPositiveButton("套用") { _, _ ->
                val txt = et.text.toString()
                if (txt.isBlank()) {
                    toastOnce("歌词内容为空")
                } else if (LyricMonitor.manualLyric(txt)) {
                    toastOnce("已套用到《$title》")
                    Logx.d("手动导入歌词成功：$title")
                    sendLyricUpdate()
                } else {
                    toastOnce("套用失败：歌曲信息已变化，重试一次")
                }
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
    }

    private fun sendLyricUpdate() {
        runCatching {
            sendBroadcast(Intent(LyricOverlayService.ACTION_UPDATE).setPackage(packageName))
        }
    }

    /** 本地 .lrc 兜底需要读公共目录；API 33+ 用细分媒体权限，28~32 用 READ_EXTERNAL_STORAGE */    private fun requestStorageForLrc() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) {
            toastOnce("存储权限已授予，本地 .lrc 兜底可用")
            return
        }
        runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }.onFailure {
            Logx.e("申请存储权限失败", it)
            toastOnce("申请失败，请到系统设置里手动授予存储权限")
        }
    }

    private fun stopLyricNow() {
        LyricWindows.stop(this)
        main.postDelayed({ updateLyricStatus() }, 500)
    }

    /** 启动歌词悬浮窗（含悬浮窗权限检查）。返回是否成功启动 */
    private fun ensureLyricStarted(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Logx.e("歌词悬浮窗：没有悬浮窗权限")
            toastOnce("请先授予悬浮窗权限，回来后会自动出现")
            IntentLauncher.requestOverlayPermission(this)
            return false
        }
        if (!LyricWindows.start(this)) {
            toastOnce("启动失败，详情看日志")
            return false
        }
        main.postDelayed({ updateLyricStatus() }, 800)
        return true
    }

    private fun updateLyricStatus() {
        tvLyricStatus?.text = buildString {
            append("歌词悬浮窗：").append(
                when {
                    LyricOverlayService.alive && LyricOverlayService.attached -> "运行中 ✓"
                    LyricOverlayService.alive -> "运行中，但窗口没挂上 ✗"
                    else -> "未运行"
                }
            )
            val np = LyricOverlayService.nowPlaying
            if (!np.isNullOrBlank()) append("　当前：").append(np)
            val src = LyricOverlayService.progressSource
            if (!src.isNullOrBlank()) append("　进度源：").append(src)
            val tk = LyricMonitor.current
            if (tk != null && !tk.title.isNullOrEmpty()) {
                append("　歌词：").append(
                    when {
                        tk.lyric == null -> "未获取"
                        tk.lines.isNullOrEmpty() -> "无时间轴"
                        else -> "${tk.lines!!.size} 行"
                    }
                )
                if (!tk.source.isNullOrBlank()) append("（${tk.source}）")
            }
        }
        btnLyricToggle?.text =
            if (Prefs.lyricEnabled(this@MainActivity)) "关闭歌词悬浮窗" else "启动歌词悬浮窗"
    }

    /** 刷新预览条（固定上→下，跟随当前系统深浅色、透明度、纯色模式） */
    private fun refreshGradientPreview() {
        val solid = Prefs.bgSolidMode(this)
        gradientPreview?.update(
            if (solid) Prefs.solidBgColor(this) else 0,
            if (solid) null else Prefs.currentGradientColors(this)
        )
    }

    /**
     * 渐变预览条：把当前配置的渐变按 1:1 画出来，
     * 让用户在配置页就能看到深浅色两种模式下的实际效果。
     *
     * ★ v3.13.0：改用 util/GradientSpec（与 DockLayout.rebuildShader 同一份实现），
     *   彻底解决「预览与实际不一致」的老问题。
     * ★ v3.13.1：支持纯色模式（solidColor != null 时画纯色）。
     */
    private class GradientPreviewView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: Shader? = null
        private var colors: IntArray? = null
        private var solidColor: Int? = null

        fun update(solidColor: Int, colors: IntArray?) {
            this.solidColor = solidColor
            this.colors = colors
            shader = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val solid = solidColor
            if (solid != null) {
                // 纯色模式：整条一个颜色
                paint.shader = null
                paint.color = solid
                canvas.drawRect(0f, 0f, w, h, paint)
                return
            }
            val cs = colors
            if (cs == null || cs.size < 2) {
                paint.shader = null
                paint.color = 0x59000000
                canvas.drawRect(0f, 0f, w, h, paint)
                return
            }
            if (shader == null && w > 0f && h > 0f) {
                val ramp = com.car.dockbar.util.GradientSpec.verticalRampColors(cs)
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    ramp,
                    com.car.dockbar.util.GradientSpec.rampPositions(ramp.size),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        reloadDock: Boolean = true,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 12f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = SwitchCompat(this).apply { isChecked = checked }
        sw.setOnCheckedChangeListener { _, v ->
            onChange(v)
            if (reloadDock) reloadService()
        }
        row.addView(sw)
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addSeekRow(
        parent: LinearLayout,
        label: String,
        progress: Int,
        max: Int,
        format: (Int) -> String,
        reloadDock: Boolean = true,
        onChange: (Int) -> Unit,
        onProgress: ((Int) -> Unit)? = null
    ): LinearLayout {
        // ★ v3.13.16：把「标签 + 滑杆」包进一个容器并返回 —— 便于按背景形态整体显隐
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            text = "$label：${format(progress)}"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val sb = SeekBar(this).apply {
            this.max = max
            this.progress = progress
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$label：${format(p)}"
                onProgress?.invoke(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                onChange(sb.progress)
                if (reloadDock) reloadService()
            }
        })
        box.addView(sb, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return box
    }

    // =====================================================================

    // =====================================================================
    // 常驻显示 / 隐藏 Dock 的应用名单
    // =====================================================================

    /**
     * ★ v1.4.98：已安装应用里的「系统应用」包名集合。
     * ★ v1.5.10：`null` = 后台预热还没取回来（见 [prewarmSystemPkgs]）。
     */
    @Volatile
    private var systemPkgCache: Set<String>? = null

    /**
     * 系统应用包名集合。后台预热还没回来先返回空集 —— 列表会全部归到「个人应用」那一栏，
     * 预热取回来后 [prewarmSystemPkgs] 会自动重排一次。
     */
    private fun systemPkgs(): Set<String> = systemPkgCache ?: emptySet()

    /**
     * ★ v1.5.10：**后台预热**系统应用包名集合。
     *
     * `getInstalledApplications(0)` 是一次返回全量应用的大 Binder 调用，车机上几十~百来 ms。
     * 原来它在 [renderDockAppList] 里**同步**跑 —— 第一次点「常驻应用」入口就卡一下。
     * 现在：`onCreate` 就扔到后台单线程池（复用图标加载那条线程），取回来写缓存；
     * 如果用户此刻已经停在「常驻应用」页，顺手重排一次列表。
     */
    private fun prewarmSystemPkgs() {
        if (systemPkgCache != null) return
        iconExecutor.execute {
            val set = runCatching {
                packageManager.getInstalledApplications(0)
                    .asSequence()
                    .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }
                    .map { it.packageName }
                    .toSet()
            }.getOrDefault(emptySet())
            systemPkgCache = set
            main.post { if (currentPage == SECTION_APPS) renderDockAppList() }
        }
    }

    /**
     * 一列「应用名单」容器（标题 + 竖列表），左右两栏各一个。
     * 返回 Pair(外层容器, 装行的竖列表)。
     */
    private fun appListColumn(title: String): Pair<LinearLayout, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(dpInt(4), dpInt(2), dpInt(4), dpInt(6))
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(body)
        return col to body
    }

    /**
     * ★ v1.5.5：应用图标**异步加载 + 内存缓存**。
     *
     * 之前是 59 个包名逐个 `packageManager.getApplicationIcon(pkg)`，**全在主线程** ——
     * 每次点「常驻应用」都要等 0.3～1.8 秒（每个图标都是一次 Binder + 资源解码）。
     * 现在：命中缓存直接贴上去；没命中先留兜底图标，后台单线程取回来再 post 填进 ImageView。
     *
     * @param iv 已显示兜底图标的 ImageView；加载回来前如果它被复用给别的包名（tag 变了）就不贴
     */
    private fun loadAppIconAsync(pkg: String, iv: ImageView) {
        synchronized(iconCache) {
            if (iconCache.containsKey(pkg)) {
                iconCache[pkg]?.let { iv.setImageDrawable(it) }
                return
            }
        }
        iv.tag = pkg
        iconExecutor.execute {
            val d = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            synchronized(iconCache) { iconCache[pkg] = d }
            main.post {
                // tag 仍是这个包名才贴（列表重建 / 行被复用时不贴错图标）
                if (iv.tag == pkg && d != null) iv.setImageDrawable(d)
            }
        }
    }

    /**
     * 渲染名单：系统默认全屏应用全部列出（默认关 = 隐藏 Dock），
     * 用户自己添加的应用也列在后面。
     *
     * ★ v1.4.97 #7：开关语义回到「常驻显示」——
     *   **打开** = 这个应用在前台时 Dock 保持显示；**关闭** = Dock 隐藏。
     * ★ v1.4.97 #8：行高 / 字号放大，与外层设置页一致。
     * ★ v1.4.98：**左右两栏** —— 左=系统应用，右=个人应用；
     *   系统 Dock 白名单（Prefs.DEFAULT_DOCK_SHOW）里的默认开关是「开」。
     */
    private fun renderDockAppList() {
        llDockApps.removeAllViews()
        val always = Prefs.dockAlwaysShowApps(this)
        val customHide = Prefs.dockCustomHideApps(this)
        val defaults = Prefs.DEFAULT_DOCK_HIDE.map { it.first }
        val custom = (always + customHide).filter { p -> defaults.none { it == p } }.distinct()
        val sysPkgs = systemPkgs()

        val twoCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val (colSys, bodySys) = appListColumn("系统应用")
        val (colUser, bodyUser) = appListColumn("个人应用")
        twoCol.addView(colSys)
        twoCol.addView(colUser)
        llDockApps.addView(twoCol)

        for (pkg in (defaults + custom).distinct()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
                setPadding(dpInt(10), dpInt(8), dpInt(8), dpInt(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpInt(4) }
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(34), dpInt(34))
                scaleType = ImageView.ScaleType.FIT_CENTER
                // ★ v1.5.5：先放兜底图标，真正的图标走异步加载（见 loadAppIconAsync）
                setImageResource(R.drawable.ic_car)
            }.also { loadAppIconAsync(pkg, it) })
            val tv = TextView(this).apply {
                text = "${Prefs.dockHideLabel(pkg)}\n$pkg"
                setTextColor(0xFFEDEFF3.toInt())
                textSize = 15f
                setPadding(dpInt(6), 0, dpInt(6), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val sw = SwitchCompat(this).apply {
                isChecked = always.contains(pkg)
                setOnCheckedChangeListener { _, on ->
                    val a = Prefs.dockAlwaysShowApps(this@MainActivity).toMutableSet()
                    val h = Prefs.dockCustomHideApps(this@MainActivity).toMutableSet()
                    if (on) {
                        a.add(pkg); h.remove(pkg)
                    } else {
                        a.remove(pkg)
                        // 系统默认全屏应用本来就会隐藏，不必再记一份；自定义应用要记下来
                        if (pkg !in defaults) h.add(pkg) else h.remove(pkg)
                    }
                    Prefs.setDockAlwaysShowApps(this@MainActivity, a)
                    Prefs.setDockCustomHideApps(this@MainActivity, h)
                    notifyAppsChanged()
                    Logx.d("名单：$pkg → ${if (on) "常驻显示" else "隐藏"}")
                }
            }
            row.addView(tv)
            row.addView(sw)
            if (pkg in sysPkgs) bodySys.addView(row) else bodyUser.addView(row)
        }
        // 某一栏为空时给个占位，避免看起来像没加载出来
        listOf(bodySys to "（无系统应用）", bodyUser to "（无个人应用）").forEach { (body, tip) ->
            if (body.childCount == 0) {
                body.addView(TextView(this@MainActivity).apply {
                    text = tip
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 13f
                    setPadding(dpInt(6), dpInt(6), 0, dpInt(6))
                })
            }
        }
        refreshAccStatus()
    }

    /**
     * ★ v1.4.97 #9：从**已安装应用列表**里选，不再手输包名。
     * ★ v1.4.98：**左右两栏** —— 左=系统应用，右=个人应用，点一行即加入名单。
     * - 选到**自定义应用**（不在系统默认名单里）→ 默认「关」= 该应用在前台时隐藏 Dock；
     * - 选到**系统默认全屏应用**（本来就在页面上）→ 直接置「开」= 该应用前台时 Dock 常驻显示。
     * 加进来后都能在列表里随时改。
     */
    private fun pickInstalledAppForDockList() {
        val loading = AlertDialog.Builder(this)
            .setTitle("正在读取已安装应用…")
            .setCancelable(true)
            .create()
        loading.show()
        loading.applyDockSafe()
        Thread {
            val pm = packageManager
            val items = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).asSequence()
                    .mapNotNull { it.activityInfo }
                    .map { info ->
                        val appInfo = info.applicationInfo
                        AppDrawerCandidate(
                            app = AppDrawerApp(
                                label = info.loadLabel(pm).toString(),
                                packageName = info.packageName,
                                activityName = info.name
                            ),
                            isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .distinctBy { it.app.packageName }
                    .sortedBy { it.app.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
            main.post {
                runCatching { loading.dismiss() }
                showInstalledAppPicker(items)
            }
        }.start()
    }

    /** 已安装应用选择弹窗：左=系统应用 / 右=个人应用，点一行即加入名单 */
    private fun showInstalledAppPicker(items: List<AppDrawerCandidate>) {
        if (items.isEmpty()) {
            toastOnce("没有读到可启动的应用")
            return
        }
        val twoCol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val (colSys, bodySys) = appListColumn("系统应用")
        val (colUser, bodyUser) = appListColumn("个人应用")
        twoCol.addView(colSys)
        twoCol.addView(colUser)

        lateinit var dialog: AlertDialog
        fun addRow(c: AppDrawerCandidate, body: LinearLayout) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener {
                    runCatching { dialog.dismiss() }
                    addDockListApp(c.app.packageName, c.app.label)
                }
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                scaleType = ImageView.ScaleType.FIT_CENTER
                runCatching { setImageDrawable(packageManager.getApplicationIcon(c.app.packageName)) }
                    .onFailure { setImageResource(R.drawable.ic_car) }
            })
            row.addView(TextView(this).apply {
                text = "${c.app.label}\n${c.app.packageName}"
                setTextColor(0xFFEDEFF3.toInt())
                textSize = 14f
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            body.addView(row)
        }
        items.forEach { addRow(it, if (it.isSystem) bodySys else bodyUser) }
        listOf(bodySys to "（无系统应用）", bodyUser to "（无个人应用）").forEach { (body, tip) ->
            if (body.childCount == 0) {
                body.addView(TextView(this@MainActivity).apply {
                    text = tip
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 13f
                    setPadding(dp(6), dp(6), 0, dp(6))
                })
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(twoCol)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("选择要添加的应用")
            .setView(scroll)
            .setNegativeButton("取消", null)
            // ★ v1.5.8：不再套 applyDockSafe() —— 它会在窗口底部垫 132dp 空白给 Dock 让位，
            //   窗口缩到 0.7 倍后这块空白占了近 1/3。窗口现在是屏幕居中、高 434dp，
            //   底边远在 Dock 上方，不需要让位。
            .show()
        // ★ v1.5.6：窗口缩小到 0.7 倍（原来 0.94×620dp 太大，底部内容被 Dock 挡住）
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(434))
    }

    /** 把一个应用加进名单（默认「关」= 隐藏），并刷新列表 */
    private fun addDockListApp(pkg: String, label: String) {
        val defaults = Prefs.DEFAULT_DOCK_HIDE.map { it.first }.toSet()
        val a = Prefs.dockAlwaysShowApps(this).toMutableSet()
        val h = Prefs.dockCustomHideApps(this).toMutableSet()
        if (pkg in defaults) {
            // 系统默认名单里的应用本来就在页面上，打开它 = 常驻显示
            a.add(pkg); h.remove(pkg)
        } else {
            a.remove(pkg); h.add(pkg)
        }
        Prefs.setDockAlwaysShowApps(this, a)
        Prefs.setDockCustomHideApps(this, h)
        notifyAppsChanged()
        renderDockAppList()
        Logx.d("已加入名单：$label（$pkg）→ ${if (pkg in a) "常驻显示" else "隐藏"}")
    }

    /** 通知悬浮窗服务立刻按当前前台应用重算是否该隐藏 */
    private fun notifyAppsChanged() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_APPS_CHANGED).setPackage(packageName))
        }.onFailure { Logx.e("发送名单变更广播失败", it) }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { Logx.e("打开无障碍设置失败", it) }
    }

    /** 无障碍（前台应用检测）是否已开启。短类名和全类名都算开着。 */
    private fun isAccessibilityEnabled(): Boolean = AccessibilityKeep.isEnabled(this)

    /**
     * ★ v1.5.6：「已开启 / 未开启」这几个字加粗（其余保持普通字重）。
     * 整行字号在 XML 里已经放到 24sp（原来 12sp，放大 2 倍）。
     */
    private fun refreshAccStatus() {
        AccessibilityKeep.restore(this)
        val on = isAccessibilityEnabled()
        val cur = ForegroundTracker.currentPackage
        val state = if (on) "已开启" else "未开启"
        val tail = if (on) {
            if (cur != null) "（当前前台：$cur）" else ""
        } else {
            " —— 名单需先开启无障碍才生效"
        }
        tvAccPerm.text = if (on) "已授权" else "未授权"
        tvAccPerm.setTextColor(permColor(on))
        tvAccHint.visibility = if (on) View.GONE else View.VISIBLE
        tvAccStatus.text = android.text.SpannableStringBuilder().apply {
            append("前台应用检测：")
            val start = length
            append(state)
            setSpan(
                android.text.style.TypefaceSpan(AppFont.bold(this@MainActivity)),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(tail)
        }
    }

    // ---------------- 子界面模式 ----------------

    /** 隐藏主设置页所有分区卡片 */
    private fun hideAllSections() {
        val root = findViewById<LinearLayout>(R.id.llRoot)
        for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.GONE
    }

    // ===== ★ v1.5.00：左栏入口 + 右栏内容（master-detail） =====

    /** 歌词区动态 view 在 llRoot 里的起始索引（-1 = 当前没加进来） */
    private var lyricSectionStart = -1

    /** 左栏入口。右侧仍是原来的页面，只改左侧样子。 */
    private val NAV_ITEMS = listOf(
        NavSpec(SECTION_OVERLAY, "悬浮 Dock 栏", R.drawable.ic_nav_dock),
        NavSpec(SECTION_APPEARANCE, "外观", R.drawable.ic_nav_home),
        NavSpec(SECTION_ICONS, "Dock条目", R.drawable.ic_nav_grid),
        NavSpec(SECTION_LYRIC, "歌词", R.drawable.ic_nav_doc),
        NavSpec(SECTION_APPS, "常驻应用", R.drawable.ic_nav_list),
        NavSpec(SECTION_LOGS, "诊断", R.drawable.ic_nav_pulse)
    )

    /** 生成左栏：标题 + 图标文字行。当前项左侧青色竖条。 */
    private fun buildNav() {
        val nav = findViewById<LinearLayout>(R.id.llNav)
        nav.removeAllViews()
        navButtons.clear()
        nav.addView(TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(0xFFF2F4F8.toInt())
            typeface = AppFont.bold(this@MainActivity)
            setPadding(dp(20), dp(8), dp(12), dp(18))
        })
        for (item in NAV_ITEMS) {
            val bar = View(this).apply {
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(16)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val label = TextView(this).apply {
                text = item.label
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nav_idle))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = dp(12)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(12), 0)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_item_press)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                )
                setOnClickListener { showPage(item.page) }
                addView(bar)
                addView(icon)
                addView(label)
            }
            nav.addView(row)
            navButtons[item.page] = NavRow(bar, icon, label)
        }
        nav.addView(TextView(this).apply {
            text = "v${Prefs.appVersion(this@MainActivity)}"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(dp(20), dp(16), dp(12), 0)
        })
    }

    /** 切换右栏内容到 page（左栏入口 key） */
    private fun showPage(page: String) {
        currentPage = page
        section = page
        hideAllSections()
        when (page) {
            SECTION_OVERLAY -> cardOverlay?.visibility = View.VISIBLE
            SECTION_APPEARANCE -> findViewById<View>(R.id.cardAppearance).visibility = View.VISIBLE
            SECTION_ICONS -> cardDockIcon?.visibility = View.VISIBLE
            SECTION_APPS -> {
                cardDockApps?.visibility = View.VISIBLE
                renderDockAppList()
            }
            SECTION_LOGS -> cardLogs?.visibility = View.VISIBLE
            SECTION_LYRIC -> showOnlyLyricSection()
            else -> cardOverlay?.visibility = View.VISIBLE
        }
        title = "设置 · ${navLabel(page)}"
        updateNavSelection()
        scrollToTop()
        Logx.d("设置页：切到「${navLabel(page)}」")
    }

    /** 左栏当前页：青色竖条、青色图标和文字 */
    private fun updateNavSelection() {
        val accent = ContextCompat.getColor(this, R.color.nav_accent)
        val idle = ContextCompat.getColor(this, R.color.nav_idle)
        for ((page, row) in navButtons) {
            val on = page == currentPage
            val color = if (on) accent else idle
            row.bar.setBackgroundColor(if (on) accent else 0x00000000)
            row.icon.imageTintList = ColorStateList.valueOf(color)
            row.label.setTextColor(color)
            row.label.typeface = if (on) AppFont.bold(this) else AppFont.regular(this)
        }
    }

    private fun navLabel(page: String): String =
        NAV_ITEMS.firstOrNull { it.page == page }?.label ?: page

    private data class NavSpec(val page: String, val label: String, val icon: Int)

    private class NavRow(val bar: View, val icon: ImageView, val label: TextView)

    /** 兼容老入口（「更多设置」那 3 个按钮 / 外部带 extra 进来） */
    private fun enterSection(name: String) = showPage(name)

    private fun scrollToTop() {
        (findViewById<View>(R.id.llRoot).parent as? ScrollView)?.scrollTo(0, 0)
    }

    override fun onBackPressed() {
        // 不在第一个入口时，返回键先回到「悬浮 Dock 栏」；已经在第一个入口就正常退出
        if (currentPage != SECTION_OVERLAY) showPage(SECTION_OVERLAY) else super.onBackPressed()
    }

    /**
     * 只显示「歌词悬浮窗设置」。
     * 歌词区是动态追加到 llRoot 末尾的 —— 重新进入时先整体移除再重建，避免重复叠加。
     */
    private fun showOnlyLyricSection() {
        val root = findViewById<LinearLayout>(R.id.llRoot)
        if (lyricSectionStart >= 0) {
            while (root.childCount > lyricSectionStart) root.removeViewAt(root.childCount - 1)
            lyricSectionStart = -1
        }
        hideAllSections()
        lyricSectionStart = root.childCount
        addLyricSection()
    }

    private fun dpInt(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun saveWindow() {
        val w = findViewById<EditText>(R.id.etWidth).text.toString().trim().toIntOrNull() ?: 1920
        val h = findViewById<EditText>(R.id.etHeight).text.toString().trim().toIntOrNull() ?: 40
        val m = findViewById<EditText>(R.id.etMargin).text.toString().trim().toIntOrNull() ?: 0
        Prefs.setDockWidthPx(this, w)
        Prefs.setDockHeightPx(this, h)
        Prefs.setDockMarginPx(this, m)
        Prefs.setDockAtBottom(this, true)
        Prefs.setScreenHeightPx(
            this,
            findViewById<EditText>(R.id.etScreenH).text.toString().trim().toIntOrNull() ?: 1080
        )
        Prefs.setSimulate(this, findViewById<SwitchCompat>(R.id.swSimulate).isChecked)
        Logx.d("窗口配置已保存：${if (w > 0) w else "MATCH_PARENT"}×$h，距边 $m px")
    }

    private fun saveAppearance() {
        Prefs.setCornerRadiusPx(this, findViewById<SeekBar>(R.id.sbCorner).progress)
        Prefs.setIconScale(this, 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)
        Prefs.setTextScale(this, 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)
        Prefs.setShowSeparators(this, findViewById<SwitchCompat>(R.id.swSeparators).isChecked)
        Logx.d("外观配置已保存：图标×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)} 文字×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)}（反色按背景自动）")
    }

    private fun saveEntries() {
        Prefs.putString(this, "settings_pkg", findViewById<EditText>(R.id.etSettingsPkg).text.toString().trim())
        Prefs.putString(this, "settings_cls", findViewById<EditText>(R.id.etSettingsCls).text.toString().trim())
        Prefs.putString(this, "settings_action", findViewById<EditText>(R.id.etSettingsAction).text.toString().trim())
        Prefs.putString(this, "seat_pkg", findViewById<EditText>(R.id.etSeatPkg).text.toString().trim())
        Prefs.putString(this, "seat_cls", findViewById<EditText>(R.id.etSeatCls).text.toString().trim())
        Prefs.putString(this, "seat_action", findViewById<EditText>(R.id.etSeatAction).text.toString().trim())
        Prefs.putString(this, "airdir_pkg", findViewById<EditText>(R.id.etAirPkg).text.toString().trim())
        Prefs.putString(this, "airdir_cls", findViewById<EditText>(R.id.etAirCls).text.toString().trim())
        Prefs.putString(this, "music_pkg", findViewById<EditText>(R.id.etMusicPkg).text.toString().trim())
        Prefs.putString(this, "recents_pkg", findViewById<EditText>(R.id.etRecentsPkg).text.toString().trim())
        Logx.d("入口：设置=${Prefs.settingsPkg(this)}/${Prefs.settingsCls(this)}")
        Logx.d("入口：空调=${Prefs.airDirPkg(this)}/${Prefs.airDirCls(this)}")
        Logx.d("入口：座椅=${Prefs.seatPkg(this)}/${Prefs.seatCls(this)}")
        Logx.d("入口：音乐兜底=${Prefs.musicPkg(this)}")
    }

    // =====================================================================

    private fun startService() {
        Logx.d("启动 Dock 服务")
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_START)
            )
        }.onFailure { Logx.e("启动失败", it) }
        main.postDelayed({ updateStatus() }, 800)
    }

    private fun stopServiceNow() {
        runCatching {
            startService(
                Intent(this, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_STOP)
            )
        }
        main.postDelayed({ updateStatus() }, 500)
    }

    private fun reloadService() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_RELOAD).setPackage(packageName))
        }
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = MediaBridge.hasPermission(this)
        val st = VehicleController.state
        val running = DockOverlayService.alive
        val mounted = DockOverlayService.attached

        tvOverlayPerm.text = if (overlayOk) "已授权" else "未授权"
        tvOverlayPerm.setTextColor(permColor(overlayOk))
        tvNotifPerm.text = if (notifOk) "已授权" else "未授权"
        tvNotifPerm.setTextColor(permColor(notifOk))
        tvNotifHint.visibility = if (notifOk) View.GONE else View.VISIBLE

        tvStatus.text = buildString {
            append("服务：").append(
                when {
                    running && mounted -> "运行中，悬浮窗已显示"
                    running && DockOverlayService.hiddenByApp ->
                        "运行中，悬浮窗已隐藏（当前应用在「隐藏 Dock 名单」里，属正常）"
                    running -> "运行中，但悬浮窗没挂上"
                    else -> "未运行"
                }
            )
            append("\n车控：").append(if (st.connected) "已连接" else "未连接")
            append("    模式：").append(if (Prefs.simulate(this@MainActivity)) "模拟" else "真实")
            if (DockOverlayService.lastError.isNotBlank()) {
                append("\n").append(DockOverlayService.lastError)
            }
        }
        tvBackend.text = "后端：${VehicleBridge.backend.label}" +
                "　温度通道：${VehicleBridge.floatChannelName}" +
                if (Prefs.simulate(this)) "（模拟模式已开启，忽略真实通道）" else ""
    }

    private fun permColor(ok: Boolean): Int =
        if (ok) 0xFF3DDC97.toInt() else ContextCompat.getColor(this, R.color.warn)

    // =====================================================================
    // 车控通道表
    // =====================================================================

    private fun buildChannelRows() {
        llChannels.removeAllViews()
        rows.clear()

        for (ch in VehicleChannels.ALL) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }

            val label = TextView(this).apply {
                text = "${ch.label}　${ch.note}"
                setTextColor(0xFF9AA1AE.toInt())
                textSize = 11f
            }
            box.addView(label)

            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val etFunc = field("0x10020100", 1.3f)
            val etZone = field("8", 0.7f)
            etZone.inputType = InputType.TYPE_CLASS_NUMBER
            row1.addView(etFunc, lp(0f, 1.3f))
            row1.addView(etZone, lp(dp(6).toFloat(), 0.7f))
            box.addView(row1)

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val tvResult = TextView(this).apply {
                text = "当前值：—"
                setTextColor(0xFFEDEFF3.toInt())
                textSize = 12f
                // 保存按钮（统一保存）
                // ── [S31] 保存全部通道 ───────────────────────
                val btnSave = smallBtn("保存全部通道") { saveAllChannels() }
                llChannels.addView(btnSave, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })

                // ★ v1.4.52：暴力扫描整片 ID 空间，找出「哪些 functionId 真的有值」。
                //   内外循环 0x10030100 在本车读回恒为 0xff（HAL 没暴露），写也全失败，
                //   强烈提示 functionId 本身就不对（oneOS_Dock 那张表是别的车型/版本）。
                //   与其猜，不如扫一遍：读通道是安全操作（读不到只返回 0xff 哨兵，不会崩）。
                // ── [S32] ★ 扫描车控通道（找出内外循环真实 ID） ────────
                val btnScan = smallBtn("★ 扫描车控通道（找出内外循环真实 ID）") { btn ->
                    btn.isEnabled = false
                    btn.alpha = 0.45f
                    tvStateRef?.text = "正在扫描车控通道（约 2560 次读 × 3 个 zone，10~40 秒）…"
                    Thread {
                        val r = runCatching { VehicleController.scanChannels(this@MainActivity) }
                            .getOrElse { "扫描失败：${it.javaClass.simpleName}: ${it.message}" }
                        Logx.d(r)
                        main.post {
                            btn.isEnabled = true
                            btn.alpha = 1f
                            tvStateRef?.text = r
                            LogUploader.upload(this@MainActivity, "channel_scan", r, "车控通道扫描")
                        }
                    }.apply { isDaemon = true }.start()
                }
                llChannels.addView(btnScan, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }
    }

    private fun saveAllChannels() {
        rows.forEach { r ->
            val f = parseNum(r.etFunc.text.toString())
            val z = r.etZone.text.toString().trim().toIntOrNull() ?: r.channel.defZone
            VehicleChannels.save(this, r.channel.key, f, z)
        }
        Logx.d("通道配置已保存：${rows.size} 条")
        Thread {
            runCatching { VehicleBridge.reset() }
            runCatching { VehicleController.init(this) }
                .onFailure { Logx.e("重连车控失败", it) }
        }.start()
    }

    private fun readChannel(ch: VehicleChannel, etFunc: EditText, etZone: EditText, out: TextView) {
        val f = parseNum(etFunc.text.toString())
        val z = etZone.text.toString().trim().toIntOrNull() ?: ch.defZone
        doRead(ch, f, z, out)
    }

    private fun writeChannel(
        ch: VehicleChannel, etFunc: EditText, etZone: EditText, etVal: EditText, out: TextView
    ) {
        val f = parseNum(etFunc.text.toString())
        val z = etZone.text.toString().trim().toIntOrNull() ?: ch.defZone
        val idx = etVal.text.toString().trim().toIntOrNull() ?: 1
        val raw = (f and 0xFFFFFF00.toInt()) or (idx and 0xFF)
        Thread {
            val ok = VehicleBridge.writeInt(f, z, raw)
            main.post {
                out.text = "写入：0x${raw.toString(16)} → ${if (ok) "成功" else "失败"}"
            }
        }.start()
    }

    private fun doRead(ch: VehicleChannel, f: Int, z: Int, out: TextView) {
        Thread {
            if (!VehicleBridge.isReady) VehicleBridge.init(this)
            val v = VehicleBridge.readInt(f, z)
            main.post {
                out.text = if (v == null) {
                    "当前值：读取失败（0x${f.toString(16)} zone=$z）"
                } else {
                    "当前值：$v (0x${v.toString(16)})"
                }
            }
        }.start()
    }

    private fun readAllChannels() {
        Logx.d("开始读取全部通道…")
        rows.forEach { r ->
            Thread {
                if (!VehicleBridge.isReady) VehicleBridge.init(this)
                val f = parseNum(r.etFunc.text.toString())
                val z = r.etZone.text.toString().trim().toIntOrNull() ?: r.channel.defZone
                val v = VehicleBridge.readInt(f, z)
                main.post {
                    r.tvResult.text = if (v == null) "当前值：读取失败"
                    else "当前值：$v (0x${v.toString(16)})"
                    Logx.d("${r.channel.label}  func=0x${f.toString(16)} zone=$z → ${v ?: "null"}")
                }
            }.start()
        }
    }

    // =====================================================================

    private fun field(hint: String, weight: Float): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(0xFFEDEFF3.toInt())
        setHintTextColor(0xFF9AA1AE.toInt())
        textSize = 12f
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
        inputType = InputType.TYPE_CLASS_TEXT
    }

    /**
     * 弹窗底部让出 Dock 高度：Dock 是悬浮窗，会盖住对话框底部的按钮。
     * show() 之后给 decorView 加底部 padding（dock 高 + 距边 + 余量），弹窗内容整体上移。
     */
    private fun AlertDialog.Builder.showDockSafe(): AlertDialog {
        val d = show()
        return d.applyDockSafe()
    }

    /** 已 show 的对话框直接套用 Dock 让位 padding */
    private fun AlertDialog.applyDockSafe(): AlertDialog {
        if (Prefs.dockAtBottom(this@MainActivity)) {
            window?.decorView?.setPadding(
                0, 0, 0,
                Prefs.dockHeightPx(this@MainActivity) + Prefs.dockMarginPx(this@MainActivity) + dp(12)
            )
        }
        return this
    }

    /** 小按钮；onClick 参数是按钮自身，便于做「禁用防连点」等反馈 */
    private fun smallBtn(text: String, onClick: (Button) -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        // ★ 加大点击热区：之前没有 minHeight、纵向 padding 为 0，手指点偏就感觉“不灵敏”
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
        setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener { onClick(this) }
    }

    /**
     * ★ v3.13.16：**背景形态四选一互斥行**（纯色 / 透明 / 渐变 / 毛玻璃）。
     * 选中项用强调色描边 + 强调色文字标示；点任意一项立即写设置并重建 Dock。
     */
    private fun addBgModeRow(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "背景形态"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 13f
            typeface = AppFont.bold(this@MainActivity)
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = LinkedHashMap<BgMode, Button>()
        BgMode.values().forEach { m ->
            val b = smallBtn(m.label) {
                Prefs.setBgMode(this, m)
                refreshBgModeButtons(buttons, m)
                refreshGradientPreview()
                reloadService()
            }
            b.textSize = 11.5f
            buttons[m] = b
            row.addView(b, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(4) })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        refreshBgModeButtons(buttons, Prefs.bgMode(this))
    }

    /** 刷新背景形态按钮的选中态（互斥）+ 按形态显隐相关控件 */
    private fun refreshBgModeButtons(buttons: Map<BgMode, Button>, selected: BgMode) {
        for ((mode, b) in buttons) {
            val on = mode == selected
            b.setTextColor(if (on) 0xFF4EA1FF.toInt() else 0xFFFFFFFF.toInt())
            b.alpha = if (on) 1f else 0.62f
            b.background = if (on) {
                // 选中：透明底 + 强调色描边（Button 没有 setStroke，用 GradientDrawable）
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x1A4EA1FF)
                    setStroke(dp(2), 0xFF4EA1FF.toInt())
                }
            } else {
                ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
            }
        }
        // ★ v3.13.16：两个「底色透明度」滑杆 + 预览条**只属于纯色 / 渐变**；
        //   透明形态没有任何底色参数，毛玻璃的色调是固定淡色 → 都隐藏。
        val showColorControls = selected == BgMode.SOLID || selected == BgMode.GRADIENT
        for (row in bgColorControlRows) {
            row.visibility = if (showColorControls) View.VISIBLE else View.GONE
        }
        gradientPreview?.visibility = if (showColorControls) View.VISIBLE else View.GONE
        blurRadiusRow?.visibility = if (selected == BgMode.BLUR) View.VISIBLE else View.GONE
    }

    private fun lp(marginStartPx: Float, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = marginStartPx.toInt()
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hex(v: Int): String = String.format("0x%08X", v)

    private fun parseNum(s: String): Int {
        val t = s.trim()
        return try {
            when {
                t.isEmpty() -> 0
                t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
                else -> t.toLong().toInt()
            }
        } catch (_: Throwable) { 0 }
    }

    /**
     * 日志区增量追加。
     *
     * 旧实现每来一行就把整个（最多 60KB）字符串重设给 TextView 并 fullScroll，
     * 高频日志（车控刷新 2.5s 一条 + 广播嗅探）时主界面会明显卡顿。
     * 现在：平时只 append 增量；超限时整体裁剪一次；滚动用单次 post 合并。
     */
    private fun appendLog(line: String) {
        logBuffer.append(line).append('\n')
        logLineCount++
        if (logLineCount > MAX_LOG_LINES || logBuffer.length > 60_000) {
            val kept = logBuffer.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(MAX_LOG_LINES)
            logBuffer = StringBuilder(kept.joinToString("\n", postfix = "\n"))
            logLineCount = kept.size
            tvLog.text = logBuffer
        } else {
            if (tvLog.text.isEmpty()) tvLog.text = line else tvLog.append("\n$line")
        }
        if (!scrollPending) {
            scrollPending = true
            main.post {
                scrollPending = false
                svLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
