package com.car.dockbar.dock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockActionCodecTest {
    @Test
    fun simpleActionsRoundTrip() {
        listOf(
            DockAction.GoHome,
            DockAction.OpenRecents,
            DockAction.ToggleFanAuto,
            DockAction.FanOff,
            DockAction.ToggleLyric,
            DockAction.OpenPlayer,
            DockAction.PlayPause,
            DockAction.MediaNext,
            DockAction.OpenCarSettings,
            DockAction.LaunchThisEntry,
            DockAction.ToggleHvacWindow,
            DockAction.ToggleAc,
            DockAction.ToggleAcFromDock,
            DockAction.ToggleDualSyncFromDock,
            DockAction.OpenAirPanel,
            DockAction.OpenDockSettings,
            DockAction.OpenThisApp,
            DockAction.OpenAppDrawerSettings,
        ).forEach { assertRoundTrip(it) }
    }

    @Test
    fun tempStepRoundTrip() {
        val back = roundTrip(DockAction.TempStep(driver = false, delta = 1))
        val step = back as DockAction.TempStep
        assertEquals(false, step.driver)
        assertEquals(1, step.delta)
    }

    @Test
    fun fanStepRoundTrip() {
        val step = roundTrip(DockAction.FanStep(-1)) as DockAction.FanStep
        assertEquals(-1, step.delta)
    }

    @Test
    fun showPopupRoundTrip() {
        val popup = roundTrip(DockAction.ShowPopup("seat")) as DockAction.ShowPopup
        assertEquals("seat", popup.popup)
    }

    @Test
    fun levelBumpRoundTrip() {
        val bump = roundTrip(DockAction.LevelBump("vent", "down")) as DockAction.LevelBump
        assertEquals("vent", bump.channel)
        assertEquals("down", bump.direction)
    }

    @Test
    fun unknownTypeIsNull() {
        assertNull(DockActionCodec.fromJson(JSONObject("""{"type":"nope"}""")))
    }

    @Test
    fun missingTypeIsNull() {
        assertNull(DockActionCodec.fromJson(JSONObject("{}")))
    }

    @Test
    fun missingOrMistypedFieldsAreNull() {
        assertNull(DockActionCodec.fromJson(JSONObject("""{"type":"tempStep","driver":false}""")))
        assertNull(DockActionCodec.fromJson(JSONObject("""{"type":"tempStep","driver":"no","delta":1}""")))
        assertNull(DockActionCodec.fromJson(JSONObject("""{"type":"levelBump","direction":"down"}""")))
    }

    private fun roundTrip(action: DockAction): DockAction =
        DockActionCodec.fromJson(DockActionCodec.toJson(action))!!

    private fun assertRoundTrip(action: DockAction) {
        assertEquals(action, roundTrip(action))
    }
}
