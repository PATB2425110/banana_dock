package com.car.dockbar.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知使用权有两用：
 *  1. [android.media.session.MediaSessionManager.getActiveSessions] 读标准媒体会话
 *  2. 读 `com.flyme.auto.music` 的媒体通知。这台 CS11 上 QQ 音乐走这个包，
 *     它不出现在活跃会话列表里，歌名只在通知里。
 */
class DockNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        MediaBridge.ingestNotifications(activeNotifications)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        MediaBridge.ingestNotification(sbn, removed = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        MediaBridge.ingestNotification(sbn, removed = true)
    }

    companion object {
        @Volatile
        var instance: DockNotificationListener? = null
            private set
    }
}
