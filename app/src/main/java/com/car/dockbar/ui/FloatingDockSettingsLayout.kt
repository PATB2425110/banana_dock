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


internal class FloatingDockSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild() {
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

        // 清空旧版“左侧权限、右侧表单”布局前，先将控件从原父容器中移除。
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
        add(geometry, screenHeight, 0) // 保留隐藏的旧版配置项，供 saveWindow() 使用。
        geometry.addView(text("推荐尺寸 1920 × 128 px", 14f, true), params(22))
        val runtime = card(right, "运行与权限", "管理悬浮栏所需的运行权限").apply {
            minimumHeight = dp(340)
            // 随左栏拉伸，使此卡片底部与显示行为卡片的底部对齐。
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
        // 将详细诊断信息保留在诊断页，避免撑大运行概况卡片。
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
                // 仅重置这些输入框，仍需点击“应用布局”才会保存。
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
}
