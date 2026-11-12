package com.car.dockbar.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import android.provider.Settings
import android.content.res.Configuration
import android.content.ContextWrapper
import android.view.LayoutInflater

/** 单个普通设置页面的生命周期管理基类。 */
abstract class SettingsPage(protected val activity: MainActivity, val key: String) : ContextWrapper(activity) {
    abstract val root: View
    protected val main = Handler(Looper.getMainLooper())
    protected val layoutInflater: LayoutInflater get() = activity.layoutInflater
    protected val currentFocus: View? get() = activity.currentFocus
    protected fun <T : View> findViewById(id: Int): T = activity.findViewById(id)
    protected fun <T : View> findOptionalViewById(id: Int): T? = activity.findViewById(id)
    open fun create() {}
    open fun buildLayout() {}
    open fun onShown() {}
    open fun onResume() {}
    open fun onConfigurationChanged(newConfig: Configuration) {}
    protected open fun onServiceStateChanged() {}
    open fun onDestroy() { main.removeCallbacksAndMessages(null) }
    private var lastToastMs = 0L

    protected fun seek(after: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = after()
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun hex(v: Int): String = String.format("0x%08X", v)

    protected fun parseNum(s: String): Int {
        val t = s.trim()
        return try {
            when {
                t.isEmpty() -> 0
                t.startsWith("0x", true) -> t.substring(2).toLong(16).toInt()
                else -> t.toLong().toInt()
            }
        } catch (_: Throwable) { 0 }
    }

    protected fun addSwitchRow(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        reloadDock: Boolean = true,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 16f
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

    protected fun addSeekRow(
        parent: LinearLayout,
        label: String,
        progress: Int,
        max: Int,
        format: (Int) -> String,
        reloadDock: Boolean = true,
        onChange: (Int) -> Unit,
        onProgress: ((Int) -> Unit)? = null,
        separateValue: Boolean = false
    ): LinearLayout {
        // ★ v3.13.16：把「标签 + 滑杆」包进一个容器并返回 —— 便于按背景形态整体显隐
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val tv = TextView(this).apply {
            text = if (separateValue) format(progress) else "$label：${format(progress)}"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 15f
            setPadding(0, dp(6), 0, 0)
        }
        if (separateValue) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsPage).apply {
                    text = label
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_text))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                tv.setTextColor(ContextCompat.getColor(this@SettingsPage, R.color.settings_accent))
                addView(tv)
            })
        } else {
            box.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val sb = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            minimumHeight = dp(44)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = if (separateValue) format(p) else "$label：${format(p)}"
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

    protected fun addEditRow(
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
            background = ContextCompat.getDrawable(this@SettingsPage, R.drawable.bg_field)
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

    protected fun addColorPresetRow(
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
                    setStroke(dp(2), if (c == current)
                        ContextCompat.getColor(this@SettingsPage, R.color.settings_accent) else 0x33FFFFFF)
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

    protected fun reloadService() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_RELOAD).setPackage(packageName))
        }
    }
    protected fun stopServiceNow() {
        runCatching {
            startService(Intent(this, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_STOP))
        }
        main.postDelayed({ onServiceStateChanged() }, 500)
    }

    protected fun sendLyricUpdate() {
        runCatching {
            sendBroadcast(Intent(LyricOverlayService.ACTION_UPDATE).setPackage(packageName))
        }
    }

    protected fun toastOnce(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastMs < 4000) return
        lastToastMs = now
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    protected fun ensureServiceStarted() {
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

    protected fun startService() {
        Logx.d("启动 Dock 服务")
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, DockOverlayService::class.java).setAction(DockOverlayService.ACTION_START)
            )
        }.onFailure { Logx.e("启动失败", it) }
        main.postDelayed({ onServiceStateChanged() }, 800)
    }

    protected fun stopLyricNow() {
        LyricWindows.stop(this)
        main.postDelayed({ onServiceStateChanged() }, 500)
    }

    protected fun ensureLyricStarted(): Boolean {
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
        main.postDelayed({ onServiceStateChanged() }, 800)
        return true
    }
}
