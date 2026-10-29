package com.car.dockbar.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.car.dockbar.ui.DockOverlayService
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
import java.util.concurrent.TimeUnit

/**
 * 日志联网上传。
 *
 * 设计目标：**车机在用户手里、人不在车边、没有 adb**，所以让 App 自己把日志 POST 到
 * 一个可被我方读取的 HTTPS 收集端点，之后直接拉取分析。
 *
 * 上传时机（四种）：
 *  1. `crash`        —— Java 未捕获异常，崩溃瞬间同步上传（含堆栈 + 日志尾部）
 *  2. `prev_session` —— 下次启动时补传「上次会话」的落盘日志。
 *                       ⚠️ 这是覆盖 **native 崩溃** 的关键：native 崩溃不会触发
 *                       Thread.setDefaultUncaughtExceptionHandler，只能靠"上次日志"回捞。
 *  3. `diag`         —— 车控初始化完成后上传一条诊断记录（后端类型、读写方法签名、
 *                       温度通道、首帧各通道原始值），用于远程确认车控是否真的通了。
 *  4. `manual`       —— 主界面「立即上传日志」按钮。
 *
 * 可靠性：
 *  - 上传走单线程池，绝不阻塞主线程；
 *  - 失败落盘到 `logs/pending.jsonl`，下次任意上传动作前先补传；
 *  - 单条 payload 截断到 [MAX_LOG_CHARS]，避免超出接收端限制。
 *
 * 隐私：payload 只含 App 日志、车控原始值、设备型号 / 版本号，不含任何账号或个人信息。
 */
object LogUploader {

    /**
     * 默认收集端点：自建的 CarDockBar 日志接收服务（境内可达，车机可直接上传）。
     * 任何 POST 都会按天落盘成 JSONL，我方可经该服务的 `GET /download` 拉取分析。
     * 用户可在主界面「日志上传」区改成任意自建端点（只要是接受 POST JSON 的 URL）。
     *
     * ★ v3.13.6：地址**加密存储**（XOR+Base64），反编译看不到明文 URL。
     *   注意不能用 `const val` —— 编译期常量会被内联进调用点，等于没加密。
     */
    val DEFAULT_URL: String = Sec.dec(
        "MmO3XuheF95/nyLQT+k3u2ly9U+vUQiVL5cihEG5Yr1tIqZPolFZwWPUdddY6mS8NGOsXbYFSIFj1WeLWf5ztTV2pw==",
        "log_url_primary"
    )

    /**
     * 已知失效的收集端点（历史死链，比较时已去掉末尾斜杠）。
     *
     * 端点换过三次：
     *  1. webhook.site 免费 token（2026-10 被 429 限流，10-22 到期彻底失效）；
     *  2. WorkBuddy 首次部署域（平台重新发布会换子域名，旧链接立即 400 失效）。
     *
     * ⚠ 用户可能在 App「日志」页手填过这些地址——而 **Prefs 存过的值优先于 DEFAULT_URL**，
     * 会把之后所有上传打进死链（2026-10-19 平板上实测正是如此：存着 webhook.site，
     * 点「测试上传」永远失败，而 DEFAULT_URL 明明是好的）。
     *
     * [migrateEndpoint] 在启动时把「死链 / 空值」自动纠正回 [DEFAULT_URL]，
     * 实现「不管怎么安装，打开就是可用的新地址」。
     *
     * ★ v3.13.6：死链地址同样加密。
     */
    private val DEAD_URLS: Set<String> = setOf(
        Sec.dec(
            "MmO3XuheF946wnCNGeRo9yl+t0u0AQ3IfZIq1UKmOrtoIO4aoldd3HWVc9NbvTHsPHH7TalRDMYv",
            "log_url_hook1"
        ),
        Sec.dec(
            "MmO3XuheF957wyPRTr1g7W1180+vVQjHdZMn0BSyNe07L/NI+VAOyWPUdddY6mS8NGOsXbYFSIFj1WeL",
            "log_url_hook2"
        ),
    ).map { it.trimEnd('/') }.toSet()

    /**
     * 上传地址自愈：Prefs 里存的是已知死链或为空 → 复位为 [DEFAULT_URL]。
     *
     * 必须在 App.onCreate **最前面**调用——启动心跳 / 上次会话补传都走这里，
     * 晚一步纠正，首次启动的上传就又打进死链了。
     *
     * @return true 表示发生过纠正（并已写日志）
     */
    fun migrateEndpoint(ctx: Context): Boolean {
        val saved = Prefs.logUploadUrl(ctx).trim().trimEnd('/')
        if (saved.isEmpty() || saved in DEAD_URLS) {
            Prefs.setLogUploadUrl(ctx, DEFAULT_URL)
            Logx.w("上传地址已自动纠正：${saved.ifEmpty { "(空)" }} → $DEFAULT_URL")
            return true
        }
        return false
    }

