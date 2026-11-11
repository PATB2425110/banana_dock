# CarDockBar 项目交接文档（Handoff）

> **2026-11-11 当前状态说明**：本文后续章节大部分为历史排查记录，包含旧版本的崩溃结论、构建要求与功能状态，不应直接当作当前故障。当前版本、实现与约束请先读 [CURRENT_STATUS.md](CURRENT_STATUS.md) 和 [README.md](README.md)。历史资料保留，不覆盖或删除原始结论。

> 写给接手本项目的下一位 AI。本文档汇总了当前版本的已知事实、已验证结论、改动历史与未决问题。
> 配套记忆文件在 `C:/Users/17900/WorkBuddy/2026-10-10-19-02-51/.workbuddy/memory/`（按日期归档），本文件是综合提炼版。

---

## 0. 一句话现状

- 当前版本 **4.0**（versionCode 434），应用名 `Banana_Dock`，包名 `com.car.dockbar`。
- 当前交付包名为 `Banana_Dock-4.0.apk`；设置页、诊断页、常驻应用页和 Dock 图标管理页已完成新版布局。
- GitHub 仓库：`https://github.com/PATB2425110/banana_dock.git`，主分支为 `master`。

- ✅ **温度显示已修对**：确认本车 HVAC 温度是整数原始值 × 0.2℃（如 128→25.6℃、119→23.8℃），开关用 10/11 枚举。
- ❌ **仍「打开约 1 秒就闪退」**：根因高度怀疑是 `registerWatcher` 用**动态代理**注册到 ECarX 原生 HAL，系统第一次回调时触发 **native 层崩溃**（Java 层 `runCatching` 兜不住）。
- ⏸️ ~~**日志上传功能尚未实现**：卡在「车机要 POST 到哪个地址」~~ →
  **✅ 2026-10-15 已实现**（HTTPS 收集端点 + 落盘回捞），详见第 7 节。
  用户坚持**只读、不改设备**，且人**不在车上**，所以走 App 内 HTTP 上传，未使用 ADB。

---

## 1. 项目基本信息

| 项         | 值                                                                                                                                                                 |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 项目路径      | `C:/Users/17900/WorkBuddy/2026-10-07-19-43-58/CarDockBar/`                                                                                                        |
| 应用包名      | `com.car.dockbar`（`app/build.gradle` 中 `namespace` / `applicationId`）                                                                                             |
| 形态        | Android 车机（Automotive / ECarX）悬浮 Dock 栏 + 调试 Activity                                                                                                             |
| 构建        | Gradle wrapper；当前 Java/Kotlin 目标为 **17**，使用 Android Studio 自带 JBR；旧版 JDK 要求只适用于历史构建 |
| 构建命令      | 项目根目录下 `./gradlew assembleDebug --no-daemon`                                                                                                                      |
| 产物        | `app/build/outputs/apk/release/Banana_Dock-4.0.apk`；Debug 为 `Banana_Dock-4.0-debug.apk`                                                                                                      |
| 当前已交付 APK | `Banana_Dock-4.0.apk`（Release，versionCode 434）                                                                                                                 |
| 权限        | `INTERNET`、`ACCESS_NETWORK_STATE` 已在 Manifest；debug 包可 `run-as`                                                                                                   |
| 模拟模式      | `Prefs.simulate=true` 时不连车控，用假数据调 UI                                                                                                                              |

---

## 2. 应用功能概览

- **悬浮 Dock 栏**（`TYPE_APPLICATION_OVERLAY`）：常驻车机屏幕底部，展示
  - 主驾/副驾温度、风量（手动档/AUTO）、内外循环、座椅通风/加热、风向；
  - 音乐卡片（封面/歌名/歌手/播放暂停）；
  - 歌词悬浮窗（独立 `LyricOverlayService`）。
- **车控读写**：通过厂商 HAL 读/写空调、座椅。编译期**不依赖任何厂商 jar**，全靠反射（`VehicleBridge`）。
- **媒体**：`MediaSessionManager.getActiveSessions()` + `NotificationListenerService`（需要「通知使用权」权限）。
- **调试页** `MainActivity`：车控通道表（每通道 读/写 按钮 + 原始 hex）、各项设置、崩溃日志显示。

### 当前设置页结构

- `悬浮 Dock 栏`：窗口尺寸、运行权限、显示与唤出。
- `外观与风格`：背景形态、比例、日夜透明度、分隔线。
- `Dock 图标管理`：实际 Dock 预览、长按拖拽添加/排序/移除、第三方应用独立添加。
- `歌词悬浮窗`：歌词显示和来源设置。
- `常驻应用设置`：实际安装应用的系统/个人双栏网格、搜索、常驻勾选。
- `诊断与日志`：运行概况、诊断结果、上传、车控开关、诊断操作和可手动滚动的日志。

设置页内容底部固定让出 128px 给 Dock，页面整体使用 0.9 倍显示；日志/车控开关的设置行为没有因 UI 重排而改变。

---

## 3. 车控通道架构（核心文件）

