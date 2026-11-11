# Dock 控件注册表与手势绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 加一种 Dock 控件只登记一个控件对象；单击、长按、双击是可序列化的动作绑定，设置页以后只做选择器。

**Architecture:** 每个内置控件是一个 `DockWidget`（规格、视图、`bind`、默认手势）。`DockCatalog.mount` 是悬浮栏和编辑预览唯一的创建入口。`GestureResolver` 决定何时触发，`DockAction` 是可保存的命令，`DockActions.perform` 是唯一执行入口。弹窗和状态小窗留在 `DockOverlayService` 的 `DockHost` 上。编辑拖拽继续用现有的 `DockEditorLongPress`，不进这套绑定。

**Tech Stack:** Kotlin，Android minSdk 28 / compileSdk 34 / JVM 17，本地单元测试只用 JUnit 4。不引入 Robolectric。

**Spec:** 本文件的 Goal、Global Constraints、手势数据模型和默认绑定表。没有另一份规格。

## Global Constraints

- `minSdk 28`，`compileSdk 34`，`jvmTarget 17`。已有 action 字符串（`home`、`music`、`temp_driver_combo` 等）保持不变，旧 JSON 仍能加载。
- 尺寸保持现状：1 槽 98px，3 槽温度组合 290px，风速组合固定 220px、箭头 40px，音乐卡弹性 295–500px，返回桌面图标 0.8 倍。屏幕仍按 1920×1080、DPI 160 的现有常量，不在本计划里改。
- 双击窗口固定 `300L` 毫秒，且只对绑定了双击的命中区生效。没有双击的控件，单击在抬手时立即触发。
- 长按超时用 `ViewConfiguration.getLongPressTimeout()`，不改成编辑页的 300ms。编辑拖拽仍走 `DockEditorLongPress`（`TIMEOUT_MS = 300`）。
- 车控 functionId、zone、编码不改。Release 继续 R8。新代码不反射、不改 `proguard-rules.pro`。
- 设置页界面不在本计划。本计划只保证覆盖 JSON 能被读、写、合并、执行。
- 隐藏的旧条目（独立的温度 ±、风速 ±、独立温度值、独立风速值）继续能从旧 JSON 建出来，继续不出现在「添加内置控件」列表。
- 未知 `action` 显示占位图标，不得再回退成返回桌面。

## 不在本计划里

- 设置页的手势编辑界面。数据模型按下面的 JSON 定死，后续界面只读写 `DockEntry.gestures`。
- 拆 `MainActivity`、`VehicleController`、`DockOverlayService` 里和控件创建无关的部分。
- 改编辑页拖拽的超时或手势。

## 手势数据模型

命中区只有这五个名字：`root`、`minus`、`value`、`plus`、`play`。

手势名字：`tap`、`longPress`、`doubleTap`。

动作 `type` 只允许下面这些。括号里是额外字段。

| type | 字段 | 现有行为 |
| --- | --- | --- |
| `goHome` | 无 | `IntentLauncher.goHome` |
| `openRecents` | 无 | `IntentLauncher.openRecents` |
| `openCarSettings` | 无 | `IntentLauncher.launch(ctx, "车机设置", settingsTarget, SETTINGS_CANDIDATES)` |
| `openDockSettings` | 无 | 打开 `MainActivity`，`FLAG_ACTIVITY_NEW_TASK \| FLAG_ACTIVITY_CLEAR_TOP` |
| `openThisApp` | 无 | 打开 `MainActivity`，只有 `FLAG_ACTIVITY_NEW_TASK` |
| `openAppDrawerSettings` | 无 | `MainActivity`，带 `EXTRA_PICK_APP_DRAWER`，两个 flag 都加 |
| `launchThisEntry` | 无 | 用这条 `DockEntry` 的 `action` 作包名、`cls` 作类名启动 |
| `showPopup` | `popup`：`airDirection` `appDrawer` `temperatureDriver` `temperaturePassenger` `volume` `seat` | 调 Host 上现有的对应弹窗 |
| `toggleHvacWindow` | 无 | 现有 `toggleSystemHvacWindow` |
| `toggleAc` | 无 | 只调 `VehicleController.toggleAc`，不弹状态窗 |
| `toggleAcFromDock` | 无 | 现有 `toggleAcFromDock`（切 AC 并弹「AC 开/关」） |
| `toggleDualSyncFromDock` | 无 | 现有 `toggleDualSync`（含状态弹窗） |
| `tempStep` | `driver` 布尔，`delta` 整数 | `VehicleController.tempStep` |
| `fanStep` | `delta` 整数 | `VehicleController.fanStep` |
| `toggleFanAuto` | 无 | `VehicleController.toggleFanAuto` |
| `fanOff` | 无 | `VehicleController.setFanOff` |
| `toggleRecirc` | 无 | `VehicleController.toggleRecirc` |
| `toggleDefrostFront` | 无 | `VehicleController.toggleDefrostFront` |
| `toggleDefrostRear` | 无 | `VehicleController.toggleDefrostRear` |
| `levelBump` | `channel`：`vent` `seatHeat` `steeringHeat`；`direction`：`down` `up` | 见档位规则 |
| `openAirPanel` | 无 | 现有长按风向：配置了风向 Intent 就启动，否则 `HvacApp.openPanel(PAGE_MAIN)` |
| `toggleLyric` | 无 | `LyricWindows.toggle` |
| `openPlayer` | 无 | `MediaBridge.openPlayer`，失败再走音乐 Intent |
| `playPause` | 无 | `MediaBridge.playPause` |
| `mediaNext` | 无 | `MediaBridge.next` |

档位规则：`down` 在当前值 `<= 0` 时写成 3，否则减 1。`up` 在当前值 `>= 3` 时写成 0，否则加 1。读当前值用 `VehicleController.state` 里对应字段，写回现有的 `setSeatVentLevel` / `setSeatHeatLevel` / `setSteeringHeatLevel`。

条目上的覆盖 JSON 深合并，不是整表替换：

