package com.car.dockbar.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureResolverTest {
    private val tap = DockAction.GoHome
    private val double = DockAction.OpenRecents
    private val long = DockAction.OpenDockSettings

    @Test
    fun tapWithoutDoubleFiresOnUpAndDoesNotSchedule() {
        val h = harness(GestureBinding(tap = tap))
        h.down(0)
        h.up(20)
        assertEquals(listOf(tap), h.emitted)
        assertTrue(h.scheduleDelays.isEmpty())
    }

    @Test
    fun doubleBindingDelaysTheSingleTap() {
        val h = harness(GestureBinding(tap = tap, doubleTap = double))
        h.down(0)
        h.up(20)
        assertTrue(h.emitted.isEmpty())
        assertEquals(listOf(300L), h.scheduleDelays)
        h.flush(320)
        assertEquals(listOf(tap), h.emitted)
    }

    @Test
    fun secondTapWithinWindowCancelsSingleAndFiresDouble() {
        val h = harness(GestureBinding(tap = tap, doubleTap = double))
        h.down(0)
        h.up(20)
        h.down(100)
        assertTrue(h.scheduled == null)
        h.up(120)
        h.flush(500)
        assertEquals(listOf(double), h.emitted)
    }

    @Test
    fun secondTapAfterWindowStartsANewSingle() {
        val h = harness(GestureBinding(tap = tap, doubleTap = double))
        h.down(0)
        h.up(20)
        h.flush(320)
        h.down(400)
        h.up(420)
        assertEquals(listOf(tap), h.emitted)
        assertEquals(listOf(300L, 300L), h.scheduleDelays)
    }

    @Test
    fun longPressFiresOnceAndConsumesTheUp() {
        val h = harness(GestureBinding(tap = tap, longPress = long))
        h.down(0)
        h.now = 400
        h.resolver.onLongTimeout()
        assertEquals(listOf(long), h.emitted)
        assertEquals(1, h.settled)
        h.up(450)
        assertEquals(listOf(long), h.emitted)
        assertEquals(1, h.settled)
    }

    @Test
    fun longTimeoutWithoutBindingEmitsNothing() {
        val h = harness(GestureBinding(tap = tap))
        h.down(0)
        h.now = 400
        h.resolver.onLongTimeout()
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun movingPastSlopCancelsTapAndLongPress() {
        val h = harness(GestureBinding(tap = tap, longPress = long, doubleTap = double))
        h.down(0)
        h.up(20)
        h.down(50)
        h.move(50, 9f, 0f)
        h.now = 500
        h.resolver.onLongTimeout()
        h.up(520, 9f, 0f)
        h.flush(900)
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun cancelMatchesASloppyPress() {
        val h = harness(GestureBinding(tap = tap, longPress = long, doubleTap = double))
        h.down(0)
        h.up(20)
        val settledAfterFirstUp = h.settled
        h.down(50)
        h.resolver.onCancel()
        h.flush(900)
        h.up(60)
        assertEquals(settledAfterFirstUp, h.settled)
        assertTrue(h.emitted.isEmpty())
    }

    @Test
    fun longPressDoesNotCountAsTheSecondTapOfADouble() {
        val h = harness(GestureBinding(tap = tap, longPress = long, doubleTap = double))
        h.down(0)
        h.up(20)
        h.down(40)
        h.now = 440
        h.resolver.onLongTimeout()
        h.up(500)
        h.flush(900)
        assertEquals(listOf(long), h.emitted)
    }

    private fun harness(binding: GestureBinding) = Harness(binding)

    private class Harness(binding: GestureBinding) {
        var now = 0L
        var scheduled: (() -> Unit)? = null
        val scheduleDelays = mutableListOf<Long>()
        val emitted = mutableListOf<DockAction>()
        var settled = 0
        val resolver = GestureResolver(
            binding = binding,
            longPressMs = 400L,
            slopPx = 8f,
            clock = { now },
            schedule = { delay, block ->
                scheduled = block
                scheduleDelays += delay
            },
            cancelScheduled = { scheduled = null },
            emit = { emitted += it },
            onFingerSettled = { settled++ },
        )

        fun down(t: Long, x: Float = 0f, y: Float = 0f) {
            now = t
            resolver.onDown(x, y)
        }

        fun move(t: Long, x: Float, y: Float) {
            now = t
            resolver.onMove(x, y)
        }

        fun up(t: Long, x: Float = 0f, y: Float = 0f) {
            now = t
            resolver.onUp(x, y)
        }

        fun flush(at: Long) {
            now = at
            val block = scheduled
            scheduled = null
            block?.invoke()
        }
    }
}
