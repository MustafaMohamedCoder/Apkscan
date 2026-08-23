package com.masahhisabat.app.ui.invoice

/**
 * يمنح استخراج الفاتورة رقم طلب متزايدًا، فلا تُسلَّم إلى الواجهة إلا نتيجة
 * الطلب النشط. يستدعى البدء والإلغاء من خيط الواجهة، بينما قد تصل النتيجة
 * من OCR أو معالجة الصورة على خيط خلفي.
 */
class InvoiceExtractionRequestGate {
    @Volatile
    private var activeRequestId = 0L

    fun begin(): Long = ++activeRequestId

    fun invalidate() {
        activeRequestId++
    }

    fun accepts(requestId: Long): Boolean = requestId == activeRequestId
}
