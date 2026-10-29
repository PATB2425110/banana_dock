package com.car.dockbar.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
/**
 * 提权环境探测：**在车机本地**判断「能不能拿到 adb / root 权限」，不依赖电脑。
 *
 * ## 为什么需要它
 *
 * 用户连不了电脑 adb，所以无法在 PC 侧跑 `pm grant` / `getprop`。
 * 但以下几件事普通 App 在车机本地**都能自己做**：
 *   - `getprop` 读属性（只读，无需权限）
 *   - 连 `127.0.0.1:5555` 发 adb 握手包，看 adbd 是否可达、是否免认证
 *   - 探测 `su` 是否存在并能否执行
 *
 * 探测结果全部打进日志并随日志上传，我在远端据此选型，用户什么都不用判断。
 *
 * ## 判定标准
 *
 * - `ro.adb.secure = 0` 且 adbd 监听 TCP → **App 内置 adb 客户端即可拿到 shell**，
 *   进而 `pm grant` 自己权限。开机自动，全程不用电脑。★最优
 * - adbd 可达但回 `AUTH` → 需要已授权的 RSA key，我们没有 → 这条路走不通
 * - `su` 可用 → root 可用，可直接 `pm grant`，或把 APK 装进 `/system/priv-app`
 * - 都不行 → 车控写入只能靠跳转原生空调界面
 */
object PrivilegeProbe {

    private const val A_CNXN = 0x4e584e43   // 'CNXN'
    private const val A_AUTH = 0x48545541   // 'AUTH'

    private val PROPS = listOf(
        "ro.adb.secure",          // 0 = adbd 不校验 RSA key（App 可直连）★关键
        "ro.secure",
        "ro.debuggable",          // 1 = userdebug/eng 版，通常 adb root 可用
        "init.svc.adbd",          // adbd 是否在运行
        "service.adb.tcp.port",   // 当前 adbd 监听的 TCP 端口（空 = 只听 USB）
        "persist.adb.tcp.port",   // 持久化端口，重启后仍生效
        "ro.build.type",          // user / userdebug / eng
    )

    private val PORTS = listOf(5555, 5556, 5037)