- 条目没有 `gestures`：全部用控件默认。
- 某个命中区没写：这个命中区用默认。
- 某个手势键没写：这个手势用默认。
- 某个手势键是 JSON `null`：这个手势解开，不再触发，也不再占用双击窗口。
- `type` 不认识：这个手势槽忽略，继续用默认。不丢整条条目。
- 命中区名字不认识：忽略这个键。
- `gestures` 不是对象：当作没有覆盖。

抬手时调用一次 `DockHost.onGesture()`（用来重置临时显示倒计时）。长按触发时调用一次，这次按下之后的抬手不再调用，也不再触发单击。双击的两次抬手各调用一次 `onGesture()`，动作只发出 `openRecents`（或该区双击绑定的那一个），不发出单击动作。

## 默认绑定

没写的手势就是没有。`home` 的单击因此带 300ms 双击窗口，其它单击都是抬手立即执行。

| action | 绑定 |
| --- | --- |
| `home` | root 单击 `goHome`，双击 `openRecents` |
| `settings` | root 单击 `openCarSettings`，长按 `openDockSettings` |
| `app_drawer` | root 单击 `showPopup/appDrawer`，长按 `openAppDrawerSettings` |
| `seat` | root 单击 `showPopup/seat` |
| `volume` | root 单击 `showPopup/volume` |
| `lyric` | root 单击 `toggleLyric`，长按 `openThisApp`。span 锁死 1，忽略条目里更大的 span |
| `air` | root 单击 `showPopup/airDirection`，长按 `openAirPanel` |
| `recirc` | root 单击 `toggleRecirc` |
| `defrost_front` | root 单击 `toggleDefrostFront` |
| `defrost_rear` | root 单击 `toggleDefrostRear` |
| `vent` | root 单击 `levelBump vent down`，长按 `levelBump vent up` |
| `seat_heat` | root 单击 `levelBump seatHeat down`，长按 `levelBump seatHeat up` |
| `steering_heat` | root 单击 `levelBump steeringHeat down`，长按 `levelBump steeringHeat up` |
| `temp_driver_combo` | minus 单击 `tempStep driver -1`；plus 单击 `tempStep driver +1`；value 单击 `showPopup/temperatureDriver`；value 长按 `toggleAcFromDock` |
| `temp_passenger_combo` | minus / plus 用 `driver=false` 的 `tempStep` ±1；value 单击 `showPopup/temperaturePassenger`；value 长按 `toggleDualSyncFromDock` |
| `fan_combo` | minus / plus 为 `fanStep` ±1；value 单击 `toggleFanAuto`；value 长按 `fanOff`。固定宽 220，箭头 40 |
| `temp_down_driver` / `temp_up_driver` | root 单击 `tempStep driver` 的 -1 / +1 |
| `temp_down_passenger` / `temp_up_passenger` | 同上，`driver=false` |
| `temp_driver` | root 单击 `toggleHvacWindow`，长按 `toggleAc`（不弹状态窗） |
| `temp_passenger` | root 单击 `toggleHvacWindow`，长按 `toggleDualSyncFromDock` |
| `fan_down` / `fan_up` | root 单击 `fanStep` -1 / +1 |
| `fan` | root 单击 `toggleHvacWindow`，长按 `fanOff` |
| `music` | root 单击 `openPlayer`；play 单击 `playPause`；play 长按 `mediaNext`。弹性宽 295–500 |
| `kind=app` | root 单击 `launchThisEntry` |

规格里的 `label`、`span`、`separatorBefore`、`hidden`、`hint` 从现有 `Builtin` 读，不在控件里再写一遍。`Builtin` 继续留给旧数据迁移。新的控件类型不追加枚举值，用 `WidgetSpec` 登记。

添加列表用的静态图标，与现在 `DockEntriesActivity.builtinIcon` 一致：

| action | drawable |
| --- | --- |
| `home` | `ic_home_circle` |
| `music`、`lyric` | `ic_music_note` |
| `volume` | `ic_volume` |
| `settings` | `ic_car` |
| `app_drawer` | `ic_apps` |
| `air` | `ic_airflow` |
| `vent` | `ic_seat_vent` |
| `seat` | `ic_seat_open` |
| `seat_heat` | `ic_seat_hot_03` |
| `recirc` | `ic_recirc` |
| `defrost_front` | `ic_defrost` |
| `defrost_rear` | `ic_rear_defrost` |
| `steering_heat` | `ic_sw_heat_03` |
| 其余内置 | `ic_car` |

运行时图标另算，不占用这张表。内外循环绑定用 `ic_ac_cycle_inner` / `ic_ac_cycle_outer`。风向用下面的位掩码。通风、加热、方向盘按档位换 `ic_seat_feng_*`、`ic_seat_hot_*`、`ic_sw_heat_*`，`darkIcons == true` 时用 `*_dark`。档位 0 用 `*_off`。

风向位：1 脸、2 脚、4 窗。`1` 脸，`2` 脚，`3` 脸脚，`4` 窗，`5` 脸窗，`6` 脚窗，`7` 全开，其它 `ic_blowing_off`。悬浮栏和编辑预览共用这一个函数。

返回桌面图标缩放 0.8，通风 / 座椅加热 / 方向盘加热 `preserveColors = true`。

span：`entry.span > 1` 时用条目 span（夹到 1..6），否则用控件默认 span。歌词例外，永远 1。

## 文件

创建：