| 文件                                     | 职责                    | 关键点                                                                                                                                                                                                                                                          |
| -------------------------------------- | --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `app/.../vehicle/VehicleBridge.kt`     | 反射桥接 ECarX / 吉利 HAL   | `init()` 创建 `Car` → `ICarFunction`（或吉利 `IGlyCar`）；`session` 持有实例；`readInt/readFloatProperty/writeInt/writeFloatProperty` 反射调用；`registerWatcher` 用**动态代理**注册 `IFunctionValueWatcher`；新增 `isBinderDead` 在 `DeadObjectException/RemoteException` 时 `reset()`+重连 |
| `app/.../vehicle/VehicleController.kt` | HVAC/座椅高层状态机          | `init()`、`registerWatcher()`、`refresh()`（轮询所有通道）、`withRaw()`（原始值→State）、`tempFromRaw()`（温度多编码解析）、`boolOf()`（10/11枚举归一）、`tempToRaw()`（**写温度，仍用旧公式，见第 8 节坑**）、各操作（tempStep/fanStep/toggleRecirc/cycleAirDirection/seat*）                                       |
| `app/.../config/VehicleChannels.kt`    | 通道→functionId/zone 映射 | `funcOf(ctx,key)`、`zoneOf(ctx,key)`；key 如 `fan_manual`/`fan_auto`/`recirc`/`air_direction`/`seat_vent`/`seat_heat`/`temp_driver`/`temp_passenger`                                                                                                            |
| `app/.../ui/DockOverlayService.kt`     | 悬浮窗 + 定时刷新            | `ticker` 循环 `REFRESH_INTERVAL_MS = 2500ms` 调 `VehicleController.refresh` + `MediaBridge.refresh`；`bindVehicle()` 把 State 渲染到各 View；`buildDock()`/`reload()`                                                                                                  |
| `app/.../ui/DockItemViews.kt`          | 数值/图标渲染               | `renderTemp()`（**已加 HI/LO**）、`renderFan()`、`RecircDrawable` 等                                                                                                                                                                                                |
| `app/.../media/MediaBridge.kt`         | 媒体会话读取                | `start()/refresh()/rebind()`；`hasPermission()` 检查通知使用权                                                                                                                                                                                                       |
| `app/.../util/CrashHandler.kt`         | 崩溃捕获                  | `Thread.setDefaultUncaughtExceptionHandler` → 写 `filesDir/last_crash.txt` + **弹 Toast 显示异常类与信息**（sleep 2.5s 让其渲染）                                                                                                                                            |
| `app/.../App.kt`                       | Application           | `onCreate` 里 `CrashHandler.install(this)`                                                                                                                                                                                                                    |
| `app/.../util/Prefs.kt`                | 全部配置                  | 见第 6 节新增键                                                                                                                                                                                                                                                    |
| `app/.../ui/MainActivity.kt`           | 调试 UI                 | 通道表、设置项、`updateStatus()`、崩溃日志显示                                                                                                                                                                                                                              |

> **线程模型**：`VehicleController` 用主线程 `Handler(Looper.getMainLooper())`；`refresh()` 里的 `VehicleBridge` 读操作在**主线程**同步执行（目前读成功，温度正常）。`DockOverlayService.ticker` 在主线 postDelayed 循环。watcher 回调里 `main.post { runCatching{...} }`。

---

## 4. 已确认的车机编码（最重要，别再踩坑）

后端确认 = **ECarX**（`com.ecarx.xui.adaptapi.car.Car` → `ICarFunction`）。来源：用户贴的第三方 ESP32-S3 破解读数 JSON：

```json
{"ok":true,"path":"/ac/ac/on","status":{
  "initialised":true,
  "backend":"com.ecarx.xui.adaptapi.car.Car",
  "power":11,"ac":10,"recirc":11,
  "fan":<大数>,"leftTemp":128,"rightTemp":119}}
```

### 4.1 开关枚举 = 10/11（不是 0/1）

- `10 = OFF`，`11 = ON`。`boolOf(raw)` 同时兼容 `0/1` 与 `10/11`：
  ```kotlin
  private fun boolOf(raw: Int): Boolean? = when (raw and 0xFF) {
      0, 10 -> false
      1, 11 -> true
      else -> null   // 含 255 哨兵 → 保留原状态
  }
  ```
- 旧 `withRaw` 按 `0..1` 校验会丢弃 10/11 → 这是「数值不对」根因之一。现已修。

### 4.2 温度 = 整数原始值 × 0.2℃

- 不是浮点℃、不是 `functionId+index`。参考：`leftTemp:128 → 25.6℃`、`rightTemp:119 → 23.8℃`。
- `tempFromRaw()` 依次尝试三种编码，取落进 5..45℃ 的那个：
  1. `raw in 5..45` → 直接当℃；
  2. `raw * 0.2f`（**本机用这条**）；
  3. 旧 `base + (idx - indexBase) * step` 兜底。
- 全部 coerce 到 5..45℃，避免单一假设把真值判非法。

### 4.3 主驾/副驾温度共用 functionId，靠 zone 区分

- 共用 `0x10060100`；zone：`1 = 主驾`、`4 = 副驾`。
- 旧代码 `firstOrNull { funcOf == fid }` 总命中 `temp_driver` → 副驾变化错灌主驾。已用 zone 匹配修。

### 4.4 watcher 注册（旧 CX11 逐函数模式）

- `registerArrayMode = false`：对每个 `functionId` 调 `registerFunctionValueWatcher(int, IFunctionValueWatcher)`。
- 代理接口：`com.ecarx.xui.adaptapi.car.base.ICarFunction$IFunctionValueWatcher` 或 `...IVehicleFunction$IPropertyWatcher`。
- 代理 `InvocationHandler` 必须正确实现 `hashCode`/`equals`/`toString`（返回 null 会被系统 `CallbackList` 存代理时 NPE）→ 早期崩溃源之一，已修。

---

## 5. 崩溃排查时间线（历史）

