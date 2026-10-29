package com.car.dockbar.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量日志总线：
 *  - 打到 logcat
 *  - 内存保留最近 [MAX] 条，供主界面实时展示（车机上没有 adb 也能看）
 *  - **同步落盘**到 [attachFile] 指定的文件，滚动截断到 [FILE_MAX_BYTES]
 *
 * 为什么是「同步」而不是异步队列：
 *   native 崩溃（SIGSEGV）时进程被内核直接干掉，Java 侧没有任何回调机会。
 *   早期版本用单线程队列异步写盘，导致崩溃前最后 0.5~1 秒的日志全丢 ——
 *   而这段恰恰是定位崩溃最关键的窗口。实测 28 次崩溃的「最后一行」各不相同，
 *   就是被队列截断造成的假象（真实崩溃点其实高度一致）。
 *   现在改为「常开文件流 + 每行 write+flush」，代价是一次 write 系统调用（几十微秒），
 *   换来崩溃现场日志 100% 完整。
 */
object Logx {
    private const val TAG = "CarDockBar"
    private const val MAX = 400

    /** 落盘文件大小上限，超出后只保留后半段 */
    private const val FILE_MAX_BYTES = 512 * 1024L

    private val buffer = ArrayDeque<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var verbose = true

    /** 常开的落盘流（为 null 时只走内存 + logcat） */
    private var stream: FileOutputStream? = null
    private var file: File? = null
    private var written = 0L

    fun attachFile(f: File?) {
        synchronized(this) {
            runCatching { stream?.close() }
            stream = null
            file = null
            written = 0L
            if (f == null) return
            runCatching {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile()
                stream = FileOutputStream(f, true)
                written = f.length()
            }
        }
    }

    fun setVerbose(v: Boolean) { verbose = v }
    fun isVerbose() = verbose

    @Synchronized
    fun d(msg: String) {
        if (!verbose) { Log.d(TAG, msg); return }
        push("D", msg, null)
    }

    @Synchronized
    fun w(msg: String) {
        push("W", msg, null)
    }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        push("E", msg, t)
    }

    private fun push(level: String, msg: String, t: Throwable?) {
        val line = "${fmt.format(Date())}  $level  $msg" +
                (t?.let { "  ⁄ ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        if (level != "E") Log.println(if (level == "W") Log.WARN else Log.DEBUG, TAG, msg)
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
        appendToFile(line)
        val snapshot = runCatching { listeners.toList() }.getOrDefault(emptyList())
        for (l in snapshot) runCatching { l(line) }
    }

    /** 同步写盘；超出上限时截断保留后半段 */
    @Synchronized
    private fun appendToFile(line: String) {
        val s = stream ?: return
        runCatching {
            if (written > FILE_MAX_BYTES) rotate()
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            s.write(bytes)
            s.flush()
            written += bytes.size
        }
    }

    /** 文件过大：截断保留后半段并重开流 */
    @Synchronized
    private fun rotate() {
        val f = file ?: return
        runCatching { stream?.close() }
        stream = null
        runCatching {
            val keep = f.readText().takeLast((FILE_MAX_BYTES / 2).toInt())
            f.writeText(keep)
            stream = FileOutputStream(f, true)
            written = f.length()
        }
    }

    /**
     * 兼容旧调用：现在写盘本身就是同步的，这里只做一次 flush 兜底。
     * 保留该 API 是为了不破坏既有调用点（崩溃处理器等）。
     */
    @Synchronized
    fun flushSync(@Suppress("UNUSED_PARAMETER") timeoutMs: Long = 1500L) {
        runCatching { stream?.flush() }
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun clear() { buffer.clear() }
}