- `app/src/main/java/com/car/dockbar/dock/DockAction.kt`：动作、编码、解码
- `app/src/main/java/com/car/dockbar/dock/GestureMap.kt`：命中区、绑定、覆盖、合并
- `app/src/main/java/com/car/dockbar/dock/GestureResolver.kt`：纯状态机
- `app/src/main/java/com/car/dockbar/dock/GestureInput.kt`：把触摸事件送进状态机
- `app/src/main/java/com/car/dockbar/dock/DockHost.kt`：`DockHost` 与 `SilentDockHost`
- `app/src/main/java/com/car/dockbar/dock/DockActions.kt`：`perform`
- `app/src/main/java/com/car/dockbar/dock/DockIcons.kt`：风向图、档位图、`bumpLevel`
- `app/src/main/java/com/car/dockbar/dock/DockSnapshot.kt`：`DockSnapshot` 与 `DockSnapshots.capture`
- `app/src/main/java/com/car/dockbar/dock/DockWidget.kt`：规格、控件接口、`iconWidget` / `comboWidget`
- `app/src/main/java/com/car/dockbar/dock/DockCatalog.kt`：注册与 `mount`
- `app/src/main/java/com/car/dockbar/dock/widgets/BuiltinWidgets.kt`：26 个内置控件和自定义应用、占位控件
- `app/src/test/java/com/car/dockbar/dock/DockActionCodecTest.kt`
- `app/src/test/java/com/car/dockbar/dock/GestureMapTest.kt`
- `app/src/test/java/com/car/dockbar/dock/GestureResolverTest.kt`
- `app/src/test/java/com/car/dockbar/dock/DockIconsTest.kt`
- `app/src/test/java/com/car/dockbar/dock/DockCatalogTest.kt`

修改：

- `app/build.gradle`：加 `testImplementation 'junit:junit:4.13.2'`
- `app/src/main/java/com/car/dockbar/model/DockEntry.kt`：`gestures` 字段
- `app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt`：实现 Host，改为 `mount` + 一次 `rebind`
- `app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt`：同一 `mount` 和 `capture`，不再摘监听
- `app/src/main/java/com/car/dockbar/ui/DockEntriesActivity.kt`：添加列表和图标走注册表
- 删除 `DockEntryViewFactory.kt` 中的 `BindKind`、构造回调和 `when (b)`。宽度常量移到 `DockCatalog` 所在的 `DockMetrics`（放在 `DockWidget.kt` 末尾，不另开文件）

`DockMetrics` 保留现有常量名的值：`FAN_COMBO_FIXED_WIDTH_PX = 220`，`FAN_COMBO_ARROW_PX = 40`，`SLOT_FIXED_PX = 98`，`SPAN3_FIXED_PX = 290`，`MUSIC_MIN_WIDTH_PX = 295`，`MUSIC_MAX_WIDTH_PX = 500`，以及 `fixedWidthFor(span)`。预览里的 `DockEntryViewFactory.FAN_COMBO_FIXED_WIDTH_PX` 改为 `DockMetrics`。

## 接口

后面的任务都用这些签名，不再改名。

```kotlin
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
    fun toJson(action: DockAction): JSONObject
    fun fromJson(obj: JSONObject): DockAction?
}

object HitTargets {
    const val ROOT = "root"
    const val MINUS = "minus"
    const val VALUE = "value"
    const val PLUS = "plus"
    const val PLAY = "play"
}

enum class Gesture { TAP, LONG_PRESS, DOUBLE_TAP }

data class GestureBinding(
    val tap: DockAction? = null,
    val longPress: DockAction? = null,
    val doubleTap: DockAction? = null,
) {
    val doubleWindowMs: Long get() = if (doubleTap != null) 300L else 0L
}

sealed class ActionSlot {
    data class Set(val action: DockAction) : ActionSlot()
    data object Clear : ActionSlot()
}

data class GesturePatch(val byTarget: Map<String, Map<Gesture, ActionSlot>>)

object GestureMaps {
    fun merge(base: Map<String, GestureBinding>, patch: GesturePatch?): Map<String, GestureBinding>
    fun parsePatch(obj: JSONObject?): GesturePatch?
    fun toJson(patch: GesturePatch): JSONObject
}

class GestureResolver(
    private val binding: GestureBinding,
    private val longPressMs: Long,
    private val slopPx: Float,
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val emit: (DockAction) -> Unit,
    private val onFingerSettled: () -> Unit,
)

fun bumpLevel(current: Int, direction: String): Int

fun airDirectionIcon(bits: Int): Int
fun levelIcon(channel: String, level: Int, darkIcons: Boolean): Int

interface DockHost {
    fun onGesture()
    fun showPopup(popup: String, anchor: View)
    fun toggleHvacWindow()
    fun toggleAcFromDock()
    fun toggleDualSyncFromDock()
}

object DockActions {
    fun perform(action: DockAction, ctx: Context, host: DockHost, anchor: View, entry: DockEntry)
}

data class MountedEntry(
    val widget: DockWidget,
    val entry: DockEntry,
    val view: View,
    val hits: Map<String, View>,
    val span: Int,
    val separator: Boolean,
    val valueView: DockValueView?,
    val fixedWidthPx: Int,
    val flexMinPx: Int,
    val flexMaxPx: Int,
)

interface DockWidget {
    val spec: WidgetSpec
    fun defaultGestures(): Map<String, GestureBinding>
    fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry
    fun bind(mounted: MountedEntry, snapshot: DockSnapshot)
}

object DockCatalog {
    fun of(action: String): DockWidget?
    fun builtins(): List<DockWidget>
    fun kindFor(kind: String, action: String): String
    fun spanOf(builtin: Builtin, entrySpan: Int): Int
    fun mount(entry: DockEntry, host: DockHost, attachGestures: Boolean, iconPx: Int): MountedEntry
}
```

`iconWidget` 的登记形式：

```kotlin
fun iconWidget(
    builtin: Builtin,
    icon: Int,
    gestures: Map<String, GestureBinding>,
    catalogIcon: Int = icon,
    iconScale: Float = 1f,
    preserveColors: Boolean = false,
    lockSpan: Boolean = false,
    bind: (DockIconView, DockSnapshot) -> Unit = { _, _ -> },
): DockWidget
```

新类型不在 `Builtin` 里时，用同一个文件里的 `iconWidget(spec: WidgetSpec, ...)`。两种都返回 `DockWidget`，由 `DockCatalog` 的列表收集。

`comboWidget` 登记温度和风速组合，以及它们内部的减、值、加。遗留的单颗 ± 和单颗数值用 `iconWidget` 或一个 `valueWidget`，不走组合布局。

