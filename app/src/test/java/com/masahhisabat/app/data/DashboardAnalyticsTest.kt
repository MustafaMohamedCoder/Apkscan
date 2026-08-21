package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** تحمي قراءة الإجماليات من اختلاف لوحة المفاتيح أو تنسيق التاجر للأرقام. */
class DashboardAnalyticsTest {

    @Test
    fun parseAmount_handlesArabicDigitsAndArabicSeparators() {
        assertEquals(1234.50, requireNotNull(DashboardAnalytics.parseAmount("١٬٢٣٤٫٥٠")), 0.0001)
    }

    @Test
    fun parseAmount_handlesWesternThousandsAndDecimalSeparators() {
        assertEquals(1234.50, requireNotNull(DashboardAnalytics.parseAmount("1,234.50 ج.م")), 0.0001)
        assertEquals(1234567.0, requireNotNull(DashboardAnalytics.parseAmount("1,234,567")), 0.0001)
    }

    @Test
    fun parseAmount_rejectsValuesWithoutDigits() {
        assertNull(DashboardAnalytics.parseAmount("غير متاح"))
    }

    @Test
    fun formatAmount_keepsWholeAndFractionalValuesReadable() {
        assertEquals("42", DashboardAnalytics.formatAmount(42.0))
        assertEquals("42.50", DashboardAnalytics.formatAmount(42.5))
    }
}
