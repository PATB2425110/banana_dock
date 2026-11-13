# Dock 纯黑名单显隐逻辑设计

## 意图

将 Dock 的前台应用显隐规则从“常驻显示名单 + 默认隐藏名单 + 自定义隐藏名单”改为单一黑名单模式：黑名单中的应用隐藏 Dock，其他应用保持默认显示。

这次改造必须兼容已有安装用户。升级后，用户在旧设置页中配置的“常驻显示”和“自定义隐藏”不能因为规则模型变化而丢失或反转。

## 目标

- 运行时只使用一个持久化黑名单 `dock_hide_apps` 判断 Dock 是否隐藏。
- 升级时把旧配置转换为升级前的“实际隐藏集合”。
- 新安装时以去除旧显示名单重叠项后的 29 项 `DEFAULT_DOCK_HIDE` 作为初始黑名单，原 30 项默认显示。
- 设置页改为直接编辑黑名单，加入黑名单即隐藏，移出黑名单即显示。
- 黑名单变更后立即刷新 Dock，不需要重启应用。
- 迁移只执行一次，重复启动、重复读取和版本重复安装不会覆盖用户后续修改。

## 非目标

- 不改变 Dock 状态机的淡入、淡出、临时显示倒计时和手势条逻辑；全局空闲自动隐藏已移除，不作为回退策略恢复。
- 不改变前台应用跟踪、无障碍服务和名单变更广播机制。
- 不在本次工作中删除旧 SharedPreferences 键；旧键保留用于故障排查和回滚，但迁移完成后不再参与运行时判断。
- 不改变其他设置项的升级重置策略。

## 改造前行为与兼容约束

改造前 `Prefs.isDockHideApp()` 的判定顺序：

1. `dock_always_show_apps` 优先，命中即显示；
2. 旧 59 项默认隐藏名单（现为 `LEGACY_DEFAULT_DOCK_HIDE` 快照）命中则隐藏；
3. `dock_custom_hide_apps` 命中则隐藏；
4. 其余显示。

旧数据已经经历 v2/v3 的延迟迁移，`ensureDockListsMigrated()` 由名单 getter 触发。Android APK 普通升级会保留 SharedPreferences，因此本次增加独立的 v4 迁移标记，不依赖 `last_version` 也不清理旧键。

## 方案与取舍

### 方案一：迁移为单一黑名单（采用）

新增 `dock_hide_apps`，迁移后所有运行时和设置页都围绕这一键工作。迁移算法是：

```text
newBlacklist = (LEGACY_DEFAULT_DOCK_HIDE ∪ oldCustomHide) - oldAlwaysShow
```

当旧数据中两个名单意外冲突时，沿用旧逻辑的优先级：常驻显示优先，因此从新黑名单中移除该包名。

默认策略补充：公开的 `DEFAULT_DOCK_SHOW` 已移除；原 59 项隐藏名单和 30 项显示名单分别冻结为私有的 `LEGACY_DEFAULT_DOCK_HIDE`、`LEGACY_DEFAULT_DOCK_SHOW`。历史 v2/v3/v4 升级只使用这些快照；当前 `DEFAULT_DOCK_HIDE` 独立保留 29 个非重叠项，仅用于新安装初始化。用户曾关闭的原显示应用仍按旧隐藏快照迁移为隐藏，已经完成 v4 的黑名单不做重算。

优点是最终模型简单、判断路径清晰，且能保留用户升级前看到的行为。

### 方案二：运行时继续适配旧双名单

每次判断时动态计算有效黑名单，同时逐步写入新键。这样回滚更容易，但会长期保留两套数据源，存在同步不一致风险。

### 方案三：仅保留旧自定义隐藏名单

直接丢弃默认隐藏和常驻显示语义。实现简单，但会使部分用户升级后看到原本被隐藏的系统应用重新显示，不符合兼容升级要求，因此不采用。

## 详细设计

### 1. 持久化与迁移

在 `Prefs` 中新增：

- `dockHideApps(ctx): Set<String>`
- `setDockHideApps(ctx, pkgs: Set<String>)`
- `Prefs` 仅保留 Context 适配和现有黑名单调用入口；`DockBlacklistDefaults` 保存当前 29 项默认值及缓存的包名集合；`DockBlacklistMigration` 统一负责完整 v2/v3/v4 升级，并私有保存旧 59/30 项快照；`DockBlacklistPreferences` 负责归一化读写、同步保护及迁移前置调用，不再回调 `Prefs`。

