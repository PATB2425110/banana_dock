package com.car.dockbar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockBlacklistRulesTest {
    @Test
    fun migrateKeepsDefaultAndCustomHiddenAppsExceptAlwaysShownApps() {
        assertEquals(
            setOf("com.example.defaultB", "com.example.custom"),
            DockBlacklistRules.migrate(
                defaultHide = setOf("com.example.defaultA", "com.example.defaultB"),
                alwaysShow = setOf("com.example.defaultA"),
                customHide = setOf("com.example.custom")
            )
        )
    }

    @Test
    fun migrateUsesDefaultHideForFreshInstall() {
        assertEquals(
            setOf("com.example.defaultA", "com.example.defaultB"),
            DockBlacklistRules.migrate(
                defaultHide = setOf("com.example.defaultA", "com.example.defaultB"),
                alwaysShow = emptySet(),
                customHide = emptySet()
            )
        )
    }

    @Test
    fun migrateAlwaysShowWinsOnLegacyConflict() {
        assertEquals(
            emptySet<String>(),
            DockBlacklistRules.migrate(
                defaultHide = setOf("com.example.app"),
                alwaysShow = setOf("com.example.app"),
                customHide = setOf("com.example.app")
            )
        )
    }

    @Test
    fun blankPackageIsNeverHidden() {
        assertFalse(DockBlacklistRules.shouldHide("", setOf("")))
        assertFalse(DockBlacklistRules.shouldHide("   ", setOf("   ")))
    }

    @Test
    fun nonBlacklistedPackageIsShown() {
        assertFalse(
            DockBlacklistRules.shouldHide(
                "com.example.shown",
                setOf("com.example.hidden")
            )
        )
    }

    @Test
    fun blacklistedPackageIsHidden() {
        assertTrue(
            DockBlacklistRules.shouldHide(
                "com.example.hidden",
                setOf("com.example.hidden")
            )
        )
    }
}
