package com.car.dockbar.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.AppDrawerApp
import com.car.dockbar.model.AppDrawerApps
import com.car.dockbar.model.DockEntries
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 负责图标卡片、启动目标及快捷应用交互，不处理其他设置页面。 */
class IconsSettingsPage(activity: MainActivity) : SettingsPage(activity, MainActivity.SECTION_ICONS) {
    companion object {
        // 隐藏 Dock 应用页也需要加载图标；后续引入共享的已安装应用目录管理类时，
        // 可将这些辅助方法迁入其中，无需宿主提供回调。
        private val iconCache = HashMap<String, Drawable?>()
        private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }

    override val root = LinearLayout(this).apply {
        id = R.id.settingsDockEntriesPage
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }
    private var created = false
    private var destroyed = false
    private var tvAppDrawerSummary: TextView? = null
    private var btnAppDrawerSelection: Button? = null
    private var quickAppsGrid: LinearLayout? = null
    private var targets: LinearLayout? = null
    private val dialogs = mutableSetOf<AlertDialog>()

    private data class AppDrawerCandidate(val app: AppDrawerApp, val isSystem: Boolean)

    override fun create() {
        if (created) return
        created = true
        root.addView(TextView(this).apply {
            text = "Dock图标设置"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 16f
            typeface = AppFont.bold(this@IconsSettingsPage)
        })

        root.addView(Button(this).apply {
            id = R.id.settingsManageDockEntries
            text = "管理 Dock 图标（增删 / 排序）"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_on_accent))
            background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_button_primary)
            setOnClickListener {
                startActivity(Intent(this@IconsSettingsPage, DockEntriesActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        tvAppDrawerSummary = TextView(this).apply {
            id = R.id.settingsQuickAppsSummary
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tvAppDrawerSummary)
        btnAppDrawerSelection = Button(this).apply {
            id = R.id.settingsManageQuickApps
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_on_accent))
            background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_button_primary)
            setOnClickListener { chooseAppDrawerApps() }
        }
        root.addView(btnAppDrawerSelection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        quickAppsGrid = LinearLayout(this).apply {
            id = R.id.settingsQuickAppsGrid
            orientation = LinearLayout.VERTICAL
        }
        root.addView(quickAppsGrid)
        refreshAppDrawerSelectionLabel()

        // --- 空调状态弹窗（自动消失） ---
        // ── [S00] 空调状态弹窗（调空调时显示） ───────────────
        var popupDurationView: LinearLayout? = null
        fun updatePopupDurationEnabled(enabled: Boolean) {
            popupDurationView?.apply {
                alpha = if (enabled) 1f else .4f
                for (i in 0 until childCount) {
                    (getChildAt(i) as? SeekBar)?.isEnabled = enabled
                }
            }
        }
        addSwitchRow(
            root, "空调状态弹窗",
            Prefs.popupEnabled(this)
        ) {
            Prefs.setPopupEnabled(this, it)
            updatePopupDurationEnabled(it)
        }
        root.getChildAt(root.childCount - 1).id = R.id.settingsPopupSwitchRow
        // ★ 用户要求：精确到秒（1 秒步进，不再 0.1 秒）
        // ── [S01] 弹窗自动消失 ───────────────────────
        popupDurationView = addSeekRow(
            root, "弹窗显示时长",
            (Prefs.popupAutoDismissMs(this) / 1000L).toInt(), 20,
            { "${it} 秒" },
            onChange = { Prefs.setPopupAutoDismissMs(this, it * 1000L) },
            separateValue = true
        )
        popupDurationView?.id = R.id.settingsPopupDurationRow
        updatePopupDurationEnabled(Prefs.popupEnabled(this))

        // --- 淡入淡出（时长滑块用于调节显示/隐藏动画快慢）---
        // 0.1 秒粒度（10ms 步进太细不好调）
        // ── [S02] 淡入淡出时长 ───────────────────────
        addSeekRow(
            root, "淡入淡出时长",
            (Prefs.fadeDurationMs(this) / 100L).toInt(), 10,
            { "%.1f 秒".format(it / 10f) },
            onChange = { Prefs.setFadeDurationMs(this, it * 100L) },
            separateValue = true
        )
        root.getChildAt(root.childCount - 1).id = R.id.settingsFadeDurationRow
        // --- ★ v1.4.82：AUTO 风道档位（这台车有 5 档 AUTO 风道） ---
        // ── [S05] AUTO 风道档位 ────────────────────
        addSeekRow(
            root, "当前档位",
            Prefs.fanAutoLevel(this), 5,
            { "%d 档".format(it) },
            onChange = {
                // ★ v1.4.84：滑条必须**直接写车机**（之前只存本地 Pref → 车机没反应）
                com.car.dockbar.vehicle.VehicleController.setFanAutoLevel(this, it)
            },
            separateValue = true
        )
        root.getChildAt(root.childCount - 1).id = R.id.settingsAutoFanRow
        // 原样复用已有的静态控件，禁止在此再次加载 activity_main 布局。
        // 旧版启动目标卡片在 XML 中隐藏，这里有意保留其可见性状态。
        targets = activity.findViewById<EditText>(R.id.etSettingsPkg).parent as LinearLayout
        targets?.let { card ->
            (card.parent as? ViewGroup)?.removeView(card)
            root.addView(card)
        }
        loadTargets()
        root.findViewById<Button>(R.id.btnApplyEntries).setOnClickListener {
            saveTargets()
            Logx.d("入口配置已保存")
        }
    }

