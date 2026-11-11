# Banana_Dock 当前实现与交接索引

更新日期：2026-11-11。本文描述当前代码，不把旧交接文档中的故障推测当作当前已验证结论。

## 版本与交付

- 应用名：`Banana_Dock`；包名/namespace：`com.car.dockbar`。
- `versionName`：`4.0`；`versionCode`：`434`。
- Release：`app/build/outputs/apk/release/Banana_Dock-4.0.apk`。
- Debug：`app/build/outputs/apk/debug/Banana_Dock-4.0-debug.apk`。
- 已有 Release 构建通过；同签名覆盖安装保留配置，不需要卸载/清数据。
- 最后一次仅改应用名称和 APK 文件名的包已生成并交付；当时模拟器未连接，不能把这一次改名包描述为已经装入模拟器。

## 用户确认的边界

- 不随意更改包名、签名、现有车控功能、实验开关和用户排序。
- 直接使用正式包覆盖用户的测试模拟器，不额外创建不同包名的测试应用。
- 日常交付不截图；需要效果图时先出设计稿，确认后再落地。
- APK 交付目录为本机桌面 `饺子互联/DOCCCK/新建文件夹/`；APK 不纳入 Git。
- 系统版本主要为 Android 9（API 28）；界面空间按 1920×1080 设计。

## 当前默认条目（10 项）

`HOME → 音乐卡 → 车机设置 → 快捷应用 → 主驾温度组合 → 风向 → 风速组合 → 内外循环 → 座椅 → 副驾温度组合`

只对首次使用/恢复默认生效，不在升级时覆盖用户自定义排列。歌词、音量和加热/通风独立控件仍可手动添加。

## 设置页与文件定位

| 功能 | 当前实现 |
| --- | --- |
| 主设置页与原始回调 | `app/src/main/java/com/car/dockbar/ui/MainActivity.kt` |
| 卡片分组与布局 | `ui/SettingsPageLayout.kt` |
| 独立设置主题/颜色 | `res/values/settings.xml` |
| 外观四选一卡片 | `ui/BackgroundModeTile.kt` |
| Dock 条目编辑 | `ui/DockEntriesActivity.kt` |
| 真实控件离屏绘制预览 | `ui/DockEditorPreviewView.kt` |
| 固定 300ms 编辑长按 | `ui/DockEditorLongPress.kt` |
| 编辑插入排序 | `model/DockEditorOrder.kt` |
| 占位宽度分配 | `ui/DockWidthPlan.kt`、`ui/DockLayout.kt` |
| 常驻应用图标网格 | `ui/ResidentAppsPanel.kt` |
| 常驻规则展示 | `ui/ResidentAppRules.kt` |
| 中文/英文名称排序 | `ui/AppDisplayNameOrder.kt` |
| 手动日志滚动/尾部跟随 | `ui/LogTailScrollView.kt` |

左侧导航为：悬浮 Dock 栏、外观与风格、Dock 图标管理、歌词悬浮窗、常驻应用设置、诊断与日志。顶栏使用原色启动图标和 `Banana_Dock`，诊断入口上方有横线；服务状态运行绿字、未运行红字。版本号在左下状态卡右下方，24sp。

底部避让保持 **128 物理像素**，并非 128dp。仅缩短设置内容的可用区域，背景和分栏竖线延伸到屏幕底部。主页面密度系数为 1.08，条目管理子页为 0.9；不改全局 DPI 或实际 Dock 的图标字号。

## Dock 图标管理

- 上方是未添加的内置控件，候选采用实际 Dock 控件绘制、当前颜色和档位，不是随意替代图标。
- 名称 14sp、提示 11sp 等未加粗编辑页文字后来又按用户要求放大 1.3 倍；加粗标题不参与。
- 下方是离屏渲染生成的 Dock 编辑预览，触摸不传递给真实控件，因此不触发车辆写入、媒体播放或启动应用。
- 固定长按 **300ms**；拖入添加、预览内做插入排序、拖出松手移除。取消拖动不保存。
- 音乐卡、温度/风速组合均作为完整条目移动。
- 第三方应用独立入口，系统/个人分类和搜索；不混入内置控件池，移除只删入口、不卸载应用。
- `dock_entries_explicit_layout` 限制旧配置补齐迁移；用户主动删除音量、快捷应用或删除到空布局后，不应每次读取又自动加回。

## 快捷应用（与 Dock 条目列表区分）

