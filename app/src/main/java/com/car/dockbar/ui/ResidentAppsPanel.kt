package com.car.dockbar.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Checkable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R

internal data class ResidentAppItem(val pkg: String, val label: String, val system: Boolean)

/** Settings-only representation of the existing always-show/hide rules. */
internal class ResidentAppsPanel(
    private val activity: Activity,
    page: LinearLayout,
    private val host: LinearLayout,
    addButton: Button,
    private val detectionButton: Button
) {
    private val tiles = LinkedHashMap<String, ResidentAppTile>()
    private var items = emptyList<ResidentAppItem>()
    private val selectedCount = text("", 21f).apply { typeface = AppFont.bold(activity) }
    private val systemCount = text("", 13f, true)
    private val personalCount = text("", 13f, true)
    private val systemBody = column()
    private val personalBody = column()
    private var currentPackages = emptyList<String>()
    private var currentSystems = emptySet<String>()
    private var searchQuery = ""
    private var lastSelection = emptySet<String>()
    private var rerender: (() -> Unit)? = null

    init {
        listOf(host, addButton, detectionButton).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        page.removeAllViews()
        page.background = null
        page.setPadding(0, 0, 0, 0)
        page.addView(text("常驻应用设置", 30f).apply { typeface = AppFont.bold(activity) })
        page.addView(text("勾选应用在前台时 Dock 常驻显示，取消勾选则隐藏", 15f, true), lp(12))

        val status = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        page.addView(status, lp(22))
        val info = column()
        info.addView(selectedCount)
        info.addView(text("绿色勾选表示常驻显示", 14f, true), lp(12))
        info.addView(text("点击图标切换，修改立即生效", 13f, true), lp(8))
        status.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addButton.text = "＋ 从已安装应用中选择"
        addButton.textSize = 16f
        addButton.minWidth = 0
        addButton.minimumWidth = 0
        addButton.background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
        addButton.setTextColor(color(R.color.settings_on_accent))
        actions.addView(addButton, LinearLayout.LayoutParams(dp(260), dp(52)))
        detectionButton.textSize = 16f
        detectionButton.typeface = AppFont.bold(activity)
        detectionButton.minWidth = 0
        detectionButton.minimumWidth = 0
        detectionButton.background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
        detectionButton.contentDescription = "前台应用检测，点击管理检测权限"
        actions.addView(detectionButton, LinearLayout.LayoutParams(dp(250), dp(52)).apply { marginStart = dp(14) })
        status.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })

        page.addView(EditText(activity).apply {
            id = R.id.settingsResidentSearch
            hint = "搜索应用名称或包名（系统 / 个人）"
            textSize = 16f
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty().trim()
                    rerender?.invoke()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }, lp(16))
        host.orientation = LinearLayout.HORIZONTAL
        host.removeAllViews()
        page.addView(host, lp(22))
        fun addGroup(title: String, description: String, count: TextView, body: LinearLayout, id: Int, gap: Int) {
            val group = card()
            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(text(title, 21f).apply { typeface = AppFont.bold(activity) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            count.setTextColor(color(R.color.settings_accent))
            heading.addView(count)
            group.addView(heading)
            group.addView(text(description, 13f, true), lp(12))
            val scroll = ScrollView(activity).apply {
                this.id = id
                isFillViewport = true
                isNestedScrollingEnabled = true
                addView(body)
            }
            // Four rows of five slots are visible by default; additional rows scroll independently.
            group.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4 * CELL_HEIGHT + 3 * ROW_GAP)).apply { topMargin = dp(18) })
            host.addView(group, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(gap)
            })
        }
        addGroup("系统应用", "系统预装与车机内置应用", systemCount, systemBody, R.id.settingsResidentSystemScroll, 0)
        addGroup("个人应用", "自行安装的导航、音乐与其他应用", personalCount, personalBody, R.id.settingsResidentPersonalScroll, 22)
        selectedCount.id = R.id.settingsResidentSelectedCount
    }

    fun setDetection(enabled: Boolean) {
        detectionButton.text = if (enabled) "● 前台应用检测已开启" else "● 前台应用检测未开启"
        detectionButton.setTextColor(color(if (enabled) R.color.settings_service_running else R.color.warn))
        detectionButton.contentDescription = "${detectionButton.text}，点击管理检测权限"
    }

    fun render(
        apps: List<ResidentAppItem>, selected: Set<String>,
        bindIcon: (String, ImageView) -> Unit,
        onToggle: (String, Boolean) -> Unit
    ) {
        rerender = { render(apps, lastSelection, bindIcon, onToggle) }
        val visible = apps.filter { searchQuery.isBlank() || it.label.contains(searchQuery, true) || it.pkg.contains(searchQuery, true) }
        val packages = visible.map { it.pkg }
        val systems = visible.filter { it.system }.mapTo(HashSet()) { it.pkg }
        val rebuild = packages != currentPackages || systems != currentSystems || tiles.isEmpty()
        items = apps
        if (rebuild) {
            currentPackages = packages
            currentSystems = systems
            tiles.clear()
            fun populate(body: LinearLayout, list: List<ResidentAppItem>) {
                body.removeAllViews()
                if (list.isEmpty()) {
                    body.addView(text(if (searchQuery.isBlank()) "暂无已安装应用" else "没有匹配的应用", 14f, true).apply { gravity = Gravity.CENTER },
                        lp(30))
                    return
                }
                val rows = maxOf(4, (list.size + 4) / 5)
                repeat(rows) { rowIndex ->
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(CELL_HEIGHT)).apply { if (rowIndex > 0) topMargin = dp(ROW_GAP) })
                    repeat(5) { col ->
                        val app = list.getOrNull(rowIndex * 5 + col)
                        val view = if (app == null) View(activity) else ResidentAppTile(activity, app).also { tile ->
                            tiles[app.pkg] = tile
                            bindIcon(app.pkg, tile.icon)
                            tile.setOnClickListener { onToggle(app.pkg, !tile.isChecked()) }
                        }
                        row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    }
                }
            }
            populate(systemBody, visible.filter { it.system })
            populate(personalBody, visible.filterNot { it.system })
        } else {
            // Label metadata may arrive after the initial background load; do not reset scroll.
            apps.forEach { tiles[it.pkg]?.updateLabel(it.label) }
        }
        setSelection(selected)
    }

    fun setSelection(selected: Set<String>) {
        lastSelection = selected
        tiles.forEach { (pkg, tile) -> tile.setChecked(pkg in selected) }
        selectedCount.text = "常驻显示 ${items.count { it.pkg in selected }} 个应用"
        val visible = items.filter { searchQuery.isBlank() || it.label.contains(searchQuery, true) || it.pkg.contains(searchQuery, true) }
        systemCount.text = "常驻 ${visible.count { it.system && it.pkg in selected }} / ${visible.count { it.system }}"
        personalCount.text = "常驻 ${visible.count { !it.system && it.pkg in selected }} / ${visible.count { !it.system }}"
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    private fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()

    companion object { private const val CELL_HEIGHT = 96; private const val ROW_GAP = 8 }
}

