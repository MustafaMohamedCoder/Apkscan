package com.masahhisabat.app.ui.groups

/** يصوغ ملخصًا قصيرًا ومتكاملًا لقارئات الشاشة لبطاقة المجموعة القابلة للفتح. */
object GroupCardAccessibilityPolicy {
    fun description(
        groupName: String,
        documentCount: Int,
        openOrders: Int,
        unpaidInvoices: Int,
        isPinned: Boolean,
        isArchived: Boolean
    ): String = buildString {
        append("فتح مجموعة ")
        append(groupName)
        append(". ")
        append(documentCount)
        append(" عناصر.")
        if (openOrders > 0) {
            append(" ")
            append(openOrders)
            append(" قيد المتابعة.")
        }
        if (unpaidInvoices > 0) {
            append(" ")
            append(unpaidInvoices)
            append(" غير مسددة.")
        }
        if (isPinned) append(" مثبتة.")
        if (isArchived) append(" مؤرشفة.")
    }
}