`showPopup` 的 `popup` 字符串和 Host 的分支一一对应：`airDirection`、`appDrawer`、`temperatureDriver`、`temperaturePassenger`、`volume`、`seat`。其它字符串 `perform` 里直接忽略。

## Review Focus

这五类输入最容易在真车上把单击拖慢、点两次，或把旧配置弄丢。对应测试写在拥有该逻辑的任务里。

1. 只有单击的控件（内外循环）抬手就执行，不等 300ms。
2. 返回桌面：300ms 内第二下只发 `openRecents`；只有一下时，`goHome` 在第一下抬手之后 300ms 才发。
3. 长按触发后，抬手不再发单击。手指移动超过 slop，单击和长按都不发。
4. 覆盖 JSON 坏掉、动作 `type` 不认识、命中区不认识时，条目仍在，没被覆盖的手势仍是默认。
5. 条目 `action` 不在注册表里时，挂出来的控件不是 `home`。

---

### Task 1: 动作编码和手势合并

**Files:**

- Modify: `app/build.gradle`（`dependencies` 块末尾）
- Create: `app/src/main/java/com/car/dockbar/dock/DockAction.kt`
- Create: `app/src/main/java/com/car/dockbar/dock/GestureMap.kt`
- Test: `app/src/test/java/com/car/dockbar/dock/DockActionCodecTest.kt`
- Test: `app/src/test/java/com/car/dockbar/dock/GestureMapTest.kt`

**Interfaces:**

- Consumes: 无
- Produces: 上面「接口」一节里的 `DockAction`、`DockActionCodec`、`GestureBinding`、`GesturePatch`、`GestureMaps`、`HitTargets`、`Gesture`

- [ ] **Step 1: 写会失败的测试**

`app/build.gradle` 的 `dependencies` 增加 `testImplementation 'junit:junit:4.13.2'`。

`DockActionCodecTest`：

- `goHome`、`openRecents`、`toggleFanAuto`、`fanOff`、`toggleLyric`、`openPlayer`、`playPause`、`mediaNext`、`openCarSettings`、`launchThisEntry`、`toggleHvacWindow`、`toggleAc`、`toggleAcFromDock`、`toggleDualSyncFromDock`、`openAirPanel`、`openDockSettings`、`openThisApp`、`openAppDrawerSettings` 往返后类型不变。
- `tempStep` 往返后 `driver == false` 且 `delta == 1`。
- `fanStep` 往返后 `delta == -1`。
- `showPopup` 往返后 `popup == "seat"`。
- `levelBump` 往返后 `channel == "vent"` 且 `direction == "down"`。
- `{"type":"nope"}` 解码为 `null`。
- 缺 `type` 解码为 `null`。

`GestureMapTest`：

- `merge` 在 `patch == null` 时返回原绑定。
- 基础是 root 单击 `GoHome`、双击 `OpenRecents`。覆盖 `{"root":{"longPress":{"type":"openRecents"}}}` 后，单击仍是 `GoHome`，双击仍是 `OpenRecents`，长按是 `OpenRecents`。
- 覆盖 `{"root":{"doubleTap":null}}` 后双击为 `null`，单击仍是 `GoHome`，`doubleWindowMs == 0L`。
- 覆盖 `{"root":{"tap":{"type":"nope"}}}` 后单击仍是 `GoHome`。
- 覆盖 `{"nope":{"tap":{"type":"goHome"}}}` 后原 root 绑定不变。
- `parsePatch(null) == null`。`parsePatch` 遇到 `gestures` 值不是对象时返回 `null`（调用方传 `optJSONObject` 的结果，字符串键得到 `null`）。

- [ ] **Step 2: 跑测试，确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.DockActionCodecTest --tests com.car.dockbar.dock.GestureMapTest`

Expected: 编译失败，类不存在。

- [ ] **Step 3: 实现编码和合并**

`fromJson` 不认识的 `type`、缺字段、字段类型不对，返回 `null`。`merge` 只替换补丁里出现且能解码的槽；`ActionSlot.Clear` 把该槽设成 `null`。不认识的命中区丢弃。`doubleWindowMs` 只看合并后的 `doubleTap`。

- [ ] **Step 4: 再跑，确认通过**

Run: 同上。Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/com/car/dockbar/dock/DockAction.kt app/src/main/java/com/car/dockbar/dock/GestureMap.kt app/src/test/java/com/car/dockbar/dock/DockActionCodecTest.kt app/src/test/java/com/car/dockbar/dock/GestureMapTest.kt
git commit -m "feat: encode dock gesture actions as data"
```

### Task 2: 手势状态机

**Files:**

- Create: `app/src/main/java/com/car/dockbar/dock/GestureResolver.kt`
- Test: `app/src/test/java/com/car/dockbar/dock/GestureResolverTest.kt`

**Interfaces:**

- Consumes: `GestureBinding`、`DockAction`
- Produces: `GestureResolver`，方法 `onDown(x, y)`、`onMove(x, y)`、`onUp(x, y)`、`onCancel()`。构造参数见接口一节。`schedule` 同时只保留一个待执行单击；新的 `schedule` 先取消旧的。`cancelScheduled` 清掉这个单击。

- [ ] **Step 1: 写会失败的测试**

测试用一个可变 `now`，一个槽位记录 `dueAt` 和回调。`slopPx = 8f`，`longPressMs = 400L`。

断言：

- 只有单击：`down(0)`、`up(20)` 立刻发出该单击，且 `doubleTap == null` 时不调用 `schedule`。
- 单击加双击：`up(20)` 不立刻发单击，`schedule(300)`；把时钟拨到 320 并执行回调后发出单击一次。
- 300ms 内第二次按下：第一次的延迟单击被 `cancelScheduled`，第二次 `up` 只发双击一次，不发单击。
- 第二次按下发生在 300ms 之后：第一次单击已经发出；第二次重新开始计时。
- 长按：`down` 之后把时钟拨过 `longPressMs` 并触发长按回调，发出长按一次，并调用 `onFingerSettled` 一次。随后 `up` 不再发单击，也不再调用 `onFingerSettled`。
- 没有长按绑定：到时不发任何动作。
- 移动超过 8px：长按不发，抬手不发单击，已预约的单击被取消。
- `onCancel` 与越界移动相同。
- 长按和双击同时存在时，长按优先：长按已经发出后，这次抬手不参与双击配对。

