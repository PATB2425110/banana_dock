package com.car.dockbar.util

import android.content.SharedPreferences

/** 按需执行 v2/v3/v4 升级；历史快照仅用于迁移，不得作为当前默认配置。 */
internal object DockBlacklistMigration {
    private const val V2 = "dock_app_list_v2"
    private const val V3 = "dock_app_list_v3"
    private const val V4 = "dock_app_list_v4"
    private const val LEGACY_SHOW = "dock_always_show_apps"
    private const val LEGACY_HIDE = "dock_custom_hide_apps"

    // 历史快照的包名、标签和顺序均属于升级数据，必须保持不变。
    private val LEGACY_DEFAULT_DOCK_HIDE: List<Pair<String, String>> = listOf(
        "com.ebanma.tinyapp" to "斑马小程序",
        "com.geely.dimmenu" to "仪表盘",
        "com.geely.hvac" to "空调",
        "com.geely.oneosphone" to "电话",
        "com.geely.energymanagement" to "能耗管理",
        "com.geely.settings" to "系统设置",
        "com.geely.weather" to "天气",
        "com.geely.calendar" to "日历",
        "com.geely.theme" to "主题",
        "com.geely.membercenter" to "个人中心",
        "com.baidu.naviauto" to "百度地图（可用）",
        "com.autonavi.amapauto" to "高德地图",
        "com.tencent.wecarflow" to "爱趣听",
        "com.geely.camera" to "摄像头（可用）",
        "com.geely.dvr" to "行车记录仪",
        "com.geely.gallery" to "相册",
        "com.geely.smarthome" to "智能家居",
        "com.geely.scene.mode" to "情景模式",
        "com.geely.smartpilot" to "智能驾驶辅助",
        "com.geely.appstore" to "应用商店",
        "com.baidu.dueros.assistant" to "语音助手",
        "com.geely.manualapp" to "用户手册",
        "com.geely.provision" to "系统配置",
        "com.geely.payment" to "支付功能",
        "com.baidu.che.codriver" to "百度CoDriver",
        "com.netease.cloudmusic.iot" to "网易云音乐车机版",
        "com.kugou.android.auto" to "酷狗音乐车机版",
        "com.arcvideo.car.video" to "视频播放器",
        "com.tencent.multimedia" to "腾讯多媒体服务",
        "com.tencent.qqlive.audiobox" to "QQ音乐车机版",
        "com.ximalaya.ting.android.car" to "喜马拉雅车机版",
        "com.tencent.wecar" to "腾讯车载服务",
        "com.bilibili.bilithings" to "B站车机版",
        "com.geely.notificationdemo" to "通知测试工具",
        "com.baidu.netdisk.car" to "百度网盘车机版",
        "com.thunder.carplay" to "雷石KTV",
        "com.autoegg" to "今日电台",
        // ★ v1.4.75：补上真车上实测会用到的全屏应用（用户反馈这些没被隐藏）
        "com.quark.browser" to "夸克浏览器",
        "com.quark" to "夸克网盘",
        "com.tencent.mm" to "微信",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.smile.gifmaker" to "快手",
        "com.xingin.xhs" to "小红书",
        "com.taobao.taobao" to "淘宝",
        "com.jingdong.app.mall" to "京东",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mtt" to "QQ浏览器",
        "com.android.browser" to "系统浏览器",
        "com.eshare.miui.player" to "小米音乐",
        "com.qiyi.video" to "爱奇艺",
        "com.qiyi.video.lite" to "爱奇艺(轻)",
        "com.starschina" to "梨视频",
        "cn.xuexi.android.plus" to "学习强国",
        "com.tencent.wemeet.app" to "腾讯会议",
        "com.tencent.docs" to "腾讯文档",
        "com.alibaba.android.rimet" to "钉钉",
        "com.cmbchina.cmbmb" to "招商银行",
        "com.icbc" to "工商银行",
        "com.chinamobile.contacts.im" to "移动和通讯"
    )

