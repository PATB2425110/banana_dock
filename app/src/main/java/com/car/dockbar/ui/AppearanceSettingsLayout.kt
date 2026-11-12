package com.car.dockbar.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.car.dockbar.R


internal class AppearanceSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild() {
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
        // 缩放整个控件而非仅缩放文字，并为变换后的边界预留空间。
        // Android 会通过子控件的逆变换分发触摸事件。
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
}
