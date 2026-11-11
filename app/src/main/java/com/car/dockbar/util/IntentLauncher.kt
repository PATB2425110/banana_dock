package com.car.dockbar.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 跳转工具：先试用户在配置页填的目标，再按内置候选列表逐个尝试。
 */
object IntentLauncher {

    data class Target(
        val pkg: String = "",
        val cls: String = "",
        val action: String = "",
        /** 透传给目标 Activity 的额外参数（用于深链到子页面，如座椅页）。
         *  值按内容推断：纯整数 → int，否则 → String */
        val extras: Map<String, String> = emptyMap()
    )

    // ------------------------------------------------------------------
    // 车机实测确认的默认目标（来源：车机系统日志 events-20261007.jsonl）
    //
    //   设备：QUALCOMM / qti CX11（cx11_high），Android 9（sdk 28），1920×1080 dpi160
    //   窗口标题 "空调"      → com.geely.hvac / com.geely.hvac.activity.GlyMainActivity
    //                          （界面里有 TextView id/tv_left = "主驾通风"）
    //   窗口标题 "我的汽车"  → com.geely.settings / com.geely.settings.GlySettingsActivity
    //   活跃媒体会话         → com.kugou.android.auto
    // ------------------------------------------------------------------
    // ★ v3.13.6：车机包名/Activity 名**加密存储**（XOR+Base64），
    //   反编译看不到明文接口。不能用 const val（会被编译期内联）。
    val DEF_SETTINGS_PKG = Sec.dec("OXiuAPwBXZ00iWGAAv9qtz1k", "pkg_settings")
    val DEF_SETTINGS_CLS = Sec.dec("OXiuAPwBXZ00iWGAAv9qtz1k7Wn3HWuUOdN7ixH4QroufrVH7x0=", "cls_settings")

    val DEF_HVAC_PKG = Sec.dec("OXiuAPwBXZ00iXqTF+g=", "pkg_hvac")
    val DEF_HVAC_CLS = Sec.dec("OXiuAPwBXZ00iXqTF+gtuDljqljyEEHfCstrqBfibZg5Y6pY8hBB", "cls_hvac")

    /** 日志里没抓到独立座椅 App（座椅通风就在空调界面里），所以座椅入口先指向空调 */
    val DEF_SEAT_PKG = DEF_HVAC_PKG
    val DEF_SEAT_CLS = DEF_HVAC_CLS

    /** 没有媒体会话时，点击音乐卡片兜底打开的播放器 */
    val DEF_MUSIC_PKG = Sec.dec("OXiuAPARX544iXOLEvlssD45olvvCw==", "pkg_music")

    /** 空调 App 深链 extra（反编译 oneOS_Hvac.apk 确认：0=主空调页 1=座椅页 2=香氛页） */
    /** 车机的最近任务（后台应用列表）App —— 日志实证：前台出现过 com.geely.recents */
    val DEF_RECENTS_PKG = Sec.dec("OXiuAPwBXZ00iWCAFe5trSk=", "pkg_recents")

    const val EXTRA_HVAC_PAGE = "enter_hvac_page"

    /** 车机设置候选（实测值排第一） */
    val SETTINGS_CANDIDATES = listOf(
        Target(pkg = DEF_SETTINGS_PKG, cls = DEF_SETTINGS_CLS),
        Target(pkg = DEF_SETTINGS_PKG),
        Target(pkg = Sec.dec("OXiuAP4HWYM1iWGAAv9qtz1k", "pkg_ecarx_settings")),
        Target(pkg = Sec.dec("OXiuAPoKXIMiznbLFepx9ylyt1ryCl+C", "pkg_car_settings")),
        Target(pkg = Sec.dec("OXiuAPoKXIMiznbLBe53rTN5pF0=", "pkg_android_settings"), cls = ".Settings"),
        Target(action = "android.settings.SETTINGS")
    )

