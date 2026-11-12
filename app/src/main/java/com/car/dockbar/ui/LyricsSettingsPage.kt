package com.car.dockbar.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.media.LyricMonitor
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

internal class LyricsSettingsPage(activity: MainActivity) : SettingsPage(activity, MainActivity.SECTION_LYRIC) {
    private val appearanceHeadingId = View.generateViewId()
    private val onlineRowId = View.generateViewId()
    private var tvLyricStatus: TextView? = null
    private var btnLyricToggle: Button? = null
    private var lyricSourceButtons: List<Pair<Int, Button>> = emptyList()
    private var lyricMapButtons: List<Pair<Int, Button>> = emptyList()
    override val root: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    override fun onShown() {
        root.removeAllViews()
        lyricSourceButtons = emptyList()
        lyricMapButtons = emptyList()
        addLyricSection(root)
        rebuildLyrics(root, 0)
        updateLyricStatus()
    }

    override fun onResume() {
        if (Prefs.lyricEnabled(activity) && !LyricOverlayService.alive) ensureLyricStarted()
        updateLyricStatus()
    }
    override fun onServiceStateChanged() { updateLyricStatus() }


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

    private fun addLyricSection(root: LinearLayout) {

        root.addView(TextView(activity).apply {
            text = "歌词悬浮窗"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 14f
            typeface = AppFont.bold(activity)
            setPadding(0, dp(20), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 当前状态一行
        root.addView(TextView(activity).apply {
            text = ""
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
        }.also { tvLyricStatus = it }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // ① 总开关
        root.addView(Button(activity).apply {
            text = if (Prefs.lyricEnabled(activity)) "关闭歌词悬浮窗" else "启动歌词悬浮窗"
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.settings_on_accent))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_button_primary)
            setOnClickListener {
                if (Prefs.lyricEnabled(activity)) {
                    Prefs.setLyricEnabled(activity, false)
                    stopLyricNow()
                    text = "启动歌词悬浮窗"
                    Logx.d("歌词悬浮窗已关闭")
                } else {
                    Prefs.setLyricEnabled(activity, true)
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
            Prefs.lyricLocked(activity), reloadDock = false
        ) {
            Prefs.setLyricLocked(activity, it)
            sendLyricUpdate()
        }
        // ── [S11] 开机自启歌词悬浮窗 ────────────────────
        addSwitchRow(
            root, "开机自启歌词悬浮窗",
            Prefs.lyricBoot(activity), reloadDock = false
        ) { Prefs.setLyricBoot(activity, it) }

        // ② 外观（全部「拖动即生效」：onProgress 里写 Prefs + 发广播热更新歌词窗）
        root.addView(TextView(activity).apply {
            id = appearanceHeadingId
            text = "② 外观（拖动即生效；若歌词窗未启动，请先点上面的启动按钮）"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            typeface = AppFont.bold(activity)
            setPadding(0, dp(12), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── [S12] 背景不透明度 ───────────────────────
        addSeekRow(
            root, "背景不透明度",
            Prefs.lyricAlpha(activity), 100,
            { "$it%" }, reloadDock = false,
            onChange = { Prefs.setLyricAlpha(activity, it) },
            onProgress = { p -> Prefs.setLyricAlpha(activity, p); sendLyricUpdate() }
        )
        // ── [S13] 窗口宽度 ─────────────────────────
        addSeekRow(
            root, "窗口宽度",
            Prefs.lyricWinW(activity), 1200,
            { "$it dp" }, reloadDock = false,
            onChange = { Prefs.setLyricWinW(activity, it) },
            onProgress = { p -> Prefs.setLyricWinW(activity, p); sendLyricUpdate() }
        )
        // ── [S14] 窗口高度（最左端=自适应） ────────────────
        addSeekRow(
            root, "窗口高度（最左端=自适应）",
            Prefs.lyricWinH(activity), 800,
            { if (it == 0) "自适应" else "$it dp" }, reloadDock = false,
            onChange = { Prefs.setLyricWinH(activity, it) },
            onProgress = { p -> Prefs.setLyricWinH(activity, p); sendLyricUpdate() }
        )
        // ── [S15] 歌词字号 ─────────────────────────
        addSeekRow(
            root, "歌词字号",
            // ★ 修正：滑块 progress 与真实字号之间差 14（format/onChange 都做 +14），
            //   旧代码传的是原始值 → 打开设置页标签显示「36 sp」而实际生效 22 sp。
            (Prefs.lyricTextSize(activity) - 14).coerceIn(0, 26), 26,
            { "${it + 14} sp" }, reloadDock = false,
            onChange = { Prefs.setLyricTextSize(activity, it + 14) },
            onProgress = { p -> Prefs.setLyricTextSize(activity, p + 14); sendLyricUpdate() }
        )
        // ── [S16] 显示行数 ─────────────────────────
        addSeekRow(
            root, "显示行数",
            Prefs.lyricLineCount(activity) - 1, 9,
            { "${it + 1} 行" }, reloadDock = false,
            onChange = { Prefs.setLyricLineCount(activity, it + 1) },
            onProgress = { p -> Prefs.setLyricLineCount(activity, p + 1); sendLyricUpdate() }
        )
        // ── [S17] 歌词偏移（负=提前，正=延后） ──────────────
        addSeekRow(
            root, "歌词偏移（负=提前，正=延后）",
            (Prefs.lyricOffsetMs(activity) + 3000) / 200, 30,
            { "${it * 200 - 3000} ms" }, reloadDock = false,
            onChange = { Prefs.setLyricOffsetMs(activity, it * 200 - 3000) },
            onProgress = { p -> Prefs.setLyricOffsetMs(activity, p * 200 - 3000); sendLyricUpdate() }
        )

        addColorPresetRow(root, "普通行颜色", Prefs.lyricTextColor(activity)) {
            Prefs.setLyricTextColor(activity, it)
        }
        addColorPresetRow(root, "当前行颜色", Prefs.lyricHlColor(activity)) {
            Prefs.setLyricHlColor(activity, it)
        }

        // ③ 歌词获取
        // ── [S18] 允许联网获取歌词（无歌词音源时按歌名抓） ─────────
        addSwitchRow(
            root, "允许联网获取歌词（无歌词音源时按歌名抓）",
            Prefs.lyricOnline(activity), reloadDock = false
        ) {
            Prefs.setLyricOnline(activity, it)
        }

        root.getChildAt(root.childCount - 1).id = onlineRowId

        // 歌词源 4 选 1
        root.addView(TextView(activity).apply {
            text = "歌词源"
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val srcLabels = listOf("自动", "网易云", "LRCLIB", "自定义", "QQ音乐")
        val srcRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val srcButtons = mutableListOf<Pair<Int, Button>>()
        for ((i, lb) in srcLabels.withIndex()) {
            val b = Button(activity).apply {
                text = lb
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    Prefs.setLyricSource(activity, i)
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
        val cur = Prefs.lyricSource(activity)
        lyricSourceButtons.forEach { (i, btn) ->
            val selected = i == cur
            btn.background = ContextCompat.getDrawable(
                activity,
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_field
            )
            btn.setTextColor(ContextCompat.getColor(activity,
                if (selected) R.color.settings_on_accent else R.color.settings_secondary))
        }
    }

    private fun updateMapButtons() {
        val cur = Prefs.lyricMapMode(activity)
        lyricMapButtons.forEach { (mode, btn) ->
            val selected = mode == cur
            btn.background = ContextCompat.getDrawable(
                activity,
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_field
            )
            btn.setTextColor(ContextCompat.getColor(activity,
                if (selected) R.color.settings_on_accent else R.color.settings_secondary))
        }
    }

    private fun showManualLyricDialog() {
        val title = LyricMonitor.currentTitle
        if (title.isNullOrEmpty()) {
            toastOnce("还没读到歌曲信息，先放一首歌")
            return
        }
        val et = EditText(activity).apply {
            hint = "粘贴 LRC 歌词（[mm:ss.xx] 格式）"
            setTextColor(0xFFEDEFF3.toInt())
            setHintTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
            minLines = 6
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_field)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
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
            .show()
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
            if (Prefs.lyricEnabled(activity)) "关闭歌词悬浮窗" else "启动歌词悬浮窗"
    }

    private fun rebuildLyrics(root: LinearLayout, start: Int) {
        val children = (start until root.childCount).map { root.getChildAt(it) }
        children.forEach { take(it) }
        val page = column()
        root.addView(page, params())
        heading(page, "歌词悬浮窗", "管理歌词窗口、显示效果与在线歌词来源")
        var section = card(page, "窗口与运行")
        children.drop(1).forEach { view ->
            when {
                view.id == appearanceHeadingId -> {
                    section = card(page, "显示效果", "调整滑杆即可生效")
                }
                view.id == onlineRowId -> {
                    section = card(page, "歌词来源", "允许联网后可自动查找当前歌曲的歌词")
                    add(section, view)
                }
                else -> add(section, view)
            }
        }
    }

}
