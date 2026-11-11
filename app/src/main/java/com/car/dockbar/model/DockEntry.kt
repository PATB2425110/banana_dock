package com.car.dockbar.model

import android.content.Context
import com.car.dockbar.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dock 上的一个条目。
 *
 * 对齐吉利 oneOS Dock（com.geely.dockbar）的设计：
 * - 内置控件（温度/风速/风向/通风/循环/座椅/音乐/设置）用 kind="builtin"
 * - 用户自己添加的第三方 App 用 kind="app"（保留原图标，不加色）
 * 全部条目序列化成 JSON 存在 SharedPreferences，增删改都即时生效。
 */
data class DockEntry(
    val id: String,
    /** "builtin" = 内置控件，"app" = 用户添加的应用 */
    val kind: String,
    /** kind=builtin 时为 [Builtin.action]；kind=app 时为包名（兼容旧字段） */
    val action: String,
    val label: String,
    /** kind=app 的 Activity 全名，可为空（空则用 LAUNCHER intent 启动） */
    val cls: String = "",
    /** 占几个等分槽位（音乐卡片默认 3） */
    val span: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("action", action)
        put("label", label)
        put("cls", cls)
        put("span", span)
    }

    companion object {
        fun fromJson(o: JSONObject): DockEntry = DockEntry(
            id = o.optString("id"),
            kind = o.optString("kind", "builtin"),
            action = o.optString("action"),
            label = o.optString("label"),
            cls = o.optString("cls", ""),
            span = o.optInt("span", 1).coerceIn(1, 6)
        )
    }
}

/**
 * 内置控件清单。每个 action 对应一个 View，由 [com.car.dockbar.dock.DockCatalog] 生成。
 *
 * 顺序即默认 Dock 的默认排布，与原硬编码版本完全一致（音乐卡片占 3 槽）。
 */
