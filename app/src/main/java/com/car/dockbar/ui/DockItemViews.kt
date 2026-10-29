package com.car.dockbar.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.car.dockbar.R
import com.car.dockbar.util.Prefs
import kotlin.math.roundToInt

/** 通用图标按钮：一个居中的 ImageView，带点击水波纹和激活态着色 */
class DockIconView @JvmOverloads constructor(
    context: Context,
    @DrawableRes iconRes: Int = 0,
    private val iconPx: Int = 26,
    /**
     * true = 保留矢量图自带的颜色（如座椅通风里的蓝色标识），只用透明度表现激活态；
     * false = 整体染成白色 / 强调色
     */
    private val preserveColors: Boolean = false
) : FrameLayout(context) {

    companion object {
        /** 前景色（文字 / 图标基色），反色时切到黑色 */
        var fgColor: Int = Color.WHITE
        /** 次级前景（歌手等），反色时切到黑色半透明 */
        var fgColorDim: Int = 0xB3FFFFFF.toInt()
        /** OFF / 无数据 灰显色，反色时切到黑色半透明 */
        var offColor: Int = 0x99FFFFFF.toInt()
        /** 自绘图标里"未激活"的灰显色 */
        var dimColor: Int = 0x45FFFFFF
        var accentColor: Int = 0xFF4EA1FF.toInt()

        // ── ★ v3.3：弹窗专用前景色 ──────────────────────────────
        // 所有弹窗（风向 / 温度滚轮 / 空调控制 / 座椅）的底色恒为深色 bg_card，
        // **不随 Dock 栏的反色变化**。之前它们直接用 fgColor，
        // 白天模式（亮背景）时 fgColor 变黑 → 黑字掉在深色弹窗上看不见。
        // 这三个是固定值，写死白色系，不参与 setPalette()。
        var popupFg: Int = Color.WHITE
        var popupFgDim: Int = 0xB3FFFFFF.toInt()
        var popupOff: Int = 0x99FFFFFF.toInt()
    }

    val image = ImageView(context)
    private var active = false
    /** true = 图标用外部传入的 Drawable 自绘（如风向/座椅/循环的复合图标），不再上色 */
    private var custom = false

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        image.layoutParams = LayoutParams(iconPx, iconPx, Gravity.CENTER)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(image)
        if (iconRes != 0) image.setImageResource(iconRes)
        background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false
        applyTint()
    }

    fun setIcon(@DrawableRes res: Int) {
        custom = false
        image.setImageResource(res)
        applyTint()
    }

    /**
     * ★ v3.13.7：运行时改图标像素尺寸（播放键跟随槽宽自适应用）。
     * 传入的 px 是「屏幕上真正看到的图标边长」，直接改 ImageView 的布局参数。
     */
    fun setIconSizePx(px: Int) {
        val lp = image.layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            image.layoutParams = lp
        }
    }

    /** 用自绘 Drawable 作为图标（颜色由 Drawable 自己控制，不再染白/强调色） */
    fun setDynamic(d: android.graphics.drawable.Drawable) {
        custom = true
        // ★ v3.1：先清掉颜色滤镜。
        //   init 里 applyTint() 已经给 ImageView 挂上白色 ColorFilter，
        //   而 setImageDrawable 不会自动清滤镜 → 用户自己的 App 图标被整张罩成白色。
        //   （系统 Dock 的第三方 App 图标是原色显示的。）
        image.clearColorFilter()
        image.setImageDrawable(d)
    }

    fun setActive(a: Boolean) {
        active = a
        applyTint()
    }

    /** 反色切换后重新上色（非自绘图标按当前前景色 / 强调色重新着色） */
    fun refreshTint() = applyTint()

    private fun applyTint() {
        if (custom) return
        if (preserveColors) {
            image.clearColorFilter()
            image.alpha = if (active) 1f else 0.72f
        } else {
            image.alpha = 1f
            image.setColorFilter(if (active) accentColor else fgColor)
        }
    }
}

/**
 * Dock 上的数值显示：大号主值 + 可选小号副值 + 可选小号单位。
 *
 * 温度：22 .5 °
 * 风速：3 档   /   自动：3 A（A 用强调色）
 * 没数据时显示 "--"
 */