迁移使用 `dock_app_list_v4` 作为幂等标记：

- 已标记：不再读取旧名单，也不重算黑名单。
- 未标记：读取旧 `dock_always_show_apps` 和 `dock_custom_hide_apps`，结合 `LEGACY_DEFAULT_DOCK_HIDE` 计算新黑名单。
  - 若检测到旧 v1/v2/v3 数据，先以旧快照完成已有迁移，再按上式生成黑名单；若完全没有旧名单键和迁移标记，则跳过旧播种流程。
- 对没有旧数据的新安装：以新的 29 项 `DEFAULT_DOCK_HIDE` 为初始黑名单，原 30 项显示应用不在其中。
- 用一次 `SharedPreferences.Editor` 写入 `dock_hide_apps` 和 v4 标记。
- 旧 v2/v3 标记和旧名单保留不删。

`isDockHideApp()` 改为只读取 `dockHideApps(ctx)`；空包名仍然返回显示。

职责拆分不改变迁移时机：仍在首次读取或写入黑名单时按需执行。删除没有调用者的旧双名单 getter/setter，但保留它们对应的 SharedPreferences 键。`Prefs.DEFAULT_DOCK_HIDE` 保留为当前默认配置的兼容只读转发；其他设置域不在本次拆分范围内。迁移模块仅依赖 SharedPreferences，不依赖 Context，以便直接测试完整历史转换。

### 2. 设置页

将当前“常驻应用”语义改为“隐藏 Dock 应用”语义：

- 选中应用：加入 `dock_hide_apps`。
- 取消选中：从 `dock_hide_apps` 移除。
- 不再根据 `DEFAULT_DOCK_HIDE`、`dockAlwaysShowApps()` 或 `dockCustomHideApps()` 计算 UI 选中状态。
- 已安装应用选择状态直接来自黑名单。
- 手动添加应用时直接加入黑名单，重复添加保持幂等。
- 保留现有图标加载、广播通知和页面刷新流程，只替换名单读写与文案。

### 3. 运行时

`DockOverlayService.applyKeepShownForForeground()` 继续通过 `Prefs.isDockHideApp()` 获取黑名单判定，状态机接口和广播协议不变。前台包名为空、空白或尚未取得时直接令 `hide=false`，并传入 `applyHiddenByApp(false)`，保持默认显示；不得描述为“交还全局空闲自动隐藏”。

- 前台应用命中黑名单时要求隐藏；未命中时保持默认显示。
- 全局空闲自动隐藏已经移除，`SHOWN` 状态不会因为无操作而倒计时消失。
- `TEMP_SHOW` 的临时显示倒计时属于独立机制。超时时，`hiddenByApp=true` 才隐藏，否则恢复 `SHOWN`；不能因 `pauseAutoHide()`、`onUserIdle()` 等旧命名误认为仍有全局空闲自动隐藏。

### 4. 测试与验证

至少覆盖：

- 黑名单命中隐藏、不命中显示、空包名显示。
- 新安装默认黑名单包含 `DEFAULT_DOCK_HIDE`。
- 旧名单迁移后保留实际行为：默认隐藏减去常驻显示，再并入自定义隐藏。
- 旧常驻显示与旧自定义隐藏冲突时，常驻显示优先。
- 无旧数据、只有旧 v2 数据、已有 v3 数据等升级路径。
- v4 标记存在时不重复迁移、不覆盖用户修改。
- 设置页勾选/取消只增删新黑名单。
- 黑名单变更后现有广播刷新链路仍被触发。

## 验收标准

- 升级前隐藏的应用，升级后仍隐藏；升级前显示的应用，升级后仍显示。
- 新安装时 `DEFAULT_DOCK_HIDE` 中的应用隐藏，其他应用显示。
- 用户在设置页将任意应用加入黑名单后，切换到该应用即可隐藏 Dock；移出后恢复显示。
- 代码中运行时不再依赖 `dock_always_show_apps` 和 `dock_custom_hide_apps`。
- 迁移可重复执行而不会改变用户已经保存的新黑名单。
