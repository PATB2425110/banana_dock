# CarDockBar 项目完整交接文档

> **历史文档提示（2026-11-11）**：本文件记录 2026-10-20 的交接现场，以下版本、设备状态及问题结论不是当前实时状态。当前已到 **Banana_Dock 4.0（434）**，请先读 [CURRENT_STATUS.md](CURRENT_STATUS.md) 和 [README.md](README.md)，再将本文件用作车控与故障排查背景资料。

> 交接时间：2026-10-20 01:15　版本：v1.4.48 (versionCode 54)
> 本文档写给接手的 AI/开发者，包含项目全部已知信息、踩坑记录和日志获取方法。

---

## 1. 项目是什么

吉利 oneOS 车机（ECarX 平台，Android 9/API 28）上的**自定义 Dock 栏**应用：
一个 TYPE_APPLICATION_OVERLAY 悬浮条，常驻屏幕底部，提供空调控制、座椅控制、媒体控制、
快捷应用、歌词悬浮窗等能力。通过 ECarX adaptapi 的 `ICarFunction` 反射调用厂商车控 HAL。

- 代码：`C:\Users\17900\WorkBuddy\2026-10-07-19-43-58\CarDockBar`
- 包名：`com.car.dockbar`，minSdk 28 / targetSdk 34，Kotlin，无 Compose（纯 View + WindowManager）
- 测试设备：小米平板（adb 序列号 `ee72592e`，模拟车控环境，后端=NONE）+ **用户真车**（ECarX 车机，屏 1920×1080，density 2.25）

## 2. 构建与安装

```bash
# 本机默认 java 是 JDK8，Gradle 8.7.3 需要 JDK11+，必须指定 Android Studio 自带 JBR：
cd C:\Users\17900\WorkBuddy\2026-10-07-19-43-58\CarDockBar
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug

# 装到平板（读取类操作允许；禁止操控用户平板，见 §8 红线）：
adb -s ee72592e install -r app/build/outputs/apk/debug/CarDockBar-debug.apk
```

APK 交给用户即可装到真车车机（用户自己操作，不要试图 adb 连车机）。

## 3. 代码结构（关键文件）

| 文件 | 职责 |
|---|---|
| `App.kt` | 启动降级阶梯：连续崩溃 streak≥2 → 关车控写入（仅读取）；≥3 再降；版本变更重置 |
| `BootReceiver.kt` | 开机自启（safeLevel≥2 不拉起） |
| `ui/DockOverlayService.kt` | 主悬浮窗服务；车控绑定、状态弹窗逻辑（onAcStateChanged）、长按行为（toggleAcFromDock / toggleDualSync） |
| `ui/DockEntryViewFactory.kt` | Dock 条目构建；comboValue 组合按钮（− 值 +，占 3 槽） |
| `ui/DockIconView / DockValueView` | 图标/数值控件；`accentColor` = 蓝色高亮（激活态统一用它） |
| `ui/AirDirectionPopup` | 风向弹窗（三开关，所有弹窗的样式基准） |
| `ui/TemperaturePopup.kt` | 主/副驾温度弹窗（zone 区分）：左栏温度滚轮（29 项 ScrollView + 固定居中半透明遮罩 + 停止吸附 + LO/HI 首尾格），右栏主驾=前挡/后挡/极速降温/方向盘加热，副驾=双温同步/车内温度/车外AQI/车内AQI |
| `ui/SeatPopup.kt` | 座椅弹窗：座椅1/2/3（记忆）+ 主副驾通风/加热档位（关→3→2→1→关 循环） |
| `ui/AppDrawerPopup.kt` | 快捷应用 2×3 面板（toggle 开关式，宽 510dp×2.5 后 clamp 屏宽） |
| `ui/StatusPopWindow.kt` | 状态提示浮窗（30sp 两行、FLAG_NOT_TOUCHABLE 触摸透传） |
| `ui/DockPopupChrome.kt` | 弹窗统一规范：贴锚点、点外面关、无 × 按钮、ACTION_OUTSIDE |
| `ui/DockStatusLabel.kt` | Dock 上方状态小字（**已无任何触发点，机制保留**） |
| `ui/MainActivity.kt` | 设置页；「更多设置」3 个入口为原地切换（enterSection/exitSection），不再 startActivity |
| `vehicle/VehicleController.kt` | 车控状态机（State data class、乐观更新、watcher 分发、所有读/写方法、写入守卫） |
| `vehicle/VehicleBridge.kt` | ECarX ICarFunction 反射桥（读/写/watcher/supportOf 探测）；Customize 系列已禁用 |
| `vehicle/SensorBridge.kt` | ISensor.getSensorLatestValue 反射桥（AQI/车内外温度），失败自动重试 |
| `config/VehicleChannels.kt` | 通道配置表（key → funcId/zone/min/max），注释里有大量实测记录 |
| `util/Logx.kt` + `util/LogUploader.kt` | 日志（内存环形 + 文件）+ 自动上传 + 崩溃计数 |
| `log_upload_service/server.py` | 日志接收服务端（纯标准库 Python，部署在云端） |

