# Banana_Dock 项目协作指南与资料总索引

> 本文件合并了原根目录的 `CURRENT_STATUS.md`、`HANDOFF.md`、`HANDOVER.md`、`反编译参考.md` 和 `改造说明.md`。为避免历史记录继续伪装成当前状态，所有内容按 **当前实现 / 历史资料 / 未验证事项** 分类；出现冲突时，以当前源码、构建配置和最新 Git 变更为准。
>
> 当前整理基线：2026-11-12，最新代码变更为设置页独立页面化重构（`c79db57`）。

快速导航：[当前快照](#1-当前项目快照当前) · [源码索引](#2-权威源码索引) · [构建与发布](#3-构建测试和发布当前) · [设置页](#4-当前设置页和-ui-结构) · [Dock 条目](#5-dock-条目默认布局和交互) · [车控与安全](#6-车控接口和安全边界) · [日志排障](#8-诊断日志和崩溃排查) · [历史资料](#9-历史车控和逆向参考历史不是当前实现) · [厂商接口附录](#附录-aoneos-厂商接口与静态索引历史)

## 0. 阅读和维护规则

### 0.1 权威顺序

1. 当前源码和资源文件。
2. `app/build.gradle`、`settings.gradle`、Gradle wrapper 和 `.github/workflows/release-apk.yml` 等构建配置。
3. 最新 Git 提交及其实际 diff。
4. 本文件中标为 **当前** 的摘要。
5. 标为 **历史** 或 **未验证** 的记录，只能用于排障背景或提出待验证假设，不能用来声称当前行为。

在文档中新增结论时，尽量写出源码路径；涉及车控、权限、真车效果或远程日志时，同时写清证据等级。不要把旧版本号、旧 APK 文件名、模拟器测试或逆向推断写成当前事实。

### 0.2 变更前检查

- 先检查 `git status --short`，不要覆盖用户已有改动。
- 改车控前先看 `config/VehicleChannels.kt`、`vehicle/VehicleController.kt`、`vehicle/VehicleBridge.kt` 的可执行代码，不能只看注释或历史交接记录。
- 改设置页前先看 `ui/SettingsPageRegistry.kt` 和各 `*SettingsPage.kt`，不要把旧的单体 `MainActivity` 结构当成现状。
- 改发布配置前同时核对本地 Gradle 输出名和 CI 的 `dist/` 重命名规则。
- 完成后至少做 Markdown 链接/路径检查，并按改动范围运行构建或测试；没有真车证据时必须保留“未验证”标记。

### 0.3 注释与提交约定

- 新增或修改的源码和测试辅助代码注释使用中文，API、类名、方法名及配置键保持既有命名，不因注释语言调整程序行为。
- Git 提交信息使用中文说明改动目的和主要内容；可以沿用 `feat:`、`fix:`、`docs:` 等类型前缀，但冒号后的摘要和提交正文使用中文。
- 提交前核对完整差异，包含用户明确要求一起提交的已有改动，不提交构建产物、缓存及本地临时文件；推送前确认当前分支及对应远端。

## 1. 当前项目快照（当前）

- 项目：`Banana_Dock`，面向 Android 车机的悬浮 Dock 应用。
- `namespace` / `applicationId`：`com.car.dockbar`。
- 应用显示名：`Banana_Dock`。
- 当前版本：`versionName 4.5.0`，`versionCode 450`，来源 `app/build.gradle`。
- 最低 Android 版本：API 28；compile/target SDK：34。
- 最新重要变更：设置页已从旧的单体实现拆为独立页面；当前入口由 `MainActivity`、`SettingsPageRegistry` 和各页面类共同管理。
- 不得提交真实 keystore、密码、Base64 内容或用户设备数据；仓库已有 `.github/signing/ci-test.jks` 是历史测试文件，不能据此认定它就是当前发布证书。
- 车控、车型适配、平台权限和真实车辆效果不能仅凭 APK 能构建或模拟器/逻辑测试通过来确认。

## 2. 权威源码索引

### 2.1 构建和发布

| 主题 | 权威位置 |
| --- | --- |
| Android/Kotlin 版本、包名、版本号、ABI、构建类型 | `app/build.gradle` |
| 根插件版本和 clean 任务 | `build.gradle` |
| Gradle 仓库、模块和项目名 | `settings.gradle` |
| Gradle wrapper 版本 | `gradle/wrapper/gradle-wrapper.properties` |
| CI 构建、重签、元数据和 GitHub Release | `.github/workflows/release-apk.yml` |
| 权限、Activity、服务、通知监听、开机接收器 | `app/src/main/AndroidManifest.xml` |

### 2.2 当前应用结构

除显式写出的仓库根路径外，以下 `ui/`、`dock/`、`model/`、`media/`、`vehicle/`、`config/`、`util/` 路径均相对 `app/src/main/java/com/car/dockbar/`。

| 领域 | 当前入口 |
| --- | --- |
| 设置页宿主、导航和缩放 | `app/src/main/java/com/car/dockbar/ui/MainActivity.kt` |
| 页面生命周期和通用页面契约 | `ui/SettingsPage.kt` |
| 页面注册和切换 | `ui/SettingsPageRegistry.kt` |
| 悬浮 Dock 页面 | `ui/FloatingDockSettingsPage.kt`、`ui/FloatingDockSettingsLayout.kt` |
| 外观与风格页面 | `ui/AppearanceSettingsPage.kt`、`ui/AppearanceSettingsLayout.kt` |
| Dock 图标管理页面 | `ui/IconsSettingsPage.kt`、`ui/IconsSettingsLayout.kt`、`DockEntriesActivity.kt` |
| 歌词悬浮窗页面 | `ui/LyricsSettingsPage.kt` |
| 隐藏 Dock 应用（黑名单）页面 | `ui/ResidentSettingsPage.kt` |
| 诊断与日志页面 | `ui/LogsSettingsPage.kt`、`ui/LogsSettingsLayout.kt` |
| Dock 条目模型与默认布局 | `model/DockEntry.kt` |
| 快捷应用固定槽位持久化 | `model/AppDrawerApp.kt` |
| 控件目录、尺寸和挂载 | `dock/DockCatalog.kt`、`dock/DockWidget.kt`、`dock/widgets/BuiltinWidgets.kt` |
| 动作分发 | `dock/DockActions.kt` |
| 悬浮 Dock 服务和窗口 | `ui/DockOverlayService.kt`、`ui/DockLayout.kt` |
| 显示/隐藏状态机 | `ui/state/DockStateMachine.kt` |
| 离屏编辑预览、300ms 长按与排序 | `ui/DockEditorPreviewView.kt`、`ui/DockEditorLongPress.kt`、`model/DockEditorOrder.kt` |
| 歌词服务和歌词数据源 | `ui/LyricOverlayService.kt`、`media/LyricMonitor.kt`、`media/LyricDownloader.kt` |
| 媒体会话和通知补充信息 | `media/MediaBridge.kt`、`media/DockNotificationListener.kt` |
| 车控通道配置 | `config/VehicleChannels.kt` |
| 车控读写和状态 | `vehicle/VehicleController.kt`、`vehicle/VehicleBridge.kt` |
| 传感器 | `vehicle/SensorBridge.kt` |
| 日志、崩溃和上传 | `util/Logx.kt`、`util/LogUploader.kt`、`util/NativeCrashHandler.kt` |
| 偏好设置兼容入口与启动 | `util/Prefs.kt`、`App.kt` |
| Dock 黑名单默认值、迁移与存储 | `util/DockBlacklistDefaults.kt`、`util/DockBlacklistMigration.kt`、`util/DockBlacklistPreferences.kt`、`util/DockBlacklistRules.kt` |
| 规则、迁移与控件单元测试 | `app/src/test/java/com/car/dockbar/util/`、`app/src/test/java/com/car/dockbar/dock/` |

## 3. 构建、测试和发布（当前）

### 3.1 本地环境

- Gradle wrapper：8.9。
- Android Gradle Plugin：8.7.3。
- Kotlin Android plugin：2.0.21。
- Java/Kotlin 编译目标：17；Gradle 运行需 JDK 17 或兼容版本（如 JDK 21），用 `JAVA_HOME` 指定。CI 使用 JDK 17，本次本地验证环境为 JDK 21。旧文档中“JDK 11+ 即可”不满足当前 AGP 要求；也不要求必须使用某个固定安装路径下的 JBR。
- NDK：`28.2.13676358`，通过 `ndk-build` 使用 `app/src/main/jni/Android.mk`。
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。
- Release 开启 R8 和资源缩减；本地 Gradle release 配置仍使用 `signingConfigs.debug`，CI 会在发布前用 Secrets 重签。

常用命令：

```bash
# Windows
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest

# Git Bash / Linux / macOS
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

本地产物：

- `app/build/outputs/apk/debug/Banana_Dock-4.5.0-debug.apk`
- `app/build/outputs/apk/release/Banana_Dock-4.5.0.apk`

以上路径只有在相应构建实际成功后才能写入交付说明；不要沿用历史的 `CarDockBar-debug.apk` 或 `Banana_Dock-4.0.apk`。

### 3.2 GitHub Actions

`.github/workflows/release-apk.yml` 的行为如下：

- workflow 接收所有分支的 push 和 `workflow_dispatch`，但实际 `detect` 只在默认分支且 ref 未被删除时运行。
- push 会扫描本次 push 的所有 commit message；中文 `发布`、`发版`、`新版`、`版本更新`，以及 release/publish/new version/bump version 等关键词会请求发布。
- 手动 dispatch 直接请求发布，但仍受默认分支条件约束。
- 发布任务使用 Java 17、Android SDK 34、Build Tools 34.0.0 和 NDK 28.2.13676358，执行 `:app:assembleRelease --no-daemon`。
- 构建目录中必须恰好有一个 APK；随后用四个 Secrets 重签并用 `apksigner verify` 校验。
- Secrets 名称：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。不得把 keystore、密码或 Base64 内容提交到仓库。
- CI 将 Gradle 产物复制为 `dist/CarDockBar-<versionName>-r<run>.apk`（当前版本形如 `CarDockBar-4.5.0-r123.apk`）。`versionName` / `versionCode` 仍只由 APK 与 `app/build.gradle` 交叉校验，CI 不改写它们，因此同一版本的新旧包可以互相覆盖安装。每次构建另写自己的 `update.json` 和 `SHA256SUMS`。
- 每次成功发布都新建 GitHub Release，tag 为 `v<versionName>-r<run>`（同一次 run 重跑时为 `v<versionName>-r<run>-a<attempt>`），标题为 `CarDockBar <versionName> (r<run>)`。已有 Release 不会被覆盖或删除。发布资产名与本地 `Banana_Dock-4.5.0.apk` 有意不同，不要混写。
- 普通文档提交不要无意包含发布关键词，避免触发 Release。

## 4. 当前设置页和 UI 结构

### 4.1 六个独立页面

`MainActivity.pageSpecs` 当前注册以下六页，顺序也是左侧导航顺序：

1. 悬浮 Dock 栏：`FloatingDockSettingsPage`。
2. 外观与风格：`AppearanceSettingsPage`。
3. Dock 图标管理：`IconsSettingsPage`。
4. 歌词悬浮窗：`LyricsSettingsPage`。
5. 隐藏 Dock 应用：`ResidentSettingsPage`。
6. 诊断与日志：`LogsSettingsPage`。

各页面由 `SettingsPageRegistry.createAll()` 创建并管理生命周期；后续新增设置应优先增加独立页面，而不是把所有控件重新塞回 `MainActivity`。

当前宿主保留的布局约束：

- 设置内容底部让出 **128 物理像素**给实际 Dock，不能把它误写成 128dp。
- 主设置宿主的缩放链为旧上下文比例 1.2 再乘页面 UI_SCALE 0.9，即当前约 1.08；图标管理页的独立密度约 0.9。不要修改系统全局 DPI 来实现页面缩放。
- 服务状态、版本号和诊断入口的视觉约束以当前布局资源和页面代码为准；旧文档中“全部仍在 MainActivity 里”的路径已经过时。

### 4.2 Dock 外观

- 默认 Dock 宽度配置为 1920px，默认屏幕高度配置为 1080px，默认高度 128px、底部距边 0；实际服务仍会结合当前显示尺寸计算窗口位置。
- 最近提交将默认圆角改为 **0px 直角**，配置键改为 `corner_radius_px_square`；旧文档中的默认 14px 圆角已失效。
- 按压高亮使用 `res/drawable/bg_item_press.xml`，不要恢复已移除的旧样式。
- 外观模式包含纯色、透明、渐变和“模糊（测试中）”。其中模糊实现是透明底上叠加灰色层和合成透明度（`GrayOverlayDrawable`），不是抓屏后的高斯模糊，也不申请录屏权限。
- `DockStatusLabel.kt` 虽仍在源码中，旧交接中的“上方状态小字已全部移除”不能单独作为判断依据；是否显示以 `DockOverlayService` 当前可执行调用为准，新增 UI 时不要根据旧文档擅自恢复常驻状态文字。
- 当前“隐藏 Dock 应用”页从 `getInstalledApplications` 取真实已安装应用，分系统/个人两栏；每栏 5 列、至少 4 行，支持应用名/包名搜索，绿色勾选表示加入黑名单，该应用在前台时隐藏 Dock；取消勾选则恢复默认显示。

## 5. Dock 条目、默认布局和交互

### 5.1 当前默认十项

默认只在首次使用或恢复默认时生效，升级不能覆盖用户自定义顺序。`DockEntry.kt` 中的当前顺序是：

1. `HOME`：返回桌面。
2. `MUSIC`：音乐信息卡。
3. `SETTINGS`：车机设置。
4. `APP_DRAWER`：快捷应用。
5. `TEMP_DRIVER_COMBO`：主驾温度组合。
6. `AIR`：空调风向。
7. `FAN_COMBO`：风速组合。
8. `RECIRC`：内外循环。
9. `SEAT`：座椅设置。
10. `TEMP_PASSENGER_COMBO`：副驾温度组合。

歌词、音量、座椅通风/加热、前后挡、方向盘加热等可从图标管理中手动添加，不要把它们写成当前默认项。

### 5.2 当前尺寸模型

尺寸的权威值在 `dock/DockWidget.kt` 的 `DockMetrics`：

- 单槽固定宽度：98px。
- 三槽固定宽度：290px。
- 风速组合：220px，箭头 40px。
- 音乐卡：最小 295px、最大 500px，按可用空间弹性伸缩。
- 条目数量变化时，布局重新计算可用占位；不要把旧文档的“始终等宽”当作现状。

### 5.3 条目编辑和快捷应用

- 图标管理页上方展示尚未加入的内置控件，候选使用实际 Dock 控件的绘制和当前外观。
- 编辑长按固定 300ms（`DockEditorLongPress.TIMEOUT_MS`）；拖入预览添加、预览内拖动插入排序、拖出松手移除，取消拖动不保存。
- 预览为离屏绘制，不把触摸转发给真实控件，因此编辑时不会写车控、播放媒体或启动应用。
- 音乐卡、温度组合和风速组合按完整条目移动。
- 第三方应用走独立入口，系统/个人分类和搜索；删除入口不卸载应用。
- 快捷应用是独立的 2×3 固定槽位数据。空位保留，替换不压缩其它位置，批量管理和 Dock 弹窗共用同一份槽位数据。
- `dock_entries_explicit_layout` 用于区分旧配置迁移和用户主动删除；用户清空或删除后不能每次读取又自动补回。

### 5.4 当前动作约束

动作集中在 `dock/DockActions.kt` 和 `dock/widgets/BuiltinWidgets.kt`。当前应保留的行为包括：返回桌面/最近任务、启动播放器、播放暂停/下一曲、车机设置、空调/温度/风量/循环/座椅操作、歌词窗口切换和应用启动。音乐卡无法打开播放器时提示“当前没有可打开的播放器”，不要恢复旧的无提示跳转。

旧交接中仍有价值的 UI 规则（但须以当前代码为最终依据）：

- 弹窗贴近触发图标，点外关闭，无额外关闭按钮，按当前配置自动消失。
- 弹窗触发应是 toggle 语义，不能用时间戳抑制后续切换。
- 主驾温度长按切换 AC；副驾温度长按切换双温同步；具体文案和实现以 `BuiltinWidgets.kt`、`DockActions.kt` 为准。
- 真正修改车控前必须经过当前 `VehicleController` 的写入保护；编辑预览不能触发真实动作。
- 当前默认淡入淡出时长为 350ms；底部手势临时唤出 Dock 默认显示 5000ms；空调状态弹窗默认自动消失 3000ms。设置项可覆盖这些默认值，改动前应以 `Prefs.kt` 为准。
- 音量弹窗按 1.8 倍绘制，媒体使用系统流 3、导航使用系统流 15，显示和写入使用系统原始流索引，不再额外加减 5；默认 6 秒收起。

### 5.5 Dock 显隐与临时显示（当前）

- 前台显隐入口为 `ui/DockOverlayService.kt` 的 `applyKeepShownForForeground()`，唯一名单来源为 `dock_hide_apps`：命中则隐藏，未命中则保持默认显示。
- 前台包名为空、空白或尚未取得（例如未开启无障碍）时，设置 `hiddenByApp=false`，不因前台应用隐藏 Dock。不存在“拿不到前台应用就交还全局空闲自动隐藏”的回退逻辑。
- 全局空闲自动隐藏已经移除，`SHOWN` 状态不会因为无操作而倒计时消失；`pauseAutoHide()`、`onUserIdle()` 等沿用的名称不能作为该功能仍存在的依据。
- 底部手势或弹窗触发的 `TEMP_SHOW` 临时显示倒计时仍保留。`DockStateMachine.onTempShowTimeout()` 到时仅在 `hiddenByApp=true` 时隐藏，否则转为 `SHOWN`，不得将其与已删除的全局空闲自动隐藏混淆。
- 黑名单默认值和迁移分别见 `util/DockBlacklistDefaults.kt`、`util/DockBlacklistMigration.kt`；日常读写经 `Prefs` 转发至 `DockBlacklistPreferences`，不改变上述未知前台默认显示策略。

## 6. 车控接口和安全边界

### 6.1 当前默认通道表

以下是 `config/VehicleChannels.kt` 与可执行 `VehicleController.kt` 的当前摘要。运行时可在设置页“车控通道”覆盖默认数字，所以这是默认配置，不等于所有车型最终实际使用的 zone。

| 通道 | functionId | 默认 zone | 当前值域/写法 |
| --- | --- | --- | --- |
| 手动风量 | `0x10020100` | `0x8` | 低字节 0..9，0=关、1..9 档 |
| AUTO 风量 | `0x10020200` | `0x8` | 实际映射：1→`0x10020204`、2→`01`、3→`02`、4→`03`、5→`05` |
| AUTO 总开关 | `0x10010200` | `0x80000000` | 裸值 0/1，裸值优先 |
| 温度（主驾/副驾） | `0x10060100` | 1 / 4 | 目标范围 15.5..28.5℃；默认尝试 GlyCar/Customize 浮点路径；关闭开关时使用整数路径，步进入口另有浮点失败后的整数回退 |
| 双温同步 | `0x10060500` | `0x80000000` | 0/1 |
| AC | `0x10010300` | `0x80000000` | 复合值优先，裸值兜底；读回可能是 `0xff` |
| 风向 | `0x10070100` | `0x8` | `func + 1..7`，位掩码语义尚未逐项实车确认 |
| 内外循环 | `0x10030100` | `0x80000000` | 外循环裸值 0，内循环复合 `0x10030101` |
| 前挡加热 | `0x10040100` | `0x80000000` | `writeSwitch`，复合值优先、裸值兜底 |
| 后挡除霜 | `0x10040300` | `0x80000000` | `writeSwitch`，复合值优先、裸值兜底 |
| 主/副驾座椅通风 | `0x10050100` | 1 / 4 | 0..3 档 |
| 主/副驾座椅加热 | `0x10050200` | 1 / 4 | 0..3 档 |
| 方向盘加热 | `0x10090100` | 1 | 0..3 档，不是简单布尔开关 |

重要纠偏：

- AUTO 当前可执行代码确实使用专用 `0x10020200` 通道和乱序映射；`VehicleChannels.kt` 中“01…05 顺序映射”的说明以及 `VehicleController.kt` 中“手动风量写 10”的部分注释属于旧说明，不能覆盖实际执行语句。
- 内外循环的旧 zone `0x8` 是已确认的错误配置；当前默认全局 zone 为 `0x80000000`。
- 温度不能简单写成“Customize 浮点通道已禁用”：`Prefs.tempCustomizeWriteEnabled` 当前默认是 true。`tempStepWrite` 会过滤上下限/哨兵，先尝试 GlyCar/Customize，失败后可继续整数候选；`writeTempValue`（绝对设温）则在开关开启时只尝试浮点链，开关关闭才进整数分支。整数低字节按 `round(℃ / 0.2)` 编码。不要把不同入口的回退条件混为一谈，也不能凭此保证车型/权限兼容。
- 旧文档中“开关统一为 10/11”是某次外部设备/历史日志的层级编码，不应替代当前 `VehicleController` 和 `VehicleChannels` 的实际值域。

### 6.2 写入保护

- `VehicleController.writeChannel` 先检查通道是否配置、`Prefs.vehicleWriteEnabled` 和 `VehicleBridge.isReady`，再按复合值、裸值、备用 zone 逐级尝试；命中备用 zone 会保存配置。
- `writeSwitch` 先调用 `VehicleBridge.supportOf`，再依 `bareFirst` 选择裸值或复合值优先，并通过 `verifyWrite` 和延迟回读记录结果。
- `VehicleBridge.writeInt` 会先探测支持状态；不支持或异常要记录权限拒绝/非法值，不得盲写。厂商 HAL 对不支持的 `(functionId, zone, value)` 可能触发 native abort，Java 层不能靠 try/catch 兜底。
- 温度回读的边界伪值和 `0xfd`、`0xfe`、`0xff` 等无效哨兵不能无条件覆盖已有状态；当前代码会保留乐观值或继续走安全候选路径。
- `verifyWrite` 的返回值只是当前写调用是否返回成功，延迟回读会另记 `NO_EFFECT`；不能因为方法名含“verify”就把 `true` 写成“真车已生效”，也不能把某些通道的 `false` 一律解释为被拒绝。
- 车控初始化和写入受崩溃熔断开关控制。不要把“熔断后暂时只读”误写成车控问题已修复。

### 6.3 连接、升级和验证

- 车控通过反射适配厂商接口，编译期不依赖厂商 jar；普通应用不能直接获得 oneOS 系统应用的 platform 权限。
- `App.kt` 的 `preserveUiUpgrade` 只匹配旧版本非空且版本字符串以 `3.13.105(` 到 `3.13.112(` 或 `4.0(` 开头的升级。它不是“所有升级都保留一切”的泛化策略。
- 未命中保留分支时，版本变更会重置 crash streak、safe level、车控写入/API/温度 watcher 开关，并记录新版本；命中分支则只更新 lastVersion，保留现有开关和安全等级。后续修改版本兼容逻辑时必须重新核对这段可执行条件。
- 当前代码、模拟器和逻辑测试不能代替真实车型回归；车控写入、车型信号、系统权限和数值语义均需日志或真车证据。
- `VehicleClient.kt`、`VehicleControlService.kt` 保留了独立 `:car` 进程的实验性客户端/服务实现，但当前 Manifest 未注册该服务、当前调用点也直接使用 `VehicleController`；不要把这组文件写成当前已启用的 native 崩溃隔离边界。

## 7. 音乐、歌词和权限

### 7.1 当前模块关系

- 音乐卡通过 `MediaBridge` 获取活跃媒体会话，通知使用权可作为媒体信息的补充来源。
- 歌词使用独立的 `LyricMonitor`、`LyricOverlayService` 和 `LyricWindows`，与 Dock 音乐卡的控制动作解耦。
- 歌词来源依次可包括广播/MediaSession 自带字段、本地 `.lrc`、联网服务和手动导入/粘贴；切歌会清理旧歌词，进度归零会延迟重新抓取。
- 歌词窗口支持行数、字号、颜色、透明度、尺寸、偏移、锁定穿透、开机自启和来源配置；详细 UI 以 `LyricsSettingsPage.kt` 当前实现为准。

### 7.2 安装权限

- Dock 和歌词窗口需要“显示在其他应用上层”权限。
- 音乐卡和歌词精确进度依赖通知使用权；安装新 APK 后如果系统重置该特殊授权，需要重新开启。
- 本地 `.lrc` 兜底才需要相应存储访问；不是所有歌词来源都要求存储权限。
- Dock 黑名单前台检测使用无障碍服务；未取得前台包名时默认显示 Dock，不回退到空闲自动隐藏。Manifest 中另有通知监听服务、Dock/歌词前台服务和开机接收器。
- `AndroidManifest.xml` 同时声明 `QUERY_ALL_PACKAGES` 与对车机包/Launcher 的显式 `queries`；能否枚举或启动目标仍受 Android 版本、系统策略和实际授权影响，不能把声明权限等同于已经取得权限。
- Manifest 还声明 `WRITE_SECURE_SETTINGS`、`MANAGE_EXTERNAL_STORAGE` 等特殊权限；普通安装不会自动授予，不能将声明写成权限已生效。

## 8. 诊断、日志和崩溃排查

### 8.1 当前上传行为

`LogUploader` 的默认端点解码后为：

```text
https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/upload
```

当前默认开启上传；用户在设置页填写的有效 endpoint 优先于默认值，空值及已知历史死链会在启动迁移时纠正。网络可用时自动上传，失败会进入本地队列并在网络恢复或下次上传动作时补发；不能承诺任何网络环境下都即时成功。

上传类型包括：

- `live`：约 1.5 秒或累计约 40 行批量上传。
- `crash`：Java 未捕获异常和日志尾部。
- `prev_session`：下次启动补传上次会话日志，覆盖 native 崩溃无法触发 Java 回调的情况。
- `diag`：车控后端、方法签名、温度通道和首帧原始值。
- `manual`：设置页手动快照。

单条 payload 上限当前为 22,000 字符，超长内容保留头尾；失败队列包括 `files/logs/live_queue.txt` 和 `files/logs/pending.jsonl`。

### 8.2 本地日志文件和服务

应用私有目录的关键文件：

- `files/logs/session.log`
- `files/logs/prev.log`
- `files/logs/native_crash.txt`、`native_crash.txt.err`
- `files/last_crash.txt`

根目录 `log_server.py` 是纯 Python 标准库的局域网接收服务，默认监听 `0.0.0.0:8080`，按天写入 `log_server_logs/YYYY-MM-DD.jsonl`。它只适用于开发诊断，不是 CI 发布服务。远程服务端的子域名可能因重新部署变化；修改默认端点后必须重新核对代码并重新构建。

常用只读诊断示例（不得借此操作用户设备）：

```bash
adb -s <serial> logcat -s CarDockBar
adb -s <serial> shell run-as com.car.dockbar ls files/logs/
adb -s <serial> shell run-as com.car.dockbar cat files/logs/session.log
```
远程接收端源码在 `log_upload_service/server.py`，只依赖 Python 标准库，提供 `/health`、`/logs` 和 `/download`。`/logs` 默认返回最近 200 条，可用 `limit`（上限 5000）和 `app` 筛选。`date=YYYY-MM-DD` 会包括该日基础文件及其分片；`date=YYYY-MM-DD.2` 可选相应分片前缀。排查全天时不要只读一个分片，远程域名是否仍可用需实际检查，本文不声称已拉取在线日志。


### 8.3 崩溃降级

- App 启动时先轮转上次会话、收集崩溃证据并尝试补传，再开始当前会话的实时日志。
- 上一次运行少于约 15 秒并发生崩溃会增加 streak；连续崩溃会逐级关闭高风险能力，当前阶梯封顶 L3。
- 车控写入熔断后仍可保留读取；“多次闪退后又能启动”可能只是熔断生效，不能直接说明根因消失。
- 版本变更的重置行为以 `App.kt` 当前代码为准，不要照抄旧 Handoff 中“所有升级都会重置”或“所有升级都会保留”的单一说法。

## 9. 历史车控和逆向参考（历史，不是当前实现）

### 9.1 厂商接口契约

原 `反编译参考.md` 的来源是用户提供的 oneOS 系统 APK（`oneOS_Dock`、`oneOS_Hvac`、`oneOS_Launcher3`），产出时间约为 2026-10-15。它用于理解厂商接口，不替代当前源码。

从该资料保留的稳定要点：

- `ICar.getICarFunction()`、`getSensorManager()`、`getCarInfoManager()` 是厂商入口；`ICarFunction` 同时存在无 zone 和带 zone 的 int 读写方法，以及 Customize float 读写方法。
- watcher 必须按精确方法名分派：`onFunctionChanged`、`onFunctionValueChanged`、`onCustomizeFunctionValueChanged`、`onSupportedFunctionStatusChanged`、`onSupportedFunctionValueChanged` 的参数个数/类型不同，不能用 `contains("Changed")` 猜参数。
- `FunctionStatus` 应按 `name()` 识别 `notactive`、`error`、`notavailable`、`available`，不要依赖 ordinal。
- 系统 Dock 的区域分工：风量/风向使用 `ZONE_ROW_1_ALL = 0x8`；温度/座椅使用主驾 1、副驾 4；整车级使用 `0x80000000`。
- 通用无效值为 `0xfd`、`0xfe`、`0xff`；它们与某些外部设备日志里的 10/11 开关编码不是同一层。
- 温度理论范围为 15.5..28.5℃、步进 0.5℃；但当前车型是否真的返回有效浮点值仍需当前日志/真车确认。
- 座椅通风、加热、按摩的官方功能号分别包括 `0x10050100`、`0x10050200`、`0x10050700`；早期项目曾整体错位一档，不能复用旧值。

### 9.2 oneOS 空调和 Dock 跳转

- `com.geely.hvac` 的主面板是 `GlyMainActivity`，`enter_hvac_page=0` 表示空调主面板，`1` 表示座椅页；`SeatSetDetailActivity` 是可选的独立座椅页。
- Provider `com.geely.hvac` 的 `projection[0]` 可为 `open`、`show`、`close`、`getVisibility`，但当前项目的目标 Activity 路径通常优先命中，Provider 是备用路径。
- oneOS 系统应用拥有平台级权限；普通应用不能把这些权限当作自己的能力，只能通过当前允许的反射/跳转/系统授权路径工作。
- 原厂布局使用数字 itemId、区域、位置、图标、是否可移动、初始值和启动包名等字段；当前项目已经改为 `DockEntry`/`Builtin` 模型，不应把原厂 JSON 当作当前持久化格式。
- 原厂车控 itemId 的历史映射包括：AUTO `400002`、前/后除霜 `400003/400004`、AUTO 风量 `400005`、循环 `400006`、座椅加热/通风 `400009..400012`、主驾温度 `400013`、双温 `400015`、手动风量 `400018`、风向 `400019`、AC `400020`、方向盘加热 `400022`、座椅入口 `400034`。这些是逆向索引，不是当前 `DockEntry` action 名。

### 9.3 历史 Handoff 中的用户约束和已知坑

以下内容来自 2026-10 的旧交接，保留为背景并已按当前代码纠偏：

- 不随意更改包名、签名、车控能力、实验开关和用户排序；直接使用正式包覆盖测试环境，不额外创造不同包名的测试应用。
- 真实设备操作应保持只读诊断原则：不替用户 tap、swipe、解锁或修改系统设置；用户授权的 APK 安装和日志读取另行执行。
- 早期 1.4.x 版本曾出现 native 崩溃、温度通道和座椅 functionId/zone 配置错误；这些版本的修复时间线不能当作当前 bug 状态。
- 早期记录把内外循环写成 zone 8、把前挡写成 `0x10040100` 或 `0x10040200` 的混合版本，也出现过 10/11 开关值和 `getCustomizeFunctionValue` 是否可用的相互冲突。当前行为只认第 6 节和源码。
- 旧文档中“当前遗留问题”包括前后挡/方向盘加热真车效果、AQI 值域、内外循环回读、座椅记忆 `0x2d400200`、极速降温组合动作和歌词兼容性；除非有新日志，不要把它们改写成已解决或仍必现。
- “极速降温”没有独立信号时只能作为 AC 开、风量最大、温度最低的组合动作假设；不能声称有单一车控通道。
- 外部资料曾放在用户桌面/工作区的绝对路径下；这些路径不是仓库依赖，换机器后不要假设存在。
### 9.4 历史原始参考表与资料定位

下面的附录保留原 `反编译参考.md` 的非重复静态接口、ID、区域和布局结论，便于离线查找；它们是厂商 APK 静态分析结果，未逐项重新验证。旧表中的调用方式和本项目现状冲突时，仍以第 6 节及当前代码为准。

外部原始素材曾位于 `C:/Users/17900/Desktop/DBPLAY改/车机系统app/`；Dock smali 曾位于 `C:/Users/17900/Documents/MuMuSharedFolder/oneOS_Dock/oneOS_Dock_src/`，Hvac 解包目录曾位于 `C:/Users/17900/WorkBuddy/2026-10-15-20-30-09/work/apkdec/s_oneOS_Hvac/`。桌面 `DBPLAY改/研究结果/` 下另有 Dock/桌面/车辆信号和空调研究报告，`饺子互联/DOCCCK/` 下曾有三张弹窗设计图。这些都只是历史定位线索，不是构建依赖，也不保证本机存在。

原“改造说明”描述的是歌词融合和条目驱动 Dock 的早期迁移。当前保留的模块包括 `media/LrcParser.kt`、`media/LrcFinder.kt`、`media/LyricDownloader.kt`、`media/LyricMonitor.kt`、`ui/LyricOverlayService.kt`、`ui/state/DockStateMachine.kt`；旧 `DockEntryViewFactory.kt` 已由 `dock/` 控件模块替代。不要按旧的“17 个默认控件”“上下移动排序”恢复已被十项默认布局和拖拽编辑器替代的功能形态。


## 10. 当前未验证事项和交付边界

以下项目不能仅通过文档整理标为完成：

1. 真车上前挡/后挡、方向盘加热、座椅记忆是否真正生效。
2. 当前车型 `0x10060100` 浮点温度路径、上下限信号和整数回退的实际响应。
3. 风向 1..7 每个组合对应的真实出风方向。
4. 座椅 0..3 每档的实际强度语义。
5. AQI/车内外温度信号的车型值域。
6. 歌词悬浮窗在所有播放器和车型上的兼容性。
7. Android 特殊权限是否因重启、刷机或系统策略被撤销。

验证时必须区分：

- 源码/单元测试证据：能证明代码路径和纯逻辑。
- 模拟器证据：能证明 UI 或无车环境交互，不能证明真实车辆。
- 车机日志证据：能证明某次设备上的调用、返回和回读。
- 真车回归证据：才能确认车型上的实际车控效果。

## 11. 文档合并来源和维护方式

本文件已吸收以下根目录资料的有效内容，并把旧版本号、旧 APK 名称、旧工程路径和未经验证的当前性声明改成了显式历史说明：

- `CURRENT_STATUS.md`：当前状态、默认布局、设置页、日志与模糊入口。
- `HANDOFF.md`：车控接口、崩溃时间线、日志上传、安全模式和用户约束。
- `HANDOVER.md`：更早的车控实测、UI 规范、设备诊断和遗留项。
- `反编译参考.md`：oneOS/ECarX/HVAC/Dock 的逆向接口、区域、功能号和跳转参考。
- `改造说明.md`：歌词悬浮窗、条目驱动 Dock、渐变/模糊入口和安装权限背景。

后续只维护本文件的索引和带证据的当前摘要；若需要补充历史排障，新增“历史”小节并注明日期、来源和是否已被当前源码验证。README 只承担项目描述与构建入口，不再承载技术实现、车控表、日志排障或历史交接内容。


## 附录 A：oneOS 厂商接口与静态索引（历史）

> 以下从原 `反编译参考.md` 迁入，来源为 2026-10-15 的厂商 APK 静态分析，不是当前项目源码描述，也不代表第三方 App 获得系统权限。凡文中“官方”“权威”“已修正”等词仅指该次历史材料。与第 6 节冲突的值域、温度路径、AUTO 编码、权限和 Provider 路由，以当前可执行代码为准。

> 特别纠偏：系统 App 的 Customize 浮点温度示例不能证明普通 App 安全；AUTO 实际使用 Controller 的乱序映射；两参 `setFunctionValue` 的第二参是 value；`0x7d8` 只记录原厂使用的窗口值，不能说与本项目 `TYPE_APPLICATION_OVERLAY` 数值相等或权限等价。

### A.1 ECarX adaptapi 权威接口签名

**来源**：`oneOS_Dock` → `com.geely.dockbar.domain.vehicleusecase.SignalHelper2`
（该类持有 `ICar mCar` / `ICarFunction mCarFunction` / `ISensor mSensorManager` 三个字段）

```java
// 入口
Car.create(Context)                 → ICar
ICar.getICarFunction()              → ICarFunction
ICar.getSensorManager()             → ISensor
ICar.getCarInfoManager()            → ICarInfo

// ICarFunction
int     getFunctionValue(int functionId)
int     getFunctionValue(int functionId, int areaId)
float   getCustomizeFunctionValue(int functionId)
float   getCustomizeFunctionValue(int functionId, int areaId)
boolean setFunctionValue(int functionId, int value)          // ★ 2 参：第二参是 value，不是 zone！
boolean setFunctionValue(int functionId, int areaId, int value)
boolean setCustomizeFunctionValue(int functionId, float value)
boolean setCustomizeFunctionValue(int functionId, int areaId, float value)
int[]   getSupportedFunctionValue(int functionId)
FunctionStatus isFunctionSupported(int functionId) / (int, int)
boolean registerFunctionValueWatcher(int[] functionIds, ICarFunction.IFunctionValueWatcher)  // ★ 只有数组模式
boolean unregisterFunctionValueWatcher(ICarFunction.IFunctionValueWatcher)

// 异步连接（可选；系统 App 两种写法都存在）
ICar implements IConnectable
  IConnectable.registerConnectWatcher(IConnectable.IConnectWatcher)
  IConnectable.connect()
```

> 注：`AdaptapiHelper` 走 `registerConnectWatcher` + `connect()` 回调后再用；
> `SignalHelper2` 则直接 `Car.create()` 后立即取 `getICarFunction()`。两种都可行。

#### A.1.1 IFunctionValueWatcher 回调契约（必须按名精确分派）

```java
void onFunctionChanged(int id)
void onFunctionValueChanged(int id, int area, int val)                    // int 通道
void onCustomizeFunctionValueChanged(int id, int area, float val)
void onSupportedFunctionStatusChanged(int id, int area, FunctionStatus st)
void onSupportedFunctionValueChanged(int id, int[] ints)
```

> ⚠️ 反面教材：CarDockBar 早期用 `method.name.contains("Changed")` 猜回调，然后取
> `args[0]=fid / args[1]=zone / args[2]=value`。这个写法对 5 个方法里有 2 个是错的
> （`onFunctionChanged` 只有 1 个参数；`onSupportedFunctionValueChanged` 第二参是 `int[]`）。
> **必须按方法名精确匹配。**

#### A.1.2 FunctionStatus 枚举

`notactive` / `error` / `notavailable` / `available`（用 `name()` 判断，别依赖 ordinal）

---

### A.2 官方属性 ID 全表（`com.geely.os.car.GlyCarPropertyIds`）

**来源**：`oneOS_Hvac` → `smali/com/geely/os/car/GlyCarPropertyIds.smali`（共 156 条 HVAC 项）

#### A.2.1 空调（HVAC_FUNC\_*）

| 常量                                     | 值            | 说明                     |
| -------------------------------------- | ------------ | ---------------------- |
| `HVAC_FUNC_POWER`                      | `0x10010100` | 空调总开关                  |
| `HVAC_FUNC_AUTO`                       | `0x10010200` | AUTO                   |
| `HVAC_FUNC_AC`                         | `0x10010300` | A/C 压缩机                |
| `HVAC_FUNC_AC_MAX`                     | `0x10010400` | MAX A/C                |
| `HVAC_FUNC_CLIMATE_ZONE`               | `0x10010500` | 分区                     |
| `HVAC_FUNC_POWER_VR`                   | `0x10011100` | 语音开关                   |
| `HVAC_FUNC_FAN_SPEED`                  | `0x10020100` | **风量（手动）**             |
| `HVAC_FUNC_AUTO_FAN_SETTING`           | `0x10020200` | **风量（自动）**             |
| `HVAC_FUNC_CIRCULATION`                | `0x10030100` | **内外循环**               |
| `HVAC_FUNC_CIRCULATION_TIMER`          | `0x10030200` | 循环定时                   |
| `HVAC_FUNC_CIRCULATION_LONG_TOUCH`     | `0x10030300` | 长按循环                   |
| `HVAC_FUNC_DEFROST_FRONT`              | `0x10040100` | 前除霜                    |
| `HVAC_FUNC_DEFROST_FRONT_MAX`          | `0x10040200` | 前除霜最大                  |
| `HVAC_FUNC_DEFROST_REAR`               | `0x10040300` | 后除霜                    |
| `HVAC_FUNC_AUTO_DEFROST_FRONT`         | `0x10040500` | 自动前除霜                  |
| `HVAC_FUNC_AUTO_DEFROST_REAR`          | `0x10040400` | 自动后除霜                  |
| `HVAC_FUNC_TEMP`                       | `0x10060100` | **温度（float ℃）**        |
| `HVAC_FUNC_TEMP_MAX`                   | `0x10060200` | 温度上限                   |
| `HVAC_FUNC_TEMP_MIN`                   | `0x10060300` | 温度下限                   |
| `HVAC_FUNC_TEMP_STEP`                  | `0x10060400` | 温度步进                   |
| `HVAC_FUNC_TEMP_DUAL`                  | `0x10060500` | 主副驾同步                  |
| `HVAC_FUNC_TEMP_UNIT`                  | `0x10060600` | 温度单位（`0x10060602` = ℉） |
| `HVAC_FUNC_BLOWING_MODE`               | `0x10070100` | **风向（位掩码 1..7）**       |
| `HVAC_FUNC_SWEEPING_MODE`              | `0x10070200` | 扫风                     |
| `HVAC_FUNC_DIRECTION_MODE`             | `0x10070300` | 风向模式                   |
| `HVAC_FUNC_ECO_SWITCH`                 | `0x10080100` | ECO                    |
| `HVAC_FUNC_IONS_SWITCH`                | `0x10080500` | 负离子                    |
| `HVAC_FUNC_INTELLIGENT_DEODORIZATION`  | `0x10080600` | 智能除味                   |
| `HVAC_FUNC_STEERING_WHEEL_HEAT`        | `0x10090100` | 方向盘加热                  |
| `HVAC_FUNC_AUTO_STEERING_WHEEL_HEAT`   | `0x10090200` | 自动方向盘加热                |
| `HVAC_FUNC_PRE_CLIMATISATION`          | `0x100a0100` | 预空调                    |
| `HVAC_FUNC_POST_CLIMATISATION`         | `0x100a0200` | 后空调                    |
| `HVAC_FUNC_AI_POWER`                   | `0x100a0400` | AI 空调                  |
| `HVAC_FUNC_AIR_FRAGRANCE`              | `0x100b0100` | 香氛                     |
| `HVAC_FUNC_AIR_FRAGRANCE_TYPE`         | `0x100b0200` | 香氛类型                   |
| `HVAC_FUNC_AIR_FRAGRANCE_LEVEL`        | `0x100b0300` | 香氛浓度                   |
| `HVAC_FUNC_AIR_FRAGRANCE_SLOT`         | `0x100b0400` | 香氛槽位                   |
| `HVAC_FUNC_AIR_FRAGRANCE_TYPE_ID`      | `0x100b0500` | 香氛类型 ID                |
| `HVAC_FUNC_AUTO_ION`                   | `0x100c0100` | 自动负离子                  |
| `HVAC_FUNC_CLIMATE_LOCK`               | `0x10100200` | 空调锁定                   |
| `HVAC_FUNC_G_CLEAN`                    | `0x10100400` | G-Clean                |
| `HVAC_FUNC_VENTILATION_ONTIME`         | `0x10100700` | 通风时长                   |
| `HVAC_FUNC_INTELLIGENT_RECOMMENDATION` | `0x10120100` | 智能推荐                   |
| `HVAC_FUNC_ELECTRICAL_AIR_VENT`        | `0x10140100` | 电动出风口                  |
| `HVAC_FUNC_FILTER_ELEMENT_LIFE`        | `0x10140300` | 滤芯寿命                   |
| `FUNC_UNIT_TEMPERATURE`                | `0x25010300` | 温度单位（通用）               |

#### A.2.2 座椅（★ 关键修正）

| 常量                                     | 值                | 说明     |
| -------------------------------------- | ---------------- | ------ |
| `HVAC_FUNC_SEAT_VENTILATION`           | **`0x10050100`** | 座椅通风   |
| `HVAC_FUNC_SEAT_HEATING`               | **`0x10050200`** | 座椅加热   |
| `HVAC_FUNC_AUTO_SEAT_VENTILATION_TIME` | `0x10050400`     | 自动通风时长 |
| `HVAC_FUNC_AUTO_SEAT_HEATING`          | `0x10050500`     | 自动加热   |
| `HVAC_FUNC_AUTO_SEAT_HEATING_TIME`     | `0x10050600`     | 自动加热时长 |
| `HVAC_FUNC_SEAT_MASSAGE`               | `0x10050700`     | 座椅按摩   |
| `HVAC_FUNC_AUTO_SEAT_MASSAGE_TIME`     | `0x10050900`     | 自动按摩时长 |
| `HVAC_FUNC_SEAT_MASSAGE_SWITCH`        | `0x10050a00`     | 按摩开关   |
| `HVAC_FUNC_SEAT_MASSAGE_PROGRAM`       | `0x10050b00`     | 按摩程序   |

> ⚠️ **早期项目把通风写成 `0x10050000`、加热写成 `0x10050100`，两者整体错位一档，
> 导致座椅功能全部读写无效。已于 2026-10-15 修正。**

#### A.2.3 座椅位置 / 舒适（`SETTING_FUNC_*`，来自座椅调节模块）

`SETTING_FUNC_SEAT_BACKREST=0x2d030200`、`SEAT_CUSHION_TILT=0x2d030100`、`SEAT_HEIGHT=0x2d020200`、
`SEAT_LENGTH=0x2d020100`、`SEAT_LUMBAR_HEIGHT=0x2d060100`、`SEAT_LUMBAR_EXTENDED=0x2d060200`、
`SEAT_LEG_SUPPORT_HEIGHT=0x2d080100`、`SEAT_LEG_SUPPORT_LENGTH=0x2d080200`、
`SEAT_POSITION_SAVE=0x2d400100`、`SEAT_POSITION_SET=0x2d400200`、`SEAT_NUM=0x2d410f00`、
`SEAT_PHYSIOTHERAPY_SWITCH=0x2d500100`、`SEAT_PHYSIOTHERAPY_PROGRAM=0x2d500200`

---

### A.3 官方区号全表（`com.geely.os.car.GlyCarAreaId`）

**来源**：`oneOS_Hvac` → `smali/com/geely/os/car/GlyCarAreaId.smali`

#### A.3.1 座椅区（**温度 / 座椅 / 车门都用这一套位掩码**）

| 常量                  | 值       | 说明          |
| ------------------- | ------- | ----------- |
| `SEAT_ROW_1_LEFT`   | `0x1`   | **前左 = 主驾** |
| `SEAT_ROW_1_CENTER` | `0x2`   | 前中          |
| `SEAT_ROW_1_RIGHT`  | `0x4`   | **前右 = 副驾** |
| `SEAT_ROW_2_LEFT`   | `0x10`  | 后左          |
| `SEAT_ROW_2_CENTER` | `0x20`  | 后中          |
| `SEAT_ROW_2_RIGHT`  | `0x40`  | 后右          |
| `SEAT_ROW_3_LEFT`   | `0x100` | 三排左         |
| `SEAT_ROW_3_CENTER` | `0x200` | 三排中         |
| `SEAT_ROW_3_RIGHT`  | `0x400` | 三排右         |


#### A.3.2 分区（Zone）

| 常量                                     | 值                             |
| -------------------------------------- | ----------------------------- |
| `ZONE_ALL`                             | `-0x80000000`（即 `0x80000000`） |
| `ZONE_ROW_1_ALL`                       | `0x8` ← **风量 / 风向用这个**        |
| `ZONE_ROW_1_LEFT`                      | `0x1`                         |
| `ZONE_ROW_1_CENTER`                    | `0x2`                         |
| `ZONE_ROW_1_RIGHT`                     | `0x4`                         |
| `ZONE_ROW_2_ALL`                       | `0x80`                        |
| `ZONE_ROW_2_LEFT` / `CENTER` / `RIGHT` | `0x10` / `0x20` / `0x40`      |
| `ZONE_ROW_3_ALL`                       | `0x800`                       |
| `ZONE_ROOF_TOP`                        | `0x10000000`                  |

> ✅ **两套区号的分工已确认**：
>
> - **风量 / 风向** → `ZONE_ROW_1_ALL = 0x8`
> - **温度 / 座椅** → `SEAT_ROW_1_LEFT = 1`（主驾）、`SEAT_ROW_1_RIGHT = 4`（副驾）

#### A.3.3 其他

`DRIVER_SIDE_LEFT=0x100301`、`DRIVER_SIDE_RIGHT=0x100302`、
`DOOR_ROW_1_LEFT=0x1`…、`WINDOW_FRONT_WINDSHIELD=0x1`、`WINDOW_ROOF_TOP=0x4`、
`VEHICLE_AREA_TYPE_SEAT=0x3`、`VEHICLE_AREA_TYPE_DOOR=0x4`

---

### A.4 HVAC 取值编码（`com.geely.dockbar.domain.vehicleusecase.HvacConstants`）

**来源**：`oneOS_Dock` → `.../HvacConstants.smali`

#### A.4.1 通用哨兵 / 状态

| 常量                              | 值       | 含义 |
| ------------------------------- | ------- | -- |
| `FUNCTION_COMMON_VALUE_OFF`     | `0x0`   | 关  |
| `FUNCTION_COMMON_VALUE_ON`      | `0x1`   | 开  |
| `FUNCTION_COMMON_VALUE_ERROR`   | `0xfd`  | 错误 |
| `FUNCTION_COMMON_VALUE_NONE`    | `0xfe`  | 无  |
| `FUNCTION_COMMON_VALUE_UNKNOWN` | `0xff`  | 未知 |
| `STATUS_ERROR`                  | `-1`    |    |
| `STATUS_NOT_ACTIVE`             | `-2`    |    |
| `STATUS_ACTIVE`                 | `-3`    |    |
| `STATUS_NOT_AVAILABLE`          | `-0xff` |    |

> ⚠️ 读到 `0xfd / 0xfe / 0xff` 一律当「无有效读数」，**不要覆盖已有状态**。
> 注意这与第三方 ESP32 破解读到的 `power:11 / ac:10 / recirc:11` 是**不同层**的编码，
> `boolOf()` 两套都兼容即可。

#### A.4.2 温度

| 常量                          | 值                     |
| --------------------------- | --------------------- |
| `TEMP_C_MIN`                | `"15.5"` ← **可调下限 ℃** |
| `TEMP_C_MAX`                | `"28.5"` ← **可调上限 ℃** |
| `TEMP_F_MIN` / `TEMP_F_MAX` | `"59"` / `"85"`       |
| `MAX_SHOW_TAMP`             | `"HI"`                |
| `MIN_SHOW_TAMP`             | `"LO"`                |

**写入方式**：`setCustomizeFunctionValue(0x10060100, areaId, ℃ float)`，步进 **0.5℃**（华氏 1.0）。
**读取方式**：`getCustomizeFunctionValue(0x10060100, areaId)` → 直接是 ℃。

温度调节实证（`TempSignalClick.addLeftTemp`）：

```java
float cur = leftTempSignal.getValue();        // getCustomizeFunctionValue(0x10060100, 1)
float max = maxTempSignal.getValue();         // 上限
if (cur >= max) return;                       // 到顶不写
if (tempUnit == 0x10060602) cur += 1.0f;      // 华氏步进
else                        cur += 0.5f;      // 摄氏步进
leftTempSignal.setValue(cur);                 // setCustomizeFunctionValue(0x10060100, 1, cur)
```

#### A.4.3 风量

- **手动**：`HVAC_FUNC_FAN_SPEED (0x10020100)`，值 = `0x10020101 .. 0x10020109`（1..9 档），`0` = 关
- **自动**：`HVAC_FUNC_AUTO_FAN_SETTING (0x10020200)`，值 = `0x10020201 .. 0x10020205`（1..5 档）
- 字符串常量：`MANUAL_FAN_SPEED_LEVEL_1..9`、`MANUAL_FAN_SPEED_LEVEL_OFF="off"`、`MANUAL_FAN_SPEED_LEVEL_AUTO="10"`

#### A.4.4 座椅（`SeatFanSignalClick` 实证）

```java
// 通风 0x10050100：读回来的值就是 0x1005010X
switch (cur) {
  case 0x10050101: next = 0x10050102; break;
  case 0x10050102: next = 0x10050103; break;
  case 0x10050103: next = 0x10050101; break;   // 3 档再点回 1 档
  default:         next = 0x10050103;          // 0（关）→ 3 档
}
// 加热 0x10050200 同构
```

字符串常量：`SEAT_LEVEL_1="level1"`、`SEAT_LEVEL_2="level2"`、`SEAT_LEVEL_3="level3"`

#### A.4.5 内部信号 ID 映射（`VehicleSignalManager` 稀疏表）

Dock 内部把 `funcId` 转成自己的信号编号 `0x61axx`：

| funcId                                     | areaId        | 内部信号                              | 含义                           |
| ------------------------------------------ | ------------- | --------------------------------- | ---------------------------- |
| `0x10010100`                               | `-0x80000000` | `0x61a81`                         | HVAC_POWER                   |
| `0x10010200`                               | `-0x80000000` | `0x61a82`                         | HVAC_AUTO                    |
| `0x10010300`                               | —             | `0x61a94`                         | HVAC_AC_MODE                 |
| `0x10010400`                               | —             | `0x61a88`                         | HVAC_AC_MAX                  |
| `0x10020100`                               | `0x8`         | `0x61a92`                         | HVAC_FAN_SPEED               |
| `0x10020200`                               | `0x8`         | `0x61a85`                         | HVAC_AUTO_FAN_SPEED          |
| `0x10030100`                               | —             | `0x61a86`                         | HVAC_CYCLE_MODE              |
| `0x10040200`                               | —             | `0x61a83`                         | HVAC_DEFROST_FRONT           |
| `0x10040300`                               | —             | `0x61a84`                         | HVAC_DEFROST_REAR            |
| `0x10050100`                               | `1` / `4`     | `0x61a8a` / `0x61a8c`             | **LEFT / RIGHT VENTILATION** |
| `0x10050200`                               | `1` / `4`     | `0x61a89` / `0x61a8b`             | **LEFT / RIGHT HEAT**        |
| `0x10050700`                               | `1` / `4`     | `0x61a98` / `0x61a99`             | LEFT / RIGHT MASSAGE         |
| `0x10050a00`                               | `1` / `4`     | `0x61a9a` / `0x61a9b`             | MASSAGE_SWITCH               |
| `0x10060100`                               | `1` / `4`     | `0x61a8d` / `0x61a8e`             | **LEFT / RIGHT TEMP**        |
| `0x10060200`                               | —             | `0x61a90`                         | HVAC_MAX_TEMP                |
| `0x10060300`                               | —             | `0x61a91`                         | HVAC_MIN_TEMP                |
| `0x10060500`                               | —             | `0x61a8f`                         | HVAC_TEMP_DUAL_SYNC          |
| `0x10060600`                               | —             | `0x61a9c`                         | HVAC_TEMP_UNIT               |
| `0x10070100`                               | `0x8`         | `0x61a93`                         | HVAC_BLOWING_MODE            |
| `0x10080100`                               | —             | `0x61a87`                         | HVAC_ECO                     |
| `0x10090100`                               | —             | `0x61a96`                         | STEERING_WHEEL_HEAT          |
| `0x100b0100`                               | —             | `0x61a97`                         | HVAC_FRAGRANCE               |
| `0x23030100` / `0x23040100` / `0x23100100` | —             | `0x61a94` / `0x61a95` / `0x61a96` | 其他                           |

---

### A.5 oneOS_Hvac（`com.geely.hvac`）对外接口

#### A.5.1 导出组件（Manifest 实测）

| 类型       | 组件                                                       | 说明                             |
| -------- | -------------------------------------------------------- | ------------------------------ |
| Activity | `com.geely.hvac.activity.GlyMainActivity`                | **主面板**（空调 + 座椅 + 香氛）          |
| Activity | `com.geely.hvac.activity.SeatSetDetailActivity`          | **座椅设置详情页**（独立导出，可直达）          |
| Activity | `com.geely.hvac.activity.BuyFragranceActivity`           | 香氛购买                           |
| Provider | `com.geely.hvac.provider.HvacCommunicateContentProvider` | `authorities = com.geely.hvac` |

#### A.5.2 深链 extra

```java
intent.setComponent(new ComponentName("com.geely.hvac",
        "com.geely.hvac.activity.GlyMainActivity"));
intent.putExtra("enter_hvac_page", 0);   // 0=主空调页  1=座椅页  2=香氛页
```

> ✅ **官方实证**（`OpenAirSeatClickFunction.openAirSeat()`）：系统 Dock 的「座椅」按钮就是
> `GlyMainActivity` + `enter_hvac_page=1`。此处保留原厂行为，不能据此推断本项目当前调用路径。

#### A.5.3 ContentProvider 指令（`hvac/query`）

调用方式：`query(uri, projection, ...)`，**用 `projection[0]` 当指令**。

| 指令                | 行为                                   |
| ----------------- | ------------------------------------ |
| `"open"`          | 打开空调主面板（车机 launcher / widget 用的官方接口） |
| `"show"`          | 显示                                   |
| `"close"`         | 仅触发应用内关闭回调，对外部无实际作用                  |
| `"getVisibility"` | 返回 Cursor，首列 long：`1`=面板可见 / `0`=不可见 |

`insert / update / delete` 均为空实现。
另有静态方法 `setHvacCommunicateListener(...)` / `setAcVisibilityListener(...)` 供同进程或系统侧使用。

> 上述是原厂 APK 的调用契约；本项目当前路由以 `hvac/HvacApp.kt` 和 `dock/DockActions.kt` 为准。

#### A.5.4 权限

`com.geely.hvac` 声明了大量系统级权限（`WRITE_SECURE_SETTINGS`、`CAR_POWER`、
`GRANT_RUNTIME_PERMISSIONS`、`MANAGE_APP_OPS_MODES` 等），是平台签名系统应用。
**普通第三方 App 无法复用这些权限**，车控只能走 adaptapi 反射。

---

### A.6 oneOS_Dock 的 Dock 布局模型

#### A.6.1 预设布局文件（`assets/`）

| 文件                        | 车型         |
| ------------------------- | ---------- |
| `dockPresetData_CX.json`  | CX（51 项）   |
| `dockPresetData_CS.json`  | CS（50 项）   |
| `dockPresetData_DCY.json` | DCY（51 项）  |
| `Ex11VehicleConfig.json`  | EX11（59 项） |

#### A.6.2 布局项字段

```json
{
  "itemId": "400013",          // 控件编码；系统功能为数字，第三方 App 为包名
  "itemName": "主驾温度",
  "itemArea": "dock_main",     // dock_main | dock_hvac_dialog_csd | dock_app_dialog_csd
  "itemPosition": 1,
  "itemIcon": "ic_temp",
  "itemText": "温度",
  "canMove": false,            // 能否拖动
  "editMode": false,           // 是否编辑态可见
  "isSwitch": true,            // 开关型控件
  "extraVal": "26",            // 初始值（温度=℃ 数值；开关=off）
  "extraStatus": "off",
  "clickData": "com.autonavi.amapauto",   // 第三方 App 的启动包名
  "dialogPosId": 2
}
```

#### A.6.3 CX 车型默认 Dock 主栏（`dock_main`）

| pos | itemId | 名称    | 图标            | clickData               |
| --- | ------ | ----- | ------------- | ----------------------- |
| 0   | 105    | MyCar | ic_mycar      | —                       |
| 1   | 400013 | 主驾温度  | ic_temp       | — (extraVal=26)         |
| 2   | 400034 | 座椅    | ic_snow       | —                       |
| 3   | 108    | 360全景 | ic_360        | —                       |
| 4   | 103    | 电话    | ic_phone      | —                       |
| 5   | 104    | 导航    | ic_navigation | `com.autonavi.amapauto` |
| 6   | 102    | 资源库   | all_app       | `com.android.launcher3` |


#### A.6.4 空调浮窗（`dock_hvac_dialog_csd`，CX 14 项）

前除霜(400003)、主驾座椅加热(400009)、主驾座椅通风(400010)、副驾座椅加热(400011)、
副驾座椅通风(400012)、A/C(400020)、循环模式(400006)、方向盘加热(400022)、节能(400007)、
后除霜(400004)、香氛(400023)、同步(400015)、风向切换(400019)、自动(400002)

EX11 车型额外含：Max AC(400008)、主驾按摩(400024)、副驾按摩(400025)、风量(400005)

#### A.6.5 完整 itemId → 处理类映射（`AppClickManager`）

**系统功能码（100–146）**

| code | 处理类                        |   | code        | 处理类                          |
| ---- | -------------------------- | - | ----------- | ---------------------------- |
| 101  | HomeClickFunction          |   | 125         | CommonClickFunction（行车记录仪）   |
| 102  | AllAppClickFunction        |   | 126         | CommonClickFunction（自动泊车）    |
| 103  | PhoneClickFunction         |   | 129         | CommonClickFunction（智能生活）    |
| 104  | MapClickFunction           |   | 131         | SceneModeClickFunction（场景模式） |
| 105  | SettingsClickFunction      |   | 133         | CommonClickFunction（用户手册）    |
| 107  | CommonClickFunction（音量）    |   | 134         | CommonClickFunction（灯光秀）     |
| 108  | CommonClickFunction（360全景） |   | 135         | CarLinkFunction（手机互联）        |
| 110  | CommonClickFunction（图库）    |   | 136         | CommonClickFunction（记忆泊车）    |
| 111  | CommonClickFunction（多媒体）   |   | 146         | GlobalSearchFunction         |
| 112  | AirConditionClickFunction  |   | 118/123/124 | CommonClickFunction          |
| 113  | CommonClickFunction（天气）    |   | 108/126     | CommonClickFunction          |

**车控功能码（4000xx）**

| code   | 处理类                          | 对应通道                                      |
| ------ | ---------------------------- | ----------------------------------------- |
| 400001 | AcPowerSignalClick           | `0x10010100`                              |
| 400002 | AutoSwitchSignalClick        | `0x10010200`                              |
| 400003 | DefrostFrontSignalClick      | `0x10040100`                              |
| 400004 | DefrostRearSignalClick       | `0x10040300`                              |
| 400005 | AutoFanSpeedClick            | `0x10020200`                              |
| 400006 | RecycleSignalClick           | `0x10030100`                              |
| 400007 | EcoSignalClick               | `0x10080100`                              |
| 400008 | AcMaxSignalClick             | `0x10010400`                              |
| 400009 | SeatHeatSignalClick（主驾）      | `0x10050200` / area 1                     |
| 400010 | SeatFanSignalClick（主驾）       | `0x10050100` / area 1                     |
| 400011 | SeatHeatSignalClick（副驾）      | `0x10050200` / area 4                     |
| 400012 | SeatFanSignalClick（副驾）       | `0x10050100` / area 4                     |
| 400013 | TempSignalClick（主驾温度）        | `0x10060100` / area 1                     |
| 400015 | TempDualSignalClick          | `0x10060500`                              |
| 400018 | FanSpeedSignalClick          | `0x10020100`                              |
| 400019 | BolwingModeSignalClick       | `0x10070100`                              |
| 400020 | AcModeSignalClick            | `0x10010300`                              |
| 400022 | SteeringWheelHeatSignalClick | `0x10090100`                              |
| 400023 | AirFragranceSignalClick      | `0x100b0100`                              |
| 400024 | LeftMassageSignalClick       | `0x10050700` / area 1                     |
| 400025 | RightMassageSignalClick      | `0x10050700` / area 4                     |
| 400034 | OpenAirSeatClickFunction     | 跳 `GlyMainActivity` + `enter_hvac_page=1` |

#### A.6.6 第三方 App 包名清单（预设里出现的）

`com.autonavi.amapauto`（高德）、`com.android.launcher3`、`com.geely.appstore`、
`com.geely.calendar`、`com.geely.scene.mode`、`com.geely.dvr`、`com.geely.settings`、
`com.netease.cloudmusic.iot`、`com.thunder.carplay`（雷石 KTV）、`com.autoegg`（今日电台）、
`com.baidu.netdisk.car`、`com.bytedance.byteautoservice`（火山车娱）、
`com.tencent.qqlive.audiobox`（腾讯视频）、`com.bilibili.bilithings`、
`com.ximalaya.ting.android.car.ecarx`、`com.arcvideo.car.video`（爱奇艺）、
`io.dushu.car`（帆书）、`com.migu.miguplay`、`cn.cmvideo.car.play`、
`com.flyme.auto.music`（QQ音乐）、`com.flyme.auto.browser`、
`com.tal.xesvideoapp`、`com.ecarx.pas.apa`（自动泊车）

#### A.6.7 Dock 的窗口实现

- 窗口类型：`0x7d8`（对应 `WindowManager.LayoutParams` 的悬浮层；本项目窗口类型需另看 `DockOverlayService.kt`）
- 常量：`0x7d0`（SCREENSAVER）、`"DOCK_BAR"`、`"STATUS_BAR_POP"`
- 多屏 displayId：`DISPLAY_ID_CSD=0`、`DISPLAY_ID_3D_ENGINE=2`、`DISPLAY_ID_PSD=3`、
  `DISPLAY_ID_DIM=4`、`DISPLAY_ID_LEFT_WIN=7`、`DISPLAY_ID_RIGHT_WIN=8`、`DISPLAY_ID_FULL=0x400`
- 车控信号类都通过 `androidx.lifecycle.MutableLiveData` 派发

#### A.6.8 车控信号类清单（`domain/vehicleusecase/signal/`）

`AbsSignal`（基类，字段 `funcId / areaId / extra`）、
`AirFragranceSignal`、`AirFragranceTypeIdSignal`、`AutoFanSpeedSignal`、`AutoSwitchSignal`、
`BolwingModeSignal`、`CycleModeSignal`、`DefrostFrontSignal`、`DefrostRearSignal`、`EcoSignal`、
`FanSpeedSignal`、`HvacAcMaxSignal`、`HvacAcSignal`、`HvacPowerSignal`、
`LeftMassageSignal`、`LeftMassageSwitchSignal`、`LeftSeatFanSignal`、`LeftSeatHeatSignal`、`LeftTempSignal`、
`MaxTempSignal`、`MinTempSignal`、`RightMassageSignal`、`RightMassageSwitchSignal`、
`RightSeatFanSignal`、`RightSeatHeatSignal`、`RightTempSignal`、
`SeatIntSignal`、`SeatSignal`、`SteeringWheelHeatSignal`、`TempDualSignal`、`TempUnitSignal`

**读写分派规则**（`AbsSignal` 子类统一逻辑）：

```java
// 读
if (areaId == -1) value = vehicleController.getFunctionValue(funcId);
else              value = vehicleController.getFunctionValue(funcId, areaId);
// 温度类（LeftTemp/RightTemp/MinTemp/MaxTemp）改用：
//   getCustomizeFunctionValue(funcId[, areaId]) → float

// 写
if (areaId == -1) vehicleController.setFunctionValue(funcId, value);
else              vehicleController.setFunctionValue(funcId, areaId, value);
// 温度类：
//   setCustomizeFunctionValue(funcId, value) / (funcId, areaId, value)
```

---

### A.7 oneOS_Launcher3（`com.android.launcher3`）

- `sharedUserId = android.uid.system`（平台签名，普通 App 无法复用）
- 导出 Provider：
  - `com.android.launcher3.LauncherProvider` → `authorities = com.android.launcher3.settings`
  - `com.geely.model.provider.InteractiveContentProvider` → `authorities = com.android.launcher3.interactive`
- 导出 Activity：`com.geely.view.carlink.CarLinkStatusActivity`
- 导出 Receiver：`com.geely.TestReceiver`（`android.intent.action.launcher.test`）、
  `com.geely.view.common.receiver.UnInstallResultReceiver`

> 对 CarDockBar 的用途有限：主要确认了「资源库」入口是 `com.android.launcher3`。

---
