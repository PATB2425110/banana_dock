package com.car.dockbar

import android.app.Application
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.CrashHandler
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.util.NativeCrashHandler
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.PrivilegeProbe

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // ---- 上传地址自愈：必须排在一切上传动作之前 ----
        // Prefs 里存过的地址优先于 DEFAULT_URL。历史死链（webhook.site / 首个部署域）
        // 会让「测试上传」永远失败——2026-10-19 平板上实测就是这个情况。
        // 启动时发现存的是死链或空值，自动纠正回当前内置地址：
        // 覆盖安装、清数据、换设备，打开就是对的那个。
        LogUploader.migrateEndpoint(this)

        // ---- 崩溃取证：必须在日志轮转之前/同时完成 ----
        //
        // 顺序很重要：
        //  ① 先把上次会话的落盘日志留档（session.log → prev.log）
        //  ② 收集崩溃证据（Java 堆栈 + Native 信号信息 + 上次会话日志）并补传
        //  ③ 再把本次会话的新文件接上 Logx
        //
        // 为什么必须落盘：内存日志会随进程一起消失。
        // Java 崩溃还能被 Thread.setDefaultUncaughtExceptionHandler 抓到；
        // **native 崩溃（SIGSEGV/SIGABRT）不会触发任何 Java 回调**，
        // 只能靠「提前落盘 + 下次启动回捞」这一条路。
        val session = LogUploader.sessionLog(this)
        val prev = LogUploader.prevLog(this)

        var report = ""
        runCatching {
            if (session.exists() && session.length() > 0) {
                session.copyTo(prev, overwrite = true)
            }
            report = LogUploader.reportPreviousSession(this)
            session.writeText("")
        }
        Logx.attachFile(session)
        // 有网就按批把新日志送上去；断网写进 live_queue.txt，恢复后再补。
        LogUploader.startLive(this)

        // ---- 自动降级：连续崩溃就逐级关闭功能，用于远程二分定位 ----
        // 上一次崩溃了且没活过 15 秒 → 连续崩溃 +1；活过 15 秒则清零。
        // 用户什么都不用做，App 会自己退到「能跑」的配置，
        // 每次崩溃都会把当时的等级随日志上传，据此即可定位是哪一层的问题。
        //
        // ⚠ 版本变更（升级 / 重装）时重置：否则旧版本的崩溃计数会把新包直接压到最低档，
        //   用户会觉得「装了新包连车控都没了」。
        val ver = appVersion(this)
        val versionChanged = Prefs.lastVersion(this) != ver
        // This release only reorganizes settings UI: preserve user switches and safety level.
        val preserveUiUpgrade = versionChanged && Prefs.lastVersion(this).isNotBlank() &&
            (ver.startsWith("3.13.105(") || ver.startsWith("3.13.106(") || ver.startsWith("3.13.107(") ||
                ver.startsWith("3.13.108(") || ver.startsWith("3.13.109(") || ver.startsWith("3.13.110(") ||
                ver.startsWith("3.13.111(") || ver.startsWith("3.13.112(") || ver.startsWith("4.0("))
        if (preserveUiUpgrade) Prefs.setLastVersion(this, ver)
        val rawStreak = LogUploader.updateCrashStreak(this, LogUploader.lastReportHadCrash)
        // 升级 / 首次安装用重置后的 0。熔断必须看这个值，不能看重置前的计数，
        // 否则「恢复全功能」写完，下面的熔断又会把温度监听立刻关回去。
        val streak = if (versionChanged && !preserveUiUpgrade) {
            LogUploader.resetCrashStreak(this)
            Prefs.setSafeLevel(this, 0)
            Prefs.setVehicleWriteEnabled(this, true)
            Prefs.setVehicleApiEnabled(this, true)
            Prefs.setVehicleTempWatcherEnabled(this, true)
            Prefs.setLastVersion(this, ver)
            Logx.d("检测到版本变更 → 重置降级阶梯，恢复全功能（含温度事件监听）")
            0
        } else {
            rawStreak
        }
        // ★ 封顶 L3，不再有 L4。
        //   旧版 streak>=5 会升到 L4「连车控都不初始化，只剩纯 UI」——用户在 CX11 上
        //   正是被压到这一档，表现为「空调状态全丢了」。
        //   但车控**读取**是安全通道（读不到只返回 0xff 哨兵，不会崩），
        //   真正的崩溃源是 watcher 回调与写入，这两处已分别由开关和熔断单独控制。
        //   所以降级最多只关「弹窗 / 截屏 / 歌词 / 媒体」，车控状态始终保留。
        val level = when {
            streak >= 4 -> 3
            streak >= 3 -> 2
            streak >= 2 -> 1
            else -> 0
        }
        if (level != Prefs.safeLevel(this) && !versionChanged) {
            Prefs.setSafeLevel(this, level)
        }
        // ★ 事件监听熔断：只要上次会话崩过，就把 watcher 关回去。
        //   真车实锤：普通 App 收到 registerFunctionValueWatcher 的回调会被厂商库 abort
        //   （SIGABRT signal 6），温度只是最容易命中的那个通道，座椅 / A-C / 风向同样会崩。
        //   用户手动打开后若是它引起的崩溃，重启一次就自动回到安全配置，不会陷入死循环。
        if (streak >= 1 && Prefs.vehicleWatcherEnabled(this)) {
            Prefs.setVehicleWatcherEnabled(this, false)
            Logx.w("⚠ 上次会话崩溃过 → 已自动关闭「车控事件监听」" +
                "（普通 App 收到回调会被厂商库 abort，状态改由轮询获取）")
        }
        if (streak >= 1 && Prefs.vehicleTempWatcherEnabled(this)) {
            Prefs.setVehicleTempWatcherEnabled(this, false)
            Logx.w("⚠ 上次会话崩溃过 → 已自动关闭「温度事件监听」实验开关")
        }

        // ★ 连续崩溃 2 次就关车控写入：setFunctionValue 是厂商私有 HAL，传错 value 时
        //   厂商 native 库可能直接 abort（SIGSEGV/SIGABRT，Java 层 runCatching / CrashHandler
        //   都拦不住）。关掉写入只保留读取，保证 App 还能正常用、状态还能显示。
        if (streak >= 2) {
            if (Prefs.vehicleWriteEnabled(this)) {
                Prefs.setVehicleWriteEnabled(this, false)
                Logx.e("⚠ 连续崩溃 $streak 次 → 已自动关闭车控写入（仅保留读取），避免 HAL 写把进程拖崩")
            }
        }

        // ★ 连续崩溃不再因为「开过模糊」退回渐变。模糊不再抓屏。

        // ★★ v1.4.51 二分熔断：关掉 watcher / 写入 / 温度通道之后 CX11 仍在闪退，
        //    说明还有别的 abort 源。这里按「先摘最可疑的、再摘次可疑的」逐级断电，
        //    每次断电都把结论打进日志，远端一看日志就知道崩在哪一层。
        //
        //    （v1.4.93 起「反射截屏采样」已整体删除，安全模式不再含这一档）
        //    第 2 级（streak>=3）：关「整个车控通道」——连 Car.create() 都不调，
        //      Dock 退化成纯 UI。若这一档还崩，则崩溃与厂商接口无关。
        if (streak >= 3 && Prefs.vehicleApiEnabled(this)) {
            Prefs.setVehicleApiEnabled(this, false)
            Logx.e("⚠⚠ 连续崩溃 $streak 次 → 已自动关闭**整个车控通道**（不调用任何厂商接口），" +
                "Dock 退化为纯 UI。若这一档仍闪退，说明崩溃与车控无关；" +
                "若不闪退，则崩溃源确定在厂商车控库（普通 App 调用即 abort）。")
        }

        // Native 崩溃信号处理器（加载失败不影响 Java 侧功能）
        NativeCrashHandler.install(this, LogUploader.nativeCrashFile(this).absolutePath)

        CrashHandler.install(this)

        Logx.d("Application 启动（SDK_INT=${android.os.Build.VERSION.SDK_INT}）")
        runCatching { AccessibilityKeep.install(this) }
            .onFailure { Logx.e("写回无障碍失败", it) }
        Logx.d("启动补传：$report")
        Logx.d(
            "日志上传：${if (Prefs.logUploadEnabled(this)) "已启用 → ${LogUploader.endpoint(this)}" else "未启用"}" +
                    "　设备=${LogUploader.deviceId(this)}" +
                    "　native捕获=${NativeCrashHandler.isAvailable}"
        )
        Logx.d(
            "安全等级=${Prefs.safeLevel(this)}（连续崩溃 $streak 次）" +
                    (if (Prefs.safeLevel(this) > 0) "　→ ${safeLevelDesc(Prefs.safeLevel(this))}" else "")
        )
        Logx.d(
            "二分开关：车控通道=${if (Prefs.vehicleApiEnabled(this)) "开" else "★关（不碰厂商 API）"}" +
                    "　事件监听=${if (Prefs.vehicleWatcherEnabled(this)) "开" else "关"}" +
                    "　温度监听=${if (Prefs.vehicleTempWatcherEnabled(this)) "开" else "关"}" +
                    "　车控写入=${if (Prefs.vehicleWriteEnabled(this)) "开" else "关"}"
        )

        // 提权环境探测：判断本车能不能拿到 adb / root 权限（用户连不了电脑，只能车机本地探）。
        // 走后台线程 —— 有 socket 与 exec，绝不能在主线程。结果随日志上传，远端据此选型。
        Thread {
            runCatching {
                Logx.d(PrivilegeProbe.report(this))
            }.onFailure { Logx.e("提权环境探测失败", it) }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /** 安全等级含义（也写进上传的日志，方便远程判读） */
        fun safeLevelDesc(level: Int): String = when (level) {
            1 -> "已关闭「空调状态弹窗」"
            2 -> "再关闭「歌词悬浮窗」"
            3 -> "再关闭「媒体接口（MediaSession / 通知使用权）」（车控状态保留）"
            else -> "全功能"
        }

        private fun appVersion(ctx: android.content.Context): String = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName}($code)"
        }.getOrDefault("unknown")
    }
}
