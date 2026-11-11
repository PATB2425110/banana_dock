package com.car.dockbar.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureResolverTest {
    private val tap = DockAction.GoHome
    private val long = DockAction.OpenDockSettings

    @Test
    fun tapFiresOnUp() {
        val h = harness(GestureBinding(tap = tap))
        h.down()
        h.up()
        assertEquals(listOf(tap), h.emitted)
    }

    @Test
    fun longPressFiresOnceAndConsumesTheUp() {
        val h = harness(GestureBinding(tap = tap, longPress = long))
        h.down()
        h.resolver.onLongTimeout()
        assertEquals(listOf(long), h.emitted)
        assertEquals(1, h.settled)
        h.up()
        assertEquals(listOf(long), h.emitted)
        assertEquals(1, h.settled)
    }

    @Test
    fun longTimeoutWithoutBindingEmitsNothing() {
        val h = harness(GestureBinding(tap = tap))
        h.down()
        h.resolver.onLongTimeout()
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun movingPastSlopCancelsTapAndLongPress() {
        val h = harness(GestureBinding(tap = tap, longPress = long))
        h.down()
        h.move(9f, 0f)
        h.resolver.onLongTimeout()
        h.up(9f, 0f)
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun cancelMatchesASloppyPress() {
        val h = harness(GestureBinding(tap = tap, longPress = long))
        h.down()
        h.resolver.onCancel()
        h.up()
        assertEquals(0, h.settled)
        assertTrue(h.emitted.isEmpty())
    }

    private fun harness(binding: GestureBinding) = Harness(binding)

    private class Harness(binding: GestureBinding) {
        val emitted = mutableListOf<DockAction>()
        var settled = 0
        val resolver = GestureResolver(
            binding = binding,
            slopPx = 8f,
            emit = { emitted += it },
            onFingerSettled = { settled++ },
        )

        fun down(x: Float = 0f, y: Float = 0f) = resolver.onDown(x, y)

        fun move(x: Float, y: Float) = resolver.onMove(x, y)

        fun up(x: Float = 0f, y: Float = 0f) = resolver.onUp(x, y)
    }
}
