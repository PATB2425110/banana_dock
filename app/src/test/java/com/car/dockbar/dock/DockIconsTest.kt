package com.car.dockbar.dock

import com.car.dockbar.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DockIconsTest {
    @Test
    fun bumpLevelWrapsAtTheEnds() {
        assertEquals(3, bumpLevel(0, "down"))
        assertEquals(0, bumpLevel(1, "down"))
        assertEquals(0, bumpLevel(3, "up"))
        assertEquals(3, bumpLevel(2, "up"))
        assertEquals(2, bumpLevel(3, "down"))
    }

    @Test
    fun airDirectionUsesTheBitmask() {
        assertEquals(R.drawable.ic_blowing_off, airDirectionIcon(0))
        assertEquals(R.drawable.ic_blowing_off, airDirectionIcon(8))
        assertEquals(R.drawable.ic_blowing_face, airDirectionIcon(1))
        assertEquals(R.drawable.ic_blowing_foot, airDirectionIcon(2))
        assertEquals(R.drawable.ic_blowing_facefoot, airDirectionIcon(3))
        assertEquals(R.drawable.ic_blowing_window, airDirectionIcon(4))
        assertEquals(R.drawable.ic_blowing_face_and_window, airDirectionIcon(5))
        assertEquals(R.drawable.ic_blowing_foot_and_window, airDirectionIcon(6))
        assertEquals(R.drawable.ic_blowing_all, airDirectionIcon(7))
    }

    @Test
    fun levelIconsFollowChannelAndTheme() {
        assertEquals(R.drawable.ic_seat_feng_off, levelIcon("vent", 0, false))
        assertEquals(R.drawable.ic_seat_feng_off_dark, levelIcon("vent", 0, true))
        assertEquals(R.drawable.ic_seat_feng_01, levelIcon("vent", 1, false))
        assertEquals(R.drawable.ic_seat_feng_01_dark, levelIcon("vent", 1, true))
        assertEquals(R.drawable.ic_seat_feng_03, levelIcon("vent", 3, false))
        assertEquals(R.drawable.ic_seat_feng_03_dark, levelIcon("vent", 3, true))
        assertEquals(R.drawable.ic_seat_hot_02, levelIcon("seatHeat", 2, false))
        assertEquals(R.drawable.ic_sw_heat_off_dark, levelIcon("steeringHeat", 0, true))
    }
}
