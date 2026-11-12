package com.car.dockbar.ui

import android.content.res.Configuration

/**
 * 供 MainActivity 双栏容器使用的有序普通页面注册表。
 * 各页面自行管理控件与生命周期；宿主只负责页面选择和事件分发。
 */
class SettingsPageRegistry(
    private val pages: List<SettingsPage>
) {
    private val byKey = pages.associateBy { it.key }
    private var current: SettingsPage? = null

    fun createAll() {
        pages.forEach { it.create() }
        pages.forEach { it.buildLayout() }
    }

    fun page(key: String): SettingsPage? = byKey[key]

    fun show(key: String): SettingsPage? {
        val next = byKey[key] ?: byKey[MainActivity.SECTION_OVERLAY] ?: return null
        current = next
        next.onShown()
        return next
    }

    fun onResume() {
        pages.forEach { it.onResume() }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        pages.forEach { it.onConfigurationChanged(newConfig) }
    }

    fun onDestroy() {
        pages.forEach { it.onDestroy() }
        current = null
    }
}
