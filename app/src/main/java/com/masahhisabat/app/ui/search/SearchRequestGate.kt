package com.masahhisabat.app.ui.search

/**
 * يمنح كل بحث رقمًا متزايدًا، ولا يقبل في الواجهة إلا نتيجة الطلب النشط.
 * يستدعى الإنشاء والإلغاء من خيط الواجهة، بينما قد تصل قراءة القبول بعد عمل خلفي.
 */
class SearchRequestGate {
    @Volatile
    private var activeRequestId = 0L

    fun begin(): Long = ++activeRequestId

    fun invalidate() {
        activeRequestId++
    }

    fun accepts(requestId: Long): Boolean = requestId == activeRequestId
}
