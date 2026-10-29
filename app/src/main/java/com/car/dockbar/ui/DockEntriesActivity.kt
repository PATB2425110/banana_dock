package com.car.dockbar.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.Builtin
import com.car.dockbar.model.DockEntries
import com.car.dockbar.model.DockEntry
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/**
 * Dock 图标管理页：增删 / 排序 Dock 条目。
 *
 * - 内置控件（温度/风速/风向/通风/循环/座椅/音乐/设置）可重复添加
 * - 从已安装应用列表添加自定义 App（保留 App 原图标）
 * - 上移 / 下移 / 删除 / 恢复默认布局
 *
 * 改完立即发 [DockOverlayService.ACTION_ENTRIES_CHANGED] 广播，悬浮栏实时重建。
 */
class DockEntriesActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private lateinit var tvSummary: TextView
    private val entries = mutableListOf<DockEntry>()

    // ===== 长按拖动排序状态（条目不跟手：长按选中原地高亮，松手才落位）=====
    private var dragView: View? = null
    private var dragIndex = -1
    private var dropIndex = -1
    private var containerTopOnScreen = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        tvSummary = TextView(this).apply {
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
        }
        root.addView(tvSummary)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(btn("＋ 内置控件") { showBuiltinPicker() }, lp(1f))
        btnRow.addView(btn("＋ 应用") { showAppPicker() }, lp(1f))
        btnRow.addView(btn("恢复默认") { confirmReset() }, lp(1f))
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(container)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(10) })

        setContentView(root)

        // ★ 底部让出 Dock 高度：Dock 是悬浮窗，会盖住列表最后几项导致点不到
        // （抬高量 = dockHeightPx + dockMarginPx，跟随配置；Dock 在顶部时不需要）
        if (Prefs.dockAtBottom(this)) {
            val clear = Prefs.dockHeightPx(this) + Prefs.dockMarginPx(this) + dp(12)
            root.setPadding(dp(14), dp(12), dp(14), dp(12) + clear)
        }
        reload()
    }

    // ==================================================================

    private fun reload() {
        entries.clear()
        entries.addAll(DockEntries.load(this))
        render()
    }

    private fun render() {
        container.removeAllViews()
        tvSummary.text = "当前 ${entries.size} 个条目（长按条目拖动调顺序，✕ 删除）"
        entries.forEachIndexed { index, e ->
            container.addView(row(e, index), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有任何条目，点上面的按钮添加。"
                setTextColor(0xFF9AA1AE.toInt())
                textSize = 13f
            })
        }
    }

    private fun row(e: DockEntry, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@DockEntriesActivity, R.drawable.bg_field)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            scaleType = ImageView.ScaleType.FIT_CENTER
            bindPreviewIcon(this, e)
        }
        row.addView(icon)

        val label = TextView(this).apply {
            text = "${index + 1}. ${e.label}　${if (e.kind == "app") "（App）" else ""}"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 13f
            setPadding(dp(10), 0, dp(6), 0)
            // 单行省略：保证每行等高（长按拖动按等高行做槽位换算）
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(smallBtn("✕") { remove(index) })
        setupDrag(row, index)
        return row
    }

    // ==================================================================
    // 长按拖动排序
    // ==================================================================

    /**
     * 给条目行挂「长按拖动」（松手落位式）：
     *  - 长按拿起：震动 + 浮起高亮，**条目保持原地不动**；
     *  - 手指移动：只计算手指指向的落点槽位（目标行高亮提示），条目不跟手；
     *  - 松手：条目落到落点槽位，保存并刷新序号。
     * 长按之前手指移动 = 正常滚动列表，互不干扰。
     */
    private fun setupDrag(v: View, index: Int) {
        v.setOnLongClickListener {
            if (dragView != null) return@setOnLongClickListener true
            dragView = v
            dragIndex = index
            dropIndex = index
            v.isPressed = false
            v.elevation = dp(6).toFloat()
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            containerTopOnScreen = loc[1].toFloat()
            // 长按成立后才禁止 ScrollView 抢事件，保证拖选期间列表不滚
            container.requestDisallowInterceptTouchEvent(true)
            highlightDropTarget()
            true
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> false // 放行给长按检测
                MotionEvent.ACTION_MOVE -> {
                    val dv = dragView ?: return@setOnTouchListener false
                    if (dv !== view) return@setOnTouchListener false
                    updateDropIndex(ev.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragView === view) finishDrag()
                    false
                }
                else -> false
            }
        }
    }

    /** 手指当前指着的落点槽位（条目本身不跟手，松手才落位） */
    private fun updateDropIndex(rawY: Float) {
        val dv = dragView ?: return
        val n = container.childCount
        if (dragIndex !in 0 until n) return
        val step = dv.height + dp(4) // 行高 + 行间距（render 时 topMargin=4dp）
        val y = rawY - containerTopOnScreen
        val t = (y / step).toInt().coerceIn(0, n - 1)
        if (t != dropIndex) {
            dropIndex = t
            highlightDropTarget()
        }
    }

    /** 落点提示：目标行全亮，其余行淡显，被拖行保持浮起 */
    private fun highlightDropTarget() {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            c.alpha = if (i == dropIndex) 1f else 0.55f
        }
        dragView?.alpha = 0.9f
    }

    /** 松手：条目落到落点槽位，保存并刷新 */
    private fun finishDrag() {
        val dv = dragView ?: return
        dragView = null
        val from = dragIndex
        val to = dropIndex
        dragIndex = -1
        dropIndex = -1
        dv.elevation = 0f
        dv.isPressed = false
        if (to != from && to in entries.indices) {
            val e = entries.removeAt(from)
            entries.add(to, e)
            DockEntries.save(this, entries)
            notifyChanged()
            Logx.d("图标管理：长按拖动 $from → $to，新顺序已保存")
        }
        render() // 恢复各行透明度并刷新序号
    }

    private fun remove(index: Int) {
        if (index !in entries.indices) return
        val e = entries.removeAt(index)
        DockEntries.save(this, entries)
        notifyChanged()
        render()
        toast("已删除「${e.label}」")
    }

    private fun showBuiltinPicker() {
        val added = entries.filter { it.kind == "builtin" }.map { it.action }.toSet()
        // hidden = 已被组合按钮取代的旧「±」条目，不再允许单独添加
        val available = Builtin.values().filter { !it.hidden && it.action !in added }
        if (available.isEmpty()) {
            toast("内置控件都已添加过了")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("添加内置控件")
            .setItems(available.map { "${it.label} — ${it.hint}" }.toTypedArray()) { _, which ->
                val b = available[which]
                entries.clear()
                entries.addAll(DockEntries.addBuiltin(this, b))
                notifyChanged()
                render()
                toast("已添加「${b.label}」")
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
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
        if (Prefs.dockAtBottom(this@DockEntriesActivity)) {
            window?.decorView?.setPadding(
                0, 0, 0,
                Prefs.dockHeightPx(this@DockEntriesActivity) + Prefs.dockMarginPx(this@DockEntriesActivity) + dp(12)
            )
        }
        return this
    }

    private fun showAppPicker() {
        val loading = AlertDialog.Builder(this)
            .setTitle("正在读取已安装应用…")
            .setCancelable(true)
            .create()
        loading.show()
        loading.applyDockSafe()
        Thread {
            val pm: PackageManager = packageManager
            val list = runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                )
            }.getOrDefault(emptyList())
            val items = list.asSequence()
                .mapNotNull { it.activityInfo }
                .map { Triple(it.loadLabel(pm).toString(), it.packageName, it.name) }
                .distinctBy { it.second }
                .sortedBy { it.first }
                .toList()
            main.post {
                runCatching { loading.dismiss() }
                showAppList(items)
            }
        }.start()
    }

    private fun showAppList(items: List<Triple<String, String, String>>) {
        if (items.isEmpty()) {
            toast("没有读到可启动的应用")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择要添加到 Dock 的应用")
            .setItems(items.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which ->
                val (label, pkg, cls) = items[which]
                entries.clear()
                entries.addAll(DockEntries.addApp(this, label, pkg, cls))
                notifyChanged()
                render()
                toast("已添加「$label」")
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认布局")
            .setMessage("会清空你的自定义增删，回到出厂默认布局。确定？")
            .setPositiveButton("恢复") { _, _ ->
                DockEntries.reset(this)
                reload()
                notifyChanged()
                toast("已恢复默认布局")
            }
            .setNegativeButton("取消", null)
            .showDockSafe()
    }

    private fun notifyChanged() {
        runCatching {
            sendBroadcast(Intent(DockOverlayService.ACTION_ENTRIES_CHANGED).setPackage(packageName))
        }
        Logx.d("图标管理：条目已变更，通知悬浮栏重建")
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 给管理页的图标预览位绑定图标：App 用原图标，内置控件用对应矢量图 */
    private fun bindPreviewIcon(iv: ImageView, e: DockEntry) {
        if (e.kind == "app") {
            runCatching { iv.setImageDrawable(packageManager.getApplicationIcon(e.action)) }
                .onFailure { iv.setImageResource(R.drawable.ic_car) }
            return
        }
        iv.setImageResource(builtinPreviewRes(e.action))
    }

    private fun builtinPreviewRes(action: String): Int = when (action) {
        // ★ v4.0：与 Dock 上实际显示保持一致（圆形图标）
        Builtin.HOME.action -> R.drawable.ic_home_circle
        Builtin.MUSIC.action -> R.drawable.ic_music_note
        Builtin.SETTINGS.action -> R.drawable.ic_car
        // ★ v1.5.14：座椅用纯座椅图标（跟 Dock 上实际显示的一致）
        Builtin.SEAT.action -> R.drawable.ic_seat_open
        Builtin.APP_DRAWER.action -> R.drawable.ic_apps
        // v1.4.24 组合按钮：温度类用车标，风速沿用箭头
        Builtin.TEMP_DRIVER_COMBO.action, Builtin.TEMP_PASSENGER_COMBO.action -> R.drawable.ic_car
        Builtin.FAN_COMBO.action -> R.drawable.ic_chevron_left
        Builtin.AIR.action -> R.drawable.ic_airflow
        Builtin.VENT.action -> R.drawable.ic_seat_vent
        Builtin.SEAT_HEAT.action -> R.drawable.ic_seat
        Builtin.RECIRC.action -> R.drawable.ic_recirc
        // ★ v3.1：前挡/后挡/方向盘加热（默认不在 Dock，手动添加后才有）
        Builtin.DEFROST_FRONT.action -> R.drawable.ic_defrost
        Builtin.DEFROST_REAR.action -> R.drawable.ic_rear_defrost
        Builtin.STEERING_HEAT.action -> R.drawable.ic_sw_heat_03
        Builtin.FAN.action, Builtin.TEMP_DRIVER.action, Builtin.TEMP_PASSENGER.action -> R.drawable.ic_chevron_left
        else -> R.drawable.ic_chevron_left
    }

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFFFFFFFF.toInt())
        background = ContextCompat.getDrawable(this@DockEntriesActivity, R.drawable.bg_button_primary)
        setPadding(dp(6), dp(10), dp(6), dp(10))
        setOnClickListener { onClick() }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setTextColor(0xFFEDEFF3.toInt())
        background = ContextCompat.getDrawable(this@DockEntriesActivity, R.drawable.bg_item_press)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun lp(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
