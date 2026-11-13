package com.car.dockbar.util

import android.content.SharedPreferences

internal const val DOCK_HIDE_APPS_KEY = "dock_hide_apps"

/** 负责黑名单的归一化读写；历史键、默认值和升级流程统一由迁移模块管理。 */
internal object DockBlacklistPreferences {
    fun read(prefs: SharedPreferences): Set<String> = synchronized(prefs) {
        DockBlacklistMigration.ensureMigrated(prefs)
        parse(prefs.getString(DOCK_HIDE_APPS_KEY, ""))
    }

    fun write(prefs: SharedPreferences, packages: Set<String>) = synchronized(prefs) {
        DockBlacklistMigration.ensureMigrated(prefs)
        val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.edit().putString(DOCK_HIDE_APPS_KEY, normalized.joinToString(",")).apply()
    }

    private fun parse(raw: String?): Set<String> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
