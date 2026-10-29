package com.car.dockbar.util

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ★ v3.13.0：Dock 底色渐变规格（**唯一实现**）。
 *
 * ## 为什么单独抽出来
 * 之前 `DockLayout.rebuildShader()` 和设置页的 `GradientPreviewView`
 * 各写了一份 LinearGradient，导致「配置页预览」和「实际 Dock」不一致
 * （v3.12.0 的平段逻辑就只改了一处）。现在两边都调这里，1:1 一致。
 *
 * ## 渐变形状（用户要求）
 * > 「渐变浓度改从最底部开始增加到最高度，而不是只变底部的浓度」
 *
 * 整条 Dock 高度**全部参与**爬升，没有平段：
 *  - 顶部 = 起始色（白天透明白 / 夜间透明黑）→ **最透**
 *  - 底部 = 结束色（白天 0xCC 白 / 夜间 0xE6 黑）→ **最浓**
 *
 * alpha 走一条**下凸曲线**（不是线性）：
 *  上半段 `alpha = midAlpha * (t/0.5)^1.8` → 爬得慢，顶部区域保持通透；
 *  下半段 `alpha = midAlpha + (endAlpha-midAlpha) * ((t-0.5)/0.5)^1.25` → 加速加浓。
 *  于是「整条都在变」的同时，浓色依然自然堆积在最下面，
 *  不会像 3 色均匀分布那样在 50% 高度就出现明显的一坨。
 */
object GradientSpec {

    /** 采样点数：越多曲线越平滑（24 段在 1080px 高度上肉眼已无折线感） */
    private const val SAMPLES = 24

    /** 上半段（通透区）爬升曲率：>1 = 起步慢 */
    private const val GAMMA_LOW = 1.8f

    /** 下半段（加浓区）爬升曲率：>1 = 收尾快 */
    private const val GAMMA_HIGH = 1.25f

    /**
     * 生成「顶部最透 → 底部最浓」的连续爬升色标数组。
     *
     * @param colors 原始色标 [起点, (中点), 终点]（长度 ≥ 2）
     * @return 长度 [SAMPLES] 的色标数组，配上等距 positions 即为整条爬升
     */
    fun verticalRampColors(colors: IntArray): IntArray {
        val n = colors.size
        val startA = Color.alpha(colors[0]).toFloat()
        val endA = Color.alpha(colors[n - 1]).toFloat()
        val midA = if (n >= 3) Color.alpha(colors[1]).toFloat() else (startA + endA) / 2f

        val out = IntArray(SAMPLES)
        for (i in 0 until SAMPLES) {
            val t = i.toFloat() / (SAMPLES - 1)
            val a = when {
                t <= 0.5f -> midA * (t / 0.5f).pow(GAMMA_LOW)
                else -> midA + (endA - midA) * ((t - 0.5f) / 0.5f).pow(GAMMA_HIGH)
            }
            val rgb = blendRgb(colors[0], colors[n - 1], t)
            out[i] = Color.argb(
                a.roundToInt().coerceIn(0, 255),
                Color.red(rgb), Color.green(rgb), Color.blue(rgb)
            )
        }
        return out
    }

    /** 等距 positions（配合 [verticalRampColors] 使用） */
    fun rampPositions(size: Int = SAMPLES): FloatArray =
        FloatArray(size) { it.toFloat() / (size - 1) }

    /** 两个 ARGB 之间按 t 线性混合 RGB（alpha 由调用方自己算） */
    private fun blendRgb(c0: Int, c1: Int, t: Float): Int {
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
