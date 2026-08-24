package com.masahhisabat.app.ui.invoice

/**
 * UX/UI: يبقي سياق بطاقة صندوق الفواتير وإجراء تغيير الحالة واضحين في TalkBack.
 * لا يغير بيانات الفاتورة أو ترتيبها أو سير تحديث حالتها.
 */
object InvoiceCardAccessibilityPolicy {
    fun cardDescription(
        invoiceTitle: String,
        groupName: String,
        date: String,
        total: String?,
        statusLabel: String
    ): String = buildList {
        add("فتح مجموعة $groupName لعرض فاتورة $invoiceTitle.")
        add("التاريخ $date.")
        total?.takeIf { it.isNotBlank() }?.let { add("الإجمالي $it.") }
        add("الحالة $statusLabel.")
    }.joinToString(" ")

    fun statusDescription(invoiceTitle: String, statusLabel: String): String =
        "تغيير حالة فاتورة $invoiceTitle. الحالة الحالية $statusLabel."

    fun emptyMessage(statusLabel: String): String =
        "لا توجد فواتير $statusLabel الآن. حدّث صندوق الوارد أو غيّر حالة المتابعة من بطاقة الفاتورة."
}
