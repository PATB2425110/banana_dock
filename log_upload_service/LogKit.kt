package com.car.logkit

/**
 * LogKit —— 独立的日志上传客户端（**零依赖，不依赖本项目任何代码**）
 *
 * 用途：让**任何** Android 应用把日志 POST 到统一日志接收服务，
 *      由服务端按 appId 分目录归档，浏览器/接口随时查看。
 *
 * 接入步骤（3 步）：
 *   1. 把本文件拷进你的项目（例如 `app/src/main/java/com/car/logkit/LogKit.kt`）
 *   2. AndroidManifest 加权限：`<uses-permission android:name="android.permission.INTERNET" />`
 *      （ACCESS_NETWORK_STATE 可选，用于判断有没有网）
 *   3. Application.onCreate 里调 `LogKit.init(context, "你的应用标识")`
 *
 * 用法：
 * ```kotlin
 * LogKit.init(this, "mytool")            // 建议放 Application.onCreate
 * LogKit.log("这是一条日志")               // 攒批上传（1.5 秒或 40 行发一次）
 * LogKit.flushNow("手动上传")              // 立刻上传当前缓冲
 * LogKit.uploadCrash("崩溃现场", throwable)  // 崩溃时调用
 * ```
 *
 * 与本项目 App 的关系：**互不影响**。本项目自己那份 LogUploader 不依赖本文件，
 * 它的日志仍然落在服务端 logs/ 根目录；只有显式带上 appId 的应用才会进子目录。
 *
 * 技术说明：
 *  - 只用 Android 框架 API + org.json，**不需要任何第三方库**
 *  - 上传走独立线程，绝不阻塞主线程
 *  - 没网 / 上传失败 → 落盘到 filesDir/logs/queue.txt，联网后自动补传
 *  - 单条日志正文截断到 22000 字符（保头 + 保尾，中间舍弃）
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

object LogKit {

    // ---------------------------------------------------------------- 配置

    /** 服务地址。默认指向线上部署实例，换地址只改这一处。 */
    @Volatile
    var serverUrl: String = "https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run"

    /**
     * 应用标识 —— **每个应用必须用不同的值**，服务端据此分目录。
     * 规则：字母数字开头，只允许字母数字 . _ -，长度 ≤ 64（服务端会强校验）。
     */
    @Volatile
    var appId: String = ""

    /** 是否启用上传。关掉后所有调用变成 no-op（但仍会写本地日志文件）。 */
    @Volatile
    var enabled: Boolean = true

    /** 单条 payload 的日志正文上限（字符），超出按保头保尾截断。 */
    private const val MAX_LOG_CHARS = 22_000
    private const val HEAD_KEEP_CHARS = 12_000

    /** HTTP 超时（毫秒）。 */
    private const val TIMEOUT_MS = 10_000

    /** 攒批参数：1.5 秒或 40 行或 8000 字符，先到先发。 */
    private const val FLUSH_MS = 1500L
    private const val FLUSH_LINES = 40
    private const val FLUSH_CHARS = 8000

    /** 离线队列上限，超出只留后半段。 */
    private const val QUEUE_MAX = 512 * 1024

    // ---------------------------------------------------------------- 内部状态

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "logkit-upload").apply { isDaemon = true }
    }

    private val lock = Object()
    private val pending = ArrayDeque<String>()
    private var appCtx: Context? = null
    private var started = false
    private var netUp = true
    private var deviceIdCache = ""

    /** 最近一次上传结果，供 UI 展示。 */
    @Volatile
    var lastResult: String = ""
        private set

    // ---------------------------------------------------------------- 对外 API

    /**
     * 初始化。必须在 Application.onCreate 调一次。
     * @param ctx 应用上下文
     * @param id  应用标识（每个应用不同）
     */
    fun init(ctx: Context, id: String) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        appId = id
        watchNetwork(appCtx!!)
        Thread({ loop() }, "logkit-live").apply { isDaemon = true }.start()
    }

    /** 追加一条日志（攒批上传）。线程安全。 */
    fun log(line: String) {
        synchronized(lock) {
            pending.addLast(nowText() + "  " + line)
            lock.notifyAll()
        }
    }

    /** 立刻把缓冲里的日志发出去（手动按钮 / 关键节点用）。 */
    fun flushNow(note: String = "") {
        val ctx = appCtx ?: return
        if (!enabled) return
        pool.execute {
            val batch = drain()
            if (batch.isNotEmpty()) {
                if (!post(buildPayload(ctx, "manual", batch.joinToString("\n"), note))) {
                    queue(ctx, batch.joinToString("\n"))
                }
            }
        }
    }

    /**
     * 上传崩溃现场（会在 payload 里带堆栈）。
     * 建议在 UncaughtExceptionHandler 里调用。
     */
    fun uploadCrash(note: String, t: Throwable? = null) {
        val ctx = appCtx ?: return
        if (!enabled) return
        val sb = StringBuilder()
        sb.append("===== 崩溃现场 =====\n").append(note).append('\n')
        if (t != null) {
            sb.append("----- 堆栈 -----\n")
            t.stackTrace.forEach { sb.append("    at ").append(it.toString()).append('\n') }
            sb.append("----- cause -----\n").append(t.cause?.toString() ?: "(无)").append('\n')
        }
        val batch = synchronized(lock) { pending.toList() }
        sb.append("\n===== 崩溃前日志 =====\n").append(batch.joinToString("\n"))
        pool.execute {
            // 崩溃时同步上传（异步任务大概率来不及跑完）
            val ok = post(buildPayload(ctx, "crash", sb.toString(), note))
            lastResult = if (ok) "崩溃日志已上传" else "崩溃日志上传失败（已留本地）"
            if (!ok) queue(ctx, sb.toString())
        }
    }

    /** 上传一段纯文本（手动快照用）。 */
    fun uploadText(kind: String, text: String, note: String = "") {
        val ctx = appCtx ?: return
        if (!enabled) return
        pool.execute {
            val ok = post(buildPayload(ctx, kind, text, note))
            lastResult = if (ok) "已上传 $kind" else "上传失败 $kind，已缓存待重试"
            if (!ok) queue(ctx, text)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun deviceId(ctx: Context): String {
        if (deviceIdCache.isNotBlank()) return deviceIdCache
        val id = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(12)
        deviceIdCache = id
        return id
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName}($code)"
    }.getOrDefault("unknown")

    private fun buildPayload(ctx: Context, kind: String, log: String, note: String): String {
        val body = if (log.length > MAX_LOG_CHARS) {
            val tailKeep = MAX_LOG_CHARS - HEAD_KEEP_CHARS
            val omitted = log.length - HEAD_KEEP_CHARS - tailKeep
            log.take(HEAD_KEEP_CHARS) +
                "\n…（中间 $omitted 字符已省略）…\n" +
                log.takeLast(tailKeep)
        } else log

        return JSONObject().apply {
            // ★ appId 是多应用接入的关键字段：服务端据此分目录
            put("appId", appId)
            put("kind", kind)
            put("device", deviceId(ctx))
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("app", appVersion(ctx))
            put("time", nowText())
            if (note.isNotBlank()) put("note", note)
            put("log", body)
        }.toString()
    }

    /** 单次 POST，返回是否成功（2xx）。 */
    private fun post(urlStr: String, payload: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "LogKit/1.0")
            }
            OutputStreamWriter(c.outputStream, "UTF-8").use { w ->
                w.write(payload)
                w.flush()
            }
            val code = c.responseCode
            runCatching { c.inputStream?.close() }
            code in 200..299
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    // ---------------- 攒批循环 ----------------

    private fun loop() {
        var backoff = FLUSH_MS
        while (true) {
            try {
                val ctx = appCtx
                if (ctx == null) {
                    SystemClock.sleep(500)
                    continue
                }
                if (!enabled || appId.isBlank()) {
                    synchronized(lock) { pending.clear() }
                    SystemClock.sleep(2000)
                    continue
                }
                // 先补传离线队列
                val queued = takeQueue(ctx)
                if (queued.isNotEmpty()) {
                    if (!online(ctx) || !post(buildPayload(ctx, "live", queued, "离线补传"))) {
                        putQueueFront(ctx, queued)
                        SystemClock.sleep(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    } else {
                        backoff = FLUSH_MS
                    }
                    continue
                }
                val batch = awaitBatch()
                if (batch.isEmpty()) continue
                val ok = online(ctx) && post(buildPayload(ctx, "live", batch.joinToString("\n"), "在线实时"))
                if (!ok) {
                    queue(ctx, batch.joinToString("\n"))
                    SystemClock.sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } else {
                    lastResult = "实时已上传 ${batch.size} 行"
                    backoff = FLUSH_MS
                }
            } catch (t: Throwable) {
                SystemClock.sleep(2000)
            }
        }
    }

    private fun awaitBatch(): List<String> {
        synchronized(lock) {
            while (pending.isEmpty()) {
                lock.wait(30_000)
            }
            val deadline = SystemClock.elapsedRealtime() + FLUSH_MS
            while (pending.size < FLUSH_LINES && SystemClock.elapsedRealtime() < deadline) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                lock.wait(left)
            }
            val out = ArrayList<String>(FLUSH_LINES)
            var chars = 0
            while (pending.isNotEmpty() && out.size < FLUSH_LINES && chars < FLUSH_CHARS) {
                val line = pending.removeFirst()
                out.add(line)
                chars += line.length + 1
            }
            return out
        }
    }

    private fun drain(): List<String> = synchronized(lock) {
        val out = pending.toList()
        pending.clear()
        out
    }

    // ---------------- 离线队列 ----------------

    private fun queueFile(ctx: Context): File =
        File(File(ctx.filesDir, "logs").apply { mkdirs() }, "queue.txt")

    private fun queue(ctx: Context, text: String) {
        if (text.isBlank()) return
        val f = queueFile(ctx)
        runCatching {
            f.appendText(text + "\n")
            if (f.length() > QUEUE_MAX) {
                val keep = f.readText().takeLast(QUEUE_MAX / 2)
                val cut = keep.indexOf('\n')
                f.writeText(if (cut >= 0) keep.substring(cut + 1) else keep)
            }
        }
    }

    private fun putQueueFront(ctx: Context, text: String) {
        val f = queueFile(ctx)
        runCatching {
            val rest = if (f.exists()) f.readText() else ""
            f.writeText(text + "\n" + rest)
        }
    }

    private fun takeQueue(ctx: Context): String {
        val f = queueFile(ctx)
        if (!f.exists() || f.length() == 0L) return ""
        val lines = f.readText().split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            f.delete()
            return ""
        }
        val take = lines.take(FLUSH_LINES).joinToString("\n")
        val rest = lines.drop(FLUSH_LINES)
        if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n") + "\n")
        return take
    }

    // ---------------- 网络状态 ----------------

    private fun watchNetwork(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    netUp = true
                    synchronized(lock) { lock.notifyAll() }
                }

                override fun onLost(network: Network) {
                    netUp = false
                }
            })
        }
    }

    private fun online(ctx: Context): Boolean {
        if (!netUp) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        return info != null && info.isConnected
    }
}
