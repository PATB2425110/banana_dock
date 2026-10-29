package com.car.dockbar.vehicle

import android.os.Bundle
import com.car.dockbar.vehicle.VehicleController.State

/**
 * `:car` 进程与主进程之间的通信协议（Messenger + Bundle，避免引入 AIDL 文件）。
 *
 * 放在这里是为了让 [VehicleControlService] 与 [VehicleClient] 共用同一份常量与编解码，
 * 避免两边字段名写错导致「读到的全是默认值」这种难查的 bug。
 */
object VehicleProtocol {

    // ---- 主进程 → 车控进程 ----
    const val MSG_SUBSCRIBE = 1
    const val MSG_REFRESH = 2
    const val MSG_COMMAND = 3
    const val MSG_DEBUG = 4

    // ---- 车控进程 → 主进程 ----
    const val MSG_STATE = 101
    const val MSG_HELLO = 102

    // ---- Bundle key ----
    const val KEY_CMD = "cmd"
    const val KEY_DRIVER = "driver"
    const val KEY_STEP = "step"
    const val KEY_LEVEL = "level"
    const val KEY_FUNC = "func"
    const val KEY_ZONE = "zone"
    const val KEY_VALUE = "value"
    const val KEY_IS_WRITE = "isWrite"
    const val KEY_READ_RESULT = "readResult"
    const val KEY_WRITE_OK = "writeOk"
    const val KEY_TEXT = "text"

    // ---- 命令名 ----
    const val CMD_TEMP_STEP = "tempStep"
    const val CMD_FAN_STEP = "fanStep"
    const val CMD_FAN_AUTO = "fanAuto"
    const val CMD_RECIRC = "recirc"
    const val CMD_AIR = "air"
    const val CMD_SEAT_VENT = "seatVent"
    const val CMD_SEAT_HEAT = "seatHeat"

    /**
     * 车控进程自己的刷新周期。
     *
     * ★ v1.4.97 #2：2500ms → 1000ms。
     *   车机上（实体按键 / 原厂空调界面）改了风速后，Dock 要等一整个轮询周期才跟上，
     *   2.5 秒的滞后在车上是明显能感觉到的「读取延迟很高」。1 秒足够快，
     *   又不至于把 binder 打满（一次轮询 = 14 个通道读 + 2 次 ISensor）。
     */
    const val REFRESH_MS = 1000L

    // =====================================================================

    fun encodeState(s: State): Bundle = Bundle().apply {
        putBoolean("connected", s.connected)
        putString("backend", s.backend)
        putFloat("driverTempC", s.driverTempC ?: Float.NaN)
        putFloat("passengerTempC", s.passengerTempC ?: Float.NaN)
        putInt("fanLevel", s.fanLevel)
        putInt("fanMax", s.fanMax)
        putBoolean("fanAuto", s.fanAuto)
        putBoolean("recircInside", s.recircInside)
        putInt("airDirection", s.airDirection)
        putInt("seatVent", s.seatVent)
        putInt("seatHeat", s.seatHeat)
    }

    fun decodeState(b: Bundle): State = State(
        connected = b.getBoolean("connected"),
        backend = b.getString("backend") ?: "未连接",
        driverTempC = b.getFloat("driverTempC").takeUnless { it.isNaN() },
        passengerTempC = b.getFloat("passengerTempC").takeUnless { it.isNaN() },
        fanLevel = b.getInt("fanLevel"),
        fanMax = b.getInt("fanMax", 9),
        fanAuto = b.getBoolean("fanAuto"),
        recircInside = b.getBoolean("recircInside"),
        airDirection = b.getInt("airDirection", 1),
        seatVent = b.getInt("seatVent"),
        seatHeat = b.getInt("seatHeat")
    )
}