    override fun buildLayout() { IconsSettingsLayout(activity).rebuild() }

    override fun onResume() {
        if (!created || destroyed) return
        refreshAppDrawerSelectionLabel()
        root.findViewById<TextView>(R.id.settingsEntryCount)?.text =
            "已添加 ${DockEntries.load(this).size} 项"
    }

    /** 响应 EXTRA_PICK_APP_DRAWER 的入口，无需在宿主中暴露选择器回调。 */
    fun requestAppDrawerPicker() {
        if (!created) create()
        main.postDelayed({ if (!destroyed) chooseAppDrawerApps() }, 300)
    }

    private fun loadTargets() {
        findViewById<EditText>(R.id.etSettingsPkg).setText(Prefs.settingsPkg(this))
        findViewById<EditText>(R.id.etSettingsCls).setText(Prefs.settingsCls(this))
        findViewById<EditText>(R.id.etSettingsAction).setText(Prefs.settingsAction(this))
        findViewById<EditText>(R.id.etSeatPkg).setText(Prefs.seatPkg(this))
        findViewById<EditText>(R.id.etSeatCls).setText(Prefs.seatCls(this))
        findViewById<EditText>(R.id.etSeatAction).setText(Prefs.seatAction(this))
        findViewById<EditText>(R.id.etAirPkg).setText(Prefs.airDirPkg(this))
        findViewById<EditText>(R.id.etAirCls).setText(Prefs.airDirCls(this))
        // ── [S34] 最近任务包名（D01 长按打开）────────
        findViewById<EditText>(R.id.etRecentsPkg).setText(Prefs.recentsPkg(this))
    }

