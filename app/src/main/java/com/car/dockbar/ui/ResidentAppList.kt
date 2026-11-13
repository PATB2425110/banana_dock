package com.car.dockbar.ui

internal data class ResidentAppItem(val pkg: String, val label: String, val system: Boolean)
internal data class ResidentAppChoice(val app: ResidentAppItem, val selected: Boolean)

/** 黑名单及应用选择器共用的名单展示规则。 */
internal object ResidentAppList {
    fun choices(apps: List<ResidentAppItem>, blacklist: Set<String>): List<ResidentAppChoice> =
        apps.map { ResidentAppChoice(it, it.pkg in blacklist) }

    fun blacklistedApps(
        apps: List<ResidentAppItem>,
        blacklist: Set<String>,
        searchQuery: String = ""
    ): List<ResidentAppItem> {
        val query = searchQuery.trim()
        return apps.filter { app ->
            app.pkg in blacklist && (
                query.isEmpty() || app.label.contains(query, ignoreCase = true) ||
                    app.pkg.contains(query, ignoreCase = true)
                )
        }
    }
}
