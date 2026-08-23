package com.masahhisabat.app.ui.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectImagePreparationRequestGateTest {

    @Test
    fun `يرفض نتيجة الصورة بعد إلغاء الشاشة`() {
        val gate = DirectImagePreparationRequestGate()

        val request = gate.beginRequest()
        gate.invalidate()

        assertFalse(gate.canDeliver(request))
    }

    @Test
    fun `يقبل الطلب الأحدث فقط`() {
        val gate = DirectImagePreparationRequestGate()

        val first = gate.beginRequest()
        val latest = gate.beginRequest()

        assertFalse(gate.canDeliver(first))
        assertTrue(gate.canDeliver(latest))
    }
}
