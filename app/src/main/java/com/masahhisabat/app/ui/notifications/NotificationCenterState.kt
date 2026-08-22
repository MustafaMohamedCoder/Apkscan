package com.masahhisabat.app.ui.notifications

/** حالة العرض المشتقة لمركز الإشعارات من عدد العناصر غير المقروءة فقط. */
data class NotificationCenterState(
    val unreadLabel: String,
    val canMarkAllRead: Boolean,
    val isEmpty: Boolean
) {
    companion object {
        fun fromUnreadCount(unreadCount: Int): NotificationCenterState {
            return fromCounts(unreadCount = unreadCount, totalCount = 1)
        }

        /**
         * يبقي العنوان وأزرار الإجراء والحالة الفارغة مشتقة من نفس لقطة البيانات.
         * فالقائمة الخالية ليست مرادفًا لعدم وجود عناصر غير مقروءة.
         */
        fun fromCounts(unreadCount: Int, totalCount: Int): NotificationCenterState {
            val safeCount = unreadCount.coerceAtLeast(0)
            val safeTotal = totalCount.coerceAtLeast(0)
            return NotificationCenterState(
                unreadLabel = if (safeCount == 0) "لا توجد إشعارات جديدة" else "$safeCount جديدة",
                canMarkAllRead = safeCount > 0,
                isEmpty = safeTotal == 0
            )
        }
    }
}
