package com.masahhisabat.app.ui.home

/**
 * نصوص موجزة ومتسقة تعرض حالة لوحة البداية وتُستخدم أيضًا للوصول عبر قارئ الشاشة.
 */
object HomeOverviewTextPolicy {

    fun messagesSummary(rawCount: Int): String = when (val count = rawCount.coerceAtLeast(0)) {
        0 -> "إرسال نصوص وصور"
        1 -> "رسالة محفوظة واحدة"
        2 -> "رسالتان محفوظتان"
        in 3..10 -> "$count رسائل محفوظة"
        else -> "$count رسالة محفوظة"
    }

    fun notificationsSummary(rawCount: Int): String = when (val count = rawCount.coerceAtLeast(0)) {
        0 -> "لا توجد إشعارات جديدة"
        1 -> "إشعار جديد واحد"
        2 -> "إشعاران جديدان"
        in 3..10 -> "$count إشعارات جديدة"
        else -> "$count إشعارًا جديدًا"
    }

    fun messagesCardDescription(rawCount: Int): String {
        val count = rawCount.coerceAtLeast(0)
        val status = if (count == 0) {
            "لا توجد رسائل محفوظة بعد. ${messagesSummary(count)}"
        } else {
            messagesSummary(count)
        }
        return "المراسلات. $status. فتح المراسلات"
    }

    fun groupsCardDescription(rawCount: Int): String = "${groupsSummary(rawCount)}. فتح قائمة المجموعات"

    fun invoicesCardDescription(rawCount: Int): String = "${invoicesSummary(rawCount)}. فتح صندوق الوارد"

    private fun groupsSummary(rawCount: Int): String = when (val count = rawCount.coerceAtLeast(0)) {
        0 -> "لا توجد مجموعات"
        1 -> "مجموعة واحدة"
        2 -> "مجموعتان"
        in 3..10 -> "$count مجموعات"
        else -> "$count مجموعة"
    }

    private fun invoicesSummary(rawCount: Int): String = when (val count = rawCount.coerceAtLeast(0)) {
        0 -> "لا توجد فواتير محفوظة"
        1 -> "فاتورة واحدة محفوظة"
        2 -> "فاتورتان محفوظتان"
        in 3..10 -> "$count فواتير محفوظة"
        else -> "$count فاتورة محفوظة"
    }
}
