package com.car.dockbar.ui

/**
 * 当前前台应用包名（进程内单例）。
 *
 * 由 [ForegroundAppService]（无障碍服务）在窗口状态变化时写入，
 * [DockOverlayService] 读它来决定 Dock 该常驻还是可以自动隐藏。
 *
 * 无障碍没开启时它恒为 null —— 此时名单功能不生效，Dock 按全局自动隐藏规则走。
 */
object ForegroundTracker {

    @Volatile
    var currentPackage: String? = null
        private set

    /** 前台应用变化回调（在主线程调用） */
    var listener: ((String?) -> Unit)? = null

    fun update(pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == currentPackage) return
        currentPackage = pkg
        listener?.invoke(pkg)
    }

    /** 无障碍服务是否真的在跑（能拿到前台包名） */
    val isActive: Boolean get() = currentPackage != null
}
