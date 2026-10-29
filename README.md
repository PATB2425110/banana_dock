# CarDockBar · 车机透明 Dock 栏

一个跑在**安卓 9（API 28）**&#x8F66;机上的透明悬浮 Dock 栏应用，接入车机空调 / 座椅控制。

- 包名 / applicationId：**`com.car.dockbar`**（namespace 与源码目录一致）
- 悬浮窗尺寸：**1920 × 40 px**，窗口本体**全透明**，只有圆角底色条和图标
- **16 个控件**按 18 个等宽槽位严格均匀分布（音乐卡片占 3 槽）
- 车控走 **ECarX / 吉利 IGlyCar 反射通道**（编译期零依赖厂商 jar）
- 音乐信息读 **系统 MediaSession**（任何播放器都能显示封面 / 歌名 / 歌手）

---

## 一、布局：13 个控件如何均匀分布

需求是「音乐信息占 3 个图标位置，其余控件均匀分布」。加上风速档位后共 **16 个控件**，  
于是把整条 1920px 切成 **18 个等宽槽位**：

```
单槽宽度 = 1920 / 18 ≈ 106.67 px      （18 除不尽，代码按四舍五入累积，相邻槽宽差 ≤ 1px）
```

| 序号 | 控件         | 占槽    | 宽度        | x 起点      |
| -- | ---------- | ----- | --------- | --------- |
| ①  | 返回桌面       | 1     | 106.7     | 0         |
| ②  | **音乐信息卡片** | **3** | **320.0** | **106.7** |
| ③  | 车机设置入口     | 1     | 106.7     | 426.7     |
| ④  | 主驾空调调低     | 1     | 106.7     | 533.3     |
| ⑤  | 主驾当前温度     | 1     | 106.7     | 640.0     |
| ⑥  | 主驾空调调高     | 1     | 106.7     | 746.7     |
| ⑦  | 空调风向设置     | 1     | 106.7     | 853.3     |
| ⑧  | 主驾通风       | 1     | 106.7     | 960.0     |
| ⑨  | **风速调低**   | 1     | 106.7     | 1066.7    |
| ⑩  | **当前风速档位** | 1     | 106.7     | 1173.3    |
| ⑪  | **风速调高**   | 1     | 106.7     | 1280.0    |
| ⑫  | 内外循环切换     | 1     | 106.7     | 1386.7    |
| ⑬  | 座椅设置入口     | 1     | 106.7     | 1493.3    |
| ⑭  | 副驾空调调低     | 1     | 106.7     | 1600.0    |
| ⑮  | 副驾当前温度     | 1     | 106.7     | 1706.7    |
| ⑯  | 副驾空调调高     | 1     | 106.7     | 1813.3    |

合计 = 18 × 106.67 = **1920 px** ✔

实现方式：`ui/DockLayout.kt` 是一个自定义 `ViewGroup`，每个子 View 带一个 `span`，  
`onMeasure` 里按 `left = width * 累计span / 总span` 精确切分，因此换任何分辨率都不会错位。

分组竖分隔线**不占布局宽度**，由 `DockLayout.onDraw` 直接画在槽位边界上（上下各留 28% 空白），  
所以加了分隔线也不会破坏均匀分布。分隔线共 6 处：②③⑦⑫⑬⑭ 之前。

---

## 二、工程结构

