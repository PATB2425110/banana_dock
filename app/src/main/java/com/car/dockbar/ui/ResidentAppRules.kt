package com.car.dockbar.ui

/** Mirrors Prefs.isDockHideApp for grid state without changing any persisted rule. */
internal object ResidentAppRules {
    fun selected(packages: List<String>, always: Set<String>, defaultHide: Set<String>, customHide: Set<String>): Set<String> =
        packages.filterTo(LinkedHashSet()) { it in always || (it !in defaultHide && it !in customHide) }
}
