package com.car.dockbar.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.car.dockbar.R
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.media.CabinVolume
import com.car.dockbar.model.Builtin
import com.car.dockbar.model.DockEntry
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController
import kotlin.math.roundToInt

internal data class DockEditorDrag(val entry: DockEntry, val fromPreview: Boolean)

/** Bitmap of the real Dock Views, rendered offscreen. No touch is ever sent to those Views. */
internal class DockEditorPreviewView(context: Context) : View(context) {
    var onPreviewLongPress: ((Int, View.DragShadowBuilder) -> Unit)? = null
    var onHover: ((DockEditorDrag, Boolean) -> Unit)? = null
    var onDrop: ((DockEditorDrag, Int?) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private var bitmap: Bitmap? = null
    private var itemRects = emptyList<Rect>()
    private var entries = emptyList<DockEntry>()
    private var gap: Int? = null
    private val density = resources.displayMetrics.density
    private var candidateFocus: Rect? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Dock 编辑预览：长按排序，拖出松手移除"
        DockEditorLongPress.attach(this) { x, y ->
            val index = itemAt(x, y)
            if (index >= 0) {
                parent?.requestDisallowInterceptTouchEvent(true)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onPreviewLongPress?.invoke(index, itemShadow(index))
            }
        }
    }

    fun render(items: List<DockEntry>, nativeWidth: Int? = null) {
        entries = items.toList()
        gap = null
        val ctx = context.applicationContext
        val width = nativeWidth ?: (Prefs.dockWidthPx(ctx).takeIf { it > 0 }?.coerceIn(320, 4096) ?: 1920)
        val height = Prefs.dockHeightPx(ctx).coerceIn(40, 512)
        val layout = DockLayout(ctx).apply {
            cornerRadius = Prefs.cornerRadiusPx(ctx).toFloat()
            showSeparators = Prefs.showSeparators(ctx)
            when (Prefs.bgMode(ctx)) {
                BgMode.SOLID -> bgColor = Prefs.solidBgColor(ctx)
                BgMode.TRANSPARENT -> bgColor = Color.TRANSPARENT
                BgMode.GRADIENT -> { bgColor = Color.TRANSPARENT; bgColors = Prefs.currentGradientColors(ctx) }
                BgMode.BLUR -> {
                    bgColor = Color.TRANSPARENT
                    background = GrayOverlayDrawable(Prefs.overlayGrayLevel(ctx),
                        Prefs.overlayTransparency(ctx), cornerRadius)
                }
            }
        }
        val factory = DockEntryViewFactory(ctx, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
        val state = VehicleController.state
        // Match the live Dock's palette without changing its global foreground colors.
        val darkIcons = Color.red(DockIconView.fgColor) < 128
        items.forEach { entry ->
            val built = factory.create(entry)
            val value = built.valueView ?: built.view as? DockValueView
            when (built.bind) {
                BindKind.TEMP_DRIVER -> value?.renderTemp(state.driverTempC ?: Prefs.lastDriverTempC(ctx), muted = !state.acOn)
                BindKind.TEMP_PASSENGER -> value?.renderTemp(state.passengerTempC ?: Prefs.lastPassengerTempC(ctx), muted = !state.acOn)
                BindKind.FAN -> value?.renderFan(state.fanLevel, state.fanAuto)
                BindKind.VOLUME -> (built.view as? VolumeDockButton)?.setLevel(CabinVolume.read(ctx, CabinVolume.MEDIA).value)
                BindKind.RECIRC -> (built.view as? DockIconView)?.setIcon(
                    if (state.recircInside) R.drawable.ic_ac_cycle_inner else R.drawable.ic_ac_cycle_outer)
                BindKind.AIR -> (built.view as? DockIconView)?.setIcon(when (state.airDirection) {
                    1 -> R.drawable.ic_blowing_face
                    2 -> R.drawable.ic_blowing_foot
                    3 -> R.drawable.ic_blowing_facefoot
                    4 -> R.drawable.ic_blowing_window
                    5 -> R.drawable.ic_blowing_face_and_window
                    6 -> R.drawable.ic_blowing_foot_and_window
                    7 -> R.drawable.ic_blowing_all
                    else -> R.drawable.ic_blowing_off
                })
                BindKind.DEFROST_FRONT -> (built.view as? DockIconView)?.setActive(state.defrostFront)
                BindKind.DEFROST_REAR -> (built.view as? DockIconView)?.setActive(state.defrostRear)
                BindKind.SEAT_HEAT -> (built.view as? DockIconView)?.apply {
                    setIcon(when (state.seatHeat) {
                        1 -> if (darkIcons) R.drawable.ic_seat_hot_01_dark else R.drawable.ic_seat_hot_01
                        2 -> if (darkIcons) R.drawable.ic_seat_hot_02_dark else R.drawable.ic_seat_hot_02
                        3 -> if (darkIcons) R.drawable.ic_seat_hot_03_dark else R.drawable.ic_seat_hot_03
                        else -> if (darkIcons) R.drawable.ic_seat_hot_off_dark else R.drawable.ic_seat_hot_off
                    })
                    setActive(state.seatHeat > 0)
                }
                BindKind.STEERING_HEAT -> (built.view as? DockIconView)?.apply {
                    setIcon(when (state.steeringHeat) {
                        1 -> if (darkIcons) R.drawable.ic_sw_heat_01_dark else R.drawable.ic_sw_heat_01
                        2 -> if (darkIcons) R.drawable.ic_sw_heat_02_dark else R.drawable.ic_sw_heat_02
                        3 -> if (darkIcons) R.drawable.ic_sw_heat_03_dark else R.drawable.ic_sw_heat_03
                        else -> if (darkIcons) R.drawable.ic_sw_heat_off_dark else R.drawable.ic_sw_heat_off
                    })
                    setActive(state.steeringHeat > 0)
                }
                BindKind.VENT -> (built.view as? DockIconView)?.apply {
                    setIcon(when (state.seatVent) {
                        1 -> if (darkIcons) R.drawable.ic_seat_feng_01_dark else R.drawable.ic_seat_feng_01
                        2 -> if (darkIcons) R.drawable.ic_seat_feng_02_dark else R.drawable.ic_seat_feng_02
                        3 -> if (darkIcons) R.drawable.ic_seat_feng_03_dark else R.drawable.ic_seat_feng_03
                        else -> if (darkIcons) R.drawable.ic_seat_feng_off_dark else R.drawable.ic_seat_feng_off
                    })
                    setActive(state.seatVent > 0)
                }
                BindKind.MUSIC -> (built.view as? DockMusicView)?.apply {
                    val info = MediaBridge.info
                    if (info.hasSession) render(info.title, info.artist, info.art, info.isPlaying) else renderEmpty()
                }
                else -> Unit
            }
            stripInteractions(built.view)
            layout.addItem(built.view, built.span, built.separator, built.fixedWidthPx,
                built.flexMinPx, built.flexMaxPx)
        }
        layout.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout.layout(0, 0, width, height)
        candidateFocus = if (items.size == 1 && layout.getChildAt(0) is DockIconView) {
            val icon = (layout.getChildAt(0) as DockIconView).image
            Rect(icon.left, icon.top, icon.right, icon.bottom).apply { intersect(0, 0, width, height) }
        } else null
        itemRects = (0 until layout.childCount).map { i ->
            val child = layout.getChildAt(i)
            Rect(child.left, child.top, child.right, child.bottom)
        }
        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(next)
        if (nativeWidth != null) canvas.drawColor(
            if (Color.red(DockIconView.fgColor) < 128) 0xFFCED1D6.toInt() else 0xFF171C25.toInt())
        layout.draw(canvas)
        // Old bitmaps are released by GC rather than recycled while RenderThread may still use them.
        bitmap = next
        updateTarget()
        invalidate()
    }

    /** Same factory, palette and state binding as the editing strip, at a compact display size. */
    fun candidateBitmap(entry: DockEntry): Bitmap {
        val nativeWidth = when (Builtin.of(entry.action)) {
            Builtin.MUSIC -> 336
            Builtin.FAN_COMBO -> DockEntryViewFactory.FAN_COMBO_FIXED_WIDTH_PX
            else -> DockEntryViewFactory.fixedWidthFor(entry.span)
        }
        val sample = DockEditorPreviewView(context)
        sample.render(listOf(entry), nativeWidth)
        val image = requireNotNull(sample.bitmap)
        val crop = sample.candidateFocus
        return if (crop != null && crop.width() > 0 && crop.height() > 0)
            Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height()) else image
    }

