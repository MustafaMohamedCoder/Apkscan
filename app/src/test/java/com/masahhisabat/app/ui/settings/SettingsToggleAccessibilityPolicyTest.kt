package com.masahhisabat.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsToggleAccessibilityPolicyTest {

    @Test
    fun `describes enabled toggle with its purpose and next action`() {
        assertEquals(
            "الحذف التلقائي للسلة. احذف العناصر نهائيًا بعد 30 يومًا. مفعّل. اضغط للإيقاف.",
            SettingsToggleAccessibilityPolicy.describe(
                title = "الحذف التلقائي للسلة",
                summary = "احذف العناصر نهائيًا بعد 30 يومًا",
                isEnabled = true,
            ),
        )
    }

    @Test
    fun `describes disabled toggle with its purpose and next action`() {
        assertEquals(
            "تنبيهات الفواتير. تذكير محلي عند استحقاق فاتورة أو طلب. غير مفعّل. اضغط للتفعيل.",
            SettingsToggleAccessibilityPolicy.describe(
                title = "تنبيهات الفواتير",
                summary = "تذكير محلي عند استحقاق فاتورة أو طلب",
                isEnabled = false,
            ),
        )
    }
}
