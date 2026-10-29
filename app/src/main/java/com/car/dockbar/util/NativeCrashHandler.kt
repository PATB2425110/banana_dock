package com.car.dockbar.util

import android.content.Context

/**
 * Native 崩溃捕获（信号处理器）的 JNI 封装。
 *
 * 覆盖 Java 层抓不到的 native 崩溃：SIGSEGV / SIGABRT / SIGBUS / SIGILL / SIGFPE / SIGTRAP / SIGSYS。
 * 崩溃信息写到 `filesDir/logs/native_crash.txt`，由 [LogUploader.reportPreviousSession]
 * 在**下次启动**时随日志一起补传。
 *
 * 设计为「可用则用」：`.so` 加载失败（ABI 不匹配、库缺失）只是少一层取证，
 * **不影响任何其他功能**，也不会抛异常打断启动。
 */
object NativeCrashHandler {

    /** .so 是否加载成功且信号处理器已装好 */
    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    private var installError: String = ""

    fun install(ctx: Context, crashFilePath: String) {
        if (isAvailable) return
        isAvailable = runCatching {
            System.loadLibrary("cardockbar")
            nativeInstall(crashFilePath) == true
        }.getOrElse {
            installError = "${it.javaClass.simpleName}: ${it.message}"
            false
        }
        if (!isAvailable) {
            Logx.d("Native 崩溃捕获不可用（不影响其他功能）：$installError")
        }
    }

    fun statusText(): String =
        if (isAvailable) "已启用" else "未启用（$installError）"

    private external fun nativeInstall(path: String): Boolean
}
