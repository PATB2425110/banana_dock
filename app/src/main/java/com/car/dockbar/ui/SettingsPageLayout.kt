package com.car.dockbar.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R

/** 复用已绑定的控件及其监听器；仅调整展示方式，不改变设置行为。 */
internal open class SettingsPageLayout(protected val activity: Activity) {
    protected fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    protected fun color(id: Int) = ContextCompat.getColor(activity, id)
    protected fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    protected fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    protected fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    protected fun take(view: View): View {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    protected fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
        includeFontPadding = false
    }

    protected fun heading(parent: LinearLayout, title: String, description: String) {
        parent.addView(text(title, 30f).apply {
            typeface = AppFont.bold(activity)
        }, params())
        parent.addView(text(description, 15f, true), params(12))
    }

    protected fun card(parent: LinearLayout, title: String, description: String? = null): LinearLayout {
        val card = column().apply {
            background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
            setPadding(dp(26), dp(26), dp(26), dp(26))
        }
        parent.addView(card, params(22))
        card.addView(text(title, 21f).apply { typeface = AppFont.bold(activity) }, params())
        if (description != null) card.addView(text(description, 14f, true), params(12))
        return card
    }

    protected fun add(parent: LinearLayout, view: View, top: Int = 16) {
        parent.addView(take(view), params(top))
    }

    protected fun divider(parent: LinearLayout) {
        parent.addView(View(activity).apply { setBackgroundColor(0x12FFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(22)
                bottomMargin = dp(6)
            })
    }

}
