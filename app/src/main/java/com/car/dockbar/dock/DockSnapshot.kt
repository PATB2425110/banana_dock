package com.car.dockbar.dock

import android.content.Context
import android.graphics.Bitmap
import com.car.dockbar.media.CabinVolume
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.ui.LyricOverlayService
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController

data class DockSnapshot(
    val driverTempC: Float?,
    val passengerTempC: Float?,
    val acOn: Boolean,
    val fanLevel: Int,
    val fanAuto: Boolean,
    val recircInside: Boolean,
    val airDirection: Int,
    val defrostFront: Boolean,
    val defrostRear: Boolean,
    val seatVent: Int,
    val seatHeat: Int,
    val steeringHeat: Int,
    val darkIcons: Boolean,
    val volumeLevel: Int,
    val lyricOn: Boolean,
    val mediaHasSession: Boolean,
    val mediaTitle: String,
    val mediaArtist: String,
    val mediaArt: Bitmap?,
    val mediaPlaying: Boolean,
)

object DockSnapshots {
    fun capture(ctx: Context, darkIcons: Boolean): DockSnapshot {
        val state = VehicleController.state
        val media = MediaBridge.info
        return DockSnapshot(
            driverTempC = state.driverTempC ?: Prefs.lastDriverTempC(ctx),
            passengerTempC = state.passengerTempC ?: Prefs.lastPassengerTempC(ctx),
            acOn = state.acOn,
            fanLevel = state.fanLevel,
            fanAuto = state.fanAuto,
            recircInside = state.recircInside,
            airDirection = state.airDirection,
            defrostFront = state.defrostFront,
            defrostRear = state.defrostRear,
            seatVent = state.seatVent,
            seatHeat = state.seatHeat,
            steeringHeat = state.steeringHeat,
            darkIcons = darkIcons,
            volumeLevel = CabinVolume.read(ctx, CabinVolume.MEDIA).value,
            lyricOn = LyricOverlayService.alive && LyricOverlayService.attached,
            mediaHasSession = media.hasSession,
            mediaTitle = media.title,
            mediaArtist = media.artist,
            mediaArt = media.art,
            mediaPlaying = media.isPlaying,
        )
    }
}