// ┌──────────────────────────────────────────────────────────────────┐
// │ ★ 可见元素编号总览（纯注释，不影响任何运行逻辑）★                    │
// │  D01–D22 = Dock 栏上的可见控件（见下方 Builtin 枚举各项前的[Dnn]）   │
// │  S01–Snn = 设置主界面的可见条目（见 MainActivity 中各 addSwitchRow/   │
// │             addSeekRow/smallBtn 前的[Snn] 注释）                    │
// │  P01–Pnn = 弹窗内的可见条目（温度/座椅/风向/AQI/应用面板…）           │
// └──────────────────────────────────────────────────────────────────┘
enum class Builtin(
    val action: String,
    val label: String,
    val span: Int,
    val separatorBefore: Boolean,
    val hint: String = "",
    /** true = 不在「添加内置控件」列表里出现（已被组合控件取代的旧条目） */
    val hidden: Boolean = false
) {
    // ── [D01] 返回桌面 ─────────────────────────
    HOME("home", "返回桌面", 1, false, "单击返回桌面 · 双击打开最近任务（后台应用列表）"),
    // ── [D02] 音乐信息卡 ────────────────────────
    // ★ v3.13.8：宽度与 span 解耦 —— 弹性区间 295~500px（总宽减去全部固定项），span 仅元数据
    MUSIC("music", "音乐信息", 3, true, "封面 + 歌名 + 播放键，宽度自适应 295~500px"),
    // ── [D03] 歌词悬浮窗开关 ──────────────────────
    LYRIC("lyric", "歌词悬浮窗", 1, true, "单击开/关歌词悬浮窗，长按打开歌词设置"),
    // ── [D26] 音量 ─────────────────────────────
    VOLUME("volume", "音量", 1, false, "单击打开音量面板：媒体、导航"),
    // ── [D04] 车机设置 ─────────────────────────
    SETTINGS("settings", "车机设置", 1, true, "单击打开车机设置，长按打开 Dock 设置页"),
    // v1.4.20 起「快捷应用」独立成一个按钮，紧挨车机设置（不再和设置挤在一个槽位里）。
    // separatorBefore=false：两个按钮各自独立，但不加分隔线，视觉上仍是一组。
    // ── [D05] 快捷应用面板 ───────────────────────
    // ★ v1.4.97 #10/#11：长按 = 进设置选快捷应用；面板里长按图标可拖到 6 个槽之一
    APP_DRAWER(
        "app_drawer", "快捷应用", 1, false,
        "打开快捷应用面板（2×3 应用格）；长按 → 进设置选择这 6 个应用；面板内长按图标可拖动换位"
    ),
    // v1.4.24 起「− 值 +」三件套融合成单个组合按钮，占 3 槽（与原三条等宽，保持原有间隙）
    // ── [D06] 主驾温度 −值+ ─────────────────────
    TEMP_DRIVER_COMBO("temp_driver_combo", "主驾温度", 3, false, "组合按钮：− 主驾温度 +，点中间开空调面板"),
    // ── [D07] 副驾温度 −值+ ─────────────────────
    TEMP_PASSENGER_COMBO("temp_passenger_combo", "副驾温度", 3, true, "组合按钮：− 副驾温度 +，长按中间切双温同步"),
    // ── [D08] 风速 −值+ ───────────────────────
    FAN_COMBO("fan_combo", "风速", 3, false, "组合按钮：− 风速 +，点中间开空调面板"),
    // ── [D09] 主驾温度 −（旧独立） ──────────────────
    TEMP_DOWN_DRIVER("temp_down_driver", "主驾温度-", 1, false, "主驾降温 1 档", hidden = true),
    // ── [D10] 主驾温度值（旧独立） ───────────────────
    TEMP_DRIVER("temp_driver", "主驾温度", 1, false, "显示/调节主驾温度", hidden = true),
    // ── [D11] 主驾温度 +（旧独立） ──────────────────
    TEMP_UP_DRIVER("temp_up_driver", "主驾温度+", 1, false, "主驾升温 1 档", hidden = true),
    // ── [D12] 空调风向 ─────────────────────────
    AIR("air", "空调风向", 1, true, "单击循环切换风向，长按打开面板"),
    // ── [D13] 主驾座椅通风 ───────────────────────
    VENT("vent", "主驾座椅通风", 1, false, "单击降档，长按升档"),
    // ── [D14] 主驾座椅加热 ───────────────────────
    SEAT_HEAT("seat_heat", "主驾座椅加热", 1, false, "单击降档，长按升档"),
    // ── [D15] 风速 −（旧独立） ────────────────────
    FAN_DOWN("fan_down", "风速-", 1, false, "手动风速 -1 档", hidden = true),
    // ── [D16] 风速值（旧独立） ─────────────────────
    FAN("fan", "风速", 1, false, "显示/调节风速档位", hidden = true),
    // ── [D17] 风速 +（旧独立） ────────────────────
    FAN_UP("fan_up", "风速+", 1, false, "手动风速 +1 档", hidden = true),
    // ── [D18] 内外循环 ─────────────────────────
    RECIRC("recirc", "内外循环", 1, true, "切换内/外循环"),
    // ── [D19] 座椅弹窗 ─────────────────────────
    SEAT("seat", "座椅设置", 1, true, "打开座椅/空调面板"),
    // ── [D20] 副驾温度 −（旧独立） ──────────────────
    TEMP_DOWN_PASSENGER("temp_down_passenger", "副驾温度-", 1, true, "副驾降温 1 档", hidden = true),
    // ── [D21] 副驾温度值（旧独立） ───────────────────
    TEMP_PASSENGER("temp_passenger", "副驾温度", 1, false, "显示/调节副驾温度", hidden = true),
    // ── [D22] 副驾温度 +（旧独立） ──────────────────
    TEMP_UP_PASSENGER("temp_up_passenger", "副驾温度+", 1, false, "副驾升温 1 档", hidden = true),
    // ── [D23] 前挡加热 ───────────────────────────
    // ★ v1.5.11 #13：**默认不在 Dock 里**（不写进 DockEntries.defaults()），
    //   需要用户在「Dock 图标设置 → 添加内置控件」里手动添加才会出现。
    DEFROST_FRONT(
        "defrost_front", "前挡加热", 1, false,
        "前挡除雾/加热开关（0x10040100）；单击切换，开启时图标点亮。默认不在 Dock，需在设置里手动添加"
    ),
    // ── [D24] 后挡除霜 ───────────────────────────
    // ★ v3.1：同样**默认不在 Dock 里**（不写进 DockEntries.defaults()），需手动添加。
    DEFROST_REAR(
        "defrost_rear", "后挡除霜", 1, false,
        "后挡除雾/加热开关（0x10040300）；单击切换，开启时图标点亮。默认不在 Dock，需在设置里手动添加"
    ),
    // ── [D25] 方向盘加热 ─────────────────────────
    // ★ v3.1：同上，默认不在 Dock 里。档位 0=关 1/2/3，图标按档位切换。
    STEERING_HEAT(
        "steering_heat", "方向盘加热", 1, false,
        "方向盘加热档位（0x10090100，zone=1 主驾）；单击降档、长按升档，图标按档位切换。默认不在 Dock，需在设置里手动添加"
    );

    companion object {
        fun of(action: String): Builtin? = values().firstOrNull { it.action == action }
    }
}

