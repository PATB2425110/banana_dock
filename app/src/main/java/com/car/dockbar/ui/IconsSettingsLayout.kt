package com.car.dockbar.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.model.DockEntries


internal class IconsSettingsLayout(activity: Activity) : SettingsPageLayout(activity) {
    fun rebuild() {
        val page = activity.findViewById<LinearLayout>(R.id.settingsDockEntriesPage)
        val manage = activity.findViewById<Button>(R.id.settingsManageDockEntries)
        val summary = activity.findViewById<TextView>(R.id.settingsQuickAppsSummary)
        val manageApps = activity.findViewById<Button>(R.id.settingsManageQuickApps)
        val grid = activity.findViewById<View>(R.id.settingsQuickAppsGrid)
        val popup = activity.findViewById<View>(R.id.settingsPopupSwitchRow)
        val duration = activity.findViewById<View>(R.id.settingsPopupDurationRow)
        val fade = activity.findViewById<View>(R.id.settingsFadeDurationRow)
        val auto = activity.findViewById<View>(R.id.settingsAutoFanRow)
        val targets = activity.findViewById<EditText>(R.id.etSettingsPkg).parent as View
        take(targets)
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

        // 按实际控件内容包裹布局，避免强行放入高度为零的权重卡片。
        val autoCard = card(right, "AUTO 风道档位").apply {
            setPadding(dp(26), dp(20), dp(26), dp(20))
            minimumHeight = dp(164)
        }
        auto.setPadding(0, dp(4), 0, dp(4))
        add(autoCard, auto, 10)
        columns.balance(left, right, feedback, autoCard)
        page.addView(targets, params())
    }
}

/** 分配多余的纵向空间，但不将任一卡片压缩到低于其实际内容高度。 */
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
