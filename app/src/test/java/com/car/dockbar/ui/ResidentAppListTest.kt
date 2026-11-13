package com.car.dockbar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentAppListTest {
    private val systemApp = ResidentAppItem("com.system.maps", "系统导航", true)
    private val personalApp = ResidentAppItem("com.personal.music", "Music Player", false)
    private val otherApp = ResidentAppItem("com.other.video", "视频", false)
    private val installedApps = listOf(systemApp, personalApp, otherApp)

    @Test
    fun listContainsOnlyBlacklistedInstalledApps() {
        assertEquals(
            listOf(systemApp, personalApp),
            ResidentAppList.blacklistedApps(
                installedApps,
                setOf(systemApp.pkg, personalApp.pkg, "com.not.installed")
            )
        )
    }

    @Test
    fun emptyBlacklistDoesNotShowInstalledApps() {
        assertTrue(ResidentAppList.blacklistedApps(installedApps, emptySet()).isEmpty())
    }

    @Test
    fun removingLastAppLeavesAnEmptyListWithoutChangingTheCatalog() {
        val blacklist = mutableSetOf(personalApp.pkg)
        assertEquals(listOf(personalApp), ResidentAppList.blacklistedApps(installedApps, blacklist))

        blacklist.remove(personalApp.pkg)

        assertTrue(ResidentAppList.blacklistedApps(installedApps, blacklist).isEmpty())
        assertEquals(listOf(systemApp, personalApp, otherApp), installedApps)
    }

    @Test
    fun addingAnAppMakesItVisibleAndPreservesCatalogOrder() {
        val blacklist = mutableSetOf(personalApp.pkg)
        blacklist.add(systemApp.pkg)
        blacklist.add(personalApp.pkg)

        assertEquals(
            listOf(systemApp, personalApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist)
        )
    }

    @Test
    fun searchMatchesBlacklistedNamesAndPackagesIgnoringCaseAndWhitespace() {
        val blacklist = setOf(systemApp.pkg, personalApp.pkg)

        assertEquals(
            listOf(personalApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "  MUSIC  ")
        )
        assertEquals(
            listOf(systemApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "COM.SYSTEM")
        )
        assertEquals(
            listOf(systemApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "导航")
        )
    }

    @Test
    fun searchNeverBringsUnlistedAppsIntoTheBlacklist() {
        assertTrue(
            ResidentAppList.blacklistedApps(installedApps, setOf(systemApp.pkg), otherApp.pkg).isEmpty()
        )
    }

    @Test
    fun pickerIncludesAllInstalledAppsAndMarksOnlyBlacklistMembers() {
        val choices = ResidentAppList.choices(installedApps, setOf(systemApp.pkg, "com.not.installed"))

        assertEquals(installedApps, choices.map { it.app })
        assertEquals(listOf(true, false, false), choices.map { it.selected })
    }

    @Test
    fun removedSystemAppRemainsAvailableForAddingAgain() {
        val blacklist = mutableSetOf(systemApp.pkg)
        assertTrue(ResidentAppList.choices(installedApps, blacklist).first().selected)

        blacklist.remove(systemApp.pkg)

        val choices = ResidentAppList.choices(installedApps, blacklist)
        assertEquals(installedApps, choices.map { it.app })
        assertEquals(listOf(false, false, false), choices.map { it.selected })
    }
}