    private fun stripInteractions(view: View) {
        view.setOnClickListener(null)
        view.setOnLongClickListener(null)
        view.setOnTouchListener(null)
        view.isClickable = false
        view.isLongClickable = false
        if (view is ViewGroup) repeat(view.childCount) { stripInteractions(view.getChildAt(it)) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateTarget() }

    private fun updateTarget() {
        val image = bitmap ?: return
        val availableW = (width - 16 * density).coerceAtLeast(1f)
        val availableH = (height - 16 * density).coerceAtLeast(1f)
        val scale = minOf(availableW / image.width, availableH / image.height)
        val w = image.width * scale
        val h = image.height * scale
        target.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        paint.color = if (Color.red(DockIconView.fgColor) < 128) 0xFFCED1D6.toInt() else 0xFF171C25.toInt()
        canvas.drawRoundRect(target, 10 * density, 10 * density, paint)
        canvas.drawBitmap(image, null, target, paint)
        if (entries.isEmpty()) {
            paint.color = 0xFF9AA1AE.toInt()
            paint.textSize = 15 * density
            val text = "长按上方控件，拖入这里添加"
            canvas.drawText(text, (width - paint.measureText(text)) / 2f, height / 2f + 5 * density, paint)
        }
        gap?.let { index ->
            val imageX = if (index <= 0) 0 else itemRects.getOrNull(index - 1)?.right ?: image.width
            val x = (target.left + imageX.toFloat() / image.width * target.width()).coerceIn(target.left + density, target.right - density)
            paint.color = 0xFF3D8BFF.toInt()
            paint.strokeWidth = 3 * density
            canvas.drawLine(x, target.top - 5 * density, x, target.bottom + 5 * density, paint)
        }
    }

    private fun itemAt(x: Float, y: Float): Int {
        val image = bitmap ?: return -1
        if (!target.contains(x, y)) return -1
        val nativeX = (x - target.left) / target.width() * image.width
        return itemRects.indexOfFirst { nativeX >= it.left && nativeX < it.right }
    }

    private fun insertionAt(x: Float): Int {
        val image = bitmap ?: return 0
        val nativeX = (x - target.left) / target.width().coerceAtLeast(1f) * image.width
        val index = itemRects.indexOfFirst { nativeX < it.exactCenterX() }
        return if (index < 0) entries.size else index
    }

    private fun itemShadow(index: Int): DragShadowBuilder = object : DragShadowBuilder(this) {
        private val src = itemRects[index]
        private val image = bitmap
        private val factor = (target.height() / (image?.height ?: 1)).coerceAtMost(1f)
        override fun onProvideShadowMetrics(size: Point, touch: Point) {
            size.set((src.width() * factor).roundToInt().coerceAtLeast(1),
                (src.height() * factor).roundToInt().coerceAtLeast(1))
            touch.set(size.x / 2, size.y / 2)
        }
        override fun onDrawShadow(canvas: Canvas) {
            image?.let { canvas.drawBitmap(it, src, Rect(0, 0, canvas.width, canvas.height), paint) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDragEvent(event: DragEvent): Boolean {
        val item = event.localState as? DockEditorDrag ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                val inside = target.contains(event.x, event.y)
                gap = if (inside) insertionAt(event.x) else null
                onHover?.invoke(item, inside)
                invalidate()
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> { gap = null; onHover?.invoke(item, false); invalidate(); true }
            DragEvent.ACTION_DROP -> {
                onDrop?.invoke(item, if (target.contains(event.x, event.y)) insertionAt(event.x) else null)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> { gap = null; invalidate(); true }
            else -> true
        }
    }
}
