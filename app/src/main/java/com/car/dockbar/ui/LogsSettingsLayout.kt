package com.car.dockbar.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R


internal class LogsSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild() {
        val page = activity.findViewById<LinearLayout>(R.id.cardLogs)
        val original = (0 until page.childCount).map { page.getChildAt(it) }
        val clear = activity.findViewById<Button>(R.id.btnClearLog)
        val log = activity.findViewById<android.widget.ScrollView>(R.id.svLog)
        val logText = activity.findViewById<TextView>(R.id.tvLog)
        val switches = original.filterIsInstance<LinearLayout>().filter { row ->
            (0 until row.childCount).any { row.getChildAt(it) is SwitchCompat }
        }
        val actionRow = activity.findViewById<LinearLayout>(R.id.settingsLogTransferActions)
        val diagnosticRow = activity.findViewById<LinearLayout>(R.id.settingsLogDiagnosticActions)
        val rowButtons = (0 until actionRow.childCount).mapNotNull { actionRow.getChildAt(it) as? Button }
        val diagnosticButtons = (0 until diagnosticRow.childCount).mapNotNull {
            diagnosticRow.getChildAt(it) as? Button
        }
        val directButtons = original.filterIsInstance<Button>()
        val buttons = rowButtons + diagnosticButtons + directButtons
        val address = activity.findViewById<EditText>(R.id.settingsLogUploadAddress)
        val addressIndex = original.indexOf(address)
        val addressLabel = original.getOrNull(addressIndex - 1) as? TextView
        val uploadHint = activity.findViewById<TextView>(R.id.settingsLogUploadHint)
        val state = activity.findViewById<TextView>(R.id.settingsLogState)
        val vehicleStatus = original.filterIsInstance<TextView>().firstOrNull { it.id == R.id.tvStatus }
        val uploadSwitch = activity.findViewById<View>(R.id.settingsLogUploadSwitch)
        val known = mutableSetOf<View>(clear, log, address, uploadHint, state, uploadSwitch)
        addressLabel?.let { known.add(it) }
        vehicleStatus?.let { known.add(it) }
        known.addAll(switches)
        known.addAll(buttons)
        actionRow?.let { known.add(it) }
        known.add(diagnosticRow)
        original.firstOrNull()?.let { known.add(it) } // 包含“清空”按钮的旧版日志标题行。
        known.add(activity.findViewById(R.id.settingsLogUploadTitle))
        (known + original).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        heading(page, "诊断与日志", "查看运行记录、管理日志上传与车控诊断")

        val overview = card(page, "运行概况")
        overview.removeViewAt(0)
        overview.setPadding(dp(24), dp(18), dp(24), dp(18))
        val metrics = row()
        overview.addView(metrics, params())
        val values = listOf("设备标识", "Native 崩溃捕获", "安全等级", "待补传崩溃").mapIndexed { index, label ->
            val metric = column().apply { if (index > 0) setPadding(dp(18), 0, 0, 0) }
            metric.addView(text(label, 13f, true))
            val value = text("", 18f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            metric.addView(value, params(10))
            metrics.addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            value
        }
        fun refreshMetrics() {
            values[0].text = com.car.dockbar.util.LogUploader.deviceId(activity)
            values[1].text = com.car.dockbar.util.NativeCrashHandler.statusText()
            val level = com.car.dockbar.util.Prefs.safeLevel(activity)
            values[2].text = "L$level · ${com.car.dockbar.App.safeLevelDesc(level)}"
            values[3].text = if (com.car.dockbar.util.LogUploader.hasPendingCrash(activity)) "有" else "无"
        }
        refreshMetrics()
        state.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshMetrics() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        val upper = row()
        page.addView(upper, params())
        val consoleCol = column()
        val uploadCol = column()
        upper.addView(consoleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        upper.addView(uploadCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(22) })
        val console = card(consoleCol, "运行日志", "保留原有实时记录与清空操作")
        val consoleHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        consoleHeader.addView(take(console.getChildAt(0)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        consoleHeader.addView(clear.apply {
            textSize = 15f
            background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
            setTextColor(color(R.color.settings_on_accent))
        }, LinearLayout.LayoutParams(dp(120), dp(44)))
        console.addView(consoleHeader, 0)
        log.setPadding(dp(14), dp(14), dp(14), dp(14))
        log.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
        logText.apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(color(R.color.settings_text))
        }
        console.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(20) })
        val upload = card(uploadCol, "日志上传")
        add(upload, uploadSwitch, 14)
        addressLabel?.let { add(upload, it, 14) }
        address.textSize = 14f
        address.minimumHeight = dp(56)
        add(upload, address, 10)
        val transfer = rowButtons.take(2)
        val transferRow = row()
        transfer.forEachIndexed { index, button ->
            transferRow.addView(take(button).apply { (this as Button).textSize = 14f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        add(upload, transferRow, 18)
        uploadHint.textSize = 13f
        add(upload, uploadHint, 18)

        val lower = row()
        page.addView(lower, params())
        val switchCol = column()
        val actionCol = column()
        lower.addView(switchCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lower.addView(actionCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(22) })
        val experiments = card(switchCol, "车控开关", "实验功能存在风险，沿用原有设置与熔断机制")
        switches.filter { it !== uploadSwitch }.forEachIndexed { index, control ->
            (control.getChildAt(0) as? TextView)?.let { label ->
                label.text = label.text.toString().replace(Regex("[（(][^）)]*[）)]"), "").trim()
            }
            if (index > 0) divider(experiments)
            add(experiments, control, 12)
        }
        val actions = card(actionCol, "诊断操作", "执行结果显示在左侧，保留原有执行与反馈逻辑")
        buttons.filter { it !in transfer }.chunked(2).forEach { pair ->
            val line = row()
            pair.forEachIndexed { index, button ->
                button.textSize = 14f
                button.minHeight = dp(56)
                button.maxLines = 3
                line.addView(take(button), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(12)
                })
            }
            add(actions, line, 18)
        }
        val result = card(page, "诊断结果", "原有上传结果、车控诊断与崩溃状态完整保留")
        state.textSize = 14f
        state.setTextColor(color(R.color.settings_secondary))
        state.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
        state.setPadding(dp(14), dp(14), dp(14), dp(14))
        add(result, state, 16)
        vehicleStatus?.let { add(result, it, 14) }
        // 保留后续新增或尚未识别的直接子控件，避免悄然丢失功能。
        original.filter { it !in known }.forEach { add(result, it, 14) }
        // 先放开关与上传区，再放结果与诊断操作，最后放通栏日志区。
        take(result)
        take(console)
        take(experiments)
        consoleCol.addView(experiments, params(22))
        switchCol.addView(result, params(22))
        page.addView(console, params(22))

        // 每对卡片按较高一侧的自然内容高度对齐，避免将控件压入固定高度。
        fun alignCards(host: LinearLayout, left: LinearLayout, right: LinearLayout) {
            take(left)
            take(right)
            host.removeAllViews()
            listOf(left, right).forEachIndexed { index, card ->
                host.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    topMargin = dp(22)
                    if (index > 0) marginStart = dp(22)
                })
            }
        }
        fun enlargeBody(view: View) {
            if (view is TextView) {
                val sp = view.textSize / view.resources.displayMetrics.scaledDensity
                if (sp < 20.5f) view.textSize = sp * 1.2f
            }
            if (view is ViewGroup) repeat(view.childCount) { enlargeBody(view.getChildAt(it)) }
        }
        enlargeBody(upload)
        enlargeBody(result)
        alignCards(upper, experiments, upload)
        alignCards(lower, result, actions)
    }
}
