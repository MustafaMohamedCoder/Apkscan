package com.masahhisabat.app.ui.notifications

/** حالة العرض المشتقة لمركز الإشعارات من عدد العناصر غير المقروءة فقط. */
data class NotificationCenterState(
    val unreadLabel: String,
    val canMarkAllRead: Boolean
) {
    companion object {
        fun fromUnreadCount(unreadCount: Int): NotificationCenterState {
            val safeCount = unreadCount.coerceAtLeast(0)
            return NotificationCenterState(
                unreadLabel = if (safeCount == 0) "لا توجد إشعارات جديدة" else "$safeCount جديدة",
                canMarkAllRead = safeCount > 0
            )
        }
    }
}
