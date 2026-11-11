package com.car.dockbar.dock

import kotlin.math.hypot

class GestureResolver(
    private val binding: GestureBinding,
    private val longPressMs: Long,
    private val slopPx: Float,
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val emit: (DockAction) -> Unit,
    private val onFingerSettled: () -> Unit,
) {
    private var pointerDown = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var sloppy = false
    private var secondTap = false
    private var lastUpAt = -1L
    private var armedGeneration = -1
    private var nextGeneration = 0

    fun onDown(x: Float, y: Float) {
        disarm()
        pointerDown = true
        longFired = false
        sloppy = false
        downX = x
        downY = y
        val elapsed = if (lastUpAt < 0L) Long.MAX_VALUE else clock() - lastUpAt
        secondTap = binding.doubleWindowMs > 0L && elapsed < binding.doubleWindowMs
    }

    fun onMove(x: Float, y: Float) {
        if (!pointerDown) return
        if (hypot(x - downX, y - downY) > slopPx) sloppy = true
    }

    fun onLongTimeout() {
        if (!pointerDown || sloppy || longFired || binding.longPress == null) return
        longFired = true
        secondTap = false
        disarm()
        emit(binding.longPress)
        onFingerSettled()
    }

    fun onUp(x: Float, y: Float) {
        if (!pointerDown) return
        onMove(x, y)
        pointerDown = false
        if (longFired || sloppy) {
            secondTap = false
            if (sloppy) disarm()
            return
        }
        onFingerSettled()
        if (secondTap && binding.doubleTap != null) {
            secondTap = false
            lastUpAt = -1L
            disarm()
            emit(binding.doubleTap)
            return
        }
        if (binding.doubleTap != null) {
            lastUpAt = clock()
            val generation = ++nextGeneration
            armedGeneration = generation
            schedule(binding.doubleWindowMs) {
                if (armedGeneration != generation || pointerDown) return@schedule
                armedGeneration = -1
                binding.tap?.let(emit)
            }
            return
        }
        lastUpAt = -1L
        binding.tap?.let(emit)
    }

    fun onCancel() {
        if (!pointerDown) {
            disarm()
            return
        }
        pointerDown = false
        secondTap = false
        sloppy = true
        disarm()
    }

    private fun disarm() {
        armedGeneration = -1
        cancelScheduled()
    }
}
