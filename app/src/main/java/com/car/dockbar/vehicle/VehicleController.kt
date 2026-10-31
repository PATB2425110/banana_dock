package com.car.dockbar.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.ui.AirFlowDrawable
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/**
 * 空调 / 座椅控制的高层封装。
 *
 * 取值编码遵循已验证的风量规律：
 *   value = functionId + index      （低 8 位 = 档位 / 枚举）
 *   例：风量手动 func=0x10020100，1 档 → 0x10020101
 *
 * 读回来的值若不是该前缀，则退化为「直接把返回值当 index 用」。
 */
object VehicleController {

    data class State(
        val connected: Boolean = false,
        val backend: String = "未连接",
        val driverTempC: Float? = null,
        val passengerTempC: Float? = null,
        val fanLevel: Int = 0,
        val fanMax: Int = 9,
        val fanAuto: Boolean = false,
        val recircInside: Boolean = false,
        val airDirection: Int = 1,
        val seatVent: Int = 0,
        val seatHeat: Int = 0,
        val acOn: Boolean = false,
        // ★ v1.4.35：温度弹窗 / 座椅弹窗所需
        val seatVentPassenger: Int = 0,
        val seatHeatPassenger: Int = 0,
        val defrostFront: Boolean = false,      // 前挡除霜/加热（0x10040100）
        val defrostRear: Boolean = false,       // 后挡除霜/加热（0x10040300）
        val steeringHeat: Int = 0               // 方向盘加热档位（0x10090100）
    )

    /** 空调风向：吹脸 / 吹脚 / 吹前挡 三个方向可按位组合（共 7 种模式） */
    const val AIR_FACE = AirFlowDrawable.FACE      // 1
    const val AIR_FOOT = AirFlowDrawable.FOOT      // 2
    const val AIR_DEFROST = AirFlowDrawable.DEFROST // 4
    /** 7 种组合的循环切换顺序（吹脸→吹脸+吹脚→吹脚→吹脚+吹前挡→吹前挡→吹脸+吹前挡→全开） */
    val AIR_MODES = AirFlowDrawable.MODES

    /**
     * 车机可调温度区间（℃）。
     * 来源：oneOS_Dock 的 HvacConstants —— `TEMP_C_MIN="15.5"` / `TEMP_C_MAX="28.5"`，
     * 对应 `MAX_SHOW_TAMP="HI"` / `MIN_SHOW_TAMP="LO"`。
     * 注意：车机实际上下限由 MaxTempSignal(0x10060300)/MinTempSignal(0x10060500) 给出，
     * 这里用系统常量作为默认兜底。
     */
    /**
     * ★ v1.4.81：风量「AUTO」档位值。
     * 来源：oneOS_Dock 的 `MANUAL_FAN_SPEED_LEVEL_AUTO = "10"` —— AUTO 是写在
     * **手动风量通道 0x10020100** 上的，值 = 10（复合编码 0x1002010A）。
     * 这台车**没有** AUTO 通道（0x10020200 在系统自己日志里就是 notactive + 255）。
     */
    const val FAN_LEVEL_AUTO = 10

    /**
     * ★★ v1.4.84：AUTO 风道「档位 → 通道值」映射（**顺序是乱的！**）
     *
     * 权威来源：osn_fan（系统 SystemUI 插件）的 `mapAutoValueToLevel`：
     * ```
     * 0x10020204 -> 1 档
     * 0x10020201 -> 2 档
     * 0x10020202 -> 3 档
     * 0x10020203 -> 4 档
     * 0x10020205 -> 5 档
     * ```
     * 也就是说 AUTO 风道 1~5 档对应的值**不是**顺序的 01..05。
     * 我们之前按 1→0x10020201 写 → 车机当成 2 档 → 档位全错。
     */
    val FAN_AUTO_VALUE_BY_LEVEL = intArrayOf(
        0,                       // 占位（档位 0 不用）
        0x10020204,              // 1 档
        0x10020201,              // 2 档
        0x10020202,              // 3 档
        0x10020203,              // 4 档
        0x10020205               // 5 档
    )

    /** AUTO 档位 → 通道值（0x10020200 | 档位值），level 1..5 */
    fun fanAutoValue(level: Int): Int {
        val v = FAN_AUTO_VALUE_BY_LEVEL.getOrNull(level.coerceIn(1, 5)) ?: 0x10020202
        return (0x10020200 and 0xFFFFFF00.toInt()) or (v and 0xFF)
    }

    /** 通道值 → AUTO 档位（0 = 不是 AUTO 档位值） */
    fun fanAutoLevelOf(value: Int): Int {
        val low = value and 0xFF
        for (l in 1..5) if ((FAN_AUTO_VALUE_BY_LEVEL[l] and 0xFF) == low) return l
        return 0
    }

    const val TEMP_MIN_C = 15.5f

    /**
     * ★ v1.4.92：温度回读的防抖延迟（ms）。
     * 连点 ± 时每次都会重置这个定时器，**只有最后一次操作后过这么久才真正回读**。
     */
    private const val TEMP_READBACK_DELAY_MS = 1000L
    const val TEMP_MAX_C = 28.5f

    /** 座椅通风 / 加热档位上限（0=关，1..3） */
    const val SEAT_LEVEL_MAX = 3

    // ★ v1.4.48 修正（用户真车日志）：前除霜真车 funcId 是 0x10040200（事件映射表），
    //   而非 oneOS_Hvac 常量表里的 0x10040100；三个信号的 areaId 在真车都是全局区
    //   0x80000000（日志实锤：0x10010300 / 0x10030100 事件 zone=-2147483648），不再用 zone=1
    /** 前挡除霜/加热（HVAC_DEFROST_FRONT） */
    private const val FUNC_DEFROST_FRONT = 0x10040200
    /** 后挡除霜/加热（HVAC_DEFROST_REAR） */
    private const val FUNC_DEFROST_REAR = 0x10040300
    /** 方向盘加热（STEERING_WHEEL_HEAT） */
    private const val FUNC_STEERING_HEAT = 0x10090100
    /** 座椅记忆-应用位置（SETTING_FUNC_SEAT_POSITION_SET）——写位置号 1/2/3，车机待实测 */
    private const val FUNC_SEAT_POSITION_SET = 0x2d400200
    /** 全局区 areaId：真车对无分区信号统一用 0x80000000 */
    private const val ZONE_GLOBAL = Int.MIN_VALUE

    /**
     * 座椅记忆-当前位置号（SETTING_FUNC_SEAT_NUM）。
     *
     * 来源：oneOS_Hvac 的 `GlyCarPropertyIds` → `SEAT_NUM = 0x2d410f00`。
     * 读它就能知道当前处于 1/2/3 哪个记忆位，弹窗据此高亮，而不是靠「点了才亮一下」。
     */
    private const val FUNC_SEAT_NUM = 0x2d410f00

    /**
     * ★★ v1.4.89：座椅记忆**当前位置**（读这个才靠谱）。
     *
     * 来源：反编译参考 2.3 节（`SETTING_FUNC_*`，来自**座椅调节模块**，不是 HVAC）：
     * ```
     * SEAT_POSITION_SAVE = 0x2d400100   ← 当前处于第几号记忆位（1/2/3）
     * SEAT_POSITION_SET  = 0x2d400200   ← 调用第几号记忆位（写 1/2/3）
     * SEAT_NUM           = 0x2d410f00   ← 记忆位数量（本车 = 3，且该通道读不到）
     * ```
     * 我们之前只用了 `SEAT_NUM`（读不到），**真正的「选/读」两个通道压根没接**。
     */
    private const val FUNC_SEAT_POSITION_SAVE = 0x2d400100
    // 注：FUNC_SEAT_POSITION_SET 已在上面（0x2d400200）声明

    /** 座椅记忆位数量（用户确认：本车 3 个） */
    const val SEAT_MEMORY_SLOTS = 3

    /** 座椅记忆的 zone 候选：主驾 1 / 副驾 4 / 前排 0x8 / 全局 */
    private val SEAT_MEMORY_ZONES = intArrayOf(1, 4, 0x8, ZONE_GLOBAL)

    /** 浮点温度的「可信区间」——挡掉空调关闭时返回的伪值（实测为 5.0） */
    const val TEMP_VALID_MIN = 10f
    const val TEMP_VALID_MAX = 40f

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(State) -> Unit>()

    /**
     * ★★ v1.4.78：**HAL 读写专用后台线程**。
     *
     * 为什么要：调温一次要打 4~6 次 binder IPC（读上下限 / 写 / 写回读…），
     * 每次几十毫秒。跑在主线程上 → **点温度 ± 会顿一下**（用户实测反馈）。
     * 现在：主线程只做乐观更新（立即响应），HAL 读写全丢到这个单线程上。
     */
    private val hal = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dock-hvac-write").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }

    // ===== ★ v1.4.97 #2/#3：写后刷新「合并」 =====
    //
    // 原来每次点 ± 都 `main.postDelayed({ refresh(ctx) }, 400)` —— refresh() 会
    // 挨个读 14 个通道（14 次 binder IPC）+ 2 次 ISensor，而它是跑在**主线程**上的。
    // 连点 10 次 = 400ms 后排队跑 10 次全量读 → 主线程被 binder 堵住 → 点着点着就卡。
    // 现在改成：连点只保留最后一次，停手 [REFRESH_DEBOUNCE_MS] 后才读一次。
    private const val REFRESH_DEBOUNCE_MS = 900L
    private var refreshCtx: Context? = null
    private val pendingRefresh = Runnable { refreshCtx?.let { refresh(it) } }

    /** 合并刷新：重复调用只生效最后一次 */
    private fun scheduleRefresh(ctx: Context, delayMs: Long = REFRESH_DEBOUNCE_MS) {
        refreshCtx = ctx
        main.removeCallbacks(pendingRefresh)
        main.postDelayed(pendingRefresh, delayMs)
    }

    @Volatile
    var state: State = State()
        private set

    /**
     * ★ v3.13.18：**用户刚操作过的风量目标**（+ 时间戳）。
     *
     * 用途：防止「回读到车机还没处理完的旧档位」把刚点的乐观值顶掉，
     *   导致 Dock 上的风量数字连点时来回摆动（见 withRaw 的 "fan_manual" 分支）。
     *
     * pendingFanLevel < 0 = 当前没有待保护的乐观值。
     */
    @Volatile
    private var pendingFanLevel: Int = -1
    @Volatile
    private var pendingFanAuto: Boolean = false
    @Volatile
    private var pendingFanAt: Long = 0L

    /** 乐观值保护窗口（ms）：这段时间内忽略不一致的风量回读 */
    private const val PENDING_FAN_HOLD_MS = 1500L

    /** 记录用户刚操作的风量目标（手动档 / AUTO） */
    private fun markPendingFan(level: Int, auto: Boolean) {
        pendingFanLevel = level
        pendingFanAuto = auto
        pendingFanAt = android.os.SystemClock.elapsedRealtime()
    }

    private var appCtx: Context? = null
    private var watcherRegistered = false
    private var lastFanAuto = false

