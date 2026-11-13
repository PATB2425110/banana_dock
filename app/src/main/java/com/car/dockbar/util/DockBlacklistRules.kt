package com.car.dockbar.util

internal object DockBlacklistRules {
    fun migrate(
        defaultHide: Set<String>,
        alwaysShow: Set<String>,
        customHide: Set<String>
    ): Set<String> = (defaultHide + customHide) - alwaysShow

    fun shouldHide(pkg: String, blacklist: Set<String>): Boolean =
        pkg.isNotBlank() && pkg in blacklist
}
