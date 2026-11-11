package com.car.dockbar.dock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureMapTest {
    private val base = mapOf(
        HitTargets.ROOT to GestureBinding(
            tap = DockAction.GoHome,
            doubleTap = DockAction.OpenRecents,
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
        assertEquals(DockAction.OpenRecents, merged.doubleTap)
        assertEquals(DockAction.OpenRecents, merged.longPress)
    }

    @Test
    fun nullDoubleTapClearsItAndDropsTheWindow() {
        val patch = GestureMaps.parsePatch(
            JSONObject("""{"root":{"doubleTap":null}}""")
        )
        val merged = GestureMaps.merge(base, patch)[HitTargets.ROOT]!!
        assertNull(merged.doubleTap)
        assertEquals(DockAction.GoHome, merged.tap)
        assertEquals(0L, merged.doubleWindowMs)
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
}
