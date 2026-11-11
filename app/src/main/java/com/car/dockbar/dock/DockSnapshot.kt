package com.car.dockbar.dock

import android.graphics.Bitmap

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
