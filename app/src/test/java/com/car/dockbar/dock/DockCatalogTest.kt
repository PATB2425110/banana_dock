package com.car.dockbar.dock

import com.car.dockbar.model.Builtin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockCatalogTest {
    @Test
    fun everyBuiltinMatchesItsWidgetSpec() {
        Builtin.values().forEach { builtin ->
            val spec = DockCatalog.of(builtin.action)!!.spec
            assertEquals(builtin.action, spec.action)
            assertEquals(builtin.label, spec.label)
            assertEquals(builtin.span, spec.span)
            assertEquals(builtin.separatorBefore, spec.separatorBefore)
            assertEquals(builtin.hidden, spec.hidden)
        }
    }

    @Test
    fun unknownActionIsNotHome() {
        assertNull(DockCatalog.of("no-such"))
        assertEquals("missing", DockCatalog.kindFor("builtin", "no-such"))
        assertEquals("home", DockCatalog.kindFor("builtin", "home"))
        assertEquals("app", DockCatalog.kindFor("app", "com.example"))
        assertNotEquals("home", DockCatalog.kindFor("builtin", "no-such"))
    }

    @Test
    fun homeOpensRecentsOnLongPressAndRecircDoesNot() {
        val home = DockCatalog.of("home")!!.defaultGestures()[HitTargets.ROOT]!!
        assertEquals(DockAction.GoHome, home.tap)
        assertEquals(DockAction.OpenRecents, home.longPress)
        assertNull(home.doubleTap)
        assertEquals(0L, home.doubleWindowMs)

        val recirc = DockCatalog.of("recirc")!!.defaultGestures()[HitTargets.ROOT]!!
        assertEquals(DockAction.ToggleRecirc, recirc.tap)
        assertNull(recirc.longPress)
        assertNull(recirc.doubleTap)
        assertEquals(0L, recirc.doubleWindowMs)
    }

    @Test
    fun hiddenTempLongPressDoesNotShowTheStatusPopup() {
        val legacy = DockCatalog.of("temp_driver")!!.defaultGestures()[HitTargets.ROOT]!!
        assertEquals(DockAction.ToggleAc, legacy.longPress)
        val combo = DockCatalog.of("temp_driver_combo")!!.defaultGestures()[HitTargets.VALUE]!!
        assertEquals(DockAction.ToggleAcFromDock, combo.longPress)
    }

    @Test
    fun spanHonorsEntryWidthExceptLyric() {
        assertEquals(1, DockCatalog.spanOf(Builtin.LYRIC, 3))
        assertEquals(3, DockCatalog.spanOf(Builtin.MUSIC, 3))
        assertEquals(1, DockCatalog.spanOf(Builtin.HOME, 1))
        assertEquals(4, DockCatalog.spanOf(Builtin.HOME, 4))
    }

    @Test
    fun defaultGesturesMatchTheCurrentDock() {
        val expected = mapOf(
            "home" to "root:goHome/long:openRecents",
            "settings" to "root:openCarSettings/long:openDockSettings",
            "app_drawer" to "root:showPopup:appDrawer/long:openAppDrawerSettings",
            "seat" to "root:showPopup:seat",
            "volume" to "root:showPopup:volume",
            "lyric" to "root:toggleLyric/long:openThisApp",
            "air" to "root:showPopup:airDirection/long:openAirPanel",
            "recirc" to "root:toggleRecirc",
            "defrost_front" to "root:toggleDefrostFront",
            "defrost_rear" to "root:toggleDefrostRear",
            "vent" to "root:levelBump:vent:down/long:levelBump:vent:up",
            "seat_heat" to "root:levelBump:seatHeat:down/long:levelBump:seatHeat:up",
            "steering_heat" to "root:levelBump:steeringHeat:down/long:levelBump:steeringHeat:up",
            "temp_driver_combo" to "minus:tempStep:true:-1|plus:tempStep:true:1|value:showPopup:temperatureDriver/long:toggleAcFromDock",
            "temp_passenger_combo" to "minus:tempStep:false:-1|plus:tempStep:false:1|value:showPopup:temperaturePassenger/long:toggleDualSyncFromDock",
            "fan_combo" to "minus:fanStep:-1|plus:fanStep:1|value:toggleFanAuto/long:fanOff",
            "temp_down_driver" to "root:tempStep:true:-1",
            "temp_up_driver" to "root:tempStep:true:1",
            "temp_down_passenger" to "root:tempStep:false:-1",
            "temp_up_passenger" to "root:tempStep:false:1",
            "temp_driver" to "root:toggleHvacWindow/long:toggleAc",
            "temp_passenger" to "root:toggleHvacWindow/long:toggleDualSyncFromDock",
            "fan_down" to "root:fanStep:-1",
            "fan_up" to "root:fanStep:1",
            "fan" to "root:toggleHvacWindow/long:fanOff",
            "music" to "root:openPlayer|play:playPause/long:mediaNext",
        )
        expected.forEach { (action, gestures) ->
            assertEquals(gestures, describe(DockCatalog.of(action)!!.defaultGestures()))
        }
        DockCatalog.builtins().forEach { widget ->
            assertTrue(widget.spec.action, widget.defaultGestures().values.any { it.tap != null || it.longPress != null || it.doubleTap != null })
        }
    }

    private fun describe(map: Map<String, GestureBinding>): String =
        map.entries.joinToString("|") { (target, binding) ->
            buildString {
                append(target).append(':').append(code(binding.tap))
                if (binding.longPress != null) append("/long:").append(code(binding.longPress))
                if (binding.doubleTap != null) append("/double:").append(code(binding.doubleTap))
            }
        }

    private fun code(action: DockAction?): String = when (action) {
        null -> ""
        DockAction.GoHome -> "goHome"
        DockAction.OpenRecents -> "openRecents"
        DockAction.OpenDockSettings -> "openDockSettings"
        DockAction.OpenThisApp -> "openThisApp"
        DockAction.OpenAppDrawerSettings -> "openAppDrawerSettings"
        DockAction.OpenCarSettings -> "openCarSettings"
        DockAction.LaunchThisEntry -> "launchThisEntry"
        is DockAction.ShowPopup -> "showPopup:${action.popup}"
        DockAction.ToggleHvacWindow -> "toggleHvacWindow"
        DockAction.ToggleAc -> "toggleAc"
        DockAction.ToggleAcFromDock -> "toggleAcFromDock"
        DockAction.ToggleDualSyncFromDock -> "toggleDualSyncFromDock"
        is DockAction.TempStep -> "tempStep:${action.driver}:${action.delta}"
        is DockAction.FanStep -> "fanStep:${action.delta}"
        DockAction.ToggleFanAuto -> "toggleFanAuto"
        DockAction.FanOff -> "fanOff"
        DockAction.ToggleRecirc -> "toggleRecirc"
        DockAction.ToggleDefrostFront -> "toggleDefrostFront"
        DockAction.ToggleDefrostRear -> "toggleDefrostRear"
        is DockAction.LevelBump -> "levelBump:${action.channel}:${action.direction}"
        DockAction.OpenAirPanel -> "openAirPanel"
        DockAction.ToggleLyric -> "toggleLyric"
        DockAction.OpenPlayer -> "openPlayer"
        DockAction.PlayPause -> "playPause"
        DockAction.MediaNext -> "mediaNext"
    }
}
