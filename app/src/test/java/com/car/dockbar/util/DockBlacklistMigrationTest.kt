package com.car.dockbar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DockBlacklistMigrationTest {
    // 历史 v3 完成后的隐藏集合独立固定，不能随新安装默认配置的调整而变化。
    private val historicalHiddenAfterV3 = setOf(
        "com.ximalaya.ting.android.car", "com.tencent.wecar", "com.bilibili.bilithings",
        "com.geely.notificationdemo", "com.baidu.netdisk.car", "com.thunder.carplay", "com.autoegg",
        "com.quark.browser", "com.quark", "com.tencent.mm", "com.ss.android.ugc.aweme",
        "com.smile.gifmaker", "com.xingin.xhs", "com.taobao.taobao", "com.jingdong.app.mall",
        "com.eg.android.AlipayGphone", "com.tencent.mtt", "com.android.browser", "com.eshare.miui.player",
        "com.qiyi.video", "com.qiyi.video.lite", "com.starschina", "cn.xuexi.android.plus",
        "com.tencent.wemeet.app", "com.tencent.docs", "com.alibaba.android.rimet",
        "com.cmbchina.cmbmb", "com.icbc", "com.chinamobile.contacts.im"
    )

    @Test
    fun freshInstallSeedsOnlyCurrentDefaultsAndPublishesMarkerWithTheList() {
        val memory = MemoryPreferences()
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(29, DockBlacklistDefaults.packages.size)
        assertSame(DockBlacklistDefaults.packages, DockBlacklistDefaults.packages)
        assertEquals(DockBlacklistDefaults.packages, blacklist(memory))
        assertFalse("com.geely.hvac" in blacklist(memory))
        assertFalse("com.autonavi.amapauto" in blacklist(memory))
        assertEquals(true, memory.values["dock_app_list_v4"])
        assertFalse(memory.values.containsKey("dock_app_list_v2"))
        assertFalse(memory.values.containsKey("dock_app_list_v3"))
        assertFalse(memory.values.containsKey("dock_always_show_apps"))
        assertFalse(memory.values.containsKey("dock_custom_hide_apps"))
        assertEquals(setOf("dock_hide_apps", "dock_app_list_v4"), memory.writes.single().keys)
    }

    @Test
    fun v1HiddenListRunsV2ThenV3BeforeConversionToV4() {
        val memory = MemoryPreferences(
            mapOf(
                "dock_always_show_apps" to "com.quark,com.geely.hvac,com.example.custom",
                "unrelated_setting" to "keep"
            )
        )
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(setOf("com.quark", "com.example.custom"), blacklist(memory))
        assertEquals(true, memory.values["dock_app_list_v2"])
        assertEquals(true, memory.values["dock_app_list_v3"])
        assertEquals(true, memory.values["dock_app_list_v4"])
        assertEquals("com.example.custom", memory.values["dock_custom_hide_apps"])
        assertEquals("keep", memory.values["unrelated_setting"])
        assertEquals(3, memory.writes.size)
    }

    @Test
    fun v2UpgradeAppliesHistoricalV3SeedAndPreservesCustomRules() {
        val memory = MemoryPreferences(
            mapOf(
                "dock_app_list_v2" to true,
                "dock_always_show_apps" to "com.quark",
                "dock_custom_hide_apps" to "com.geely.hvac,com.example.custom"
            )
        )
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(
            (historicalHiddenAfterV3 - "com.quark") + "com.example.custom",
            blacklist(memory)
        )
        assertEquals("com.example.custom", memory.values["dock_custom_hide_apps"])
        assertEquals(true, memory.values["dock_app_list_v3"])
        assertEquals(2, memory.writes.size)
    }

    @Test
    fun v3UpgradeKeepsUserDisabledDefaultShowAppAndAlwaysShowWinsConflicts() {
        val legacy = mapOf(
            "dock_app_list_v2" to true,
            "dock_app_list_v3" to true,
            "dock_always_show_apps" to "com.autonavi.amapauto,com.quark",
            "dock_custom_hide_apps" to "com.example.custom,com.quark"
        )
        val memory = MemoryPreferences(legacy)
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        val actual = blacklist(memory)
        assertEquals(58, actual.size)
        assertTrue("com.geely.hvac" in actual)
        assertTrue("com.example.custom" in actual)
        assertFalse("com.autonavi.amapauto" in actual)
        assertFalse("com.quark" in actual)
        legacy.forEach { (key, value) -> assertEquals(value, memory.values[key]) }
        assertEquals(setOf("dock_hide_apps", "dock_app_list_v4"), memory.writes.single().keys)
    }

    @Test
    fun legacyMarkersWithoutListsKeepHistoricalVersionSemantics() {
        val v2 = MemoryPreferences(mapOf("dock_app_list_v2" to true))
        DockBlacklistMigration.ensureMigrated(v2.prefs)
        assertEquals(historicalHiddenAfterV3, blacklist(v2))

        val v3 = MemoryPreferences(mapOf("dock_app_list_v2" to true, "dock_app_list_v3" to true))
        DockBlacklistMigration.ensureMigrated(v3.prefs)
        assertEquals(59, blacklist(v3).size)
        assertTrue("com.geely.hvac" in blacklist(v3))
    }

    @Test
    fun v3MarkerWithoutV2StillRunsV2BeforeSkippingV3() {
        val memory = MemoryPreferences(
            mapOf("dock_app_list_v3" to true, "dock_always_show_apps" to "com.quark")
        )
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(setOf("com.quark"), blacklist(memory))
        assertEquals(true, memory.values["dock_app_list_v2"])
    }

    @Test
    fun legacyCustomHideKeyAloneUsesTheExistingV2ThenV3Conversion() {
        val memory = MemoryPreferences(mapOf("dock_custom_hide_apps" to "com.example.custom"))
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(historicalHiddenAfterV3, blacklist(memory))
        assertEquals("", memory.values["dock_custom_hide_apps"])
        assertEquals(true, memory.values["dock_app_list_v2"])
        assertEquals(true, memory.values["dock_app_list_v3"])
    }

    @Test
    fun completedV4KeepsExplicitSelectionAndIgnoresStaleLegacyData() {
        val initial = mapOf(
            "dock_app_list_v4" to true,
            "dock_hide_apps" to "com.geely.hvac,com.example.custom",
            "dock_always_show_apps" to "com.geely.hvac",
            "dock_app_list_v2" to false
        )
        val memory = MemoryPreferences(initial)
        repeat(2) { DockBlacklistMigration.ensureMigrated(memory.prefs) }

        assertEquals(initial, memory.values)
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun completedV4NeverRepopulatesAnEmptyOrMissingBlacklist() {
        for (value in listOf("", null)) {
            val memory = MemoryPreferences(mapOf("dock_app_list_v4" to true))
            if (value != null) memory.values["dock_hide_apps"] = value
            val before = memory.values.toMap()
            DockBlacklistMigration.ensureMigrated(memory.prefs)
            assertEquals(before, memory.values)
            assertTrue(memory.writes.isEmpty())
        }
    }

    @Test
    fun repeatedMigrationDoesNotReapplyLegacyDataAfterUserChanges() {
        val memory = MemoryPreferences()
        DockBlacklistMigration.ensureMigrated(memory.prefs)
        memory.prefs.edit()
            .putString("dock_hide_apps", "com.example.custom")
            .putString("dock_custom_hide_apps", "com.example.stale")
            .apply()
        val writes = memory.writes.size
        repeat(2) { DockBlacklistMigration.ensureMigrated(memory.prefs) }

        assertEquals(setOf("com.example.custom"), blacklist(memory))
        assertEquals(writes, memory.writes.size)
    }

    @Test
    fun concurrentInitialReadsPublishOnlyOneMigration() {
        val memory = MemoryPreferences()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = (1..8).map {
                workers.submit { DockBlacklistMigration.ensureMigrated(memory.prefs) }
            }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        assertEquals(DockBlacklistDefaults.packages, blacklist(memory))
        assertEquals(1, memory.writes.size)
    }

    @Test
    fun emptyLegacyShowKeyIsNotTreatedAsAFreshInstall() {
        val memory = MemoryPreferences(mapOf("dock_always_show_apps" to ""))
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertTrue(blacklist(memory).isEmpty())
        assertEquals(true, memory.values["dock_app_list_v2"])
        assertEquals(true, memory.values["dock_app_list_v3"])
        assertEquals(3, memory.writes.size)
    }

    @Test
    fun emptyLegacyHideKeyStillRunsHistoricalMigration() {
        val memory = MemoryPreferences(mapOf("dock_custom_hide_apps" to ""))
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(historicalHiddenAfterV3, blacklist(memory))
        assertEquals(true, memory.values["dock_app_list_v2"])
        assertEquals(true, memory.values["dock_app_list_v3"])
    }

    @Test
    fun falseLegacyFlagsWithoutListKeysUseFreshInstallDefaults() {
        val memory = MemoryPreferences(mapOf("dock_app_list_v2" to false, "dock_app_list_v3" to false))
        DockBlacklistMigration.ensureMigrated(memory.prefs)

        assertEquals(DockBlacklistDefaults.packages, blacklist(memory))
        assertEquals(false, memory.values["dock_app_list_v2"])
        assertEquals(false, memory.values["dock_app_list_v3"])
        assertFalse(memory.values.containsKey("dock_always_show_apps"))
        assertEquals(setOf("dock_hide_apps", "dock_app_list_v4"), memory.writes.single().keys)
    }

    private fun blacklist(memory: MemoryPreferences): Set<String> =
        (memory.values["dock_hide_apps"] as String).split(",").filter { it.isNotBlank() }.toSet()
}
