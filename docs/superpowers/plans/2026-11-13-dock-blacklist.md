# Dock 纯黑名单显隐逻辑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Dock 前台应用显隐收敛为单一 `dock_hide_apps` 黑名单，并在 APK 升级时保留旧版本用户的实际显隐结果。

**Architecture:** `DockBlacklistRules` 保存纯集合规则；`DockBlacklistDefaults` 保存当前默认名单及缓存包名集合；`DockBlacklistMigration` 统一负责 v2/v3/v4 迁移与私有历史快照；`DockBlacklistPreferences` 负责同步和读写并直接调用迁移；`Prefs` 仅提供兼容入口及 Context 适配，不再提供历史迁移回调。

**Tech Stack:** Kotlin 2.0.21、Android API 28–34、SharedPreferences、JUnit 4.13.2、Gradle 8.9、JDK 17/21。

**Spec:** `docs/superpowers/specs/2026-11-13-dock-blacklist-design.md`

## Global Constraints

- 运行时规则必须是：`pkg` 在 `dock_hide_apps` 中则隐藏，否则显示；空包名显示。
- 新安装的初始黑名单为当前 29 项 `DEFAULT_DOCK_HIDE`，原显示名单中的 30 个应用默认显示。
- 检测到旧 v1/v2/v3 名单时，先以私有旧快照完成现有迁移，再使用 `(LEGACY_DEFAULT_DOCK_HIDE ∪ oldCustomHide) - oldAlwaysShow` 生成新黑名单。
- `dock_app_list_v4` 是幂等标记；迁移后不得因重复启动覆盖用户对新黑名单的修改。
- 不删除旧 SharedPreferences 键、v2/v3 标记或旧迁移代码；它们只用于一次性兼容迁移，不得成为新的运行时数据源。
- 保持现有前台跟踪、状态机、名单变更广播和图标加载流程不变。
- 代码与测试使用现有 Kotlin/JUnit 风格，不新增依赖。

## Review Focus

1. **全新安装没有任何旧名单键**：只播种当前 29 项 `DEFAULT_DOCK_HIDE`，不使用历史 59/30 项快照；由 Task 2 的真实默认值及独立迁移默认值测试覆盖。
2. **旧名单迁移顺序**：旧常驻显示与自定义隐藏冲突时必须沿用常驻显示优先；由 Task 1 的冲突测试覆盖。
3. **v4 幂等性**：v4 标记存在后不能重新计算或覆盖用户已经修改的 `dock_hide_apps`；由 Task 2 的迁移代码检查和最终 diff review 覆盖。
4. **异常包名输入**：空白包名不得隐藏，黑名单之外的未知包名默认显示；由 Task 1 的规则测试覆盖。
5. **设置页修改链路**：勾选只增入黑名单，取消只移出黑名单，并继续发出现有应用变更广播；由 Task 3 的代码检查、编译和最终行为验证覆盖。

---

### Task 1: 建立可测试的黑名单规则

**Files:**
- Create: `app/src/main/java/com/car/dockbar/util/DockBlacklistRules.kt`
- Test: `app/src/test/java/com/car/dockbar/util/DockBlacklistRulesTest.kt`

**Interfaces:**
- Produces `internal object DockBlacklistRules` with:
  - `fun migrate(defaultHide: Set<String>, alwaysShow: Set<String>, customHide: Set<String>): Set<String>`
  - `fun shouldHide(pkg: String, blacklist: Set<String>): Boolean`
- `migrate` returns `(defaultHide + customHide) - alwaysShow`.
- `shouldHide` returns `false` for blank package names and otherwise checks only blacklist membership.

- [ ] **Step 1: Write failing unit tests**

  Add tests with exact cases:
  - `migrateKeepsDefaultAndCustomHiddenAppsExceptAlwaysShownApps`: default `{A, B}`, always `{A}`, custom `{C}` produces `{B, C}`.
  - `migrateUsesDefaultHideForFreshInstall`: default `{A, B}`, empty old sets produces `{A, B}`.
  - `migrateAlwaysShowWinsOnLegacyConflict`: default `{A}`, always `{A}`, custom `{A}` produces an empty set.
  - `blankPackageIsNeverHidden`.
  - `nonBlacklistedPackageIsShown` and `blacklistedPackageIsHidden`.

- [ ] **Step 2: Run the focused test and verify it fails**

  Run on Windows:
  ```text
  .\gradlew.bat :app:testDebugUnitTest --tests com.car.dockbar.util.DockBlacklistRulesTest
  ```
  Expected: compilation/test failure because `DockBlacklistRules` does not exist yet.

- [ ] **Step 3: Implement the minimal pure rule object**

  Create `DockBlacklistRules.kt` with no Android imports and the two exact methods above. Use set union/subtraction so duplicate package names are naturally normalized.

