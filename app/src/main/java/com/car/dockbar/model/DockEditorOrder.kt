package com.car.dockbar.model

/** Gap is between items in the original list: 0 is before the first, size is after the last. */
internal object DockEditorOrder {
    fun <T> insert(items: List<T>, item: T, gap: Int): List<T> = items.toMutableList().apply {
        add(gap.coerceIn(0, size), item)
    }

    fun <T> move(items: List<T>, from: Int, gap: Int): List<T> {
        if (from !in items.indices) return items.toList()
        val target = gap.coerceIn(0, items.size)
        return items.toMutableList().apply {
            val item = removeAt(from)
            add((target - if (from < target) 1 else 0).coerceIn(0, size), item)
        }
    }
}
