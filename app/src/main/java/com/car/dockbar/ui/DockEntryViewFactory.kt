package com.car.dockbar.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.car.dockbar.R
import com.car.dockbar.hvac.HvacApp
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.model.Builtin
import com.car.dockbar.model.DockEntry
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController

/**
 * 生成出来的 Dock 条目 View 附带"数据绑定类型"，
 * 服务据此在车辆信号/媒体信息变化时刷新对应控件。
 */
enum class BindKind { NONE, TEMP_DRIVER, TEMP_PASSENGER, FAN, MUSIC, LYRIC, RECIRC, VENT, SEAT_HEAT, AIR, DEFROST_FRONT, DEFROST_REAR, STEERING_HEAT }

data class BuiltEntry(
    val view: View,
    val span: Int,
    val separator: Boolean,
    val bind: BindKind,
    /** 组合控件（− 值 +）里中间的数值 View；服务按 BindKind 订阅刷新时用 */
    val valueView: DockValueView? = null,
    /**
     * ★ v3.13.8：**固定宽度（px）** —— >0 时该条目锁死宽度、不随音乐卡伸缩。
     * 1 槽项 98、3 槽温度组合 290、D08 风速 250（由 create() 统一按 span 换算）。
     */
    val fixedWidthPx: Int = 0,
    /**
     * ★ v3.13.8：**弹性区间（px）** —— 音乐卡专用：宽度 = 总宽 - 全部固定项，
     * 钳到 [flexMinPx, flexMaxPx] = 295~500px。
     */
    val flexMinPx: Int = 0,
    val flexMaxPx: Int = 0
)

/**
 * 按 [DockEntry] 生成对应的 Dock View。
 *
 * 内置控件（kind=builtin）走与原来硬编码版本完全一致的交互逻辑；
 * 用户自定义 App（kind=app）用 `setDynamic(应用原图标)` 保留图标原色，不加滤镜。
 */