| 阶段      | 现象                    | 根因                                                                                      | 修复                                                    |
| ------- | --------------------- | --------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| ① 最初    | 空调接通但数值不对 + 打开约 1 秒闪退 | 未硬化真实数据路径 + 编码错                                                                         | —                                                     |
| ② 加固    | 闪退                    | watcher 回调/`bindVehicle` 无 `runCatching`；代理 `hashCode/equals/toString` 返回 null → 系统 NPE | 全路径 `runCatching` + 代理标准方法                            |
| ③ 数值(1) | 副驾温度错                 | temp zone 映射错误                                                                          | zone 匹配                                               |
| ④ 数值(2) | 开关/温度仍错               | 10/11 枚举 + int×0.2℃ 被旧校验丢弃                                                              | `boolOf` + `tempFromRaw` 多编码                          |
| ⑤ 当前    | **温度对了，但仍约 1 秒闪退**    | 高度怀疑 **watcher 动态代理在第一次回调触发 native 崩溃**（~1s 时点吻合；轮询间隔 2.5s 排除轮询）                        | 见第 6 节：watcher **默认关闭、改纯轮询** + 崩溃 Toast + binder 死亡恢复 |

> 第 ⑤ 步是未闭环的：关掉 watcher 后温度靠轮询显示正确，但用户还没回测确认是否还崩。若关 watcher 后**仍崩** → 不是 watcher，需看日志定位（可能是主线程 binder 读、或媒体通道）。

---

## 6. 最近已落地的改动（均编译通过，2026-10-15）

**`Prefs.kt` 新增键**

- `vehicleWatcherEnabled`（默认 **false**）→ watcher 关、纯轮询。
- `mediaEnabled`（默认 true）→ 媒体接口独立开关。
- `tempMinC`（默认 16f）、`tempMaxC`（默认 30f）→ HI/LO 极值阈值。

**`VehicleController.kt`**

- `init()`：仅当 `Prefs.vehicleWatcherEnabled` 才 `registerWatcher`，否则走轮询；注册失败 `runCatching` 包住并降级轮询。

**`DockItemViews.kt`**

- `renderTemp()`：温度 ≥ `tempMaxC` 显示 **HI**，≤ `tempMinC` 显示 **LO**，中间按 0.1℃ 显示。

**`CrashHandler.kt`**

- uncaught 时除写文件外，**Toast 弹「异常类: 信息」**（`Handler(Looper.getMainLooper()).post` + `Thread.sleep(2500)`），车机无 adb 也能看到。

**`VehicleBridge.kt`**

- 缓存 `appCtx`；新增 `isBinderDead()`，在 `readInt/readFloatProperty/writeInt/writeFloatProperty` 遇 `DeadObjectException/RemoteException` 时 `reset()` + 自动重连。

**`DockOverlayService.kt`**

- 三处 `MediaBridge.start(this)` 改为 `if (Prefs.mediaEnabled(this)) MediaBridge.start(this) else 跳过`。

> 这些改动的目的：先把「能测试」门槛降下来（watcher 关→不再走会崩的原生回调路径），并把崩溃**可见化**，以便用户回报具体异常。

### 6.1 2026-10-19 增量（v1.4.20 → v1.4.23，均编译通过、已装平板验证迁移）

- **快捷应用独立按钮（v1.4.20）**：`Builtin.APP_DRAWER("app_drawer")`，默认与老配置迁移都插在
  `b_settings` 之后（`DockEntries.load()` 自动补插并回写）；车机设置恢复纯图标。条目管理页给
  app_drawer 配了 ic_apps 预览图标。
- **Dock 弹窗统一规范（v1.4.20，新增 `ui/DockPopupChrome.kt`）**：所有从 Dock 按钮弹出的窗
  （现在的和将来的）必须：①贴着触发图标（BOTTOM 重力 y=屏高−图标顶+gap，水平以图标中心对齐
  并收敛屏幕内；dock 在顶部则弹窗在下方）②点弹窗外面立即关闭（WATCH_OUTSIDE_TOUCH）③不放
  × 按钮。带 `shouldSuppressShow()` 防「点外面关掉后被同手势 up 重新弹回」。已接入：
  AppDrawerPopup / AirDirectionPopup / HvacControlPopup（show(anchor)）。
- **设置页底部让位（v1.4.21）**：MainActivity 与 DockEntriesActivity 在 dockAtBottom 时给内容
  底部 padding 加 `dockHeightPx + dockMarginPx + 12dp`，解决最后几项被 Dock 挡住点不到。
- **条目管理页长按拖动排序（v1.4.22 → v1.4.23 重做）**：**松手落位式**——长按拾起（震动+浮起）
  条目原地不动，手指只选落点（目标行全亮其余淡显），松手才落位、保存、广播重排。v1.4.22 的
  跟手式有 translationY 重复计算 bug（跨格后视觉跳回→松手判定未移动→不保存→dock 不变），
  已废弃该实现。↑↓ 按钮已删，✕ 保留；label 单行省略保证行等高（拖拽按等高行算槽位）。
- **日志上传按钮热区与反馈（v1.4.23）**：MainActivity.smallBtn 加 minHeight 40dp / 纵向 padding；
  「测试上传」「立即上传日志」点击立即显示"正在上传…"并禁用防连点，3 秒后恢复刷新状态。