    // =====================================================================
    // 生命周期
    // =====================================================================

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // 车机在空调关闭时通常返回 UNKNOWN；先恢复上次有效设定，避免 Dock 显示两个 OFF。
        val cachedDriverTemp = Prefs.lastDriverTempC(ctx)
        val cachedPassengerTemp = Prefs.lastPassengerTempC(ctx)
        if ((state.driverTempC == null && cachedDriverTemp != null) ||
            (state.passengerTempC == null && cachedPassengerTemp != null)
        ) {
            update(
                state.copy(
                    driverTempC = state.driverTempC ?: cachedDriverTemp,
                    passengerTempC = state.passengerTempC ?: cachedPassengerTemp
                )
            )
        }
        // ★★ v1.4.51 一级隔离开关：完全不碰厂商 API。
        //   连 Car.create() 都不调 —— 用于二分「闪退是不是厂商车控库 abort 引起的」。
        if (!Prefs.vehicleApiEnabled(ctx)) {
            Logx.w("车控通道已整体关闭（二分开关）：本次不调用任何厂商接口，空调状态保持上次缓存值")
            update(state.copy(connected = false, backend = "已关闭（排查闪退）"))
            return
        }
        if (Prefs.simulate(ctx)) {
            Logx.d("车控：模拟模式，跳过厂商通道")
            // 给一组看起来合理的初值，方便直接调 UI
            if (state.driverTempC == null) {
                state = state.copy(
                    driverTempC = 22.0f,
                    passengerTempC = 22.0f,
                    fanLevel = 3,
                    fanMax = 9,
                    fanAuto = false
                )
            }
            update(state.copy(connected = true, backend = "模拟数据"))
            return
        }
        dumpCarPermissions(ctx)
        runCatching {
            val ok = VehicleBridge.init(ctx)
            if (ok) {
                // ★ v1.4.53：事件监听分三态，别再让「温度监听」开关形同虚设。
                //   旧逻辑：只有基础监听开着才会注册 → 用户打开「温度事件监听」毫无反应
                //   （1.4.51/1.4.52 环境摘要里 `温度监听=开` 但日志一条回调都没有，就是这个原因）。
                //
                //   状态机：
                //     · 基础监听开        → 注册全部通道（含温度，若温度开关也开）
                //     · 基础监听关+温度开  → **只注册温度通道**（拿回 1.4.14 时代能读的温度）
                //     · 两个都关          → 纯轮询
                //
                //   ⚠ 温度 0x10060100 是 Customize（float）通道，1.4.47 时代普通 App
                //     收到它的回调会 native SIGSEGV（signal 11, fault addr 低位 0xbc0）。
                //     但 19:20 的 SIGABRT 已证实是 dock-contrast 截屏线程，与监听无关。
                //     崩了会被 App 的 streak>=1 熔断自动关回去，可以放心试。
                val tempOnly = !Prefs.vehicleWatcherEnabled(ctx) && Prefs.vehicleTempWatcherEnabled(ctx)
                if (tempOnly) {
                    Logx.w("⚠ 仅注册「温度」事件监听（实验）：用 float 回调拿温度。" +
                        "若开始闪退，下次启动会自动关回纯轮询。")
                    runCatching { registerTempOnlyWatcher(ctx) }.onFailure {
                        Logx.e("温度事件监听注册失败，退回轮询模式：${it.javaClass.simpleName} ${it.message}")
                    }
                } else if (Prefs.vehicleWatcherEnabled(ctx)) {
                    // 事件监听：用动态代理注册到系统 HAL。已知部分车机在收到第一次回调时
                    // 原生层会拖崩进程（runCatching 兜不住），故默认关闭，仅靠轮询刷新。
                    runCatching { registerWatcher(ctx) }.onFailure {
                        Logx.e("车控事件监听注册失败，退回轮询模式：${it.javaClass.simpleName} ${it.message}")
                    }
                } else {
                    Logx.d("车控事件监听已关闭（PollOnly），仅靠定时刷新（排查闪退用）")
                }
                refresh(ctx)
            } else {
                update(state.copy(connected = false, backend = "未连接"))
            }
        }.onFailure {
            Logx.e("车控初始化失败（真实通道）：${it.javaClass.simpleName} ${it.message}", it)
            update(state.copy(connected = false, backend = "未连接"))
        }
    }

    /**
     * 车控权限自检 —— 这是「能不能真的调空调」的决定性开关。
     *
     * ★ 2026-10-20 关键发现：对照能控内外循环的第三方 App「领克 OSN 车机助手
     *   (cn.navitool)」——它是普通 App、无 sharedUserId，但 Manifest 里声明了
     *   `android.car.permission.CONTROL_CAR_CLIMATE` 等权限，且 App 内有
     *   「正在授予 ADB 权限...」的流程。它之所以能写成功，不是用了更牛的 API，
     *   而是**拿到了车控写入权限**；服务端的 uid/权限校验放行，setFunctionValue 才返回 true。
     *
     *   反观本项目：权限声明了但**从未被授予**（signature 级权限安装时不会自动授予），
     *   所以反射调 setFunctionValue 一律被服务端挡回 false。
     *   这也解释了 osn_fan_widget 为什么「只能读」——读不校验权限，写才校验。
     *
     *   授予方式（车机连一次 adb 即可，授予结果写进 packages.xml，**重启不丢**）：
     *     adb shell pm grant com.car.dockbar android.car.permission.CONTROL_CAR_CLIMATE
     */
    private fun dumpCarPermissions(ctx: Context) {
        val perms = listOf(
            "android.car.permission.CONTROL_CAR_CLIMATE",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.permission.WRITE_SECURE_SETTINGS"
        )
        for (p in perms) {
            val granted = runCatching {
                ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val short = p.removePrefix("android.car.permission.").removePrefix("android.permission.")
            if (granted) {
                Logx.d("权限 $short = 已授予")
            } else {
                Logx.w("权限 $short = ★未授予（车控写入大概率被服务端拒绝；" +
                    "需 adb shell pm grant ${ctx.packageName} $p）")
            }
        }
    }

    /**
     * ★ v1.4.53：**只注册温度通道**的事件监听。
     *
     * 为什么单独一个函数：温度（0x10060100）在本车只有 Customize float 回调能给出真实值
     * （int 通道 getFunctionValue 恒返回 0xff）。1.4.14 时代温度就是这么读到的：
     * ```
     * 14:46:28.122  D  车控事件 0x10060100 zone=1 value=24.0
     * ```
     * 后来为消崩把整个 watcher 关掉，温度一起丢了 → 一直显示 15.5℃。
     *
     * 现在只注册温度一个通道，把回调面缩到最小；崩了会被 App 的熔断自动关回去。
     */
    private fun registerTempOnlyWatcher(ctx: Context) {
        val keys = listOf("temp_driver", "temp_passenger")
        val funcs = keys.map { VehicleChannels.funcOf(ctx, it) }.filter { it > 0 }.distinct().toIntArray()
        if (funcs.isEmpty()) return
        val ok = VehicleBridge.registerWatcher(funcs) { fid, zone, value ->
            main.post {
                runCatching {
                    Logx.d("温度事件 0x${fid.toString(16)} zone=$zone value=$value")
                    val t = when (value) {
                        is Float -> value
                        is Number -> value.toFloat()
                        else -> null
                    } ?: return@runCatching
                    val key = if (zone == VehicleChannels.zoneOf(ctx, "temp_passenger")) "temp_passenger"
                    else "temp_driver"
                    val norm = normalizeTempC(t)
                    if (norm == null) {
                        Logx.w("温度事件 ${"%.1f".format(t)}℃ 超出合理区间，忽略")
                        return@runCatching
                    }
                    Logx.d("温度事件 ${if (key == "temp_driver") "主驾" else "副驾"} = ${"%.1f".format(norm)}℃")
                    update(
                        if (key == "temp_driver") state.copy(driverTempC = norm)
                        else state.copy(passengerTempC = norm)
                    )
                }.onFailure { Logx.e("温度事件处理异常", it) }
            }
        }
        Logx.d("温度事件监听注册：func=${funcs.joinToString { "0x" + it.toString(16) }} → ${if (ok) "OK" else "FAIL"}")
    }

    private fun registerWatcher(ctx: Context) {
        if (watcherRegistered) return

        // ★ 温度通道默认 **不注册进 watcher** —— 这是 CX11 上连续 native 崩溃的根因。
        //
        // 机制：0x10060100 在 ECarX 上是 Customize（float）通道。一旦把它放进监听数组，
        // 厂商库就会用 `onCustomizeFunctionValueChanged(int, int, float)` 把温度推过来，
        // 普通 App 进程收到这条回调 **立刻 SIGSEGV**（不是异常，Java 层拦不住）。
        // 真车取证：11 次崩溃里 9 次的落盘日志最后一行都是 `车控事件 0x10060100 … value=21.0`。
        //
        // 我们虽然「没有主动调用 getCustomizeFunctionValue」，但注册 watcher 等于
        // 把这条通道打开了 —— 这就是「已经移除 Customize 调用却还在崩」的原因。
        val tempKeys = listOf("temp_driver", "temp_passenger")
        val baseKeys = listOf(
            "fan_manual", "fan_auto", "recirc", "air_direction", "ac",
            "seat_vent", "seat_heat"
        )
        val keys = if (Prefs.vehicleTempWatcherEnabled(ctx)) baseKeys + tempKeys else baseKeys
        if (Prefs.vehicleTempWatcherEnabled(ctx)) {
            Logx.w("⚠ 温度事件监听已开启（实验开关）：若车机开始闪退请立即关掉")
        }

        // ★ v3.7：**回退** —— 不把 AUTO 总开关加进 watcher 列表。
        //   理由：watcher 回调在部分车机「收到第一次回调时原生层会拖崩进程」
        //   （见上方注释），取证功能绝不能给日常运行增加这个风险。
        //   AUTO 总开关的取证改走「取证窗口内的轮询读」，见 evidenceOn()。
        val funcs = keys.map { VehicleChannels.funcOf(ctx, it) }.filter { it > 0 }.toIntArray()
        if (funcs.isEmpty()) return
        watcherRegistered = VehicleBridge.registerWatcher(funcs) { fid, zone, value ->
            main.post {
                runCatching {
                    val c = appCtx ?: return@runCatching
                    Logx.d("车控事件 0x${fid.toString(16)} zone=$zone value=$value")
                    // ★★★ v3.7：循环通道的**专项取证** —— **默认关闭**，
                    //   只在「抓循环取证」按钮打开的 2 分钟窗口内输出。
                    //   watcher 是厂商 HAL 的跨进程回调：系统 Dock / 系统空调 App
                    //   改了循环通道，HAL 会把变化推给我们（**包括我们**）。
                    //   → 用户在系统里切循环，这里就能看到系统写进去之后的读回值。
                    if (evidenceOn() && fid == VehicleChannels.FUNC_CIRCULATION) {
                        val n = (value as? Number)?.toInt()
                        val hex = if (n == null) "?" else "0x" + Integer.toHexString(n)
                        Logx.d(
                            "★ 取证：内外循环 系统侧变化 → value=$n ($hex) zone=$zone " +
                            "判定=${if (n == null) "?" else recircTextOf(n)}"
                        )
                    }
                    // 主驾/副驾温度共用 functionId 0x10060100，必须靠 zone 区分（1=主驾 4=副驾），
                    // 否则两者的变化都会被错误处理成主驾温度（导致数值不对）。其余通道只看 functionId。
                    val key = keys.firstOrNull { k ->
                        VehicleChannels.funcOf(c, k) == fid && when (k) {
                            "temp_driver", "temp_passenger" -> VehicleChannels.zoneOf(c, k) == zone
                            else -> true
                        }
                    }
                    if (key == null) {
                        refresh(c)
                        return@runCatching
                    }
                    // 温度走 onCustomizeFunctionValueChanged（Float），其余走 onFunctionValueChanged（Int）
                    if (value is Float && (key == "temp_driver" || key == "temp_passenger")) {
                        val t = normalizeTempC(value)
                        update(
                            if (key == "temp_driver") state.copy(driverTempC = t)
                            else state.copy(passengerTempC = t)
                        )
                    } else {
                        val v = (value as? Number)?.toInt() ?: return@runCatching
                        applyRaw(key, v)
                    }
                }.onFailure {
                    Logx.e("车控事件处理异常 0x${fid.toString(16)} zone=$zone value=$value", it)
                }
            }
        }
    }

    /** watcher 回调：把某个通道的原始值并入当前 State（无效读数不覆盖已知状态） */
    private fun applyRaw(key: String, raw: Int) {
        val c = appCtx ?: return
        update(withRaw(state, key, raw, c))
    }

    // =====================================================================
    // 刷新
    // =====================================================================

    /**
     * ★ v3.4：刷新入口。**在主线程被调用时整体丢到后台线程**再跑。
     *
     * refresh() 要读 14 个通道（14 次 binder IPC）+ 2 个 ISensor + 1 次点火状态，
     * 跑在主线程上会让「点温度 ±」顿一下；连点时更糟（v1.4.97 只合并了调用次数，
     * 没解决"单次就很慢"的问题）。
     *
     * 为什么可以安全地丢后台：
     *  - 所有 State 监听器都自己 `main.post { ... }` 回主线程（DockOverlayService /
     *    AirDirection / HvacControl / Seat / Temperature 五个都是），
     *    或走 `sendBroadcast`（VehicleControlService）→ 后台线程调用是安全的；
     *  - 所有写操作本来就跑在 [hal] 这条单线程上，天然串行，不会出现并发交错。
     */
    fun refresh(ctx: Context? = null) {
        val c = ctx ?: appCtx ?: return
        // 一级隔离开关：整体关闭时一次厂商接口都不调
        if (!Prefs.vehicleApiEnabled(c)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hal.execute { refreshInner(c) }
            return
        }
        refreshInner(c)
    }

    /** 真正干活的刷新（可在任意线程跑） */
    private fun refreshInner(c: Context) {
        // ★ v1.4.72：读一次点火状态（0x200100，事件量）。
        //   熄火时车机侧不响应写入 —— 与其白发 10 次写不如跳过。
        SensorBridge.readIgnitionState()?.let { ignitionState = it }

        // ★★ v1.4.74：温度改用 **ISensor 主动读**。
        //   之前温度只靠 watcher 回调（onCustomizeFunctionValueChanged），
        //   而回调**只在温度变化时**才推送 → 不调温就永远没数据，显示一直 15.5。
        //   真车 ISensor 扫描实锤：0x100C00 = 29.7（车内实际温度）、0x100B00 = 20.0（车外），
        //   这是**稳定可读**的连续量，直接主动读即可。
        //   （0x10060100 读回恒 0xff，那是「设定温度」通道，本车读不出来。）
        val indoorT = SensorBridge.readFloat(SensorBridge.TEMP_INDOOR)
        val ambientT = SensorBridge.readFloat(SensorBridge.TEMP_AMBIENT)
        // ★★ v1.4.75：**只更新「车内实际温度」，不碰「空调设定温度」**。
        //   之前直接覆盖 driverTempC → Dock 上显示的设定温度被实际舱温顶掉了
        //   （用户反馈「你把车内温度跟空调温度绑定了」）。
        //   设定温度（driverTempC）只来自 0x10060100 的 watcher 回调 / Customize 读。
        if (indoorT != null && indoorT > -40f && indoorT < 90f) {
            val cur = cabinTempC
            if (cur == null || Math.abs(cur - indoorT) >= 0.05f) cabinTempC = indoorT
        }
        if (ambientT != null && ambientT > -40f && ambientT < 90f) {
            ambientTempC = ambientT
        }
        if (Prefs.simulate(c)) {
            update(state.copy(connected = true, backend = "模拟数据"))
            return
        }
        if (!VehicleBridge.isReady) {
            update(state.copy(connected = false, backend = "未连接"))
            return
        }

        // 真实通道：记录每个通道的原始返回值，方便在车机上对照硬件校准编码
        val raws = mutableMapOf<String, Int>()

        // 逐个通道读取，但只读有效值并入 State；
        // 读到 255 / 0xFF / 0 这类"无效哨兵"时保留现有状态，避免把已知状态冲掉
        // （本机 getFunctionValue 对未读通道统一返回 255，刷新里若直接覆盖会让风速被清成 --、recirc 误显开启）
        var s = state.copy(connected = true, backend = VehicleBridge.backend.label)
        fun apply(key: String) {
            val raw = read(key, c) ?: return
            raws[key] = raw
            s = withRaw(s, key, raw, c)
        }
        apply("fan_auto")
        apply("fan_manual")
        apply("recirc")
        apply("air_direction")
        apply("ac")
        apply("seat_vent")
        apply("seat_heat")
        apply("seat_vent_passenger")
        apply("seat_heat_passenger")

        // ★ v3.7：**取证窗口内**才做额外取证（默认关闭 → 平时零额外 binder 调用）
        if (evidenceOn()) evidencePoll(c)

        // ★ v1.4.35：除霜 / 方向盘加热（报告 funcId，zone=1 主驾区；裸值 10/11 开关、0..3 档位）
        readBare(FUNC_DEFROST_FRONT)?.let { raw ->
            raws["defrost_front"] = raw
            boolOf(raw)?.let { b -> s = s.copy(defrostFront = b) }
        }
        readBare(FUNC_DEFROST_REAR)?.let { raw ->
            raws["defrost_rear"] = raw
            boolOf(raw)?.let { b -> s = s.copy(defrostRear = b) }
        }
        readBare(FUNC_STEERING_HEAT)?.let { raw ->
            raws["steering_heat"] = raw
            val idx = raw and 0xFF
            if (idx in 0..SEAT_LEVEL_MAX) s = s.copy(steeringHeat = idx)
        }

        // ===== 温度 =====
        // ⚠ 温度**只走 int 通道**，不再使用 getCustomizeFunctionValue。
        //   原因：Customize 系列接口在普通 App 上会触发厂商库 native SIGSEGV（详见 VehicleBridge 注释），
        //   且实测空调关闭时它返回伪值 5.0。
        //   int 通道编码：低 8 位 = round(℃ / 0.2)，实测 128 → 25.6℃、119 → 23.8℃。
        //
        // 每一侧独立判定：读到合法值就覆盖；读到哨兵（0xfd/0xfe/0xff）保留原值。
        // 温度置 null（UI 显示 OFF）只在浮点/整型都给出明确「无效」时发生。
        val rawTempD = read("temp_driver", c)
        val rawTempP = read("temp_passenger", c)
        if (rawTempD != null) raws["temp_driver"] = rawTempD
        if (rawTempP != null) raws["temp_passenger"] = rawTempP

        if (!isSentinel(rawTempD ?: -1) && rawTempD != null) {
            tempFromRaw("temp_driver", rawTempD, c)?.let { t -> s = s.copy(driverTempC = t) }
        }
        if (!isSentinel(rawTempP ?: -1) && rawTempP != null) {
            tempFromRaw("temp_passenger", rawTempP, c)?.let { t -> s = s.copy(passengerTempC = t) }
        }

        update(s)
        applyAmbientSeatOnce(c)

        // 真实模式诊断：把每个通道的原始值打到日志（每 2.5s 一次，不刷屏），
        // 车机没 adb 时据此核对编码即可校准，是修「数值不对」的关键依据。
        Logx.d(
            "车控刷新 raw: " + raws.map { "${it.key}=0x${it.value.toString(16)}" }.joinToString(" ") +
                " | 解析: 主驾温=${s.driverTempC ?: "—"} 副驾温=${s.passengerTempC ?: "—"} " +
                "风速=${s.fanLevel}${if (s.fanAuto) "(AUTO)" else ""} " +
                "循环=${if (s.recircInside) "内" else "外"} 风向=${s.airDirection} " +
                "座椅通风=${s.seatVent} 座椅加热=${s.seatHeat}"
        )
    }

    /**
     * 把某个通道的原始值并入 [base]，返回新 State。
     * 无效读数（255 / 0xFF / 越界）直接返回 [base]，不破坏已知状态。
     */
    /**
     * 把 ECarX 的"开关"枚举归一成 true/false。
     * 兼容两套约定：0/1 与 10/11（部分 ECarX HAL 用 10=OFF、11=ON）。
     * 其它值（含 255 哨兵）返回 null，保留原状态。
     */
    /**
     * 把 ECarX 的"开关"枚举归一成 true/false。
     * 兼容两套约定：0/1（HvacConstants：OFF=0 ON=1）与 10/11（部分 HAL 层的 0x0a/0x0b）。
     * 其它值（含 0xfd/0xfe/0xff 哨兵）返回 null，保留原状态。
     */
    private fun boolOf(raw: Int): Boolean? = when (raw and 0xFF) {
        0, 10 -> false
        1, 11 -> true
        else -> null
    }

    /**
     * 浮点温度归一。
     *
     * 有效区间取 10..40℃，比车机实际可调区间（15.5..28.5℃）略宽，
     * 用来挡掉「空调关闭 / 通道不可用」时返回的伪值。
     *
     * 实测踩坑：车机空调未开启时 `getCustomizeFunctionValue` 会返回 **5.0**，
     * 而同一通道的 int 值 `getFunctionValue` 返回 `0xff`（UNKNOWN 哨兵）。
     * 早期实现把 5.0 coerce 进 5..45 就直接显示成「5.0°」——既不是真实温度，
     * 也不是 OFF，属于误导。现在区间外一律返回 null（UI 显示 OFF）。
     */
    private fun normalizeTempC(t: Float): Float? {
        if (t.isNaN() || t < TEMP_VALID_MIN || t > TEMP_VALID_MAX) return null
        return t
    }

    /** ECarX/HvacConstants 的无效读数哨兵：0xfd=ERROR 0xfe=NONE 0xff=UNKNOWN */
    private fun isSentinel(raw: Int): Boolean {
        val b = raw and 0xFF
        return b == VehicleBridge.VALUE_ERROR || b == VehicleBridge.VALUE_NONE ||
                b == VehicleBridge.VALUE_UNKNOWN
    }

    private fun withRaw(base: State, key: String, raw: Int, c: Context): State {
        val invalid = isSentinel(raw)
        return when (key) {
            "fan_manual" -> {
                if (invalid) return base
                val idx = raw and 0xFF
                // ★ v1.4.81：**低字节 10 = AUTO**（MANUAL_FAN_SPEED_LEVEL_AUTO="10"）。
                //   这台车没有 AUTO 通道（0x10020200 系统自己查也是 notactive），
                //   AUTO 是写在「手动风量」通道 0x10020100 上的，值 = 10。
                val parsed = if (idx == FAN_LEVEL_AUTO) {
                    base.copy(fanAuto = true, fanLevel = 0, fanMax = 5)
                } else if (idx !in 0..9) {
                    base
                } else {
                    base.copy(fanAuto = false, fanLevel = idx, fanMax = 9)
                }
                // ★★ v3.13.18：**用户刚操作过风量 → 忽略本次回读**。
                //
                // 现象：连点 ± 时数字会「跳得慢 / 来回摆」。
                // 根因（真车日志 19:09:37~38 实锤）：
                //   写 idx=4 → 乐观值 4（显示立刻变）
                //   → 200ms 后回读到车机**还没处理完的旧值 3** → 无条件覆盖 → 数字退回 3
                //   → 再下一轮回读到 4 → 又变 4 …… 乐观值与回读值来回摆。
                //
                // 为什么温度/A-C 没这个问题：它们的通道回读恒为 0xff 哨兵 →
                //   invalid 分支直接 return base，**从不覆盖**乐观值。
                //   风量恰恰因为**读得到真值**才会每轮覆盖。
                //
                // 保护：用户点完 ± 后的 1.5s 内，若回读档位与刚点的目标不一致 → 丢弃这次回读，
                //   保留乐观值；超过 1.5s 才接受回读值纠偏（车机真没跟上时仍能纠正）。
                if (parsed !== base && pendingFanLevel >= 0 &&
                    android.os.SystemClock.elapsedRealtime() - pendingFanAt < PENDING_FAN_HOLD_MS
                ) {
                    val pLevel = parsed.fanLevel
                    if (parsed.fanAuto != pendingFanAuto || pLevel != pendingFanLevel) {
                        Logx.d(
                            "风量回读忽略：乐观值=(auto=$pendingFanAuto,档=$pendingFanLevel) " +
                                    "回读=(auto=${parsed.fanAuto},档=$pLevel) —— 车机还没跟上，先按用户操作显示"
                        )
                        return base
                    }
                }
                parsed
            }
            "fan_auto" -> {
                if (invalid) return base
                // ★ v1.4.84：用**乱序映射**解析 AUTO 档位
                //   （osn_fan: 0x10020204→1档, 0x10020201→2档, 0x10020202→3档,
                //     0x10020203→4档, 0x10020205→5档）
                val lvl = fanAutoLevelOf(raw)
                when {
                    lvl > 0 -> base.copy(fanAuto = true, fanLevel = lvl, fanMax = 5)
                    // HAL 层风格：10/11 布尔
                    boolOf(raw) == true -> base.copy(fanAuto = true, fanLevel = base.fanLevel.coerceAtLeast(1), fanMax = 5)
                    boolOf(raw) == false -> base.copy(fanAuto = false, fanMax = 9)
                    else -> base
                }
            }
            "recirc" -> {
                if (invalid) return base
                // ★★★ v3.6：按系统 Dock 权威值域 —— 外循环 = **裸值 0**，
                //   内循环 = **复合 0x10030101**（低字节 1）。
                //   boolOf 按低字节判：0x10030101→1→内 ✔ / 0x10030100→0→外 ✔ / 裸值0→外 ✔
                boolOf(raw)?.let { return base.copy(recircInside = it) } ?: base
            }
            "ac" -> {
                if (invalid) return base
                // HVAC_FUNC_AC：0=关 1=开；部分 HAL 用 10/11，一并兼容
                boolOf(raw)?.let { return base.copy(acOn = it) } ?: base
            }
            "air_direction" -> {
                if (invalid) return base
                val bits = raw and 0xFF
                // 位掩码：1=吹脸 2=吹脚 4=吹前挡，合法组合 1..7
                if (bits !in 1..7) base else base.copy(airDirection = bits)
            }
            "seat_vent" -> {
                if (invalid) return base
                val idx = raw and 0xFF
                if (idx !in 0..3) base else base.copy(seatVent = idx)
            }
            "seat_heat" -> {
                if (invalid) return base
                val idx = raw and 0xFF
                if (idx !in 0..3) base else base.copy(seatHeat = idx)
            }
            "seat_vent_passenger" -> {
                if (invalid) return base
                val idx = raw and 0xFF
                if (idx !in 0..3) base else base.copy(seatVentPassenger = idx)
            }
            "seat_heat_passenger" -> {
                if (invalid) return base
                val idx = raw and 0xFF
                if (idx !in 0..3) base else base.copy(seatHeatPassenger = idx)
            }
            "temp_driver" -> tempFromRaw(key, raw, c)?.let { base.copy(driverTempC = it) } ?: base
            "temp_passenger" -> tempFromRaw(key, raw, c)?.let { base.copy(passengerTempC = it) } ?: base
            else -> base
        }
    }

    private fun read(key: String, ctx: Context): Int? {
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) return null
        val zone = VehicleChannels.zoneOf(ctx, key)
        val v = VehicleBridge.readInt(f, zone)
        if (v != null && v != 0xff && v != 0xfe && v != 0xfd) return v
        // ★ v1.4.53：读也做 zone 回退。
        //   真车实证：内外循环 0x10030100 在 zone=0x8 读回 0xff，
        //   但 watcher 回调给的是 `zone=-2147483648（全局）value=0`；
        //   写的时候也是只有全局 zone 才写进去（1.4.52 日志实锤）。
        //   所以配置的 zone 读不到就换 zone 试，命中就自动记住 —— 与写入同一套逻辑。
        for (z in READ_ALT_ZONES) {
            if (z == zone) continue
            val alt = VehicleBridge.readInt(f, z)
            if (alt != null && alt != 0xff && alt != 0xfe && alt != 0xfd) {
                Logx.w("读 $key：zone=0x${zone.toString(16)} 读不到 → 改用 zone=0x${z.toString(16)} 读到 0x${alt.toString(16)}（已自动记住该 zone）")
                VehicleChannels.save(ctx, key, f, z)
                return alt
            }
        }
        return v
    }

    /** 读取失败时依次尝试的备选 zone（与写入共用同一套，保持一致） */
    private val READ_ALT_ZONES = intArrayOf(0x8, 1, 4, 0x10, 0x20, 0x40, ZONE_GLOBAL)

    /** 直读固定 funcId（不走 VehicleChannels 配置） */
    private fun readBare(func: Int): Int? = VehicleBridge.readInt(func, ZONE_GLOBAL)

    /**
     * ★ v1.4.48 新信号写入守卫：写前探测 HAL 支持状态，非 active 一律不写。
     * 真车日志实锤：对不支持的 (funcId, zone, value) 调 setFunctionValue 会直接
     * native abort（用户「闪退 2-3 次才能打开」= 连续触发后写入熔断才恢复正常）。
     * 读取不受影响（getFunctionValue 返回哨兵不崩）。
     */
    /**
     * ★ v1.4.60：布尔开关通道的统一写入（A/C / 前挡 / 后挡 / ECO 等）。
     *
     * 与 [writeBareGuarded] 的区别：**会依次试复合编码和裸值**。
     * 真车日志实锤：成功的通道（风量 0x10020101、循环 0x10030101、座椅 0x10050102）
     * 全是复合编码 `functionId | 档位`；而前挡/后挡旧实现只发裸值 0/1，40 次全 FAIL。
     *
     * ⚠ 这三个通道读回恒 `0xff`（UNKNOWN），回读校验失效，
     *   `setFunctionValue` 返回 false 也不代表被拒 —— 用户 2026-10-20 确认车机真实响应。
     */
    private fun writeSwitch(
        ctx: Context, key: String, func: Int, zone: Int, on: Boolean, label: String,
        bareFirst: Boolean = false
    ): Boolean {
        if (Prefs.simulate(ctx)) return true
        if (!Prefs.vehicleWriteEnabled(ctx)) {
            Logx.w("写 $label 跳过：车控写入熔断中")
            return false
        }
        val support = VehicleBridge.supportOf(func, zone)
        val v = if (on) 1 else 0
        val composite = (func and 0xFFFFFF00.toInt()) or v
        // ★★★ v3.6：加 bareFirst 开关 —— 有些通道系统的权威写法就是**裸值 0/1**，
        //   不做 functionId|value 的复合编码。
        //   依据（oneOS_Dock.apk dex）：
        //     AutoSwitchSignal.setValue → setFunctionValue(0x10010200, 0x80000000, 0|1)
        //     RecycleSignalClick 的外循环也是写裸值 0。
        //   这类通道若先发复合值，HAL 会拒（我们「怎么切都是内循环」就是这么来的）。
        var ok: Boolean
        if (bareFirst) {
            // ① 裸值（系统权威形态）
            ok = VehicleBridge.verifyWrite(func, zone, v)
            // ② 复合编码（兜底）
            if (!ok) ok = VehicleBridge.verifyWrite(func, zone, composite)
        } else {
            // ① 复合编码（本车主力形态）
            ok = VehicleBridge.verifyWrite(func, zone, composite)
            // ② 裸值
            if (!ok) ok = VehicleBridge.verifyWrite(func, zone, v)
        }
        Logx.d(
            "写 $label target=${if (on) "开" else "关"} " +
            "func=0x${func.toString(16)} zone=0x${zone.toString(16)} " +
            "顺序=${if (bareFirst) "裸值优先" else "复合优先"} " +
            "复合=0x${composite.toString(16)} 裸值=$v 支持状态=$support → " +
            if (ok) "OK" else "HAL 返回 false（该通道读回 0xff，回读校验失效；实测车机会生效）"
        )
        return ok
    }

    /**
     * ★ v1.4.35 温度弹窗：直接把温度设到目标值（点温度档位列表用）。
     * 与 [tempStep] 同一编码（int 通道 ×0.2），带双温同步支持。
     */
    /**
     * ★ v1.4.35 温度弹窗：直接把温度设到目标值（点温度档位列表用）。
     *
     * ★★ v1.4.54：绝对设温已废弃为「多次档位步进」。
     *   真车日志实锤：复合编码低字节 = round(℃/0.2)（16℃=80、20℃=100、32℃=160），
     *   全部超出 HAL 的小编码枚举范围 → 8 个 zone 全 FAIL。绝对设温在本车不可行。
     *   现在改为：算出与当前值的档位差，逐档步进到位。
     */
    fun setTempC(ctx: Context, driver: Boolean, target: Float) {
        val next = target.coerceIn(TEMP_MIN_C, TEMP_MAX_C)
        val key = if (driver) "temp_driver" else "temp_passenger"
        if (Prefs.simulate(ctx)) {
            update(
                if (driver) state.copy(driverTempC = next, connected = true)
                else state.copy(passengerTempC = next, connected = true)
            )
            return
        }
        // ★★ v1.4.77：**直接写目标值**，不再「逐档步进」。
        //   原因：温度写的是**绝对设定温度**（float ℃），系统空调也是直接写目标值
        //   （读当前 +0.5 再写）。而旧实现把滑动跨 5℃ 拆成 10 次 tempStep，
        //   每次都要做多次 HAL 写 + 读 → 慢到来不及「选中」，用户以为没生效。
        val cur = (if (driver) state.driverTempC else state.passengerTempC) ?: Prefs.tempBase(ctx)
        if (Math.abs(next - cur) < 0.05f) {
            Logx.d("设温：已是 ${"%.1f".format(cur)}℃，无需调整")
            return
        }
        Logx.d("设温：${"%.1f".format(cur)}℃ → ${"%.1f".format(next)}℃（直接写目标值）")
        writeTempValue(ctx, driver, next)
    }

    /**
     * ★★ v1.4.79：判断一个回读温度**是否可信**。
     *
     * 背景：`0x10060100`（设定温度）的**读取本身不可靠** ——
     *   真车实测恒回 15.5（= TEMP_MIN_C，是「空调关闭/无值」时的伪值）。
     *   之前「读到就覆盖」→ 把正确的乐观值顶成 15.5，
     *   弹窗和 Dock 显示全错（用户反馈「选对了、发对了，就是显示不对」）。
     *
     * 采信条件：
     *  1. 落在上下限**内部**（排除 15.5 / 28.5 这类边界伪值）
     *  2. 与目标值相差 ≤ 1.5℃（否则说明车机还没生效）
     */
    private fun isPlausibleTemp(back: Float, target: Float): Boolean =
        back > TEMP_MIN_C + 0.4f && back < TEMP_MAX_C - 0.4f &&
                Math.abs(back - target) <= 1.5f

    /**
     * ★★ v1.4.77：写入一个**绝对设定温度**（℃），并做乐观更新。
     *
     * ⚠ 关键：**乐观更新必须在写之前无条件执行**。
     *   HAL 的返回值不可信 —— A/C 通道实测「返回 false 但车机真生效」，
     *   温度的 Customize 通道同样是「返回 false 但生效」。
     *   之前写成 `if (ok) update(...)` → HAL 返回 false 时本地状态不更新
     *   → Dock 显示的还是旧温度 → 「跟实际温度不统一」。
     */
    private fun writeTempValue(ctx: Context, driver: Boolean, target: Float) {
        val key = if (driver) "temp_driver" else "temp_passenger"
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("设温失败：通道 $key 未配置 functionId")
            return
        }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val v = target.coerceIn(TEMP_MIN_C, TEMP_MAX_C)

        // ① 乐观更新（无条件）—— 先让 Dock 显示跟上操作
        update(if (driver) state.copy(driverTempC = v) else state.copy(passengerTempC = v))

        // ② 写入
        var wrote = false
        if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            VehicleBridge.customizeWriteEnabled = true
            wrote = VehicleBridge.writeTempViaGlyCar(f, zone, v) ||
                    VehicleBridge.writeTempCustomize(f, zone, v)
        } else {
            // int 通道：低字节 = round(℃/0.2)，走复合编码
            val idx = Math.round(v / 0.2f).toInt().coerceIn(0, 255)
            wrote = writeChannel(ctx, key, idx)
        }
        Logx.d("设温 ${if (driver) "主驾" else "副驾"} → ${"%.1f".format(v)}℃ zone=0x${zone.toString(16)} 写入=$wrote")

        // ③ **延迟**回读校正（★ 关键：回读不可无条件采信）
        //
        // 真车日志实锤（2026-10-20 22:47）：
        //   ```
        //   温度 Customize 写：0x10060100 zone=0x4 value=255.0℃ → 返回=false
        //   温度 Customize 读：0x10060100 zone=0x4 → 15.5℃      ← ★ 伪值！
        //   ```
        //   0x10060100 的**读取本身不可靠**（恒回 15.5 / 28.5 这类边界值或关闭态伪值）。
        //   之前「读到就覆盖」→ 把正确的乐观值顶成 15.5 →
        //   弹窗和 Dock 显示都不对（用户反馈：选对了、发对了，就是显示不对）。
        //
        // 现在：等 600ms 让车机生效后再读，且**只在读数落在合理区间内**才采信：
        //   · 排除上下限边界值（15.5 / 28.5 —— 正是伪值的形态）
        //   · 排除与目标值相差过大的值（>1.5℃ 说明还没生效）
        // 读不到/不合理 → 保持乐观值（那才是用户选的值）。
        // ★ v1.4.92：改成**防抖**回读（连点只在停手 1 秒后读一次），不再每次都排一个 600ms 任务
        scheduleTempReadback(driver, f, zone, v)
    }

    /**
     * ★ v1.4.52：切车机自身的「双温 / 主副驾同步」通道（HVAC_FUNC_TEMP_DUAL 0x10060500）。
     *
     * 原来「双温同步」只是本项目的一个本地开关（[Prefs.syncTemp]），
     * 只决定要不要顺带写另一侧温度，**从来没有真正告诉车机**。现在补上。
     */
    fun setTempDual(ctx: Context, on: Boolean) {
        if (Prefs.simulate(ctx)) return
        val key = "temp_dual"
        if (!VehicleChannels.enabled(ctx, key)) {
            Logx.d("双温通道未启用（functionId<=0），只切本地同步开关")
            return
        }
        // ★★ v1.4.83：**先读回当前状态，一致就不写**。
        //   该通道（0x10060500，全局 zone）在车机侧可能是**翻转**语义 —— 每写一次翻一次。
        //   现在：回读 == 目标状态 → 直接不写；读不到才按原逻辑写。
        val cur = readDualState(ctx)
        if (cur != null && cur == on) {
            Logx.d("双温同步：车机已是" + (if (on) "开" else "关") + "，跳过写入（避免翻转）")
            return
        }
        // ★ v1.4.54：0x10060500 这个 ID 本身没验证过（oneOS_Dock 表里抄的），
        //   把 0/1/2/3 全试一遍，哪个回读生效就用哪个；全失败至少留下日志证据。
        val ok = writeChannelCandidates(ctx, key, listOf(if (on) 1 else 0, 1, 0, 2, 3))
        Logx.d("写 车机双温同步 target=${if (on) "开" else "关"} func=0x${VehicleChannels.funcOf(ctx, key).toString(16)} → ${if (ok) "OK" else "FAIL"}")
        if (!ok) {
            Logx.w("双温同步写入失败：0x10060500 可能不是本车的双温通道，" +
                "请用「扫描车控通道」找真实 ID，或先用「长按副驾温度」的本地同步（写入两侧温度）")
        }
    }

    /** ★ v1.4.35：前挡加热 开/关（0x10040100）*/
    fun toggleDefrostFront(ctx: Context) {
        val next = !state.defrostFront
        if (Prefs.simulate(ctx)) {
            update(state.copy(defrostFront = next, connected = true))
            return
        }
        update(state.copy(defrostFront = next))   // 乐观更新
        // ★ v1.4.60：改走 writeSwitch（复合编码优先，裸值兜底）。
        //   旧实现 writeBareGuarded 只发裸值 0/1，真车 40 次全 FAIL。
        val ctxBg = ctx
        hal.execute { writeSwitch(ctxBg, "defrost_front", FUNC_DEFROST_FRONT, ZONE_GLOBAL, next, "前挡加热") }
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：后挡加热 开/关（0x10040300） */
    fun toggleDefrostRear(ctx: Context) {
        val next = !state.defrostRear
        if (Prefs.simulate(ctx)) {
            update(state.copy(defrostRear = next, connected = true))
            return
        }
        update(state.copy(defrostRear = next))
        // ★ v1.4.60：同前挡，改走 writeSwitch（复合编码优先）
        val ctxBg = ctx
        hal.execute { writeSwitch(ctxBg, "defrost_rear", FUNC_DEFROST_REAR, ZONE_GLOBAL, next, "后挡加热") }
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：方向盘加热 —— 默认关；打开即 3 档，再点 2 档 → 1 档 → 关（用户要求从高档往低调） */
    fun steeringHeatCycle(ctx: Context) {
        val next = if (state.steeringHeat <= 0) SEAT_LEVEL_MAX else state.steeringHeat - 1
        if (Prefs.simulate(ctx)) {
            update(state.copy(steeringHeat = next, connected = true))
            return
        }
        update(state.copy(steeringHeat = next))
        // ★ v1.4.60：方向盘加热是档位（0..N）不是布尔，走 writeChannel（复合编码优先）
        val ok = writeChannel(ctx, "steering_heat", next)
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /**
     * ★ v3.1：方向盘加热 —— 直接设到指定档位（0=关 1..3），供 Dock 条目「长按升档」用。
     * 通道：functionId=0x10090100（HVAC_FUNC_STEERING_WHEEL_HEAT），zone=1（主驾）。
     */
    fun setSteeringHeatLevel(ctx: Context, level: Int) {
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.steeringHeat) return
        if (Prefs.simulate(ctx)) {
            update(state.copy(steeringHeat = next, connected = true))
            return
        }
        update(state.copy(steeringHeat = next))
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, "steering_heat", next)
            Logx.d("写 方向盘加热 idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        scheduleRefresh(ctx)
    }

    /**
     * ★ v1.4.36：座椅记忆 —— 应用第 [pos] 个记忆位（1..3）。
     * 信号：SETTING_FUNC_SEAT_POSITION_SET = 0x2d400200（写位置号）。
     * ⚠ 车机未实测，写无效时看日志（HVAC_WRITE 行）定位编码问题。
     */
    fun applySeatMemory(ctx: Context, pos: Int) {
        if (pos !in 1..3) return
        if (Prefs.simulate(ctx)) {
            Logx.d("座椅记忆：模拟模式，应用位置 $pos（仅日志）")
            return
        }
        // ★★ v1.4.89：**直接写位置号 pos（1/2/3）**。
        //   之前这里调的是 `writeSwitch(..., pos > 0, ...)`，而 writeSwitch 内部
        //   `val v = if (on) 1 else 0` → **M1/M2/M3 写出去的值一模一样（都是 1）**，
        //   所以「选用记忆位」从来没生效过。
        //   现在：zone 依次试 1 / 4 / 0x8 / 全局，每个 zone 都试「裸值」和「复合编码」。
        var ok = false
        for (z in SEAT_MEMORY_ZONES) {
            val compositePos = (FUNC_SEAT_POSITION_SET and 0xFFFFFF00.toInt()) or pos
            for (v in intArrayOf(pos, compositePos)) {
                val r = VehicleBridge.verifyWrite(FUNC_SEAT_POSITION_SET, z, v)
                Logx.d(
                    "座椅记忆：调用位置 $pos  func=0x" + Integer.toHexString(FUNC_SEAT_POSITION_SET) +
                    " zone=0x" + Integer.toHexString(z) + " value=" +
                    (if (v == pos) "裸值$pos" else "复合0x" + Integer.toHexString(v)) +
                    " → " + r
                )
                if (r) { ok = true; break }
            }
            if (ok) break
        }
        Logx.d("座椅记忆：调用位置 $pos 最终结果=$ok")
        // ★ v1.4.97 #12：车机的「当前记忆位」通道在这台车上读不回来，
        //   所以把我们自己刚调用的这个位置记到本地，弹窗下次打开就知道该高亮哪个。
        Prefs.setSeatMemoryIndex(ctx, pos)
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /**
     * 读当前座椅记忆位置号（1/2/3），读不到返回 null。
     *
     * ★ v1.4.50：座椅弹窗原来只能「点了才高亮 1.5 秒」，看不出当前究竟在哪个位置。
     *   现在主动读 `SEAT_NUM (0x2d410f00)`。
     *
     * 取值编码未实测，故两种都认：
     *  - 复合编码 `0x2d410f01/02/03` → 取低 8 位
     *  - 裸值 `1/2/3`
     * zone 也逐个试（主驾 1 / 全局 0x80000000 / 前排 0x8），取第一个有效值。
     */
    fun readSeatMemoryIndex(ctx: Context): Int? {
        if (Prefs.simulate(ctx)) return null
        // ★★ v1.4.89：**优先读 SEAT_POSITION_SAVE (0x2d400100)** —— 这才是「当前在第几号位」。
        //   裸值 1/2/3 与复合编码 0x2d400101/02/03 都认，zone 逐个试。
        for (z in SEAT_MEMORY_ZONES) {
            val raw = VehicleBridge.readInt(FUNC_SEAT_POSITION_SAVE, z) ?: continue
            val n = if (raw > FUNC_SEAT_POSITION_SAVE) raw and 0xFF else raw
            if (n in 1..SEAT_MEMORY_SLOTS) {
                Logx.d(
                    "座椅记忆位置：读 SAVE 0x" + Integer.toHexString(FUNC_SEAT_POSITION_SAVE) +
                    " zone=0x" + Integer.toHexString(z) + " raw=0x" + Integer.toHexString(raw) +
                    " → 第 " + n + " 号位"
                )
                return n
            }
            Logx.d(
                "座椅记忆位置：SAVE zone=0x" + Integer.toHexString(z) +
                " raw=0x" + Integer.toHexString(raw) + " 不在 1..$SEAT_MEMORY_SLOTS"
            )
        }
        // ★ v1.4.64：复合编码兜底。
        //   本车其它通道（风量/循环/座椅通风加热）全部吃复合编码 `functionId | 值`，
        //   座椅记忆大概率同样 —— 值 1/2/3，即 0x2d410f01/02/03。
        //   同时 zone 扩大到 1 / 4 / 0x8 / 全局 四种。
        val zones = intArrayOf(1, ZONE_GLOBAL, 0x8, 4)
        for (z in zones) {
            val raw = VehicleBridge.readInt(FUNC_SEAT_NUM, z) ?: continue
            val n = if (raw > FUNC_SEAT_NUM) raw and 0xFF else raw
            if (n in 1..3) {
                Logx.d("座椅记忆位置：读 0x${FUNC_SEAT_NUM.toString(16)} zone=$z raw=0x${raw.toString(16)} → 位置 $n")
                return n
            }
        }
        // 复合编码：0x2d410f01 / 02 / 03
        for (n in 1..3) {
            val composite = (FUNC_SEAT_NUM and 0xFFFFFF00.toInt()) or n
            val raw = VehicleBridge.readInt(FUNC_SEAT_NUM, 1) ?: continue
            if (raw == composite || raw == n) {
                Logx.d("座椅记忆位置：复合编码命中 → 位置 $n")
                return n
            }
        }
        // ★ v1.4.74：0x2d410f00 读不到（该 ID 属于「设置」段，ECarX 侧可能不开放）。
        //   换个思路：ISensor 里有**座椅位置的连续量**（真车 ISensor 扫描实锤有值）：
        //     0x102600 主驾坐垫倾角 / 0x102700 主驾靠背倾角 / 0x102800 副驾座椅高度
        //     0x102900 副驾座椅前后 / 0x102A00 副驾坐垫倾角 / 0x102500 副驾座椅前后
        //   靠背/坐垫倾角在座椅记忆切换时变化最明显 → 用它做一个「变了就是换了位置」的启发式。
        val seatSig = seatSignature()
        if (seatSig != null) {
            Logx.d("座椅记忆位置：0x2d410f00 读不到，改用 ISensor 座椅特征 = $seatSig")
        }
        Logx.d("座椅记忆位置：未读到有效值（SEAT_NUM 0x${FUNC_SEAT_NUM.toString(16)}，" +
            "HAL 可能不开放该通道；已试裸值+复合 × 4 zone）")
        // ★ v1.4.97 #12：车机通道读不到 → 退回「上次我们自己调用过的记忆位」。
        //   这样座椅弹窗至少能正确高亮我们刚选的那一个，不再三个都灰着。
        val local = Prefs.seatMemoryIndex(ctx)
        if (local in 1..SEAT_MEMORY_SLOTS) {
            Logx.d("座椅记忆位置：车机读不到，用本地记录 → 第 $local 号位")
            return local
        }
        return null
    }

    /**
     * ★ v1.4.74：ISensor 座椅特征（主/副驾靠背与坐垫倾角组合）。
     * 座椅记忆切换时这些值会变 → 可用来判断「当前是不是记忆位 1/2/3」。
     */
    private fun seatSignature(): String? {
        val ids = intArrayOf(0x102600, 0x102700, 0x102800, 0x102900, 0x102A00, 0x102500)
        val vals = ids.map { SensorBridge.readFloat(it) }
        if (vals.all { it == null }) return null
        return vals.joinToString(",") { it?.let { v -> "%.0f".format(v) } ?: "-" }
    }

    /**
     * ★ v1.4.35：极速降温 —— 无独立信号，组合动作：
     *   **AC 开 + 风量最大 + 温度最低（主驾 + 副驾）**（用户 v3.13.12 明确要求两侧都设）。
     */
    fun fastCool(ctx: Context) {
        if (Prefs.simulate(ctx)) {
            update(
                state.copy(
                    acOn = true, fanAuto = false, fanLevel = 9,
                    driverTempC = TEMP_MIN_C, passengerTempC = TEMP_MIN_C, connected = true
                )
            )
            return
        }
        runCatching {
            // AC 开
            val acF = VehicleChannels.funcOf(ctx, "ac")
            if (acF > 0) VehicleBridge.writeInt(acF, VehicleChannels.zoneOf(ctx, "ac"), 11)
            // 风量最大
            val fanF = VehicleChannels.funcOf(ctx, "fan_manual")
            if (fanF > 0) VehicleBridge.writeInt(fanF, VehicleChannels.zoneOf(ctx, "fan_manual"), 9)
            // 主驾温度最低
            tempToRaw("temp_driver", TEMP_MIN_C, ctx)?.let { raw ->
                VehicleBridge.writeInt(
                    VehicleChannels.funcOf(ctx, "temp_driver"),
                    VehicleChannels.zoneOf(ctx, "temp_driver"),
                    raw
                )
            }
            // 副驾温度最低
            tempToRaw("temp_passenger", TEMP_MIN_C, ctx)?.let { raw ->
                VehicleBridge.writeInt(
                    VehicleChannels.funcOf(ctx, "temp_passenger"),
                    VehicleChannels.zoneOf(ctx, "temp_passenger"),
                    raw
                )
            }
        }
        update(
            state.copy(
                acOn = true, fanAuto = false, fanLevel = 9,
                driverTempC = TEMP_MIN_C, passengerTempC = TEMP_MIN_C
            )
        )
        Logx.d("极速降温：AC 开 + 风量最大 + 主副驾温度最低")
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /**
     * ★ v3.13.12：极速升温 —— 组合动作：
     *   **风量最大 + 温度最高（主驾 + 副驾）**（用户 v3.13.12 明确规格，**不动 AC**）。
     * 车机侧没有独立的 PTC「加热」开关信号（通道表里只有 ac），
     * 所以升温靠「大风量 + 两侧温度设到最高」组合实现。
     */
    fun fastHeat(ctx: Context) {
        if (Prefs.simulate(ctx)) {
            update(
                state.copy(
                    fanAuto = false, fanLevel = 9,
                    driverTempC = TEMP_MAX_C, passengerTempC = TEMP_MAX_C, connected = true
                )
            )
            return
        }
        runCatching {
            // 风量最大
            val fanF = VehicleChannels.funcOf(ctx, "fan_manual")
            if (fanF > 0) VehicleBridge.writeInt(fanF, VehicleChannels.zoneOf(ctx, "fan_manual"), 9)
            // 主驾温度最高
            tempToRaw("temp_driver", TEMP_MAX_C, ctx)?.let { raw ->
                VehicleBridge.writeInt(
                    VehicleChannels.funcOf(ctx, "temp_driver"),
                    VehicleChannels.zoneOf(ctx, "temp_driver"),
                    raw
                )
            }
            // 副驾温度最高
            tempToRaw("temp_passenger", TEMP_MAX_C, ctx)?.let { raw ->
                VehicleBridge.writeInt(
                    VehicleChannels.funcOf(ctx, "temp_passenger"),
                    VehicleChannels.zoneOf(ctx, "temp_passenger"),
                    raw
                )
            }
        }
        update(
            state.copy(
                fanAuto = false, fanLevel = 9,
                driverTempC = TEMP_MAX_C, passengerTempC = TEMP_MAX_C
            )
        )
        Logx.d("极速升温：风量最大 + 主副驾温度最高")
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** ★ v1.4.35：副驾座椅通风档位（0..3，通道 seat_vent_passenger / zone=4） */
    fun setSeatVentLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_vent_passenger", state.seatVentPassenger, level,
        set = { s, v -> s.copy(seatVentPassenger = v) },
        label = "副驾座椅通风"
    )

    /** ★ v1.4.35：副驾座椅加热档位（0..3，通道 seat_heat_passenger / zone=4） */
    fun setSeatHeatLevelPassenger(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat_passenger", state.seatHeatPassenger, level,
        set = { s, v -> s.copy(seatHeatPassenger = v) },
        label = "副驾座椅加热"
    )

    /**
     * int 原始值 → 摄氏度（浮点通道不可用时的兜底）。
     *
     * 依次尝试三种编码，取落进 5..45℃ 的那个：
     *  1) 直接就是摄氏度（部分后端 5..45）
     *  2) ×0.2℃（低 8 位索引，如 128 → 25.6℃）
     *  3) 旧的 base + (idx - indexBase) × step 公式
     */
    private fun tempFromRaw(key: String, raw: Int?, ctx: Context): Float? {
        if (raw == null || raw == 0 || isSentinel(raw)) return null
        val r = raw and 0xFF   // 温度原始值（低 8 位）
        if (r <= 0) return null
        if (r in 5..45) return r.toFloat()
        val byPoint2 = r * 0.2f
        if (byPoint2 in 5f..45f) return byPoint2
        val base = Prefs.tempBase(ctx)
        val step = Prefs.tempStep(ctx)
        val iBase = Prefs.tempIndexBase(ctx)
        val t = base + (r - iBase) * step
        return if (t in 5f..45f) t else null
    }

    /**
     * 摄氏度 → int 原始值（仅在浮点通道不可用时使用）。
     *
     * ★ 修正：低 8 位 = round(℃ / 0.2)，与实测 leftTemp:128 → 25.6℃ 一致。
     *   旧实现用的是 base + (idx-iBase)*step 反推，写回车机的值是错的。
     */
    private fun tempToRaw(key: String, tempC: Float, ctx: Context): Int? {
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) return null
        val idx = Math.round(tempC / 0.2f)
        if (idx !in 0..255) return null
        // ⚠️ 该车机温度属性是浮点 Customize 通道（普通 App 写它会 native 崩溃，已禁用），
        //    int 通道 getFunctionValue 读回 0xff（不可读）。本轮对「写」尝试用纯索引值
        //    （与座椅一致：HAL 按低字节生效），若下次日志仍 FAIL 则确认是厂商限制，
        //    需系统签名（android.uid.system）才能控温。
        return idx and 0xFF
    }

    // =====================================================================
    // 操作
    // =====================================================================

    /**
     * 统一写 int 通道。
     *
     * ★ 权威调用形态（用户 2026-10-16 提供，内外循环实测）：
     *   `ICar.getICarFunction().setFunctionValue(functionId, areaId, value)`
     *   value 是**裸枚举/裸档位**，不是 `functionId | value` 的复合编码。
     *   例：开内循环 = `setFunctionValue(0x10030100, 0x8, 1)`。
     *
     * 修正前本项目所有通道都写 `(functionId & 0xFFFFFF00) | value`（0x10030101 这种），
     * 这是从反编译 oneOS_Dock 的「func + 值」记号误推出来的，实车写被拒。
     *
     * ★★ v1.4.54 顺序与策略（真车日志逐条实锤）：
     * ```
     * 风量  0x10020100 | 1 → OK      座椅 0x10050100 | 2 → OK
     * 风向  0x10070100 | 7 → OK      循环 0x10030100 | 1 → OK（zone 必须用全局 0x80000000）
     * 温度  0x10060100 | 100(=20℃) → 全部 zone 全 FAIL   ← 低字节只能是小编码
     * ```
     * 规律：**复合编码的低字节只能是小档位（风量 1..9、座椅 0..3、循环 0/1、风向 1..7）**。
     * 温度的低字节是 round(℃/0.2)，20℃ 就是 100，远超 HAL 枚举范围 → 一律被拒。
     * ⇒ 温度必须按「相对档位」写，见 [tempStep] 的说明。
     *
     * 顺序：① 复合编码 ② 裸值 ③ 换 zone 再试复合。
     *
     * ⚠ 熔断：连续崩溃 2 次后 [Prefs.vehicleWriteEnabled] 会被置 false，这里直接跳过写入。
     *   setFunctionValue 是厂商私有 HAL 反射调用，传错值可能直接 native abort，
     *   Java 层拦不住，只能从源头停写。
     */
    private fun writeChannel(ctx: Context, key: String, value: Int): Boolean {
        val f = VehicleChannels.funcOf(ctx, key)
        // ★ v1.4.97 #1：原来这里**静默 return false**（P04 点没反应也不打日志，查不到原因）。
        //   现在明确打出来，谁再漏配通道一眼就能看出来。
        if (f <= 0) {
            Logx.e("写 $key 失败：通道表里没有这个 key（funcOf=$f），请检查 VehicleChannels.ALL")
            return false
        }
        if (!Prefs.vehicleWriteEnabled(ctx)) {
            Logx.w("写 $key 已跳过：车控写入熔断（连续崩溃后自动关闭，仅保留读取）")
            return false
        }
        // ★★ v1.4.55 启动竞态修复：Dock 刚挂载时用户就可能点按钮，而 VehicleBridge
        //   要再过几百毫秒才 init 完（真车日志：19:53:53.844 点击 → 19:53:54.324 才就绪），
        //   于是全部写成「后端=NONE」。
        //   这里等一会儿再写，别让用户的点击白费。
        var waited = 0
        while (!VehicleBridge.isReady && waited < WAIT_READY_MS) {
            android.os.SystemClock.sleep(150)
            waited += 150
        }
        if (!VehicleBridge.isReady) {
            Logx.e("写 $key 失败：等了 ${WAIT_READY_MS}ms 车控仍未就绪（后端=${VehicleBridge.backend}）")
            return false
        }
        val zone = VehicleChannels.zoneOf(ctx, key)
        val composite = (f and 0xFFFFFF00.toInt()) or (value and 0xFF)

        // ① 复合编码（本车实测有效形态）
        //    用 verifyWrite：写完 400ms 回读一次，日志直接给出「已生效 / 未生效」，
        //    避免 HAL 返回 true 却静默丢弃时被误判成成功。
        if (VehicleBridge.verifyWrite(f, zone, composite)) {
            Logx.d("写 $key：复合编码 0x${composite.toString(16)} → OK")
            return true
        }
        // ② 裸值
        if (VehicleBridge.verifyWrite(f, zone, value)) {
            Logx.d("写 $key：裸值 $value → OK")
            return true
        }
        // ③ 换 zone 再试复合编码：zone 不对时 HAL 会静默丢弃（读回 0xff 就是典型症状）。
        //    命中就自动把 zone 记住，用户不用手动改。
        for (z in WRITE_ALT_ZONES) {
            if (z == zone) continue
            if (VehicleBridge.verifyWrite(f, z, composite)) {
                Logx.w("写 $key：zone=0x${zone.toString(16)} 无效 → 改用 zone=0x${z.toString(16)} 写复合编码 0x${composite.toString(16)} → OK（已自动记住该 zone）")
                VehicleChannels.save(ctx, key, f, z)
                return true
            }
        }
        Logx.d("写 $key：复合 0x${composite.toString(16)} / 裸值 $value 在 zone=0x${zone.toString(16)} 及备选 zone 全部 FAIL")
        return false
    }

    /** 写入失败时依次尝试的备选 zone */
    private val WRITE_ALT_ZONES = intArrayOf(0x8, 1, 4, 0x10, 0x20, 0x40, ZONE_GLOBAL)

    /** 写入前等车控就绪的最长时间（Dock 刚挂载时用户就可能点按钮） */
    private const val WAIT_READY_MS = 3000

    // =====================================================================
    // 通道扫描（找真实 functionId —— 内外循环读回 0xff 时的唯一解）
    // =====================================================================

    /**
     * ★★ v1.4.52：暴力扫描车控通道，把「哪些 functionId 真的有值」列出来。
     *
     * 为什么必须扫：内外循环 0x10030100 在本车**读回恒为 0xff**（HAL 没暴露），
     * 写也全部 FAIL —— 强烈提示 functionId 本身不对（oneOS_Dock 那张表是别的车型/版本）。
     * 与其猜，不如把整片 ID 空间读一遍：读通道是安全操作（读不到只返回哨兵，不会崩）。
     *
     * 扫描范围：`0x10000000` 起，步长 `0x100`，共 `count` 个；每个 ID 在 [zones] 里逐个试。
     * 判定「有效」：返回非 null 且不是 0xff / 0xfe / 0xfd 哨兵。
     *
     * 结果打进日志并可由调用方上传。
     */
    fun scanChannels(
        ctx: Context,
        zones: IntArray = intArrayOf(0x8, 1, 4),
        startFunc: Int = 0x10000000,
        count: Int = 0xA00,          // 0x10000000 .. 0x100A0000
        step: Int = 0x100
    ): String {
        if (!Prefs.vehicleApiEnabled(ctx)) return "车控通道已关闭，无法扫描"
        if (!VehicleBridge.isReady) return "车控未连接，无法扫描"
        val t0 = android.os.SystemClock.elapsedRealtime()
        val hits = mutableListOf<String>()
        var tried = 0
        for (i in 0 until count) {
            val f = startFunc + i * step
            for (z in zones) {
                tried++
                val v = VehicleBridge.readInt(f, z) ?: continue
                if (v == 0xff || v == 0xfe || v == 0xfd) continue
                // 复合编码（func|n）与裸值都算命中；标注出来方便判读
                val kind = when {
                    v > f && (v and 0xFFFFFF00.toInt()) == (f and 0xFFFFFF00.toInt()) -> "复合 n=${v and 0xFF}"
                    v == 0 -> "0"
                    else -> "裸值"
                }
                hits.add("0x%08X zone=0x%X → 0x%08X (%d) [%s]".format(f, z, v, v, kind))
            }
        }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        return buildString {
            append("===== 车控通道扫描 =====\n")
            append("范围 0x").append(startFunc.toString(16)).append(" 起 ")
                .append(count).append(" 个 ID × ").append(zones.size).append(" 个 zone，")
            append("共 ").append(tried).append(" 次读，耗时 ").append(ms).append("ms\n\n")
            if (hits.isEmpty()) {
                append("★ 没有任何通道返回有效值（全是 0xff 哨兵）\n")
            } else {
                append("命中 ").append(hits.size).append(" 条：\n")
                hits.forEach { append("  ").append(it).append('\n') }
            }
            append("\n提示：对照这张表，把「内外循环 / 温度」的 functionId 改到真有值的那个 ID 上\n")
            append("（主界面 → 车控通道 → 长按条目改数字保存）。\n")
            append("========================\n")
        }
    }

    /**
     * ★★ v1.4.54：调温改为「相对档位」写入。
     *
     * 为什么必须这么改（2026-10-20 真车日志实锤）：
     * ```
     * HVAC_WRITE 0x10060100 zone=1 value=268829028 → false   ← 复合，低字节=100(20℃)
     * HVAC_WRITE 0x10060100 zone=1 value=100        → false   ← 裸索引
     * zone=8/4/16/32/64/全局 全部 false
     * ```
     * 而同一份日志里：
     * ```
     * 0x10020100 | 1  → OK      0x10050100 | 2  → OK
     * 0x10070100 | 7  → OK      0x10030100 | 1  → OK
     * ```
     *  ⇒ 复合编码的低字节 HAL 只认**小编码**（风量 1..9 / 座椅 0..3 / 风向 1..7 / 循环 0..1）。
     *    温度的低字节 = round(℃/0.2)，16℃=80、32℃=160，全部越界 → 绝对索引写法不可能成功。
     *
     *  这也是系统空调面板只有「温度 +/−」两个按钮、没有数字键盘的原因：
     *  HAL 侧温度就是按档位步进的。
     *
     *  另外：温度**读不到**（int 通道恒 0xff，Customize 回调也收不到），
     *  所以这里是「盲调」——发一档步进，本地状态按 step 自己推算。
     */
    /**
     * ★★ v1.4.59 调温：`0x10060100` 在本车**只读不可写**，改为返回 false 让调用方打开系统空调界面。
     *
     * 证据链（2026-10-20 真车日志 + logcat）：
     * ```
     * isFunctionSupported 0x10060100 zone=1/4/8/16/32/64/全局 → notavailable
     * HVAC_WRITE 0x10060100 zone=4 value=268828929(=0x10060101) → false
     * HVAC_WRITE 0x10060100 zone=4 value=268828930(=0x10060102) → false
     * HVAC_WRITE 0x10060100 zone=4 value=1 / value=2           → false
     * 8 个 zone × 复合/裸值 全部 FAIL
     * ```
     * 对比同期**成功**的通道：
     * ```
     * isFunctionSupported 0x10020100 zone=8 → active     ← 风量是可写的
     * 0x10020100|1 → OK   0x10030100|1 → OK   0x10050100|2 → OK
     * ```
     * 而温度的**监听回调完全正常**（实体按键调时实时收到 15.5/18.5/22.5/28.5）
     * → HAL 只开放了温度的通知，没开放写入。这是 HAL 能力限制，
     * `CONTROL_CAR_CLIMATE` 是 signature 级，adb 也授不了 → 普通 App 无解。
     *
     * @return true = 已自行写入；false = 不可写，调用方应打开系统空调界面
     */
    /**
     * ★★ v1.4.92：**温度回读防抖** —— 连点 ± 时只在「最后一次操作后 1 秒」回读一次。
     *
     * 起因（用户反馈）：快速连点温度 ± 时，**显示的温度很不连贯**。
     *   原先有两处回读，每次点击都会触发：
     *     ① `tempStepWrite` 里**写完立即读** → 连点 5 次就读 5 次，
     *        每次都拿到「车机还没跟上」的中间值 → 显示来回跳
     *     ② `writeTempValue` 里 `postDelayed(600)` 读 → 还会再堆叠 5 个延迟回读
     *   两者叠加，显示就像在抖。
     *
     * 现在：每次调温都 `removeCallbacks` + `postDelayed(1000)`，
     *   **连点只会在停止 1 秒后触发一次回读**，用那一次的最终状态刷新显示。
     *   过程中显示一律用乐观值（用户点了多少次就显示多少）—— 反而更连贯。
     */
    private var tempReadDriver = true
    private var tempReadFunc = 0
    private var tempReadZone = 0
    private var tempReadTarget = 0f

    private val pendingTempReadback = Runnable {
        val driver = tempReadDriver
        val f = tempReadFunc
        val zone = tempReadZone
        val target = tempReadTarget
        val back = VehicleBridge.readTempCustomize(f, zone)
            ?: VehicleBridge.readTempViaGlyCar(f, zone)
        if (back == null) {
            Logx.d("温度回读：读不到 → 保持本地值 %.1f℃".format(target))
            return@Runnable
        }
        if (isPlausibleTemp(back, target)) {
            update(if (driver) state.copy(driverTempC = back) else state.copy(passengerTempC = back))
            Logx.d("温度回读：%.1f℃（采信，停手 1 秒后的最终值）".format(back))
        } else {
            Logx.d("温度回读：%.1f℃ 不可信（伪值或未生效）→ 保持 %.1f℃".format(back, target))
        }
    }

    /** ★ v1.4.92：安排一次防抖回读（连点只会保留最后一个） */
    private fun scheduleTempReadback(driver: Boolean, f: Int, zone: Int, target: Float) {
        tempReadDriver = driver
        tempReadFunc = f
        tempReadZone = zone
        tempReadTarget = target
        main.removeCallbacks(pendingTempReadback)
        main.postDelayed(pendingTempReadback, TEMP_READBACK_DELAY_MS)
    }

    /** ★ v1.4.90：给调温调用栈日志用的当前状态描述 */
    private fun stateMachineStateName(): String =
        "connected=" + state.connected + " auto=" + state.fanAuto + " recirc=" + state.recircInside

    /**
     * 只在第一次读到有效车外温度时做一次：
     * 高于 25℃ 开主驾通风 1 档；低于 15℃ 开主驾和副驾加热 1 档。
     * 开关关掉的那一项跳过。15～25℃ 什么都不开，同样记为已处理。
     */
    private fun applyAmbientSeatOnce(ctx: Context) {
        if (Prefs.ambientSeatDone(ctx)) return
        val t = ambientTempC ?: return
        Prefs.setAmbientSeatDone(ctx, true)
        when {
            t > 25f && Prefs.autoSeatVentHot(ctx) && state.seatVent <= 0 -> {
                Logx.d("首次启动：车外 ${t}℃ > 25 → 主驾座椅通风 1 档")
                setSeatVentLevel(ctx, 1)
            }
            t < 15f && Prefs.autoSeatHeatCold(ctx) -> {
                Logx.d("首次启动：车外 ${t}℃ < 15 → 主驾/副驾座椅加热 1 档")
                if (state.seatHeat <= 0) setSeatHeatLevel(ctx, 1)
                if (state.seatHeatPassenger <= 0) setSeatHeatLevelPassenger(ctx, 1)
            }
            else -> Logx.d("首次启动：车外 ${t}℃，座椅保持原状")
        }
    }

    /** ★ v1.4.74：车外环境温度（ISensor 0x100B00） */
    @Volatile
    var ambientTempC: Float? = null
        private set

    /**
     * ★ v1.4.75：**车内实际温度**（ISensor 0x100C00，座舱环境温度）。
     * 与「空调设定温度」（driverTempC/passengerTempC）是两回事，不可混用。
     */
    @Volatile
    var cabinTempC: Float? = null
        private set

    /** ★ v1.4.72：最近一次读到的点火状态（null = 读不到） */
    @Volatile
    var ignitionState: Int? = null
        private set

    /** 点火状态文字（熄火 / ACC / 点火 / 启动中；读不到显示 "--"） */
    fun ignitionLabel(): String =
        ignitionState?.let { SensorBridge.Ignition.label(it) } ?: "--"

    /** 当前是否已点火（ACC/ON/START 都算） */
    fun isIgnitionOn(): Boolean =
        ignitionState != null && ignitionState != SensorBridge.Ignition.OFF

    fun tempStep(ctx: Context, driver: Boolean, step: Int): Boolean {
        // ★★ v1.4.97 #3：这里原来是「每次点击都 new Throwable + 取 10 行调用栈」（v1.4.90 诊断用）。
        //   Throwable 填充 + 字符串拼接在连点时是实打实的主线程开销，
        //   用户反馈「快速点击 D06 调温度会卡」→ 改成一行轻量日志（不再取栈）。
        //   （v1.4.90 要查的「副驾 + 被自动点了 2 次」未再复现；真要再查，
        //     把下面这行临时换成 getStackTraceString 即可。）
        val key = if (driver) "temp_driver" else "temp_passenger"
        val cur = (if (driver) state.driverTempC else state.passengerTempC)
            ?: Prefs.tempBase(ctx)
        val tStep = Prefs.tempStep(ctx)
        val next = (cur + step * tStep).coerceIn(TEMP_MIN_C, TEMP_MAX_C)
        if (next == cur) {
            Logx.d("调温：已在边界 ${"%.1f".format(cur)}℃")
            return false
        }
        if (Prefs.simulate(ctx)) {
            update(if (driver) state.copy(driverTempC = next, connected = true) else state.copy(passengerTempC = next, connected = true))
            return true
        }

        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("调温失败：通道 $key 未配置 functionId")
            return false
        }
        // 档位步进：升温=1、降温=2（候选值全部试，回读生效才算数）
        val dir = if (step > 0) 1 else 2

        // ★★ v1.4.77：**乐观更新前置且无条件**。
        //   HAL 返回值不可信（温度 Customize 通道实测「返回 false 但真生效」），
        //   之前是 `if (ok) update(...)` → 返回 false 时本地状态不更新
        //   → Dock 显示旧温度 → 「按 ± 按钮不直接显示调节后的温度，跟实际不统一」。
        // ★★ v1.4.78：这一步**只在主线程做状态更新**（瞬时，UI 立即响应），
        //   真正的 HAL 读写全部丢到后台线程 → 消除「点温度 ± 顿一下」。
        update(if (driver) state.copy(driverTempC = next) else state.copy(passengerTempC = next))

        // ★★ v1.4.78：**HAL 读写全部丢到后台线程**（主线程只做上面的乐观更新）。
        //   一次调温要打 4~6 次 binder IPC，跑主线程上会让「点温度 ±」顿一下。
        val ctxBg = ctx
        hal.execute {
            try {
                tempStepWrite(ctxBg, driver, step, next, dir)
            } catch (t: Throwable) {
                Logx.e("调温写异常：${t.javaClass.simpleName} ${t.message}")
            }
        }
        return true
    }

    /**
     * ★★ v1.4.78：调温的**实际写入**（跑在后台线程）。
     *
     * 分两条路：
     *  A. Customize/GlyCar 浮点通道（系统空调实际走的路）—— 写**目标温度 float ℃**
     *  B. int 通道复合编码 `0x10060100 | round(℃/0.2)`（备用）
     */
    private fun tempStepWrite(
        ctx: Context, driver: Boolean, step: Int, next: Float, dir: Int
    ) {
        val key = if (driver) "temp_driver" else "temp_passenger"
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("调温失败：通道 $key 未配置 functionId")
            return
        }
        // ★★ v1.4.68：**严格照抄系统空调的调用序列**（反编译 + 真车 logcat 双重确认）：
        // ```
        // isFunctionSupported HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] status active
        // setCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] value 18.0
        // getFunctionValue HVAC_FUNC_TEMP_MAX [0x10060200] zone ZONE_ALL [0x80000000] value 28.5
        // getFunctionValue HVAC_FUNC_TEMP_MIN [0x10060300] zone ZONE_ALL [0x80000000] value 15.5
        // setCustomizeFunctionValue: ... results:true
        // getCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone [0x1] results:19.5
        // ```
        // 之前全写错了：方法名、value 形态（应是目标温度 float ℃）、zone 都对不上。
        if (Prefs.tempCustomizeWriteEnabled(ctx)) {
            val zone = VehicleChannels.zoneOf(ctx, key)
            // ① 上下限（zone=全局），用于夹取目标值
            // ★ v1.4.74：**必须过滤哨兵**！真车日志实锤：
            //   `调温 副驾 ECarX Customize：... 目标=255.0℃ (上下限 min=255 max=255)`
            //   0x10060200 / 0x10060300 读回的是 255（=0xff UNKNOWN 哨兵），
            //   被当成真实上下限 → coerceIn(255,255) → **目标温度变成 255℃** → 必然被拒。
            val maxRaw = VehicleBridge.readInt(VehicleChannels.FUNC_TEMP_MAX, ZONE_GLOBAL)
            val minRaw = VehicleBridge.readInt(VehicleChannels.FUNC_TEMP_MIN, ZONE_GLOBAL)
            val maxT = maxRaw?.takeIf { it in 16..40 }
            val minT = minRaw?.takeIf { it in 16..40 }
            val target = next.coerceIn(
                (minT ?: 15.5f).toFloat(),
                (maxT ?: 28.5f).toFloat()
            )
            VehicleBridge.customizeWriteEnabled = true

            // ② **优先走 GlyCar 浮点属性** —— 系统空调实际走的就是这条
            //    （logcat: `GlyCarImpl set HVAC_FUNC_TEMP propertyId=268828928 areaId=1 val=18.0`）
            //    本车 ECarX 侧 isFunctionSupported(0x10060100, zone)=notavailable（只读），
            //    两套 API 同编号但权限检查可能不同 → 这就是突破口。
            var cok = VehicleBridge.writeTempViaGlyCar(f, zone, target)
            var via = "车控通道 B 浮点属性"
            if (!cok) {
                // ③ 回退 ECarX Customize
                cok = VehicleBridge.writeTempCustomize(f, zone, target)
                via = "车控通道 A Customize"
            }
            Logx.d(
                "调温 ${if (driver) "主驾" else "副驾"} $via：0x${f.toString(16)} " +
                "zone=0x${zone.toString(16)} 目标=${"%.1f".format(target)}℃ " +
                "(上下限 min=$minT max=$maxT) → $cok"
            )
            // ④ 回读：★ v1.4.92 **不再立即读、也不再用它刷新显示**。
            //   原来这里写完就立刻读一次并 `update(...)`，连点时每次都读到
            //   「车机还没跟上」的中间值 → 显示来回跳（用户反馈「很不连贯」）。
            //   现在：显示一律用乐观值（点了多少显示多少），
            //   回读交给防抖任务 —— 只有停手 1 秒后才读一次最终状态来刷新。
            Logx.d("调温已下发：目标 %.1f℃（zone=0x%x），回读延后到停手 1 秒后".format(target, zone))
            scheduleTempReadback(driver, f, zone, target)
            if (Prefs.syncTemp(ctx)) {
                val otherKey = if (driver) "temp_passenger" else "temp_driver"
                val otherZone = VehicleChannels.zoneOf(ctx, otherKey)
                val ook = VehicleBridge.writeTempViaGlyCar(f, otherZone, target) ||
                    VehicleBridge.writeTempCustomize(f, otherZone, target)
                if (ook) {
                    update(
                        if (driver) state.copy(passengerTempC = target)
                        else state.copy(driverTempC = target)
                    )
                }
                // ★★ v1.4.83：调温时不再写 DUAL 通道 —— 该通道在车机侧是「翻转」语义，
                //   每次调温都写会导致「每第二次调节把双温同步关掉」。
                //   「同步」的本意是同时写另一侧温度（上面已写），无需重设车机双温开关。
            }
            // ★ v1.4.97 #3：合并刷新（连点只跑最后一次）
            scheduleRefresh(ctx)
            return
        }

        val ok = writeChannelCandidates(ctx, key, listOf(dir, if (dir == 1) 2 else 1))
        if (ok) {
            if (Prefs.syncTemp(ctx)) {
                val otherKey = if (driver) "temp_passenger" else "temp_driver"
                val otherDir = if (step > 0) 1 else 2
                if (writeChannelCandidates(ctx, otherKey, listOf(otherDir))) {
                    update(
                        if (driver) state.copy(passengerTempC = next)
                        else state.copy(driverTempC = next)
                    )
                }
                // 双温同步本身也试着告诉车机
                // ★★ v1.4.83：调温时不再写 DUAL 通道 —— 该通道在车机侧是「翻转」语义，
                //   每次调温都写会导致「每第二次调节把双温同步关掉」。
                //   「同步」的本意是同时写另一侧温度（上面已写），无需重设车机双温开关。
            }
            scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
            Logx.d("调温 ${if (driver) "主驾" else "副驾"} ${if (step > 0) "+" else ""}$step 档 → 档位=$dir OK（本地推算 ${"%.1f".format(next)}℃）")
            return
        }
        Logx.w("调温 ${if (driver) "主驾" else "副驾"} 不可写：0x${f.toString(16)} " +
            "通道支持探测=notavailable，HAL 未开放温度写入")
        // ★★ v1.4.62：**不再自动发 keyevent**。
        //   2026-10-20 实测教训：`input keyevent` 是直接注入当前焦点窗口的，
        //   用户点了一下「试发温度+」车机就黑屏了（弹出黑色图片）。
        //   ⇒ keyevent 是**破坏性操作**，只有在用户明确配置了 keycode、
        //      且明确知道该键作用时才允许发，绝不自动触发。
        //   温度要通，只能靠「抓实体按键」被动拿到真实 keycode 后由用户手动配置。
        Logx.w("调温：温度通道 HAL 不可写。如需用实体按键方案，" +
            "请先在主界面「抓实体按键」抓到真实 keycode 并手动填入保存，" +
            "之后调温才会发送该 keycode。")
        return
    }

    /**
     * ★★ v1.4.54：带候选值的写入 —— 按顺序试多个 value，哪个回读生效就用哪个。
     *
     * 用于「关」这类没有明显编码的场合（如外循环：复合 0x10030100 退化成 functionId 本身，
     * HAL 不认；裸值 0 也不行）。把所有候选一次试完，命中即返回。
     */
    private fun writeChannelCandidates(ctx: Context, key: String, values: List<Int>): Boolean {
        for (v in values) {
            if (writeChannel(ctx, key, v)) return true
        }
        return false
    }

    /** 切换风量 AUTO 档（开/关）。开时写 fan_auto 通道，关时写 fan_manual 通道 */
    /**
     * ★ v1.4.64：切 AUTO 风速。
     *
     * ⚠️ 之前的行为：`writeChannel(ctx, key, level)` —— level 是**手动档 0..9**，
     *    而 AUTO 通道 `0x10020200` 的取值范围是 **1..5**（`mSupportValues=[1,5]`），
     *    把手动档（如 9）写进 AUTO 通道 → HAL 拒绝/忽略 → 车机风速被顶到 9 档
     *    看起来像"只是把风速调到 9，并没有 auto"。
     *
     *    真车日志（1.4.52，19:09:37）实锤成功写入形态：
     *    `写 fan_auto：复合 0x10020201 → OK`  —— 1 档 AUTO。
     *    即 AUTO 通道也吃复合编码，值域 1..5。
     *
     * 修正：
     *  - 开 AUTO：把**当前手动档位按比例折算成 1..5**（9 档 → 5 档 AUTO），
     *    写 `0x10020200 | autoLevel`
     *  - 关 AUTO：写**手动通道** `0x10020100`，恢复原档位
     */
    fun toggleFanAuto(ctx: Context) {
        val next = !state.fanAuto
        if (Prefs.simulate(ctx)) {
            update(state.copy(fanAuto = next, fanLevel = state.fanLevel.coerceAtLeast(1), fanMax = if (next) 5 else 9, connected = true))
            return
        }
        // ★★ v3.5：**先接 AUTO 总开关 0x10010200**（HVAC_FUNC_AUTO，
        //   系统 Dock 里对应 AutoSwitchSignalClick / code 400002）。
        //   之前我们只写 AUTO 风量通道 0x10020200，从没碰过这个总开关。
        //   走 writeSwitch，zone 用全局。
        //
        // ★★★ v3.6：按系统反编译 → **裸值优先**。
        //   `AutoSwitchSignal.setValue` → `setFunctionValue(0x10010200, 0x80000000, 0|1)`
        //   系统写的就是**裸值 0/1**，不做 functionId|value 的复合编码。
        //   注册处：`AutoSwitchSignal(0x10010200, areaId=0x80000000, 0)` ✔ 全局 zone
        val ctxBg = ctx
        hal.execute {
            val okMaster = writeSwitch(
                ctxBg, "auto_master", VehicleChannels.FUNC_AUTO, ZONE_GLOBAL, next, "AUTO 总开关",
                bareFirst = true
            )
            Logx.d("AUTO 总开关 0x10010200 → ${if (next) "开" else "关"}：${if (okMaster) "OK" else "HAL 返回 false"}")
        }
        if (next) {
            // ★★ v1.4.81：**AUTO 写在「手动风量」通道上，值 = 10**。
            //
            // 依据：
            //  1) 这台车**没有** AUTO 风量通道 —— 0x10020200 在**系统自己的**日志里就是
            //     `isFunctionSupported ... status notactive` + `getFunctionValue ... value 255`
            //     （DBPLAY改/研究结果/空调/空调日志整理.txt，10-15 23:45:57）。
            //     我们一直往这个 notactive 通道写 → HAL 直接拒 → 什么都不会发生。
            //  2) oneOS_Dock 常量 `MANUAL_FAN_SPEED_LEVEL_AUTO = "10"`
            //     → AUTO 档位值是 10，**写在手动风量通道 0x10020100 上**，
            //       复合编码即 0x1002010A。
            //  3) 回读能验证：fan_manual 读回 0x1002010A（低字节 10）= 车机处于 AUTO。
            // AUTO 风道档位（1..5 档）
            val manualNow = state.fanLevel.coerceAtLeast(1)
            val autoLevel = Prefs.fanAutoLevel(ctx)
                .takeIf { it in 1..5 }
                ?: ((manualNow + 1) * 5 / 9).coerceIn(1, 5)
            update(state.copy(fanAuto = true, fanLevel = autoLevel, fanMax = 5))
            markPendingFan(level = autoLevel, auto = true)
            val ctxBg = ctx
            hal.execute {
                val fAuto = VehicleChannels.funcOf(ctxBg, "fan_auto")
                val zoneA = VehicleChannels.zoneOf(ctxBg, "fan_auto")
                if (fAuto > 0) {
                    val value = fanAutoValue(autoLevel)
                    VehicleBridge.writeInt(fAuto, zoneA, value)
                    val back = VehicleBridge.readInt(fAuto, zoneA)
                    val backLvl = back?.let { fanAutoLevelOf(it) } ?: 0
                    Logx.d(
                        "写 风量 AUTO（0x10020200 乱序映射）：档位=" + autoLevel +
                        " value=0x" + value.toString(16) + " zone=0x" + zoneA.toString(16) +
                        " 回读=0x" + (back?.toString(16) ?: "读不到") +
                        " 回读档位=" + backLvl +
                        (if (backLvl == autoLevel) "（✓ 一致）" else "")
                    )
                }
            }
        } else {
            val key = "fan_manual"
            val f = VehicleChannels.funcOf(ctx, key)
            if (f <= 0) return
            // ★ v1.4.81：关 AUTO → 写一个**具体手动档**。
            //   注意：AUTO 状态下 state.fanLevel = 0（见 withRaw 的 AUTO 识别），
            //   所以不能再从 fanLevel 折算，直接用 3 档（中速）作为退出 AUTO 后的档位。
            val manual = if (state.fanAuto) 3 else state.fanLevel.coerceIn(1, 9)
            update(state.copy(fanAuto = false, fanLevel = manual, fanMax = 9))
            markPendingFan(level = manual, auto = false)
            val ctxBg = ctx
            hal.execute {
                val key = "fan_manual"
                val f = VehicleChannels.funcOf(ctxBg, key)
                if (f <= 0) return@execute
                val composite = (f and 0xFFFFFF00.toInt()) or manual
                val zone = VehicleChannels.zoneOf(ctxBg, key)
                val ok = VehicleBridge.writeInt(f, zone, composite)
                Logx.d(
                    "写 风量 手动：→ 手动 $manual 档 " +
                    "value=0x${composite.toString(16)} zone=0x${zone.toString(16)} → " +
                    if (ok) "OK" else "HAL 返回 false"
                )
            }
        }
    }

    /** 风量 ±1。AUTO 开着时只改 AUTO 风道 1..5 档，不退回手动风速。 */
    fun fanStep(ctx: Context, delta: Int) {
        if (state.fanAuto) {
            val cur = state.fanLevel.takeIf { it in 1..5 } ?: Prefs.fanAutoLevel(ctx).coerceIn(1, 5)
            val next = (cur + delta).coerceIn(1, 5)
            if (next == cur) return
            setFanAutoLevel(ctx, next)
            return
        }
        val start = state.fanLevel
        val next = (start + delta).coerceIn(0, 9)
        if (next == start) return
        if (Prefs.simulate(ctx)) {
            update(state.copy(fanLevel = next, fanAuto = false, fanMax = 9, connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, "fan_manual")
        if (f <= 0) return
        // 乐观更新：图标立即响应（解决"调节后 2-3 秒才显示"）
        update(state.copy(fanLevel = next, fanAuto = false, fanMax = 9))
        // ★ v3.13.18：记下用户刚点的目标档位 —— 1.5s 内的旧回读不许覆盖它
        //   （否则连点时数字会在乐观值与回读旧值之间来回摆，见 withRaw 的 fan_manual 分支）
        markPendingFan(level = next, auto = false)
        // ★ v1.4.78：写入放后台（binder IPC 几十毫秒，跑主线程会「顿一下」）
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, "fan_manual", next)
            Logx.d("写 风量 手动 idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
        // ★ v1.4.97 #2：合并刷新。原来固定 400ms 后读一次 —— 车机还没跟上时读到旧值，
        //   把刚做的乐观更新顶回去，要再等 2.5s 的轮询才对上，看起来就是「读取延迟很高」。
        //   现在停手 900ms 后才读一次，且连点只跑最后一次。
        scheduleRefresh(ctx)
    }

    /** 内外循环切换 */
    fun toggleRecirc(ctx: Context) {
        val target = !state.recircInside
        if (Prefs.simulate(ctx)) {
            update(state.copy(recircInside = target, connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, "recirc")
        if (f <= 0) {
            Logx.e("内外循环：通道未配置")
            return
        }
        // ★ 权威写法（用户提供）：setFunctionValue(0x10030100, 0x8, 1) = 开内循环
        //   → 真车实锤本车吃**复合编码** 0x10030101，且 zone 必须是**全局 0x80000000**
        //     （0x8 会被 HAL 静默丢弃）。zone 命中后已自动记住。
        //
        // ★★ v1.4.63：内外循环是**切换型**通道，不是开/关型。
        //   真车日志实锤（19:57:31）：
        //   ```
        //   target=外 → 试 0/2/3 全 FAIL → 试 1（0x10030101）→ OK
        //   ```
        //   也就是说 HAL 只认 `0x10030101` 这一个值，收到就**切换一次**，
        //   不管当前是内还是外。这解释了：
        //   · 为什么"开内"能成、"关"不成 —— 因为关的时候发的 0 系列 HAL 不认
        //   · 为什么回读校验会误判 —— 写 0x10030100 后回读 0x10030101，低字节不同判"未生效"
        //
        //   ⇒ 统一发 `0x10030101`（切换），且**不做回读校验**（切换型通道回读无意义），
        //     以 setFunctionValue 返回值为准。
        update(state.copy(recircInside = target))
        // ★★ v1.4.75：改成「写目标值 → 回读验证 → 不行就换编码」。
        //
        // 为什么不能盲写：HAL 的返回值不可信（A/C 通道实测「返回 false 但车机真生效」）。
        //   而 recirc 的**回读是可靠的**（能读到 0x10030101=内 / 0x0=外），
        //   所以正确做法是「写完读回来对账」。
        //
        // 已知真车事实：
        //   读：zone=0x8 读不到，zone=全局(0x80000000) 能读到 0x10030101
        //   写：全局 zone + 复合编码 0x10030101 → 返回 true（真·内循环）
        //       全局 zone + 复合编码 0x10030100 → 返回 false（外循环，需回读确认）
        // ★ v1.4.78：HAL 写入 + 回读对账全放后台（主线程只做上面的乐观更新）
        val ctxBg = ctx
        hal.execute { recircWrite(ctxBg, target) }
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
        return
    }

    /** ★ v1.4.78：内外循环的实际写入（后台线程） */
    private fun recircWrite(ctx: Context, target: Boolean) {
        val f = VehicleChannels.funcOf(ctx, "recirc")
        if (f <= 0) {
            Logx.e("内外循环：通道未配置")
            return
        }
        val zone = VehicleChannels.zoneOf(ctx, "recirc")
        // ★★ v1.4.80：**裸值优先**！
        //
        // 依据（真车回读形态）：内循环读回 0x10030101、**外循环读回裸值 0x0**。
        // → HAL 这条通道的原生编码就是 **int 裸值**（0=外 / 1=内）。
        //   复合编码 0x10030100（= functionId 本身，低字节 0 但高位非零）会被 HAL
        //   判为非法值而拒绝 —— 这就是「内循环能切、外循环切不回」的原因。
        //
        // ⚠ 且**不能靠返回值判成败**（HAL 返回 false 但车机真生效，A/C 就是这样），
        //   必须**回读对账**。
        // ★★ v1.4.87：**10/11 枚举优先**（HAL 层的真值）
        //
        // 权威依据（Handoff 4.1 + ESP32-S3 破译实时读数）：
        //   ```
        //   {"power":11,"ac":10,"recirc":11, ...}
        //   ### 4.1 开关枚举 = 10/11（不是 0/1）
        //   10 = OFF，11 = ON
        //   ```
        // → 内循环 = 11，外循环 = **10**。
        //   我们之前写 0x10030100（低字节 0）→ HAL 的 {10,11} 体系里根本没有 0
        //     → 外循环永远写不进去；写 0x10030101（低字节 1）被兼容接受
        //     → 内循环「碰巧」能切。
        //
        // ★★★ v3.6：按**系统 Dock 反编译的权威实现**重排候选（2026-10-22 定论）
        //
        // 证据链（oneOS_Dock.apk / com.geely.dockbar，dex 字节码）：
        //  1) VehicleSignalManager 里 `CycleModeSignal(0x10030100, areaId=0x80000000, 0)`
        //     → funcId 0x10030100 + **全局 zone**（跟我们实测「zone=0x8 全 FAIL、
        //       只有 0x80000000 才有效」完全吻合）
        //  2) RecycleSignalClick.processClick 反编译还原：
        //       ```java
        //       int v = signal.getValue();      // getFunctionValue(0x10030100, 0x80000000)
        //       if (v == 0) signal.setValue(0x10030101);  // 当前=外(0) → 写内
        //       else        signal.setValue(0);           // 当前≠0   → 写外
        //       ```
        //  3) CycleModeSignal.setValue → `setFunctionValue(funcId, areaId, value)`
        //     **原样写入，不做复合编码**
        //
        // ⇒ **权威值域**：
        //     外循环 = **裸值 0**
        //     内循环 = **复合 0x10030101**
        //
        // 这解释了我们「怎么切都是内循环」：
        //   · 0x10030101 有效 → 内循环能切进去
        //   · 0x10030100（低字节 0 但高位非零）**HAL 不认** → 切不出去
        //   · 裸值 1 也不认（1 不在值域）
        //   · 唯一能切到外循环的就是**裸值 0** —— 而我们历史上从没在「目标是外」
        //     的场景下单独试过它（23:48:29 那次目标是内，初始就是外，天然看不出效果）
        //
        // ⇒ 新的尝试顺序（每步回读对账，HAL 返回值不可信）：
        //     内循环：复合 0x10030101 → 裸值 11 → 裸值 1
        //     外循环：裸值 0      → 复合 0x10030100 → 裸值 10
        val compOuter = f                                    // 0x10030100（备用，历史实测 HAL 不认）
        var done = false
        // ⓪ ★ v3.6：**按系统权威值先试**（内=0x10030101 / 外=裸值 0）
        //
        // ★★ v4.1：**写完等 400ms 再回读**（这是外循环切不过去的根因修复）
        //
        // 依据（真车实测）：
        //   · HAL 写入到车机生效需要时间 —— DCY11 实测 A/C 写入后 **+401ms** 才回读出变化
        //     ```
        //     12:57:46.270  写 A/C target=关 setFunctionValue(0x10010300, 全局, 0) → OK
        //     12:57:46.671  HVAC_VERIFY 写前=1 写值=0 写后=0 → 已生效   ← +401ms
        //     ```
        //   · 而这里是**写完立即回读**（0ms）→ 永远读到旧值 → 正确的值被误判为失败
        //   · 误判失败 → 继续往下试 → 最后掉进兜底「切换法」，
        //     而切换法第一步写的是 0x10030101（=切到**内**循环，有效！）
        //     ⇒ 结果被推回内循环 —— 正是"能切到内、永远切不到外"的原因
        //
        // 权威值统计（云端全量日志，0x10030100 全部写入尝试）：
        //   zone=全局0x80000000  value=0x10030101 → HAL true  ×18  ← 内循环
        //   zone=全局0x80000000  value=0         → HAL true  ×9   ← 外循环
        //   其余全部 false（0x10030100 ×64 / 1 ×9 / 10 ×7 / 0x10030102 / 0x10030103 / 0x1003010A）
        //   ⚠ 裸值 0 在 zone=0x8 上 7 次全 false → **必须全局 zone**
        // ★★ v4.2：**方法调用代替裸 { } 块** —— 之前这段写在一个独立 { } 块里，
        //   Kotlin 把它提升成 `ExternalSyntheticLambda15`（只 new 不 invoke），
        //   整段成为**死代码**，从来没真正执行过（dexdump 字节码实证）。
        //   现在抽成独立方法，彻底避开闭包提升。
        //
        // ★★★ v3.13.15：**按《空调内外循环接口参考》文档口径**改写
        //   文档给的写法：
        //     int current = carFunction.getFunctionValue(HVAC_CYCLE_MODE);          // 无 area 读
        //     carFunction.setFunctionValue(HVAC_CYCLE_MODE, CYCLE_INNER);           // 无 area 写
        //     carFunction.setFunctionValue(HVAC_CYCLE_MODE, CYCLE_OUTSIDE);          // 无 area 写
        //   权威值：内循环 = 复合 0x10030101；外循环 = **裸值 0**
        //   原车接口内部 area = Integer.MIN_VALUE（= 我们的全局 zone 0x80000000）。
        //
        //   ⇒ 写入顺序：
        //     ① **无 area 重载**（文档首选，VehicleBridge 独立旁路，不影响其它通道）
        //     ② 带 area 重载（zone=全局，原有路径，作为回落）
        //   每步都是「写 → 等 400ms → 严格回读对账」；回读不到明确值时再等一次二次回读。
        val authorityValue = if (target) ((f and 0xFFFFFF00.toInt()) or 1) else 0
        // ① 文档首选：无 area 重载
        if (VehicleBridge.hasNoAreaOverload()) {
            if (recircTryValue(
                    ctx, f, "无area重载", authorityValue, target,
                    write = { v -> VehicleBridge.writeIntNoArea(f, v) },
                    read = { VehicleBridge.readIntNoArea(f) }
                )
            ) done = true
        }
        // ② 文档等价：带 area 重载（zone = 全局 = Integer.MIN_VALUE）
        if (!done) {
            if (recircTryValue(
                    ctx, f, "带area(全局zone)", authorityValue, target,
                    write = { v -> VehicleBridge.writeInt(f, zone, v) },
                    read = { VehicleBridge.readIntNoArea(f) ?: VehicleBridge.readInt(f, zone) }
                )
            ) done = true
        }
        // ① 复合 0x10030100（外循环备用形态；历史实测 HAL 多半不认）
        if (!done && !target) {
            VehicleBridge.writeInt(f, zone, compOuter)
            val back = readRecircState(ctx)
            Logx.d(
                "写 内外循环[复合 0x10030100]：目标=外 value=0x${compOuter.toString(16)} " +
                "zone=0x${zone.toString(16)} 回读=${recircText(back)}"
            )
            if (back != null && back == target) done = true
        }
        // ② 裸值 10/11（另一套枚举体系：10=外 OFF / 11=内 ON）
        val ints1111 = if (target) listOf(11, 1) else listOf(10)
        for (v in ints1111) {
            if (done) break
            VehicleBridge.writeInt(f, zone, v)
            val back = readRecircState(ctx)      // true=内循环 false=外循环 null=读不到
            Logx.d(
                "写 内外循环[裸值]：目标=${if (target) "内" else "外"} value=$v " +
                "zone=0x${zone.toString(16)} 回读=${recircText(back)}"
            )
            if (back != null && back == target) { done = true; break }
        }
        // ③ 复合编码 0x1003010A / 0x1003010B（10/11 体系的复合形态，历史很少走通，留作兜底）
        if (!done) {
            for (v in (if (target) listOf(11) else listOf(10))) {
                val composite = (f and 0xFFFFFF00.toInt()) or v
                VehicleBridge.writeInt(f, zone, composite)
                val back = readRecircState(ctx)
                Logx.d(
                    "写 内外循环[复合]：目标=${if (target) "内" else "外"} value=0x${composite.toString(16)} " +
                    "回读=${recircText(back)}"
                )
                if (back != null && back == target) { done = true; break }
            }
        }
        // ③ Customize 浮点通道（兜底：0.0=外 / 1.0=内）
        if (!done && Prefs.tempCustomizeWriteEnabled(ctx)) {
            VehicleBridge.customizeWriteEnabled = true
            val fv = if (target) 1.0f else 0.0f
            val okC = VehicleBridge.writeTempCustomize(f, zone, fv)
            val back = readRecircState(ctx)
            Logx.d(
                "写 内外循环[Customize float]：value=$fv 返回=$okC 回读=${recircText(back)}"
            )
            if (back != null && back == target) done = true
        }
        if (!done) {
            // 兜底：有些 HAL 把这个通道当「切换」——先切到内(1)，再切到外(0)
            //
            // ★★ v4.2：外循环第二步由 `0x10030100`（func|0）改为**裸值 0**。
            //   依据：真车日志 1.4.96(102) 19:34:33 ——
            //     写 value=0（裸值） → HAL 返回 true
            //     写 value=0x10030101 → HAL 返回 true，1 秒后回读 recirc=0x10030101（内循环）
            //   而 `func|0` = 0x10030100 历史实测 HAL 全部返回 false（64 次全 false）。
            //   系统 Dock 反编译（RecycleSignalClick.processClick）也是：
            //     `if (v == 0) setValue(0x10030101) else setValue(0)` —— 裸值 0 才是权威外循环值。
            Logx.w("内外循环：直接写目标值未生效，改用「切换法」（写 0x10030101 再写裸值 0）")
            if (target) {
                VehicleBridge.writeInt(f, zone, (f and 0xFFFFFF00.toInt()) or 1)
            } else {
                VehicleBridge.writeInt(f, zone, (f and 0xFFFFFF00.toInt()) or 1)
                Thread.sleep(300)
                VehicleBridge.writeInt(f, zone, 0)     // ★ 裸值 0（不是 0x10030100）
                Thread.sleep(400)
                val back = readRecircState(ctx)
                Logx.d(
                    "写 内外循环[切换法·裸值0]：目标=外 value=0(裸值) " +
                    "zone=0x${zone.toString(16)} 回读=${recircText(back)}"
                )
            }
        }
    }

    /**
     * ★★ v4.2：⓪「系统权威值」写入步骤 —— 抽成独立方法。
     *
     * 为什么要抽出来：原代码把这段写在一个**独立 `{ }` 块**里，而块内修改了外层
     * `var done`。Kotlin 编译器会把这种闭包提升为 `$$ExternalSyntheticLambda`，
     * 而在生成的字节码里只 `new` 了该 lambda 对象、**从未 invoke**，
     * 导致整段成为**死代码**（dexdump `ExternalSyntheticLambda15` 实证，
     * 全 dex 搜其 `.invoke` 调用点 = 0）。抽成独立方法后彻底避开闭包提升。
     *
     * 权威值：内循环 = 复合 `0x10030101`；外循环 = **裸值 0**。
     * 写完等 400ms 再回读对账（HAL 生效延迟，实测 A/C 写入 +401ms 才生效）。
     *
     * @return true = 回读已确认达到目标状态
     */
    /**
     * ★ v3.13.15：**文档口径的一次写入尝试**（《空调内外循环接口参考》）。
     *
     * 流程：写入 → 等 400ms（HAL 生效延迟，实测 A/C 写入 +401ms 才生效）→ 回读对账；
     *      回读到**未知值**时再等 400ms 做二次回读（避免误判失败）。
     *
     * @param label 日志标签（区分无 area / 带 area 等形态）
     * @param value 要写的值（内循环 = 复合 0x10030101，外循环 = 裸值 0）
     * @param write 实际写入动作（不同调用形态）
     * @param read  回读动作
     * @return true = 回读**明确**达到目标状态
     */
    private fun recircTryValue(
        ctx: Context,
        f: Int,
        label: String,
        value: Int,
        target: Boolean,
        write: (Int) -> Boolean?,
        read: () -> Int?
    ): Boolean {
        val accepted = write(value)
        Thread.sleep(400)
        var raw = read()
        var state = classifyRecirc(f, raw)
        if (state == null) {
            // 未知 → 再等一次、二次回读（HAL 生效延迟 + 读值抖动）
            Thread.sleep(400)
            raw = read()
            state = classifyRecirc(f, raw)
        }
        Logx.d(
            "写 内外循环[$label]：目标=${if (target) "内" else "外"} " +
            "value=0x${value.toString(16)}($value) HAL返回=$accepted " +
            "回读=${recircRawText(raw)} 判定=${recircStateText(state)}"
        )
        return state != null && state == target
    }

    /**
     * ★ v3.13.15：**严格按文档判定**读回来的循环状态。
     *
     * 文档原文：「内循环的判断条件是返回值**精确等于 0x10030101**；
     * **未知或非激活值不应当作外循环确认**。」
     *
     * ⇒ 内循环 = 精确 0x10030101；外循环 = **精确裸值 0**；
     *   其它（0xff / 0x10030100 / 1 / 10 / 11 …）一律 = **未知（null）**。
     *
     * ⚠ 与旧实现的差别：旧版按低字节判（low==0 → 外），会把 **0x10030100**
     *   （低字节 0 但高位非零、HAL 根本不认的非激活值）误判成「外循环已生效」。
     */
    private fun classifyRecirc(f: Int, raw: Int?): Boolean? {
        if (raw == null) return null
        if (raw == 0) return false                                  // 外循环（裸值 0）
        if (raw == ((f and 0xFFFFFF00.toInt()) or 1)) return true   // 内循环（0x10030101）
        return null                                                 // 未知 / 非激活值
    }

    /** 日志用：原始回读值的可读描述 */
    private fun recircRawText(raw: Int?): String =
        if (raw == null) "读不到"
        else "0x${raw.toString(16)}($raw)"

    /** 日志用：判定结果的可读描述 */
    private fun recircStateText(state: Boolean?): String = when (state) {
        true -> "内循环"
        false -> "外循环"
        null -> "未知"
    }

    /**
     * ★ v1.4.81：读「手动风量」通道的档位（低字节）。
     * 返回 0..9 = 手动档位，**10 = AUTO**，null = 读不到。
     */
    private fun readFanLevel(ctx: Context): Int? {
        val f = VehicleChannels.funcOf(ctx, "fan_manual")
        if (f <= 0) return null
        for (z in intArrayOf(VehicleChannels.zoneOf(ctx, "fan_manual"), 0x8, ZONE_GLOBAL)) {
            val raw = VehicleBridge.readInt(f, z) ?: continue
            if (raw == 0xff || raw == 0xfe || raw == 0xfd) continue
            val idx = raw and 0xFF
            if (idx in 0..FAN_LEVEL_AUTO) return idx
        }
        return null
    }

    /**
     * ★ v1.4.83：读车机当前的双温同步状态（true=开 false=关 null=读不到）。
     *
     * 通道 0x10060500（HVAC_FUNC_TEMP_DUAL），zone = 全局 0x80000000
     * —— 系统自己也是这么读的（logcat: `getFunctionValue HVAC_FUNC_TEMP_DUAL
     * [0x10060500] zone:VehicleZone.ZONE_ALL [0x80000000] value 0`）。
     * 值：0=关，1=开。
     */
    private fun readDualState(ctx: Context): Boolean? {
        val f = VehicleChannels.funcOf(ctx, "temp_dual")
        if (f <= 0) return null
        for (z in intArrayOf(ZONE_GLOBAL, 0x8, 1)) {
            val raw = VehicleBridge.readInt(f, z) ?: continue
            if (raw == 0xff || raw == 0xfe || raw == 0xfd) continue
            val v = raw and 0xFF
            if (v == 0) return false
            if (v == 1) return true
        }
        return null
    }

    /**
     * ★ v1.4.84：设置 AUTO 风道档位（1..5 档）—— **直接写车机**。
     * 供主界面「AUTO 风道档位」滑条调用（之前只存本地 Pref，车机没反应）。
     */
    fun setFanAutoLevel(ctx: Context, level: Int) {
        val l = level.coerceIn(1, 5)
        Prefs.setFanAutoLevel(ctx, l)
        update(state.copy(fanAuto = true, fanLevel = l, fanMax = 5))
        markPendingFan(level = l, auto = true)
        if (Prefs.simulate(ctx)) return
        val ctxBg = ctx
        hal.execute {
            val f = VehicleChannels.funcOf(ctxBg, "fan_auto")
            if (f <= 0) {
                Logx.e("AUTO 风道档位：通道未配置")
                return@execute
            }
            val zone = VehicleChannels.zoneOf(ctxBg, "fan_auto")
            val value = fanAutoValue(l)
            val r = VehicleBridge.writeInt(f, zone, value)
            val back = VehicleBridge.readInt(f, zone)
            val backLvl = back?.let { fanAutoLevelOf(it) } ?: 0
            Logx.d(
                "设置 AUTO 风道档位：" + l + " 档 → value=0x" + value.toString(16) +
                " zone=0x" + zone.toString(16) + " 返回=" + r +
                " 回读档位=" + backLvl
            )
        }
    }

    /** 把回读的循环状态转成文字（true=内循环 false=外循环 null=读不到） */
    private fun recircText(back: Boolean?): String = when (back) {
        true -> "内循环"
        false -> "外循环"
        null -> "读不到"
    }

    // =====================================================================
    // ★ v3.7 取证窗口（默认关闭）
    // =====================================================================

    /**
     * 取证窗口的截止时间戳（`System.currentTimeMillis()`）。
     * **默认 0 = 关闭** —— 平时不产生任何额外开销、不打任何取证日志、
     * 也不注册任何额外的 watcher 通道。
     *
     * 只在用户点「抓循环取证（系统 Dock/空调 App）」按钮后才打开，
     * 窗口内才做「额外取证动作」，到期自动失效。
     */
    @Volatile
    private var evidenceUntil = 0L

    /** 取证窗口是否开启 */
    fun evidenceOn(): Boolean = evidenceUntil > System.currentTimeMillis()

    /**
     * 打开取证窗口。
     * @param durationMs 窗口时长（默认 2 分钟）
     */
    fun openEvidence(durationMs: Long = 120_000L) {
        evidenceUntil = System.currentTimeMillis() + durationMs
        Logx.d("★ 取证窗口已开启：${durationMs / 1000} 秒内做取证（到期自动关闭）")
    }

    /** 手动关闭取证窗口 */
    fun closeEvidence() {
        evidenceUntil = 0L
        Logx.d("★ 取证窗口已关闭")
    }

    /**
     * 取证窗口内的**主动轮询读**：把循环 / AUTO 总开关的原始值打出来。
     *
     * 为什么要主动读而不是只靠 watcher 回调：
     *   · watcher 在本车**默认是关闭的**（`Prefs.vehicleWatcherEnabled`，
     *     因为「收到第一次回调时原生层可能拖崩进程」）；
     *   · 就算开着，回调给的是**回调时刻的值**，可能已经变了。
     *   → 主动读能拿到「此刻」的确切原始值，是最硬的证据。
     *
     * ⚠ 只在窗口内调用；窗口外一次 binder 调用都不多。
     */
    fun evidencePoll(ctx: Context) {
        if (!evidenceOn()) return
        val c = appCtx ?: ctx
        // ① 内外循环：把几个候选 zone 都读一遍（看哪个 zone 有值、值是什么形态）
        val f = VehicleChannels.FUNC_CIRCULATION
        for (z in intArrayOf(ZONE_GLOBAL, 0x8)) {
            val raw = VehicleBridge.readInt(f, z)
            if (raw == null) continue
            val hex = "0x" + Integer.toHexString(raw)
            Logx.d(
                "★ 取证[轮询]：内外循环 zone=0x${z.toString(16)} value=$raw ($hex) " +
                "→ ${recircTextOf(raw)}"
            )
        }
        // ② AUTO 总开关（0x10010200，全局 zone）
        val rawAuto = VehicleBridge.readInt(VehicleChannels.FUNC_AUTO, ZONE_GLOBAL)
        if (rawAuto != null) {
            Logx.d(
                "★ 取证[轮询]：AUTO总开关 zone=0x${ZONE_GLOBAL.toString(16)} " +
                "value=$rawAuto (0x${Integer.toHexString(rawAuto)})"
            )
        }
    }

    /** 按系统权威值域判断一个原始值代表什么（外/内/看不懂） */
    private fun recircTextOf(raw: Int): String = when {
        raw == 0 -> "外循环（裸值 0）"
        raw == 0x10030101 -> "内循环（复合 0x10030101）"
        raw == 0x10030100 -> "外循环？复合 0x10030100（HAL 多半不认）"
        (raw and 0xFF) == 0 -> "外循环（低字节 0）"
        (raw and 0xFF) == 1 -> "内循环（低字节 1）"
        (raw and 0xFF) == 10 -> "外循环（10/11 枚举）"
        (raw and 0xFF) == 11 -> "内循环（10/11 枚举）"
        else -> "看不懂"
    }

    /** 读当前内外循环状态（true=内循环，false=外循环，null=读不到） */
    private fun readRecircState(ctx: Context): Boolean? {
        val f = VehicleChannels.funcOf(ctx, "recirc")
        if (f <= 0) return null
        // ★ v3.13.15：优先用**文档口径的无 area 读** getFunctionValue(functionId)
        VehicleBridge.readIntNoArea(f)?.let { return classifyRecirc(f, it) }
        // ★ v3.13.6：recirc 的 zone 已是权威的全局 0x80000000（zoneOf 对 recirc 强制返回它），
        //   这里仍保留 0x8 / 1 作兜底（万一某些车机版本读的是分区 zone）。
        val zones = intArrayOf(ZONE_GLOBAL, 0x8, 1)
        for (z in zones) {
            val raw = VehicleBridge.readInt(f, z) ?: continue
            // ★ v3.13.15：**严格判定**（文档口径）：只有精确 0 / 0x10030101 才算数，
            //   其它值（0xff / 0x10030100 / 1 / 10 / 11 …）= 未知，继续试下一个 zone。
            val st = classifyRecirc(f, raw)
            if (st != null) return st
        }
        return null
    }

    /** A/C 压缩机开 / 关（长按主驾温度触发） */
    fun toggleAc(ctx: Context) {
        val target = !state.acOn
        if (Prefs.simulate(ctx)) {
            update(state.copy(acOn = target, connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, "ac")
        if (f <= 0) {
            Logx.e("A/C：通道未配置")
            return
        }
        // 与内外循环同一套布尔通道：value 用裸枚举 0/1
        val value = if (target) 1 else 0
        // 乐观更新：图标立即响应；写失败仅打日志，不阻塞 UI
        update(state.copy(acOn = target))
        // ★ v1.4.78：写入放后台（binder IPC 会让点击「顿一下」）
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, "ac", value)
            Logx.d(
                "写 A/C target=${if (target) "开" else "关"} " +
                "调用=车控写入(0x${f.toString(16)}, 0x${VehicleChannels.zoneOf(ctxBg, "ac").toString(16)}, $value) → " +
                if (ok) "OK" else "HAL 返回 false（该通道读回 0xff，回读校验失效；实测车机会生效）"
            )
        }
        // ★ v1.4.60：A/C 通道读回恒 0xff（UNKNOWN），所以 HVAC_VERIFY 的回读校验对它无效
        //   （拿 0xff 比对永远判「未生效」）。用户 2026-10-20 确认：A/C / 前挡 / 后挡
        //   **车机真实响应**，尽管 setFunctionValue 返回 false。
        //   ⇒ 对这三个通道，setFunctionValue 返回 false 只表示「HAL 未确认」，不代表被拒。
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** 风速直接关到 0 档（长按风速触发）。乐观更新 + 400ms 后回读校准 */
    fun setFanOff(ctx: Context) {
        if (Prefs.simulate(ctx)) {
            update(state.copy(fanLevel = 0, fanAuto = false, fanMax = 9, connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, "fan_manual")
        if (f <= 0) return
        // 乐观更新：图标立即响应
        update(state.copy(fanLevel = 0, fanAuto = false, fanMax = 9))
        val ok = writeChannel(ctx, "fan_manual", 0)
        Logx.d("写 风速 0（OFF）→ ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        scheduleRefresh(ctx)   // ★ v3.4：合并刷新（连点只跑最后一次）
    }

    /** 风向循环切换（按 7 种组合位掩码循环） */
    fun cycleAirDirection(ctx: Context) {
        val order = AIR_MODES
        val cur = state.airDirection
        val idx = order.indexOf(cur)
        val next = if (idx < 0) order.first() else order[(idx + 1) % order.size]
        setAirDirection(ctx, next)
    }

    /**
     * 直接设置风向为指定的位组合（1=吹脸 2=吹脚 4=吹前挡，至少选中一项）。
     * 供风向弹窗的 3 个独立开关使用（每个方向单独开/关，可任意组合）。
     */
    fun setAirDirection(ctx: Context, bits: Int) {
        val next = bits and 0x07
        if (next == 0) {
            Logx.d("风向：不能全部关闭，忽略")
            return
        }
        if (Prefs.simulate(ctx)) {
            update(state.copy(airDirection = next, connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, "air_direction")
        if (f <= 0) {
            Logx.e("风向：通道未配置")
            return
        }
        // 乐观更新：图标立即响应
        update(state.copy(airDirection = next))
        val ok = writeChannel(ctx, "air_direction", next)
        Logx.d("写 风向 mode=0x${next.toString(16)} (${AirFlowDrawable.label(next)}) → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
    }

    /** 主驾座椅通风 ±1 档（0 = 关） */
    fun seatVentStep(ctx: Context, delta: Int) {
        val next = (state.seatVent + delta).coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.seatVent) return
        setSeatVentLevel(ctx, next)
    }

    /** 主驾座椅加热 ±1 档（0 = 关） */
    fun seatHeatStep(ctx: Context, delta: Int) {
        val next = (state.seatHeat + delta).coerceIn(0, SEAT_LEVEL_MAX)
        if (next == state.seatHeat) return
        setSeatHeatLevel(ctx, next)
    }

    /**
     * 主驾座椅通风：直接设到指定档位（0=关 1=弱 2=中 3=强），供「高档→低档」循环用。
     * ★ 通道：functionId=0x10050100（HVAC_FUNC_SEAT_VENTILATION），zone=1（主驾）
     *   取值 = functionId + 档位。
     */
    fun setSeatVentLevel(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_vent", state.seatVent, level,
        set = { s, v -> s.copy(seatVent = v) },
        label = "座椅通风"
    )

    /**
     * 主驾座椅加热：直接设到指定档位（0=关 1..3）。
     * ★ 通道：functionId=0x10050200（HVAC_FUNC_SEAT_HEATING），zone=1（主驾）
     */
    fun setSeatHeatLevel(ctx: Context, level: Int) = writeSeatLevel(
        ctx, "seat_heat", state.seatHeat, level,
        set = { s, v -> s.copy(seatHeat = v) },
        label = "座椅加热"
    )

    /** 座椅通风 / 加热共用的写档位逻辑 */
    private fun writeSeatLevel(
        ctx: Context,
        key: String,
        cur: Int,
        level: Int,
        set: (State, Int) -> State,
        label: String
    ) {
        val next = level.coerceIn(0, SEAT_LEVEL_MAX)
        if (next == cur) return
        if (Prefs.simulate(ctx)) {
            update(set(state, next).copy(connected = true))
            return
        }
        val f = VehicleChannels.funcOf(ctx, key)
        if (f <= 0) {
            Logx.e("$label：通道未配置")
            return
        }
        // 取值：座椅通风/加热的 HAL 规范值是「纯档位 0..3」，不是 functionId|档位
        // （watcher 回传 seat_vent/seat_heat 的 value=0/1/2/3，而非 0x100501xx）。
        // 与内外循环统一：一律写裸档位，失败才回退复合编码。
        // 乐观更新：图标立即响应
        update(set(state, next))
        val ctxBg = ctx
        hal.execute {
            val ok = writeChannel(ctxBg, key, next)
            Logx.d("写 $label idx=$next → ${if (ok) "OK" else "FAIL（本地状态已更新）"}")
        }
    }

    // =====================================================================

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun update(s: State) {
        appCtx?.let { ctx ->
            s.driverTempC
                ?.takeIf { it.isFinite() && it in 5f..45f }
                ?.takeIf { it != Prefs.lastDriverTempC(ctx) }
                ?.let { Prefs.setLastDriverTempC(ctx, it) }
            s.passengerTempC
                ?.takeIf { it.isFinite() && it in 5f..45f }
                ?.takeIf { it != Prefs.lastPassengerTempC(ctx) }
                ?.let { Prefs.setLastPassengerTempC(ctx, it) }
        }
        state = s
        // 回调统一在主线程派发；快照也在主线程取，避免与 add/removeListener 并发
        main.post {
            val snapshot = runCatching { listeners.toList() }.getOrDefault(emptyList())
            snapshot.forEach { runCatching { it(s) } }
        }
    }
}
