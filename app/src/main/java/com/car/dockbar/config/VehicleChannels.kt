package com.car.dockbar.config

import android.content.Context
import com.car.dockbar.util.Prefs

/**
 * 一条车控通道：functionId + zone。
 *
 * ✅ 权威来源：反编译车机系统应用 `oneOS_Dock.apk`
 *    （`com.geely.dockbar.domain.vehicleusecase.SignalHelper2` / `VehicleSignalManager`
 *      / `controller.appclick.signal.*`，2026-10-15 复核）
 *
 *  | 通道 | functionId | areaId(zone) | 调用形态 | 取值编码 |
 *  |---|---|---|---|---|
 *  | 风量手动 | 0x10020100 | 0x8 | getFunctionValue(func, zone) | func + 01..09（0=关） |
 *  | 风量自动 | 0x10020200 | 0x8 | getFunctionValue(func, zone) | func + 01..05 |
 *  | 内外循环 | 0x10030100 | 0x8 | setFunctionValue(func, zone, value) | 裸枚举 0=外 / 1=内 |
 *  | 空调风向 | 0x10070100 | 0x8 | get/setFunctionValue | func + 01..07（7 种模式） |
 *  | 空调温度 | 0x10060100 | 1=主驾 / 4=副驾 | **getCustomizeFunctionValue(func, zone)** | **直接是摄氏度 float，步进 0.5℃** |
 *  | 座椅通风 | **0x10050100** | 1=主驾 / 4=副驾 | get/setFunctionValue | func + 0..3（0=关 1..3 档） |
 *  | 座椅加热 | **0x10050200** | 1=主驾 / 4=副驾 | get/setFunctionValue | func + 0..3 |
 *  | 座椅按摩 | 0x10050700 | 1 / 4 | get/setFunctionValue | func + 0..N |
 *  | 温度上限 | 0x10060300 | — | getCustomizeFunctionValue | float ℃ |
 *  | 温度下限 | 0x10060500 | — | getCustomizeFunctionValue | float ℃ |
 *
 *  ⚠️ 两套 areaId，别混用：
 *   - 风量 / 风向用 ECarX 系：**0x8**
 *   - 温度 / 座椅用 VehicleAreaSeat 位掩码：**1=前左(主驾) 4=前右(副驾)**
 *
 *  ⚠️ 历史踩坑（已修）：本项目早期把座椅通风写成 0x10050000、座椅加热写成 0x10050100、
 *   且座椅沿用 zone=0x8，三项全错 → 座椅通风/加热读写无效。正确值见上表。
 *
 *  任何 ID / zone 都可在主界面「车控通道」里直接改数字保存（写进 SharedPreferences，无需重编译）覆盖默认值。
 *  实测方法：通道列表 → 点「读」看是否返回合理值 → 再用「写」试写看车机是否响应。
 */
data class VehicleChannel(
    val key: String,
    val label: String,
    /** 默认 functionId（-1 表示未接入） */
    val defFunc: Int,
    /** 默认 zone */
    val defZone: Int,
    /** 取值枚举下限（低 8 位） */
    val valueMin: Int,
    /** 取值枚举上限（低 8 位） */
    val valueMax: Int,
    val note: String = ""
)

object VehicleChannels {

    /** 风量 / 风向用 zone —— 来自 oneOS_Dock 反编译，已验证 */
    const val ZONE_DRIVER = 0x8

    /**
     * ★★★ v3.6：内外循环的 zone —— 按**系统 Dock 反编译的权威实现**改为**整车级 0x80000000**。
     *
     * 证据（oneOS_Dock.apk / com.geely.dockbar dex）：
     *   `VehicleSignalManager` 里注册 `CycleModeSignal(0x10030100, areaId=0x80000000, 0)`
     *   → funcId `0x10030100` + **areaId = 0x80000000（ZONE_ALL）**
     *
     * 且与我们的实车日志完全吻合：
     * ```
     * 19:30:55  HVAC_WRITE 0x10030100 zone=8             value=0x10030101 → false
     * 19:30:55  HVAC_WRITE 0x10030100 zone=1/4/16/32/64  value=0x10030101 → false
     * 19:30:55  HVAC_WRITE 0x10030100 zone=-2147483648   value=0x10030101 → true  ← 只有全局 zone 有效
     * ```
     * （zone=0x8 等 7 个 zone 全被 HAL 静默丢弃。）
     *
     * 值域（同源，RecycleSignalClick.processClick 反编译）：
     *   外循环 = **裸值 0**；内循环 = **复合 0x10030101**。
     */
    const val ZONE_RECIRC = -2147483648   // 0x80000000（整车级，与系统 Dock 一致）