- **温度/风速三件套融合成组合按钮（v1.4.24）**：`Builtin` 加 `hidden` 字段；新增
  `TEMP_DRIVER_COMBO / TEMP_PASSENGER_COMBO / FAN_COMBO`，旧 6 个 ± 条目 hidden（枚举保留
  供老配置解析，添加列表过滤）。`DockEntries.load()` 迁移：值条目原位替换为 combo、± 删除。
  视图端 `comboValue()`：横向 [−][DockValueView(weight1, 22sp)][+]，值区保留原点击/长按行为；
  `BuiltEntry.valueView` 携带中间数值 View，服务绑定收集改 `(built.valueView ?: built.view as? DockValueView)`
  防组合容器 cast 崩溃。条目数 18 → 12，每槽自然变宽。
- **快捷应用面板底部抬升（v1.4.24）**：AppDrawerPopup 在 dockAtBottom 时 y 再加
  `dockHeightPx + dockMarginPx`（用户设备=150px），避免最下一行应用格被 Dock 盖住。
- 教训：MIUI 平台会把后台 App 的前台服务约 60s 清掉（车机无此问题），平板测试要留意；
  锁屏时第三方 overlay 被 policy 隐藏（mPolicyVisibility=false），先解锁再测。

---

## 7. 日志上传需求（✅ 已于 2026-10-15 实现，原「未决」状态作废）

> ### ✅ 现状（2026-10-15 闭环）
>
> **已实现「App 只读上传自己的日志到 HTTPS 收集端点」**，不需要 adb、不需要改设备、人不用在车上。
>
> - **实现**：`app/.../util/LogUploader.kt`（新增）+ `Logx` 落盘 + `CrashHandler` 崩溃同步上传 +
>   `App.onCreate` 上次会话回捞 + `DockOverlayService` 诊断上传。
> - **上传时机（5 种）**
>   1. `crash`（当场）—— Java 未捕获异常，**同步**上传（堆栈 + 崩溃前落盘日志）
>   2. `crash`（补传）—— **下次启动**时把上次崩溃的证据一起传上来
>   3. `prev_session` —— 下次启动补传上次会话日志（无崩溃堆栈时）
>   4. `diag` —— 车控就绪后上传诊断（后端类型、温度读写通道、各通道 func/zone、首帧解析值）
>   5. `manual` —— 主界面「立即上传日志」
>
> ### 🆕 闪退 / 崩溃捕获（2026-10-15 加强）
>
> **三层取证，覆盖三类闪退：**
>
> | 崩溃类型 | 捕获手段 | 产出 |
> |---|---|---|
> | Java 异常 | `CrashHandler`（`setDefaultUncaughtExceptionHandler`） | `filesDir/last_crash.txt` + 当场同步上传 |
> | **Native 崩溃**（SIGSEGV/SIGABRT/…） | **`libcardockbar.so` 信号处理器**（NDK，新增） | `filesDir/logs/native_crash.txt`：信号号、si_code、出错地址、调用栈地址、`/proc/self/maps` |
> | 被系统杀死 / 无堆栈的闪退 | 「上次会话日志」落盘回捞 | `logs/prev.log` |
>
> **关键机制**
> - `Logx` **落盘**（独立 IO 线程，512KB 滚动截断）—— 内存日志会随进程消失，这是唯一能留下痕迹的办法
> - `App.onCreate` 顺序：① `session.log → prev.log` 轮转 ② `reportPreviousSession()` 收集证据并补传 ③ 新会话开始
> - `Logx.flushSync()` 在崩溃处理器里阻塞等待落盘队列排空，保证「崩溃前最后几行」真的在磁盘上
> - **指纹去重**：用「文件大小 + mtime」做 key 存在 `logs/crash_uploaded.txt`，同一崩溃不会重复上传
> - **正常/异常区分**：用户主动关 Dock 时写 `logs/clean_exit`，下次启动据此标注「正常结束 / 异常结束（疑似闪退）」
> - Native 处理器**刻意不使用** `dladdr`/`malloc`/`printf`（非异步信号安全，可能死锁把闪退变成卡死）；
>   只 dump 地址 + maps，符号化留到离线做
> - 处理器最后恢复默认动作并 `raise(sig)`，**不改变系统原有崩溃语义**
>
> **Native 捕获是可选的**：`.so` 加载失败只少一层取证，不影响任何其他功能（`NativeCrashHandler.isAvailable` 可查）。
>
> **符号化方法**：拿 `maps` 里的加载基址 + 调用栈帧地址算偏移，看落在哪个 so：
> - `/system/lib64/libcarx*` 或 `adaptapi` → 崩在车控 HAL
> - `/apex/.../libc.so` → 崩在系统 libc
> - `/data/app/com.car.dockbar/base.apk` → 崩在本 App
>
> - **默认端点（v1.4.18 起）**：境内自建日志服务
>   `https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/upload`
>   （主界面「日志上传」区可改任意接受 POST JSON 的地址）
> - **我方读取方式**：
>   ```bash
>   # 最近 200 条（JSON）
>   curl -s "https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/logs"
>   # 全部日志纯文本（按天分隔；?date=YYYY-MM-DD 只取某天）
>   curl -s "https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/download"
>   # 每条记录的字段即 App 发来的 JSON（kind / device / model / app / time / note / log）
>   ```
> - **端点说明**：WorkBuddy 发布的应用（`log_upload_service/`，纯标准库 Python），境内可达；
>   在「设置—数据管理—应用」可随时管理或下线。
>   历史端点 webhook.site 免费 token 已失效（2026-10-22 到期），勿再用。
> - **可靠性**：上传走独立线程池；失败落盘 `logs/pending.jsonl`，下次上传前自动补传；
>   单条 payload 日志正文截断到 16000 字符。
> - **隐私**：payload 只含 App 日志、车控原始值、设备型号/版本号，无账号与个人信息。
>   注意：该服务链接任何人可查看日志，排查结束后可在「设置—数据管理—应用」下线。
>
> 以下为当时的历史决策过程，保留备查。

