package com.car.dockbar.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Editor-only fixed long press. Does not change system settings or live Dock gestures. */
internal object DockEditorLongPress {
    const val TIMEOUT_MS = 300L

    fun attach(view: View, action: (Float, Float) -> Unit) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        var x = 0f
        var y = 0f
        var tracking = false
        var fired = false
        val trigger = Runnable {
            if (tracking && view.isAttachedToWindow && view.isEnabled) {
                fired = true
                view.isPressed = false
                action(x, y)
            }
        }
        view.setOnLongClickListener(null)
        view.isLongClickable = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(trigger)
                    x = event.x
                    y = event.y
                    tracking = true
                    fired = false
                    view.postDelayed(trigger, TIMEOUT_MS)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!fired && (kotlin.math.abs(event.x - x) > slop ||
                        kotlin.math.abs(event.y - y) > slop)) {
                        tracking = false
                        view.removeCallbacks(trigger)
                    }
                    fired
                }
                MotionEvent.ACTION_UP -> {
                    tracking = false
                    view.removeCallbacks(trigger)
                    fired
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    tracking = false
                    view.removeCallbacks(trigger)
                    false
                }
                else -> false
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                tracking = false
                v.removeCallbacks(trigger)
            }
        })
    }
}