    private fun saveTargets() {
        Prefs.putString(this, "settings_pkg", findViewById<EditText>(R.id.etSettingsPkg).text.toString().trim())
        Prefs.putString(this, "settings_cls", findViewById<EditText>(R.id.etSettingsCls).text.toString().trim())
        Prefs.putString(this, "settings_action", findViewById<EditText>(R.id.etSettingsAction).text.toString().trim())
        Prefs.putString(this, "seat_pkg", findViewById<EditText>(R.id.etSeatPkg).text.toString().trim())
        Prefs.putString(this, "seat_cls", findViewById<EditText>(R.id.etSeatCls).text.toString().trim())
        Prefs.putString(this, "seat_action", findViewById<EditText>(R.id.etSeatAction).text.toString().trim())
        Prefs.putString(this, "airdir_pkg", findViewById<EditText>(R.id.etAirPkg).text.toString().trim())
        Prefs.putString(this, "airdir_cls", findViewById<EditText>(R.id.etAirCls).text.toString().trim())
        Prefs.putString(this, "recents_pkg", findViewById<EditText>(R.id.etRecentsPkg).text.toString().trim())
        Logx.d("入口：设置=${Prefs.settingsPkg(this)}/${Prefs.settingsCls(this)}")
        Logx.d("入口：空调=${Prefs.airDirPkg(this)}/${Prefs.airDirCls(this)}")
        Logx.d("入口：座椅=${Prefs.seatPkg(this)}/${Prefs.seatCls(this)}")
    }

    override fun onDestroy() {
        destroyed = true
        dialogs.toList().forEach { runCatching { it.dismiss() } }
        dialogs.clear()
        super.onDestroy()
    }

    private fun refreshAppDrawerSelectionLabel() {
        val count = AppDrawerApps.load(this).size
        tvAppDrawerSummary?.text = "已选择 $count / ${AppDrawerApps.MAX_APPS}"
        btnAppDrawerSelection?.text = "管理应用"
        renderQuickAppsGrid()
    }