    /**
     * 旧（错误）取值：0x8 = 前排全区。
     * 历史上曾把 recirc 的 zone 设为 0x8，导致 HAL 静默丢弃所有写入 → 「内外循环都切不了」。
     * 保留常量仅供迁移旧配置使用。
     */
    const val ZONE_RECIRC_LEGACY = 0x8

    /** 整车级 area（ZONE_ALL）—— 前挡 / ECO / 双温 / 空调电源都用它 */
    const val ZONE_GLOBAL = -2147483648   // 0x80000000

    /** 风量 / 风向的副驾 zone（部分车型用；本机风量只有主驾一个区） */
    const val ZONE_PASSENGER = 0x10

    /**
     * 温度 / 座椅专用 zone —— 已确认（oneOS_Dock VehicleSignalManager 实测）。
     * 用的是 VehicleAreaSeat 位掩码，与风量的 0x8/0x10 不是一套：
     *   1=前左(主驾)  4=前右(副驾)  0x10=后左  0x40=后右
     */
    const val ZONE_TEMP_DRIVER = 1
    const val ZONE_TEMP_PASSENGER = 4

    /** 座椅（通风 / 加热 / 按摩）与温度同一套区掩码：1=主驾 4=副驾 */
    const val ZONE_SEAT_DRIVER = ZONE_TEMP_DRIVER
    const val ZONE_SEAT_PASSENGER = ZONE_TEMP_PASSENGER

    // =====================================================================
    // functionId 来源
    //  - 全部来自 oneOS_Dock.apk 反编译的 VehicleSignalManager 稀疏表
    //    （0x10020100 → HVAC_FAN_SPEED 等），与 IHvac.HVAC_FUNC_* 等价
    // =====================================================================

    // ---- 风量 ----
    const val FUNC_FAN_MANUAL = 0x10020100      // HVAC_FUNC_FAN_SPEED
    const val FUNC_FAN_AUTO = 0x10020200        // HVAC_FUNC_AUTO_FAN_SETTING

    // ---- 空调 ----
    const val FUNC_TEMP = 0x10060100            // HVAC_FUNC_TEMP（float ℃）
    const val FUNC_TEMP_MAX = 0x10060200        // HVAC_FUNC_TEMP_MAX
    const val FUNC_TEMP_MIN = 0x10060300        // HVAC_FUNC_TEMP_MIN
    const val FUNC_TEMP_STEP = 0x10060400       // HVAC_FUNC_TEMP_STEP
    const val FUNC_TEMP_DUAL = 0x10060500       // HVAC_FUNC_TEMP_DUAL（主副驾同步）
    const val FUNC_TEMP_UNIT = 0x10060600       // HVAC_FUNC_TEMP_UNIT
    const val FUNC_CIRCULATION = 0x10030100     // HVAC_FUNC_CIRCULATION
    const val FUNC_BLOWING_MODE = 0x10070100    // HVAC_FUNC_BLOWING_MODE
    const val FUNC_POWER = 0x10010100           // HVAC_FUNC_POWER
    const val FUNC_AUTO = 0x10010200            // ★ v3.5：HVAC_FUNC_AUTO（AUTO 总开关，AutoSwitchSignalClick）
    const val FUNC_AC = 0x10010300              // HVAC_FUNC_AC（A/C 压缩机）
    const val FUNC_AC_MAX = 0x10010400          // HVAC_FUNC_AC_MAX
    const val FUNC_DEFROST_FRONT = 0x10040100   // HVAC_FUNC_DEFROST_FRONT
    const val FUNC_DEFROST_FRONT_MAX = 0x10040200 // HVAC_FUNC_DEFROST_FRONT_MAX
    const val FUNC_DEFROST_REAR = 0x10040300    // HVAC_FUNC_DEFROST_REAR
    const val FUNC_ECO = 0x10080100             // HVAC_FUNC_ECO_SWITCH