```
CarDockBar/
├── settings.gradle / build.gradle / gradle.properties / local.properties
├── gradle/wrapper/…                          Gradle 8.9
└── app/
    ├── build.gradle                          AGP 8.7.3 · Kotlin 2.0.21 · compileSdk 34 · minSdk 28
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/car/dockbar/
        │   ├── BootReceiver.kt               开机自启
        │   ├── config/VehicleChannels.kt     ★ functionId / zone 配置表（可运行时改）
        │   ├── media/
        │   │   ├── DockNotificationListener.kt   取通知使用权只为读 MediaSession
        │   │   └── MediaBridge.kt            ★ 封面 / 歌名 / 歌手 / 播放状态
        │   ├── ui/
        │   │   ├── DockLayout.kt             ★ 15 槽位均匀布局 + 圆角底 + 分隔线
        │   │   ├── DockItemViews.kt          ★ 图标按钮 / 温度显示 / 音乐卡片
        │   │   ├── DockOverlayService.kt     ★ 1920×40 透明悬浮窗前台服务
        │   │   └── MainActivity.kt           ★ 配置页 + 通道调试页 + 日志
        │   ├── util/{Logx,Prefs,IntentLauncher}.kt
        │   └── vehicle/
        │       ├── VehicleBridge.kt          ★ 反射桥接（多签名自动探测）
        │       └── VehicleController.kt      ★ 空调 / 座椅高层封装
        └── res/{layout,drawable,values,mipmap-*}
```

---

## 三、车控接口怎么接的

参考工程里已反编译验证过：oneOS Dock 的风量走  
`com.ecarx.xui.adaptapi.car.Car` → `getICarFunction()` → `getFunctionValue(functionId, zone)`，  
`functionId` 高位表示功能、低 8 位表示取值枚举：

```kotlin
val value = functionId + index            // index = 档位
// 例：手动风量 func = 0x10020100，1 档 → 0x10020101
```

### 已知（来自接口文档 / 反编译，已确认 functionId）

| 功能        | functionId   | 来源                              |
| --------- | ------------ | ------------------------------- |
| 主驾风量（手动）  | `0x10020100` | HVAC_FUNC_FAN_SPEED（已验证）        |
| 主驾风量（自动）  | `0x10020200` | HVAC_FUNC_AUTO_FAN_SETTING（已验证） |
| 主驾/副驾空调温度 | `0x10060100` | HVAC_FUNC_TEMP（zone 区分主/副驾）     |
| 内外循环      | `0x10030100` | HVAC_FUNC_CIRCULATION           |
| 空调风向      | `0x10070100` | HVAC_FUNC_BLOWING_MODE          |
| 主驾座椅通风    | `0x10050000` | HVAC_FUNC_SEAT_VENTILATION      |
| 主驾座椅加热    | `0x10050100` | HVAC_FUNC_SEAT_HEATING          |

> 温度 / 风向的 functionId 已确认，但**取值编码（低 8 位如何表示温度档位 / 风向枚举）**  
> 与系统子功能（TEMP_MIN/MAX/DUAL、SWEEPING/DIRECTION_MODE）存在歧义，仍需上车实测修正解析逻辑。

### 默认通道表（`VehicleChannels.kt`）

| 功能     | 默认 functionId | 默认 zone | 取值编码可信度                           |
| ------ | ------------- | ------- | --------------------------------- |
| 主驾温度   | `0x10060100`  | `0x8`   | functionId 已确认；解析待实测              |
| 副驾温度   | `0x10060100`  | `0x10`  | functionId 已确认；解析待实测              |
| 空调风向   | `0x10070100`  | `0x8`   | functionId 已确认；解析待实测              |
| 内外循环   | `0x10030100`  | `0x8`   | ★已确认：`setFunctionValue(0x10030100, 0x8, 1)` = 开内循环，value 裸枚举 0=外 / 1=内 |
| 主驾座椅通风 | `0x10050000`  | `0x8`   | functionId+0..3，建议实测              |
| 主驾座椅加热 | `0x10050100`  | `0x8`   | functionId+0..3，建议实测              |
| 副驾座椅通风 | `0x10050000`  | `0x10`  | 复用 SEAT_VENTILATION + 副驾 zone，待实测 |

