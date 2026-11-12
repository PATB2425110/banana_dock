package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import android.content.res.Configuration

class AppearanceSettingsPage(activity: MainActivity) : SettingsPage(activity, MainActivity.SECTION_APPEARANCE) {
    override val root: LinearLayout get() = findViewById(R.id.cardAppearance)
    override fun buildLayout() { AppearanceSettingsLayout(activity).rebuild() }
    private lateinit var tvCorner: TextView
    private lateinit var tvIconScale: TextView
    private lateinit var tvTextScale: TextView
    private var gradientPreview: GradientPreviewView? = null
    private var bgColorControlRows: List<View> = emptyList()
    private val backgroundModeTiles = LinkedHashMap<BgMode, BackgroundModeTile>()
    private var blurRadiusRow: View? = null
    override fun create() {
        tvCorner = findViewById(R.id.tvCorner)
        tvIconScale = findViewById(R.id.tvIconScale)
        tvTextScale = findViewById(R.id.tvTextScale)
        findViewById<SwitchCompat>(R.id.swSeparators).isChecked = Prefs.showSeparators(this)
        findViewById<SeekBar>(R.id.sbCorner).progress = Prefs.cornerRadiusPx(this)
        findViewById<SeekBar>(R.id.sbIconScale).progress =
            ((Prefs.iconScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.sbTextScale).progress =
            ((Prefs.textScale(this) - 0.5f) * 100).toInt().coerceIn(0, 100)


        refreshAppearanceLabels()
        findViewById<SeekBar>(R.id.sbCorner).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbIconScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SeekBar>(R.id.sbTextScale).setOnSeekBarChangeListener(seek { refreshAppearanceLabels() })
        findViewById<SwitchCompat>(R.id.swSeparators).setOnCheckedChangeListener { _, _ ->
            saveAppearance()
            reloadService()
        }
        val appearance = root
        addSwitchRow(
            appearance, "底色跟随系统日夜模式",
            Prefs.bgFollowNight(this)
        ) { Prefs.setBgFollowNight(this, it) }
        appearance.getChildAt(appearance.childCount - 1).id = R.id.settingsFollowNightRow

        // （「空调温度」分区已按用户要求从设置页移除：
        //   双温联动改由长按副驾温度切换、HI/LO 阈值用车机默认 15.5~28.5℃）

        // --- 底色（形态 + 参数）────────────────
        appearance.addView(TextView(this).apply {
            text = "底色"
            setTextColor(0xFFEDEFF3.toInt())
            textSize = 13f
            typeface = AppFont.bold(this@AppearanceSettingsPage)
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 背景形态四选一；旧「模糊」入口用于透明灰色叠层试验，不采样屏幕。
        addBgModeRow(appearance)
        val overlayControls = LinearLayout(this).apply {
            id = R.id.settingsOverlayControls
            orientation = LinearLayout.VERTICAL
        }
        appearance.addView(overlayControls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        blurRadiusRow = overlayControls
        addSeekRow(
            overlayControls, "第二层 · 灰色深浅",
            Prefs.overlayGrayLevel(this), 100,
            { when (it) { 0 -> "黑色"; 100 -> "白色"; else -> "$it%（越大越浅）" } },
            onChange = { Prefs.setOverlayGrayLevel(this, it) }
        )
        addSeekRow(
            overlayControls, "第三层 · 透明度",
            Prefs.overlayTransparency(this), 100,
            { when (it) { 0 -> "不透明"; 100 -> "完全透明"; else -> "$it%" } },
            onChange = { Prefs.setOverlayTransparency(this, it) }
        )
        overlayControls.addView(TextView(this).apply {
            text = "第一层始终纯透明。灰色深浅与合成透明度分别调节，不影响图标和文字；此效果不是真正的背景模糊。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AppearanceSettingsPage, R.color.settings_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        blurRadiusRow?.visibility =
            if (Prefs.bgMode(this) == BgMode.BLUR) View.VISIBLE else View.GONE

        // 实时预览条：按当前配置（含系统深浅色）画一条一模一样的渐变
        val preview = GradientPreviewView(this).apply {
            id = R.id.settingsGradientPreview
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
            onProgress = { p -> Prefs.setDayBgTransparency(this, p); refreshGradientPreview() },
            separateValue = true
        )
        dayTransparencyRow.id = R.id.settingsDayTransparencyRow
        // ── 夜间底色透明度 ───────────────────────
        val nightTransparencyRow = addSeekRow(
            appearance, "夜间底色透明度",
            Prefs.nightBgTransparency(this), 100,
            format = { "$it%" },
            onChange = { Prefs.setNightBgTransparency(this, it) },
            onProgress = { p -> Prefs.setNightBgTransparency(this, p); refreshGradientPreview() },
            separateValue = true
        )
        nightTransparencyRow.id = R.id.settingsNightTransparencyRow
        // ★ v3.13.16：按当前形态显隐相关控件（滑杆/预览条只属于纯色与渐变）
        bgColorControlRows = listOf(dayTransparencyRow, nightTransparencyRow)
        refreshBgModeButtons(backgroundModeTiles, Prefs.bgMode(this))


    }
    override fun onResume() { refreshGradientPreview() }
    override fun onConfigurationChanged(newConfig: Configuration) { refreshGradientPreview() }
    private fun refreshAppearanceLabels() {
        tvCorner.text = "${findViewById<SeekBar>(R.id.sbCorner).progress} px"
        val isc = 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f
        tvIconScale.text = "${"%.2f".format(isc)}×"
        val tsc = 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f
        tvTextScale.text = "${"%.2f".format(tsc)}×"
    }
    private fun saveAppearance() {
        Prefs.setCornerRadiusPx(this, findViewById<SeekBar>(R.id.sbCorner).progress)
        Prefs.setIconScale(this, 0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)
        Prefs.setTextScale(this, 0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)
        Prefs.setShowSeparators(this, findViewById<SwitchCompat>(R.id.swSeparators).isChecked)
        Logx.d("外观配置已保存：图标×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbIconScale).progress / 100f)} 文字×${"%.2f".format(0.5f + findViewById<SeekBar>(R.id.sbTextScale).progress / 100f)}（反色按背景自动）")
    }
    private fun refreshGradientPreview() {
        val solid = Prefs.bgSolidMode(this)
        gradientPreview?.update(
            if (solid) Prefs.solidBgColor(this) else 0,
            if (solid) null else Prefs.currentGradientColors(this)
        )
    }
    private fun refreshBgModeButtons(buttons: Map<BgMode, BackgroundModeTile>, selected: BgMode) {
        for ((mode, b) in buttons) {
            b.isSelected = mode == selected
        }
        // 纯色 / 渐变显示日夜透明度；现有试验参数只在「模糊」入口下显示。
        val showColorControls = selected == BgMode.SOLID || selected == BgMode.GRADIENT
        for (row in bgColorControlRows) {
            row.visibility = if (showColorControls) View.VISIBLE else View.GONE
        }
        gradientPreview?.visibility = if (showColorControls) View.VISIBLE else View.GONE
        blurRadiusRow?.visibility = if (selected == BgMode.BLUR) View.VISIBLE else View.GONE
    }
    private fun addBgModeRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            id = R.id.settingsBgModeRow
            orientation = LinearLayout.HORIZONTAL
        }
        val buttons = backgroundModeTiles.apply { clear() }
        BgMode.values().forEach { m ->
            val b = BackgroundModeTile(this, m).apply {
                setOnClickListener {
                    Prefs.setBgMode(this@AppearanceSettingsPage, m)
                    refreshBgModeButtons(buttons, m)
                    refreshGradientPreview()
                    reloadService()
                }
            }
            buttons[m] = b
            row.addView(b, LinearLayout.LayoutParams(
                0, dp(142), 1f
            ).apply { if (buttons.size > 1) marginStart = dp(16) })
        }
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        refreshBgModeButtons(buttons, Prefs.bgMode(this))
    }
    private class GradientPreviewView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: Shader? = null
        private var colors: IntArray? = null
        private var solidColor: Int? = null
        fun update(solidColor: Int, colors: IntArray?) { this.solidColor = solidColor; this.colors = colors; shader = null; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val solid = solidColor
            if (solid != null) { paint.shader = null; paint.color = solid; canvas.drawRect(0f, 0f, w, h, paint); return }
            val cs = colors
            if (cs == null || cs.size < 2) { paint.shader = null; paint.color = 0x59000000; canvas.drawRect(0f, 0f, w, h, paint); return }
            if (shader == null && w > 0f && h > 0f) { val ramp = com.car.dockbar.util.GradientSpec.verticalRampColors(cs); shader = LinearGradient(0f, 0f, 0f, h, ramp, com.car.dockbar.util.GradientSpec.rampPositions(ramp.size), Shader.TileMode.CLAMP) }
            paint.shader = shader; canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}
