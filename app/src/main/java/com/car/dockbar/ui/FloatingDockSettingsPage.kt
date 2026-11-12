package com.car.dockbar.ui

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleBridge
import com.car.dockbar.vehicle.VehicleController
import android.provider.Settings

class FloatingDockSettingsPage(activity: MainActivity) : SettingsPage(activity, MainActivity.SECTION_OVERLAY) {
    override val root: LinearLayout get() = findViewById(R.id.cardOverlay)
    override fun buildLayout() { FloatingDockSettingsLayout(activity).rebuild() }
    override fun onServiceStateChanged() { updateStatus() }
    private lateinit var tvStatus: TextView
    private lateinit var tvOverlayPerm: TextView
    private lateinit var tvNotifPerm: TextView
    private lateinit var tvNotifHint: TextView
    private lateinit var tvBackend: TextView
    private var notifWasOk = false
    override fun create() {
        tvStatus = findViewById(R.id.tvStatus)
        tvOverlayPerm = findViewById(R.id.tvOverlayPerm)
        tvNotifPerm = findViewById(R.id.tvNotifPerm)
        tvNotifHint = findViewById(R.id.tvNotifHint)
        tvBackend = findViewById(R.id.tvBackend)
        findViewById<SwitchCompat>(R.id.swEnabled).isChecked = Prefs.overlayEnabled(this)
        findViewById<SwitchCompat>(R.id.swSimulate).isChecked = Prefs.simulate(this)
        findViewById<EditText>(R.id.etWidth).setText(Prefs.dockWidthPx(this).toString())
        findViewById<EditText>(R.id.etHeight).setText(Prefs.dockHeightPx(this).toString())
        findViewById<EditText>(R.id.etMargin).setText(Prefs.dockMarginPx(this).toString())
        findViewById<EditText>(R.id.etScreenH).setText(Prefs.screenHeightPx(this).toString())

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

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener { IntentLauncher.requestOverlayPermission(this) }
        findViewById<Button>(R.id.btnGrantNotif).setOnClickListener { IntentLauncher.requestNotificationAccess(this) }
        findViewById<Button>(R.id.btnGrantAccessibilityDock).setOnClickListener { openAccessibilitySettings() }
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

        Thread {
            runCatching { VehicleController.init(this) }
                .onFailure { Logx.e("车控初始化异常", it) }
            runCatching { MediaBridge.refresh(this) }
            main.post { updateStatus() }
        }.start()
        updateStatus()
    }
    override fun onResume() {
        ensureServiceStarted()
        val notifOk = MediaBridge.hasPermission(this)
        if (notifOk && !notifWasOk) {
            Logx.d("检测到通知使用权刚被授予 → 立即重绑媒体会话")
            runCatching { MediaBridge.rebind(this) }
        }
        notifWasOk = notifOk
        updateStatus()
        main.postDelayed({ updateStatus(); ensureServiceStarted() }, 1500)
    }
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
    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = MediaBridge.hasPermission(this)
        val st = VehicleController.state
        val running = DockOverlayService.alive
        val mounted = DockOverlayService.attached
        findOptionalViewById<TextView>(R.id.settingsRuntimeBadge)?.apply {
            text = if (running) "●  Dock 正在运行" else "●  Dock 未运行"
            setTextColor(ContextCompat.getColor(this@FloatingDockSettingsPage,
                if (running) R.color.settings_service_running else R.color.warn))
        }
        findOptionalViewById<TextView>(R.id.settingsNavStatus)?.apply {
            text = if (running) "●  Dock 服务已就绪" else "●  Dock 未运行"
            setTextColor(ContextCompat.getColor(this@FloatingDockSettingsPage,
                if (running) R.color.settings_service_running else R.color.warn))
        }
        findViewById<View>(R.id.btnGrantOverlay).visibility = View.VISIBLE
        findViewById<View>(R.id.btnGrantNotif).visibility = View.VISIBLE

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
            append("    模式：").append(if (Prefs.simulate(this@FloatingDockSettingsPage)) "模拟" else "真实")
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

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { Logx.e("打开无障碍设置失败", it) }
    }
}