/**
 * Dock 条目持久化（JSON 数组存 SharedPreferences）。
 */
object DockEntries {

    private fun defaults(): List<DockEntry> = listOf(
        DockEntry("b_home", "builtin", Builtin.HOME.action, Builtin.HOME.label, span = 1),
        DockEntry("b_music", "builtin", Builtin.MUSIC.action, Builtin.MUSIC.label, span = 3),
        DockEntry("b_settings", "builtin", Builtin.SETTINGS.action, Builtin.SETTINGS.label),
        DockEntry("b_app_drawer", "builtin", Builtin.APP_DRAWER.action, Builtin.APP_DRAWER.label),
        DockEntry("b_temp_driver_combo", "builtin", Builtin.TEMP_DRIVER_COMBO.action, Builtin.TEMP_DRIVER_COMBO.label, span = Builtin.TEMP_DRIVER_COMBO.span),
        DockEntry("b_air", "builtin", Builtin.AIR.action, Builtin.AIR.label),
        DockEntry("b_fan_combo", "builtin", Builtin.FAN_COMBO.action, Builtin.FAN_COMBO.label, span = Builtin.FAN_COMBO.span),
        DockEntry("b_recirc", "builtin", Builtin.RECIRC.action, Builtin.RECIRC.label),
        DockEntry("b_seat", "builtin", Builtin.SEAT.action, Builtin.SEAT.label),
        DockEntry("b_temp_passenger_combo", "builtin", Builtin.TEMP_PASSENGER_COMBO.action, Builtin.TEMP_PASSENGER_COMBO.label, span = Builtin.TEMP_PASSENGER_COMBO.span)
    )

    fun defaultsMutable(): MutableList<DockEntry> = defaults().toMutableList()

