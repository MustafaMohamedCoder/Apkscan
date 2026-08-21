package com.masahhisabat.app.ui.notifications

/** حالة عرض شارة الإشعارات، مشتقة من المصدر الوحيد للإشعارات غير المقروءة. */
data class NotificationBadgeState(
    val visible: Boolean,
    val label: String,
    val contentDescription: String
) {
    companion object {
        fun fromUnreadCount(count: Int): NotificationBadgeState {
            val unread = count.coerceAtLeast(0)
            if (unread == 0) {
                return NotificationBadgeState(false, "", "لا توجد إشعارات غير مقروءة")
            }
            return NotificationBadgeState(
                visible = true,
                label = if (unread > 99) "99+" else unread.toString(),
                contentDescription = "لديك $unread إشعارات غير مقروءة"
            )
        }
    }
}
