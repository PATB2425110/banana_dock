package com.car.dockbar.media

import android.service.notification.NotificationListenerService

/**
 * 只为拿到通知使用权 → 从而能通过 MediaSessionManager.getActiveSessions() 读系统媒体信息。
 * 不对任何通知做处理。
 */
class DockNotificationListener : NotificationListenerService()