    // ---- 座椅（★ 修正：通风 0x10050100 / 加热 0x10050200）----
    const val FUNC_SEAT_VENT = 0x10050100       // HVAC_FUNC_SEAT_VENTILATION
    const val FUNC_SEAT_HEAT = 0x10050200       // HVAC_FUNC_SEAT_HEATING
    const val FUNC_SEAT_MASSAGE = 0x10050700    // HVAC_FUNC_SEAT_MASSAGE
    const val FUNC_SEAT_MASSAGE_SWITCH = 0x10050a00 // HVAC_FUNC_SEAT_MASSAGE_SWITCH
    const val FUNC_STEERING_WHEEL_HEAT = 0x10090100 // HVAC_FUNC_STEERING_WHEEL_HEAT

    val ALL: List<VehicleChannel> = listOf(
        VehicleChannel(
            "fan_manual", "主驾风量（手动）", FUNC_FAN_MANUAL, ZONE_DRIVER, 0, 9,
            "已验证：0x10020101…09 → 1…9 档，0 = 关"
        ),
        VehicleChannel(
            "fan_auto", "主驾风量（自动）", FUNC_FAN_AUTO, ZONE_DRIVER, 1, 5,
            "已验证：0x10020201…05 → 1…5 档"
        ),
        VehicleChannel(
            "temp_driver", "主驾空调温度", FUNC_TEMP, ZONE_TEMP_DRIVER, 15, 29,
            "int 通道编码：低 8 位 = round(℃ / 0.2)，实测 128 → 25.6℃。" +
            "可调区间 15.5..28.5℃（HvacConstants.TEMP_C_MIN/MAX）。" +
            "⚠ 温度的 Customize 浮点通道已禁用（在普通 App 上会触发厂商库 native 崩溃）"
        ),
        VehicleChannel(
            "temp_passenger", "副驾空调温度", FUNC_TEMP, ZONE_TEMP_PASSENGER, 15, 29,
            "同 functionId 0x10060100，zone=4。int 通道编码同主驾"
        ),
        VehicleChannel(
            "air_direction", "空调风向", FUNC_BLOWING_MODE, ZONE_DRIVER, 1, 7,
            "已确认 functionId=0x10070100，取值 func+01..07 共 7 种模式"
        ),
        VehicleChannel(
            "recirc", "内外循环", FUNC_CIRCULATION, ZONE_RECIRC, 0, 1,
            "★★v3.6 权威（据系统 Dock 实测行为还原）：" +
            "系统内外循环信号：funcId=0x10030100，areaId=0x80000000 → zone=整车级 0x80000000。" +
            "系统切换逻辑：当前值==0 → 写 0x10030101(内)；否则 → 写 0(外，裸值)。" +
            "★ 外循环 = 裸值 0，内循环 = 复合 0x10030101。" +
            "⚠ 0x10030100（低字节0高位非零）HAL 不认 → 发了也切不出去，别再用"
        ),
        VehicleChannel(
            "auto_master", "AUTO 总开关", FUNC_AUTO, ZONE_GLOBAL, 0, 1,
            "★v3.5/3.6 新增：HVAC_FUNC_AUTO 0x10010200（系统 AUTO 开关 / code 400002）。" +
            "系统 AUTO 信号：funcId=0x10010200，areaId=0x80000000，写**裸值 0/1**，不做复合编码。" +
            "注意与「手动风量通道写 10 = AUTO」是两回事"
        ),
        VehicleChannel(
            "temp_dual", "双温同步（车机）", FUNC_TEMP_DUAL, ZONE_GLOBAL, 0, 1,
            "★v1.4.52 新增：车机自身的主副驾温度同步开关（HVAC_FUNC_TEMP_DUAL 0x10060500）。" +
            "★v1.4.58 zone 修正为**全局 0x80000000**：logcat 实锤系统侧就是这个 zone —— " +
            "`IHvac.HVAC_FUNC_TEMP_DUAL [0x10060500] zone:VehicleZone.ZONE_ALL [0x80000000] " +
            "Data{mStatus=active, mSupportValues=[1, 0], mValue=0}`。原来用 zone=1 所以写不进去。" +
            "取值 0=关 1=开"
        ),
        VehicleChannel(
            "ac", "A/C 压缩机开关", FUNC_AC, ZONE_GLOBAL, 0, 1,
            "functionId=0x10010300（HVAC_FUNC_AC）。★v1.4.60 zone 修正为**全局 0x80000000**：" +
            "logcat 实锤所有开关类通道（POWER/POWER_VR/TEMP_DUAL/ECO）系统侧都用 " +
            "`zone:VehicleZone.ZONE_ALL [0x80000000]`。⚠ 实测车控写入对该通道" +
            "返回 false 但车机实际生效（读回恒 0xff，回读校验失效）——返回 false ≠ 被拒。"
        ),
        VehicleChannel(
            "seat_vent", "主驾座椅通风", FUNC_SEAT_VENT, ZONE_SEAT_DRIVER, 0, 3,
            "★已修正：functionId=0x10050100（原写 0x10050000 错误），zone=1，取值 func+0..3"
        ),
        VehicleChannel(
            "seat_heat", "主驾座椅加热", FUNC_SEAT_HEAT, ZONE_SEAT_DRIVER, 0, 3,
            "★已修正：functionId=0x10050200（原写 0x10050100 错误），zone=1，取值 func+0..3"
        ),
        VehicleChannel(
            "seat_vent_passenger", "副驾座椅通风", FUNC_SEAT_VENT, ZONE_SEAT_PASSENGER, 0, 3,
            "★已修正：functionId=0x10050100 + zone=4（副驾）"
        ),
        VehicleChannel(
            "seat_heat_passenger", "副驾座椅加热", FUNC_SEAT_HEAT, ZONE_SEAT_PASSENGER, 0, 3,
            "★已修正：functionId=0x10050200 + zone=4（副驾）"
        ),
        // ★ v1.4.97 #1（P04 方向盘加热设置不了）：
        //   `steering_heat` 这个 key **从来没进过这张表** → `funcOf()` 返回 -1
        //   → `writeChannel()` 第一行 `if (f <= 0) return false` 直接静默返回，
        //   于是点了 P04 车机没反应、日志里也什么都不打。补进来即可。
        VehicleChannel(
            "steering_heat", "方向盘加热", FUNC_STEERING_WHEEL_HEAT, 1, 0, 3,
            "functionId=0x10090100（HVAC_FUNC_STEERING_WHEEL_HEAT），zone=1（主驾），取值 func+0..3"
        )
    )

