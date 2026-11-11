package com.car.dockbar.dock

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

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
        var single: Runnable? = null
        var tracking = false
        var longFired = false
        lateinit var resolver: GestureResolver
        val longRunnable = Runnable { resolver.onLongTimeout() }
        resolver = GestureResolver(
            binding = binding,
            longPressMs = longPressMs,
            slopPx = slop,
            clock = { SystemClock.elapsedRealtime() },
            schedule = { delay, block ->
                single?.let { view.removeCallbacks(it) }
                val posted = Runnable { block() }
                single = posted
                view.postDelayed(posted, delay)
            },
            cancelScheduled = {
                single?.let { view.removeCallbacks(it) }
                single = null
            },
            emit = { action ->
                if (action == binding.longPress && tracking) longFired = true
                onAction(action)
            },
            onFingerSettled = onSettled,
        )
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(longRunnable)
                    longFired = false
                    tracking = true
                    resolver.onDown(event.x, event.y)
                    view.postDelayed(longRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    resolver.onMove(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return@setOnTouchListener false
                    view.removeCallbacks(longRunnable)
                    resolver.onUp(event.x, event.y)
                    val consumed = tracking || longFired
                    tracking = false
                    consumed
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!tracking) return@setOnTouchListener false
                    view.removeCallbacks(longRunnable)
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
                v.removeCallbacks(longRunnable)
                single?.let { v.removeCallbacks(it) }
                resolver.onCancel()
            }
        })
    }
}
