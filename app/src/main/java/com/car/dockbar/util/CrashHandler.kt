package com.car.dockbar.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获：
 *  1. 把未捕获异常写到 `filesDir/last_crash.txt`（下次打开应用时主界面直接显示）
 *  2. **同步上传**到日志收集端点（车机上没有 adb，这是唯一能拿到崩溃现场的方式）
 *  3. 弹 Toast 显示异常类与信息，方便用户截图
 *
 * ⚠️ 局限：`Thread.setDefaultUncaughtExceptionHandler` **只能捕获 Java 层异常**。
 * native 崩溃（SIGSEGV / Abort，例如 ECarX HAL 回调里崩）不会走到这里。
 * 这类崩溃靠 [Logx] 的落盘 + `App` 启动时的「上次会话补传」来回捞。
 */
object CrashHandler {

    /** 崩溃文件（App 启动时会读取并补传，见 LogUploader.reportPreviousSession） */
    const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val text = buildString {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                append("  线程=").append(thread.name).append('\n')
                append(sw.toString())
            }
            // 先落盘：即使上传失败，下次启动也会补传
            runCatching { File(appCtx.filesDir, FILE).writeText(text) }
            Logx.e("⚠ 崩溃：${throwable.javaClass.simpleName}: ${throwable.message}")

            // 把异步落盘的日志队列排空，保证「崩溃前最后几行」真的在磁盘上
            Logx.flushSync()

            // 同步上传：进程马上会被回收，异步任务来不及跑
            runCatching {
                val tail = runCatching {
                    LogUploader.sessionLog(appCtx).readText().takeLast(24_000)
                }.getOrDefault(Logx.snapshot().takeLast(200).joinToString("\n"))
                LogUploader.uploadBlocking(
                    appCtx,
                    kind = "crash",
                    log = "===== Java 崩溃堆栈 =====\n$text\n\n" +
                            "===== 崩溃前日志（落盘）=====\n$tail",
                    note = "Java 未捕获异常 @ ${thread.name}"
                )
            }

            // 车机上没有 adb，把崩溃类型直接弹出来，方便用户截图反馈具体异常
            runCatching {
                val msg = "⚠ 崩溃: ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}"
                Handler(Looper.getMainLooper()).post {
                    runCatching { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
                }
                Thread.sleep(2500)
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    fun read(ctx: Context): String? =
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists() || f.length() == 0L) null else f.readText()
        }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