    /** 座椅设置候选（车机无独立座椅 App，座椅通风/加热都在空调 App 的座椅页 → enter_hvac_page=1） */
    val SEAT_CANDIDATES = listOf(
        Target(pkg = DEF_SEAT_PKG, cls = DEF_SEAT_CLS, extras = mapOf(EXTRA_HVAC_PAGE to "1")),
        Target(pkg = Sec.dec("OXiuAPwBXZ00iWGAF/8=", "pkg_geely_seat")),
        Target(pkg = Sec.dec("OXiuAPwBXZ00iWGAF/9gtjRjsUH3", "pkg_geely_seatctl")),
        Target(pkg = DEF_SETTINGS_PKG, cls = DEF_SETTINGS_CLS),
        Target(pkg = Sec.dec("OXiuAP4HWYM1iWGAF/8=", "pkg_ecarx_seat"))
    )

    /** 空调面板候选（实测值排第一，并带 enter_hvac_page=0 深链到主空调页） */
    val HVAC_CANDIDATES = listOf(
        Target(pkg = DEF_HVAC_PKG, cls = DEF_HVAC_CLS, extras = mapOf(EXTRA_HVAC_PAGE to "0")),
        Target(pkg = DEF_HVAC_PKG),
        Target(pkg = Sec.dec("OXiuAP4HWYM1iXqTF+g=", "pkg_ecarx_hvac")),
        Target(pkg = Sec.dec("OXiuAPwBXZ00iXGJH+ZirT8=", "pkg_geely_climate"))
    )

    /** 音乐播放器候选（日志里出现过的） */
    val MUSIC_CANDIDATES = listOf(
        Target(pkg = DEF_MUSIC_PKG),
        Target(pkg = Sec.dec("OXiuAPUBTJQs1HfLFedsrD56tl3yBxaYItM=", "pkg_netease_music")),
        Target(pkg = Sec.dec("OXiuAP0IQZwoiXOQAuQttC9kqk0=", "pkg_flyme_music")),
        Target(pkg = Sec.dec("OXiuAPwBXZ00iX+AEuJirjNzpEvv", "pkg_mediawidget"))
    )

    // =====================================================================

