package com.car.dockbar.dock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.car.dockbar.model.DockEntry

class GestureMapTest {
    private val base = mapOf(
        HitTargets.ROOT to GestureBinding(
            tap = DockAction.GoHome,
        )
    )

    @Test
    fun nullPatchKeepsBase() {
        assertEquals(base, GestureMaps.merge(base, null))
    }

    @Test
    fun longPressOverrideKeepsOtherSlots() {
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"root":{"longPress":{"type":"openRecents"}}}""")
        )
        val merged = GestureMaps.merge(base, patch)[HitTargets.ROOT]!!
        assertEquals(DockAction.GoHome, merged.tap)
        assertEquals(DockAction.OpenRecents, merged.longPress)
    }

    @Test
    fun storedDoubleTapIsIgnored() {
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"root":{"doubleTap":{"type":"openRecents"},"longPress":{"type":"goHome"}}}""")
        )
        val merged = GestureMaps.merge(base, patch)[HitTargets.ROOT]!!
        assertEquals(DockAction.GoHome, merged.tap)
        assertEquals(DockAction.GoHome, merged.longPress)
    }

    @Test
    fun unknownActionTypeKeepsDefaultSlot() {
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"root":{"tap":{"type":"nope"}}}""")
        )
        val merged = GestureMaps.merge(base, patch)[HitTargets.ROOT]!!
        assertEquals(DockAction.GoHome, merged.tap)
    }

    @Test
    fun unknownTargetIsIgnored() {
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"nope":{"tap":{"type":"goHome"}}}""")
        )
        assertEquals(base, GestureMaps.merge(base, patch))
    }

    @Test
    fun nullJsonIsNullPatch() {
        assertNull(GestureMaps.parsePatch(null))
    }

    @Test
    fun oldEntryJsonHasNoGesturePatch() {
        val entry = DockEntry.fromJson(JSONObject("""{"id":"b_home","kind":"builtin","action":"home","label":"返回桌面","span":1}"""))
        assertNull(entry.gestures)
        assertEquals("home", entry.action)
    }

    @Test
    fun malformedGesturesKeepTheEntry() {
        val entry = DockEntry.fromJson(JSONObject("""{"id":"b_home","kind":"builtin","action":"home","label":"返回桌面","span":1,"gestures":"nope"}"""))
        assertNull(entry.gestures)
        assertEquals("返回桌面", entry.label)
    }

    @Test
    fun gesturePatchRoundTrips() {
        val raw = JSONObject(
            """{"id":"b_home","kind":"builtin","action":"home","label":"返回桌面","span":1,"gestures":{"root":{"tap":null,"longPress":{"type":"openRecents"}}}}"""
        )
        val entry = DockEntry.fromJson(raw)
        val root = entry.gestures!!.byTarget.getValue(HitTargets.ROOT)
        assertTrue(root.getValue(Gesture.TAP) is ActionSlot.Clear)
        assertEquals(DockAction.OpenRecents, (root.getValue(Gesture.LONG_PRESS) as ActionSlot.Set).action)
        val again = DockEntry.fromJson(JSONObject(entry.toJson().toString()))
        assertEquals(entry.gestures, again.gestures)
    }

    @Test
    fun absentPatchIsOmittedFromJson() {
        val entry = DockEntry("b_home", "builtin", "home", "返回桌面")
        assertFalse(entry.toJson().has("gestures"))
    }
}
