package com.car.dockbar.vehicle

import android.content.Context
import com.car.dockbar.util.Logx
import java.lang.reflect.Method

/**
 * 车辆传感器桥（ISensor 通道，纯反射）。
 *
 * ★★ v1.4.72：**连续量与事件量是两套 API，之前只用了连续量**。
 *   - 连续量：`getSensorLatestValue(int) → float`
 *       车内温度 0x100c00 / 车外温度 0x100b00
 *   - 事件量：`getSensorEvent(int) → int`
 *       **点火状态 0x200100**（OFF/ACC/ON/START）
 *       档位 0x200200 / 手刹 0x200300 / 日夜模式 0x201000
 *       **车内 PM2.5 等级 0x202200 / 车外 PM2.5 等级 0x202100**
 *
 *   之前空气质量全 null 的根因不是 ID 错，而是**用连续量接口去读事件量 ID**。
 *   （0x100400/0x100500 那些 AQI 连续量通道本车确实返回 Float.MAX_VALUE。）
 *
 * 信号来源：《Dock桌面关系与车辆信号研究报告》5.3.4/5.3.5/5.3.7（smali 可复核）：
 *  - 点火状态 SENSOR_TYPE_IGNITION_STATE = 0x200100
 *  - 车内 PM2.5 等级 = 0x202200 / 车外 PM2.5 等级 = 0x202100
 *  - 车内温度 0x100c00 / 车外温度 0x100b00
 *
 * ⚠️ 与 VehicleBridge 的关系：ISensor 与 ICarFunction 是 ICar 上两个不同的管理器。
 *    这里只调用 getSensorManager + getSensorLatestValue，**绝不触碰已禁用的
 *    Customize 系列接口**（那会 native 崩溃）。读取全部 runCatching 包裹。
 *
 * ⚠️ 报告 3.3：getSensorLatestValue 是拉取式缓存值，信号未就绪可能返回 0.0f —— 按「无数据」处理。
 */
object SensorBridge {

    // 传感器 ID（ISensor 常量，反射期不依赖厂商类）
    const val AQI_INDOOR = 0x100500       // 车内空气质量指数
    const val AQI_AMBIENT = 0x100400      // 车外空气质量指数
    const val TEMP_INDOOR = 0x100c00      // 车内实际温度
    const val TEMP_AMBIENT = 0x100b00     // 车外环境温度

    // PM2.5 连续量（μg/m³）—— AQI 通道在本车返回 Float.MAX_VALUE 时的备选
    const val PM25_INDOOR = 0x100300      // 车内 PM2.5
    const val PM25_AMBIENT = 0x100200     // 车外 PM2.5

    // ★★ v1.4.72：**事件量**（用 getSensorEvent 读，不是 getSensorLatestValue）
    /** 点火状态：OFF / ACC / ON / START */
    const val IGNITION_STATE = 0x200100
    /** 档位 */
    const val GEAR = 0x200200
    /** 手刹 / EPB */
    const val HANDBRAKE_STATE = 0x200300
    /** 日夜模式 */
    const val DAY_NIGHT = 0x201000
    /** 车外 PM2.5 等级（事件量） */
    const val PM25_LEVEL_AMBIENT = 0x202100
    /** 车内 PM2.5 等级（事件量） */
    const val PM25_LEVEL_INDOOR = 0x202200
    /** 车内 PM2.5 传感器状态（事件量） */
    const val PM25_STATE_INDOOR = 0x202700

    /** 点火状态枚举（与厂商常量一致） */
    object Ignition {
        const val OFF = 0
        const val ACC = 1
        const val ON = 2
        const val START = 3
        fun label(v: Int): String = when (v) {
            OFF -> "熄火"
            ACC -> "ACC"
            ON -> "点火"
            START -> "启动中"
            else -> "未知($v)"
        }
    }

    private var sensorMgr: Any? = null
    private var latestMethod: Method? = null
    /** ★ v1.4.72：事件量读取方法 getSensorEvent(int) → int */
    private var eventMethod: Method? = null

    val isReady: Boolean get() = sensorMgr != null && latestMethod != null

