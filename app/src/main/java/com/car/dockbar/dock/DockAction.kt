package com.car.dockbar.dock

import org.json.JSONObject

sealed class DockAction {
    data object GoHome : DockAction()
    data object OpenRecents : DockAction()
    data object OpenDockSettings : DockAction()
    data object OpenThisApp : DockAction()
    data object OpenAppDrawerSettings : DockAction()
    data object OpenCarSettings : DockAction()
    data object LaunchThisEntry : DockAction()
    data class ShowPopup(val popup: String) : DockAction()
    data object ToggleHvacWindow : DockAction()
    data object ToggleAc : DockAction()
    data object ToggleAcFromDock : DockAction()
    data object ToggleDualSyncFromDock : DockAction()
    data class TempStep(val driver: Boolean, val delta: Int) : DockAction()
    data class FanStep(val delta: Int) : DockAction()
    data object ToggleFanAuto : DockAction()
    data object FanOff : DockAction()
    data object ToggleRecirc : DockAction()
    data object ToggleDefrostFront : DockAction()
    data object ToggleDefrostRear : DockAction()
    data class LevelBump(val channel: String, val direction: String) : DockAction()
    data object OpenAirPanel : DockAction()
    data object ToggleLyric : DockAction()
    data object OpenPlayer : DockAction()
    data object PlayPause : DockAction()
    data object MediaNext : DockAction()
}

object DockActionCodec {
    fun toJson(action: DockAction): JSONObject = when (action) {
        DockAction.GoHome -> simple("goHome")
        DockAction.OpenRecents -> simple("openRecents")
        DockAction.OpenDockSettings -> simple("openDockSettings")
        DockAction.OpenThisApp -> simple("openThisApp")
        DockAction.OpenAppDrawerSettings -> simple("openAppDrawerSettings")
        DockAction.OpenCarSettings -> simple("openCarSettings")
        DockAction.LaunchThisEntry -> simple("launchThisEntry")
        is DockAction.ShowPopup -> simple("showPopup").put("popup", action.popup)
        DockAction.ToggleHvacWindow -> simple("toggleHvacWindow")
        DockAction.ToggleAc -> simple("toggleAc")
        DockAction.ToggleAcFromDock -> simple("toggleAcFromDock")
        DockAction.ToggleDualSyncFromDock -> simple("toggleDualSyncFromDock")
        is DockAction.TempStep -> simple("tempStep").put("driver", action.driver).put("delta", action.delta)
        is DockAction.FanStep -> simple("fanStep").put("delta", action.delta)
        DockAction.ToggleFanAuto -> simple("toggleFanAuto")
        DockAction.FanOff -> simple("fanOff")
        DockAction.ToggleRecirc -> simple("toggleRecirc")
        DockAction.ToggleDefrostFront -> simple("toggleDefrostFront")
        DockAction.ToggleDefrostRear -> simple("toggleDefrostRear")
        is DockAction.LevelBump -> simple("levelBump")
            .put("channel", action.channel)
            .put("direction", action.direction)
        DockAction.OpenAirPanel -> simple("openAirPanel")
        DockAction.ToggleLyric -> simple("toggleLyric")
        DockAction.OpenPlayer -> simple("openPlayer")
        DockAction.PlayPause -> simple("playPause")
        DockAction.MediaNext -> simple("mediaNext")
    }

    fun fromJson(obj: JSONObject): DockAction? {
        if (!obj.has("type") || obj.isNull("type")) return null
        return when (obj.get("type")) {
            "goHome" -> DockAction.GoHome
            "openRecents" -> DockAction.OpenRecents
            "openDockSettings" -> DockAction.OpenDockSettings
            "openThisApp" -> DockAction.OpenThisApp
            "openAppDrawerSettings" -> DockAction.OpenAppDrawerSettings
            "openCarSettings" -> DockAction.OpenCarSettings
            "launchThisEntry" -> DockAction.LaunchThisEntry
            "showPopup" -> text(obj, "popup")?.let { DockAction.ShowPopup(it) }
            "toggleHvacWindow" -> DockAction.ToggleHvacWindow
            "toggleAc" -> DockAction.ToggleAc
            "toggleAcFromDock" -> DockAction.ToggleAcFromDock
            "toggleDualSyncFromDock" -> DockAction.ToggleDualSyncFromDock
            "tempStep" -> {
                val driver = bool(obj, "driver") ?: return null
                val delta = int(obj, "delta") ?: return null
                DockAction.TempStep(driver, delta)
            }
            "fanStep" -> int(obj, "delta")?.let { DockAction.FanStep(it) }
            "toggleFanAuto" -> DockAction.ToggleFanAuto
            "fanOff" -> DockAction.FanOff
            "toggleRecirc" -> DockAction.ToggleRecirc
            "toggleDefrostFront" -> DockAction.ToggleDefrostFront
            "toggleDefrostRear" -> DockAction.ToggleDefrostRear
            "levelBump" -> {
                val channel = text(obj, "channel") ?: return null
                val direction = text(obj, "direction") ?: return null
                DockAction.LevelBump(channel, direction)
            }
            "openAirPanel" -> DockAction.OpenAirPanel
            "toggleLyric" -> DockAction.ToggleLyric
            "openPlayer" -> DockAction.OpenPlayer
            "playPause" -> DockAction.PlayPause
            "mediaNext" -> DockAction.MediaNext
            else -> null
        }
    }

    private fun simple(type: String) = JSONObject().put("type", type)

    private fun text(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.get(key) as? String
    }

    private fun bool(obj: JSONObject, key: String): Boolean? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.get(key) as? Boolean
    }

    private fun int(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.get(key)) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }
    }
}
