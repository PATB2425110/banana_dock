package com.car.dockbar.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 管理隐藏 Dock 应用设置页面及已安装应用目录。
 *
 * 本页面仅将 Activity 作为上下文使用，不依赖宿主回调，
 * 因而由设置页面控制器承载时，黑名单规则仍可独立使用。
 */
class ResidentSettingsPage(activity: MainActivity) : SettingsPage(activity, MainActivity.SECTION_APPS) {
    override val root: LinearLayout
        get() = findViewById(R.id.cardDockApps) ?: error("cardDockApps is not bound")

    private val iconCache = HashMap<String, Drawable?>()
    private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "resident-app-icons").apply { isDaemon = true }
    }

    @Volatile
    private var systemPkgCache: Set<String>? = null
    @Volatile
    private var residentInstalledApps: List<ResidentAppItem> = emptyList()
    @Volatile
    private var residentCatalogLoading = false
    @Volatile
    private var destroyed = false

    private var residentAppsPanel: ResidentAppsPanel? = null

    override fun create() {
        val page = root
        val host = findViewById<LinearLayout>(R.id.llDockApps)
        val addButton = findViewById<Button>(R.id.btnAddApp)
        val detectionButton = findViewById<Button>(R.id.btnGrantAccessibility)
        residentAppsPanel = ResidentAppsPanel(activity, page, host, addButton, detectionButton)
        addButton.setOnClickListener { pickInstalledAppForDockList() }
        detectionButton.setOnClickListener { openAccessibilitySettings() }
        findOptionalViewById<Button>(R.id.btnGrantAccessibilityDock)?.setOnClickListener {
            openAccessibilitySettings()
        }
        prewarmSystemPkgs()
        renderDockAppList()
    }


    override fun onShown() {
        prewarmSystemPkgs()
        renderDockAppList()
        refreshAccStatus()
    }

    override fun onResume() {
        refreshAccStatus()
        prewarmSystemPkgs(force = true)
    }

    override fun onDestroy() {
        destroyed = true
        iconExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun prewarmSystemPkgs(force: Boolean = false) {
        if (residentCatalogLoading || (!force && systemPkgCache != null)) return
        residentCatalogLoading = true
        Thread {
            val installed = runCatching { packageManager.getInstalledApplications(0) }
                .getOrDefault(emptyList())
            val nameOrder = AppDisplayNameOrder.comparator()
            residentInstalledApps = installed.distinctBy { it.packageName }.map { info ->
                ResidentAppItem(
                    info.packageName,
                    runCatching { packageManager.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedWith(Comparator { left, right ->
                val label = nameOrder.compare(left.label, right.label)
                if (label != 0) label else left.pkg.compareTo(right.pkg)
            })
            systemPkgCache = installed
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .mapTo(HashSet()) { it.packageName }
            residentCatalogLoading = false
            main.post {
                if (!destroyed) renderDockAppList()
            }
        }.apply {
            isDaemon = true
            name = "resident-app-catalog"
        }.start()
    }

    /**
     * 在不阻塞主线程的情况下加载单个已安装应用图标，缓存与线程池由本页面管理。
     * 后续若将图标加载集中管理，图标设置页应复用相同的辅助接口约定。
     */
    private fun loadAppIconAsync(pkg: String, image: ImageView) {
        synchronized(iconCache) {
            if (iconCache.containsKey(pkg)) {
                iconCache[pkg]?.let(image::setImageDrawable)
                return
            }
        }
        image.tag = pkg
        runCatching {
            iconExecutor.execute {
                val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
                synchronized(iconCache) { iconCache[pkg] = drawable }
                main.post {
                    if (!destroyed && image.tag == pkg && drawable != null) {
                        image.setImageDrawable(drawable)
                    }
                }
            }
        }.onFailure { Logx.e("隐藏 Dock 应用图标加载失败", it) }
    }

    private fun renderDockAppList() {
        val panel = residentAppsPanel ?: return
        val blacklist = Prefs.dockHideApps(this)
        panel.render(residentInstalledApps, blacklist, ::loadAppIconAsync) { pkg ->
            val updated = Prefs.dockHideApps(this).toMutableSet()
            updated.remove(pkg)
            Prefs.setDockHideApps(this, updated)
            notifyAppsChanged()
            renderDockAppList()
            Logx.d("已从 Dock 黑名单删除：$pkg → 默认显示")
        }
        refreshAccStatus()
    }

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
                pm.getInstalledApplications(0).asSequence()
                    .map { info ->
                        ResidentAppItem(
                            pkg = info.packageName,
                            label = runCatching { pm.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                            system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
            main.post {
                runCatching { loading.dismiss() }
                if (!destroyed) showInstalledAppPicker(items)
            }
        }.start()
    }

    private fun showInstalledAppPicker(items: List<ResidentAppItem>) {
        if (items.isEmpty()) {
            toastOnce("没有读到已安装应用")
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

        val choices = ResidentAppList.choices(items, Prefs.dockHideApps(this))
        lateinit var dialog: AlertDialog
        fun addRow(choice: ResidentAppChoice, body: LinearLayout) {
            val candidate = choice.app
            val alreadyAdded = choice.selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@ResidentSettingsPage, R.drawable.bg_field)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                isFocusable = true
                contentDescription = "${candidate.label}，${candidate.pkg}，${if (alreadyAdded) "已加入黑名单" else "点击加入黑名单"}"
                if (!alreadyAdded) {
                    setOnClickListener {
                        runCatching { dialog.dismiss() }
                        addDockListApp(candidate.pkg, candidate.label)
                    }
                }
            }
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_car)
                loadAppIconAsync(candidate.pkg, this)
            })
            row.addView(CheckBox(this).apply {
                text = "${candidate.label}${if (alreadyAdded) "（已添加）" else ""}\n${candidate.pkg}"
                setTextColor(0xFFEDEFF3.toInt())
                textSize = 14f
                isChecked = alreadyAdded
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            body.addView(row)
        }
        choices.forEach { addRow(it, if (it.app.system) bodySys else bodyUser) }
        listOf(bodySys to "（无系统应用）", bodyUser to "（无个人应用）").forEach { (body, tip) ->
            if (body.childCount == 0) {
                body.addView(TextView(this).apply {
                    text = tip
                    setTextColor(ContextCompat.getColor(this@ResidentSettingsPage, R.color.text_secondary))
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
            .show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.7f).toInt(), dp(434))
    }

    private fun appListColumn(title: String): Pair<LinearLayout, LinearLayout> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        column.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(this@ResidentSettingsPage)
            setPadding(dp(4), dp(2), dp(4), dp(6))
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        column.addView(body)
        return column to body
    }

    private fun addDockListApp(pkg: String, label: String) {
        val blacklist = Prefs.dockHideApps(this).toMutableSet()
        if (!blacklist.add(pkg)) return
        Prefs.setDockHideApps(this, blacklist)
        notifyAppsChanged()
        renderDockAppList()
        Logx.d("已加入 Dock 黑名单：$label（$pkg）→ 隐藏")
    }

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

    private fun refreshAccStatus() {
        AccessibilityKeep.restore(this)
        val enabled = AccessibilityKeep.isEnabled(this)
        residentAppsPanel?.setDetection(enabled)
        val current = ForegroundTracker.currentPackage
        val state = if (enabled) "已开启" else "未开启"
        val tail = if (enabled) {
            if (current != null) "（当前前台：$current）" else ""
        } else {
            " —— 名单需先开启无障碍才生效"
        }
        findOptionalViewById<TextView>(R.id.tvAccPerm)?.apply {
            text = if (enabled) "已授权" else "未授权"
            setTextColor(permColor(enabled))
        }
        findOptionalViewById<View>(R.id.btnGrantAccessibilityDock)?.visibility = View.VISIBLE
        findOptionalViewById<TextView>(R.id.tvAccHint)?.visibility = if (enabled) View.GONE else View.VISIBLE
        findOptionalViewById<TextView>(R.id.tvAccStatus)?.text = android.text.SpannableStringBuilder().apply {
            append("前台应用检测：")
            val start = length
            append(state)
            setSpan(
                android.text.style.TypefaceSpan(AppFont.bold(this@ResidentSettingsPage)),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            append(tail)
        }
    }

    private fun permColor(enabled: Boolean): Int =
        if (enabled) 0xFF3DDC97.toInt() else ContextCompat.getColor(this, R.color.warn)

    private fun AlertDialog.applyDockSafe(): AlertDialog {
        if (Prefs.dockAtBottom(this@ResidentSettingsPage)) {
            window?.decorView?.setPadding(
                0, 0, 0,
                Prefs.dockHeightPx(this@ResidentSettingsPage) +
                    Prefs.dockMarginPx(this@ResidentSettingsPage) + dp(12)
            )
        }
        return this
    }

}
