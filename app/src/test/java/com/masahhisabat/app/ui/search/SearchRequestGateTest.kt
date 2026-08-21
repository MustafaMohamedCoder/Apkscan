package com.masahhisabat.app.ui.search

import org.junit.Assert.assertFalse
import org.junit.Test

/** يحمي شاشة البحث من إعادة عرض نتائج طلب تم إلغاؤه عند مسح الاستعلام. */
class SearchRequestGateTest {

    @Test
    fun resetInvalidatesActiveRequestSoLateResultIsRejected() {
        val gate = SearchRequestGate()
        val activeRequest = gate.begin()

        gate.invalidate()

        assertFalse(gate.accepts(activeRequest))
    }
}
