# Banana_Dock

## 项目描述

Banana_Dock 是运行在 Android 车机上的悬浮 Dock 应用，提供常用车辆控制、音乐信息、快捷应用、歌词悬浮窗、应用隐藏黑名单和诊断日志等入口，帮助用户在车机桌面快速访问常用功能。

- 当前版本：**4.5.0**（versionCode **450**）
- 应用包名：`com.car.dockbar`
- 应用名称：`Banana_Dock`

## Dock 显示与隐藏

- 设置页“隐藏 Dock 应用”采用纯黑名单：勾选表示该应用在前台时隐藏 Dock，取消勾选则保持默认显示。
- 前台应用不在黑名单中，或暂时拿不到前台包名（例如未开启无障碍检测）时，保持默认显示，不回退到全局空闲自动隐藏。
- 全局空闲自动隐藏已经移除。底部手势或弹窗触发的临时显示倒计时仍保留，到时仅在前台应用仍要求隐藏时收起，否则保持显示。

## 项目构建

### 构建环境

- Android API：最低 28，compile/target SDK 34。
- Java/Kotlin 编译目标：17；Gradle 运行需 JDK 17 或兼容版本（如 JDK 21），通过 `JAVA_HOME` 指定。CI 使用 JDK 17。
- Android Gradle Plugin：8.7.3。
- Kotlin：2.0.21。
- Gradle Wrapper：8.9。
- Native 构建使用 NDK `28.2.13676358`，目标 ABI 为 `arm64-v8a`、`armeabi-v7a`、`x86_64`。

### 常用命令

Windows：

```bat
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
```

Git Bash、Linux 或 macOS：

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

### 本地构建产物

- Debug：`app/build/outputs/apk/debug/Banana_Dock-4.5.0-debug.apk`
- Release：`app/build/outputs/apk/release/Banana_Dock-4.5.0.apk`

Release 构建启用代码和资源缩减；本地构建使用 Gradle 配置中的签名方式。发布到 GitHub 前，CI 会使用仓库 Secrets 对 APK 重签并校验。

### GitHub Actions 发布

发布 workflow 支持默认分支上的发布关键词提交和手动触发。发布所需 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

不得将 keystore、密码或 Base64 内容提交到仓库。CI 每次发布一个新的 Release，资产名为 `CarDockBar-<versionName>-r<run>.apk`（当前版本形如 `CarDockBar-4.5.0-r123.apk`），并同时生成 `update.json` 和 `SHA256SUMS`。同一版本可以重复构建，已发布的 APK 会保留。

开发协作与相关资料见 [AGENTS.md](AGENTS.md)。
