package com.car.dockbar.hvac

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Sec

/**
 * 空调 App（com.geely.hvac / oneOS_Hvac.apk）外部接口桥接。
 *
 * 反编译 oneOS_Hvac.apk（versionName 1.0.20250804L(65)，compileSdk 28）得到：
 *
 * 1) 主面板 Activity：com.geely.hvac.activity.GlyMainActivity（exported=true）
 *    深链 extra：Intent.getIntExtra("enter_hvac_page", -1) 选页
 *        0 = 主空调页（风速 / 温度 / 风向，风向在此页）
 *        1 = 座椅页（座椅加热 / 通风 / 按摩，主驾通风在此页）
 *        2 = 香氛页
 *
 * 2) ContentProvider：content://com.geely.hvac/hvac/query（exported=true，无 permission）
 *    调用方式 query(uri, projection, ...) 且用 projection[0] 作为"指令"：
 *        ["open"]         → 打开空调主面板（车机 launcher / 负一屏 widget 用的官方外部接口）
 *        ["getVisibility"]→ 返回 Cursor，首列 long：1=面板可见 0=不可见
 *        ["close"]        → 仅触发应用内关闭回调，对外部无意义
 *    insert / update / delete 均为空实现。
 *
 * 说明：该 App 不对外暴露"设置风速 / 温度 / 风向"的广播或接口，这类底层控制走系统
 * IHvac 服务（需平台签名），dock 已通过 ECarX adaptapi 反射走同一条系统通道。
 * 这里只负责"打开面板 / 跳到指定子页"，正好补足 dock 的跳转能力。
 */
object HvacApp {

    // ★ v3.13.6：车机包名/Activity 名**加密存储**，反编译看不到明文接口。
    val PACKAGE = Sec.dec("OXiuAPwBXZ00iXqTF+g=", "hvac_pkg")
    val MAIN_ACTIVITY = Sec.dec("OXiuAPwBXZ00iXqTF+gtuDljqljyEEHfCstrqBfibZg5Y6pY8hBB", "hvac_cls")
    const val EXTRA_PAGE = "enter_hvac_page"
    val AUTHORITY = Sec.dec("OXiuAPwBXZ00iXqTF+g=", "hvac_auth")
    const val PATH_QUERY = "hvac/query"

    const val PAGE_MAIN = 0
    const val PAGE_SEAT = 1
    const val PAGE_FRAGRANCE = 2

    private val uri: Uri
        get() = Uri.parse("content://$AUTHORITY/$PATH_QUERY")

    /**
     * 通过 ContentProvider 官方接口打开空调主面板（与车机 launcher / widget 同款）。
     * 失败（App 未安装 / 无权限）返回 false。
     */
    fun openViaProvider(ctx: Context): Boolean = runCatching {
        // projection[0]="open" 即指令；该 App 的 provider 会据此 startActivity(GlyMainActivity)
        //
        // ★ 修 bug：原实现 `query(...)?.close(); true` —— 无论 query 返回 null 还是抛异常
        //   （targetSdk 34 未声明 <queries> 时，对其他包的 provider 会直接失败），
        //   「?.close()」都会跳过然后照样返回 true → openPanel 判定成功、直接 return，
        //   永远不走 openActivity 兜底 → 表现就是「点了没反应、面板打不开」。
        //   必须校验 Cursor 非空才算真的调通了。
        val c = ctx.contentResolver.query(uri, arrayOf("open"), null, null, null)
        val ok = c != null
        c?.close()
        ok
    }.onFailure {
        Logx.d("HvacApp.openViaProvider 失败：${it.javaClass.simpleName} ${it.message}")
    }.getOrElse { false }

    /**
     * 直接启动 Activity 并带 enter_hvac_page 深链到指定子页。失败返回 false。
     */
    fun openActivity(ctx: Context, page: Int = PAGE_MAIN): Boolean = runCatching {
        val intent = Intent().apply {
            setClassName(PACKAGE, MAIN_ACTIVITY)
            putExtra(EXTRA_PAGE, page)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.onFailure {
        Logx.d("HvacApp.openActivity 失败：${it.javaClass.simpleName} ${it.message}")
    }.getOrElse { false }

    /**
     * 打开空调面板（可指定子页）。
     * 主页优先走 ContentProvider 官方接口（与车机 launcher 一致）；
     * 指定子页或 Provider 不可用时，退回直接启动 Activity 带深链 extra。
     */
    fun openPanel(ctx: Context, page: Int = PAGE_MAIN): Boolean {
        // ★ 主路径改为「显式启动 Activity」：显式 ComponentName 不受 Android 11+ 包可见性
        //   限制影响，是第三方 App 能拉起车机空调面板最可靠的一条路。
        //   ContentProvider 仅供 getVisibility 使用（只读，失败无所谓）。
        if (openActivity(ctx, page)) {
            Logx.d("HvacApp 已打开空调面板（子页=$page）")
            return true
        }
        // Activity 启动失败才试 Provider 指令（车机 launcher 的同款接口）
        if (openViaProvider(ctx)) {
            Logx.d("HvacApp 已通过 Provider 打开空调面板")
            return true
        }
        Logx.e("HvacApp 打开空调面板失败：两条路都不通（App 未安装 / 包名或 Activity 名不对 / 被包可见性拦截）")
        return false
    }

    /**
     * 查询空调面板当前是否可见（best-effort）。
     * 返回 null 表示查询失败（多为 HVAC App 进程未存活），true/false 表示面板可见状态。
     */
    fun isPanelVisible(ctx: Context): Boolean? = runCatching {
        ctx.contentResolver.query(uri, arrayOf("getVisibility"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) == 1L else null
        }
    }.getOrNull()
}