> ID 来源于反编译 `oneOS_Hvac.apk` 得到的 `com.geely.os.car.GlyCarPropertyIds` 常量表  
> （与 `IHvac.HVAC_FUNC_*` 等价）。
>
> 修正有两处，改完立即生效、无需重新编译：
>
> 1. 主界面「车控通道」区直接改 functionId / zone → 点「保存全部通道」
> 2. 或者改 `VehicleChannels.kt` 的 `defFunc` 默认值后重新编译
>
> 调试建议：点每行的「读」看能否读到合理值；某功能读不到时，  
> 先试相邻值，或把 zone 换成 `0x8 / 0x10 / 0x4` 试。  
> 日志里会打出后端类型和最终使用的读写方法签名，方便定位。

### 通道自动探测

`VehicleBridge` 不做硬编码依赖，而是启动时依次尝试：

1. `com.ecarx.xui.adaptapi.car.Car#create(Context)` → `getICarFunction()`
   - 读：`getFunctionValue(int,int)` / `getIntProperty(int,int)`
   - 写：依次试 `setFunctionValue(int,int,int)`、`setFunctionValue(int,int,Object)`、  
     `setIntFunctionValue(int,int,int)`、`setFunctionIntValue`、`setIntProperty(int,int,int)`
   - 事件：`registerFunctionValueWatcher(int[], IFunctionValueWatcher)`（动态代理回调）
2. 失败则试 `com.geely.os.car.GlyCar#create(Context)` → `IGlyCar#getIntProperty / setIntProperty`
3. 类不在 classpath 时从 `/system/framework/ecarx.adaptapi.jar` 用 `DexClassLoader` 加载

结论会打进运行日志（主界面底部），例如：

```
车控通道就绪：ECarX adaptapi / ICarFunction
后端=ECARX  读方法=getFunctionValue(int,int)  写方法=setFunctionValue(int,int,Object)
```

---

## 四、温度显示怎么算

车机给的原始值通常是「温度档位索引」，需要换算成摄氏度。默认：

```
摄氏温度 = tempBase + (索引 - tempIndexBase) × tempStep
默认：tempBase = 17.5，tempStep = 0.5，tempIndexBase = 1
→ 索引 1 = 17.5℃，索引 2 = 18.0℃，…
```

在「温度换算」区改这三个数并保存即可适配不同车型。

---

## 五、16 个控件的行为

| 控件          | 单击                 | 长按       |
| ----------- | ------------------ | -------- |
| ① 返回桌面      | 发 HOME intent      | —        |
| ② 音乐卡片      | 打开当前播放器            | —        |
| ② ↳ 播放/暂停按钮 | 播放 / 暂停            | 下一曲      |
| ③ 车机设置      | 跳转（配置的包名 → 内置候选回退） | —        |
| ④ 主驾降温      | 温度 −1 档            | —        |
| ⑤ 主驾温度      | 打开空调面板             | 立即刷新车控数据 |
| ⑥ 主驾升温      | 温度 +1 档            | —        |
| ⑦ 空调风向      | 配了入口就跳转，否则循环切换风向   | 循环切换风向   |
| ⑧ 主驾通风      | 0→1→2→3→关 循环       | 直接关闭     |
| ⑨ 风速调低      | 风量 −1 档            | —        |
| ⑩ 当前风速档位    | 打开空调面板             | 立即刷新车控数据 |
| ⑪ 风速调高      | 风量 +1 档            | —        |
| ⑫ 内外循环      | 内 / 外循环切换（激活态变强调色） | —        |
| ⑬ 座椅设置      | 跳转（配置的包名 → 内置候选回退） | —        |
| ⑭ 副驾降温      | 温度 −1 档            | —        |
| ⑮ 副驾温度      | 打开空调面板             | 立即刷新车控数据 |
| ⑯ 副驾升温      | 温度 +1 档            | —        |

### 风速档位怎么显示

复用与温度同一套 `DockValueView`（大号主值 + 小号副值 + 小号单位）：

| 模式  | 显示             | 说明                               |
| --- | -------------- | -------------------------------- |
| 手动  | `4` + 小号 `档`   | 1~9 档，取自 `0x10020100`            |
| 自动  | `3` + 小号蓝色 `A` | 1~5 档，取自 `0x10020200`，`A` 用强调色区分 |
| 无数据 | `--`           | 车控未连接                            |

