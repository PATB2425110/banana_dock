package com.car.dockbar.ui

/**
 * ★ v3.13.16：Dock 背景形态（**四选一互斥**）。
 *
 * - [SOLID]       纯色：整条一个颜色（白天纯白 / 夜间纯黑），不透明度由「底色透明度」滑杆控制
 * - [TRANSPARENT] 透明：完全不画底色，背景直接透出
 * - [GRADIENT]    渐变：整条高度连续爬升（顶部最透 → 底部最浓），默认值
 * - [BLUR]        模糊：壁纸做可调半径的盒式模糊（近似高斯），半透明铺底。不截屏。
 */
enum class BgMode(val key: String, val label: String) {
    SOLID("solid", "纯色"),
    TRANSPARENT("transparent", "透明"),
    GRADIENT("gradient", "渐变"),
    BLUR("blur", "模糊");

    companion object {
        fun of(key: String?): BgMode = values().firstOrNull { it.key == key } ?: GRADIENT
    }
}
