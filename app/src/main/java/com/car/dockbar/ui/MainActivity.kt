package com.car.dockbar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/** 设置页容器：仅负责页面注册、导航及生命周期分发。 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val UI_SCALE = 0.9f
        const val EXTRA_SECTION = "section"
        const val SECTION_OVERLAY = "overlay"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_ICONS = "icons"
        const val SECTION_LYRIC = "lyric"
        const val SECTION_APPS = "apps"
        const val SECTION_LOGS = "logs"
        const val EXTRA_PICK_APP_DRAWER = "pick_app_drawer"

        fun scaledContext(base: Context, previousScale: Float = 1.2f): Context {
            val config = Configuration(base.resources.configuration)
            config.densityDpi = (base.resources.displayMetrics.densityDpi * previousScale * UI_SCALE).toInt()
            return base.createConfigurationContext(config)
        }
    }

    private data class PageSpec(
        val key: String,
        val label: String,
        val icon: Int,
        val factory: (MainActivity) -> SettingsPage
    )

    /** 在此注册或移除页面；控件与行为由各页面自身的控制器管理。 */
    private val pageSpecs = listOf(
        PageSpec(SECTION_OVERLAY, "悬浮 Dock 栏", R.drawable.ic_nav_dock, ::FloatingDockSettingsPage),
        PageSpec(SECTION_APPEARANCE, "外观与风格", R.drawable.ic_nav_home, ::AppearanceSettingsPage),
        PageSpec(SECTION_ICONS, "Dock 图标管理", R.drawable.ic_nav_grid, ::IconsSettingsPage),
        PageSpec(SECTION_LYRIC, "歌词悬浮窗", R.drawable.ic_nav_doc, ::LyricsSettingsPage),
        PageSpec(SECTION_APPS, "常驻应用设置", R.drawable.ic_nav_list, ::ResidentSettingsPage),
        PageSpec(SECTION_LOGS, "诊断与日志", R.drawable.ic_nav_pulse, ::LogsSettingsPage)
    )
    private class NavRow(val container: View, val bar: View, val icon: ImageView, val label: TextView)
    private val navButtons = LinkedHashMap<String, NavRow>()
    private lateinit var pages: SettingsPageRegistry
    private var currentPage = SECTION_OVERLAY

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(scaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 保留原有的 128 物理像素 Dock 避让距离，不随显示密度变化。
        listOf(R.id.svNav, R.id.svContent).forEach { id ->
            val viewport = findViewById<View>(id)
            (viewport.layoutParams as LinearLayout.LayoutParams).apply {
                bottomMargin = 128
                viewport.layoutParams = this
            }
        }
        val content = findViewById<LinearLayout>(R.id.llRoot)
        val controllers = pageSpecs.map { it.factory(this) }
        controllers.forEach { page ->
            if (page.root.parent == null) content.addView(page.root,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        pages = SettingsPageRegistry(controllers)
        pages.createAll()
        buildNav()
        mapOf(R.id.btnOpenApps to SECTION_APPS, R.id.btnOpenLyric to SECTION_LYRIC,
            R.id.btnOpenLog to SECTION_LOGS).forEach { (id, key) ->
            findViewById<View>(id).setOnClickListener { showPage(key) }
        }
        val target = intent?.getStringExtra(EXTRA_SECTION)
        showPage(target?.takeUnless { it.isBlank() } ?: SECTION_OVERLAY)
        if (intent?.getBooleanExtra(EXTRA_PICK_APP_DRAWER, false) == true && target.isNullOrBlank()) {
            (pages.page(SECTION_ICONS) as? IconsSettingsPage)?.requestAppDrawerPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pages.isInitialized) pages.onResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::pages.isInitialized) pages.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (::pages.isInitialized) pages.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Uses the existing settings navigation back behavior")
    override fun onBackPressed() {
        if (currentPage != SECTION_OVERLAY) showPage(SECTION_OVERLAY) else super.onBackPressed()
    }

    private fun showPage(key: String) {
        val page = pages.page(key) ?: pages.page(SECTION_OVERLAY) ?: return
        currentPage = page.key
        val content = findViewById<LinearLayout>(R.id.llRoot)
        for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.GONE
        page.root.visibility = View.VISIBLE
        pages.show(page.key)
        val label = pageSpecs.first { it.key == currentPage }.label
        title = "设置 · $label"
        updateNavSelection()
        findViewById<ScrollView>(R.id.svContent).post {
            findViewById<ScrollView>(R.id.svContent).scrollTo(0, 0)
        }
        Logx.d("设置页：切到「$label」")
    }

    private fun buildNav() {
        val nav = findViewById<LinearLayout>(R.id.llNav)
        nav.removeAllViews()
        navButtons.clear()
        nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(10), dp(12), dp(36))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                imageTintList = null
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@MainActivity).apply {
                text = "Banana_Dock"
                textSize = 20f
                setTextColor(0xFFF2F4F8.toInt())
                typeface = AppFont.bold(this@MainActivity)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
        })
        nav.addView(TextView(this).apply {
            text = "个性化设置"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
            setPadding(dp(28), 0, dp(12), dp(18))
        })
        for (item in pageSpecs) {
            if (item.key == SECTION_LOGS) {
                nav.addView(View(this).apply {
                    setBackgroundColor(0xFF3B414D.toInt())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(28), dp(4), dp(28), dp(16))
                })
            }
            val bar = View(this).apply {
                setBackgroundColor(0x00000000)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(16)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val label = TextView(this).apply {
                text = item.label
                textSize = 18f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nav_idle))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = dp(12)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(12), 0)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_settings_nav_idle)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
                ).apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                    bottomMargin = dp(12)
                }
                setOnClickListener { showPage(item.key) }
                addView(bar)
                addView(icon)
                addView(label)
            }
            nav.addView(row)
            navButtons[item.key] = NavRow(row, bar, icon, label)
        }
        nav.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                id = R.id.settingsNavStatus
                text = "●  Dock 未运行"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warn))
            })
            addView(TextView(this@MainActivity).apply {
                id = R.id.settingsNavVersion
                text = "v${Prefs.appVersion(this@MainActivity)}"
                textSize = 24f
                gravity = Gravity.END or Gravity.BOTTOM
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
                setPadding(0, dp(12), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(28), dp(24), dp(16))
        })
    }

    private fun updateNavSelection() {
        val accent = ContextCompat.getColor(this, R.color.settings_accent)
        val idle = ContextCompat.getColor(this, R.color.settings_secondary)
        for ((page, row) in navButtons) {
            val on = page == currentPage
            val color = if (on) accent else idle
            row.container.background = ContextCompat.getDrawable(this,
                if (on) R.drawable.bg_settings_nav_selected else R.drawable.bg_settings_nav_idle)
            row.bar.setBackgroundColor(if (on) accent else 0x00000000)
            row.icon.imageTintList = ColorStateList.valueOf(color)
            row.label.setTextColor(color)
            row.label.typeface = if (on) AppFont.bold(this) else AppFont.regular(this)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
