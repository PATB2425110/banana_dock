package com.car.dockbar.ui

/**
 * ★ v3.13.16：Dock 背景形态（**四选一互斥**）。
 *
 * - [SOLID]       纯色：整条一个颜色（白天纯白 / 夜间纯黑），不透明度由「底色透明度」滑杆控制
 * - [TRANSPARENT] 透明：完全不画底色，背景直接透出
 * - [GRADIENT]    渐变：整条高度连续爬升（顶部最透 → 底部最浓），默认值
 * - [BLUR]        毛玻璃：PixelCopy 抓取 Dock 区域真实像素 → 降采样模糊 → 作为底图
 *
 * ★ 毛玻璃是**实验特性**：抓屏依赖公开 API PixelCopy（API 26+），
 *   失败或崩溃会自动退回 [GRADIENT]（见 DockOverlayService 的崩溃保护）。
 */
enum class BgMode(val key: String, val label: String) {
    SOLID("solid", "纯色"),
    TRANSPARENT("transparent", "透明"),
    GRADIENT("gradient", "渐变"),
    BLUR("blur", "毛玻璃");

    companion object {
        fun of(key: String?): BgMode = values().firstOrNull { it.key == key } ?: GRADIENT
    }
}