    fun load(ctx: Context): MutableList<DockEntry> {
        val raw = Prefs.entriesJson(ctx)
        if (raw.isBlank()) return defaultsMutable()
        val loaded = runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<DockEntry>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(DockEntry.fromJson(it)) }
            }
            out
        }.getOrNull() ?: return defaultsMutable()
        // v1.4.20 起「快捷应用」从车机设置按钮里独立成一个按钮（紧挨其后）。
        // 老配置里没有这一条，升级时补插到车机设置后面，避免升级后入口消失。
        val migrateMissingEntries = !Prefs.sp(ctx).getBoolean("dock_entries_explicit_layout", false) && loaded.isNotEmpty()
        if (migrateMissingEntries && loaded.none { it.kind == "builtin" && it.action == Builtin.APP_DRAWER.action }) {
            val entry = DockEntry(
                "b_app_drawer", "builtin",
                Builtin.APP_DRAWER.action, Builtin.APP_DRAWER.label
            )
            val at = loaded.indexOfFirst {
                it.kind == "builtin" && it.action == Builtin.SETTINGS.action
            }
            if (at >= 0) loaded.add(at + 1, entry) else loaded.add(entry)
            save(ctx, loaded)
        }
        if (migrateMissingEntries && loaded.none { it.kind == "builtin" && it.action == Builtin.VOLUME.action }) {
            val entry = DockEntry(
                "b_volume", "builtin",
                Builtin.VOLUME.action, Builtin.VOLUME.label
            )
            val at = loaded.indexOfFirst {
                it.kind == "builtin" && it.action == Builtin.LYRIC.action
            }
            if (at >= 0) loaded.add(at + 1, entry) else loaded.add(entry)
            save(ctx, loaded)
        }
        // v1.4.24 起「主驾温度 / 副驾温度 / 风速」的「− 值 +」三件套融合成单个组合按钮。
        // 老配置里是三条独立条目：把值条目原位替换成组合条目，并删掉两侧的 ± 条目。
        var comboChanged = false
        comboChanged = migrateCombo(loaded, Builtin.TEMP_DRIVER.action, Builtin.TEMP_DOWN_DRIVER.action, Builtin.TEMP_UP_DRIVER.action, Builtin.TEMP_DRIVER_COMBO) || comboChanged
        comboChanged = migrateCombo(loaded, Builtin.FAN.action, Builtin.FAN_DOWN.action, Builtin.FAN_UP.action, Builtin.FAN_COMBO) || comboChanged
        comboChanged = migrateCombo(loaded, Builtin.TEMP_PASSENGER.action, Builtin.TEMP_DOWN_PASSENGER.action, Builtin.TEMP_UP_PASSENGER.action, Builtin.TEMP_PASSENGER_COMBO) || comboChanged
        if (comboChanged) save(ctx, loaded)
        Prefs.sp(ctx).edit().putBoolean("dock_entries_explicit_layout", true).apply()

        // v3.13.8：音乐卡宽度已与 span 解耦（弹性 295~500px），
        // 3.13.7 曾把 span 迁到 5，这里无伤害地残留 5 也不影响布局，不再迁移。
        return loaded
    }

    /** 三件套 → 组合条目：值条目原位替换为 combo，± 条目删除；只有 ± 没有值时在 ± 处落位。返回是否发生了变更 */
    private fun migrateCombo(
        list: MutableList<DockEntry>,
        valueAction: String,
        minusAction: String,
        plusAction: String,
        combo: Builtin
    ): Boolean {
        val hasCombo = list.any { it.kind == "builtin" && it.action == combo.action }
        val valueIdx = list.indexOfFirst { it.kind == "builtin" && it.action == valueAction }
        val minusIdx = list.indexOfFirst { it.kind == "builtin" && it.action == minusAction }
        val plusIdx = list.indexOfFirst { it.kind == "builtin" && it.action == plusAction }
        if (hasCombo) {
            // 已有组合条目：只清理残留的旧 ± 条目
            val removed = list.removeAll {
                it.kind == "builtin" && (it.action == minusAction || it.action == plusAction)
            }
            // v1.4.24 首版迁移出的 combo 是 1 槽，统一升到 combo.span（3 槽，与原三件套等宽）
            val idx = list.indexOfFirst { it.kind == "builtin" && it.action == combo.action }
            var spanFixed = false
            if (idx >= 0 && list[idx].span < combo.span) {
                list[idx] = list[idx].copy(span = combo.span)
                spanFixed = true
            }
            return removed || spanFixed
        }
        val at = if (valueIdx >= 0) valueIdx else maxOf(minusIdx, plusIdx)
        if (at < 0) return false // 三件套都没有，无需迁移
        val old = list[at]
        list[at] = DockEntry(
            "b_${combo.action}", "builtin",
            combo.action, combo.label,
            // 组合按钮占 3 槽（与原三件套等宽）；用户若手动加宽过值条目则尊重更大值
            span = maxOf(old.span, combo.span)
        )
        list.removeAll {
            it.kind == "builtin" && (it.action == minusAction || it.action == plusAction)
        }
        return true
    }

    fun save(ctx: Context, list: List<DockEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        Prefs.setEntriesJson(ctx, arr.toString())
        Prefs.sp(ctx).edit().putBoolean("dock_entries_explicit_layout", true).apply()
    }

    fun reset(ctx: Context) = save(ctx, defaults())

    /** 追加一个内置控件（已存在则忽略，避免重复堆叠） */
    fun addBuiltin(ctx: Context, b: Builtin): List<DockEntry> {
        val list = load(ctx)
        if (list.none { it.kind == "builtin" && it.action == b.action }) {
            list.add(DockEntry("b_${b.action}", "builtin", b.action, b.label, span = b.span))
            save(ctx, list)
        }
        return list
    }

    /** 追加一个自定义 App；同包名可重复添加（比如放两个音乐入口） */
    fun addApp(ctx: Context, label: String, pkg: String, cls: String): List<DockEntry> {
        val list = load(ctx)
        list.add(
            DockEntry(
                id = "app_${System.currentTimeMillis()}_${pkg.hashCode()}",
                // kind=app 时包名存在 action 字段里（见类注释），本类没有 pkg 参数
                kind = "app", action = pkg, label = label, cls = cls, span = 1
            )
        )
        save(ctx, list)
        return list
    }

    fun remove(ctx: Context, id: String): List<DockEntry> {
        val list = load(ctx).filter { it.id != id }.toMutableList()
        save(ctx, list)
        return list
    }

    fun move(ctx: Context, from: Int, to: Int): List<DockEntry> {
        val list = load(ctx)
        if (from !in list.indices || to !in list.indices) return list
        val e = list.removeAt(from)
        list.add(to, e)
        save(ctx, list)
        return list
    }
}