- [ ] **Step 4: Run the focused test and verify it passes**

  Run the same Gradle command. Expected: all `DockBlacklistRulesTest` cases pass.

---

### Task 2: Add v4 SharedPreferences migration and switch runtime persistence

**Files:**
- Modify: `app/src/main/java/com/car/dockbar/util/Prefs.kt:585-685`
- Create: `app/src/main/java/com/car/dockbar/util/DockBlacklistPreferences.kt`
- Test: `app/src/test/java/com/car/dockbar/util/DockBlacklistPreferencesTest.kt`

**Interfaces:**
- Add `fun dockHideApps(ctx: Context): Set<String>`.
- Add `fun setDockHideApps(ctx: Context, pkgs: Set<String>)`.
- Keep `dockAlwaysShowApps`, `setDockAlwaysShowApps`, `dockCustomHideApps`, and `setDockCustomHideApps` available for legacy migration only.
- `isDockHideApp(ctx, pkg)` consumes `dockHideApps(ctx)` and `DockBlacklistRules.shouldHide`.

- [ ] **Step 1: Implement the v4 migration entry point**

  First add the SharedPreferences contract tests and observe RED with the missing storage API, then implement the helper. Keep `dock_hide_apps` and `dock_app_list_v4` keys in this helper; `Prefs.dockHideApps()` delegates to `read(prefs, defaultHide, legacyDefaultHide, migrateLegacy)` and the setter delegates to `write(prefs, defaultHide, packages, legacyDefaultHide, migrateLegacy)`. Pass the current 29-item defaults separately from the private 59-item migration snapshot. Synchronize initialization and writes on the preference instance. Test fresh-install behavior, legacy callback ordering, retained old keys, atomic marker/list publication, normalized reads/writes, formerly shown apps that users disabled, and persistent user edits including an empty blacklist.

- [ ] **Step 2: Implement fresh-install versus legacy detection**

  In `DockBlacklistPreferences.ensureMigrated(prefs, defaultHide, legacyDefaultHide, migrateLegacy)`:
  - Return immediately when `dock_app_list_v4` is already true.
  - Detect legacy state before invoking v2/v3 migration by checking the old list keys and v2/v3 flags.
  - If no legacy key or migration flag exists, use `DEFAULT_DOCK_HIDE.map { it.first }.toSet()` directly; do not run v2/v3 seeding.
  - If legacy state exists, call the existing `ensureDockListsMigrated(ctx)` using private legacy snapshots, parse the resulting old always-show and custom-hide sets, and call `DockBlacklistRules.migrate(legacyDefaultHide, ...)`.
  - Write `dock_hide_apps` and `dock_app_list_v4=true` in one `SharedPreferences.Editor` operation. Leave all old keys untouched.

- [ ] **Step 3: Switch the runtime predicate and setter**

  Parse `dock_hide_apps` with the same trimming/empty-item behavior as existing list getters. Make `setDockHideApps` write the normalized package set. Replace the old precedence logic in `isDockHideApp` with `DockBlacklistRules.shouldHide(pkg, dockHideApps(ctx))`; preserve the existing blank-package behavior.

- [ ] **Step 4: Run the rule test and compile the production changes**

  Run:
  ```text
  .\gradlew.bat :app:testDebugUnitTest --tests com.car.dockbar.util.DockBlacklistRulesTest
  .\gradlew.bat :app:compileDebugKotlin
  ```
  Expected: focused tests pass and Kotlin compilation succeeds.

---

### Task 3: Convert the settings page to edit the blacklist

**Files:**
- Modify: `app/src/main/java/com/car/dockbar/ui/ResidentAppsPanel.kt:40-60,180-195`
- Modify: `app/src/main/java/com/car/dockbar/ui/ResidentSettingsPage.kt:141-164,300-316`
- Delete: `app/src/main/java/com/car/dockbar/ui/ResidentAppRules.kt`
- Modify navigation and accessibility copy: `app/src/main/java/com/car/dockbar/ui/MainActivity.kt`, `app/src/main/res/layout/settings_page_resident.xml`, `app/src/main/res/layout/settings_page_overlay.xml`, `app/src/main/res/layout/settings_legacy_links.xml`, `app/src/main/res/values/strings.xml`.

**Interfaces:**
- `ResidentAppsPanel.render(...)` and its callback shape remain unchanged; the `selected` set now means “currently blacklisted / hidden”, not “currently resident / shown”.
- `ResidentSettingsPage.renderDockAppList()` reads and writes only `Prefs.dockHideApps()` / `Prefs.setDockHideApps()`.

- [ ] **Step 1: Update the panel copy and counters**

  Change the existing hard-coded labels from “常驻应用/常驻显示” to “隐藏 Dock 应用/隐藏 Dock”; make checked state visibly mean “加入黑名单并隐藏 Dock”. Keep search, grouping, icon loading, and click handling unchanged.