长按的到时由测试在 `down` 之后自己调用一个包内可见的方法。签名定成 `fun onLongTimeout()`，不要在状态机里启动真的 `Handler`。`GestureInput` 以后负责 `postDelayed(longPressMs) { resolver.onLongTimeout() }`。

- [ ] **Step 2: 跑测试，确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.GestureResolverTest`

Expected: 失败，类不存在。

- [ ] **Step 3: 实现状态机**

一次按下的规则：

1. `onDown`：若已有待执行单击，先 `cancelScheduled`。记录坐标。若这次按下距上次抬手小于 `binding.doubleWindowMs`，记为双击的第二下。复位「已长按」「已越界」。
2. `onMove`：与按下点的距离大于 `slopPx` 时记为越界。越界后长按不再成立。
3. `onLongTimeout`：仍按下、未越界、未发过长按、且 `longPress != null` 时，发出长按，调用一次 `onFingerSettled`，并 `cancelScheduled`。
4. `onUp`：若已发长按或已越界，结束，不发单击。若这是双击的第二下且 `doubleTap != null`，发双击，不发单击。若 `doubleTap != null`，记下这次抬手时间并 `schedule(doubleWindowMs)` 去发单击。否则立刻发单击。每次真正结束且没有发过长按的抬手，调用一次 `onFingerSettled`。越界和 `onCancel` 不调用它。
5. `onCancel`：与越界后的抬手相同，并且手指不再算按下。

延迟单击的回调在执行前再看一眼：若这期间已经开始了新的按下或已经取消，就不再发出。

- [ ] **Step 4: 再跑，确认通过**

Run: 同上。Expected: 通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/car/dockbar/dock/GestureResolver.kt app/src/test/java/com/car/dockbar/dock/GestureResolverTest.kt
git commit -m "feat: resolve tap, long press, and double tap without waiting by default"
```

### Task 3: 风向、档位和刷新用的纯函数

**Files:**

- Create: `app/src/main/java/com/car/dockbar/dock/DockIcons.kt`
- Test: `app/src/test/java/com/car/dockbar/dock/DockIconsTest.kt`
- Modify: `app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt`（`airResOf`、通风、加热、方向盘三处 `when`）
- Modify: `app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt`（对应的 `when`）

**Interfaces:**

- Consumes: 现有 `R.drawable` 资源名
- Produces: `bumpLevel`、`airDirectionIcon`、`levelIcon`。`levelIcon` 的 `channel` 用 `vent`、`seatHeat`、`steeringHeat`。`direction` 用 `down`、`up`。

- [ ] **Step 1: 写会失败的测试**

`bumpLevel(0, "down") == 3`，`bumpLevel(1, "down") == 0`，`bumpLevel(3, "up") == 0`，`bumpLevel(2, "up") == 3`，`bumpLevel(3, "down") == 2`。

`airDirectionIcon`：`0` 和 `8` 都是 `R.drawable.ic_blowing_off`；`1` 脸，`2` 脚，`3` 脸脚，`4` 窗，`5` 脸窗，`6` 脚窗，`7` 全开。资源名用现在 `airResOf` 里的那一组。

`levelIcon("vent", 0, false)` 是 `ic_seat_feng_off`，`darkIcons = true` 时是 `ic_seat_feng_off_dark`。`1` 和 `3` 分别对应 `ic_seat_feng_01` 与 `ic_seat_feng_03` 及其 `_dark`。`seatHeat` 的 2 档亮色是 `ic_seat_hot_02`。`steeringHeat` 的 0 档暗色是 `ic_sw_heat_off_dark`。

- [ ] **Step 2: 跑测试，确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.DockIconsTest`

Expected: 失败，函数不存在。

- [ ] **Step 3: 实现函数，并让两处刷新改调它们**

删除 `DockOverlayService.airResOf`。预览里的风向 `when (state.airDirection)` 和三组档位 `when` 改成这三个函数。内外循环图标这一步先不动。

- [ ] **Step 4: 测试和编译**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.DockIconsTest :app:compileDebugKotlin`