## 4. 车控知识（真车实测，最值钱的部分）

### 4.1 写入口径（血泪教训）
- **`setFunctionValue(funcId, areaId, 裸值)`**——第三参是裸枚举/裸档位，不是复合编码
- 布尔通道：`1/0`（部分 HAL 也认 `10/11`）；档位：`0..3`；风量：`1..9`
- 温度：int 通道，低 8 位 = `round(℃ / 0.2)`（21.0℃ → 105），范围 15.5~28.5 步进 0.5
- 哨兵值：`0xfd=ERROR、0xfe=NONE、0xff=UNKNOWN`（读到这些 = 无效，保留原状态）

### 4.2 真车 funcId/zone 实测表（2026-10-20 日志实锤）
| 功能 | funcId | zone | 备注 |
|---|---|---|---|
| 风量手动 | 0x10020100 | 0x8 | |
| 风量 AUTO | 0x10020200 | 0x8 | |
| 内外循环 | 0x10030100 | **0x80000000** | 事件 zone=-2147483648；**我们配置 zone=8 读不到（遗留：读一直 0xff，靠事件更新）** |
| 风向 | 0x10070100 | 1/4/8 | 位掩码 1=吹脸 2=吹脚 4=前挡 |
| AC | 0x10010300 | **0x80000000** | |
| 座椅通风 | 0x10050100 | 1=主驾 / 4=副驾 | |
| 座椅加热 | 0x10050200 | 1 / 4 | |
| 座椅按摩 | 0x10050700 | 1 / 4 | 未接 |
| 温度 | 0x10060100 | 1 / 4 | **事件回调送 float（22.5）**；主动 getCustomize 会崩 |
| 双温同步 | 0x10060500 | — | |
| **前除霜** | **0x10040200** | **0x80000000** | ★ v1.4.48 修正：不是 0x10040100（常量表与事件映射表不一致，以事件映射为准） |
| 后除霜 | 0x10040300 | 0x80000000 | |
| 方向盘加热 | 0x10090100 | 0x80000000 | |
| 座椅记忆 | 0x2d400200 | ? | 写位置号 1/2/3，真车未验证 |
| 车内温度 | 0x100c00 | ISensor | |
| 车外温度 | 0x100b00 | ISensor | |
| 车内 AQI | 0x100500 | ISensor | |
| 车外 AQI | 0x100400 | ISensor | |

### 4.3 ⚠️ native 崩溃红线（最重要的经验）
1. **`getCustomizeFunctionValue` / `setCustomizeFunctionValue` 在普通 App 上主动调用 = 进程必崩**
   （厂商库 native SIGSEGV，Java 层无法捕获）。事件回调 `onCustomizeFunctionValueChanged` 是安全的。
2. **对 HAL 不支持的 (funcId, zone, value) 调 `setFunctionValue` 也会 native abort**
   （v1.4.46/47 时期用户「闪退 2-3 次才能打开」就是这个——连续崩 2 次后写入熔断生效才正常）。
3. **防御**：`VehicleController.writeBareGuarded()` —— 写前 `VehicleBridge.supportOf(func, zone)`
   探测 HAL 支持状态，**非 "active" 一律不写**。新增任何未验证信号的写入都必须走这个守卫。
4. 读取（`getFunctionValue`）返回哨兵不崩，安全；ISensor.getSensorLatestValue 真车已读过（未崩）。
5. 熔断恢复：设置页没有手动恢复入口；**版本变更（升级 APK）自动重置**，或清 App 数据。

### 4.4 事件监听
`registerFunctionValueWatcher(int[], watcher)` 数组模式，当前注册：
0x10020100, 0x10020200, 0x10030100, 0x10070100, 0x10010300, 0x10050100, 0x10050200, 0x10060100。
回调 `onFunctionValueChanged(id, area, val)` 按日志分派。**注意事件 area 可能是 0x80000000（全局）**。

## 5. 日志系统与获取方法

### 5.1 App 自动上传（主通道）
- App「设置 → 日志」页可开关上传、改上传地址
- **默认端点**：`https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run`
- 服务端源码：项目根 `log_upload_service/server.py`（纯标准库 Python）；本地局域网版 `log_server.py`（8080 端口）
- App 会自动上传：内存日志（diag）、上次会话日志（prev_session）、**native crash 文件（crash）**

**AI 远程拉日志：**
```bash
# 最近 200 条（JSON，含 received_at）
curl -s https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/logs
# 全部历史日志（纯文本按天分隔）
curl -s https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/download -o logs.txt
```
⚠️ **重新发布服务端会换分享链接（子域名哈希变化，旧链接立即失效）**——每次重新发布必须：
① 验证新链接可达 ② 把新地址同步进 `LogUploader.DEFAULT_URL` ③ 重打包，否则 APK 对着死地址上传。

