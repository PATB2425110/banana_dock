package com.car.dockbar.dock

import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

object GestureInput {
    fun attach(
        view: View,
        binding: GestureBinding,
        onAction: (DockAction) -> Unit,
        onSettled: () -> Unit,
    ) {
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.isLongClickable = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var tracking = false
        var longFired = false
        var pressed = false
        var downX = 0f
        var downY = 0f
        var armClickSound = false
        fun setPressed(down: Boolean, x: Float = 0f, y: Float = 0f) {
            if (down) {
                view.drawableHotspotChanged(x, y)
                view.isPressed = true
                pressed = true
            } else if (pressed) {
                view.isPressed = false
                pressed = false
            }
        }
        val resolver = GestureResolver(
            binding = binding,
            slopPx = slop,
            emit = { action ->
                if (action == binding.longPress && tracking) longFired = true
                if (armClickSound && action == binding.tap) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                }
                onAction(action)
            },
            onFingerSettled = onSettled,
        )
        val longRunnable = Runnable { resolver.onLongTimeout() }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(longRunnable)
                    longFired = false
                    tracking = true
                    downX = event.x
                    downY = event.y
                    setPressed(true, event.x, event.y)
                    resolver.onDown(event.x, event.y)
                    view.postDelayed(longRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    if (pressed && hypot(event.x - downX, event.y - downY) > slop) {
                        setPressed(false)
                    }
                    resolver.onMove(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return@setOnTouchListener false
                    view.removeCallbacks(longRunnable)
                    setPressed(false)
                    armClickSound = true
                    resolver.onUp(event.x, event.y)
                    armClickSound = false
                    val consumed = tracking || longFired
                    tracking = false
                    consumed
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!tracking) return@setOnTouchListener false
                    view.removeCallbacks(longRunnable)
                    setPressed(false)
                    resolver.onCancel()
                    tracking = false
                    true
                }
                else -> false
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                tracking = false
                setPressed(false)
                v.removeCallbacks(longRunnable)
                resolver.onCancel()
            }
        })
    }
}
