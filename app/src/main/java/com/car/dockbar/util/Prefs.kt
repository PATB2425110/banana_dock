package com.car.dockbar.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部用户配置集中在这里，键名稳定，方便车机上直接改 XML 或用调试页改。
 */
object Prefs {
    private const val NAME = "dock_prefs"

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------- 悬浮窗 ----------------
    fun overlayEnabled(ctx: Context) = sp(ctx).getBoolean("overlay_enabled", true)
    fun setOverlayEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("overlay_enabled", v).apply()

    /** 曾经读到过前台检测已开启。系统重启把无障碍从名单里拿掉之后，凭这个标记写回去。 */
    fun accKeep(ctx: Context) = sp(ctx).getBoolean("acc_keep", false)
    fun setAccKeep(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("acc_keep", v).apply()

    /** Dock 固定贴屏幕底部。保留旧键兼容，但不再提供顶部/底部切换。 */
    fun dockAtBottom(ctx: Context) = true
    fun setDockAtBottom(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dock_at_bottom", true).apply()

    /** 悬浮窗宽度（px）。0 = 自动铺满屏幕宽度 */
    fun dockWidthPx(ctx: Context) = sp(ctx).getInt("dock_width_px", 1920)
    fun setDockWidthPx(ctx: Context, v: Int) = sp(ctx).edit().putInt("dock_width_px", v).apply()

    /** 悬浮窗高度（px） */
    fun dockHeightPx(ctx: Context): Int {
        val s = sp(ctx)
        if (!s.getBoolean("dock_height_default_128", false)) {
            val cur = if (s.contains("dock_height_px")) s.getInt("dock_height_px", 128) else 128
            val next = if (cur == 120) 128 else cur
            s.edit().putInt("dock_height_px", next).putBoolean("dock_height_default_128", true).apply()
            return next
        }
        return s.getInt("dock_height_px", 128)
    }
    fun setDockHeightPx(ctx: Context, v: Int) = sp(ctx).edit().putInt("dock_height_px", v).apply()

    /** 底部 / 顶部的偏移量（px） */
    fun dockMarginPx(ctx: Context) = sp(ctx).getInt("dock_margin_px", 0)
    fun setDockMarginPx(ctx: Context, v: Int) = sp(ctx).edit().putInt("dock_margin_px", v).apply()

    /**
     * 车机屏幕高度（px）。固定按车机实际分辨率 1080 走，不去问系统。
     * 只用于"贴底部"时算 y 坐标。
     */
    fun screenHeightPx(ctx: Context) = sp(ctx).getInt("screen_height_px", 1080)
    fun setScreenHeightPx(ctx: Context, v: Int) = sp(ctx).edit().putInt("screen_height_px", v).apply()

    /** 整个悬浮窗的透明度 0..1 */
    fun windowAlpha(ctx: Context) = sp(ctx).getFloat("window_alpha", 1f)
    fun setWindowAlpha(ctx: Context, v: Float) = sp(ctx).edit().putFloat("window_alpha", v).apply()

    // ---------------- Dock 外观 ----------------
    /** 底色不透明度 0..255，0 = 完全透明（纯图标浮在桌面上） */
    fun bgAlpha(ctx: Context) = sp(ctx).getInt("bg_alpha", 0x59)
    fun setBgAlpha(ctx: Context, v: Int) = sp(ctx).edit().putInt("bg_alpha", v).apply()

    /** 底色 RGB */
    fun bgColor(ctx: Context) = sp(ctx).getInt("bg_color", 0x000000)
    fun setBgColor(ctx: Context, v: Int) = sp(ctx).edit().putInt("bg_color", v).apply()

    /**
     * 圆角半径（px）。默认直角。
     * 换键以丢掉旧的 14px 默认值；外观页滑条仍可另存新值。
     */
    fun cornerRadiusPx(ctx: Context) = sp(ctx).getInt("corner_radius_px_square", 0)
    fun setCornerRadiusPx(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("corner_radius_px_square", v).apply()

    /** 是否画分组竖分隔线 */
    fun showSeparators(ctx: Context) = sp(ctx).getBoolean("show_separators", true)
    fun setShowSeparators(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("show_separators", v).apply()

    /** 图标/文字整体缩放系数（旧字段，保留兼容） */
    fun uiScale(ctx: Context) = sp(ctx).getFloat("ui_scale", 1f)
    fun setUiScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("ui_scale", v).apply()

    /** 图标缩放系数（独立于文字） */
    fun iconScale(ctx: Context) = sp(ctx).getFloat("icon_scale", 1f)
    fun setIconScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("icon_scale", v).apply()

    /** 文字缩放系数（独立于图标） */
    fun textScale(ctx: Context) = sp(ctx).getFloat("text_scale", 1f)
    fun setTextScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("text_scale", v).apply()

    /** 强调色（激活态，如内循环开启 / 通风开启） */
    fun accentColor(ctx: Context) = sp(ctx).getInt("accent_color", 0xFF4EA1FF.toInt())
    fun setAccentColor(ctx: Context, v: Int) = sp(ctx).edit().putInt("accent_color", v).apply()

    // ---------------- 行为 ----------------
    /** 模拟模式：不连车控，用内置假数据，方便在没有车机的环境调 UI */
    fun simulate(ctx: Context) = sp(ctx).getBoolean("simulate", false)
    fun setSimulate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("simulate", v).apply()

    /** 点击"主驾/副驾温度"时，是否同时把两侧温度联动 */
    fun syncTemp(ctx: Context) = sp(ctx).getBoolean("sync_temp", false)
    fun setSyncTemp(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("sync_temp", v).apply()

    // ---------------- 跳转入口 ----------------
    // ---- 跳转入口默认值：来自车机实测日志，用户可在配置页覆盖 ----
    // 注意 getString(key, null) ?: DEF 的写法：显式存空串会返回空串，仍可清空
    private val DEF_SETTINGS_PKG get() = com.car.dockbar.util.IntentLauncher.DEF_SETTINGS_PKG
    private val DEF_SETTINGS_CLS get() = com.car.dockbar.util.IntentLauncher.DEF_SETTINGS_CLS
    private val DEF_HVAC_PKG get() = com.car.dockbar.util.IntentLauncher.DEF_HVAC_PKG
    private val DEF_HVAC_CLS get() = com.car.dockbar.util.IntentLauncher.DEF_HVAC_CLS
    private val DEF_SEAT_PKG get() = com.car.dockbar.util.IntentLauncher.DEF_SEAT_PKG
    private val DEF_SEAT_CLS get() = com.car.dockbar.util.IntentLauncher.DEF_SEAT_CLS

    /** ③ 车机设置 App：包名 / Activity */
    fun settingsPkg(ctx: Context) = sp(ctx).getString("settings_pkg", null) ?: DEF_SETTINGS_PKG
    fun settingsCls(ctx: Context) = sp(ctx).getString("settings_cls", null) ?: DEF_SETTINGS_CLS
    fun settingsAction(ctx: Context) = sp(ctx).getString("settings_action", null) ?: ""

    /** ⑬ 座椅设置入口 */
    fun seatPkg(ctx: Context) = sp(ctx).getString("seat_pkg", null) ?: DEF_SEAT_PKG
    fun seatCls(ctx: Context) = sp(ctx).getString("seat_cls", null) ?: DEF_SEAT_CLS
    fun seatAction(ctx: Context) = sp(ctx).getString("seat_action", null) ?: ""

    /**
     * 座椅深链 extra：车机日志里没有抓到独立的座椅 Activity，
     * 座椅页其实是空调 App 内部的子页。这里把 extra 透传给空调 Activity，
     * 一旦知道空调 App 用于选中座椅页的 key/value，填在这里即可直达座椅页。
     */
    fun seatExtraKey(ctx: Context) = sp(ctx).getString("seat_extra_key", null) ?: ""
    fun seatExtraVal(ctx: Context) = sp(ctx).getString("seat_extra_val", null) ?: ""

    /** ⑦ 空调风向 / ⑤⑩ 温度点击打开的面板 */
    fun airDirPkg(ctx: Context) = sp(ctx).getString("airdir_pkg", null) ?: DEF_HVAC_PKG
    fun airDirCls(ctx: Context) = sp(ctx).getString("airdir_cls", null) ?: DEF_HVAC_CLS
    fun airDirAction(ctx: Context) = sp(ctx).getString("airdir_action", null) ?: ""

    /**
     * ★ v1.4.96：**最近任务（后台应用列表）**入口包名。
     * 车机上实测是 `com.geely.recents`（日志里出现过「前台 com.geely.recents」）。
     * D01「返回桌面」长按时打开它。
     */
    fun recentsPkg(ctx: Context) = sp(ctx).getString("recents_pkg", null) ?: com.car.dockbar.util.IntentLauncher.DEF_RECENTS_PKG
    fun setRecentsPkg(ctx: Context, v: String) =
        sp(ctx).edit().putString("recents_pkg", v.trim()).apply()

    fun recentsCls(ctx: Context) = sp(ctx).getString("recents_cls", null) ?: ""
    fun setRecentsCls(ctx: Context, v: String) =
        sp(ctx).edit().putString("recents_cls", v.trim()).apply()

    fun putString(ctx: Context, key: String, value: String) =
        sp(ctx).edit().putString(key, value).apply()

    // ---------------- 温度换算 ----------------
    /** 温度索引 1 对应的摄氏温度 */
    fun tempBase(ctx: Context) = sp(ctx).getFloat("temp_base", 17.5f)
    fun setTempBase(ctx: Context, v: Float) = sp(ctx).edit().putFloat("temp_base", v).apply()

    /**
     * ★ v1.4.78：每档温度步进 —— **默认 1.0℃**（用户要求：Dock 上的 ± 改成 1 度调节）。
     *
     * 换了新 key `temp_step_v2`：老用户存的 `temp_step=0.5` 不再被读取，
     * 升级后自动变成 1 度（用户可再在设置里改）。
     */
    fun tempStep(ctx: Context) = sp(ctx).getFloat("temp_step_v2", 1.0f)
    fun setTempStep(ctx: Context, v: Float) = sp(ctx).edit().putFloat("temp_step_v2", v).apply()

    /** 第一次读到车外温度时：高于 25℃ 是否打开主驾座椅通风。 */
    fun autoSeatVentHot(ctx: Context) = sp(ctx).getBoolean("auto_seat_vent_hot", true)
    fun setAutoSeatVentHot(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("auto_seat_vent_hot", v).apply()

    /** 第一次读到车外温度时：低于 15℃ 是否打开主驾和副驾座椅加热。 */
    fun autoSeatHeatCold(ctx: Context) = sp(ctx).getBoolean("auto_seat_heat_cold", true)
    fun setAutoSeatHeatCold(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("auto_seat_heat_cold", v).apply()

    /** 上面两条只判断一次。读到有效车外温度后置位，之后不再自动开。 */
    fun ambientSeatDone(ctx: Context) = sp(ctx).getBoolean("ambient_seat_once", false)
    fun setAmbientSeatDone(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("ambient_seat_once", v).apply()

    /**
     * ★ v1.4.82：AUTO 风道的档位（1..5 档）。
     * 这台车有 5 档 AUTO 风道（反编译 AutoFanSpeedClick → 0x10020200，值 0x10020201..05）。
     */
    fun fanAutoLevel(ctx: Context) = sp(ctx).getInt("fan_auto_level", 3)
    fun setFanAutoLevel(ctx: Context, v: Int) = sp(ctx).edit().putInt("fan_auto_level", v).apply()

    /** 索引起点（默认 1） */
    fun tempIndexBase(ctx: Context) = sp(ctx).getInt("temp_index_base", 1)
    fun setTempIndexBase(ctx: Context, v: Int) = sp(ctx).edit().putInt("temp_index_base", v).apply()

    // ---------------- 记忆上次空调设定温度 ----------------
    /** 最近一次有效的主驾温度设定；null 表示尚无历史值 */
    fun lastDriverTempC(ctx: Context): Float? {
        val prefs = sp(ctx)
        if (!prefs.contains("last_temp_driver_c")) return null
        return prefs.getFloat("last_temp_driver_c", Float.NaN).takeIf { it.isFinite() && it in 5f..45f }
    }
    fun setLastDriverTempC(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat("last_temp_driver_c", v).apply()

    /** 最近一次有效的副驾温度设定；null 表示尚无历史值 */
    fun lastPassengerTempC(ctx: Context): Float? {
        val prefs = sp(ctx)
        if (!prefs.contains("last_temp_passenger_c")) return null
        return prefs.getFloat("last_temp_passenger_c", Float.NaN).takeIf { it.isFinite() && it in 5f..45f }
    }
    fun setLastPassengerTempC(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat("last_temp_passenger_c", v).apply()

    // ---------------- 温度极值显示（HI / LO） ----------------
    /**
     * 低于等于该温度显示 LO。
     * 默认 15.5℃ —— 与车机 HvacConstants.TEMP_C_MIN="15.5" / MIN_SHOW_TAMP="LO" 一致。
     */
    fun tempMinC(ctx: Context) = sp(ctx).getFloat("temp_min_c", 15.5f)
    fun setTempMinC(ctx: Context, v: Float) = sp(ctx).edit().putFloat("temp_min_c", v).apply()

    /**
     * 高于等于该温度显示 HI。
     * 默认 28.5℃ —— 与车机 HvacConstants.TEMP_C_MAX="28.5" / MAX_SHOW_TAMP="HI" 一致。
     */
    fun tempMaxC(ctx: Context) = sp(ctx).getFloat("temp_max_c", 28.5f)
    fun setTempMaxC(ctx: Context, v: Float) = sp(ctx).edit().putFloat("temp_max_c", v).apply()

    // ---------------- 车控诊断开关（排查闪退用） ----------------
    /**
     * 是否启用事件监听（registerFunctionValueWatcher）。
     * 默认关闭：仅靠定时轮询刷新，避开「动态代理 watcher 被原生 HAL 回调时拖崩进程」的已知问题
     * （这类 native 崩溃 runCatching 兜不住，表现为「打开约 1 秒就闪退」）。
     * 排查时单独打开它，即可验证是不是 watcher 引发的崩溃。
     */
    /**
     * 是否注册车控事件监听（registerFunctionValueWatcher）。
     *
     * ★ 默认 **false**。
     *
     * 真车取证（2026-10-20，CX11，v1.4.47 → v1.4.49）：
     *  - v1.4.47 监听列表含温度 → 11 次崩溃里 9 次的最后一行是温度回调；
     *  - v1.4.49 把温度剔除后 **照样崩**，崩点移到座椅 / A-C / 风向回调；
     *  - 崩溃信号是 **SIGABRT（signal 6, si_code -6）**，不是 SIGSEGV —— 说明是厂商库
     *    主动 `abort()`，而不是野指针。结合「系统 App 能正常用 watcher、普通 App 一回调就死」，
     *    基本可判定为厂商库对调用方身份的断言。
     *
     * ⇒ **不是某个通道的问题，是 watcher 回调对普通 App 整体不可用。**
     *   状态一律靠 2.5 秒轮询（[VehicleController.refresh]），实测轮询全程无崩溃。
     *
     * 想验证的用户可在主界面打开；崩一次会被 [com.car.dockbar.App] 自动关回去。
     */
    fun vehicleWatcherEnabled(ctx: Context) = sp(ctx).getBoolean("vehicle_watcher_enabled", false)
    fun setVehicleWatcherEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("vehicle_watcher_enabled", v).apply()

    /**
     * 是否把「温度通道 0x10060100」也放进事件监听数组。
     *
     * ★ 默认 **关闭**，这是 1.4.47 在 CX11 上「一直崩到只剩 UI」的直接根因：
     *   温度在 ECarX 上是 **Customize（float）通道**，注册进 watcher 后，厂商库会以
     *   `onCustomizeFunctionValueChanged(int, int, float)` 把温度推给普通 App 进程，
     *   **一收到这条回调进程就 native SIGSEGV**（runCatching / CrashHandler 都拦不住）。
     *
     *   真车取证：2026-10-20 00:27–00:45 共 11 次崩溃，其中 9 次落盘日志的
     *   **最后一行**都是 `车控事件 0x10060100 zone=1 value=21.0` —— 崩点高度一致。
     *   剩下 2 次最后一行是「状态机：已显示」，即挂载后还没来得及打日志就崩在第一波回调上。
     *
     *   只监听 int 通道（风量 / 风向 / 循环 / 座椅 / A-C）不会触发这条回调，是安全的。
     *   想验证温度通道的用户可在主界面单独打开它（崩了会自动关回去，见 App.onCreate）。
     *
     * ★★★ v4.0（2026-10-26）：**默认值改为 true**。
     *   原因：在 **DCY11**（另一台车，QUALCOMM DCY11）上实测 —— 温度通道
     *   `0x10060100` 的 **int 读全 zone 返回 0xff**（不暴露），
     *   唯一能拿到温度的是 `onCustomizeFunctionValueChanged` **回调**。
     *   开关关闭时 → 回调不注册 → **温度永远显示 `—`，且用户以为调温无效**
     *   （实际写入是生效的，只是显示不更新）。
     *   用户确认：「温度监听开关默认打开」→ 调和读都正常了。
     *
     *   ⚠ 崩溃风险仍在（CX11 上 11 次崩溃 9 次最后一行是 `车控事件 0x10060100 …`），
     *   所以**保留熔断**：真崩了会自动关回去（App.onCreate 的 streak 检测）。
     */
    fun vehicleTempWatcherEnabled(ctx: Context) =
        sp(ctx).getBoolean("vehicle_temp_watcher_enabled", true)

    fun setVehicleTempWatcherEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("vehicle_temp_watcher_enabled", v).apply()

    /** 是否启用系统媒体接口（MediaSession / 通知使用权）。关闭可确认是不是媒体通道导致闪退 */
    fun mediaEnabled(ctx: Context) = sp(ctx).getBoolean("media_enabled", true)
    fun setMediaEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("media_enabled", v).apply()

    // ---------------- v1.4.51：闪退二分隔离开关 ----------------
    /**
     * ★★ 一级隔离开关：**完全不初始化车控通道**（连 `Car.create()` 都不调）。
     *
     * 背景：1.4.49 / 1.4.50 已经把温度通道、事件监听、车控写入全部关掉，CX11 上
     * **照样闪退**（18:34–18:35 十次崩溃全部是 SIGABRT signal 6 si_code -6，
     * pc/lr 跨版本完全一致，说明是同一个 abort 点）。剩下的厂商 API 接触面只剩
     * 2.5 秒一次的 `getFunctionValue` 轮询 —— 所以必须能把它整个摘掉才能定性。
     *
     * 关掉后：Dock 完全不碰厂商任何接口，空调状态不再刷新（显示上次缓存值）。
     * 若关掉后不再闪退 → 崩溃源确定在厂商车控库；若还闪退 → 与车控无关，另查。
     */
    fun vehicleApiEnabled(ctx: Context) = sp(ctx).getBoolean("vehicle_api_enabled", true)
    fun setVehicleApiEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("vehicle_api_enabled", v).apply()

    /**
     * ★★ v1.4.65（实验）：温度走 **Customize 浮点通道** 写入。
     *
     * 背景：温度 int 通道 `0x10060100` 实测只读（notavailable，1362 次写入 0 成功）。
     * 而系统空调的温度是 float（watcher 回调 18.5/22.5），
     * 说明正规写入通道是 `setCustomizeFunctionValue(id, zone, float)`。
     * 该通道在 1.4.47 被禁（「调用即 SIGSEGV」），但那个结论很可能是误诊
     * （当时 dock-contrast 截屏线程每秒在跑，正是后来证实的 SIGABRT 真凶；
     *  且当时取证只有 maps 原文，无法归因）。
     *
     * ⚠️ 默认关闭。打开后若崩溃，熔断会自动停写，取证会给出真凶。
     */
    fun tempCustomizeWriteEnabled(ctx: Context) = sp(ctx).getBoolean("temp_customize_write", true)
    fun setTempCustomizeWriteEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("temp_customize_write", v).apply()

    /**
     * ★★ v1.4.59：调温实体按键的 keycode。
     *
     * 为什么需要：温度通道 `0x10060100` 在 CX11 上 `isFunctionSupported=notavailable`
     * （HAL 只开放通知、不开放写入，`CONTROL_CAR_CLIMATE` 又是 signature 级授不了），
     * 所以车控 API 写不进去。但**实体按键能调温度** —— 说明按键走的是输入子系统。
     * shell 在 input 组 + 本机 adb 免认证 → `input keyevent <keycode>` 等价于按实体键。
     *
     * 默认 -1 = 未配置。用主界面「★ 抓实体按键」抓到真实 keycode 后填进来，
     * 或用「模拟 keyevent」盲试（会连带读温度回调验证）。
     */
    fun tempKeyCode(ctx: Context, driver: Boolean) =
        sp(ctx).getInt(if (driver) "temp_keycode_driver" else "temp_keycode_passenger", -1)

    fun setTempKeyCode(ctx: Context, driver: Boolean, v: Int) =
        sp(ctx).edit().putInt(if (driver) "temp_keycode_driver" else "temp_keycode_passenger", v).apply()


    // ---------------- 日志联网上传 ----------------
    /**
     * 是否把日志上传到 [com.car.dockbar.util.LogUploader.endpoint]。
     * 默认开启：用户本次需求就是「上传给 AI 直接排查」，装完即可生效。
     * 主界面可随时关闭，关闭后不会再上传任何内容。
     */
    fun logUploadEnabled(ctx: Context) = sp(ctx).getBoolean("log_upload_enabled", true)
    fun setLogUploadEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("log_upload_enabled", v).apply()

    /** 上传地址（POST JSON）。空 = 用 LogUploader.DEFAULT_URL */
    fun logUploadUrl(ctx: Context) = sp(ctx).getString("log_upload_url", "") ?: ""
    fun setLogUploadUrl(ctx: Context, v: String) =
        sp(ctx).edit().putString("log_upload_url", v.trim()).apply()

    /** 设备标识（首次使用时生成并固定，便于区分多台车） */
    fun logDeviceId(ctx: Context) = sp(ctx).getString("log_device_id", "") ?: ""
    fun setLogDeviceId(ctx: Context, v: String) =
        sp(ctx).edit().putString("log_device_id", v).apply()

    // ---------------- 自动降级（安全模式阶梯） ----------------
    /**
     * 安全等级。连续崩溃时由 App 自动递增，用来做「远程二分定位」：
     *  0 = 正常，全功能
     *  1 = 关闭「空调状态弹窗」（纯装饰性功能）
     *  2 = 再关闭「歌词悬浮窗」
     *  3 = 再关闭「媒体接口（MediaSession / 通知使用权）」
     *  4 = 再关闭「车控读取」，只留纯 UI
     *
     * 逻辑：只要上一次会话崩溃了且没活过 15 秒，等级就 +1；活过 15 秒则清零。
     * 这样用户什么都不用做，App 会自己一步步退到「能跑」的配置，
     * 而每一次崩溃都会把当时的等级随日志上传 —— 据此即可定位是哪一层导致的。
     */
    fun safeLevel(ctx: Context) = sp(ctx).getInt("safe_level", 0)
    fun setSafeLevel(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("safe_level", v.coerceIn(0, 4)).apply()

    /** 当前 App 版本号（versionName），用于「版本变更 → 重置降级阶梯」判定 */
    fun appVersion(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        info.versionName ?: ""
    }.getOrElse { "" }

    /** 上次运行的版本号。版本变更时重置降级阶梯，避免旧崩溃记录把新包直接压到最低档 */
    fun lastVersion(ctx: Context) = sp(ctx).getString("last_version", "") ?: ""
    fun setLastVersion(ctx: Context, v: String) =
        sp(ctx).edit().putString("last_version", v).apply()

    /**
     * 车控「写入」总开关。默认开；一旦检测到连续崩溃就自动关（见 App.onCreate）。
     *
     * ★ 为什么单独设一个开关：setFunctionValue 是厂商私有 HAL 通道，本项目靠反射调用，
     *   传错 value 时厂商 native 库可能直接 abort —— **native 崩溃 Java 层兜不住**
     *   （`runCatching` / `UncaughtExceptionHandler` 都拦不住 SIGSEGV/SIGABRT）。
     *   所以崩过一次就必须立刻停止写入，只保留读取，保证 App 还能正常用。
     */
    fun vehicleWriteEnabled(ctx: Context) = sp(ctx).getBoolean("vehicle_write_enabled", true)
    fun setVehicleWriteEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("vehicle_write_enabled", v).apply()

    // ---------------- Dock 条目（图标自定义增删） ----------------
    /** Dock 条目的 JSON 数组；空串 = 用默认布局 */
    fun entriesJson(ctx: Context) = sp(ctx).getString("dock_entries_json", "") ?: ""
    fun setEntriesJson(ctx: Context, v: String) = sp(ctx).edit().putString("dock_entries_json", v).apply()

    /** 车机设置右侧应用面板的快捷应用 JSON 数组 */
    fun appDrawerAppsJson(ctx: Context) = sp(ctx).getString("app_drawer_apps_json", "") ?: ""
    fun setAppDrawerAppsJson(ctx: Context, v: String) =
        sp(ctx).edit().putString("app_drawer_apps_json", v).apply()

    /**
     * ★ v1.4.97 #12：座椅记忆「当前在第几号位」的**本地记录**（1/2/3，0 = 还没选过）。
     *
     * 为什么需要：车机那两个通道（SEAT_POSITION_SAVE 0x2d400100 / SEAT_NUM 0x2d410f00）
     * 在这台车上**全部读回 null**（日志实锤：4 个 zone 逐个试都读不到），
     * 所以「座椅弹窗打开时哪个位置高亮」永远判不出来。
     * 现在：我们自己调用过哪个记忆位就记下来，下次打开高亮它；
     * 万一哪天车机通道能读到了，以车机读到的为准（见 [VehicleController.readSeatMemoryIndex]）。
     */
    fun seatMemoryIndex(ctx: Context) = sp(ctx).getInt("seat_memory_index", 0)
    fun setSeatMemoryIndex(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("seat_memory_index", v).apply()

    // ---------------- 显示/隐藏 状态机 & 动画 ----------------
    /**
     * 淡入淡出时长（ms）。对齐吉利 oneOS Dock 的 350ms。
     */
    fun fadeDurationMs(ctx: Context) = sp(ctx).getLong("fade_duration_ms", 350L)
    fun setFadeDurationMs(ctx: Context, v: Long) = sp(ctx).edit().putLong("fade_duration_ms", v).apply()

    // ---------------- 底部上滑临时显示 Dock（v1.4.73） ----------------
    /**
     * Dock 被「打开时隐藏名单」里的应用藏起来后，从屏幕底部往上滑 → 临时显示 Dock
     * 若干毫秒 → 自动再藏起来。
     *
     * 实现方式：屏幕最底部常驻一条透明「手势条」窗口（只在 Dock 隐藏时挂载）接这个上滑。
     */
    fun swipeShowDockEnabled(ctx: Context) = sp(ctx).getBoolean("swipe_show_dock", true)
    fun setSwipeShowDockEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("swipe_show_dock", v).apply()

    /** 临时显示时长（ms），默认 5000 */
    fun swipeShowDockMs(ctx: Context) = sp(ctx).getLong("swipe_show_dock_ms", 5000L)
    fun setSwipeShowDockMs(ctx: Context, v: Long) =
        sp(ctx).edit().putLong("swipe_show_dock_ms", v).apply()

    /** 手势条高度（px），默认 64 —— 会挡住该区域的点击，越小越好 */
    fun swipeShowDockStripPx(ctx: Context) = sp(ctx).getInt("swipe_show_dock_strip_px", 64)
    fun setSwipeShowDockStripPx(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("swipe_show_dock_strip_px", v).apply()

    // ---------------- 打开时隐藏 Dock 的应用名单 ----------------
    /** 兼容入口；默认名单和缓存的包名集合统一由 DockBlacklistDefaults 管理。 */
    val DEFAULT_DOCK_HIDE: List<Pair<String, String>>
        get() = DockBlacklistDefaults.apps

    /** v4 之后 Dock 只使用这一份黑名单：命中则隐藏，不命中则显示。 */
    fun dockHideApps(ctx: Context): Set<String> =
        DockBlacklistPreferences.read(sp(ctx))

    fun setDockHideApps(ctx: Context, pkgs: Set<String>) =
        DockBlacklistPreferences.write(sp(ctx), pkgs)

    /** 该包名在前台时是否要**隐藏** Dock；v4 之后只看单一黑名单。 */
    fun isDockHideApp(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return DockBlacklistRules.shouldHide(pkg, dockHideApps(ctx))
    }

    /** 名单里某个包名的显示名（不在默认名单里的自定义应用就用包名本身） */
    fun dockHideLabel(pkg: String): String =
        DEFAULT_DOCK_HIDE.firstOrNull { it.first == pkg }?.second ?: pkg

    /** 显示时轻微上移的位移（px），吉利 animSlideAlpha 是 alpha + translationY 组合 */
    fun slideOffsetPx(ctx: Context) = sp(ctx).getInt("slide_offset_px", 24)
    fun setSlideOffsetPx(ctx: Context, v: Int) = sp(ctx).edit().putInt("slide_offset_px", v).apply()

    // ---------------- 空调状态弹窗（自动消失） ----------------
    /** 调空调时弹出状态浮窗，对齐吉利 BasePopWindow */
    fun popupEnabled(ctx: Context) = sp(ctx).getBoolean("popup_enabled", true)
    fun setPopupEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("popup_enabled", v).apply()

    /** 弹窗自动消失时间（ms）。吉利 BasePopWindow.autoDisMissTime = 3000 */
    fun popupAutoDismissMs(ctx: Context) = sp(ctx).getLong("popup_auto_dismiss_ms", 3000L)
    fun setPopupAutoDismissMs(ctx: Context, v: Long) = sp(ctx).edit().putLong("popup_auto_dismiss_ms", v).apply()

    // ---------------- 日夜模式背景（对齐吉利 setDockBg 日夜切换） ----------------
    /** true = 跟随系统深浅色自动切换底色 */
    fun bgFollowNight(ctx: Context) = sp(ctx).getBoolean("bg_follow_night", true)
    fun setBgFollowNight(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bg_follow_night", v).apply()

    /** 白天底色 RGB（跟随日夜时使用；关闭跟随则用 bgColor） */
    fun dayBgColor(ctx: Context) = sp(ctx).getInt("day_bg_color", 0x000000)
    fun setDayBgColor(ctx: Context, v: Int) = sp(ctx).edit().putInt("day_bg_color", v).apply()

    /** 白天底色不透明度 0..255 */
    fun dayBgAlpha(ctx: Context) = sp(ctx).getInt("day_bg_alpha", 0x59)
    fun setDayBgAlpha(ctx: Context, v: Int) = sp(ctx).edit().putInt("day_bg_alpha", v).apply()

    /** 夜间底色 RGB */
    fun nightBgColor(ctx: Context) = sp(ctx).getInt("night_bg_color", 0x000000)
    fun setNightBgColor(ctx: Context, v: Int) = sp(ctx).edit().putInt("night_bg_color", v).apply()

    /** 夜间底色不透明度 0..255 */
    fun nightBgAlpha(ctx: Context) = sp(ctx).getInt("night_bg_alpha", 0x8C)
    fun setNightBgAlpha(ctx: Context, v: Int) = sp(ctx).edit().putInt("night_bg_alpha", v).apply()

    // ---------------- 底色（★ v3.13.0：统一为一套纯白/纯黑 + 透明度） ----------------
    //
    // v3.13.0 取消了「磨砂玻璃」和「纯色/渐变二选一」两套开关，
    // 现在只有**一套底色**：
    //   白天 = 纯白 0xFFFFFF，夜间 = 纯黑 0x000000
    //   不透明度由「底色透明度」滑杆控制（0% = 完全不透明，100% = 完全透明）
    //   渲染方式是**整条高度连续爬升的透明→浓渐变**（顶部最透、底部最浓），
    //   细节见 util/GradientSpec.kt。
    //
    // 旧 key（bg_glass_* / bg_gradient_enabled / day_gradient_* 等）保留在
    // SharedPreferences 里不再读取，避免升级时数据残留造成困扰。

    /**
     * 白天底色透明度 0..100（0 = 完全不透明，100 = 完全透明）。
     * 默认 30 → 底部白色 alpha ≈ 178（0xB2）。
     */
    fun dayBgTransparency(ctx: Context) = sp(ctx).getInt("bg_transparency_day", 30)
    fun setDayBgTransparency(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("bg_transparency_day", v.coerceIn(0, 100)).apply()

    /**
     * 夜间底色透明度 0..100（0 = 完全不透明，100 = 完全透明）。
     * 默认 15 → 底部黑色 alpha ≈ 217（0xD9）。
     */
    fun nightBgTransparency(ctx: Context) = sp(ctx).getInt("bg_transparency_night", 15)
    fun setNightBgTransparency(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("bg_transparency_night", v.coerceIn(0, 100)).apply()

    /**
     * 当前生效的底色 RGB（纯白 / 纯黑，跟随日夜）。
     */
    fun bgSolidRgb(ctx: Context): Int {
        val night = bgFollowNight(ctx) && isNightMode(ctx)
        return if (night) 0x000000 else 0xFFFFFF
    }

    /**
     * 当前生效的底色渐变色标：[起点(全透), 中点, 终点(最浓)]。
     *
     * 起点 alpha = 0（顶部最透），终点 alpha 由「底色透明度」反算：
     *   alpha = (100 - 透明度%) * 255 / 100
     */
    fun currentGradientColors(ctx: Context): IntArray {
        val night = bgFollowNight(ctx) && isNightMode(ctx)
        val rgb = bgSolidRgb(ctx)
        val transparency = if (night) nightBgTransparency(ctx) else dayBgTransparency(ctx)
        val maxA = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        return intArrayOf(
            rgb,
            ((maxA / 2) shl 24) or rgb,
            (maxA shl 24) or rgb
        )
    }

    /**
     * ★ v3.13.1：纯色模式开关。
     *
     * false = 默认，渐变模式（整条高度连续爬升，顶部最透 → 底部最浓）
     * true  = **纯色模式**，整条 Dock 一个颜色：
     *         白天纯白 0xFFFFFF / 夜间纯黑 0x000000（跟随系统日夜），
     *         不透明度同样由「底色透明度」滑杆控制。
     */
    fun bgSolidMode(ctx: Context) = sp(ctx).getBoolean("bg_solid_mode", false)
    fun setBgSolidMode(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("bg_solid_mode", v).apply()

    /**
     * ★ v3.13.16：**背景形态（四选一互斥）**。
     *
     * 迁移：老版本只有「纯色模式」开关（bg_solid_mode），
     *   开 = SOLID，关 = GRADIENT；首次读到本项时按老开关推导。
     */
    fun bgMode(ctx: Context): com.car.dockbar.ui.BgMode {
        val saved = sp(ctx).getString("bg_mode", null)
        if (!saved.isNullOrEmpty()) return com.car.dockbar.ui.BgMode.of(saved)
        return if (sp(ctx).getBoolean("bg_solid_mode", false)) {
            com.car.dockbar.ui.BgMode.SOLID
        } else {
            com.car.dockbar.ui.BgMode.GRADIENT
        }
    }

    fun setBgMode(ctx: Context, m: com.car.dockbar.ui.BgMode) {
        sp(ctx).edit().putString("bg_mode", m.key).apply()
    }

    /** 模糊半径，0 = 只半透明不模糊，24 = 最糊。只在背景形态为「模糊」时使用。 */
    fun blurRadius(ctx: Context) = sp(ctx).getInt("blur_radius", 12).coerceIn(0, 24)
    fun setBlurRadius(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("blur_radius", v.coerceIn(0, 24)).apply()

    /** 透明叠层试验：灰度 0 = 黑，100 = 白；透明度 100 = 完全透明。 */
    fun overlayGrayLevel(ctx: Context) = sp(ctx).getInt("overlay_gray_level", 50).coerceIn(0, 100)
    fun setOverlayGrayLevel(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("overlay_gray_level", v.coerceIn(0, 100)).apply()
    fun overlayTransparency(ctx: Context) = sp(ctx).getInt("overlay_transparency", 50).coerceIn(0, 100)
    fun setOverlayTransparency(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("overlay_transparency", v.coerceIn(0, 100)).apply()

    /**
     * 纯色模式下的底色 ARGB（整条一个颜色）。
     */
    fun solidBgColor(ctx: Context): Int {
        val night = bgFollowNight(ctx) && isNightMode(ctx)
        val rgb = bgSolidRgb(ctx)
        val transparency = if (night) nightBgTransparency(ctx) else dayBgTransparency(ctx)
        val a = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        return (a shl 24) or rgb
    }

    /** 当前纯色底色（未启用渐变时用）：跟随日夜 or 固定 bgColor */
    fun currentBgColor(ctx: Context): Int {
        val night = bgFollowNight(ctx) && isNightMode(ctx)
        val rgb = if (night) nightBgColor(ctx) else dayBgColor(ctx)
        val a = if (night) nightBgAlpha(ctx) else dayBgAlpha(ctx)
        return (a shl 24) or (rgb and 0xFFFFFF)
    }

    /** 系统是否处于深色（夜间）模式 */
    fun isNightMode(ctx: Context): Boolean {
        val ref = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return ref == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // =====================================================================
    // 歌词悬浮窗（融合自 LyricFloat「车机歌词悬浮窗」）
    // =====================================================================

    /** 歌词悬浮窗总开关（与 Dock 相互独立） */
    fun lyricEnabled(ctx: Context) = sp(ctx).getBoolean("lyric_enabled", false)
    fun setLyricEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("lyric_enabled", v).apply()

    /** 背景不透明度 0(全透明) ~ 100(全黑) */
    fun lyricAlpha(ctx: Context) = sp(ctx).getInt("lyric_alpha", 40)
    fun setLyricAlpha(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_alpha", v).apply()

    /** 歌词字号 sp */
    fun lyricTextSize(ctx: Context) = sp(ctx).getInt("lyric_text_size", 22)
    fun setLyricTextSize(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_text_size", v).apply()

    /** 普通行颜色 ARGB */
    fun lyricTextColor(ctx: Context) = sp(ctx).getInt("lyric_text_color", 0xFFE6E6E6.toInt())
    fun setLyricTextColor(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_text_color", v).apply()

    /** 当前行（高亮）颜色 ARGB */
    fun lyricHlColor(ctx: Context) = sp(ctx).getInt("lyric_hl_color", 0xFFFFD54F.toInt())
    fun setLyricHlColor(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_hl_color", v).apply()

    /** 显示行数 1~10，默认 10 */
    fun lyricLineCount(ctx: Context): Int {
        val v = sp(ctx).getInt("lyric_line_count", 10)
        return v.coerceIn(1, 10)
    }

    fun setLyricLineCount(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_line_count", v.coerceIn(1, 10)).apply()

    /** 歌词时间轴偏移 ms（±3000，200 步进） */
    fun lyricOffsetMs(ctx: Context) = sp(ctx).getInt("lyric_offset_ms", 0)
    fun setLyricOffsetMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_offset_ms", v.coerceIn(-3000, 3000)).apply()

    /** 锁定 = 整窗点击穿透（FLAG_NOT_TOUCHABLE），点窗口等于点它下面的内容 */
    fun lyricLocked(ctx: Context) = sp(ctx).getBoolean("lyric_locked", false)
    fun setLyricLocked(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("lyric_locked", v).apply()

    /** 窗口宽度 dp 160~1200 */
    fun lyricWinW(ctx: Context): Int =
        sp(ctx).getInt("lyric_win_w", 340).coerceIn(160, 1200)

    fun setLyricWinW(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_win_w", v.coerceIn(160, 1200)).apply()

    /** 窗口高度 dp，0 = 自适应内容，80~800 */
    fun lyricWinH(ctx: Context): Int {
        val v = sp(ctx).getInt("lyric_win_h", 560)
        if (v == 0) return 0
        return v.coerceIn(80, 800)
    }

    fun setLyricWinH(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("lyric_win_h", if (v == 0) 0 else v.coerceIn(80, 800)).apply()

    /** 窗口位置（px，由拖动保存） */
    fun lyricX(ctx: Context) = sp(ctx).getInt("lyric_pos_x", 40)
    fun setLyricX(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_pos_x", v).apply()

    fun lyricY(ctx: Context) = sp(ctx).getInt("lyric_pos_y", 120)
    fun setLyricY(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_pos_y", v).apply()

    /** 追加的广播 action（换行分隔），空 = 只用默认列表 */
    fun lyricActions(ctx: Context) = sp(ctx).getString("lyric_actions", "") ?: ""
    fun setLyricActions(ctx: Context, v: String) =
        sp(ctx).edit().putString("lyric_actions", v).apply()

    /** 元数据字段映射：0=标准(TITLE=歌名,ARTIST=歌手) 1=车机错位(ARTIST=歌名,ALBUM=歌手) */
    fun lyricMapMode(ctx: Context) = sp(ctx).getInt("lyric_map_mode", 1)
    fun setLyricMapMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_map_mode", v).apply()

    /** 是否允许联网找歌词 */
    fun lyricOnline(ctx: Context) = sp(ctx).getBoolean("lyric_online", true)
    fun setLyricOnline(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("lyric_online", v).apply()

    /** 歌词源：0=自动 1=网易云 2=LRCLIB 3=自定义 */
    fun lyricSource(ctx: Context) = sp(ctx).getInt("lyric_source", 0)
    fun setLyricSource(ctx: Context, v: Int) = sp(ctx).edit().putInt("lyric_source", v).apply()

    /** 自定义歌词接口模板，需含 {title}/{artist} */
    fun lyricUrl(ctx: Context) = sp(ctx).getString("lyric_url", "") ?: ""
    fun setLyricUrl(ctx: Context, v: String) = sp(ctx).edit().putString("lyric_url", v).apply()

    /** 开机自启歌词悬浮窗 */
    fun lyricBoot(ctx: Context) = sp(ctx).getBoolean("lyric_boot", false)
    fun setLyricBoot(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("lyric_boot", v).apply()

    /** 歌词悬浮窗预设色板（普通行 / 高亮行各一套） */
    val LYRIC_COLOR_PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), // 白
        0xFFFFD54F.toInt(), // 金黄
        0xFF4DD0E1.toInt(), // 青
        0xFF81C784.toInt(), // 绿
        0xFFF48FB1.toInt(), // 粉
        0xFFFFA726.toInt()  // 橙
    )
}
