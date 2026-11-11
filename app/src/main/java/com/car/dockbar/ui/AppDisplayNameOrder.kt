package com.car.dockbar.ui

import java.text.Collator
import java.util.Locale

/** Chinese names by pinyin first, Latin names second, then digits/other scripts. */
internal object AppDisplayNameOrder {
    fun comparator(): Comparator<String> {
        // Collators are not shared across metadata-loader threads.
        val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        fun key(value: String) = value.trim().dropWhile { !it.isLetterOrDigit() }
        fun group(value: String): Int {
            if (value.isEmpty()) return 3
            val initial = value.codePointAt(0)
            return when {
                Character.UnicodeScript.of(initial) == Character.UnicodeScript.HAN -> 0
                initial in 'A'.code..'Z'.code || initial in 'a'.code..'z'.code -> 1
                else -> 2
            }
        }
        return Comparator { left, right ->
            val a = key(left)
            val b = key(right)
            val category = group(a).compareTo(group(b))
            if (category != 0) category else {
                val name = collator.compare(a, b)
                if (name != 0) name else left.compareTo(right, ignoreCase = true)
            }
        }
    }
}