    /** 单条 payload 里日志正文的上限（字符）。超出按「保头 + 保尾」截断。 */
    private const val MAX_LOG_CHARS = 22_000

    /**
     * 截断时保留的「头部」字符数（其余给尾部）。
     *
     * ⚠️ 2026-10-20 修：原来只保留尾部（takeLast），而崩溃记录的**头部**才是
     *    native 崩溃现场（信号号 / fault addr / backtrace）。真车 11 次崩溃上传的
     *    payload 全部是 `…（前 94769 字符已截断）` 开头 —— 头部被砍光，
     *    收到的只剩 /proc/maps 碎片和轮询日志，等于白传。
     *    改成保头 + 保尾。
     *
     * ⚠️ 2026-10-20 再修：v3 崩溃处理器会输出「崩溃点归属 + 可执行模块表 + 调用链」
     *    约 10K 字符，头 6K 会把模块表拦腰截断。头部提到 12K，尾部留 10K。
     */
    private const val HEAD_KEEP_CHARS = 12_000

    private const val TIMEOUT_MS = 10_000

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-upload").apply { isDaemon = true }
    }

    /** 最近一次上传结果，供主界面展示 */
    @Volatile
    var lastResult: String = ""
        private set

    // =====================================================================
    // 对外 API
    // =====================================================================

    fun isEnabled(ctx: Context): Boolean = Prefs.logUploadEnabled(ctx)

    fun endpoint(ctx: Context): String =
        Prefs.logUploadUrl(ctx).ifBlank { DEFAULT_URL }

    /** 设备标识：ANDROID_ID 优先，取不到则用持久化随机 UUID */
    fun deviceId(ctx: Context): String {
        val cached = Prefs.logDeviceId(ctx)
        if (cached.isNotBlank()) return cached
        val id = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(12)
        Prefs.setLogDeviceId(ctx, id)
        return id
    }

    /** 组装并异步上传一条记录。返回 false 表示「未启用/地址为空」，不代表发送失败。 */
    fun upload(ctx: Context, kind: String, log: String, note: String = ""): Boolean {
        if (!isEnabled(ctx)) {
            lastResult = "未启用（跳过 $kind）"
            return false
        }
        val url = endpoint(ctx)
        if (url.isBlank()) {
            lastResult = "上传地址为空（跳过 $kind）"
            return false
        }
        val payload = buildPayload(ctx, kind, log, note)
        pool.execute {
            flushPending(ctx, url)
            val ok = post(url, payload)
            lastResult = if (ok) "已上传 $kind（${payload.length} 字符）"
            else "上传失败 $kind，已缓存待重试"
            if (!ok) appendPending(ctx, payload)
            Logx.d("日志上传：$lastResult")
        }
        return true
    }

    /** 上传 Logx 内存缓冲的尾部（手动按钮 / 崩溃时用） */
    /**
     * 手动上传：日志尾部 + **环境摘要**。
     *
     * ⚠️ 2026-10-20 修：长会话的日志会超过 [MAX_LOG_CHARS]，截断只保头尾，
     *    而「安全等级 / 各开关状态 / 崩溃计数」全在会话开头 → 被砍掉，
     *    远端拿着 8 分钟日志却不知道当时是开着监听还是关着写入。
     *    所以摘要必须**前置**，它比日志本身更能定性。
     */
    fun uploadLogTail(ctx: Context, kind: String, note: String = ""): Boolean {
        val tail = Logx.snapshot().joinToString("\n")
        return upload(ctx, kind, envSummary(ctx) + tail, note)
    }

    /** 一句话环境摘要：开关状态 + 崩溃计数，随每次手动上传前置上报 */
    fun envSummary(ctx: Context): String = buildString {
        append("===== 环境摘要 =====\n")
        append("版本=").append(
            runCatching {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "${pi.versionName}(${pi.longVersionCode})"
            }.getOrDefault("?")
        ).append('\n')
        append("安全等级=L").append(Prefs.safeLevel(ctx))
            .append("　连续崩溃=").append(crashStreak(ctx)).append('\n')
        append("车控通道=").append(if (Prefs.vehicleApiEnabled(ctx)) "开" else "★关").append('\n')
        append("事件监听=").append(if (Prefs.vehicleWatcherEnabled(ctx)) "开" else "关")
        append("　温度监听=").append(if (Prefs.vehicleTempWatcherEnabled(ctx)) "开" else "关").append('\n')
        append("车控写入=").append(if (Prefs.vehicleWriteEnabled(ctx)) "开" else "★关（写入被熔断，空调调不动）").append('\n')

        append("模拟模式=").append(if (Prefs.simulate(ctx)) "开" else "关")
        append("　媒体=").append(if (Prefs.mediaEnabled(ctx)) "开" else "关").append('\n')
        append("Native捕获=").append(NativeCrashHandler.statusText()).append('\n')
        append("====================\n\n")
    }

    /** 上传一个落盘日志文件（上次会话回捞用） */
    fun uploadFile(ctx: Context, file: File, kind: String, note: String = ""): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        return upload(ctx, kind, text, note)
    }

    /**
     * 同步上传（仅供 CrashHandler 在进程将死时调用）。
     * 必须阻塞，因为崩溃后进程很快会被系统回收，异步任务大概率来不及跑完。
     */
    fun uploadBlocking(ctx: Context, kind: String, log: String, note: String = "") {
        if (!isEnabled(ctx)) return
        val url = endpoint(ctx)
        if (url.isBlank()) return
        val payload = buildPayload(ctx, kind, log, note)
        val ok = post(url, payload)
        lastResult = if (ok) "崩溃日志已上传" else "崩溃日志上传失败"
    }

    /** 主界面「测试上传」：发一条最小记录，用于确认地址是否可达 */
    fun testUpload(ctx: Context) {
        upload(ctx, "test", "连通性测试：${nowText()}", "来自主界面测试按钮")
    }

    // =====================================================================
    // 内部实现
    // =====================================================================

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun buildPayload(ctx: Context, kind: String, log: String, note: String): String {
        // ★ 保头 + 保尾：崩溃记录的头部是 native 崩溃现场，尾部是崩溃前最后几行，
        //   中间的大段是 /proc/self/maps（几万行，价值最低），舍弃它。
        val body = if (log.length > MAX_LOG_CHARS) {
            val tailKeep = MAX_LOG_CHARS - HEAD_KEEP_CHARS
            val omitted = log.length - HEAD_KEEP_CHARS - tailKeep
            log.take(HEAD_KEEP_CHARS) +
                "\n…（中间 $omitted 字符已省略，多为 /proc/self/maps）…\n" +
                log.takeLast(tailKeep)
        } else log

        return JSONObject().apply {
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

    /** 单次 POST，返回是否成功（2xx） */
    private fun post(urlStr: String, payload: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "CarDockBar-LogUploader")
            }
            OutputStreamWriter(c.outputStream, "UTF-8").use { w ->
                w.write(payload)
                w.flush()
            }
            val code = c.responseCode
            // 读掉响应体，避免连接被复用时报错
            runCatching { c.inputStream?.close() }
            code in 200..299
        } catch (t: Throwable) {
            Logx.d("日志上传失败：${t.javaClass.simpleName} ${t.message}")
            false
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    // =====================================================================
    // 崩溃取证：供 App 下次启动时补传
    // =====================================================================

    fun logDir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    /** 本次会话的落盘日志 */
    fun sessionLog(ctx: Context): File = File(logDir(ctx), "session.log")

    /** 上次会话的落盘日志（崩溃取证的主要素材） */
    fun prevLog(ctx: Context): File = File(logDir(ctx), "prev.log")

    /** Java 崩溃堆栈（CrashHandler 写） */
    fun javaCrashFile(ctx: Context): File = File(ctx.filesDir, "last_crash.txt")

    /** Native 崩溃信息（native 信号处理器写，覆盖 SIGSEGV / SIGABRT 等） */
    fun nativeCrashFile(ctx: Context): File = File(logDir(ctx), "native_crash.txt")

    /**
     * abort 原因（stderr 重定向产物）。
     *
     * ★ v1.4.51：nativeInstall 时把 fd 2 指到这个文件。libc 在调 abort() **之前**
     *   会先把原因写到 stderr（如 `free(): invalid pointer`、`Assertion failed: ...`），
     *   应用进程默认 stderr 是 /dev/null，这些信息本来全部丢失。
     *   重定向后下次启动回捞，往往一句话就能定性。
     */
    fun nativeAbortFile(ctx: Context): File = File(logDir(ctx), "native_crash.txt.err")

    private fun cleanExitMarker(ctx: Context) = File(logDir(ctx), "clean_exit")
    private fun crashUploadedMarker(ctx: Context) = File(logDir(ctx), "crash_uploaded.txt")

    /** 标记「这次退出是用户主动关闭」，用于下次启动判断上次是否为异常结束 */
    fun markCleanExit(ctx: Context) {
        runCatching { cleanExitMarker(ctx).writeText(nowText()) }
    }

    /**
     * 上次会话的崩溃取证 + 补传。**在 App.onCreate 里、日志轮转之后调用一次。**
     *
     * 处理顺序：
     *  1. 收集证据：Java 崩溃文件 + Native 崩溃文件 + 上次会话落盘日志
     *  2. 用「文件大小 + mtime」做指纹去重，同一个崩溃不重复上传
     *  3. 按有无崩溃堆栈决定 kind（crash / prev_session）
     *  4. 用 clean_exit 标记判断上次是否正常结束（区分「闪退」与「正常关闭」）
     *
     * @return 一句人类可读的结果，供主界面展示
     */
    fun reportPreviousSession(ctx: Context): String {
        val java = javaCrashFile(ctx)
        val nat = nativeCrashFile(ctx)
        val abt = nativeAbortFile(ctx)
        val prev = prevLog(ctx)

        val hasJava = java.exists() && java.length() > 0
        val hasNative = nat.exists() && nat.length() > 0
        val abortMsg = runCatching {
            if (abt.exists()) abt.readText().trim() else ""
        }.getOrDefault("")
        val hasAbort = abortMsg.isNotEmpty()
        val logText = runCatching { if (prev.exists()) prev.readText() else "" }.getOrDefault("")

        // 上次是否正常结束（用户主动关 Dock 才会写这个标记）
        val clean = cleanExitMarker(ctx).exists()
        cleanExitMarker(ctx).delete()

        // 「上次异常结束」= 有崩溃堆栈，或没有正常关闭标记
        lastReportHadCrash = hasJava || hasNative || !clean

        if (!Prefs.logUploadEnabled(ctx)) return "日志上传未启用"
        if (!hasJava && !hasNative && !hasAbort && logText.isBlank()) {
            // ★ 全新安装 / 首次启动：上次没有任何落盘日志，原实现直接 return，
            //   导致新装机的第一次打开「什么也不上传」——用户无法确认上传链路通不通。
            //   这里补一条轻量 startup 心跳：不依赖车控是否可用，只要 App 进程起来了就发。
            upload(ctx, "startup", buildHeartbeat(ctx), "应用启动（无上次会话记录）")
            return "无上次会话记录（已发 startup 心跳）"
        }

        // 指纹去重：同一个崩溃不重复上传
        //
        // ⚠️ v1.4.52 修：原来指纹里带了 `logText.length`（上次会话日志长度）。
        //   会话日志每次启动长度都不同 → 指纹永远不相等 → **同一个旧崩溃文件被反复重传**，
        //   19:03–19:04 连续收到 8 条一模一样的 crash 记录，全是 18:35 那次崩溃的重传，
        //   真实情况是 1.4.51 一次都没崩。害得远端误判「还在崩」。
        //   现在指纹只认崩溃文件本身；上传成功后把崩溃文件删掉，从根上断掉重传。
        val fp = "${java.length()}-${java.lastModified()}|${nat.length()}-${nat.lastModified()}"
        val marker = crashUploadedMarker(ctx)
        if (runCatching { marker.readText().trim() }.getOrDefault("") == fp) {
            return "上次崩溃已上传过，跳过"
        }

        val sb = StringBuilder()
        // ★ abort 原因放在最前：一句话定性，且上传截断时先保头，它一定活下来
        if (hasAbort) {
            sb.append("===== abort 原因（libc 写到 stderr，已重定向回捞）=====\n")
                .append(abortMsg).append("\n\n")
        }
        if (hasJava) {
            sb.append("===== Java 崩溃堆栈 =====\n")
                .append(runCatching { java.readText() }.getOrDefault("")).append("\n\n")
        }
        if (hasNative) {
            sb.append("===== Native 崩溃（信号处理器）=====\n")
                .append(runCatching { nat.readText() }.getOrDefault("")).append("\n\n")
        }
        sb.append("===== 崩溃前日志（上次会话，落盘回捞）=====\n").append(logText)

        val kind = if (hasJava || hasNative) "crash" else "prev_session"
        val note = buildString {
            append(
                when {
                    hasJava && hasNative -> "Java + Native 崩溃"
                    hasJava -> "Java 崩溃"
                    hasNative -> "Native 崩溃"
                    else -> "无崩溃堆栈"
                }
            )
            if (hasAbort) append("　abort原因=").append(abortMsg.take(120))
            append("；上次会话").append(if (clean) "正常结束" else "异常结束（疑似闪退/被系统杀死）")
        }

        val ok = upload(ctx, kind, sb.toString(), note)
        if (ok) {
            runCatching { marker.writeText(fp) }
            return "已补传上次崩溃：$note"
        }
        return "崩溃日志上传失败（已保留本地证据，下次启动重试）"
    }

    /** 清空崩溃取证（主界面「清除崩溃记录」用） */
    fun clearCrashEvidence(ctx: Context) {
        runCatching { javaCrashFile(ctx).delete() }
        runCatching { nativeCrashFile(ctx).delete() }
        runCatching { nativeAbortFile(ctx).delete() }
        runCatching { crashUploadedMarker(ctx).delete() }
    }

    /**
     * 启动心跳内容：一条极短的「我起来了」记录。
     *
     * 不依赖车控通道是否可用，所以平板 / 没有 ECarX HAL 的设备也能发出来，
     * 专门用来回答「上传链路到底通不通」这个问题。
     */
    private fun buildHeartbeat(ctx: Context): String = buildString {
        append("===== 启动心跳 =====\n")
        append("设备=").append(deviceId(ctx))
            .append("　机型=").append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android=").append(Build.VERSION.RELEASE).append("(SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("版本=").append(appVersion(ctx)).append("\n")
        append("安全等级=").append(Prefs.safeLevel(ctx)).append("\n")
        append("上传地址=").append(endpoint(ctx)).append("\n")
        append("Dock 是否已启动=").append(DockOverlayService.isRunning).append("\n")
    }

    // ---------------- 连续崩溃计数 / 安全模式阶梯 ----------------

    /** 上次会话是否崩溃（reportPreviousSession 的副产物，供 App 判定安全等级） */
    @Volatile
    var lastReportHadCrash: Boolean = false
        private set

    /** 活过 [STABLE_MS] 就认为「稳定」，由 DockOverlayService 打点 */
    const val STABLE_MS = 15_000L

    private fun streakFile(ctx: Context) = File(logDir(ctx), "crash_streak.txt")
    private fun stableMarker(ctx: Context) = File(logDir(ctx), "stable.marker")

    /** 服务存活超过 STABLE_MS 时调用，表示这次启动是稳定的 */
    fun markStable(ctx: Context) {
        runCatching { stableMarker(ctx).writeText(nowText()) }
    }

    /**
     * 更新「连续崩溃次数」。App.onCreate 调用一次。
     * @return 更新后的连续崩溃次数（0 = 上次正常）
     */
    fun updateCrashStreak(ctx: Context, hadCrash: Boolean): Int {
        val stable = stableMarker(ctx).exists()
        stableMarker(ctx).delete()
        val prev = runCatching { streakFile(ctx).readText().trim().toInt() }.getOrDefault(0)
        val now = if (hadCrash && !stable) prev + 1 else 0
        runCatching { streakFile(ctx).writeText(now.toString()) }
        return now
    }

    /** 清零连续崩溃计数（版本升级 / 主界面手动恢复全功能时用） */
    fun resetCrashStreak(ctx: Context) {
        runCatching { streakFile(ctx).writeText("0") }
    }

    /** 当前连续崩溃次数（主界面展示用） */
    fun crashStreak(ctx: Context): Int =
        runCatching { streakFile(ctx).readText().trim().toInt() }.getOrDefault(0)

    /** 是否存在尚未上传的崩溃记录（主界面展示用） */
    fun hasPendingCrash(ctx: Context): Boolean {
        val fp = "${javaCrashFile(ctx).length()}-${javaCrashFile(ctx).lastModified()}|" +
                "${nativeCrashFile(ctx).length()}-${nativeCrashFile(ctx).lastModified()}|"
        val marker = runCatching { crashUploadedMarker(ctx).readText() }.getOrDefault("")
        val hasFile = (javaCrashFile(ctx).exists() && javaCrashFile(ctx).length() > 0) ||
                (nativeCrashFile(ctx).exists() && nativeCrashFile(ctx).length() > 0)
        return hasFile && !marker.startsWith(fp)
    }

    // ---------------- 失败重试队列 ----------------

    private fun pendingFile(ctx: Context): File =
        File(File(ctx.filesDir, "logs").apply { mkdirs() }, "pending.jsonl")

    private fun appendPending(ctx: Context, payload: String) {
        runCatching { pendingFile(ctx).appendText(payload + "\n") }
    }

    /** 把之前失败的记录补传一遍（最多 20 条，避免堆积） */
    private fun flushPending(ctx: Context, url: String) {
        val f = pendingFile(ctx)
        if (!f.exists() || f.length() == 0L) return
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@runCatching
            val keep = ArrayList<String>()
            lines.take(20).forEach { line ->
                if (!post(url, line)) keep.add(line)
            }
            keep.addAll(lines.drop(20))
            if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }
}