Expected: 都通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/car/dockbar/dock/DockIcons.kt app/src/test/java/com/car/dockbar/dock/DockIconsTest.kt app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt
git commit -m "refactor: share dock icon selection between the bar and the editor"
```

### Task 4: Host、执行器和触摸适配

**Files:**

- Create: `app/src/main/java/com/car/dockbar/dock/DockHost.kt`
- Create: `app/src/main/java/com/car/dockbar/dock/DockActions.kt`
- Create: `app/src/main/java/com/car/dockbar/dock/GestureInput.kt`
- Modify: `app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt`（内部类实现 `DockHost`，这一步先不改 `buildDock`）

**Interfaces:**

- Consumes: `GestureResolver`、`DockAction`、现有弹窗和车控方法
- Produces:
  - `object GestureInput { fun attach(view: View, binding: GestureBinding, onAction: (DockAction) -> Unit, onSettled: () -> Unit) }`
  - `DockActions.perform`
  - `object SilentDockHost : DockHost`，五个方法都空实现
  - `DockOverlayService` 里的私有 `ServiceDockHost`，把 Host 方法转到现有的 `onUserInteraction`、`showAirDirectionPopup`、`showAppDrawer`、`showAirQualityPopup`、`showVolumePopup`、`showSeatPopup`、`toggleSystemHvacWindow`、`toggleAcFromDock`、`toggleDualSync`

- [ ] **Step 1: 写执行分支**

`perform` 按「手势数据模型」表调用现有函数。`showPopup` 只把 `popup` 交给 `host.showPopup`。`toggleHvacWindow`、`toggleAcFromDock`、`toggleDualSyncFromDock` 走 Host。`toggleAc` 直接 `VehicleController.toggleAc(ctx)`。`levelBump` 用 `bumpLevel` 计算下一档再调用现有 setter。`launchThisEntry` 使用传入的 `entry`。`openPlayer` 保持「先 `MediaBridge.openPlayer`，失败再 `IntentLauncher`」的顺序。

`GestureInput.attach`：

- 用该 View 的 `scaledTouchSlop` 和 `ViewConfiguration.getLongPressTimeout()`。
- `clock` 用 `SystemClock.elapsedRealtime`。
- `schedule` / `cancelScheduled` 用 `view.postDelayed` / `view.removeCallbacks`，只跟踪延迟单击。
- 另起一个长按 `Runnable`，在 `ACTION_DOWN` 时 `postDelayed(longPressMs)`，在移动越界、抬手、取消、从窗口摘掉时 `removeCallbacks`。到时调用 `resolver.onLongTimeout()`。
- 监听 `ACTION_DOWN`、`MOVE`、`UP`、`CANCEL`、`POINTER_DOWN`。`POINTER_DOWN` 按取消处理。
- 监听从窗口摘掉，摘掉时 `onCancel` 并清掉两个回调。
- 触摸监听返回值：已经发出长按，或正在跟踪这次按下时，返回 `true`，避免原来的 `OnClickListener` 再响一次。未跟踪的事件返回 `false`。
- 挂上之前把该 View 的 `setOnClickListener(null)` 和 `setOnLongClickListener(null)` 清掉，`isLongClickable = false`。

`ServiceDockHost.showPopup` 用 `when (popup)` 调用现有六个方法。`temperatureDriver` / `temperaturePassenger` 传 `isDriver`。不认识的 `popup` 什么也不做。

这一步不要改 `DockEntryViewFactory` 的监听。先让新类型能编译。

- [ ] **Step 2: 编译**

Run: `.\gradlew :app:compileDebugKotlin`

Expected: 通过。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/car/dockbar/dock/DockHost.kt app/src/main/java/com/car/dockbar/dock/DockActions.kt app/src/main/java/com/car/dockbar/dock/GestureInput.kt app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt
git commit -m "feat: dispatch dock actions through one host"
```

### Task 5: 控件注册表替换工厂

**Files:**

- Create: `app/src/main/java/com/car/dockbar/dock/DockWidget.kt`
- Create: `app/src/main/java/com/car/dockbar/dock/DockCatalog.kt`
- Create: `app/src/main/java/com/car/dockbar/dock/widgets/BuiltinWidgets.kt`
- Create: `app/src/test/java/com/car/dockbar/dock/DockCatalogTest.kt`
- Modify: `app/src/main/java/com/car/dockbar/ui/DockEntryViewFactory.kt`（变薄，直到删除）
- Modify: `app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt` 的 `buildDock`
- Modify: `app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt` 的工厂构造

**Interfaces:**

- Consumes: Task 1–4 的全部接口，外加现有 `DockIconView`、`DockValueView`、`DockMusicView`、`VolumeDockButton`
- Produces: `WidgetSpec`、`MountedEntry`、`DockWidget`、`DockCatalog.mount`、`DockMetrics`

- [ ] **Step 1: 写注册表测试**

`DockCatalogTest`：

- 每个 `Builtin.values()` 都能 `of(action)`，且 `spec.action`、`label`、`span`、`separatorBefore`、`hidden` 与枚举相同。
- `of("no-such") == null`。
- `kindFor("builtin", "no-such") == "missing"`，`kindFor("builtin", "home") == "home"`，`kindFor("app", "com.example") == "app"`。`missing` 不得等于 `home`。
- `home` 的 root：单击是 `GoHome`，双击是 `OpenRecents`，长按是 `null`，`doubleWindowMs == 300L`。
- `recirc` 的 root：单击是 `ToggleRecirc`，长按和双击都是 `null`，`doubleWindowMs == 0L`。
- `temp_driver` 的长按是 `ToggleAc`，不是 `ToggleAcFromDock`。
- `temp_driver_combo` 的 value 长按是 `ToggleAcFromDock`。
- `lyric` 的 `lockSpan` 行为用纯函数测：`DockCatalog.spanOf(Builtin.LYRIC, entrySpan = 3) == 1`，`spanOf(Builtin.MUSIC, 3) == 3`，`spanOf(Builtin.HOME, 1) == 1`，`spanOf(Builtin.HOME, 4) == 4`。把 `spanOf` 做成 `DockCatalog` 的函数，控件的 `lockSpan` 为真时忽略条目 span。
- 默认手势表里每个控件至少有一个非空绑定。用一张测试内的期望表覆盖「默认绑定」一节的每一行，断言动作类型和关键字段（`delta`、`driver`、`popup`、`channel`、`direction`）。少一个或多一个绑定都失败。

