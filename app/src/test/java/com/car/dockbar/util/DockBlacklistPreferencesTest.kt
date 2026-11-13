package com.car.dockbar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DockBlacklistPreferencesTest {
    @Test
    fun firstReadUsesActualFreshInstallDefaultsWithoutLegacySeeding() {
        val memory = MemoryPreferences()
        assertEquals(DockBlacklistDefaults.packages, DockBlacklistPreferences.read(memory.prefs))
        assertEquals(29, DockBlacklistPreferences.read(memory.prefs).size)
        assertFalse(memory.values.containsKey("dock_always_show_apps"))
        assertEquals(setOf("dock_hide_apps", "dock_app_list_v4"), memory.writes.single().keys)
    }

    @Test
    fun firstReadPerformsRealLegacyMigrationAndRetainsLegacyKeys() {
        val legacy = mapOf(
            "dock_app_list_v2" to true,
            "dock_app_list_v3" to true,
            "dock_always_show_apps" to "com.autonavi.amapauto",
            "dock_custom_hide_apps" to "com.example.custom"
        )
        val memory = MemoryPreferences(legacy)
        val actual = DockBlacklistPreferences.read(memory.prefs)

        assertTrue("com.geely.hvac" in actual)
        assertTrue("com.example.custom" in actual)
        assertFalse("com.autonavi.amapauto" in actual)
        legacy.forEach { (key, value) -> assertEquals(value, memory.values[key]) }
    }

    @Test
    fun firstWriteMigratesLegacyStateThenSavesNormalizedUserSelection() {
        val memory = MemoryPreferences(mapOf("dock_app_list_v2" to true, "dock_app_list_v3" to true))
        DockBlacklistPreferences.write(memory.prefs, setOf(" com.example.custom ", "", "  "))

        val migrated = (memory.writes.first()["dock_hide_apps"] as String).split(",").toSet()
        assertEquals(59, migrated.size)
        assertTrue("com.geely.hvac" in migrated)
        assertEquals(true, memory.writes.first()["dock_app_list_v4"])
        assertEquals("com.example.custom", memory.values["dock_hide_apps"])
        assertEquals(setOf("com.example.custom"), DockBlacklistPreferences.read(memory.prefs))
    }

    @Test
    fun firstWriteOnFreshInstallWinsOverTheDefaultSeed() {
        val memory = MemoryPreferences()
        DockBlacklistPreferences.write(memory.prefs, setOf(" app.a ", "app.a", ""))

        assertEquals(setOf("app.a"), DockBlacklistPreferences.read(memory.prefs))
        assertFalse(memory.values.containsKey("dock_app_list_v3"))
        assertEquals(true, memory.values["dock_app_list_v4"])
    }

    @Test
    fun removingDefaultAndAddingCustomAppSurvivesRepeatedReads() {
        val memory = MemoryPreferences()
        val selected = DockBlacklistPreferences.read(memory.prefs).toMutableSet()
        selected.remove("com.quark")
        selected.add("com.example.custom")
        DockBlacklistPreferences.write(memory.prefs, selected)

        repeat(2) {
            assertEquals(
                (DockBlacklistDefaults.packages - "com.quark") + "com.example.custom",
                DockBlacklistPreferences.read(memory.prefs)
            )
        }
    }

    @Test
    fun emptyUserSelectionIsNeverRepopulated() {
        val memory = MemoryPreferences()
        DockBlacklistPreferences.write(memory.prefs, emptySet())
        val writes = memory.writes.size

        repeat(2) { assertTrue(DockBlacklistPreferences.read(memory.prefs).isEmpty()) }
        assertEquals(writes, memory.writes.size)
    }

    @Test
    fun completedV4IgnoresLegacyAlwaysShowAndPreservesStoredChoice() {
        val initial = mapOf(
            "dock_app_list_v4" to true,
            "dock_hide_apps" to "com.geely.hvac,com.example.custom",
            "dock_always_show_apps" to "com.geely.hvac",
            "dock_app_list_v2" to false
        )
        val memory = MemoryPreferences(initial)
        assertEquals(setOf("com.geely.hvac", "com.example.custom"), DockBlacklistPreferences.read(memory.prefs))
        assertEquals(initial, memory.values)
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun commaSeparatedValuesAreTrimmedAndDeduplicated() {
        val memory = MemoryPreferences(
            mapOf("dock_app_list_v4" to true, "dock_hide_apps" to " app.a, ,app.b,app.a, ")
        )
        assertEquals(setOf("app.a", "app.b"), DockBlacklistPreferences.read(memory.prefs))
    }

    @Test
    fun prefsDefaultCompatibilityEntryForwardsToTheSameCurrentList() {
        assertSame(DockBlacklistDefaults.apps, Prefs.DEFAULT_DOCK_HIDE)
        assertEquals(DockBlacklistDefaults.apps.map { it.first }.toSet(), DockBlacklistDefaults.packages)
        assertEquals("夸克网盘", Prefs.dockHideLabel("com.quark"))
        assertEquals("unknown.package", Prefs.dockHideLabel("unknown.package"))
    }

    @Test
    fun concurrentFirstReadAndWriteInitializeOnceAndKeepExplicitSelection() {
        val memory = MemoryPreferences()
        val selected = setOf("com.example.explicit")
        val ready = CountDownLatch(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            // 先持有同一个存储锁，让首次读写均到达边界后再放行。
            val (reader, writer) = synchronized(memory.prefs) {
                val read = workers.submit<Set<String>> {
                    ready.countDown()
                    DockBlacklistPreferences.read(memory.prefs)
                }
                val write = workers.submit {
                    ready.countDown()
                    DockBlacklistPreferences.write(memory.prefs, selected)
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertFalse(read.isDone)
                assertFalse(write.isDone)
                read to write
            }
            writer.get(5, TimeUnit.SECONDS)
            val observed = reader.get(5, TimeUnit.SECONDS)
            assertTrue(observed == DockBlacklistDefaults.packages || observed == selected)
            assertEquals(selected, DockBlacklistPreferences.read(memory.prefs))
            assertEquals(1, memory.writes.count { it["dock_app_list_v4"] == true })
        } finally {
            workers.shutdownNow()
        }
    }
}