class DockEntryViewFactory(
    private val ctx: Context,
    private val onInteraction: () -> Unit,
    private val onShowHvac: () -> Unit,
    private val onToggleHvac: () -> Unit,
    private val onShowAirDirection: (View) -> Unit,
    private val onToggleDualSync: () -> Unit,
    private val onShowAppDrawer: (View) -> Unit,
    /** ★ v1.4.97 #10：长按 D05（快捷应用）→ 打开设置里的「选择快捷应用」界面 */
    private val onOpenAppDrawerSettings: () -> Unit,
    /** ★ 温度组合按钮中间值单击 → 温度弹窗（isDriver 区分主/副驾；不再进系统空调页面） */
    private val onShowAirQuality: (View, Boolean) -> Unit,
    /** ★ 长按主驾温度 → 切 AC 并弹 AC 状态弹窗 */
    private val onToggleAcLong: () -> Unit,
    /** ★ 座椅按钮单击 → 座椅弹窗（通风/加热档位） */
    private val onShowSeatPopup: (View) -> Unit,
    iconScale: Float = Prefs.iconScale(ctx),
    private val textScale: Float = Prefs.textScale(ctx)
) {
    /**
     * ★ v3.1：Dock 栏图标尺寸**普通/大图标统一 65px**。
     * （原来是 26px 普通 + 52px 大图标；想调回把 65 改成 26 即可。）
     */
    private val iconPx = (65 * iconScale).toInt().coerceAtLeast(12)
    private val bigPx = iconPx
    private val barHeightPx = Prefs.dockHeightPx(ctx)

    companion object {
        /**
         * ★ v3.13.7：**D08 风速组合按钮「− 值 +」整体宽度**（用户指定）。
         * v3.13.7 = 250px；★ v3.13.9 = **220px 恒定**。
         */
        const val FAN_COMBO_FIXED_WIDTH_PX = 220

        /**
         * ★ v3.13.9：**D08 的 ± 箭头图标 = 40px**（用户指定）。
         * 注意：只作用于 D08 风速组合；温度组合的 ± 维持 iconPx（65×图标缩放）。
         */
        const val FAN_COMBO_ARROW_PX = 40

        // ── ★ v3.13.8 固定宽度规格（用户逐项指定，其它项不随音乐卡伸缩）──
        /** 1 槽条目（HOME/歌词/设置/快捷应用/风向/通风/循环/座椅…）固定宽度 */
        const val SLOT_FIXED_PX = 98
        /** 3 槽温度组合按钮（主驾/副驾）固定宽度 */
        const val SPAN3_FIXED_PX = 290
        /** 音乐卡弹性区间下限 */
        const val MUSIC_MIN_WIDTH_PX = 295
        /** 音乐卡弹性区间上限 */
        const val MUSIC_MAX_WIDTH_PX = 500

        /** span → 固定宽度换算（音乐卡不走这里，走弹性区间） */
        fun fixedWidthFor(span: Int): Int = when (span) {
            1 -> SLOT_FIXED_PX
            3 -> SPAN3_FIXED_PX
            else -> (span * SLOT_FIXED_PX).coerceAtLeast(SLOT_FIXED_PX)
        }
    }

    fun create(entry: DockEntry): BuiltEntry {
        val built = if (entry.kind == "app") createApp(entry)
        else createBuiltin(Builtin.of(entry.action) ?: Builtin.HOME, entry.span)
        // ★ v3.13.8：统一宽度规格 ——
        //  音乐卡走弹性区间（295~500）；D08 已自带 250 固定值原样保留；
        //  其余条目按 span 换算固定像素（1 槽 98 / 3 槽 290）。
        return when {
            built.bind == BindKind.MUSIC -> built
            built.fixedWidthPx > 0 -> built
            else -> built.copy(fixedWidthPx = fixedWidthFor(built.span))
        }
    }

    // ------------------------------------------------------------------
    // 自定义 App
    // ------------------------------------------------------------------

    private fun createApp(entry: DockEntry): BuiltEntry {
        val v = DockIconView(ctx, 0, iconPx)
        // 图标：保留 App 原图标；取不到就退回默认占位
        runCatching {
            v.setDynamic(ctx.packageManager.getApplicationIcon(entry.action))
        }.onFailure {
            runCatching { v.setIcon(R.drawable.ic_car) }
        }
        v.setOnClickListener {
            onInteraction()
            val target = IntentLauncher.Target(pkg = entry.action, cls = entry.cls)
            IntentLauncher.launch(ctx, entry.label, target, emptyList())
        }
        return BuiltEntry(v, entry.span.coerceIn(1, 6), false, BindKind.NONE)
    }

    // ------------------------------------------------------------------
    // 内置控件
    // ------------------------------------------------------------------

    private fun createBuiltin(b: Builtin, entrySpan: Int): BuiltEntry {
        val sep = b.separatorBefore
        // 条目 span：JSON 里显式 >1 就按它（用户可把音乐卡改宽），否则用该控件的默认 span。
        // 旧实现完全忽略 entry.span，导致配置里改宽度无效。
        val sp = if (entrySpan > 1) entrySpan.coerceIn(1, 6) else b.span.coerceIn(1, 6)
        return when (b) {
            // ★ v1.4.96：单击 = 返回桌面；**双击 = 打开最近任务（后台应用列表）**
            // ★★ v4.0：D01 改回**圆形图标**（ic_home_circle）——
            //   1.5.12 换系统图标时 ic_home 被换成了「房子」，
            //   用户要求改回原来那个空心圆环（v1.5.12 之前的样式）。
            //   描边图标照样吃 DockIconView 的 ColorFilter → 白天/夜间自动反色。
            // ★ v3.12.0：用户反馈 D01 圆环图标太大 → 缩至 **0.8 倍**
            //   （只缩 D01 这一个图标，其他内置图标尺寸不动）。
            Builtin.HOME -> BuiltEntry(
                icon(R.drawable.ic_home_circle, (iconPx * 0.8f).toInt()) { onHomeClick() },
                sp, sep, BindKind.NONE
            )

            Builtin.SETTINGS -> BuiltEntry(
                icon(R.drawable.ic_car, iconPx, onLong = {
                    Logx.d("D04 长按 → 打开 Dock 设置页")
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }) {
                    IntentLauncher.launch(
                        ctx, "车机设置",
                        IntentLauncher.settingsTarget(ctx),
                        IntentLauncher.SETTINGS_CANDIDATES
                    )
                },
                sp, sep, BindKind.NONE
            )

            // ★ v1.4.20：快捷应用独立成单独按钮，紧挨「车机设置」（不再共用槽位）。
            Builtin.APP_DRAWER -> BuiltEntry(
                icon(R.drawable.ic_apps, iconPx, onLong = { onOpenAppDrawerSettings() }) { v -> onShowAppDrawer(v) },
                sp, sep, BindKind.NONE
            )

            // ★ v1.4.24：主驾温度 / 副驾温度 / 风速的「− 值 +」融合成单个组合按钮（占 3 槽）
            // ★ v1.4.34：温度组合按钮中间值单击 → 空气质量弹窗（不再进系统空调页面）
            Builtin.TEMP_DRIVER_COMBO -> comboValue(
                sp, sep,
                onMinus = { VehicleController.tempStep(ctx, true, -1) },
                onPlus = { VehicleController.tempStep(ctx, true, 1) },
                onClickValue = { v -> onShowAirQuality(v, true) },
                // ★ 长按主驾温度 = 切 AC 并弹 AC 状态弹窗
                onLongValue = { onToggleAcLong() },
                bind = BindKind.TEMP_DRIVER
            )

            Builtin.TEMP_PASSENGER_COMBO -> comboValue(
                sp, sep,
                onMinus = { VehicleController.tempStep(ctx, false, -1) },
                onPlus = { VehicleController.tempStep(ctx, false, 1) },
                onClickValue = { v -> onShowAirQuality(v, false) },
                onLongValue = { onToggleDualSync() },
                bind = BindKind.TEMP_PASSENGER
            )

            // ★ v3.13.5：D08 风速组合按钮**整体宽度锁死 200px**（用户要求）。
            //   原来占 3 槽 = 288px（1920/20 槽 × 3），偏宽；
            //   纯改 span 算不出精确 200px（1920/20×x = 200 → x=2.08 非整数），
            //   所以走 DockLayout 的 fixedWidthPx 通道：锁 200px，
            //   腾出的 88px 由其余 11 个条目按 span 比例补齐，Dock 总宽仍 1920。
            //   内部「− 值 +」三块仍按 1:1:1 权重平分这 200px。
            Builtin.FAN_COMBO -> comboValue(
                sp, sep,
                onMinus = { VehicleController.fanStep(ctx, -1) },
                onPlus = { VehicleController.fanStep(ctx, 1) },
                // ★ 单击 = 切换 AUTO 风速（用户要求，不进空调面板）；长按 = 关风速
                onClickValue = { VehicleController.toggleFanAuto(ctx) },
                onLongValue = { VehicleController.setFanOff(ctx) },
                bind = BindKind.FAN,
                fixedWidthPx = FAN_COMBO_FIXED_WIDTH_PX,
                arrowPx = FAN_COMBO_ARROW_PX
            )

            // ★ v1.4.34：座椅按钮单击 → 座椅弹窗（通风/加热档位，不再进座椅设置 App）
            // ★ v1.5.14：用**纯座椅**图标（ic_open_seat），不再跟「座椅加热」共用带热浪的那张
            Builtin.SEAT -> BuiltEntry(
                icon(R.drawable.ic_seat_open, iconPx) { v -> onShowSeatPopup(v) },
                sp, sep, BindKind.NONE
            )

            Builtin.TEMP_DOWN_DRIVER -> BuiltEntry(
                icon(R.drawable.ic_chevron_left, iconPx) {
                    VehicleController.tempStep(ctx, true, -1)
                },
                sp, sep, BindKind.NONE
            )

            Builtin.TEMP_UP_DRIVER -> BuiltEntry(
                icon(R.drawable.ic_chevron_right, iconPx) {
                    VehicleController.tempStep(ctx, true, 1)
                },
                sp, sep, BindKind.NONE
            )

            Builtin.TEMP_DRIVER -> BuiltEntry(
                value(
                    onClick = { onToggleHvac() },
                    onLong = { VehicleController.toggleAc(ctx) }
                ),
                sp, sep, BindKind.TEMP_DRIVER
            )

            Builtin.TEMP_DOWN_PASSENGER -> BuiltEntry(
                icon(R.drawable.ic_chevron_left, iconPx) {
                    VehicleController.tempStep(ctx, false, -1)
                },
                sp, sep, BindKind.NONE
            )

            Builtin.TEMP_UP_PASSENGER -> BuiltEntry(
                icon(R.drawable.ic_chevron_right, iconPx) {
                    VehicleController.tempStep(ctx, false, 1)
                },
                sp, sep, BindKind.NONE
            )

            Builtin.TEMP_PASSENGER -> BuiltEntry(
                value(
                    onClick = { onToggleHvac() },
                    onLong = { onToggleDualSync() }
                ),
                sp, sep, BindKind.TEMP_PASSENGER
            )

            Builtin.AIR -> {
                val v = icon(R.drawable.ic_airflow, bigPx) { view -> onShowAirDirection(view) }
                v.setOnLongClickListener {
                    openAirDirection()
                    true
                }
                BuiltEntry(v, sp, sep, BindKind.AIR)
            }

            Builtin.VENT -> {
                val v = icon(R.drawable.ic_seat_vent, bigPx, preserveColors = true) {
                    val cur = VehicleController.state.seatVent
                    VehicleController.setSeatVentLevel(ctx, if (cur <= 0) 3 else cur - 1)
                }
                v.setOnLongClickListener {
                    val cur = VehicleController.state.seatVent
                    VehicleController.setSeatVentLevel(ctx, if (cur >= 3) 0 else cur + 1)
                    true
                }
                BuiltEntry(v, sp, sep, BindKind.VENT)
            }

            // ★ 新增：主驾座椅加热（functionId 0x10050200，zone=1）。
            // 旧版本完全没有加热入口，只能靠调试通道表手写。
            Builtin.SEAT_HEAT -> {
                // ★ v3.1：图标按**档位**切换（ic_seat_hot_off / _01 / _02 / _03），
                //   保留原色显示（白座椅 + 橙色热浪），激活态用透明度表现。
                val v = icon(R.drawable.ic_seat_hot_03, bigPx, preserveColors = true) {
                    val cur = VehicleController.state.seatHeat
                    VehicleController.setSeatHeatLevel(ctx, if (cur <= 0) 3 else cur - 1)
                }
                v.setOnLongClickListener {
                    val cur = VehicleController.state.seatHeat
                    VehicleController.setSeatHeatLevel(ctx, if (cur >= 3) 0 else cur + 1)
                    true
                }
                BuiltEntry(v, sp, sep, BindKind.SEAT_HEAT)
            }

            Builtin.FAN_DOWN -> BuiltEntry(
                icon(R.drawable.ic_chevron_left, iconPx) { VehicleController.fanStep(ctx, -1) },
                sp, sep, BindKind.NONE
            )

            Builtin.FAN_UP -> BuiltEntry(
                icon(R.drawable.ic_chevron_right, iconPx) { VehicleController.fanStep(ctx, 1) },
                sp, sep, BindKind.NONE
            )

            Builtin.FAN -> BuiltEntry(
                value(
                    onClick = { onShowHvac() },
                    onLong = { VehicleController.setFanOff(ctx) }
                ),
                sp, sep, BindKind.FAN
            )

            Builtin.RECIRC -> BuiltEntry(
                icon(R.drawable.ic_recirc, bigPx) { VehicleController.toggleRecirc(ctx) },
                sp, sep, BindKind.RECIRC
            )

            // ── [D23] 前挡加热 ─────────────────────────
            // ★ v1.5.11 #13：默认不在 Dock（DockEntries.defaults() 里没有它），
            //   用户在「Dock 图标设置 → 添加内置控件」手动加进来才会有。
            Builtin.DEFROST_FRONT -> BuiltEntry(
                icon(R.drawable.ic_defrost, bigPx) { VehicleController.toggleDefrostFront(ctx) },
                sp, sep, BindKind.DEFROST_FRONT
            )

            // ── [D24] 后挡除霜 ─────────────────────────
            // ★ v3.1：同样默认不在 Dock，需在「Dock 图标设置 → 添加内置控件」手动添加。
            Builtin.DEFROST_REAR -> BuiltEntry(
                icon(R.drawable.ic_rear_defrost, bigPx) { VehicleController.toggleDefrostRear(ctx) },
                sp, sep, BindKind.DEFROST_REAR
            )

            // ── [D25] 方向盘加热 ───────────────────────
            // ★ v3.1：默认不在 Dock。档位 0=关 1/2/3，单击降档、长按升档；
            //   图标按档位切换（ic_sw_heat_off / _01 / _02 / _03）。
            Builtin.STEERING_HEAT -> {
                val v = icon(R.drawable.ic_sw_heat_03, bigPx, preserveColors = true) {
                    val cur = VehicleController.state.steeringHeat
                    VehicleController.setSteeringHeatLevel(ctx, if (cur <= 0) 3 else cur - 1)
                }
                v.setOnLongClickListener {
                    val cur = VehicleController.state.steeringHeat
                    VehicleController.setSteeringHeatLevel(ctx, if (cur >= 3) 0 else cur + 1)
                    true
                }
                BuiltEntry(v, sp, sep, BindKind.STEERING_HEAT)
            }

            Builtin.MUSIC -> {
                val m = DockMusicView(ctx, barHeightPx, textScale, Prefs.iconScale(ctx))
                m.setOnClickListener {
                    onInteraction()
                    val opened = MediaBridge.openPlayer(ctx)
                    if (!opened) {
                        IntentLauncher.launch(
                            ctx, "音乐",
                            IntentLauncher.musicTarget(ctx),
                            IntentLauncher.MUSIC_CANDIDATES
                        )
                    }
                }
                m.btnPlay.setOnClickListener {
                    onInteraction()
                    MediaBridge.playPause()
                }
                m.btnPlay.setOnLongClickListener {
                    onInteraction()
                    MediaBridge.next()
                    true
                }
                BuiltEntry(m, sp, sep, BindKind.MUSIC,
                    flexMinPx = MUSIC_MIN_WIDTH_PX, flexMaxPx = MUSIC_MAX_WIDTH_PX)
            }

            Builtin.LYRIC -> {
                val v = icon(R.drawable.ic_music_note, iconPx) {
                    val on = LyricWindows.toggle(ctx)
                    Logx.d(if (on) "Dock：歌词悬浮窗已开启" else "Dock：歌词悬浮窗已关闭")
                }
                v.setOnLongClickListener {
                    onInteraction()
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
                BuiltEntry(v, 1, sep, BindKind.LYRIC)
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun icon(
        res: Int,
        sizePx: Int,
        preserveColors: Boolean = false,
        /** ★ v1.4.97 #10：可选长按行为（D05 长按 = 打开快捷应用设置） */
        onLong: (() -> Unit)? = null,
        onClick: (View) -> Unit
    ): DockIconView {
        val v = DockIconView(ctx, res, sizePx, preserveColors)
        v.setOnClickListener {
            onInteraction()
            onClick(v)
        }
        if (onLong != null) {
            v.setOnLongClickListener {
                onInteraction()
                onLong()
                true
            }
        }
        return v
    }

    private fun value(onClick: () -> Unit, onLong: () -> Unit): DockValueView {
        val v = DockValueView(ctx, 25f * textScale, 16f * textScale, 12f * textScale)
        v.setOnClickListener {
            onInteraction()
            onClick()
        }
        v.setOnLongClickListener {
            onInteraction()
            onLong()
            true
        }
        return v
    }

    /**
     * ★ v1.4.24 组合按钮：「− 值 +」融合成一个大按钮（主驾温度 / 副驾温度 / 风速共用）。
     * 两侧 ± 直接调温/调风量；中间数值保留原值条目的点击 / 长按行为（开空调面板等）；
     * 中间的 DockValueView 通过 BuiltEntry.valueView 交给服务按 BindKind 实时刷新。
     */
    private fun comboValue(
        sp: Int,
        sep: Boolean,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        onClickValue: (View) -> Unit,
        onLongValue: () -> Unit,
        bind: BindKind,
        /** ★ v3.13.5：>0 = 整体锁死这个宽度（px），不参与 span 均分 */
        fixedWidthPx: Int = 0,
        /**
         * ★ v3.13.9：± 箭头图标尺寸（px）。默认 = iconPx（65×图标缩放）；
         * D08 风速组合传固定 40px（用户指定）。温度组合不传、维持 65px。
         */
        arrowPx: Int = iconPx
    ): BuiltEntry {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // ★ 用户要求：元素尺寸全部回到原来独立条目的样子（± 图标 iconPx、值 25sp），
        //   三个元素在 3 槽里各占 1/3 宽（= 原来 1 槽的宽度），只是合并在同一个按钮里
        val minus = DockIconView(ctx, R.drawable.ic_chevron_left, arrowPx)
        minus.setOnClickListener {
            onInteraction()
            onMinus()
        }
        val value = DockValueView(ctx, 25f * textScale, 16f * textScale, 12f * textScale)
        value.setOnClickListener {
            onInteraction()
            // 锚点 = 整个组合按钮，弹窗贴着它弹出
            onClickValue(box)
        }
        value.setOnLongClickListener {
            onInteraction()
            onLongValue()
            true
        }
        val plus = DockIconView(ctx, R.drawable.ic_chevron_right, arrowPx)
        plus.setOnClickListener {
            onInteraction()
            onPlus()
        }
        box.addView(minus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        box.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        box.addView(plus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return BuiltEntry(box, sp, sep, bind, valueView = value, fixedWidthPx = fixedWidthPx)
    }

    /** D01 双击判定窗口（ms） */
    private val HOME_DOUBLE_TAP_MS = 300L
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastHomeTapAt = 0L

    /** D01 单击的延迟执行体（等双击窗口过去再真回桌面） */
    private val homeSingleTap = Runnable {
        lastHomeTapAt = 0L
        Logx.d("D01 单击 → 返回桌面")
        IntentLauncher.goHome(ctx)
    }

    /**
     * ★ v1.4.96：D01 的单击 / 双击判定。
     *
     * · 单击     → 返回桌面（原行为）
     * · **双击** → 打开最近任务（后台应用列表），走车机自带的 `com.geely.recents`
     *
     * 为什么不用 KEYCODE_APP_SWITCH：那是按键注入，1.4.62 因黑屏事故已全部移除。
     * Android 也没有公开 API 能直接调起 recents 界面，所以启动车机自己的最近任务 App
     * （日志实证前台出现过 `com.geely.recents`），用户可在设置里改包名。
     */
    private fun onHomeClick() {
        val now = SystemClock.elapsedRealtime()
        if (lastHomeTapAt != 0L && now - lastHomeTapAt < HOME_DOUBLE_TAP_MS) {
            lastHomeTapAt = 0L
            uiHandler.removeCallbacks(homeSingleTap)
            Logx.d("D01 双击 → 打开最近任务（后台应用列表）")
            IntentLauncher.openRecents(ctx)
            return
        }
        lastHomeTapAt = now
        uiHandler.removeCallbacks(homeSingleTap)
        uiHandler.postDelayed(homeSingleTap, HOME_DOUBLE_TAP_MS)
    }

    private fun openAirDirection() {
        val t = IntentLauncher.airDirTarget(ctx)
        if (IntentLauncher.isConfigured(t)) {
            IntentLauncher.launch(ctx, "空调风向", t, IntentLauncher.HVAC_CANDIDATES)
        } else {
            HvacApp.openPanel(ctx, HvacApp.PAGE_MAIN)
        }
    }

    /** 占位：方便以后按图标 resId 反查名字 */
    private fun nameOf(res: Int): String =
        runCatching { ctx.resources.getResourceEntryName(res) }.getOrDefault(res.toString())
}