- [ ] **Step 2: 跑测试，确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.DockCatalogTest`

Expected: 失败，注册表不存在。

- [ ] **Step 3: 实现控件和 mount**

`WidgetSpec` 从 `Builtin` 填充 `label`、`span`、`separatorBefore`、`hidden`、`hint`。`catalogIcon` 用上面的静态图标表。

`iconWidget` 创建一个 `DockIconView`。`iconScale` 乘在调用方传入的 `iconPx` 上。`create` 先不 `bind`。字号、栏高、图标缩放的基准从 `Prefs` 读，和现在的工厂一样。`hits` 只有 `root`。

`valueWidget` 创建现在的 `DockValueView`（字号继续 `25f * textScale`、`16f * textScale`、`12f * textScale`）。`hits` 只有 `root`，`valueView` 指向它。

`comboWidget` 复制现在 `comboValue` 的横向三等分布局。`hits` 为 `minus`、`value`、`plus`。风速组合把 `fixedWidthPx = 220`、箭头 `40` 放在 spec 上。温度组合 `fixedWidthPx = 0`，由 `mount` 再用 `fixedWidthFor(3)` 得到 290。

`MusicWidget`：`DockMusicView`，`hits` 为 `root` 和 `play`（`btnPlay`），`flexMinPx = 295`，`flexMaxPx = 500`。

`AppWidget` 不进 `builtins()`。`mount` 看到 `kind == "app"` 就用它。图标逻辑保持现在的 `setDynamic`，失败时 `ic_car`。

`MissingWidget`：`of` 失败且不是 app 时使用。图标 `ic_car`，`defaultGestures()` 为空，`spec.action` 用条目自己的 `action`。禁止调用 `Builtin.HOME`。 `kindFor` 对 app 返回 `app`，对注册表里有的 action 返回该 action，其余返回 `missing`。

`mount`：

1. 选出控件。
2. `create`。
3. span、分隔线、固定宽、弹性宽按「默认绑定」前面的规则填进 `MountedEntry`。音乐已经带了弹性宽，不要再套 `fixedWidthFor`。`fixedWidthPx > 0` 的保持。其余用 `fixedWidthFor(span)`。
4. `attachGestures == true` 时，对 `hits` 里每个 View 取合并后的绑定。合并用 `GestureMaps.merge(widget.defaultGestures(), entry.gestures)`。这一步 `DockEntry.gestures` 还没有字段，先传 `null`。绑定的三个槽都是 `null` 时不要 `attach`。
5. `onAction` 调 `DockActions.perform`。`onSettled` 调 `host.onGesture()`。

`DockOverlayService.buildDock` 改成对 `DockCatalog.mount(e, ServiceDockHost(...), attachGestures = true, iconPx)` 循环。`iconPx` 继续 `(65 * iconScale).toInt().coerceAtLeast(12)`。`addItem` 的参数从 `MountedEntry` 取。这一步 `BindKind` 的 `when` 先留着，让刷新还能工作：`MountedEntry` 暂时仍带一个 `bindKind` 字段，由控件声明，取值与现在的枚举相同。工厂文件可以改成只保留 `BindKind` 和 `BuiltEntry` 的类型，或者把 `bindKind` 暂时放在 `MountedEntry` 上并删除工厂。选择后者，然后把预览和悬浮栏的 `when (built.bind)` 改成 `when (mounted.bindKind)`。预览构造工厂的那一行删除，改成 `mount(..., SilentDockHost, attachGestures = false, iconPx)`。`stripInteractions` 留到 Task 6 再删，避免这一步行为变掉。

宽度常量的外部引用改到 `DockMetrics`。

- [ ] **Step 4: 测试和编译**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.DockCatalogTest :app:compileDebugKotlin`

Expected: 通过。全仓库不再引用 `DockEntryViewFactory`。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/car/dockbar/dock app/src/test/java/com/car/dockbar/dock/DockCatalogTest.kt app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt app/src/main/java/com/car/dockbar/ui/DockEntryViewFactory.kt
git commit -m "refactor: create dock rows from a widget catalog"
```

### Task 6: 刷新收进控件

**Files:**

- Create: `app/src/main/java/com/car/dockbar/dock/DockSnapshot.kt`
- Modify: `BuiltinWidgets.kt` 里每个需要变图的控件的 `bind`
- Modify: `DockOverlayService.kt`、`DockEditorPreviewView.kt`
- Delete: `BindKind`（若还在 `DockEntryViewFactory.kt`，连文件一起删）

**Interfaces:**

- Consumes: `MountedEntry`、`levelIcon`、`airDirectionIcon`
- Produces:

```kotlin
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
    val mediaArt: android.graphics.Bitmap?,
    val mediaPlaying: Boolean,
)

object DockSnapshots {
    fun capture(ctx: Context, darkIcons: Boolean): DockSnapshot
}
```

- [ ] **Step 1: 把两处 when 收成 bind**

`capture` 的取值与现在两处刷新一致：温度为 `null` 时用 `Prefs.lastDriverTempC` / `lastPassengerTempC`；音量用 `CabinVolume.read(ctx, CabinVolume.MEDIA).value`；歌词用 `LyricOverlayService.alive && LyricOverlayService.attached`；媒体用 `MediaBridge.info`。

控件 `bind`：

- 温度数值：`renderTemp(..., muted = !acOn)`
- 风速数值：`renderFan(fanLevel, fanAuto)`
- 音量：`setLevel`
- 内外循环：内 `ic_ac_cycle_inner`，外 `ic_ac_cycle_outer`
- 风向：`airDirectionIcon`
- 前后除霜：`setActive`
- 通风、座椅加热、方向盘：`levelIcon` 后 `setActive(level > 0)`
- 歌词：`setActive(lyricOn)`
- 音乐：有会话就 `render`，否则 `renderEmpty`
- 其它：空 `bind`

`darkIcons` 的算法保持调用方现有写法。悬浮栏继续用它现在的 `darkText` 推导，预览继续用 `Color.red(DockIconView.fgColor) < 128`。两边都把结果传进 `capture`。

悬浮栏删除按 `BindKind` 分的那些 View 列表、`clearBindings` 里的这些列表，以及 `bindVehicle` / `bindMedia` / `refreshLyricIcons` 里的逐类刷新。换成一个 `mounted: MutableList<MountedEntry>`。`rebind()` 调用 `capture` 后对每个 `mounted.widget.bind`。原来的车辆、媒体、音量监听改成调 `rebind()`。`rebind()` 末尾仍调用现有的 `onAcStateChanged(VehicleController.state)`，避免空调外部变化不再弹窗。

预览 `render` 对每个条目 `mount(..., attachGestures = false)` 后立刻 `bind`。删除 `stripInteractions`。`candidateBitmap` 继续用单条目渲染，宽度仍是音乐 336、风速 `DockMetrics.FAN_COMBO_FIXED_WIDTH_PX`、其它 `fixedWidthFor`。

- [ ] **Step 2: 编译，并确认 BindKind 消失**

Run: `.\gradlew :app:compileDebugKotlin`

再在 `app/src/main/java` 搜索 `BindKind` 和 `DockEntryViewFactory`。Expected: 没有匹配，编译通过。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/car/dockbar/dock app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt app/src/main/java/com/car/dockbar/ui/DockEditorPreviewView.kt app/src/main/java/com/car/dockbar/ui/DockEntryViewFactory.kt
git commit -m "refactor: bind dock widgets from one snapshot"
```

