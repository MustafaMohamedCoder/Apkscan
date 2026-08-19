package com.masahhisabat.app.data

import android.content.Context

/** قاموس موحّد لحالات المتابعة كي تبقى شاشة الوارد والتنبيهات متسقة. */
object InvoiceWorkflow {
    const val NEW = "new"
    const val IN_REVIEW = "in_review"
    const val COMPLETED = "completed"
    const val PAID = "paid"

    val statuses = listOf(NEW, IN_REVIEW, COMPLETED, PAID)

    fun label(status: String): String = when (status) {
        IN_REVIEW -> "قيد المراجعة"
        COMPLETED -> "مكتملة"
        PAID -> "مدفوعة"
        else -> "جديدة"
    }

    fun updateStatus(context: Context, groupId: String, itemId: String, status: String): InvoiceItem? {
        val item = AppRepository.updateInvoiceStatus(groupId, itemId, status) ?: return null
        InvoiceReminderScheduler.update(context)
        return item
    }
}