### 5.2 平板本地（adb 直连）
```bash
# 实时日志（tag = CarDockBar）：
adb -s ee72592e logcat -s CarDockBar
# 应用私有目录日志（debug 包可 run-as）：
adb -s ee72592e shell run-as com.car.dockbar ls files/logs/
adb -s ee72592e shell run-as com.car.dockbar cat files/logs/<当天>.log
# native crash 文件：
adb -s ee72592e shell run-as com.car.dockbar cat files/logs/native_crash.txt
```

### 5.3 真车车机本地
crash 详情文件 `files/logs/native_crash.txt` 在车机本地，**服务端只存了文本日志、没有存 crash 文件内容**
（/download 里搜不到 SIGSEGV/backtrace）。要看 native 崩溃堆栈：让用户在车机文件管理器把
`Android/data/com.car.dockbar/files/logs/native_crash.txt` 传出来，或加一个「上传 crash 原文」的日志条目。

### 5.4 崩溃自动降级（排障时必须知道）
- App 启动时：上次会话没活过 15 秒 → streak+1；streak≥2 → 关车控写入
- **用户看到「闪退几次后才正常」= 熔断生效，不是 bug 修好了**——先看日志确认崩溃原因
- 版本升级自动重置计数和写入开关

## 6. UI 规范（用户定下的，不要破坏）

- **弹窗统一规范**（DockPopupChrome）：贴触发图标、点外面关闭、**无 × 按钮**、超时自动消失
- **激活状态 = 字体变蓝**（DockIconView.accentColor）
- **弹窗触发全部是 toggle**（开着就关/关着就开），不要用时间戳抑制 show
- **状态弹窗**：改了什么显示什么（逐字段对比），FLAG_NOT_TOUCHABLE 透传不挡操作
- **长按主驾温度** = 切 AC + 弹「AC 开/关」；**长按副驾温度** = 切双温同步 + 弹「双温同步 开/关」（各单项）
- Dock 上方状态小字（DockStatusLabel）**已全部移除，不要再恢复**
- 弹窗尺寸自适应：SCALE = min(2.5, 屏幕可用高/内容自然高)（车机 1080p 屏放不下 2.5 倍）
- 设置页「更多设置」入口是**原地切换**（enterSection），返回键回主设置页并恢复滚动位置

## 7. 用户工作习惯

- 汇报要简短直接；他测出问题会立刻反馈，往往一句话描述（注意口语歧义，必要时确认）
- **禁止操控他的平板**（tap/swipe/解锁/改系统设置），只许读取类诊断（logcat/dumpsys/读 prefs）和安装 APK
- 他会自己传日志（App 内上传），然后说「传日志了/我穿日志了」→ 拉端点分析
- 版本号 1.4.x 递增，每次改动都要构建+安装+让他真车验证

## 8. 当前遗留问题 / 待验证

1. **前挡/后挡加热、方向盘加热、座椅记忆写入**：v1.4.48 已修正 funcId/zone 并加守卫，但**真车是否真正生效未验证**——等用户测试日志看「写 xxx 跳过：HAL 支持状态」和 HVAC_WRITE 行
2. **AQI/车内外温度数字“不对”**：v1.4.48 修了 SensorBridge 重试，真车读到的原始值在日志「SensorBridge 探测」行——数字具体差什么（值域？系数？信号 ID？）等用户反馈
3. **内外循环读取**：配置 zone=8 实际读不到（真车 zone=0x80000000）——一直靠事件更新显示，**未修**（用户没报问题，动它有风险）
4. **座椅记忆 0x2d400200**：写位置号的编码未验证
5. **极速降温**：无独立信号，用组合动作（AC 开+风量最大+温度最低）模拟
6. 歌词悬浮窗功能长期没动，可能有兼容性问题

## 9. 外部资料（用户桌面上，非常重要）

| 文件 | 内容 |
|---|---|
| `C:\Users\17900\Desktop\DBPLAY改\车机系统app\反编译参考.md` | HVAC 156 项功能全表、GlyCarAreaId 区号全表、接口签名、坑列表 |
| `C:\Users\17900\Desktop\DBPLAY改\研究结果\Dock桌面关系与车辆信号研究报告.md` | oneOS Dock 机制、ISensor 传感器信号全表、smali 级证据 |
| `C:\Users\17900\Desktop\DBPLAY改\研究结果\空调\空调桌面HUDDOCK反编译参考.md` | 空调桌面反编译 |
| `C:\Users\17900\Desktop\饺子互联\DOCCCK\*.png` | 三张弹窗设计示意图（主驾/副驾温度、座椅） |

## 10. 项目记忆文件

- 工作区记忆：`C:\Users\17900\WorkBuddy AI\2026-10-15-20-30-09\.workbuddy-ai\memory\2026-10-19.md`（逐版本改动记录，**每个版本干了什么全在里面**）
- 更早的项目路径：`C:\Users\17900\WorkBuddy\2026-10-07-19-43-58\CarDockBar`（注意与工作区路径不同）
