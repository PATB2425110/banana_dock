package com.car.dockbar.ui

/** Occupied widths only. Icon and text sizes are deliberately unaffected. */
internal data class DockWidthPlan(val fixedScale: Float, val flexWidth: Int, val startX: Float) {
    companion object {
        fun create(width: Int, fixedTotal: Int, hasFlex: Boolean, flexMin: Int, flexMax: Int): DockWidthPlan {
            val w = width.coerceAtLeast(0)
            val flex = if (hasFlex) (w - fixedTotal).coerceAtLeast(0)
                .coerceIn(flexMin, flexMax).coerceAtMost(w) else 0
            val scale = if (fixedTotal > 0) (w - flex).toFloat() / fixedTotal else 1f
            // The only bounded card left (music) stays centered instead of sticking to the left.
            val start = if (hasFlex && fixedTotal == 0) (w - flex) / 2f else 0f
            return DockWidthPlan(scale, flex, start)
        }
    }
}