    private fun renderQuickAppsGrid() {
        val grid = quickAppsGrid ?: return
        grid.removeAllViews()
        val apps = AppDrawerApps.loadSlots(this)
        val slotViews = mutableListOf<View>()
        var dragFrom = -1
        var dragTo = -1
        fun highlightDrag() {
            slotViews.forEachIndexed { index, view ->
                view.alpha = when {
                    dragFrom < 0 -> 1f
                    index == dragFrom -> .9f
                    index == dragTo -> 1f
                    else -> .5f
                }
                view.elevation = if (index == dragFrom) dp(8).toFloat() else 0f
            }
        }
        fun finishDrag(commit: Boolean) {
            val from = dragFrom
            val to = dragTo
            dragFrom = -1
            dragTo = -1
            grid.parent?.requestDisallowInterceptTouchEvent(false)
            if (commit && from in 0 until AppDrawerApps.MAX_APPS &&
                to in 0 until AppDrawerApps.MAX_APPS && from != to) {
                // 只交换两个固定槽位，空槽位也参与交换。
                val slots = MutableList<AppDrawerApp?>(AppDrawerApps.MAX_APPS) { apps.getOrNull(it) }
                val old = slots[from]
                slots[from] = slots[to]
                slots[to] = old
                AppDrawerApps.saveSlots(this, slots)
                Logx.d("设置页快捷应用：槽位 $from ↔ $to，已保存")
                refreshAppDrawerSelectionLabel()
            } else highlightDrag()
        }
        repeat(2) { rowIndex ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = dp(14) })
            repeat(3) { colIndex ->
                val slot = rowIndex * 3 + colIndex
                val app = apps.getOrNull(slot)
                val tile = FrameLayout(this).apply {
                    background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_field)
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (app == null) "管理快捷应用 ${slot + 1}" else "替换快捷应用 ${app.label}"
                    setOnClickListener {
                        if (app == null) chooseAppDrawerApps() else chooseAppDrawerApps(slot)
                    }
                    if (app != null) setOnLongClickListener {
                        if (dragFrom >= 0) return@setOnLongClickListener true
                        dragFrom = slot
                        dragTo = slot
                        parent?.requestDisallowInterceptTouchEvent(true)
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        highlightDrag()
                        true
                    }
                    setOnTouchListener { _, event ->
                        if (dragFrom != slot) return@setOnTouchListener false
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> {
                                val location = IntArray(2)
                                val target = slotViews.indexOfFirst { view ->
                                    view.getLocationOnScreen(location)
                                    event.rawX >= location[0] && event.rawX <= location[0] + view.width &&
                                        event.rawY >= location[1] && event.rawY <= location[1] + view.height
                                }
                                if (target >= 0 && target != dragTo) {
                                    dragTo = target
                                    highlightDrag()
                                }
                                true
                            }
                            MotionEvent.ACTION_UP -> { finishDrag(true); true }
                            MotionEvent.ACTION_CANCEL -> { finishDrag(false); true }
                            else -> false
                        }
                    }
                }
                slotViews.add(tile)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                }
                tile.addView(body, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val icon = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (app != null) {
                        setImageResource(R.drawable.ic_car)
                        loadAppIconAsync(app.packageName, this)
                    } else {
                        setImageDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
                    }
                }
                if (app != null) body.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                else body.addView(TextView(this).apply {
                    text = "+"
                    textSize = 32f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.settings_secondary))
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                body.addView(TextView(this).apply {
                    text = app?.label ?: "管理应用"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@IconsSettingsPage,
                        if (app == null) R.color.settings_secondary else R.color.settings_text))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
                if (app != null) tile.addView(TextView(this).apply {
                    text = "×"
                    textSize = 35f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFE5484D.toInt())
                    }
                    contentDescription = "清除快捷应用 ${app.label}"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val current = AppDrawerApps.loadSlots(this@IconsSettingsPage).toMutableList()
                        if (current[slot]?.packageName == app.packageName) {
                            current[slot] = null
                            AppDrawerApps.saveSlots(this@IconsSettingsPage, current)
                        }
                        refreshAppDrawerSelectionLabel()
                    }
                }, FrameLayout.LayoutParams(dp(45), dp(45), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    marginEnd = dp(4)
                })
                row.addView(tile, LinearLayout.LayoutParams(0, dp(98), 1f).apply {
                    if (colIndex > 0) marginStart = dp(14)
                })
            }
        }
    }

    private fun chooseAppDrawerApps(slot: Int? = null) {
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
                if (slot == null) showAppDrawerPicker(items) else showAppDrawerSlotPicker(items, slot)
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
            setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.text_secondary))
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
                    setTextColor(ContextCompat.getColor(this@IconsSettingsPage, R.color.text_secondary))
                    setPadding(dp(4), dp(5), 0, dp(5))
                })
                return
            }
            categoryItems.forEach { candidate ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@IconsSettingsPage, R.drawable.bg_field)
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

    private fun showAppDrawerSlotPicker(items: List<AppDrawerCandidate>, slot: Int) {
        if (items.isEmpty()) {
            toastOnce("没有读到可启动的应用")
            return
        }
        val labels = items.map { "${it.app.label}\n${it.app.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (AppDrawerApps.loadSlots(this).getOrNull(slot) != null) "替换快捷应用" else "添加快捷应用")
            .setItems(labels) { _, index ->
                val chosen = items[index].app
                val apps = AppDrawerApps.loadSlots(this).toMutableList()
                val existing = apps.indexOfFirst { it?.packageName == chosen.packageName }
                if (existing >= 0 && existing != slot) apps[existing] = apps[slot]
                apps[slot] = chosen
                AppDrawerApps.saveSlots(this, apps)
                refreshAppDrawerSelectionLabel()
                Logx.d("快捷应用已更新：${apps.count { it != null }} 个应用")
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
    }

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
            typeface = AppFont.bold(this@IconsSettingsPage)
            setPadding(dp(4), dp(2), dp(4), dp(6))
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

    private fun AlertDialog.Builder.showDockSafe(): AlertDialog {
        val d = show()
        return d.applyDockSafe()
    }

    /** 已 show 的对话框直接套用 Dock 让位 padding */
    private fun AlertDialog.applyDockSafe(): AlertDialog {
        if (Prefs.dockAtBottom(this@IconsSettingsPage)) {
            window?.decorView?.setPadding(
                0, 0, 0,
                Prefs.dockHeightPx(this@IconsSettingsPage) + Prefs.dockMarginPx(this@IconsSettingsPage) + dp(12)
            )
        }
        return this
    }
}