    private val LEGACY_DEFAULT_DOCK_SHOW: List<Pair<String, String>> = listOf(
        "com.ebanma.tinyapp" to "斑马小程序",
        "com.geely.dimmenu" to "仪表盘",
        "com.geely.hvac" to "空调",
        "com.geely.oneosphone" to "电话",
        "com.geely.energymanagement" to "能耗管理",
        "com.geely.settings" to "系统设置",
        "com.geely.weather" to "天气",
        "com.geely.calendar" to "日历",
        "com.geely.theme" to "主题",
        "com.geely.membercenter" to "个人中心",
        "com.baidu.naviauto" to "百度地图（可用）",
        "com.autonavi.amapauto" to "高德地图",
        "com.tencent.wecarflow" to "爱趣听",
        "com.geely.camera" to "摄像头（可用）",
        "com.geely.dvr" to "行车记录仪",
        "com.geely.gallery" to "相册",
        "com.geely.smarthome" to "智能家居",
        "com.geely.scene.mode" to "情景模式",
        "com.geely.smartpilot" to "智能驾驶辅助",
        "com.geely.appstore" to "应用商店",
        "com.baidu.dueros.assistant" to "语音助手",
        "com.geely.manualapp" to "用户手册",
        "com.geely.provision" to "系统配置",
        "com.geely.payment" to "支付功能",
        "com.baidu.che.codriver" to "百度CoDriver",
        "com.netease.cloudmusic.iot" to "网易云音乐车机版",
        "com.kugou.android.auto" to "酷狗音乐车机版",
        "com.arcvideo.car.video" to "视频播放器",
        "com.tencent.multimedia" to "腾讯多媒体服务",
        "com.tencent.qqlive.audiobox" to "QQ音乐车机版"
    )

    private val legacyHidePackages = LEGACY_DEFAULT_DOCK_HIDE.map { it.first }.toSet()
    private val legacyShowPackages = LEGACY_DEFAULT_DOCK_SHOW.map { it.first }.toSet()

    fun ensureMigrated(prefs: SharedPreferences) = synchronized(prefs) {
        if (prefs.getBoolean(V4, false)) return@synchronized
        val hasLegacyState = prefs.contains(LEGACY_SHOW) || prefs.contains(LEGACY_HIDE) ||
            prefs.getBoolean(V2, false) || prefs.getBoolean(V3, false)
        val blacklist = if (hasLegacyState) {
            migrateV2(prefs)
            migrateV3(prefs)
            DockBlacklistRules.migrate(
                legacyHidePackages,
                parse(prefs.getString(LEGACY_SHOW, "")),
                parse(prefs.getString(LEGACY_HIDE, ""))
            )
        } else {
            DockBlacklistDefaults.packages
        }
        // 黑名单与迁移标记一起写入，后续启动不再重新计算用户的选择。
        prefs.edit().putString(DOCK_HIDE_APPS_KEY, blacklist.joinToString(","))
            .putBoolean(V4, true).apply()
    }

    /** v1 曾用此键保存隐藏名单，后续版本复用同一键保存常驻显示名单。 */
    private fun migrateV2(prefs: SharedPreferences) {
        if (prefs.getBoolean(V2, false)) return
        val oldHidden = if (prefs.contains(LEGACY_SHOW)) {
            parse(prefs.getString(LEGACY_SHOW, ""))
        } else {
            legacyHidePackages
        }
        val always = legacyHidePackages - oldHidden
        val hide = oldHidden - legacyHidePackages
        prefs.edit().putBoolean(V2, true)
            .putString(LEGACY_SHOW, always.joinToString(","))
            .putString(LEGACY_HIDE, hide.joinToString(","))
            .apply()
    }

    /** 仅对尚未完成 v3 迁移的旧安装执行一次历史默认显示名单播种。 */
    private fun migrateV3(prefs: SharedPreferences) {
        if (prefs.getBoolean(V3, false)) return
        val always = parse(prefs.getString(LEGACY_SHOW, "")).toMutableSet()
        val hide = parse(prefs.getString(LEGACY_HIDE, "")).toMutableSet()
        always.addAll(legacyShowPackages)
        hide.removeAll(legacyShowPackages)
        prefs.edit().putBoolean(V3, true)
            .putString(LEGACY_SHOW, always.joinToString(","))
            .putString(LEGACY_HIDE, hide.joinToString(","))
            .apply()
    }

    private fun parse(raw: String?): Set<String> =
        raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
