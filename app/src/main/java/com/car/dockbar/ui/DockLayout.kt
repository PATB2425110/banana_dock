package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/**
 * Dock 栏根容器。
 *
 * ★ v3.13.8 布局模型（用户指定规格）：**固定项 + 唯一弹性项**。
 *  - 固定项：1 槽 98px、3 槽温度组合 290px、D08 风速 250px —— 恒定不伸缩；
 *  - 弹性项（音乐卡）：宽度 = 总宽 - 全部固定项，钳到 295~500px。
 * 条目宽度由 [Lp.fixedWidthPx] / [Lp.flexMinPx]+[Lp.flexMaxPx] 决定，
 * [Lp.span] 退化为纯元数据（设置页与配置迁移还在读）。
 *
 * 同时它自己绘制：
 *  - 圆角半透明底色（透明度 / 颜色 / 圆角都可在配置页实时调）
 *  - 分组竖分隔线（画在条目边界上，不占布局宽度）
 */
class DockLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /**
     * ★ v3.13.8 布局模型：**固定项 + 唯一弹性项（音乐卡）**。
     *
     * @param fixedWidthPx >0 = 该条目锁死固定宽度（px）。
     *   1 槽项 98、3 槽温度组合 290、D08 风速 250 —— 这些宽度**不随音乐卡伸缩**。
     * @param flexMinPx / flexMaxPx >0 = 弹性区间：该条目宽度 = 总宽 - 全部固定项，
     *   再钳到 [flexMinPx, flexMaxPx]。当前只有音乐卡用（295~500px）。
     *   音乐卡钳到上限后多出的空间、或保住下限后缺的空间，
     *   由各固定项**等比**分摊/让出（主场景 1920 宽时恰好 =0，固定项纹丝不动）。
     * @param span 不再参与宽度计算，仅作元数据保留（设置页/迁移逻辑还在读它）。
     */
    class Lp(
        var span: Int,
        var separatorBefore: Boolean,
        var fixedWidthPx: Int = 0,
        var flexMinPx: Int = 0,
        var flexMaxPx: Int = 0
    ) : ViewGroup.LayoutParams(0, 0)

    /**
     * ★ v3.13.0：磨砂玻璃样式**已整体移除**。
     *
     * 用户要求：「取消这个模糊玻璃，改成纯白/纯黑，浓度调节变成透明度调节」。
     * 玻璃底色 / 磨砂纹理 / sheen 高光 / 内阴影 / 顶部高光弧全部删除，
     * 底色只剩两种形态：
     *  - 纯色（[bgColor]）：白天纯白、夜间纯黑，不透明度由「底色透明度」滑杆控制
     *  - 渐变（[bgColors]）：整条高度连续爬升，**顶部最透 → 底部最浓**
     */
    var bgColor: Int = 0x59000000
        set(v) { field = v; invalidate() }

    /**
     * 渐变底色（>= 2 个 ARGB，按顺序插值）。
     * null = 不用渐变，退回纯色 [bgColor]。
     *
     * ★ v3.13.0：色标由 util/GradientSpec 生成 —— **整条高度连续爬升**，
     *   顶部最透 → 底部最浓（不再有 v3.12.0 的底部 30% 平段）。
     */
    var bgColors: IntArray? = null
        set(v) { field = v; rebuildShader() }

    /** 渐变方向：0=上→下 1=下→上 2=左→右 3=右→左 */
    var gradientAngle: Int = 0
        set(v) { field = v; rebuildShader() }

    /** 圆角半径 px */
    var cornerRadius: Float = 0f
        set(v) { field = v; invalidate() }

    var separatorColor: Int = 0x33FFFFFF
        set(v) { field = v; invalidate() }

    /** 分隔线上下留白占高度的比例 */
    var separatorInsetRatio: Float = 0.28f

    var showSeparators: Boolean = true
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val rect = RectF()

    /** 当前生效的渐变着色器，null 表示纯色模式 */
    private var bgShader: Shader? = null

    /**
     * ★ v3.13.16：**毛玻璃底图**（PixelCopy 抓屏 + 降采样模糊的结果）。
     * null = 不画毛玻璃层（其它三种形态）。
     * 用 BitmapShader + drawRoundRect 绘制，自带圆角裁剪。
     */
    var blurBitmap: android.graphics.Bitmap? = null
        set(v) { field = v; blurShader = v?.let { android.graphics.BitmapShader(it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }; invalidate() }

    private var blurShader: android.graphics.Shader? = null
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }

    private var lefts = IntArray(0)
    private var rights = IntArray(0)

    init {
        setWillNotDraw(false)
    }

    /**
     * 添加一个条目。
     * @param fixedWidthPx >0 = 锁死固定宽度（px）。
     * @param flexMinPx / flexMaxPx >0 = 弹性区间（音乐卡 295~500）。
     */
    fun addItem(
        v: View,
        span: Int,
        separatorBefore: Boolean = false,
        fixedWidthPx: Int = 0,
        flexMinPx: Int = 0,
        flexMaxPx: Int = 0
    ) {
        addView(v, Lp(span, separatorBefore, fixedWidthPx, flexMinPx, flexMaxPx))
    }

    /** 尺寸变化后渐变要重新构造（LinearGradient 绑定的是固定坐标） */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    /** 根据 [bgColors] + [gradientAngle] + 当前尺寸重建着色器 */
    private fun rebuildShader() {
        val colors = bgColors
        if (colors == null || colors.size < 2 || width <= 0 || height <= 0) {
            bgShader = null
            bgPaint.shader = null
            invalidate()
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()

        // ★ v3.13.0：渐变改为**整条高度连续爬升**（顶部最透 → 底部最浓）。
        //   v3.12.0 是「顶部 70% 平段 + 底部 30% 爬升」，
        //   用户反馈「而不是只变底部的浓度」→ 现在上下整条都在变。
        //   具体的 alpha 曲线见 util/GradientSpec.kt（设置页预览复用同一份实现）。
        val vertical = gradientAngle == 0 || gradientAngle == 1
        val expColors: IntArray
        val positions: FloatArray?
        if (vertical) {
            // 上→下：起点(最透)在顶部，终点(最浓)在底部
            val ramp = if (gradientAngle == 0) {
                com.car.dockbar.util.GradientSpec.verticalRampColors(colors)
            } else {
                // 下→上：把色标反过来
                com.car.dockbar.util.GradientSpec.verticalRampColors(colors.reversedArray())
            }
            expColors = ramp
            positions = com.car.dockbar.util.GradientSpec.rampPositions(ramp.size)
        } else {
            // 左右方向：等距插值即可
            expColors = colors
            positions = null
        }

        val shader = when (gradientAngle) {
            1 -> LinearGradient(
                0f, h, 0f, 0f, expColors, positions,
                Shader.TileMode.CLAMP
            )
            2 -> LinearGradient(
                0f, 0f, w, 0f, expColors, positions,
                Shader.TileMode.CLAMP
            )
            3 -> LinearGradient(
                w, 0f, 0f, 0f, expColors, positions,
                Shader.TileMode.CLAMP
            )
            else -> LinearGradient(
                0f, 0f, 0f, h, expColors, positions,
                Shader.TileMode.CLAMP
            )
        }
        bgShader = shader
        bgPaint.shader = shader
        invalidate()
    }

    /**
     * 反色切换后刷新所有子控件颜色（图标着色、数值文字、音乐卡文字）和分隔线颜色。
     * @param light true = 亮背景（黑字），false = 暗背景（白字）
     *
     * ★ v3.3：改成**递归**遍历。原来只处理直接子 View，
     *   而温度/风速组合按钮（「− 值 +」）是把 DockIconView 装在一个 LinearLayout 里
     *   再加进来的 → 里面的 ± 图标永远刷不到，白天模式下还是白色 → 看不见。
     */
    fun applyPalette(light: Boolean) {
        separatorColor = if (light) 0x33000000 else 0x33FFFFFF
        applyPaletteDeep(this, light)
    }

    private fun applyPaletteDeep(v: android.view.View, light: Boolean) {
        when (v) {
            is DockIconView -> v.refreshTint()
            is DockValueView -> v.refreshColors()
            is DockMusicView -> v.refreshColors()
            is android.view.ViewGroup ->
                for (i in 0 until v.childCount) applyPaletteDeep(v.getChildAt(i), light)
        }
    }

    /**
     * 底色的「平均不透明度」0f..1f。
     * 渐变模式取所有色标 alpha 均值；纯色模式取 bgColor alpha。
     * 供自动反色（亮/暗字）判断使用。
     */
    fun effectiveBgAlpha(): Float {
        val colors = bgColors
        if (bgShader != null && colors != null && colors.isNotEmpty()) {
            var sum = 0
            for (c in colors) sum += Color.alpha(c)
            return sum.toFloat() / colors.size / 255f
        }
        return Color.alpha(bgColor) / 255f
    }

    /**
     * 底色「平均颜色」RGB（忽略 alpha）。渐变模式取所有色标均值。
     * 用于判断背景偏亮还是偏暗（决定黑字还是白字）。
     */
    fun effectiveBgRgb(): Int {
        val colors = bgColors
        if (bgShader != null && colors != null && colors.isNotEmpty()) {
            var r = 0; var g = 0; var b = 0
            for (c in colors) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
            val n = colors.size
            return Color.rgb(r / n, g / n, b / n)
        }
        return Color.rgb(Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)

        if (lefts.size < childCount) {
            lefts = IntArray(childCount)
            rights = IntArray(childCount)
        }

        // ★ v3.13.8：**固定项 + 唯一弹性项**布局模型（用户指定规格）：
        //   固定项 = 1 槽 98px / 3 槽组合 290px / D08 250px，宽度恒定不随音乐卡变；
        //   音乐卡 = 总宽 - 全部固定项，钳到 295~500px。
        //   主场景（1920 宽 / 默认 12 条目）：剩余 = 306px 落在区间内，
        //   固定项 scale=1 纹丝不动。
        //   音乐卡顶到 500（删了条目）多出的空间、或保住 295（加了条目）缺的
        //   空间，由各固定项**等比**放大/让出，总宽恒等于 w、不留空。
        var fixedTotal = 0
        var flexIdx = -1
        var flexMin = 0
        var flexMax = 0
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as Lp
            if (lp.flexMaxPx > 0 && flexIdx < 0) {
                flexIdx = i; flexMin = lp.flexMinPx; flexMax = lp.flexMaxPx
            } else {
                fixedTotal += lp.fixedWidthPx.coerceAtLeast(0)
            }
        }

        // 音乐卡宽度 = 总宽 - 固定项总宽，钳到 [min, max]
        val avail = (w - fixedTotal).coerceAtLeast(0)
        val musicW = if (flexIdx >= 0) {
            avail.coerceIn(flexMin, flexMax)
        } else avail

        // 剩余/缺口 → 固定项等比分摊（scale=1 = 完全固定不动）
        val leftover = w - fixedTotal - musicW
        val scale = if (flexIdx >= 0 && fixedTotal > 0) {
            (fixedTotal + leftover).toFloat() / fixedTotal
        } else 1f

        // 用浮点累计游标，避免逐项取整造成累计误差（最后一项自动补齐尾差）
        var cursor = 0f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as Lp
            val segW = if (i == flexIdx) musicW.toFloat()
            else (lp.fixedWidthPx * scale).coerceAtLeast(0f)
            val l = cursor.roundToInt()
            cursor += segW
            val r = cursor.roundToInt()
            lefts[i] = l
            rights[i] = r
            val cw = (r - l).coerceAtLeast(0)
            c.measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = if (i < lefts.size) lefts[i] else 0
            val right = if (i < rights.size) rights[i] else width
            c.layout(left, 0, right, height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ⓿ ★ v3.13.16：**毛玻璃底图**（真实抓屏模糊结果）
        //   绘制在最底层，随后再叠半透明色调层 → 这才是毛玻璃的正确层次。
        val bs = blurShader
        if (bs != null) {
            val cr = cornerRadius.coerceIn(0f, height / 2f)
            blurPaint.shader = bs
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            if (cr > 0f) canvas.drawRoundRect(rect, cr, cr, blurPaint)
            else canvas.drawRect(rect, blurPaint)
            blurPaint.shader = null
        }

        // ① 底色：★ v3.13.0 玻璃样式已移除，现在只有「渐变 / 纯色」两条路
        val gradientVisible = bgShader != null &&
            (bgColors?.any { Color.alpha(it) > 0 } == true)
        if (gradientVisible || Color.alpha(bgColor) > 0) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            if (gradientVisible) {
                // 渐变模式下 shader 自带颜色，bgPaint.color 仅作兜底
                bgPaint.color = Color.WHITE
            } else {
                bgPaint.color = bgColor
            }
            val cr = cornerRadius.coerceIn(0f, height / 2f)
            if (cr > 0f) canvas.drawRoundRect(rect, cr, cr, bgPaint)
            else canvas.drawRect(rect, bgPaint)
        }
        // ② 分隔线：★ 每两个相邻条目之间都画（不再按 separatorBefore 分组边界）
        if (!showSeparators) return
        sepPaint.color = separatorColor
        val inset = height * separatorInsetRatio
        for (i in 1 until childCount) {
            if (i >= lefts.size) continue
            val x = lefts[i].toFloat()
            canvas.drawLine(x, inset, x, height - inset, sepPaint)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = Lp(1, false)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = Lp(1, false)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = Lp(1, false)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is Lp
}