class DockValueView @JvmOverloads constructor(
    context: Context,
    private val mainPx: Float = 25f,
    private val subPx: Float = 16f,
    private val unitPx: Float = 12f
) : LinearLayout(context) {

    private val tvMain = TextView(context)
    private val tvSub = TextView(context)
    private val tvUnit = TextView(context)

    /** 上次渲染的灰显 / 强调色状态，换色时按它重刷颜色 */
    private var lastMuted = false
    private var lastUnitAccent = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false

        listOf(tvMain to mainPx, tvSub to subPx, tvUnit to unitPx).forEach { (tv, size) ->
            tv.includeFontPadding = false
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            tv.setTextColor(DockIconView.fgColor)
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.gravity = Gravity.CENTER
            addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        // 副值下沉成下标，单位符号略微上浮
        tvSub.setPadding(0, (mainPx * 0.30f).toInt(), 0, 0)
        tvUnit.setPadding(1, (mainPx * 0.06f).toInt(), 0, 0)
        tvUnit.alpha = 0.85f

        renderEmpty()
    }

    fun render(main: String, sub: String?, unit: String?, unitAccent: Boolean = false, muted: Boolean = false) {
        lastMuted = muted
        lastUnitAccent = unitAccent
        tvMain.text = main
        setPart(tvSub, sub)
        setPart(tvUnit, unit)
        val mutedColor = if (muted) DockIconView.offColor else DockIconView.fgColor
        tvSub.setTextColor(mutedColor)
        tvSub.alpha = if (muted) 0.45f else 1f
        tvUnit.setTextColor(
            when {
                muted -> DockIconView.offColor
                unitAccent -> DockIconView.accentColor
                else -> DockIconView.fgColor
            }
        )
        tvUnit.alpha = when {
            muted -> 0.45f
            unitAccent -> 1f
            else -> 0.85f
        }
        // 关闭状态整体灰显，包括温度的小数和单位。
        tvMain.alpha = if (muted) 0.45f else 1f
        tvMain.setTextColor(mutedColor)
    }

    /** 反色切换后按当前内容重刷文字颜色，不改内容 */
    fun refreshColors() {
        val mutedColor = if (lastMuted) DockIconView.offColor else DockIconView.fgColor
        val mutedAlpha = if (lastMuted) 0.45f else 1f
        tvMain.setTextColor(mutedColor)
        tvMain.alpha = mutedAlpha
        tvSub.setTextColor(mutedColor)
        tvSub.alpha = mutedAlpha
        tvUnit.setTextColor(
            when {
                lastMuted -> DockIconView.offColor
                lastUnitAccent -> DockIconView.accentColor
                else -> DockIconView.fgColor
            }
        )
        tvUnit.alpha = when {
            lastMuted -> 0.45f
            lastUnitAccent -> 1f
            else -> 0.85f
        }
    }

    private fun setPart(tv: TextView, text: String?) {
        if (text.isNullOrEmpty()) tv.visibility = View.GONE
        else {
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    fun renderEmpty() {
        tvMain.text = "--"
        tvSub.visibility = View.GONE
        tvUnit.visibility = View.GONE
    }

    /** 温度：22.5°；达到极值显示 HI/LO；空调关闭时保留温度并整体灰显 */
    fun renderTemp(temp: Float?, muted: Boolean = false) {
        if (temp == null) {
            // 没有可恢复的历史温度时用未知占位符，不把「无数据」误显示成空调 OFF。
            render("--", null, null, muted = true)
            return
        }
        val max = Prefs.tempMaxC(context)
        val min = Prefs.tempMinC(context)
        if (temp >= max) {
            render("HI", null, null, unitAccent = !muted, muted = muted)
            return
        }
        if (temp <= min) {
            render("LO", null, null, unitAccent = !muted, muted = muted)
            return
        }
        val whole = kotlin.math.floor(temp).toInt()
        val frac = Math.round((temp - whole) * 10)
        render(whole.toString(), if (frac == 0) null else ".$frac", "°", muted = muted)
    }

    /** 风速：AUTO 档显示「AUTO」；手动显示「n 档」；0 档（关）显示 OFF */
    /**
     * 风速显示。
     *
     * ★ v1.4.85：AUTO 模式下按 [DockValueView] 的设计注释显示「**档位 + A**」
     *   （原文：「风速：3 档 / 自动：3 A（A 用强调色）」）——
     *   大号主值 = 当前 AUTO 风道档位（1..5），蓝色小字 A = AUTO 标记。
     *   之前写成 `render("AUTO", null, null)`，只显示 AUTO 字样、看不到是第几档
     *   （用户反馈「AUTO 图标旁边应该加一个档位显示」）。
     *   读不到档位（level<=0）时才退回纯 "AUTO"。
     */
    fun renderFan(level: Int, auto: Boolean) {
        if (auto) {
            if (level > 0) {
                render(level.toString(), null, "A", unitAccent = true)
            } else {
                render("AUTO", null, null, unitAccent = true)
            }
            return
        }
        if (level <= 0) {
            render("OFF", null, null, muted = true)
            return
        }
        render(level.toString(), null, "档")
    }
}

/**
 * 音乐信息卡片：封面 + 歌名 + 歌手（仅 2 行文字）+ 播放/暂停
 */
class DockMusicView @JvmOverloads constructor(
    context: Context,
    private val barHeightPx: Int = 40,
    private val textScale: Float = 1f,
    private val iconScale: Float = 1f
) : LinearLayout(context) {

    val album = ImageView(context)
    val noteOverlay = ImageView(context)
    val tvTitle = TextView(context)
    val tvArtist = TextView(context)

    /** ★ v3.13.11：当前生效的字号缓存（避免每次 onMeasure 都 setTextSize 触发 requestLayout 死循环） */
    private var curTitlePx = -1f
    private var curArtistPx = -1f
    // ★ v3.13.2：用户反馈「放大后还是很小」→ 定位到根因不是按钮容器，
    //   而是 ic_pause / ic_play 矢量图自带大片空白边距（56×56 viewport 里
    //   图形只占 35% 宽），FIT_CENTER 按整个 viewport 缩放 → 放大倍数被空白吃掉。
    //   已把两个 drawable 的 viewport 裁到图形边界（图形铺满画布），
    //   现在这里的 px 就是**屏幕上真正看到的像素高**。
    //   18px 基准 × 1.8 倍 = 32px 实际可见高度（之前 1.8 倍时只有约 18px 可见）。
    val btnPlay = DockIconView(context, R.drawable.ic_pause, (18 * 1.8f * iconScale).toInt())

    // 专辑图：原 0.80× 缩到 0.7×（即 0.56× 栏高），左移腾出空间给歌曲信息
    private val albumSize = (barHeightPx * 0.80f * 0.7f).toInt().coerceAtLeast(8)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_item_press)
        isClickable = true
        setPadding((4 * textScale).toInt(), 0, (10 * textScale).toInt(), 0)

        // 封面（占位是紫粉渐变 + 音符）
        val albumFrame = FrameLayout(context)
        albumFrame.background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_album)
        albumFrame.addView(
            album, FrameLayout.LayoutParams(albumSize, albumSize, Gravity.CENTER)
                .apply { album.scaleType = ImageView.ScaleType.CENTER_CROP }
        )
        albumFrame.addView(
            noteOverlay, FrameLayout.LayoutParams(
                (albumSize * 0.55f).toInt(),
                (albumSize * 0.55f).toInt(),
                Gravity.CENTER
            )
        )
        noteOverlay.setImageResource(R.drawable.ic_music_note)
        noteOverlay.setColorFilter(DockIconView.fgColor)
        addView(albumFrame, LayoutParams(albumSize, albumSize).apply {
            marginEnd = (9 * iconScale).toInt()
        })

        // 文字列
        val textCol = LinearLayout(context)
        textCol.orientation = VERTICAL
        textCol.gravity = Gravity.CENTER_VERTICAL

        tvTitle.includeFontPadding = false
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * textScale)
        tvTitle.setTextColor(DockIconView.fgColor)
        tvTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tvTitle.maxLines = 1
        tvTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        tvTitle.text = ""

        tvArtist.includeFontPadding = false
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * textScale)
        tvArtist.setTextColor(DockIconView.fgColorDim)
        tvArtist.maxLines = 1
        tvArtist.ellipsize = android.text.TextUtils.TruncateAt.END
        tvArtist.text = ""

        textCol.addView(tvTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textCol.addView(
            tvArtist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * textScale).toInt()
            }
        )
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // 播放/暂停
        // ★ v3.13.3：用户反馈「按钮宽度太窄，容易点到旁边的音乐卡片」
        //   → 把**点击热区**加宽到「半个 Dock 槽位」。
        //   音乐卡占 3 槽，所以半个槽 = 卡片宽度的 1/6（默认 1920/20 槽时约 48px）。
        //   这里 width 先置 0，实际宽度在 onMeasure/onLayout 里按卡片宽度动态算，
        //   这样改 Dock 总宽或音乐卡 span 都不用改代码。
        addView(btnPlay, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            marginStart = (6 * iconScale).toInt()
        })

        renderEmpty()
    }

    /**
     * ★ v3.13.8：播放键**随音乐卡宽度线性伸缩**（用户指定规格）：
     *   卡宽 295px（下限）→ 图标 32px / 热区 70px
     *   卡宽 500px（上限）→ 图标 50px / 热区 100px
     *   中间线性插值；图标另兜一层「不被卡片高度裁掉」的保护。
     * 热区宽度始终 ≥ 图标 + 12px 余量。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cardW = MeasureSpec.getSize(widthMeasureSpec)
        val cardH = MeasureSpec.getSize(heightMeasureSpec)
        val iconPx = lerp(cardW, PLAY_ICON_MIN_PX, PLAY_ICON_MAX_PX)
            .coerceAtMost((cardH - 12).coerceAtLeast(20))
        btnPlay.setIconSizePx(iconPx)
        val hotW = lerp(cardW, PLAY_HOT_MIN_PX, PLAY_HOT_MAX_PX)
            .coerceAtLeast(iconPx + 12)
        // 先把宽度写进 LayoutParams 再交给 LinearLayout 测量，
        // 这样布局/边距都由 LinearLayout 正常处理（保留 marginStart）。
        btnPlay.layoutParams = btnPlay.layoutParams.apply { width = hotW }
        // ★ v3.13.11：歌名 / 歌手字号随音乐卡宽度自动增加（用户指定）
        //   歌名 295px→14px、500px→20px；歌手 295px→11px、500px→14px（线性）
        //   ※ setTextSize 每次调用都会 requestLayout，必须「值变了才设」，
        //     否则在 onMeasure 里会陷入 measure → requestLayout → measure 死循环。
        val titlePx = lerp(cardW, TITLE_MIN_PX, TITLE_MAX_PX) * textScale
        if (curTitlePx != titlePx) {
            curTitlePx = titlePx
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx)
        }
        val artistPx = lerp(cardW, ARTIST_MIN_PX, ARTIST_MAX_PX) * textScale
        if (curArtistPx != artistPx) {
            curArtistPx = artistPx
            tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, artistPx)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 卡宽 [cardW] 在 [CARD_MIN_WIDTH_PX, CARD_MAX_WIDTH_PX] 区间线性插值到 [vMin, vMax] */
    private fun lerp(cardW: Int, vMin: Int, vMax: Int): Int {
        val t = ((cardW - CARD_MIN_WIDTH_PX).toFloat() /
            (CARD_MAX_WIDTH_PX - CARD_MIN_WIDTH_PX)).coerceIn(0f, 1f)
        return (vMin + t * (vMax - vMin)).roundToInt()
    }

    fun renderEmpty() {
        album.visibility = View.GONE
        noteOverlay.visibility = View.VISIBLE
        tvTitle.text = "暂无播放"
        tvArtist.text = "点此打开播放器"
        btnPlay.visibility = View.INVISIBLE
    }

    companion object {
        /** ★ v3.13.9：播放键自适应规格（px），用户指定 ——
         *  卡宽 295 → 图标 32 / 热区 70；卡宽 500 → 图标 40 / 热区 100，中间线性
         *  （v3.13.8 时图标上限是 50，v3.13.9 改 40） */
        const val CARD_MIN_WIDTH_PX = 295
        const val CARD_MAX_WIDTH_PX = 500
        const val PLAY_ICON_MIN_PX = 32
        const val PLAY_ICON_MAX_PX = 40
        const val PLAY_HOT_MIN_PX = 70
        const val PLAY_HOT_MAX_PX = 100

        /** ★ v3.13.11：歌名 / 歌手字号随卡宽线性伸缩（px，用户指定） */
        const val TITLE_MIN_PX = 14
        const val TITLE_MAX_PX = 20
        const val ARTIST_MIN_PX = 11
        const val ARTIST_MAX_PX = 14
    }

    /** 反色切换后重刷歌名 / 歌手 / 音符占位 / 播放键颜色，不改内容 */
    fun refreshColors() {
        tvTitle.setTextColor(DockIconView.fgColor)
        tvArtist.setTextColor(DockIconView.fgColorDim)
        noteOverlay.setColorFilter(DockIconView.fgColor)
        btnPlay.refreshTint()
    }

    fun render(
        title: String,
        artist: String,
        art: android.graphics.Bitmap?,
        playing: Boolean
    ) {
        if (art != null) {
            album.setImageBitmap(art)
            album.visibility = View.VISIBLE
            noteOverlay.visibility = View.GONE
        } else {
            album.setImageDrawable(null)
            album.visibility = View.GONE
            noteOverlay.visibility = View.VISIBLE
        }
        tvTitle.text = title.ifBlank { "未知曲目" }
        tvArtist.text = artist.ifBlank { "—" }
        btnPlay.visibility = View.VISIBLE
        btnPlay.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }
}
