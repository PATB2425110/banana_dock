package com.car.dockbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.car.dockbar.ui.DockOverlayService
import com.car.dockbar.ui.LyricOverlayService
import com.car.dockbar.util.AccessibilityKeep
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/** 开机自启：如果用户开启过 Dock / 歌词悬浮窗，就把它拉起来 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON" ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED ||
            a == Intent.ACTION_USER_PRESENT
        ) {
            runCatching { AccessibilityKeep.restore(context) }
                .onFailure { Logx.e("BootReceiver 写回无障碍失败", it) }
            if (Prefs.overlayEnabled(context)) {
                Logx.d("BootReceiver: $a → 启动 Dock 服务")
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, DockOverlayService::class.java)
                            .setAction(DockOverlayService.ACTION_START)
                    )
                }.onFailure { Logx.e("BootReceiver 启动 Dock 失败", it) }
            }
            // ★ 修正：此前只判断 lyricEnabled，导致配置页的「开机自启歌词悬浮窗」
            //   （Prefs.lyricBoot）开关是死配置、点了没反应。
            //   语义：上次开着歌词窗（lyricEnabled）或用户显式要求开机自启（lyricBoot）→ 拉起。
            //   安全等级 >= 2 时不拉起（连续崩溃后的自动降级）。
            val lyricWanted = (Prefs.lyricEnabled(context) || Prefs.lyricBoot(context)) &&
                    Prefs.safeLevel(context) < 2
            if (lyricWanted) {
                Logx.d("BootReceiver: $a → 启动歌词悬浮窗（enabled=${Prefs.lyricEnabled(context)} boot=${Prefs.lyricBoot(context)}）")
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, LyricOverlayService::class.java)
                    )
                }.onFailure { Logx.e("BootReceiver 启动歌词悬浮窗失败", it) }
            }
        }
    }
}