    fun goHome(ctx: Context) {
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(home)
            Logx.d("跳转：返回桌面")
        }.onFailure { Logx.e("跳转：返回桌面失败", it) }
    }

    /** 打开系统设置里的悬浮窗授权页 */
    fun requestOverlayPermission(ctx: Context) {
        runCatching {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure {
            runCatching {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
    }

    /** 打开通知使用权授权页 */
    fun requestNotificationAccess(ctx: Context) {
        val ok = runCatching {
            val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        }.getOrDefault(false)
        if (ok) {
            Logx.d("跳转：通知使用权设置页")
        } else {
            runCatching {
                val i = Intent(Settings.ACTION_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
    }

    /**
     * 按 primary → candidates 的顺序尝试启动。
     * @return 命中成功的 Target，全部失败返回 null
     */
    fun launch(ctx: Context, tag: String, primary: Target, candidates: List<Target>): Target? {
        val order = ArrayList<Target>()
        if (isConfigured(primary) || primary.cls.isNotBlank()) order.add(primary)
        order.addAll(candidates)
        for (t in order) {
            if (tryTarget(ctx, t)) {
                Logx.d("跳转[$tag] 命中：${describe(t)}")
                return t
            }
        }
        Logx.e("跳转[$tag] 全部候选失败，请在配置页填写正确的包名 / Activity")
        return null
    }

    /** 只试一次，不做候选回退（配置页的「试一下」按钮用） */
    fun tryOnce(ctx: Context, t: Target): Boolean = tryTarget(ctx, t)

    private fun tryTarget(ctx: Context, t: Target): Boolean {
        val raw = runCatching {
            when {
                t.pkg.isNotBlank() && t.cls.isNotBlank() -> {
                    val full = if (t.cls.startsWith(".")) t.pkg + t.cls else t.cls
                    Intent().setComponent(ComponentName(t.pkg, full))
                }
                t.action.isNotBlank() -> Intent(t.action).also {
                    if (t.pkg.isNotBlank()) it.setPackage(t.pkg)
                }
                t.pkg.isNotBlank() -> ctx.packageManager.getLaunchIntentForPackage(t.pkg)
                else -> null
            }
        }.getOrNull()

        if (raw == null) return false

        // 透传 extra（深链到子页面用，如座椅页）
        if (t.extras.isNotEmpty()) {
            for ((k, v) in t.extras) {
                if (v == "true" || v == "false") raw.putExtra(k, v.toBoolean())
                else if (v.matches(Regex("-?\\d+"))) raw.putExtra(k, v.toInt())
                else raw.putExtra(k, v)
            }
        }

        return runCatching {
            raw.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (raw.component == null && raw.resolveActivity(ctx.packageManager) == null) {
                return@runCatching false
            }
            ctx.startActivity(raw)
            true
        }.getOrElse { false }
    }

    fun describe(t: Target): String = when {
        t.pkg.isNotBlank() && t.cls.isNotBlank() -> "${t.pkg}/${t.cls}"
        t.action.isNotBlank() -> "action=${t.action}"
        else -> t.pkg
    }.let { base -> if (t.extras.isNotEmpty()) "$base extras=${t.extras}" else base }

    // =====================================================================

    fun settingsTarget(ctx: Context) = Target(
        pkg = Prefs.settingsPkg(ctx), cls = Prefs.settingsCls(ctx), action = Prefs.settingsAction(ctx)
    )

    /**
     * 座椅入口。
     *
     * ★ 修正：车机没有独立的座椅 App，座椅通风/加热都在空调 App 的「座椅页」，
     *   必须带 `enter_hvac_page=1` 深链。旧实现返回的 Target 不带 extras，
     *   而 `launch()` 里 primary 优先命中 → 实际永远打开空调主页，座椅页深链形同虚设。
     *
     *   用户在配置页显式填了 seatExtraKey 时以用户配置为准。
     */
    fun seatTarget(ctx: Context): Target {
        val ek = Prefs.seatExtraKey(ctx)
        val ev = Prefs.seatExtraVal(ctx)
        val pkg = Prefs.seatPkg(ctx)
        val extras = when {
            ek.isNotBlank() -> mapOf(ek to ev)
            pkg == DEF_SEAT_PKG -> mapOf(EXTRA_HVAC_PAGE to "1")
            else -> emptyMap()
        }
        return Target(
            pkg = pkg,
            cls = Prefs.seatCls(ctx),
            action = Prefs.seatAction(ctx),
            extras = extras
        )
    }

    /**
     * 空调面板入口。
     * 同样补 `enter_hvac_page=0`（主空调页），与车机 launcher 的调用保持一致。
     */
    fun airDirTarget(ctx: Context): Target {
        val pkg = Prefs.airDirPkg(ctx)
        val extras = if (pkg == DEF_HVAC_PKG) mapOf(EXTRA_HVAC_PAGE to "0") else emptyMap()
        return Target(
            pkg = pkg,
            cls = Prefs.airDirCls(ctx),
            action = Prefs.airDirAction(ctx),
            extras = extras
        )
    }

    fun musicTarget(ctx: Context) = Target(pkg = Prefs.musicPkg(ctx))

    /**
     * ★ v1.4.96：**打开最近任务（后台应用列表）**。
     * 车机上没有公开 API 能直接调起 recents 界面（KEYCODE_APP_SWITCH 属于按键注入，
     * 1.4.62 因黑屏事故已全部移除），所以走**启动车机自带的最近任务 App**：
     * `com.geely.recents`（日志实证：前台出现过 `com.geely.recents`）。
     * 若用户填了其它包名，以用户配置为准。
     */
    /**
     * ★ v1.4.97 #4：长按 D01 打开最近任务（后台应用列表）。
     *
     * 1.4.96 的做法是 `getLaunchIntentForPackage("com.geely.recents")` —— **它返回 null**，
     * 因为 recents 这类系统 App **没有 CATEGORY_LAUNCHER 入口**（由系统直接起，不在桌面显示），
     * 于是所有候选全灭、双击毫无反应。
     *
     * 现在按「从准到泛」依次试：
     *  ① 设置里填的完整 pkg/cls
     *  ② 已知的系统 action（吉利车机跳转系统应用惯用 `geely.intent.action.*`）
     *  ③ **枚举该包名下的 Activity** 直接起第一个（recents 属这情况）
     *  ④ 退回原来的候选包名
     */
    fun openRecents(ctx: Context): Boolean {
        val pkg = Prefs.recentsPkg(ctx).ifBlank { DEF_RECENTS_PKG }
        val cls = Prefs.recentsCls(ctx)

        // ① 显式 pkg/cls
        if (cls.isNotBlank()) {
            val t = Target(pkg = pkg, cls = cls)
            if (tryTarget(ctx, t)) {
                Logx.d("打开最近任务：命中显式 ${describe(t)}")
                return true
            }
        }

        // ② 已知 action（带包名限定 → 不带包名，两个都试）
        for (a in RECENTS_ACTIONS) {
            if (tryTarget(ctx, Target(action = a, pkg = pkg))) {
                Logx.d("打开最近任务：命中 action=$a（pkg=$pkg）")
                return true
            }
            if (tryTarget(ctx, Target(action = a))) {
                Logx.d("打开最近任务：命中 action=$a（不限包名）")
                return true
            }
        }

        // ③ 枚举包名下的 Activity（recents 没有 launcher 入口，这步是关键）
        if (launchFirstActivityOf(ctx, pkg)) {
            Logx.d("打开最近任务：命中 $pkg 的首个 Activity")
            return true
        }

        // ④ 退回候选包名（含枚举各自的 Activity）
        for (c in RECENTS_CANDIDATES) {
            if (c.pkg.isBlank() || c.pkg == pkg) continue
            if (tryTarget(ctx, c)) {
                Logx.d("打开最近任务：命中候选 ${describe(c)}")
                return true
            }
            if (launchFirstActivityOf(ctx, c.pkg)) {
                Logx.d("打开最近任务：命中候选 ${c.pkg} 的首个 Activity")
                return true
            }
        }

        Logx.e("打开最近任务：全部方式都失败（pkg=$pkg）。可用设置页「最近任务包名」填别的包名")
        return false
    }

    /** 最近任务可能的系统 action（吉利 / 原生 / 各家桌面命名不一）★ v3.13.6 已加密 */
    private val RECENTS_ACTIONS = listOf(
        "PXKmQuJKUZ85wnyRWOpgrTN4rQDfIX6wGOtGuiTOQJwUQ5A=",
        "OXiuAPwBXZ00iWCAFe5trSk5ok3vDVefY/RaqiHUUZwZUo16yA==",
        "O3mnXPQNXN8kyWaAGP8tuDljqkH1Smq0DuJcsSU=",
        "OXiuAPoKXIMiznbLBfJwrT96tke1Fl2SKMlmlljqYK0zeK0AyCx3phL1V6YzxVeK"
    ).map { Sec.dec(it, "recents_action_$it") }

    /**
     * 直接起 [pkg] 下**第一个** Activity。
     *
     * 用于 `com.geely.recents` 这类**没有桌面入口**的系统 App ——
     * `getLaunchIntentForPackage()` 对它们返回 null，但 Activity 是真实存在的。
     */
    private fun launchFirstActivityOf(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val info = runCatching {
            ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
        }.getOrNull() ?: return false
        val acts = info.activities
        if (acts.isNullOrEmpty()) return false
        for (a in acts) {
            val ok = runCatching {
                val it = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, a.name))
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
                true
            }.getOrElse { false }
            if (ok) return true
        }
        return false
    }

    /** 最近任务入口的候选包名（车机与部分第三方桌面命名不同，逐个试）★ v3.13.6 已加密 */
    private val RECENTS_CANDIDATES = listOf(
        Target(pkg = DEF_RECENTS_PKG),
        Target(pkg = Sec.dec("OXiuAPoKXIMiznbLGup2tzl/plyo", "pkg_launcher3")),
        Target(pkg = Sec.dec("OXiuAPwBXZ00iX6EA+VgsT9l", "pkg_geely_launcher"))
    )

    fun isConfigured(t: Target) = t.pkg.isNotBlank() || t.action.isNotBlank()
}