- 共享六个固定槽位，`AppDrawerApps.loadSlots/saveSlots` 用 JSON `null` 保留空位。
- `load()` 只用于计数与批量管理的紧凑列表，不用于绘制固定格子。
- 设置页格子高 98dp，宽度三等分；应用图标 42dp；删除按钮红色实心圆 45×45dp、白色 × 35sp。
- 空位进入批量管理；已填格单击替换；长按互换两个位置。
- 删除不压紧其它格子；批量管理保留未变应用位置，新添加应用优先补空位。
- `ui/AppDrawerPopup.kt` 只作固定槽位读写适配，保留实际 Dock 弹窗的点击启动及长按交换交互。

## 常驻应用设置

- 数据取自实际 `getInstalledApplications`，不把未安装历史包名画成占位图标；不删历史规则记录。
- 系统/个人两栏，5 列、默认 4 行，图标 45×45dp，更多内容分别滚动。
- 名称/包名统一搜索；中文名称按拼音在前，拉丁字母名称按 A–Z 在后，其他名称随后。
- 绿圆白勾表示当前应用前台时 Dock **常驻显示**；取消时该应用前台隐藏。无特别规则的应用沿用原逻辑：默认显示，不因 UI 改版批量反转。
- 检测按钮位于添加按钮右侧，深色底，已开启绿字/未开启红字，直接进入检测权限设置。

## 诊断与日志

排列从上至下：

1. 运行概况（设备、Native 捕获、安全等级、待补传）。
2. 车控开关 / 日志上传。
3. 诊断结果 / 诊断操作。
4. 整行运行日志。

两排卡片分别底部对齐；上传、结果卡片的普通文字放大约 20%。车控四个开关标签删除了括号说明，但回调、开关含义及警告保留。5 个开关和 9 个操作入口均复用原控件，不在 UI 初始化时赋新值。

日志窗口可手动滚动历史；查看历史时暂停跟随，回到底部或清空后恢复。自动滚动不抢走搜索/地址输入焦点，也不滚动整张设置页。

`App.kt` 对此次纯 UI 升级（包含当前 4.0）保留已有开关与安全等级，不走旧的“升级恢复车控全功能”重置路径；首次安装与崩溃熔断机制未重新设计。未来修改版本需留意这个按版本匹配的兼容逻辑，不能只改版本号而忽略开关保存要求。

日志服务可按用户提供的 `?date=2026-11-11.2` 形式查询日期分片。此处只记录查询约定，尚未据此重新核验服务器日志或枚举所有分片。

## 透明与模糊入口的真实含义

“透明”是不画底色的透明悬浮窗。当前“模糊（测试中）”并非高斯模糊，而是 `GrayOverlayDrawable` 的纯透明底＋灰色层＋独立合成透明度：不抓屏、不录屏、不申请录屏权限、不真正模糊后方内容。

旧的 `BehindBlur`、`WallpaperFrost` 文件及部分历史注释不能代表当前入口生效路径。不要把蒙层失焦感描述成跨窗口模糊。

## 构建与签名

- `minSdk 28`、`compileSdk/targetSdk 34`、Java/Kotlin 17。
- NDK `28.2.13676358`，`ndk-build`；ABI 为 arm64-v8a、armeabi-v7a、x86_64。
- Release 使用 R8 与资源缩减，当前 `signingConfig signingConfigs.debug`。
- 本机证书 SHA-256：`55A8613C15D70D392AE507161CB27C1FE707EE680200977B7456EA0D9E2A3461`。
- Actions 需要 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个 **Secrets**；不得提交密钥/密码/Base64 文件。
- 仓库现有 `.github/workflows/release-apk.yml` 会解码四个 Secrets，对 Gradle 输出的 APK 重签并校验包名和版本，然后生成 `update.json`、`SHA256SUMS` 并发布 GitHub Release。没有在本次文档更新中代为配置仓库 Secrets，是否已配置需仓库管理员确认。
- 默认分支发布关键词提交或手动 dispatch 会请求构建/发布；普通文档提交避免发布关键词，不要为更新说明无意创建 Release。
- 当前 workflow 发布文件名/标题仍为 `CarDockBar-4.0.apk` / `CarDockBar 4.0`，与本地 `Banana_Dock-4.0.apk` 的命名不同。此处如实记录，未擅自更改 CI。
- `.github/signing/ci-test.jks` 是远程已有的测试文件，不是本机发布证书。没有把本机真实 keystore、生成的 Base64 或密码加入 Git。

## 历史资料与验证边界

`HANDOFF.md` 和 `HANDOVER.md` 中的 2026-10 月闪退、主线程和接口调试记录为历史背景，不能据此声称当前仍存在同样故障或已经获得系统权限。当前 UI、拖拽与排序有模拟器/逻辑测试证据；所有真实车辆能力仍依赖车机权限与车型接口，不能把模拟器验证写成真车验证。