### Task 7: 条目可以保存手势覆盖

**Files:**

- Modify: `app/src/main/java/com/car/dockbar/model/DockEntry.kt`
- Modify: `DockCatalog.mount`（把 `entry.gestures` 传进 `merge`）
- Test: `app/src/test/java/com/car/dockbar/dock/GestureMapTest.kt`（追加条目往返）
- Test: `DockCatalogTest` 不测 View。覆盖的合并已经在 Task 1。这里只测 `DockEntry` 往返。

**Interfaces:**

- Consumes: `GesturePatch`、`GestureMaps.parsePatch`、`GestureMaps.toJson`
- Produces: `DockEntry.gestures: GesturePatch? = null`。`toJson` 仅在非空时写入 `gestures`。`fromJson` 用 `optJSONObject("gestures")`。

- [ ] **Step 1: 写会失败的测试**

在 `GestureMapTest` 增加：

- 没有 `gestures` 的旧 JSON，`fromJson` 得到 `gestures == null`，`action` 仍是 `home`。
- `gestures` 是字符串 `"nope"` 时，条目仍解析成功，`gestures == null`，`label` 保持原值。
- 合法覆盖 `root.doubleTap = null`、`root.longPress = openRecents` 往返后，双击槽是 `Clear`，长按是 `OpenRecents`，单击键不在补丁里。
- `toJson` 在 `gestures == null` 时没有 `gestures` 键。

- [ ] **Step 2: 跑测试，确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.GestureMapTest`

Expected: 失败，`DockEntry` 还没有 `gestures`，或断言失败。

- [ ] **Step 3: 实现字段并接到 mount**

`mount` 使用 `GestureMaps.merge(widget.defaultGestures(), entry.gestures)`。不要在 `DockEntries.load` 里丢弃未知字段；`fromJson` 自己读 `gestures`。保存仍走现有 `toJson`。

- [ ] **Step 4: 再跑**

Run: `.\gradlew :app:testDebugUnitTest --tests com.car.dockbar.dock.GestureMapTest --tests com.car.dockbar.dock.DockCatalogTest`

Expected: 通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/car/dockbar/model/DockEntry.kt app/src/main/java/com/car/dockbar/dock/DockCatalog.kt app/src/test/java/com/car/dockbar/dock/GestureMapTest.kt
git commit -m "feat: store optional per-entry gesture overrides"
```

### Task 8: 编辑页改走注册表

**Files:**

- Modify: `app/src/main/java/com/car/dockbar/ui/DockEntriesActivity.kt`
- Modify: 若 `DockEntries.addBuiltin` 仍只接受 `Builtin`，加一个 `addWidget(ctx, action: String)`，用 `DockCatalog.of(action)` 的 spec 写 label 和 span。现有 `addBuiltin` 改成调用它。

**Interfaces:**

- Consumes: `DockCatalog.builtins()`、`spec.catalogIcon`、`spec.hidden`
- Produces: 添加列表不再调用 `Builtin.values()`。`builtinIcon` 删除。

- [ ] **Step 1: 换数据源**

`renderPool` 的内置候选：

```kotlin
DockCatalog.builtins()
    .filter { !it.spec.hidden && it.spec.action !in added }
    .map { w ->
        DockEntry(
            "b_${w.spec.action}", "builtin", w.spec.action, w.spec.label, span = w.spec.span
        )
    }
```

`bindIcon` 在 `kind == "builtin"` 时用 `DockCatalog.of(entry.action)?.spec?.catalogIcon ?: R.drawable.ic_car`。

确认 `DockEditorLongPress` 仍只挂在编辑页磁贴上，不挂到 `GestureInput`。

- [ ] **Step 2: 编译并搜索残留**

Run: `.\gradlew :app:testDebugUnitTest :app:compileDebugKotlin`

在 `app/src/main/java` 搜索 `BindKind`、`DockEntryViewFactory`、`builtinIcon`、`?: Builtin.HOME`。Expected: 单元测试和编译通过，这四项没有残留。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/car/dockbar/ui/DockEntriesActivity.kt app/src/main/java/com/car/dockbar/model/DockEntry.kt
git commit -m "refactor: list addable dock widgets from the catalog"
```

## 加一个新控件时改哪里

计划做完之后，和现有档位按钮同类的控件：

1. 在 `BuiltinWidgets.kt` 用 `iconWidget` 登记一行（图标、默认手势、`bind`）。
2. 把这个对象放进 `DockCatalog` 的列表。

不改 `DockOverlayService`、`DockEditorPreviewView`、`DockEntriesActivity`、`DockEntryViewFactory`。要进默认栏才改 `DockEntries.defaults()`。

全新动作才改三处，而且都在 `dock` 包里：`DockAction` 加一个分支、`DockActionCodec` 加对应 `type`、`DockActions.perform` 加执行。设置页以后只列出 `DockActionCodec` 能解码的 `type`，把用户选择写成 `GesturePatch`，放进该条目的 `gestures`。

## 自测清单

单元测试覆盖不了悬浮窗。执行完在车机或模拟器上对一遍，行为应与改前一致：

- 返回桌面单击回桌面，300ms 内双击只开最近任务。
- 内外循环单击立即切换，没有可以感觉到的等待。
- 主驾温度数字单击出温度弹窗，长按切 AC 并出现状态提示。副驾数字长按切双温同步并出现状态提示。
- 风速数字单击切 AUTO，长按关风速。两侧 ± 只单击。
- 通风、座椅加热、方向盘加热：单击降档，关着时跳到 3；长按升档，3 再按关。亮暗图标仍成对。
- 风向单击弹窗，长按打开空调。编辑预览里的风向图标和悬浮栏一致。
- 音乐卡单击打开播放器，播放键单击暂停，长按下一曲。
- 一条写了不存在 `action` 的条目显示车形占位，不会变成返回桌面。
- 手工把某条 `home` 的 JSON 写成 `gestures.root.doubleTap = null` 后，单击立即回桌面。
