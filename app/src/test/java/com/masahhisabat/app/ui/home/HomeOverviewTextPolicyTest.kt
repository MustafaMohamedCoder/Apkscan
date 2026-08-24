package com.masahhisabat.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOverviewTextPolicyTest {

    @Test
    fun `يصوغ ملخص الرسائل بعدد عربي واضح`() {
        assertEquals("إرسال نصوص وصور", HomeOverviewTextPolicy.messagesSummary(0))
        assertEquals("رسالة محفوظة واحدة", HomeOverviewTextPolicy.messagesSummary(1))
        assertEquals("رسالتان محفوظتان", HomeOverviewTextPolicy.messagesSummary(2))
        assertEquals("3 رسائل محفوظة", HomeOverviewTextPolicy.messagesSummary(3))
        assertEquals("11 رسالة محفوظة", HomeOverviewTextPolicy.messagesSummary(11))
    }

    @Test
    fun `يفرق وصف بطاقة المجموعات عن الفواتير`() {
        assertEquals("3 مجموعات. فتح قائمة المجموعات", HomeOverviewTextPolicy.groupsCardDescription(3))
        assertEquals("فاتورة واحدة محفوظة. فتح صندوق الوارد", HomeOverviewTextPolicy.invoicesCardDescription(1))
    }

    @Test
    fun `يحافظ على ملخص الإشعارات القابل للفهم`() {
        assertEquals("لا توجد إشعارات جديدة", HomeOverviewTextPolicy.notificationsSummary(0))
        assertEquals("إشعار جديد واحد", HomeOverviewTextPolicy.notificationsSummary(1))
        assertEquals("إشعاران جديدان", HomeOverviewTextPolicy.notificationsSummary(2))
        assertEquals("12 إشعارًا جديدًا", HomeOverviewTextPolicy.notificationsSummary(12))
    }

    @Test
    fun `يوضح وصف بطاقة المراسلات الإجراء وحالة الفراغ أو العدد`() {
        assertEquals(
            "المراسلات. لا توجد رسائل محفوظة بعد. إرسال نصوص وصور. فتح المراسلات",
            HomeOverviewTextPolicy.messagesCardDescription(0)
        )
        assertEquals(
            "المراسلات. 3 رسائل محفوظة. فتح المراسلات",
            HomeOverviewTextPolicy.messagesCardDescription(3)
        )
    }
}
