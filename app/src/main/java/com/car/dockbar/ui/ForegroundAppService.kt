package com.car.dockbar.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.Logx

/**
 * 前台应用检测（无障碍服务）。
 *
 * 切应用时事件顺序不稳定，先到的经常是上一个界面。
 * 所以不拿事件里的包名当结果，等切换停一下，再读系统当前最前面的应用窗口。
 */
class ForegroundAppService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var pendingPkg: String? = null
    private val commitPending = Runnable { commitForeground() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.notificationTimeout = 0
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val gestures = runCatching {
            val f = AccessibilityServiceInfo::class.java.getDeclaredField("mCanPerformGestures")
            f.isAccessible = true
            f.setBoolean(info, true)
            f.getBoolean(info)
        }.getOrDefault(false)
        serviceInfo = info
        AccessibilityKeep.restore(this)
        Logx.d(
            "前台应用检测：无障碍服务已连接，可读窗口=" +
                "${info.canRetrieveWindowContent} 可回放手势=$gestures"
        )
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 把延伸区上的单击交回下面的应用。窗口要先改成不接收触摸，否则这一下还会打在自己身上。 */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    companion object {
        @Volatile
        var instance: ForegroundAppService? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) pendingPkg = pkg
        main.removeCallbacks(commitPending)
        main.postDelayed(commitPending, 250)
    }

    override fun onInterrupt() {}

    /** 以当前窗口列表为准。读不到时才退回事件里的包名。 */
    private fun commitForeground() {
        val fromWindows = foregroundFromWindows()
        val pkg = fromWindows ?: pendingPkg ?: return
        Logx.d("前台窗口：$pkg（窗口列表=${fromWindows != null}，事件=$pendingPkg）")
        ForegroundTracker.update(pkg)
    }

    private fun foregroundFromWindows(): String? {
        val list = runCatching { windows }.getOrNull() ?: return null
        var bestPkg: String? = null
        var bestScore = Int.MIN_VALUE
        for (w in list) {
            try {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (pkg.isNullOrBlank() || pkg == packageName) continue
                var score = w.layer
                if (w.isActive) score += 10_000
                if (w.isFocused) score += 20_000
                if (score > bestScore) {
                    bestScore = score
                    bestPkg = pkg
                }
            } finally {
                runCatching { w.recycle() }
            }
        }
        return bestPkg
    }
}
