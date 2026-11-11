package com.car.dockbar.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.DockEntries

/** Reuses the bound controls and their listeners; changes presentation, not settings behavior. */
internal class SettingsPageLayout(private val activity: Activity) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun take(view: View): View {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
        includeFontPadding = false
    }

    private fun heading(parent: LinearLayout, title: String, description: String) {
        parent.addView(text(title, 30f).apply {
            typeface = AppFont.bold(activity)
        }, params())
        parent.addView(text(description, 15f, true), params(12))
    }

    private fun card(parent: LinearLayout, title: String, description: String? = null): LinearLayout {
        val card = column().apply {
            background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
            setPadding(dp(26), dp(26), dp(26), dp(26))
        }
        parent.addView(card, params(22))
        card.addView(text(title, 21f).apply { typeface = AppFont.bold(activity) }, params())
        if (description != null) card.addView(text(description, 14f, true), params(12))
        return card
    }

    private fun add(parent: LinearLayout, view: View, top: Int = 16) {
        parent.addView(take(view), params(top))
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(activity).apply { setBackgroundColor(0x12FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(22)
                bottomMargin = dp(6)
            })
    }

    fun rebuild() {
        rebuildOverlay()
        rebuildAppearance()
        rebuildEntries()
        rebuildLogs()
    }

    private fun rebuildLogs() {
        val page = activity.findViewById<LinearLayout>(R.id.cardLogs)
        val original = (0 until page.childCount).map { page.getChildAt(it) }
        val clear = activity.findViewById<Button>(R.id.btnClearLog)
        val log = activity.findViewById<android.widget.ScrollView>(R.id.svLog)
        val logText = activity.findViewById<TextView>(R.id.tvLog)
        val switches = original.filterIsInstance<LinearLayout>().filter { row ->
            (0 until row.childCount).any { row.getChildAt(it) is SwitchCompat }
        }
        val switchRows = switches.associateBy { (it.getChildAt(0) as? TextView)?.text.toString() }
        val actionRow = original.filterIsInstance<LinearLayout>().firstOrNull { row ->
            (0 until row.childCount).any { (row.getChildAt(it) as? Button)?.text == "测试上传" }
        }
        val rowButtons = actionRow?.let { row -> (0 until row.childCount).mapNotNull { row.getChildAt(it) as? Button } }.orEmpty()
        val directButtons = original.filterIsInstance<Button>()
        val buttons = rowButtons + directButtons
        val address = original.filterIsInstance<EditText>().first()
        val addressIndex = original.indexOf(address)
        val addressLabel = original.getOrNull(addressIndex - 1) as? TextView
        val uploadHint = original.filterIsInstance<TextView>().first { it.text.startsWith("有网时日志自动上传") }
        val state = original.filterIsInstance<TextView>().first { it.text.startsWith("设备 ") }
        val vehicleStatus = original.filterIsInstance<TextView>().firstOrNull { it.id == R.id.tvStatus }
        val uploadSwitch = switchRows.getValue("启用日志上传")
        val known = mutableSetOf<View>(clear, log, address, uploadHint, state, uploadSwitch)
        addressLabel?.let { known.add(it) }
        vehicleStatus?.let { known.add(it) }
        known.addAll(switches)
        known.addAll(buttons)
        actionRow?.let { known.add(it) }
        original.firstOrNull()?.let { known.add(it) } // Legacy log title row containing Clear.
        original.filterIsInstance<TextView>().filter { it.text == "日志上传（远程排查）" }.forEach { known.add(it) }
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
        val transfer = buttons.filter { it.text == "测试上传" || it.text == "立即上传日志" }
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
        // Preserve any future/unknown direct controls rather than silently dropping functionality.
        original.filter { it !in known }.forEach { add(result, it, 14) }
        // Switches/uploads first, results/diagnostic actions next, full-width logs last.
        take(result)
        take(console)
        take(experiments)
        consoleCol.addView(experiments, params(22))
        switchCol.addView(result, params(22))
        page.addView(console, params(22))

        // Match each pair to its taller natural content; never squeeze controls into a fixed height.
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

    private fun rebuildSimple(id: Int, title: String, description: String, section: String) {
        val page = activity.findViewById<LinearLayout>(id)
        val children = (0 until page.childCount).map { page.getChildAt(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        heading(page, title, description)
        val body = card(page, section)
        val content = if (children.firstOrNull() is TextView) children.drop(1) else children
        content.forEach { add(body, it, 16) }
    }

    fun rebuildLyrics(root: LinearLayout, start: Int) {
        val children = (start until root.childCount).map { root.getChildAt(it) }
        children.forEach { take(it) }
        val page = column()
        root.addView(page, params())
        heading(page, "歌词悬浮窗", "管理歌词窗口、显示效果与在线歌词来源")
        var section = card(page, "窗口与运行")
        children.drop(1).forEach { view ->
            val label = (view as? TextView)?.text?.toString().orEmpty()
            val rowLabel = ((view as? LinearLayout)?.getChildAt(0) as? TextView)?.text?.toString().orEmpty()
            when {
                label.startsWith("② 外观") -> {
                    section = card(page, "显示效果", "调整滑杆即可生效")
                }
                rowLabel.startsWith("允许联网获取歌词") -> {
                    section = card(page, "歌词来源", "允许联网后可自动查找当前歌曲的歌词")
                    add(section, view)
                }
                else -> add(section, view)
            }
        }
    }

    private fun rebuildOverlay() {
        val page = activity.findViewById<LinearLayout>(R.id.cardOverlay)
        val side = activity.findViewById<LinearLayout>(R.id.colOverlaySide)
        val width = activity.findViewById<EditText>(R.id.etWidth)
        val dimensions = width.parent.parent as LinearLayout
        val enabled = activity.findViewById<SwitchCompat>(R.id.swEnabled)
        val simulate = activity.findViewById<SwitchCompat>(R.id.swSimulate)
        val status = activity.findViewById<TextView>(R.id.tvStatus)
        val apply = activity.findViewById<Button>(R.id.btnApplyWindow)
        val screenHeight = activity.findViewById<EditText>(R.id.etScreenH)
        val permissionIds = listOf(R.id.tvOverlayPerm, R.id.tvNotifPerm, R.id.tvAccPerm)
        val permissions = permissionIds.map { activity.findViewById<TextView>(it).parent as LinearLayout }
        val known = setOf<View>(enabled, simulate, status, dimensions, apply, screenHeight)
        val interaction = (0 until side.childCount).map { side.getChildAt(it) }.filter { it !in known }

        // Detach before clearing the legacy permission-left/form-right arrangement.
        (known + permissions + interaction).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(header, params())
        val title = column()
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading(title, "悬浮 Dock 栏", "设置窗口尺寸、显示行为与运行权限")
        header.addView(text("●  Dock 未运行", 14f).apply {
            id = R.id.settingsRuntimeBadge
            setTextColor(color(R.color.warn))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_nav_selected)
            setPadding(dp(20), dp(13), dp(20), dp(13))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val columns = row()
        page.addView(columns, params(4))
        val left = column()
        val right = column()
        columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.89f))
        columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(22)
        })

        val geometry = card(left, "布局与位置", "调整后点击“应用布局”保存并生效")
        dimensions.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until dimensions.childCount) {
            val field = dimensions.getChildAt(i) as? LinearLayout ?: continue
            (field.getChildAt(0) as? TextView)?.apply {
                textSize = 14f
                setTextColor(color(R.color.settings_secondary))
            }
            (field.getChildAt(1) as? EditText)?.apply {
                textSize = 28f
                minHeight = dp(60)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
            }
        }
        add(geometry, dimensions, 16)
        add(geometry, screenHeight, 0) // Retain the hidden legacy setting for saveWindow().
        geometry.addView(text("推荐尺寸 1920 × 128 px", 14f, true), params(22))
        val runtime = card(right, "运行与权限", "管理悬浮栏所需的运行权限").apply {
            minimumHeight = dp(340)
            // Stretch with the left column so the card ends at the behavior card's bottom.
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                height = 0
                weight = 1f
            }
        }
        enabled.text = "启用悬浮 Dock"
        enabled.textSize = 18f
        enabled.minHeight = dp(52)
        add(runtime, enabled)
        divider(runtime)
        // Detailed diagnostics remain available without expanding the summary card.
        val diagnostics = activity.findViewById<LinearLayout>(R.id.cardLogs)
        status.textSize = 14f
        status.setLineSpacing(dp(6).toFloat(), 1f)
        add(diagnostics, status, 14)

        val access = runtime
        permissions.forEach { permission ->
            permission.background = null
            permission.setPadding(0, 0, 0, 0)
            val label = permission.getChildAt(0) as TextView
            val value = permission.getChildAt(1) as TextView
            val permissionHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
            take(label)
            take(value)
            label.textSize = 16f
            label.setTextColor(color(R.color.settings_text))
            value.textSize = 20f
            value.typeface = AppFont.bold(activity)
            permissionHeader.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            permissionHeader.addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            permission.addView(permissionHeader, 0)
            for (i in 0 until permission.childCount) {
                (permission.getChildAt(i) as? Button)?.apply {
                    textSize = 14f
                    minHeight = dp(44)
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_permission_button)
                    setTextColor(color(R.color.settings_on_accent))
                }
            }
            add(access, permission, 18)
        }

        val actions = row().apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(actions, params(24))
        actions.addView(text("尺寸调整后，点击“应用布局”生效", 13f, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(activity).apply {
            text = "恢复默认"
            textSize = 15f
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_nav_idle)
            setTextColor(color(R.color.settings_secondary))
            setOnClickListener {
                // Only reset these fields; saving remains explicit via Apply.
                width.setText("1920")
                activity.findViewById<EditText>(R.id.etHeight).setText("128")
                activity.findViewById<EditText>(R.id.etMargin).setText("0")
            }
        }, LinearLayout.LayoutParams(dp(136), dp(54)).apply { marginEnd = dp(24) })
        actions.addView(take(apply), LinearLayout.LayoutParams(dp(303), dp(54)))

        val behavior = card(left, "显示与唤出", "调整上滑唤出与临时显示行为")
        side.removeAllViews()
        side.background = null
        side.setPadding(0, 0, 0, 0)
        interaction.forEach { add(side, it, 12) }
        add(behavior, side, 10)
        divider(behavior)
        simulate.text = "模拟模式 · 不连接车控"
        simulate.textSize = 16f
        simulate.minHeight = dp(52)
        add(behavior, simulate, 12)
    }

    private fun rebuildAppearance() {
        val page = activity.findViewById<LinearLayout>(R.id.cardAppearance)
        val separators = activity.findViewById<SwitchCompat>(R.id.swSeparators)
        val modeRow = activity.findViewById<View>(R.id.settingsBgModeRow)
        val followNight = activity.findViewById<View>(R.id.settingsFollowNightRow)
        val day = activity.findViewById<View>(R.id.settingsDayTransparencyRow)
        val night = activity.findViewById<View>(R.id.settingsNightTransparencyRow)
        val overlay = activity.findViewById<View>(R.id.settingsOverlayControls)
        val preview = activity.findViewById<View>(R.id.settingsGradientPreview)
        val scales = listOf(
            Triple("圆角半径", R.id.tvCorner, R.id.sbCorner),
            Triple("图标缩放", R.id.tvIconScale, R.id.sbIconScale),
            Triple("文字缩放", R.id.tvTextScale, R.id.sbTextScale))
        val scaleViews = scales.map { (_, valueId, seekId) ->
            activity.findViewById<TextView>(valueId) to activity.findViewById<SeekBar>(seekId)
        }
        (listOf(separators, modeRow, followNight, day, night, overlay, preview) +
            scaleViews.flatMap { listOf(it.first, it.second) }).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(header, params())
        val titles = column()
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading(titles, "外观与风格", "背景、比例与显示细节，按你的习惯调整")
        val actions = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        separators.text = "显示分隔线"
        separators.textSize = 16f
        separators.minHeight = dp(54)
        separators.switchPadding = dp(12)
        // Scale the entire control, not just the text, and reserve the transformed bounds.
        // Android dispatches touch events through the inverse child transform.
        val scaledToggle = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(
                    View.resolveSize(kotlin.math.ceil(measuredWidth * 1.2).toInt(), widthMeasureSpec),
                    View.resolveSize(kotlin.math.ceil(measuredHeight * 1.2).toInt(), heightMeasureSpec))
            }
        }
        separators.pivotX = 0f
        separators.pivotY = 0f
        separators.scaleX = 1.2f
        separators.scaleY = 1.2f
        scaledToggle.addView(separators, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(54)))
        actions.addView(scaledToggle)
        header.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })

        val modes = card(page, "背景形态", "四种形态，选择后显示对应参数")
        add(modes, modeRow, 22)

        val columns = row()
        page.addView(columns, params())
        val left = column()
        val right = column()
        columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.51f))
        columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(22)
        })
        val sizing = card(left, "尺寸与比例", "分别调整圆角、图标和文字大小")
        scales.forEachIndexed { index, (label, _, _) ->
            if (index > 0) divider(sizing)
            val (value, seek) = scaleViews[index]
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(text(label, 17f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            value.textSize = 16f
            value.gravity = Gravity.CENTER
            value.setPadding(dp(12), dp(8), dp(12), dp(8))
            value.setTextColor(color(R.color.settings_text))
            value.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            line.addView(value, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
            add(sizing, line, 20)
            seek.minimumHeight = dp(44)
            add(sizing, seek, 6)
        }
        val lighting = card(right, "日夜与透明度", "按当前背景形态显示可用选项").apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                height = 0
                weight = 1f
            }
        }
        add(lighting, followNight, 16)
        divider(lighting)
        add(lighting, overlay, 14)
        add(lighting, day, 14)
        add(lighting, night, 14)
        add(lighting, preview, 20)
    }

    private fun rebuildEntries() {
        val page = activity.findViewById<LinearLayout>(R.id.settingsDockEntriesPage)
        val manage = activity.findViewById<Button>(R.id.settingsManageDockEntries)
        val summary = activity.findViewById<TextView>(R.id.settingsQuickAppsSummary)
        val manageApps = activity.findViewById<Button>(R.id.settingsManageQuickApps)
        val grid = activity.findViewById<View>(R.id.settingsQuickAppsGrid)
        val popup = activity.findViewById<View>(R.id.settingsPopupSwitchRow)
        val duration = activity.findViewById<View>(R.id.settingsPopupDurationRow)
        val fade = activity.findViewById<View>(R.id.settingsFadeDurationRow)
        val auto = activity.findViewById<View>(R.id.settingsAutoFanRow)
        listOf(manage, summary, manageApps, grid, popup, duration, fade, auto).forEach { take(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        heading(page, "Dock 图标管理", "管理快捷入口、排列顺序与交互设置")
        val columns = BalancedDockSettingsColumns(activity)
        page.addView(columns, params())
        val left = column()
        val right = column()
        columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.51f))
        columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(22)
        })
        val entries = card(left, "添加、删除图标，长按拖动调整排列顺序")
        val entryHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        entryHeader.addView(take(entries.getChildAt(0)), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        entryHeader.addView(text("已添加 ${DockEntries.load(activity).size} 项", 13f, true).apply {
            id = R.id.settingsEntryCount
            background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        })
        entries.addView(entryHeader, 0)
        manage.text = "修改DOCK图标显示"
        manage.textSize = 17f
        manage.gravity = Gravity.CENTER
        manage.setPadding(dp(20), dp(12), dp(20), dp(12))
        manage.background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
        manage.setTextColor(color(R.color.settings_on_accent))
        add(entries, manage, 20)

        val apps = card(left, "快捷应用", "长按拖动换位，点击右上角 × 清除应用")
        val appsHeader = row().apply { gravity = Gravity.CENTER_VERTICAL }
        summary.setPadding(0, 0, 0, 0)
        summary.textSize = 14f
        summary.setTextColor(color(R.color.settings_accent))
        appsHeader.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        manageApps.textSize = 13f
        manageApps.visibility = View.GONE
        manageApps.minHeight = dp(40)
        manageApps.background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_nav_idle)
        manageApps.setTextColor(color(R.color.settings_secondary))
        appsHeader.addView(manageApps)
        add(apps, appsHeader, 10)
        add(apps, grid, 12)
        apps.addView(text("快捷应用显示在 Dock 的应用面板中", 13f, true), params(18))

        val feedback = card(right, "交互与反馈", "控制状态提示与显示动画")
        feedback.setPadding(dp(26), dp(24), dp(26), dp(22))
        (feedback.getChildAt(1).layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        popup.setPadding(0, dp(8), 0, dp(8))
        duration.setPadding(0, dp(6), 0, dp(6))
        fade.setPadding(0, dp(6), 0, dp(6))
        fun compactDivider() {
            feedback.addView(View(activity).apply { setBackgroundColor(0x12FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(16)
                    bottomMargin = dp(6)
                })
        }
        add(feedback, popup, 8)
        compactDivider()
        add(feedback, duration, 6)
        compactDivider()
        add(feedback, fade, 6)

        // Wrap the actual controls instead of forcing them into a zero-height weighted card.
        val autoCard = card(right, "AUTO 风道档位").apply {
            setPadding(dp(26), dp(20), dp(26), dp(20))
            minimumHeight = dp(164)
        }
        auto.setPadding(0, dp(4), 0, dp(4))
        add(autoCard, auto, 10)
        columns.balance(left, right, feedback, autoCard)
    }
}