    fun byKey(key: String): VehicleChannel? = ALL.firstOrNull { it.key == key }

    /** 读取用户校准后的 functionId */
    fun funcOf(ctx: Context, key: String): Int {
        val def = byKey(key)?.defFunc ?: -1
        return Prefs.sp(ctx).getInt("ch_func_$key", def)
    }

    /** 读取用户校准后的 zone */
    fun zoneOf(ctx: Context, key: String): Int {
        val def = byKey(key)?.defZone ?: ZONE_DRIVER
        val sp = Prefs.sp(ctx)
        // ★ v1.4.75：删掉 v1.4.13 那段「recirc 的 zone 自动改回 0x8」的迁移。
        //   它的前提是「0x8 是权威写法」，但真车日志已证明**循环的真实 zone 是全局 0x80000000**：
        //   ```
        //   读 recirc：zone=0x8 读不到 → 改用 zone=0x-80000000 读到 0x10030101
        //   HVAC_WRITE 0x10030100 zone=-2147483648 value=268632321 → true   ← 全局才写得进
        //   ```
        //   有了那段迁移就形成死循环：读一次把 zone 改回 0x8 → 写用 0x8 → HAL 静默丢弃
        //   → 表现为「内外循环都切不了」。
        //
        // ★★★ v3.6：反过来做**一次性强制迁移** —— 老设备可能把 0x8 persist 在
        //   `ch_zone_recirc` 里（或被 1.4.63 的 zone 记忆机制写成别的值），
        //   而系统 Dock 的权威 zone 是**全局 0x80000000**。
        //   → recirc 无条件用全局 zone，不吃 persist 的旧值。
        if (key == "recirc") return ZONE_RECIRC
        return sp.getInt("ch_zone_$key", def)
    }

    fun save(ctx: Context, key: String, func: Int, zone: Int) {
        Prefs.sp(ctx).edit()
            .putInt("ch_func_$key", func)
            .putInt("ch_zone_$key", zone)
            .apply()
    }

    /** 判断某功能是否已启用（functionId > 0） */
    fun enabled(ctx: Context, key: String): Boolean = funcOf(ctx, key) > 0
}
