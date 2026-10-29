package com.car.dockbar.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.car.dockbar.util.Logx

/**
 * 前台应用检测（无障碍服务）。
 *
 * 用途：知道「当前哪个 App 在前台」，以便按「常驻显示 Dock 的应用名单」决定
 *       Dock 是保持可见还是允许空闲自动隐藏。
 *
 * 为什么必须用无障碍：Android 9 上普通 App 拿不到前台包名 ——
 *   - getRunningTasks() 对非系统 App 只返回自己；
 *   - UsageStatsManager 需要 PACKAGE_USAGE_STATS（也要手动授权）且有分钟级延迟。
 *   无障碍是唯一能实时、可靠拿到前台包名的方式。
 *
 * ⚠️ 需要用户手动在「设置 → 无障碍」里开启本服务（或用 adb 开启），
 *    否则 [ForegroundTracker.currentPackage] 恒为 null，名单功能不生效。
 */
class ForegroundAppService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("前台应用检测：无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 只关心窗口切换，避免被其它高频事件刷屏
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = e.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    // ★★ v1.4.74：**必须忽略我们自己的窗口事件**。
                    //   我们的 Dock 是 TYPE_APPLICATION_OVERLAY 悬浮窗，隐藏/显示时
                    //   无障碍同样会收到 packageName = 自己包名的窗口事件。
                    //   于是形成无限乒乓（真车日志实锤）：
                    //   ```
                    //   22:44:17.935 前台 com.baidu.dueros.assistant 命中隐藏名单 → Dock 隐藏
                    //   22:44:18.500 前台 com.car.dockbar 不在隐藏名单 → Dock 保持显示  ← 自己！
                    //   22:44:19.311 前台 com.baidu.dueros.assistant 命中隐藏名单 → Dock 隐藏
                    //   ```
                    //   结果 Dock 疯狂闪，用户根本点不到 → 外循环/AUTO/座椅全部失灵。
                    if (pkg == packageName) return
                    main.post { ForegroundTracker.update(pkg) }
                }
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}
}