/** Distribute spare vertical space without shrinking either card below its real content height. */
private class BalancedDockSettingsColumns(context: Context) : LinearLayout(context) {
    private lateinit var leftColumn: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var feedback: LinearLayout
    private lateinit var auto: LinearLayout

    init { orientation = HORIZONTAL }

    fun balance(left: LinearLayout, right: LinearLayout, feedback: LinearLayout, auto: LinearLayout) {
        leftColumn = left
        rightColumn = right
        this.feedback = feedback
        this.auto = auto
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!::leftColumn.isInitialized) return
        fun naturalHeight(card: LinearLayout): Int {
            card.measure(MeasureSpec.makeMeasureSpec(card.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            return card.measuredHeight
        }
        val feedbackHeight = naturalHeight(feedback)
        val autoHeight = naturalHeight(auto)
        val fp = feedback.layoutParams as LayoutParams
        val ap = auto.layoutParams as LayoutParams
        val margins = fp.topMargin + fp.bottomMargin + ap.topMargin + ap.bottomMargin
        val contentHeight = feedbackHeight + autoHeight + margins +
            rightColumn.paddingTop + rightColumn.paddingBottom
        val extra = (leftColumn.measuredHeight - contentHeight).coerceAtLeast(0)
        val feedbackExtra = (extra * .6f).toInt()
        fp.height = feedbackHeight + feedbackExtra
        ap.height = autoHeight + extra - feedbackExtra
        rightColumn.measure(MeasureSpec.makeMeasureSpec(rightColumn.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight + extra, MeasureSpec.EXACTLY))
        setMeasuredDimension(measuredWidth, resolveSizeAndState(
            maxOf(leftColumn.measuredHeight, rightColumn.measuredHeight), heightMeasureSpec, 0))
    }
}
