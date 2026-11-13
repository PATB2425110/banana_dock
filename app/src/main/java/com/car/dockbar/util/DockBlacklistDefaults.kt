package com.car.dockbar.util

/** 新安装使用的当前默认黑名单；历史迁移数据由 DockBlacklistMigration 单独管理。 */
internal object DockBlacklistDefaults {
    val apps: List<Pair<String, String>> = listOf(
        "com.ximalaya.ting.android.car" to "喜马拉雅车机版",
        "com.tencent.wecar" to "腾讯车载服务",
        "com.bilibili.bilithings" to "B站车机版",
        "com.geely.notificationdemo" to "通知测试工具",
        "com.baidu.netdisk.car" to "百度网盘车机版",
        "com.thunder.carplay" to "雷石KTV",
        "com.autoegg" to "今日电台",
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

    val packages: Set<String> = apps.map { it.first }.toSet()
}