- [ ] **Step 2: Replace list loading and toggle persistence**

  In `renderDockAppList()`:
  - Build `selected` from installed package names present in `Prefs.dockHideApps(this)`.
  - On `on=true`, add the package to a mutable blacklist; on `on=false`, remove it.
  - Persist through `Prefs.setDockHideApps`, then call the existing `notifyAppsChanged()` and `panel.setSelection(...)` flow.
  - Update logs to describe “隐藏/显示” rather than “常驻显示/隐藏”.

- [ ] **Step 3: Replace manual add behavior**

  In `addDockListApp(pkg, label)`, add the package directly to `Prefs.dockHideApps(this)`, persist it, broadcast the change, rerender, and log that the app was added to the Dock blacklist. Remove all branching on `DEFAULT_DOCK_HIDE` and all reads/writes of the legacy two-list APIs.

- [ ] **Step 4: Remove the obsolete UI rule adapter and compile**

  Delete `ResidentAppRules.kt` after its only callers are removed. Run:
  ```text
  .\gradlew.bat :app:compileDebugKotlin
  ```
  Expected: no references remain to `ResidentAppRules`, `dockAlwaysShowApps`, or `dockCustomHideApps` from the settings UI.

---

### Task 4: Clean runtime wording and perform full verification

**Files:**
- Modify: `app/src/main/java/com/car/dockbar/ui/DockOverlayService.kt:600-612`
- Modify: `app/src/main/java/com/car/dockbar/util/Prefs.kt` comments around `DEFAULT_DOCK_SHOW` and legacy migration, if needed for clarity.

- [ ] **Step 1: Update runtime comments and logs**

  Keep `applyKeepShownForForeground()` and its state-machine calls unchanged, but remove wording that says the decision is based on a resident-show list or a system full-screen list. Describe the single blacklist predicate instead.

- [ ] **Step 2: Search for legacy runtime call sites**

  Run:
  ```text
  git grep -n -E "dockAlwaysShowApps|dockCustomHideApps|ResidentAppRules|isDockHideApp|dockHideApps" -- app/src/main
  ```
  Expected: only legacy migration/accessor definitions plus the new `dockHideApps` path remain; the settings page and runtime predicate no longer consume the old lists.

- [ ] **Step 3: Run all unit tests and debug build**

  Run:
  ```text
  .\gradlew.bat :app:testDebugUnitTest
  .\gradlew.bat :app:assembleDebug
  ```
  Expected: all unit tests pass and the debug APK assembles successfully.

- [ ] **Step 4: Review the final diff and worktree**

  Run:
  ```text
  git diff --check
  git diff -- docs/superpowers/specs/2026-11-13-dock-blacklist-design.md docs/superpowers/plans/2026-11-13-dock-blacklist.md app/src/main app/src/test
  git status --short
  ```
  Confirm no unrelated files changed, the design and plan are present, the old keys are not deleted, and the v4 migration cannot overwrite a marked blacklist.

### Task 5: Scoped Prefs responsibility extraction (approved follow-up)

**Files:** Create `util/DockBlacklistDefaults.kt` and `util/DockBlacklistMigration.kt`; modify `util/Prefs.kt` and `util/DockBlacklistPreferences.kt`; create `src/test/java/com/car/dockbar/util/MemoryPreferences.kt` and `DockBlacklistMigrationTest.kt`; adapt `DockBlacklistPreferencesTest.kt` to the actual store API. Production paths are relative to `app/src/main/java/com/car/dockbar/`.

**Interfaces:** `DockBlacklistDefaults.apps` and cached `packages`; `DockBlacklistMigration.ensureMigrated(prefs: SharedPreferences)`; `DockBlacklistPreferences.read(prefs): Set<String>` and `write(prefs, packages)`. Keep `Prefs.dockHideApps`, `setDockHideApps`, `isDockHideApp`, `dockHideLabel` and the default-list forwarding property stable. Remove unused old dual-list getter/setter methods, not persisted keys.

- [x] Added direct migration tests and observed RED for the missing extraction modules before production changes; now cover fresh install, complete v1/v2/v3/v4 chains, flag combinations, conflicts, empty/completed v4 and concurrent first migration.
- [x] Moved every 29/59/30 package-label entry unchanged and verified ordering against the pre-refactor source; package sets are cached once.
- [x] Unified migration in `DockBlacklistMigration`, removed callbacks and unused legacy getters/setters, and updated storage tests to use real migrations. Other Prefs sections were verified byte-for-byte unchanged.
- [x] Full tests: 62 passed, 0 failures/errors; Chinese-comment, whitespace and Markdown link checks passed. Independent review found no Critical/Important blockers; added empty legacy-key, false-only marker, independent historical expectation and coordinated first-read/write coverage. Clean Debug build remains blocked by generated `util/Sec.dex` missing during dex output hashing; no current APK is claimed.
