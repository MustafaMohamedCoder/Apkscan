package com.masahhisabat.app.ui.search

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * يبقي آخر عمل بحث فقط؛ الطلب الأحدث يلغي العمل السابق كي لا تتراكم مهام الخلفية
 * عند الكتابة المتتابعة. لا يقرر قبول النتيجة في الواجهة؛ تلك مسؤولية SearchRequestGate.
 */
class LatestSearchTaskCoordinator(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private var activeTask: Future<*>? = null

    @Synchronized
    fun submit(work: () -> Unit) {
        activeTask?.cancel(true)
        activeTask = executor.submit(work)
    }

    @Synchronized
    fun cancelPending() {
        activeTask?.cancel(true)
        activeTask = null
    }

    @Synchronized
    fun close() {
        cancelPending()
        executor.shutdownNow()
    }
}
