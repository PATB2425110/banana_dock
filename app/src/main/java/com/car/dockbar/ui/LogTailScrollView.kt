package com.car.dockbar.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/** Nested log console: manual history scrolling pauses tail-following until the bottom is reached. */
class LogTailScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private var following = true
    private var touching = false
    private var lastY = 0f

    init { isNestedScrollingEnabled = true; updateDescription() }

    fun shouldFollowTail(): Boolean = following && !touching

    fun resumeFollowing() {
        following = true
        updateDescription()
        post { if (!touching) scrollTo(0, getChildAt(0)?.height ?: 0) }
    }

    private fun atBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        val tolerance = (8 * resources.displayMetrics.density).toInt()
        return scrollY + height - paddingBottom >= child.bottom - tolerance
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(canScrollVertically(-1) || canScrollVertically(1))
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                following = false
                updateDescription()
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(canScrollVertically(-1) || canScrollVertically(1))
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - lastY
                val canMove = if (delta > 0) canScrollVertically(-1) else canScrollVertically(1)
                parent?.requestDisallowInterceptTouchEvent(canMove)
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                following = atBottom()
                updateDescription()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!touching) following = atBottom()
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = if (following) "运行日志：跟随最新" else "运行日志：查看历史"
    }
}