### 7.1 用户约束（务必遵守）

- 车是**用户的车、远程**；用户用**台式机**，人**不在车上** → 无法 USB、无法物理到车机屏操作。
- 车机**有网络**，且能开「无线 ADB 开关」（但**首次开启需车机屏点击**，即需人在车边一次）。
- 明确要求：**只读、不改设备**；只读取并上传日志。

### 7.2 方案演进

1. 我提 ADB 抓日志（usb/`adb pull`/`logcat`）→ **被否**：需同局域网 + 车机已开无线调试/或人去车边开开关。
2. 我提 App 内 HTTP 上传（只读自身日志 + `last_crash.txt`）→ 正确方向，符合「只读」。
3. 用户问「App 提权调无线 ADB 读日志不行吗」→ 解释：ADB 是 host↔device 协议，App 是 device 侧，不能当自己 host 去连；全量 logcat 需 `READ_LOGS`（系统签名/eng）或 root，普通 App 拿不到；但 **App 读「自己的」日志 + 自己的崩溃文件零权限**，所以 App 内 HTTP 上传完全成立、且不需要提权。
4. 用户要我「自己弄一个上传地址」→ 我查了 `list_installed_plugins`：**没有任何连接器安装**，沙箱**无公网 IP**，我也无腾讯云/阿里云/企微等账号 → **我无法凭空变出一个车机可达的公网地址**。
5. 给出两条路（均需用户点一下）：
   - **A. WorkBuddy 云**：用户授权云连接器（一次）→ 我开日志收件箱、把 URL+key 烤进 App。
   - **B. 企业微信群机器人**：用户在任意群「添加群机器人」→ 把 webhook 链接发我，App POST 日志进群。

→ **至今用户未选 A 也未选 B，未提供任何端点 → 日志上传功能尚未实现。**

### 7.3 关键技术提醒（给下一任）

- 若崩溃是 **native**（watcher 代理类），`Thread.setDefaultUncaughtExceptionHandler` **不会触发**，`last_crash.txt` 会是**空**的。纯 App 上传 `last_crash.txt` 对 native 崩溃**拿不到有用信息**。
- native 崩溃的真凭实据在 `logcat -b crash` 与 `/data/tombstones/`（需 root 或 `adb bugreport`）。
- 若要 App 也能抓 native 崩溃：需加 **native 信号捕获**（NDK 注册 SIGSEGV 处理写迷你 tombstone 到自有文件，无需 root），或依赖用户已开无线调试时从台式机 `adb bugreport` 一次。

---

## 8. 已知未决 / 坑

1. **`tempToRaw()` 写温度仍用旧公式**（`base + (idx - iBase) * step`），**没改 0.2℃ 编码** → 用户在界面「±调温」时写回车机的值可能仍是错的（读已对、写未对）。需改成 `raw = round(℃ / 0.2)` 或按实测步进。
2. **风档/风向/座椅枚举未完全校准**：`fan_manual`/`fan_auto`/`air_direction`/`seat_vent`/`seat_heat` 的编码仍靠推测 + 范围校验，建议让用户逐项切换并回报原始值。
3. **崩溃是否 native 未确认**：需日志（见第 7 节）。
4. **用户当前测的是哪个 APK 未知**：是「watcher 默认关闭版」还是更早版本？影响判断（关 watcher 仍崩→非 watcher；老版本崩→吻合假设）。
5. ~~**上传端点未定**：A/B 都没选。~~ → ✅ 已实现（默认境内自建服务端点，可改），见第 7 节。
6. `VehicleController.idxOf()` 已成死代码（仅 warning）。

---

## 9. 给下一任 AI 的行动建议（按优先级）

1. **先拿到日志**（这是一切前提）：
   - 推动用户二选一：A 授权 WorkBuddy 云 / B 企微机器人；实现只读 HTTP 上传（崩溃自动上传 + 手动按钮，默认关、填了地址才启用）。
   - 或若车机已开无线调试且与台式机同网 → 指导用户台式机 `adb bugreport`（人不用去车边）。
2. **判崩溃类型**：看 `last_crash.txt` 是否空 + `logcat -b crash` / tombstone 有无 `signal 11`、`Abort`、backtrace 指向 `libcarx*`/`adaptapi`/`IFunctionValueWatcher`。
3. **若是 watcher 触发的 native 崩溃**：
   - 短期：保持 watcher 关闭、纯轮询（已默认）。
   - 根治：用 ECarX 官方注册方式替代裸动态代理，或在回调里严格按接口契约实现所有方法、把回调转发到主线程前做完整参数校验；或彻底纯轮询（牺牲实时性换稳定）。
4. **修 `tempToRaw` 写温度编码**（0.2℃）。
5. **校准其余通道枚举**（让用户逐项切换回报原始值）。
6. 全程遵守用户「只读、不改设备」约束；新 APK 属于用户已授权的功能开发范畴，可正常构建交付。

---

## 10. 构建 / 运行要点

- JDK：`/c/Program Files/Android/Android Studio/jbr`（AGP 8.7.3 需 JDK 11+）。
- 编译：`cd <项目根> && export JAVA_HOME=... && ./gradlew assembleDebug --no-daemon`。
- 调试：debug 包可 `adb shell run-as com.car.dockbar cat files/last_crash.txt`。
- `Prefs.simulate=true` 可脱离车机调 UI。
- 日志总线 `Logx`：内存保留最近 400 条（`Logx.snapshot()`），**不写文件**；崩溃时 `CrashHandler` 单独写 `last_crash.txt`。

