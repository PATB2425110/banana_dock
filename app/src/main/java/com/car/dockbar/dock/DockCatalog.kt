package com.car.dockbar.dock

import android.content.Context
import com.car.dockbar.model.Builtin
import com.car.dockbar.model.DockEntry
import com.car.dockbar.dock.widgets.AppWidget
import com.car.dockbar.dock.widgets.BuiltinWidgets
import com.car.dockbar.dock.widgets.MissingWidget

object DockCatalog {
    private val byAction = BuiltinWidgets.all.associateBy { it.spec.action }

    fun of(action: String): DockWidget? = byAction[action]

    fun builtins(): List<DockWidget> = BuiltinWidgets.all

    fun kindFor(kind: String, action: String): String = when {
        kind == "app" -> "app"
        of(action) != null -> action
        else -> "missing"
    }

    fun spanOf(builtin: Builtin, entrySpan: Int): Int =
        spanFor(of(builtin.action)!!, entrySpan, builtin.span)

    fun mount(
        ctx: Context,
        entry: DockEntry,
        host: DockHost,
        attachGestures: Boolean,
        iconPx: Int,
    ): MountedEntry {
        val widget = when {
            entry.kind == "app" -> AppWidget
            else -> of(entry.action) ?: MissingWidget(entry.action)
        }
        val created = widget.create(ctx, entry, iconPx)
        val span = spanFor(widget, entry.span, widget.spec.span)
        val fixed = when {
            created.flexMaxPx > 0 -> 0
            created.fixedWidthPx > 0 -> created.fixedWidthPx
            else -> DockMetrics.fixedWidthFor(span)
        }
        val mounted = created.copy(
            span = span,
            separator = widget.spec.separatorBefore,
            fixedWidthPx = fixed,
            flexMinPx = widget.spec.flexMinPx,
            flexMaxPx = widget.spec.flexMaxPx,
        )
        if (attachGestures) attach(mounted, host)
        return mounted
    }

    private fun spanFor(widget: DockWidget, entrySpan: Int, defaultSpan: Int): Int {
        if (widget.spec.lockSpan) return widget.spec.span.coerceIn(1, 6)
        return if (entrySpan > 1) entrySpan.coerceIn(1, 6) else defaultSpan.coerceIn(1, 6)
    }

    private fun attach(mounted: MountedEntry, host: DockHost) {
        val merged = GestureMaps.merge(mounted.widget.defaultGestures(), mounted.entry.gestures)
        for ((target, hit) in mounted.hits) {
            val binding = merged[target] ?: continue
            if (binding.tap == null && binding.longPress == null && binding.doubleTap == null) continue
            GestureInput.attach(hit, binding, { action ->
                DockActions.perform(action, hit.context, host, mounted.view, mounted.entry)
            }, host::onGesture)
        }
    }
}
