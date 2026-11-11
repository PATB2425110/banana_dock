package com.car.dockbar.dock

import com.car.dockbar.R

fun bumpLevel(current: Int, direction: String): Int = when (direction) {
    "up" -> if (current >= 3) 0 else current + 1
    else -> if (current <= 0) 3 else current - 1
}

fun airDirectionIcon(bits: Int): Int {
    val face = bits and 0x1 != 0
    val foot = bits and 0x2 != 0
    val win = bits and 0x4 != 0
    return when {
        face && foot && win -> R.drawable.ic_blowing_all
        face && foot -> R.drawable.ic_blowing_facefoot
        face && win -> R.drawable.ic_blowing_face_and_window
        foot && win -> R.drawable.ic_blowing_foot_and_window
        face -> R.drawable.ic_blowing_face
        foot -> R.drawable.ic_blowing_foot
        win -> R.drawable.ic_blowing_window
        else -> R.drawable.ic_blowing_off
    }
}

fun levelIcon(channel: String, level: Int, darkIcons: Boolean): Int {
    val step = level.coerceIn(0, 3)
    return when (channel) {
        "seatHeat" -> seatHeatIcon(step, darkIcons)
        "steeringHeat" -> steeringHeatIcon(step, darkIcons)
        else -> ventIcon(step, darkIcons)
    }
}

private fun ventIcon(level: Int, dark: Boolean): Int = when (level) {
    1 -> if (dark) R.drawable.ic_seat_feng_01_dark else R.drawable.ic_seat_feng_01
    2 -> if (dark) R.drawable.ic_seat_feng_02_dark else R.drawable.ic_seat_feng_02
    3 -> if (dark) R.drawable.ic_seat_feng_03_dark else R.drawable.ic_seat_feng_03
    else -> if (dark) R.drawable.ic_seat_feng_off_dark else R.drawable.ic_seat_feng_off
}

private fun seatHeatIcon(level: Int, dark: Boolean): Int = when (level) {
    1 -> if (dark) R.drawable.ic_seat_hot_01_dark else R.drawable.ic_seat_hot_01
    2 -> if (dark) R.drawable.ic_seat_hot_02_dark else R.drawable.ic_seat_hot_02
    3 -> if (dark) R.drawable.ic_seat_hot_03_dark else R.drawable.ic_seat_hot_03
    else -> if (dark) R.drawable.ic_seat_hot_off_dark else R.drawable.ic_seat_hot_off
}

private fun steeringHeatIcon(level: Int, dark: Boolean): Int = when (level) {
    1 -> if (dark) R.drawable.ic_sw_heat_01_dark else R.drawable.ic_sw_heat_01
    2 -> if (dark) R.drawable.ic_sw_heat_02_dark else R.drawable.ic_sw_heat_02
    3 -> if (dark) R.drawable.ic_sw_heat_03_dark else R.drawable.ic_sw_heat_03
    else -> if (dark) R.drawable.ic_sw_heat_off_dark else R.drawable.ic_sw_heat_off
}