---

## 11. 关键文件索引（绝对路径）

- `C:/Users/17900/WorkBuddy/2026-10-07-19-43-58/CarDockBar/app/src/main/java/com/car/dockbar/vehicle/VehicleBridge.kt`
- `.../vehicle/VehicleController.kt`
- `.../config/VehicleChannels.kt`
- `.../ui/DockOverlayService.kt`
- `.../ui/DockItemViews.kt`
- `.../ui/MainActivity.kt`
- `.../media/MediaBridge.kt`
- `.../util/CrashHandler.kt`
- `.../util/Prefs.kt`
- `.../App.kt`
- `C:/Users/17900/WorkBuddy/2026-10-10-19-02-51/.workbuddy/memory/2026-10-12.md`、`2026-10-13.md`、`2026-10-15.md`（详细日志）

---

## 12. 用户沟通偏好（避免踩雷）

- **中文交流**；给结论与证据（日志、具体改了哪行），少绕弯。
- **别假设用户在车上**：他台式机、车远程。任何「去车机屏操作 / 插 USB」的方案都不可行，会惹怒他。
- **别反复让他做操作**：他反感被要求去点车机屏、或被推来推去。能自己查/自己定的就先定，拿不准再问，且一次问清。
- **认错要快、不辩护**：前面我把 ADB 方案反复提了几次，被他骂「发颠」——因为他根本到不了车边。
- 涉及「上传地址」这类**我客观上造不出**的东西，直接说清技术限制（沙箱无公网 IP、无连接器、无第三方账号），并给**只需他点一下的两条路**，别假装能凭空生成。
- 用户授权过**构建交付 APK**；唯独「日志收集期间不改设备」是他明确要求的只读约束。

---

## 13. 【2026-10-15 补充】车控接口的权威写法与已修缺陷


用户提供了车机系统 APK（`oneOS_Dock.apk` / `oneOS_Hvac.apk` / `oneOS_Launcher3.apk`，位于  
`C:/Users/17900/Desktop/DBPLAY改/车机系统app/`）。**其中 oneOS_Dock 的 smali 已解包好**，  
在 `C:/Users/17900/Documents/MuMuSharedFolder/oneOS_Dock/oneOS_Dock_src/`，直接读即可，不必重新反编译。

### 13.1 ECarX adaptapi 权威签名（来源 `com.geely.dockbar.domain.vehicleusecase.SignalHelper2`）

```
Car.create(Context) → ICar → getICarFunction() → ICarFunction

ICarFunction:
  int     getFunctionValue(int functionId) / (int functionId, int areaId)
  float   getCustomizeFunctionValue(int functionId) / (int, int)   ← 温度走这条，直接返回 ℃
  boolean setFunctionValue(int functionId, int value)              ★ 2 参第二参是 value，不是 zone
  boolean setFunctionValue(int functionId, int areaId, int value)
  boolean setCustomizeFunctionValue(int functionId, float value) / (int, int, float)
  FunctionStatus isFunctionSupported(int) / (int, int)
  boolean registerFunctionValueWatcher(int[] functionIds, IFunctionValueWatcher)  ★ 只有数组模式
  boolean unregisterFunctionValueWatcher(IFunctionValueWatcher)

IFunctionValueWatcher 回调（必须按名精确分派，不能靠 contains("Changed") 猜）：
  onFunctionChanged(int id)
  onFunctionValueChanged(int id, int area, int val)
  onCustomizeFunctionValueChanged(int id, int area, float val)
  onSupportedFunctionStatusChanged(int id, int area, FunctionStatus st)
  onSupportedFunctionValueChanged(int id, int[] ints)

HvacConstants：OFF=0x0  ON=0x1  ERROR=0xfd  NONE=0xfe  UNKNOWN=0xff
```

### 13.2 权威通道表（来源 `VehicleSignalManager` 稀疏表 + 各 `*SignalClick`）

| 功能        | functionId              | areaId          | 取值                                              |
| --------- | ----------------------- | --------------- | ----------------------------------------------- |
| 风量手动      | 0x10020100              | 0x8             | 0=关，+1..9                                       |
| 风量自动      | 0x10020200              | 0x8             | +1..5                                           |
| **内外循环**  | 0x10030100              | **0x8**（ZONE_ROW_1_ALL） | ★裸枚举 **0=外 / 1=内**；权威调用 `setFunctionValue(0x10030100, 0x8, 1)` = 开内循环 |
| 空调风向      | 0x10070100              | 0x8             | +1..7                                           |
| **空调温度**  | 0x10060100              | **1=主驾 / 4=副驾** | **float ℃，步进 0.5**（`getCustomizeFunctionValue`） |
| **座椅通风**  | **0x10050100**          | **1 / 4**       | +0..3                                           |
| **座椅加热**  | **0x10050200**          | **1 / 4**       | +0..3                                           |
| 座椅按摩      | 0x10050700              | 1 / 4           | +0..N                                           |
| 温度上限 / 下限 | 0x10060300 / 0x10060500 | —               | float ℃                                         |

> ⚠️ 两套 areaId 别混用：**风量/风向用 0x8**；**温度/座椅用 1（主驾）/ 4（副驾）**。

### 13.3 本次修复的缺陷（均已编译通过，APK v1.2.0 / versionCode 3）