手动/自动由「自动风量通道返回值是否有效」自动判定（返回 0 / 255 视为无效，退化为手动）。  
风量的这两个 functionId 是**接口文档里已验证过的**，不用校准。

---

## 六、配置页说明（MainActivity）

- **启用 Dock 悬浮栏**：开启 / 关闭前台服务
- **贴屏幕底部**：不勾选则贴顶部
- **车机屏幕高 px**：贴底定位用，1920×1080 就填 1080（不查询系统，纯按配置算）
- **模拟模式**：不连车控，用内置数据调 UI（手机 / 模拟器上也能看效果）
- **宽 1920 / 高 40 / 距边 0**：窗口尺寸与偏移，填 0 宽度 = 铺满屏幕
- **窗口整体不透明度**、**底色不透明度（0 = 完全透明）**、**圆角**、**图标文字缩放**、**分组分隔线**
- **跳转入口**：③车机设置 / ⑩座椅设置 / ⑦空调面板的包名 · Activity · action
- **温度换算**：索引 ↔ 摄氏度
- **车控通道**：每条通道的 functionId / zone 可改，带「读」「写」按钮现场验证
- **运行日志**：反射探测结果、每次读写、异常，全部在车机上直接可见

---

## 七、编译

环境：JDK 17+（本机用的是 Android Studio 自带 JBR 21）、Android SDK（platform 34）。

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="C:/Users/<你>/AppData/Local/Android/Sdk"

cd CarDockBar
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/CarDockBar-debug.apk
```

也可以直接用 Android Studio 打开 `CarDockBar` 目录运行。

---

## 八、安装到车机

```bash
adb install -r CarDockBar-debug.apk
```

首次运行需在配置页完成两件事：

1. **授权悬浮窗权限** → 点「授权悬浮窗权限」跳系统设置打开
2. **授权通知使用权**（音乐信息需要）→ 点「授权通知使用权」打开

> 车机通常对车控接口有**签名 / 白名单**限制。如果日志里显示  
> 「车控通道不可用」，说明当前 APK 没有访问车控服务的权限，  
> 需要把包名加进厂商白名单，或用平台签名重新签名后安装。

---

## 九、目标屏幕参数

| 项目  | 值                                |
| --- | -------------------------------- |
| 尺寸  | 12 英寸                            |
| 分辨率 | **1920 × 1080**                  |
| DPI | 160（density = 1.0，**1dp = 1px**） |

### 实现约定：一切以给定的 1920×1080 为准，不查询系统显示参数

- Dock 宽高默认 **1920 × 40 px**，铺满屏宽，贴在底部。
- 贴底部的 y 坐标 = **屏幕高(1080) − 高(40) − 距边(0) = 1040**。  
  「屏幕高」是配置页里写死的常量（默认 1080），  
  **不读 `displayMetrics`、不做系统栏探测、不做自动避让**——按车机实际分辨率来。
- 所有绘制（图标尺寸、字号、圆角、分隔线）都用 **px**  
  （`TypedValue.COMPLEX_UNIT_PX`、XML 里也是 `px`），  
  160 DPI 下 **1dp = 1px**，严格 1:1 呈现，不会被系统缩放。

### 40px 会不会太细

12 英寸上 40px ≈ 5.5mm 高。嫌细就改配置页这两个值：

- **高 px**：40 → 56 之类
- **图标 / 文字缩放**：同步往上拉

---

## 十、已知待办

- 温度 / 风向 / 循环 / 座椅的 functionId 是**推断值**，必须上车实测后修正
- ③车机设置、⑩座椅设置的包名需要你确认后填入配置页
- 若车型的「主驾/副驾」不是按 zone 区分而是两套 functionId，把 `VehicleChannels.kt` 里  
  对应通道的 `defZone` 改成同一个值、`defFunc` 改成两套 ID 即可
