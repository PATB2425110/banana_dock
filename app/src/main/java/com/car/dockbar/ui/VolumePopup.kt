package com.car.dockbar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R
import com.car.dockbar.media.CabinVolume
import com.car.dockbar.util.Logx

/**
 * 音量面板。窗口规范和空调控制弹窗一致：贴着触发图标、点外面关闭、无关闭按钮、超时收起。
 * 媒体、导航各一条竖向音量条，条上显示当前数值，上下是 + / −。
 * 整体按 1.8 倍放大。
 */
class VolumePopup(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var content: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (content == null) return
            render()
            main.postDelayed(this, 500L)
        }
    }

    private val bars = LinkedHashMap<CabinVolume.Channel, LevelBar>()

    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean
        get() = content != null

    fun show(anchor: View? = null) {
        main.post {
            if (content != null) {
                resetTimer()
                return@post
            }
            if (DockPopupChrome.shouldSuppressShow()) return@post
            runCatching { buildAndShow(anchor) }
                .onFailure {
                    Logx.e("音量面板：添加失败", it)
                    content = null
                }
        }
    }

    fun hide() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        val box = content ?: return
        box.animate().alpha(0f).setDuration(180L).withEndAction {
            runCatching { wm?.removeViewImmediate(box) }
                .onFailure { Logx.e("音量面板：移除失败", it) }
            if (content === box) content = null
            onDismissed?.invoke()
        }.start()
    }

    fun destroy() {
        main.removeCallbacks(dismissRunnable)
        main.removeCallbacks(refreshRunnable)
        val box = content
        content = null
        if (box != null) runCatching { wm?.removeViewImmediate(box) }
    }

    private fun buildAndShow(anchor: View?) {
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bars.clear()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dock_popup_card)
            val pad = dp(16)
            setPadding(pad, dp(12), pad, dp(12))
        }
        card.addView(TextView(ctx).apply {
            text = "音量"
            setTextColor(DockIconView.popupFg)
            textSize = sp(16f)
            typeface = AppFont.bold(ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(column(CabinVolume.MEDIA), colParams())
        row.addView(column(CabinVolume.NAVI), colParams())
        card.addView(row)

        val p = DockPopupChrome.anchoredParams(ctx, anchor, card)
        content = card
        card.setOnTouchListener { _, ev -> DockPopupChrome.onOutsideTouch(ev) { hide() } }
        card.alpha = 0f
        wm?.addView(card, p)
        card.animate().alpha(1f).setDuration(180L).start()
        render()
        main.postDelayed(refreshRunnable, 500L)
        resetTimer()
        Logx.d("音量面板：显示（媒体 group=0，导航 group=1）")
    }

    private fun column(channel: CabinVolume.Channel): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(ctx).apply {
            text = channel.title
            setTextColor(DockIconView.popupFgDim)
            textSize = sp(13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        })
        box.addView(stepBtn("+") {
            CabinVolume.step(ctx, channel, 1)
            render()
        })
        val bar = LevelBar(ctx)
        bar.onSeek = { shown ->
            bar.value = shown
            CabinVolume.write(ctx, channel, shown)
            resetTimer()
        }
        bars[channel] = bar
        box.addView(bar, LinearLayout.LayoutParams(dp(56), dp(148)).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        box.addView(stepBtn("−") {
            CabinVolume.step(ctx, channel, -1)
            render()
        })
        return box
    }

    private fun stepBtn(label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(DockIconView.popupFg)
        textSize = sp(22f)
        typeface = AppFont.bold(ctx)
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_item_press)
        minWidth = dp(56)
        minHeight = dp(40)
        isClickable = true
        setOnClickListener {
            onClick()
            resetTimer()
        }
    }

    private fun render() {
        for ((channel, bar) in bars) {
            if (bar.dragging) continue
            val level = CabinVolume.read(ctx, channel)
            bar.max = level.max
            bar.value = level.value
        }
    }

    private fun resetTimer() {
        main.removeCallbacks(dismissRunnable)
        main.postDelayed(dismissRunnable, 6000L)
    }

    private fun colParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }

    /** 1.8 倍。车机 1080 高放得下，不再按屏幕收缩。 */
    private val scale = 1.8f

    private fun dp(v: Int): Int = (v * scale * ctx.resources.displayMetrics.density).toInt()

    private fun sp(v: Float): Float = v * scale

    private inner class LevelBar(context: Context) : View(context) {
        var onSeek: ((Int) -> Unit)? = null
        var dragging = false

        init {
            isClickable = true
        }

        var max: Int = 1
            set(v) {
                field = v.coerceAtLeast(1)
                invalidate()
            }
        var value: Int = 0
            set(v) {
                field = v
                invalidate()
            }

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DockIconView.popupFg
            textAlign = Paint.Align.CENTER
            typeface = AppFont.bold(context)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = dp(8).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            val span = (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
            if (span > 0f) {
                fill.color = DockIconView.accentColor
                val top = height * (1f - span)
                rect.set(0f, top, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(rect, r, r, fill)
            }
            num.textSize = dp(16).toFloat()
            val y = height / 2f - (num.descent() + num.ascent()) / 2f
            canvas.drawText(value.toString(), width / 2f, y, num)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    seekTo(event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seekTo(event.y)
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun seekTo(y: Float) {
            val h = height.coerceAtLeast(1)
            val span = (1f - y / h).coerceIn(0f, 1f)
            val shown = (span * max + 0.5f).toInt().coerceIn(0, max)
            if (shown == value && dragging) return
            value = shown
            onSeek?.invoke(shown)
        }
    }
}