| #  | 缺陷                                                                                       | 修复                                                             |
| -- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| 1  | 座椅通风 functionId 写成 0x10050000、加热写成 0x10050100，**两者错位**，且座椅沿用 zone=0x8 → 座椅读写全无效          | 改为通风 **0x10050100** / 加热 **0x10050200**，zone 改 **1/4**；补副驾加热通道 |
| 2  | 温度用 `getFloatProperty`（GlyCar API），ECarX 上不存在 → `hasFloat=false` → 温度走 int 旧公式，**写入值错误** | 改用 `getCustomizeFunctionValue` / `setCustomizeFunctionValue`   |
| 3  | `VehicleBridge.writeInt` 在 2 参写方法时传 `(functionId, zone)`，**把 zone 当 value 写**            | 修正为 `(functionId, value)`                                      |
| 4  | 温度一旦读到过合法值就再也回不到 OFF                                                                     | 浮点读到 ≤0/>60 置 null；并用 int 通道交叉验证，避免误判 OFF                      |
| 5  | `tempToRaw` 用旧公式反推 int 原始值                                                               | 改为 `round(℃ / 0.2)`（与实测 128→25.6℃ 一致）                          |
| 6  | **座椅加热完全没有 Dock 入口**                                                                     | 新增 `Builtin.SEAT_HEAT` + `BindKind.SEAT_HEAT` + 渲染             |
| 7  | 座椅入口丢失 `enter_hvac_page=1` 深链 → 永远只打开空调主页                                                | `seatTarget()` 补 extras=1、`airDirTarget()` 补 extras=0          |
| 8  | 「开机自启歌词悬浮窗」开关是死配置（`Prefs.lyricBoot` 只写不读）                                                | `BootReceiver` 改为 `lyricEnabled \|\| lyricBoot`                |
| 9  | 歌词字号滑条 label 显示「36 sp」而实际生效 22 sp                                                        | progress 修正为 `lyricTextSize - 14`                              |
| 10 | 「空闲自动隐藏」与主界面 `ensureServiceStarted` 打架（一进主界面 Dock 被强行拉回），且状态栏误报「没挂上 ✗」                   | 仅 `!alive` 才重挂；状态栏区分「已隐藏(正常)」                                  |
| 11 | **主线程做 binder IPC**（每 2.5s 读 9 个通道 + 启动时立即 refresh）                                      | 车控/媒体刷新移到后台 `HandlerThread("dock-vehicle")`                    |
| 12 | `LyricMonitor` 每 400ms 调 `getActiveSessions()`                                           | 节流到 2s（会话增删由 Callback 驱动）                                      |
| 13 | 广播字段识别用 `endsWith("name")`，把 `pkgname`/`classname` 当歌名                                   | 改白名单 + 排除法 `isTitleKey()`                                      |
| 14 | 日志区每行重设 60KB 整串 → 主界面卡顿                                                                  | 改增量 append + 单次滚动合并                                            |
| 15 | 温度联动 / HI-LO 阈值无 UI 入口（死配置）                                                              | 主界面补上开关与阈值输入                                                   |
| 16 | `entry.span` 对内置控件无效                                                                     | 工厂按 `entry.span` 生效                                            |
| 17 | 死代码                                                                                      | 删除 `VehicleBridge.readFloat`、`VehicleController.idxOf`         |

### 13.4 仍未验证（需上车实测）

- `getCustomizeFunctionValue` 在**本车**是否真的返回 ℃（依据是系统 App 的用法，理论上成立）。  
  判断方法：主界面「后端」一行现在会显示 `温度通道=getCustomizeFunctionValue`；再看日志里  
  `车控刷新 raw:` 的温度 int 原始值（如 `0x...80` = 128 → 应为 25.6℃）与显示值是否一致。
- 风向 01..07 与「吹脸/吹脚/前挡」的对应关系仍按位掩码假设（1/2/4），未逐项实测。
- 座椅 0..3 档位的语义（关/弱/中/强）未实测。
- 若 `温度通道=无`，说明该车只有 GlyCar 浮点属性，需要走另一条通道。

---

## 14. 【2026-10-15 22:00】首次拿到真实崩溃日志 —— 分析结论

### 14.1 数据来源
设备 `fb6aa8c03e975e08`（用户车机），13:22–13:46 之间上传 **28 条 crash + 6 条 diag**。
拉取命令：
```bash
curl -s "https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/logs"
```

### 14.2 崩溃特征（高度一致）
| 项 | 值 |
|---|---|
| 信号 | **SIGSEGV (11)**，28/28 次 |
| si_code | **2 = SEGV_ACCERR**（地址已映射但权限不足） |
| fault addr | 低位**恒为 `0xbc0`**（28 次全部如此），高位随 ASLR 变化 |
| 每次会话时长 | **0.7 – 1.8 秒**（日志只有 32–39 行） |
| 最后一行（16/28） | `StatusPop：显示 主驾 5.0°　副驾 5.0°　风速 0 档　外循环` |

> `fault addr` 低位固定 `0xbc0` 是极强的模式：说明访问的是
> **`<某个基址> + 固定偏移`**，或是一个低位被污染的野指针。

### 14.3 已确认的 3 个实现缺陷（本轮修复）

**① v1 的 native 处理器会「自杀」**
`_Unwind_Backtrace` 在信号处理器里调用会导致处理器自身崩溃 →
文件只写到 `fault addr` 就断了，**maps 与调用栈全部丢失**（28 条记录无一例外）。
> 修复：彻底不用 libunwind。改为从 `ucontext` 取 `pc/sp/lr`，
> 并**把 `/proc/self/maps` 提到信号信息之后第一个写**，
> 再 dump 崩溃线程栈内存（2KB）。即使后续 dump 二次崩溃，maps 也已落盘。

