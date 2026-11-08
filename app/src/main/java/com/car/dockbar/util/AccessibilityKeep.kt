package com.car.dockbar.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台检测的开关在 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES。
 * 车机有的重启不会把第三方无障碍写回这条名单。应用只读的话，页面就会一直红。
 *
 * 读到过一次「已开启」之后记住。之后发现不在名单里，用 WRITE_SECURE_SETTINGS 写回去。
 * 没开通过的不主动打开。写接口失败时再试本机 adb 的 settings put。
 */
object AccessibilityKeep {

    private const val SERVICE = "com.car.dockbar.ui.ForegroundAppService"
    private val adbBusy = AtomicBoolean(false)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        restore(app)
        Handler(Looper.getMainLooper()).postDelayed({ restore(app) }, 20_000L)
    }

    fun isEnabled(ctx: Context): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        return raw.split(':').any { matches(it, ctx.packageName) }
    }

    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        if (isEnabled(app)) {
            if (!Prefs.accKeep(app)) {
                Prefs.setAccKeep(app, true)
                Logx.d("前台检测已授权，已记住；之后被系统关掉会写回")
            }
            return
        }
        if (!Prefs.accKeep(app)) return

        val wrote = writeSecure(app)
        if (isEnabled(app)) {
            Logx.d("无障碍被系统关闭，已写回名单")
            return
        }
        if (!wrote) {
            Logx.d("无障碍被系统关闭，WRITE_SECURE_SETTINGS 没有写进去")
        }
        if (!adbBusy.compareAndSet(false, true)) return
        Thread {
            try {
                if (isEnabled(app)) return@Thread
                val ok = writeViaAdb(app)
                Logx.d(
                    if (ok && isEnabled(app)) "无障碍被系统关闭，已用本机 adb 写回"
                    else "无障碍被系统关闭，写回后名单里仍然没有"
                )
            } finally {
                adbBusy.set(false)
            }
        }.apply {
            isDaemon = true
            name = "acc-keep"
        }.start()
    }

    private fun writeSecure(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val merged = merge(cur, ctx.packageName)
            val listOk = Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged
            )
            val masterOk = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            listOk && masterOk
        }.getOrDefault(false)
    }

    private fun writeViaAdb(ctx: Context): Boolean {
        val cur = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        val merged = merge(cur, ctx.packageName)
        val quoted = "'" + merged.replace("'", "") + "'"
        for (port in listOf(5555, 5556, 5557)) {
            val put = AdbClient.exec(
                port,
                "settings put secure enabled_accessibility_services $quoted",
                8_000
            )
            if (!put.ok) continue
            AdbClient.exec(port, "settings put secure accessibility_enabled 1", 8_000)
            return true
        }
        return false
    }

    private fun merge(current: String, pkg: String): String {
        val parts = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.none { matches(it, pkg) }) parts.add("$pkg/$SERVICE")
        return parts.joinToString(":")
    }

    private fun matches(raw: String, pkg: String): Boolean {
        val c = ComponentName.unflattenFromString(raw.trim()) ?: return false
        return c.packageName == pkg && c.className == SERVICE
    }
}