    /** 从 VehicleBridge 持有的 ICar 实例上取 ISensor 管理器（反射）。
     *  ★ v1.4.48：失败后允许重试——用户真车日志实锤「弹窗打开时车控未连接 → init 失败
     *  → 永不重试 → AQI 永远无数据」。现在只要车控会话后来建立了，就能补上。 */
    @Synchronized
    fun init(ctx: Context): Boolean {
        if (isReady) return true
        val car = VehicleBridge.carInstance() ?: return false   // 车控未连接：不标记 inited，下次再试
        runCatching {
            val sm = car.javaClass.methods
                .firstOrNull { it.name == "getSensorManager" && it.parameterTypes.isEmpty() }
                ?.invoke(car) ?: return false
            sensorMgr = sm
            latestMethod = sm.javaClass.methods.firstOrNull {
                it.name == "getSensorLatestValue" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            // ★ v1.4.72：事件量接口（点火状态 / 档位 / PM2.5 等级都靠它）
            eventMethod = sm.javaClass.methods.firstOrNull {
                it.name == "getSensorEvent" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            Logx.d("SensorBridge：getSensorLatestValue=${latestMethod != null} getSensorEvent=${eventMethod != null}")
        }.onFailure { Logx.d("SensorBridge：初始化失败 ${it.javaClass.simpleName} ${it.message}") }
        if (isReady) {
            Logx.d("SensorBridge：就绪（ISensor.getSensorLatestValue）")
            // 首次就绪时把各信号读一遍打进日志，用于核对真车数值。
            // ★ 连同 PM2.5 一起探：AQI 在本车可能无数据，要看备选通道有没有值。
            for (id in intArrayOf(
                AQI_INDOOR, AQI_AMBIENT, TEMP_INDOOR, TEMP_AMBIENT,
                PM25_INDOOR, PM25_AMBIENT
            )) {
                Logx.d("SensorBridge 探测(连续量) 0x${id.toString(16)} = ${readFloat(id)}")
            }
            // ★ v1.4.72：事件量探测（点火状态 / 档位 / PM2.5 等级）
            for (id in intArrayOf(
                IGNITION_STATE, GEAR, HANDBRAKE_STATE, DAY_NIGHT,
                PM25_LEVEL_AMBIENT, PM25_LEVEL_INDOOR, PM25_STATE_INDOOR
            )) {
                val v = readEvent(id)
                val extra = if (id == IGNITION_STATE && v != null) "（${Ignition.label(v)}）" else ""
                Logx.d("SensorBridge 探测(事件量) 0x${id.toString(16)} = $v$extra")
            }
        }
        return isReady
    }

    /**
     * 读连续量传感器（float）。未就绪 / 异常 / 无效哨兵 → null。
     *
     * ★ 无效哨兵不只有 0.0f。真车实测（CX11，2026-10-20 18:01）：
     * ```
     * 0x100500（车内 AQI）= 3.4028235E38   ← Float.MAX_VALUE
     * 0x100400（车外 AQI）= 3.4028235E38
     * 0x100c00（车内温度）= 26.7           ← 正常
     * ```
     * 早期只排除 0.0f，于是把 Float.MAX_VALUE 直接 `%.0f` 打印成
     * 340282346638528860000000000000000000000 —— 这就是「AQI 数字不对」的根因。
     * 现在把 NaN / ±Infinity / Float.MAX_VALUE 一并当「无数据」。
     */
    fun readFloat(sensorId: Int): Float? {
        val m = latestMethod ?: return null
        val s = sensorMgr ?: return null
        return runCatching {
            val r = m.invoke(s, sensorId) as? Float
            if (r == null || r == 0.0f) return@runCatching null
            if (r.isNaN() || r.isInfinite()) return@runCatching null
            if (r >= Float.MAX_VALUE) return@runCatching null
            r
        }.getOrElse {
            Logx.d("SensorBridge 读 0x${sensorId.toString(16)} 失败：${it.message}")
            null
        }
    }

    /**
     * ★★ v1.4.72：读**事件量**（int）。
     *
     * 事件量与连续量是两套 API：
     *  - 连续量 `getSensorLatestValue(int) → float`（温度）
     *  - 事件量 `getSensorEvent(int) → int`（点火状态 / 档位 / PM2.5 等级…）
     *
     * 之前空气质量全 null 就是因为用错了接口。
     */
    fun readEvent(sensorId: Int): Int? {
        val m = eventMethod ?: return null
        val s = sensorMgr ?: return null
        return runCatching {
            val r = m.invoke(s, sensorId) as? Int ?: return@runCatching null
            // 无效哨兵：厂商用 -1 / 0x7fffffff 表示无数据
            if (r == -1 || r == Int.MAX_VALUE || r == 0x7fffffff) return@runCatching null
            r
        }.getOrElse {
            Logx.d("SensorBridge 读事件 0x${sensorId.toString(16)} 失败：${it.message}")
            null
        }
    }

    /** ★ v1.4.72：当前点火状态（null = 读不到；否则用 [Ignition.label] 转文字） */
    fun readIgnitionState(): Int? = readEvent(IGNITION_STATE)

    /**
     * 读空气质量：**优先 AQI，取不到退回 PM2.5**（μg/m³）。
     *
     * @return Pair(数值, 单位标签)；都取不到返回 null，调用方应显示 `--`
     */
    /**
     * 读空气质量：**优先 AQI，取不到退回 PM2.5**（μg/m³）。
     *
     * @return Pair(数值, 单位标签)；都取不到返回 null，调用方应显示 `--`
     */
    fun readAirQuality(indoor: Boolean): Pair<Float, String>? {
        // ① 事件量通道：PM2.5 等级（本车真实可用的通道）
        val lvl = readEvent(if (indoor) PM25_LEVEL_INDOOR else PM25_LEVEL_AMBIENT)
        if (lvl != null) return lvl.toFloat() to "PM2.5等级"
        // ② 连续量 AQI
        val aqi = readFloat(if (indoor) AQI_INDOOR else AQI_AMBIENT)
        if (aqi != null) return aqi to "AQI"
        // ③ 连续量 PM2.5
        val pm = readFloat(if (indoor) PM25_INDOOR else PM25_AMBIENT)
        if (pm != null) return pm to "PM2.5"
        return null
    }

    /**
     * ★★ v1.4.64：暴力扫描 ISensor 通道，找出真实的空气质量 / 温度 ID。
     *
     * 真车日志实锤（1.4.62，21:04）：
     * ```
     * 0x100500 = null  0x100400 = null  0x100300 = null  0x100200 = null  ← 空气质量全无
     * 0x100c00 = 33.5（车内温度）  0x100b00 = 22.0（车外温度）  ← 正常
     * ```
     * → ISensor 是通的（温度能读），但空气质量 ID 猜错了（oneOS_Dock 那张表是别的车型）。
     * 与其猜，不如把 ISensor 的 ID 空间扫一遍：0x100000..0x200000，步长 0x100。
     */
    fun scanSensors(): String {
        if (latestMethod == null || sensorMgr == null) {
            return "ISensor 未就绪，无法扫描（需要先打开温度弹窗触发 init）"
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val hits = mutableListOf<String>()
        var tried = 0
        // ISensor 的 ID 空间：观察已知值 0x100200~0x100c00，扫 0x100000..0x200000
        var id = 0x100000
        while (id <= 0x200000) {
            tried++
            val v = runCatching { latestMethod!!.invoke(sensorMgr, id) as? Float }.getOrNull()
            if (v != null && !v.isNaN() && !v.isInfinite() && v < Float.MAX_VALUE && v != 0.0f) {
                hits.add("0x%06X = %s".format(id, if (v > 1000f) "%.0f".format(v) else "%.2f".format(v)))
            }
            id += 0x100
            if (hits.size > 200) break
        }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        return buildString {
            append("===== ISensor 通道扫描 =====\n")
            append("范围 0x100000..0x200000 步长 0x100，共 ").append(tried)
                .append(" 次，耗时 ").append(ms).append("ms\n\n")
            if (hits.isEmpty()) {
                append("★ 没有命中（全是 null / 0 / Float.MAX）\n")
            } else {
                append("命中 ").append(hits.size).append(" 条：\n")
                hits.forEach { append("  ").append(it).append('\n') }
            }
            append("\n判读：\n")
            append(" · 值在 0~500 且恒定的 → 空气质量（AQI）\n")
            append(" · 值在 0~150 的 → PM2.5\n")
            append(" · 值在 -40~85 的 → 温度\n")
            append(" · 值在 0~100% 且随门窗变化的 → 湿度\n")
            append("============================\n")
        }
    }

    /**
     * ★★ v1.4.64：空气质量专属扫描（给主界面「扫描 ISensor」按钮用）。
     * 见 [scanSensors]。
     */
    fun scanAirQuality(): String = scanSensors()
}
