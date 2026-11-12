package com.car.dockbar.dock

import kotlin.math.hypot

class GestureResolver(
    private val binding: GestureBinding,
    private val slopPx: Float,
    private val emit: (DockAction) -> Unit,
    private val onFingerSettled: () -> Unit,
) {
    private var pointerDown = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var sloppy = false

    fun onDown(x: Float, y: Float) {
        pointerDown = true
        longFired = false
        sloppy = false
        downX = x
        downY = y
    }

    fun onMove(x: Float, y: Float) {
        if (!pointerDown) return
        if (hypot(x - downX, y - downY) > slopPx) sloppy = true
    }

    fun onLongTimeout() {
        if (!pointerDown || sloppy || longFired || binding.longPress == null) return
        longFired = true
        emit(binding.longPress)
        onFingerSettled()
    }

    fun onUp(x: Float, y: Float) {
        if (!pointerDown) return
        onMove(x, y)
        pointerDown = false
        if (longFired || sloppy) return
        onFingerSettled()
        binding.tap?.let(emit)
    }

    fun onCancel() {
        pointerDown = false
        sloppy = true
    }
}
