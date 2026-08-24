package com.masahhisabat.app.ui.groups

import org.junit.Assert.assertEquals
import org.junit.Test

/** يحمي ملخص TalkBack لبطاقة المجموعة من فقدان سياق الحالة والإجراء. */
class GroupCardAccessibilityPolicyTest {

    @Test
    fun description_includesOpenActionAndPriorityStates() {
        val description = GroupCardAccessibilityPolicy.description(
            groupName = "مورد الورق",
            documentCount = 3,
            openOrders = 1,
            unpaidInvoices = 2,
            isPinned = true,
            isArchived = false
        )

        assertEquals(
            "فتح مجموعة مورد الورق. 3 عناصر. 1 قيد المتابعة. 2 غير مسددة. مثبتة.",
            description
        )
    }
}
