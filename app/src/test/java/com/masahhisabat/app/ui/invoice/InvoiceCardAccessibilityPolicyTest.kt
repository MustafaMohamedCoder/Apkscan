package com.masahhisabat.app.ui.invoice

import org.junit.Assert.assertEquals
import org.junit.Test

/** يحمي سياق بطاقة الفاتورة وإجراء تغيير حالتها من الضياع لدى قارئات الشاشة. */
class InvoiceCardAccessibilityPolicyTest {

    @Test
    fun description_distinguishesOpeningGroupFromChangingInvoiceStatus() {
        assertEquals(
            "فتح مجموعة مورد الورق لعرض فاتورة إيصال أغسطس. التاريخ 2026-08-24. الإجمالي 125 جنيه. الحالة قيد المراجعة.",
            InvoiceCardAccessibilityPolicy.cardDescription(
                invoiceTitle = "إيصال أغسطس",
                groupName = "مورد الورق",
                date = "2026-08-24",
                total = "125 جنيه",
                statusLabel = "قيد المراجعة"
            )
        )
        assertEquals(
            "تغيير حالة فاتورة إيصال أغسطس. الحالة الحالية قيد المراجعة.",
            InvoiceCardAccessibilityPolicy.statusDescription(
                invoiceTitle = "إيصال أغسطس",
                statusLabel = "قيد المراجعة"
            )
        )
    }
}