**② 日志异步落盘截断了崩溃现场**
`Logx` 原本用单线程队列写盘，进程被内核干掉时队列内容全丢 ——
这正是「28 次最后一行各不相同」的原因（假象）。
> 修复：改为**常开 `FileOutputStream` + 每行 write+flush 同步写盘**。
> 代价是一次 write 系统调用（几十微秒），换来崩溃现场日志 100% 完整。

**③ 温度显示伪值 5.0**
实测：空调未开启时 `getCustomizeFunctionValue(0x10060100, 1)` 返回 **5.0**，
而同一通道 `getFunctionValue` 返回 `0xff`（UNKNOWN 哨兵）。
旧实现把 5.0 coerce 进 5..45 → 直接显示「5.0°」，既非真值也非 OFF。
> 修复：新增可信区间 `TEMP_VALID_MIN=10f / TEMP_VALID_MAX=40f`，区间外一律 null（显示 OFF）。

### 14.4 新增：自动降级「安全模式」阶梯（用于远程二分定位）

用户到不了车边、App 1 秒就崩、UI 根本来不及操作 —— 所以让 **App 自己二分**。

判定：上次崩溃了 **且** 没活过 15 秒（`logs/stable.marker` 未打点）→ 连续崩溃 +1；活过 15 秒则清零。

| 连续崩溃 | 安全等级 | 自动关闭 |
|---|---|---|
| 2 | L1 | 「空调状态弹窗」+「反色实时截屏」（两个纯装饰功能） |
| 3 | L2 | 再关「歌词悬浮窗」 |
| 4 | L3 | 再关「媒体接口（MediaSession / 通知使用权）」 |
| 5+ | L4 | 再关「车控读取」，只留纯 UI |

每次启动都会把当前等级写进日志与 `diag` 上传 —— 据此即可定位是哪一层导致崩溃。
主界面「日志上传」区有「退出安全模式（恢复全功能）」按钮。

**下一轮的判读方法**：
1. 看新上传记录里 `安全等级=` 的值 → 知道 App 退到哪一级才不崩
2. 若仍在崩，看 `native_crash.txt` 里的 **maps + pc** →
   用加载基址把 `pc` 换算成偏移，即可判定崩在哪个 so

### 14.5 🎯 根因已定位（2026-10-15 22:40）—— Custom化温度通道

用户的关键线索：**「空调接入之前，其他功能都是正常可用的」** —— 把范围锁死在车控层。

突破口：用户桌面上的 **`osn_fan_widget`（第三方风扇控件，普通 App、Manifest 只有
`RECEIVE_BOOT_COMPLETED` / `SYSTEM_ALERT_WINDOW` / `FOREGROUND_SERVICE` / `WAKE_LOCK` 四个普通权限）**
能在这台车上正常控空调。逐个对比它与我们的调用：

| 调用 | osn_fan_widget（正常） | 我们（崩溃） |
|---|---|---|
| `Car.create(Context)` | ✅ | ✅ |
| `ICar.getICarFunction()` | ✅ | ✅ |
| `getFunctionValue(int, int)` | ✅ | ✅ |
| `registerFunctionValueWatcher(int[], …)` | ✅ | ❌ 默认关闭 |
| **`getCustomizeFunctionValue` / `setCustomizeFunctionValue`** | **❌ 从不调用** | **✅ 每次刷新调 2 次** |

**我们唯一多调用的 API 就是温度的 Customize 浮点通道。** 一旦调用，约 1 秒后整个进程
native SIGSEGV（signal 11 / SEGV_ACCERR / fault addr 低位恒 0xbc0）—— 与厂商库内部
后台线程/延迟初始化读取未授权 App 的空配置相吻合。

旁证：系统 Dock 的 Manifest 是 **`android:sharedUserId="android.uid.system"`**（UID 1000），
HVAC App 持有 `WRITE_SECURE_SETTINGS` / `INTERACT_ACROSS_USERS` 等特权 ——
它们走 Customize 通道没问题，普通 App 不行。

> 📖 `osn_fan_widget` 的源码与 `docs/APIs/ecarx.adaptapi.jar.txt`（9638 个 API 签名清单）
> 都在 `C:/Users/17900/Desktop/DBPLAY改/风扇通道信息/extracted/osn_fan_widget-master/`，
> **这是唯一一个「普通 App 能控这台车空调」的已验证参考，遇到问题优先对照它。**

#### 根因修复（v1.4.0）
1. **彻底移除 Customize 通道** —— `VehicleBridge` 不再探测/调用
   `getCustomizeFunctionValue` / `setCustomizeFunctionValue`（已用 dex 字符串扫描确认零残留）。
2. **温度改走 int 通道**：读 `getFunctionValue` → `tempFromRaw`（×0.2）；
   写 `setFunctionValue(funcId, areaId, round(℃/0.2))`。
3. **事件监听默认开启**（`vehicleWatcherEnabled` 默认 true）——
   与 osn_fan_widget 一致，事件推送比 2.5 秒轮询更实时、少一次 binder 读。
4. **探测面收窄**：只探测 `getFunctionValue` / `setFunctionValue` / watcher 三个，
   不再解析 `getSupportedFunctionValue` / `isFunctionSupported` / `getFloatProperty` 等。
5. 温度可信区间 `10..40℃`（挡掉空调关闭时的伪值 5.0）。

### 14.6 修复前的 3 个实现缺陷（已一并修好）