private class ResidentAppTile(context: Context, private val app: ResidentAppItem) : LinearLayout(context), Checkable {
    private var checkedState = false
    private var appLabel = app.label
    private val badge = ResidentCheckBadge(context)
    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_apps)
    }
    private val name = TextView(context).apply {
        text = app.label
        textSize = 13f
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextColor(ContextCompat.getColor(context, R.color.settings_text))
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(context, R.drawable.bg_settings_nav_idle)
        val frame = FrameLayout(context)
        frame.addView(icon, FrameLayout.LayoutParams(dp(45), dp(45), Gravity.TOP or Gravity.START))
        frame.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END))
        addView(frame, LayoutParams(dp(55), dp(55)))
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)).apply { topMargin = dp(4) })
    }

    fun updateLabel(value: String) { appLabel = value; name.text = value; updateDescription() }
    override fun isChecked(): Boolean = checkedState
    override fun toggle() { setChecked(!checkedState) }
    override fun setChecked(checked: Boolean) {
        checkedState = checked
        isSelected = checked
        badge.checked = checked
        updateDescription()
    }
    private fun updateDescription() {
        contentDescription = "$appLabel，${app.pkg}，${if (checkedState) "常驻显示" else "不常驻，前台时隐藏 Dock"}"
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.CheckBox"
        info.isCheckable = true
        info.isChecked = checkedState
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}

private class ResidentCheckBadge(context: Context) : View(context) {
    var checked = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (checked) 0xFF3DDC97.toInt() else 0xFF39404A.toInt()
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        if (checked) {
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(width * .25f, height * .51f)
            path.lineTo(width * .44f, height * .69f)
            path.lineTo(width * .77f, height * .32f)
            canvas.drawPath(path, paint)
        }
    }
}