    /** 读一个系统属性（普通 App 可执行 getprop，只读不需权限） */
    fun getprop(name: String): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", name))
        val v = p.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        p.destroy()
        v
    }.getOrElse { "(读取失败:${it.javaClass.simpleName})" }

    /**
     * 向本机 [port] 发一个 adb `CNXN` 握手包，看回什么。
     *
     * - 回 `CNXN` → adbd 免认证，**直接可用**
     * - 回 `AUTH` → 需要已授权 RSA key，我们没有，不可用
     * - 连不上 / 超时 → adbd 没在监听 TCP
     */
    fun probeAdbPort(port: Int, timeoutMs: Int = 1500): String = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(cnxnPacket("host::features=shell_v2,cmd\u0000"))
                flush()
            }
            val hdr = ByteArray(24)
            DataInputStream(s.getInputStream()).readFully(hdr)
            val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int
            val arg0 = b.int
            when (cmd) {
                A_CNXN -> "★可达且免认证（App 可直接拿 shell）"
                A_AUTH -> "可达但需认证（AUTH type=$arg0），无已授权 key → 不可用"
                else -> "有响应但非 adb 协议（cmd=0x${Integer.toHexString(cmd)}）"
            }
        }
    }.getOrElse { e ->
        when (e) {
            is java.net.ConnectException -> "端口关闭"
            is java.net.SocketTimeoutException -> "超时（端口开但无 adb 响应）"
            else -> "不通（${e.javaClass.simpleName}: ${e.message}）"
        }
    }

    /** 探测 root：su 是否存在、能否执行 */
    fun probeSu(): String {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/bin/su.bin", "/su/bin/su"
        )
        val found = paths.filter { java.io.File(it).exists() }
        if (found.isEmpty()) return "未发现 su 二进制 → 大概率无 root"
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf(found.first(), "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0 && out.contains("uid=0")) "★root 可用（${out}）"
            else "su 存在但未授权（exit=$code out=${out.take(40)} err=${err.take(40)}）"
        }.getOrElse { "su 执行异常：${it.javaClass.simpleName}" }
    }

    /** 当前车控权限状态（决定能不能写空调） */
    fun carPermissions(ctx: Context): List<Pair<String, Boolean>> {
        val perms = listOf(
            "android.car.permission.CONTROL_CAR_CLIMATE",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.permission.WRITE_SECURE_SETTINGS"
        )
        return perms.map { p ->
            p to runCatching {
                ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }
    }

    /** 生成一份完整报告（打日志 + 主界面展示 + 上传） */
    fun report(ctx: Context): String = buildString {
        append("===== 提权环境探测 =====\n")
        append("[系统属性]\n")
        for (p in PROPS) append("  $p = ").append(getprop(p)).append('\n')

        append("[adb 端口]\n")
        for (port in PORTS) {
            append("  127.0.0.1:$port → ").append(probeAdbPort(port)).append('\n')
        }

        append("[root]\n  ").append(probeSu()).append('\n')

        append("[车控权限]\n")
        for ((p, ok) in carPermissions(ctx)) {
            append("  ").append(p.removePrefix("android.car.permission.").removePrefix("android.permission."))
                .append(" = ").append(if (ok) "已授予" else "★未授予").append('\n')
        }
        append("========================\n")
    }

    // ---------------- adb 自授权 ----------------

    /** adbd 常监听的端口（本机实测 service.adb.tcp.port=5555，persist 是 5556） */
    private val ADB_PORTS = listOf(5555, 5556, 5557)

    /**
     * 连本机 adbd 拿 shell，然后给自己授予车控权限。
     *
     * 返回人类可读的完整过程（成功/失败原因都会写清楚），供主界面展示 + 上传。
     * 全程不抛异常，任何一步失败都只是记一行。
     */
    /**
     * ★ v1.4.93：**关闭 ADB**（安全加固）。
     *
     * 背景：这台 userdebug 车机的 adbd 常开，且 `service.adb.tcp.port=5555`
     *   免 RSA 认证 → **本机任何 App 都能直接拿到 adb shell**
     *   （本项目自己的 `AdbClient` 就是这么连的，用于 `pm grant`）。
     *
     * 关掉后**不影响任何功能**（实测结论）：
     *   · 车控走 ECarX adaptapi 的公开接口，不校验 `CONTROL_CAR_CLIMATE`
     *     ——该权限至今未授予，空调照样能控
     *   · 悬浮窗 / 无障碍 / 通知使用权都是用户手动授的，与 adb 无关
     *
     * ⚠ **不可逆**：关掉后没有电脑 adb 就无法再打开，需要在系统里改回来。
     */
    fun disableAdb(ctx: Context): String = buildString {
        appendLine("===== 关闭 ADB =====")
        // 先找出当前能连上的 adb 端口
        val ports = listOf(5555, 5556, 5554, 5037).distinct()
        var port: Int? = null
        for (p in ports) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port == null) {
            appendLine("  ✗ 没有可用的 adb 连接（端口 5555/5556/5554/5037 都不通）")
            appendLine("    → ADB 可能已经关闭了，无需再关")
            return@buildString
        }
        appendLine("  已连上 adb 端口 $port，开始关闭：")
        val cmds = listOf(
            "setprop persist.adb.tcp.port -1",   // 关掉持久化的 TCP 端口（重启后仍不开放）
            "stop adbd",                          // 停掉 adbd 进程
            "setprop service.adb.tcp.port -1",     // 清掉当前端口属性
            "setprop ctl.restart adbd"            // 若系统允许再拉起则无所谓，端口已清
        )
        for (c in cmds) {
            val r = AdbClient.exec(port, c, 8_000)
            appendLine("  $c")
            appendLine("    → " + if (!r.ok) "失败：${r.error.take(80)}"
                                    else (r.text.trim().take(80).ifEmpty { "OK（无输出即成功）" }))
        }
        appendLine("")
        appendLine("★ ADB 已关闭。**空调等车控功能不受影响**（实测：CONTROL_CAR_CLIMATE")
        appendLine("  至今未授予，空调照样能控 —— 车控走的是厂商公开接口）。")
        appendLine("★ 警告：没有电脑 adb 就**无法再打开**，需要在系统侧（recovery/刷机）恢复。")
    }

    fun grantCarPermissionsViaAdb(ctx: Context): String = buildString {
        val pkg = ctx.packageName
        append("===== adb 自授权 =====\n")

        var port = -1
        for (p in ADB_PORTS) {
            val r = AdbClient.exec(p, "id", 4_000)
            if (r.ok) {
                port = p
                append("adb 端口 $p 已连通\n  id → ${r.text.trim()}\n")
                val su = AdbClient.exec(p, "su -c id", 6_000)
                append("  su -c id → ${su.text.trim().take(80)}\n")
                break
            } else {
                append("adb 端口 $p 不可用（${r.error.take(60)}）\n")
            }
        }
        if (port < 0) {
            append("结论：本机 adbd 不可达，无法自授权。\n")
            return@buildString
        }

        val perms = listOf(
            "android.car.permission.CONTROL_CAR_CLIMATE",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_INFO",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.permission.WRITE_SECURE_SETTINGS"
        )
        for (perm in perms) {
            val r = AdbClient.exec(port, "pm grant $pkg $perm", 8_000)
            val out = r.text.trim()
            val short = perm.removePrefix("android.car.permission.").removePrefix("android.permission.")
            // pm grant 成功时静默无输出；失败会带错误信息
            val verdict = when {
                !r.ok -> "执行失败（${r.error.take(60)}）"
                out.isBlank() -> "★成功（无输出即成功）"
                out.contains("not a changeable permission", true) ||
                    out.contains("is not a changeable", true) ->
                    "✗该权限是 signature 级，adb 也授不了 → 需装成系统应用(/system/priv-app)"
                out.contains("Unknown permission", true) -> "✗权限名不存在"
                else -> "✗失败：$out"
            }
            append("  pm grant $short → $verdict\n")
        }

        append("[授权后状态]\n")
        for ((p, ok) in carPermissions(ctx)) {
            append("  ").append(p.removePrefix("android.car.permission.").removePrefix("android.permission."))
                .append(" = ").append(if (ok) "★已授予" else "未授予").append('\n')
        }
        append("======================\n")
    }

    // ---------------- 竞品取证 ----------------

    /**
     * ★★ 查「别人家为什么能控空调还不闪退」——决定性证据。
     *
     * 思路：本机 adbd 免认证可达（实测 127.0.0.1:5555，shell uid=2000），
     * 于是 App 自己就能跑 `pm list packages -f` / `dumpsys package`，
     * 把目标包的**安装位置、系统标志、已授予权限、uid** 全部抓出来。
     *
     * 判读：
     *  - codePath 在 `/system/priv-app` 或 `/system/app` → 它是**系统应用**，
     *    signature 级权限在安装时自动授予 → 它能写、也不会被厂商库 abort。★最可能的答案
     *  - userId < 10000 或有 sharedUserId → 系统 uid
     *  - grantedPermissions 含 CONTROL_CAR_CLIMATE → 拿到了写入权限（我们拿不到）
     *  - 以上都没有却仍能写 → 那它走的是别的通道（跳转系统空调界面 / 模拟点击）
     */
    fun probeRivalPackage(pkg: String): String = buildString {
        append("===== 竞品取证：$pkg =====\n")

        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) {
            append("本机 adbd 不可达，无法取证。\n")
            return@buildString
        }
        append("（通过 127.0.0.1:$port，shell 身份执行）\n\n")

        val path = AdbClient.exec(port, "pm list packages -f $pkg", 6_000)
        append("[安装位置]\n  ").append(path.text.trim().ifBlank { "(查不到该包)" }).append("\n\n")

        val dump = AdbClient.exec(port, "dumpsys package $pkg", 12_000)
        val body = dump.text
        if (body.isBlank()) {
            append("[dumpsys] 无输出（${dump.error.take(80)}）\n")
        } else {
            val keep = body.lineSequence().filter { l ->
                val s = l.trim()
                s.startsWith("userId=") || s.startsWith("sharedUserId=") ||
                    s.startsWith("codePath=") || s.startsWith("versionCode") ||
                    s.startsWith("flags=") || s.startsWith("pkgFlags") ||
                    s.startsWith("dataDir=") || s.startsWith("primaryCpuAbi=") ||
                    s.contains("android.car.permission") ||
                    s.contains("WRITE_SECURE_SETTINGS") ||
                    s.contains("READ_FRAME_BUFFER") ||
                    s.contains("privapp") || s.contains("privileged") ||
                    s.contains("grantedPermissions") || s.contains("install permissions") ||
                    s.contains("requested permissions")
            }.take(80).joinToString("\n")
            append("[dumpsys 关键片段]\n").append(keep.ifBlank { "(没匹配到关键行，原始输出前 500 字：\n${body.take(500)})" }).append("\n\n")
        }

        val ps = AdbClient.exec(port, "ps -A -o PID,UID,NAME 2>/dev/null | grep $pkg", 6_000)
        if (ps.text.isNotBlank()) append("[进程]\n  ").append(ps.text.trim().take(300)).append("\n\n")

        append("[判读]\n")
        val txt = (path.text + body)
        val isSystem = txt.contains("/system/priv-app") || txt.contains("/system/app") ||
            txt.contains("/product/priv-app") || txt.contains("/vendor/app")
        val hasClimate = txt.contains("android.car.permission.CONTROL_CAR_CLIMATE")
        append("  · ").append(
            if (isSystem) "★它是**系统应用**（安装路径在 /system 下）→ signature 级权限自动授予，这是它能写且不被 abort 的根本原因"
            else "不是系统应用（装在 /data/app）→ 它跟我们一样是普通 App"
        ).append('\n')
        append("  · ").append(
            if (hasClimate) "★权限清单里出现 CONTROL_CAR_CLIMATE（需再看是否已 granted）"
            else "权限清单里没看到 CONTROL_CAR_CLIMATE → 它可能压根不走车控 HAL，而是跳转系统空调界面 / 模拟点击"
        ).append('\n')
        append("========================\n")
    }

    /**
     * ★★ v1.4.66：取证「能控空调的竞品」在车上的真实行为。
     *
     * 两种可能，必须用证据区分：
     *  A) 它**自己写车控 HAL**（调 setFunctionValue / setCustomizeFunctionValue）
     *  B) 它**只是拉起系统空调界面**（launcher 类应用的常见做法）
     *
     * 做法：拿到它的 pid → 抓它自己进程的 logcat → 看有没有
     *  `setFunctionValue` / `setCustomizeFunctionValue` / `am start` 到 hvac。
     *
     * @param pkg   目标包名
     * @param waitMs 抓取窗口（用户在这段时间里操作它的空调卡片）
     */
    fun probeCompetitorRuntime(pkg: String, waitMs: Long = 30_000): String = buildString {
        append("===== 竞品运行时取证：$pkg =====\n")
        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) {
            append("本机 adbd 不可达。\n")
            return@buildString
        }
        append("adb 端口 $port\n\n")

        val pid = AdbClient.exec(port, "pidof $pkg", 6_000).text.trim()
        append("pid = ${pid.ifBlank { "(未运行，请先在车机上打开它)" }}\n")
        val path = AdbClient.exec(port, "pm path $pkg", 6_000).text.trim()
        append("安装路径 = ${path.ifBlank { "(未安装)" }}\n")
        append("\n★ 请在接下来的 ${waitMs / 1000} 秒内，在车机上打开「$pkg」" +
            "并操作它的空调卡片（调温度 / 切内循环 / 开关 A-C）\n\n")

        AdbClient.exec(port, "logcat -c", 8_000)
        android.os.SystemClock.sleep(waitMs)

        // 全量抓（不按 pid，因为要看到它拉起别的 app 的痕迹）
        val dump = AdbClient.exec(port, "logcat -d -v time", 60_000)
        val body = dump.text
        if (body.isBlank()) {
            append("logcat 无输出\n")
            return@buildString
        }
        val KW = listOf(
            "EU+IHforDLoak0eOD8N6oxw8pxztJQXM",
            "Ck+IHforDLoak0eOD8N6oxw8pxztJQXM",
            "EU+IHfkBDKkZ7SaCDOZqmBdH9lruDQ3aP+JWqzTdUJwRVv4T",
            "Ck+IHfkBDKkZ7SaCDOZqmBdH9lruDQ3aP+JWqzTdUJwRVv4T",
            "FyWQaMxLbbkZ7XWMD95EiBhht13qHQ2IPdAv2A==",
            "EV+IRclPX6AV/l+pRuV6nhtiiV3vE0DDP5Zkzju6bp8WZPod9xMFzA==",
            "HyaQR8MqCKMb11vQDOUxlQ==",
            "HU+iVvorDLoak0eOD8N05A==",
            "FSSMR8MRALce7XXY",
            "HSSMR8MRAJ0e7XXY",
            "EiaQR8Mpdcw=",
            "CiSQR8Mrdcw=",
            "Ek+2GPkCV6Y=",
            "HE+uQ/40DLwY/VuNAeBEsQpG/hM=",
            "HyeEX8MrAKMa/SKAAeV6jh1xqBM=",
            "HyeQQ8orX70ewC/Y",
            "O3rsV9MVTKI=",
            "O3rsV9MVTKk=",
            "O3rsV9MVTKY=",
            "O3rsV9MVTKc=",
            "O3rsV9MVTKU=",
            "H3yEbfk1Bcw=",
            "H3qER881Bcw=",
            "F3qER881Bcw=",
            "HU+2X8pLV6AV9i/Y",
            "FU+2X8pLV6AV9i/Y",
            "FSSxRMMBALceyEfY",
            "HSSQHclPCL8Z7nW/DuZElhU8s1ruIAmIPvYv2A==",
            "GUCMR8MrAbQe7l+MDso+5A==",
            "Enn1WcMSW7cc/UOV",
        ).map { Sec.dec(it, "kw_$it") }
        val hits = body.lineSequence().filter { l -> KW.any { l.contains(it) } }.toList()
        append("日志 ${body.length} 字符，命中 ${hits.size} 条（显示前 250 条）：\n\n")
        // 优先显示与该 pkg 相关的行
        val mine = hits.filter { it.contains(pkg) }
        val other = hits.filter { !it.contains(pkg) }
        if (mine.isNotEmpty()) {
            append("-- 属于 $pkg 自己的行 --\n")
            mine.take(120).forEach { append("  ").append(it.take(260)).append('\n') }
            append('\n')
        }
        append("-- 其他相关行（车控 API / 界面拉起）--\n")
        other.take(130).forEach { append("  ").append(it.take(260)).append('\n') }
        append("\n判读：\n")
        append(" · 出现车控 HAL 写入调用 → 它自己写车控（我们照抄）\n")
        append(" · 只出现 am start 到 hvac/Climate → 它只是拉起系统空调界面（我们也照抄）\n")
        append(" · 出现 ISensor / IVirtualSensor → 它读的是传感器通道\n")
        append("====================================\n")
    }

    // ---------------- 抓系统空调的 logcat（学它怎么写） ----------------

    /**
     * ★★ v1.4.55：抓 logcat，看**系统空调界面**操作时到底调了什么。
     *
     * 为什么需要：我们自己写 `0x10060100`（温度）怎么试都被 HAL 拒，
     * 而系统空调面板能调。差别只能在「调用形态 / functionId / zone / value」上。
     * 抓一份「用户操作前 → 操作后」的 logcat，一对比就知道正确答案。
     *
     * 步骤（全部走车机本地 adb，shell 身份即可，不需要 root）：
     *   1. `logcat -c` 清空缓冲区
     *   2. 提示用户去车机上操作空调（**实体按键 + 空调 App 两种都操作**）
     *   3. `logcat -d` 抓全量，过滤出 HVAC / Car / ECarX 相关行
     *
     * ★★ v1.4.57：抓取窗口默认改成 **120 秒**（原来 20 秒太短，
     *   用户要切界面、找按钮，20 秒只够点一两下）。
     *
     * @param waitMs 抓取窗口（毫秒）。用户在这段时间里操作车机空调。
     */
    fun grabHvacLogcat(waitMs: Long = 120_000): String = buildString {
        append("===== 抓系统空调 logcat =====\n")
        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) {
            append("本机 adbd 不可达。\n")
            return@buildString
        }
        append("adb 端口 $port 已连通\n\n")

        val clear = AdbClient.exec(port, "logcat -c", 8_000)
        append("[1] 清空日志缓冲区 → ${if (clear.ok) "OK" else "失败：${clear.error.take(60)}"}\n")
        if (!clear.ok) {
            append("（logcat -c 失败多半是权限问题，试试下面的全量抓取）\n")
        }
        append("\n★ 请在接下来的 ${waitMs / 1000} 秒内，去车机上操作空调：\n")
        append("  【实体按键】\n")
        append("  · 按空调开关 / 温度加减实体键，各 1~2 次\n")
        append("  · 按内外循环 / 前挡 / A-C 实体键，各 1 次\n")
        append("  【空调 App】\n")
        append("  · 打开车机自带空调界面（com.geely.hvac）\n")
        append("  · 在界面上点温度 +/−、内外循环、前挡、A-C，各 1 次\n")
        append("  · ⚠ 避开屏幕最底部 120px（那是我们的 Dock，会挡住空调按钮）\n")
        append("  两种方式都要操作，时间充裕，慢慢来。操作完等着别动。\n\n")

        // 等待用户操作（每 10 秒报一次进度，避免以为卡死）
        val step = 1000L
        var waited = 0L
        while (waited < waitMs) {
            android.os.SystemClock.sleep(step)
            waited += step
            if (waited % 10_000 == 0L) {
                append("  …已等 ${waited / 1000}s / ${waitMs / 1000}s\n")
            }
        }

        append("\n[2] 抓取 logcat -d …\n")
        // ★ v1.4.58：先查空调 App 的 pid，再按 pid 抓，噪音降一个数量级。
        //   上一轮全量抓 686 万字符，其中 99% 是 AbsCarSignal 的周期回调。
        val hvacPid = runCatching {
            AdbClient.exec(port, "pidof com.geely.hvac", 6_000).text.trim()
        }.getOrDefault("")
        append("空调 App pid = ${hvacPid.ifBlank { "(未运行)" }}\n\n")

        val dump = if (hvacPid.isNotBlank() && hvacPid.all { it.isDigit() }) {
            AdbClient.exec(port, "logcat -d -v time --pid=$hvacPid", 60_000)
        } else {
            AdbClient.exec(port, "logcat -d -v time", 60_000)
        }
        val body = dump.text
        if (body.isBlank()) {
            append("logcat 无输出（${dump.error.take(80)}）\n")
            return@buildString
        }
        append("原始日志 ").append(body.length).append(" 字符\n\n")

        // 过滤出车控相关行。
        // ★★ v1.4.56：关键词大幅扩充，且**区分「疑似写入」与「普通回调」**。
        //   上一次抓了 686 万字符，但 HVAC 相关行全被上传上限砍掉（ payload 只保 12K 头），
        //   白抓一次。这次在 App 内就把结果压到最小。
        val KW = listOf(
            "MmGiTQ==",
            "EmGiTQ==",
            "EkGCbQ==",
            "GXuqQ/oQXQ==",
            "OXuqQ/oQXQ==",
            "GVuKY9owfQ==",
            "DnKuXsgNX58syw==",
            "LnKuXsgNX58syw==",
            "GXahR/UwXZw9",
            "OXahR/UwXZw9",
            "HHatfesBXZU=",
            "PHatfesBXZU=",
            "HFaNccg0fbQJ",
            "G36xaPcLTw==",
            "GHusWdYLXJQ=",
            "GX6xTe4IWYU=",
            "OX6xTe4IWYU=",
            "GV6Rbc4oeaU=",
            "CHKgR+kH",
            "KHKgR+kH",
            "HnKlXPQXTA==",
            "PnKlXPQXTA==",
            "G3SQWfIQW5k=",
            "O3SQWfIQW5k=",
            "G1SQWfIQW5k=",
            "am/yHqtSCMB9lw==",
            "am/yHqtXCMB9lw==",
            "am/yHqtWCMB9lw==",
            "am/yHqtTCMB9lw==",
            "am/yHqtVCMJ9lw==",
            "am/yHqtQCMB9lw==",
            "am/yHqtQCMJ9lw==",
            "am/yHqtSCMR9lw==",
            "am/yHqtRCMB9lw==",
            "am/yHqtRCMN9lw==",
            "KXK3aO4KW4UkyHyzF+d2vA==",
            "PXK3aO4KW4UkyHyzF+d2vA==",
            "KXK3Z/UQaIMi13eXAvI=",
            "PXK3Z/UQaIMi13eXAvI=",
            "E1SiXN0RVpI5zn2L",
            "GXaxaO4KW4UkyHw=",
            "O3OiXu8FSJg=",
            "G3OiXu8lSJg=",
            "H1SiXMM=",
            "P3SiXOM=",
            "HXu6bfoW",
            "FXmmeP4MUZIhwkGhPQ==",
            "DHKrR/gIXaIkwHyEGg==",
            "CX6kQPoIcJQh13eX",
            "OXiuAPwBXZ00iXqTF+g=",
            "HXu6Y/oNVrAu03uTH/96",
            "EmGiTdgLVoU/yH4=",
            "H3SiXOMtVoE401aMBftirTl/plw=",
            "Pn6wXvoQW5kEyWKQAs51vDRj",
        ).map { Sec.dec(it, "kw_$it") }

        val hits = body.lineSequence().filter { line -> KW.any { line.contains(it) } }.toList()
        append("[3] 车控相关行 ").append(hits.size).append(" 条").append(
            if (hits.size > 900) "（只保留前 900 条，其余已丢弃）" else ""
        ).append("：\n")

        if (hits.isEmpty()) {
            append("（一条都没匹配到）\n\n")
            append("[诊断] 说明系统空调写车控时**完全不打日志**。\n")
            append("       那就只能靠「输入事件 + 状态变化」反推：\n")
            append("       · 系统输入分发器会记录你的点击坐标；\n")
            append("       · AbsCarSignal 会记录属性变化。\n")
            append("       两者时间戳一对，就知道点哪个按钮引起哪条属性变化。\n")
        } else {
            // 先打输入事件（少而关键），再打属性/写入
            val inputs = hits.filter { it.contains(Sec.dec("EmGiTdgLVoU/yH4=", "kw_dispatchInputEvent")) || it.contains(Sec.dec("H3SiXOMtVoE401aMBftirTl/plw=", "kw_EcarxInputDispatcher")) }
            val others = hits.filter { !(it.contains(Sec.dec("EmGiTdgLVoU/yH4=", "kw_dispatchInputEvent")) || it.contains(Sec.dec("H3SiXOMtVoE401aMBftirTl/plw=", "kw_EcarxInputDispatcher"))) }
            if (inputs.isNotEmpty()) {
                append("\n-- 你的点击（系统输入分发器）--\n")
                inputs.take(40).forEach { append("  ").append(it.take(200)).append('\n') }
            }
            append("\n-- 车控属性 / 写入 --\n")
            others.take(800).forEach { append("  ").append(it.take(280)).append('\n') }
        }

        // 无论有没有命中，都统计一下 AbsCarSignal 里出现过的属性名
        val names = LinkedHashMap<String, Int>()
        val nameRe = Regex("propertyName ([A-Za-z0-9_]+)")
        for (l in body.lineSequence()) {
            val m = nameRe.find(l)
            if (m != null) {
                val nm = m.groupValues[1]
                names[nm] = (names[nm] ?: 0) + 1
            }
        }
        if (names.isNotEmpty()) {
            append("\n[4] 本次抓到的全部属性名（共 ").append(names.size).append(" 种）：\n")
            names.entries.sortedByDescending { it.value }.take(60).forEach {
                append("  ").append(it.key).append(" ×").append(it.value).append('\n')
            }
        }
        append("\n提示：把上面属性名/坐标和我们的通道表对照，找出温度的真正通道。\n")
        append("========================================\n")
    }

    // ---------------- 内外循环专项取证（v3.6） ----------------

    /**
     * ★★★ v3.6：抓**系统 Dock / 空调 App 切换内外循环**时的真实 HAL 调用。
     *
     * 背景：v3.6 我们按系统 Dock 反编译的权威实现改了内外循环的值域
     *   （外循环 = 裸值 0，内循环 = 复合 0x10030101，zone = 0x80000000）。
     * 但**这台车**的 HAL 到底认哪个值，只有真车日志能定论。
     *
     * 做法：清空 logcat → 提示用户在**系统 Dock** 或**系统空调 App** 里切内外循环
     *   → 抓全量 logcat，把 `setFunctionValue` / `getFunctionValue` /
     *     `0x10030100` / `HVAC_FUNC_CIRCULATION` 相关的行全部捞出来，
     *     并**按时间顺序**打印（谁先谁后一目了然）。
     *
     * @param waitMs 抓取窗口（毫秒），用户在这段时间里操作
     */
    fun grabRecircEvidence(waitMs: Long = 90_000): String = buildString {
        append("===== 内外循环专项取证（v3.6）=====\n")
        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) {
            append("本机 adbd 不可达（试过 5555/5556/5557）。\n")
            append("→ 无法抓系统 logcat，只能看我们自己的日志。\n")
            return@buildString
        }
        append("adb 端口 $port 已连通\n\n")

        val clear = AdbClient.exec(port, "logcat -c", 8_000)
        append("[1] 清空日志缓冲区 → ${if (clear.ok) "OK" else "失败：" + clear.error.take(60)}\n\n")

        append("★ 请在接下来的 ${waitMs / 1000} 秒内，**在系统 Dock 或系统空调 App 里**切换内外循环：\n")
        append("  · 系统 Dock 的循环按钮（com.geely.dockbar）点 2~3 次\n")
        append("  · 系统空调界面（com.geely.hvac）的循环按钮点 2~3 次\n")
        append("  · **外循环和内循环都要点到**（每个状态各停 3 秒）\n")
        append("  · ⚠ 屏幕最底部 120px 是我们的 Dock，别点到那儿\n")
        append("  我们的 App 不用动，只操作系统的。\n\n")

        var waited = 0L
        while (waited < waitMs) {
            android.os.SystemClock.sleep(1000L)
            waited += 1000L
            if (waited % 15_000 == 0L) append("  …已等 ${waited / 1000}s / ${waitMs / 1000}s\n")
        }

        append("\n[2] 抓取 logcat -d …\n")
        val dump = AdbClient.exec(port, "logcat -d -v time", 60_000)
        val body = dump.text
        if (body.isBlank()) {
            append("logcat 无输出（${dump.error.take(80)}）\n")
            return@buildString
        }
        append("原始日志 ${body.length} 字符\n\n")

        // 精准关键词：只要跟循环 / 车控写入 / 我们已知的 functionId 有关的
        val KW = listOf(
            "am/yHqtXCMB9lw==",
            "ayfzHatVCME=",
            "EkGCbcQibb8O+FGsJMhWlRtDimHV",
            "GV6Rbc4oeaUE6Fw=",
            "KXK3aO4KW4UkyHyzF+d2vA==",
            "PXK3aO4KW4UkyHyzF+d2vA==",
            "KXK3be4XTJ4gzmiAMP5tui5+rEDNBVSEKA==",
            "M2SFW/UHTJgiyUGQBvtsqy5ypw==",
            "KHKkR+gQXYML0nyGAuJstwx2r1v+M1mFLs93lw==",
            "GW6gQv4pV5Uo9HuCGOpv",
            "CHKgV/gIXaIkwHyEGg==",
            "CHKgV/gIXaIkwHyEGshvsDl8",
            "GX6xTe4IWYU=",
            "OX6xTe4IWYU=",
            "GV6Rbc4oeaU=",
            "CHKgR+kH",
            "KHKgR+kH",
            "E1SiXN0RVpI5zn2L",
            "GXaxaO4KW4UkyHw=",
            "O3OiXu8FSJg=",
            "G3OiXu8lSJg=",
            "H1SiXMM=",
            "P3SiXOM=",
            "HXu6bfoW",
            "FXmmeP4MUZIhwkGhPQ==",
            "DHKrR/gIXaIkwHyEGg==",
            "EkGCbQ==",
            "EmGiTQ==",
            "MmGiTQ==",
            "OXiuAPwBXZ00iXaKFeBhuCg=",
            "OXiuAPwBXZ00iXqTF+g=",
            "HXu6Y/oNVrAu03uTH/96",
            "am/yHqtVCMN9lw==",
            "am/yHqtWCMN9lw==",
            "am/yHqtWCMB9lw==",
        ).map { Sec.dec(it, "kw_$it") }
        val hits = body.lineSequence()
            .filter { l -> KW.any { l.contains(it) } }
            .toList()
        append("[3] 命中 ${hits.size} 行（按时间顺序）\n\n")
        if (hits.isEmpty()) {
            append("（一条都没匹配到 —— 系统写车控时可能完全不打日志）\n")
            append("那就只能靠「我们的日志 + 状态变化」反推。\n")
        } else {
            hits.take(600).forEach { append("  ").append(it.take(300)).append('\n') }
        }

        // 专项：把 setFunctionValue 调用的实参形式单独摘出来（最关键）
        append("\n\n[4] ★ 专项：车控 HAL 读写的实参（最关键）\n")
        val callRe = Regex("(set|get)FunctionValue[^\\n]{0,220}")
        val calls = hits.mapNotNull { callRe.find(it)?.value }.distinct()
        if (calls.isEmpty()) {
            append("（没有抓到 FunctionValue 调用）\n")
        } else {
            calls.take(120).forEach { append("  ").append(it).append('\n') }
        }

        // 专项：0x10030100 附近的数值（十进制 + 十六进制对照）
        append("\n[5] ★ 专项：0x10030100 相关的数值（十进制↔十六进制对照）\n")
        val numRe = Regex("(26863[0-9]{3}|0x100301[0-9a-fA-F]{2})")
        val nums = LinkedHashSet<String>()
        for (l in hits) {
            for (m in numRe.findAll(l)) {
                val s = m.value
                val dec = if (s.startsWith("0x")) s.removePrefix("0x").toLong(16).toString() else s
                nums.add("$s = $dec")
            }
        }
        if (nums.isEmpty()) append("（无）\n") else nums.forEach { append("  ").append(it).append('\n') }

        append("\n判读：\n")
        append(" · 出现 value=0（十进制）→ 外循环的权威写值就是裸值 0 ✔ v3.6 的实现正确\n")
        append(" · 出现 value=268632321（=0x10030101）→ 内循环用复合编码 ✔\n")
        append(" · 出现 value=268632320（=0x10030100）→ 外循环用这个复合值\n")
        append(" · 出现 value=10 / 11 → 用的是 10/11 枚举体系\n")
        append("========================================\n")
    }

    // ---------------- 实体按键：抓取 + 模拟 ----------------

    /**
     * ★★ v1.4.59：抓**实体按键**的原始输入事件，找出车机按键的真实 keycode。
     *
     * 为什么走这条路：温度通道 `0x10060100` 在本车 HAL 是 **notavailable**（只读不可写），
     * 而用户用实体按键能调温度 → 说明按键走的是**输入子系统**（keyevent），
     * 不是车控 HAL。shell 在 `input` 组里（实测 `groups=...,1004(input),...`），
     * 所以 `getevent -lt` 普通 App 通过本机 adb 就能读到。
     *
     * 抓到 keycode 后，用 [simulateKey] 发 `input keyevent <code>` 就能**完全模拟实体按键**，
     * 这是目前唯一确认可用的调温通路。
     */
    fun grabPhysicalKeys(waitMs: Long = 60_000): String = buildString {
        append("===== 抓实体按键（getevent）=====\n")
        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) {
            append("本机 adbd 不可达。\n")
            return@buildString
        }
        append("adb 端口 $port 已连通\n\n")

        // 确认 shell 在 input 组（否则 getevent 读不到 /dev/input/eventX）
        val id = AdbClient.exec(port, "id", 6_000).text.trim()
        append("[身份] ").append(id.take(200)).append('\n')
        val inInputGroup = id.contains("1004(input)")
        append("[input 组] ").append(if (inInputGroup) "★在组内，getevent 可用" else "⚠不在组内，getevent 可能读不到").append("\n\n")

        // 列出输入设备
        val devs = AdbClient.exec(port, "ls -l /dev/input/", 8_000).text
        append("[输入设备]\n").append(devs.lineSequence().take(25).joinToString("\n") { "  $it" }).append("\n\n")

        append("★ 请在接下来的 ${waitMs / 1000} 秒内，**用车机实体按键**操作：\n")
        append("  · 温度 + 按 2 次\n")
        append("  · 温度 − 按 2 次\n")
        append("  · 内外循环按 1 次\n")
        append("  · 前挡按 1 次\n")
        append("  · A/C 按 1 次\n")
        append("  每按一次间隔 1 秒以上，方便区分。\n\n")

        // getevent -lt 会一直输出，靠 socket 超时截断（adbd 随 stream 关闭而终止进程）
        val cap = AdbClient.exec(port, "getevent -lt", (waitMs + 8_000).toInt())
        val body = cap.text
        if (body.isBlank()) {
            append("[结果] getevent 无输出（${cap.error.take(120)}）\n")
            append("       若提示 Permission denied，说明 shell 不在 input 组，\n")
            append("       这条路走不通，只能改用「模拟 keyevent」盲试。\n")
            return@buildString
        }
        append("[结果] 抓到 ").append(body.length).append(" 字符原始事件：\n\n")
        // 只保留 KEY / 值变化行，压缩掉海量 ABS/REL 噪音
        val keys = body.lineSequence().filter { l ->
            l.contains("KEY_") || l.contains("EV_KEY") || l.contains("BTN_")
        }.toList()
        if (keys.isEmpty()) {
            append("（没有 KEY 事件，可能全是触摸/旋钮。附原始前 60 行）\n")
            body.lineSequence().take(60).forEach { append("  ").append(it.take(200)).append('\n') }
        } else {
            append("-- 按键事件（共 ").append(keys.size).append(" 条）--\n")
            keys.take(300).forEach { append("  ").append(it.take(200)).append('\n') }
        }
        append("\n提示：把 `KEY_xxx` 的 keycode 记下来，填到下面的「模拟实体按键」里。\n")
        append("==================================\n")
    }

    /**
     * ★★ v1.4.59：用 `input keyevent` 模拟实体按键。
     *
     * 这是目前唯一确认能调温的通路（温度 HAL 通道 notavailable，不可写）。
     * keycode 用 [grabPhysicalKeys] 抓到的真实值；没抓到就先盲试候选表。
     *
     * @param keycode Android keycode，如 24=VOLUME_UP、25=VOLUME_DOWN
     * @param times   连发次数
     */
    fun simulateKey(keycode: Int, times: Int = 1): String = buildString {
        var port = -1
        for (p in ADB_PORTS) {
            if (AdbClient.exec(p, "id", 4_000).ok) { port = p; break }
        }
        if (port < 0) return "本机 adbd 不可达"
        val sb = StringBuilder()
        for (i in 0 until times) {
            val r = AdbClient.exec(port, "input keyevent $keycode", 6_000)
            sb.append("  input keyevent $keycode → ")
            sb.append(if (r.ok && r.text.isNotBlank() && !r.text.contains("Error")) "已发送" else "异常：${r.error.take(60)}")
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * ★★ v1.4.59：盲试候选 keycode，找出哪个能调温度。
     *
     * 边试边读温度回调（[com.car.dockbar.vehicle.VehicleController] 的 watcher），
     * 温度一变就说明找对了。候选表覆盖 AOSP 常用键 + 车机常见自定义键。
     */
    val TEMP_KEY_CANDIDATES = listOf(
        24 to "VOLUME_UP", 25 to "VOLUME_DOWN",
        87 to "MEDIA_NEXT", 88 to "MEDIA_PREVIOUS",
        19 to "DPAD_UP", 20 to "DPAD_DOWN",
        81 to "PLUS", 82 to "MINUS",
        61 to "TAB", 62 to "SPACE",
        79 to "HEADSETHOOK",
        90 to "MOVE_HOME", 91 to "MOVE_END",
        120 to "SOFT_RIGHT", 121 to "SOFT_LEFT",
        // 车机常见自定义区（部分国产车机 HVAC 键落在这里）
        219 to "CUSTOM_219", 220 to "CUSTOM_220", 221 to "CUSTOM_221", 222 to "CUSTOM_222",
        223 to "CUSTOM_223", 224 to "CUSTOM_224", 225 to "CUSTOM_225", 226 to "CUSTOM_226",
        227 to "CUSTOM_227", 228 to "CUSTOM_228", 229 to "CUSTOM_229", 230 to "CUSTOM_230",
        231 to "CUSTOM_231", 232 to "CUSTOM_232", 233 to "CUSTOM_233", 234 to "CUSTOM_234",
        235 to "CUSTOM_235", 236 to "CUSTOM_236"
    )

    /**
     * ★★ v1.4.62：**已废弃，请勿调用**。
     *
     * ⚠️ 2026-10-20 实测教训：`input keyevent` 是**直接注入到当前焦点窗口**的。
     *    盲试候选 keycode 等于往车机里乱灌按键 —— 用户只点了一下「试发温度+」，
     *    车机直接黑屏（弹出一张黑色图片，疑似某个按键触发了全屏遮罩/截屏）。
     *    **盲试 = 破坏性操作，绝对禁止。**
     *
     * 唯一安全的用法：keycode 必须是**用户明确提供的**（从 [grabPhysicalKeys] 抓到，
     * 或用户自己知道），且**只发用户指定的那一个**。
     * 找 keycode 只能靠 [grabPhysicalKeys] 被动抓取，不能主动盲试。
     */
    @Deprecated(
        "盲试 keyevent 会把按键注入焦点窗口，可能打断车机当前界面（实测导致黑屏）。" +
            "只能用 grabPhysicalKeys 被动抓取，或发送用户明确指定的 keycode。",
        ReplaceWith("grabPhysicalKeys()")
    )
    fun autoFindTempKey(readTemp: () -> Float?): String =
        "已禁用：盲试 keyevent 会把按键注入当前焦点窗口，可能打断车机界面。" +
            "请改用「抓实体按键」被动抓取真实 keycode。"

    // ---------------- adb 协议 ----------------

    private fun cnxnPacket(payload: String): ByteArray {
        val data = payload.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(data) }.value
        return ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(A_CNXN)
            putInt(0x01000000)                 // protocol version
            putInt(256)                        // maxdata
            putInt(data.size)
            putInt(crc.toInt())
            putInt(A_CNXN xor -1)              // magic = command ^ 0xffffffff
            put(data)
        }.array()
    }
}
