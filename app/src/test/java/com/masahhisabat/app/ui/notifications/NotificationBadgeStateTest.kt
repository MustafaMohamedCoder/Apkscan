package com.masahhisabat.app.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBadgeStateTest {
    @Test
    fun `shows the exact unread badge count`() {
        val state = NotificationBadgeState.fromUnreadCount(7)

        assertTrue(state.visible)
        assertEquals("7", state.label)
        assertEquals("لديك 7 إشعارات غير مقروءة", state.contentDescription)
    }

    @Test
    fun `hides the badge when every notification was read`() {
        val state = NotificationBadgeState.fromUnreadCount(0)

        assertFalse(state.visible)
        assertEquals("", state.label)
        assertEquals("لا توجد إشعارات غير مقروءة", state.contentDescription)
    }

    @Test
    fun `caps a dense inbox badge at ninety nine plus`() {
        val state = NotificationBadgeState.fromUnreadCount(104)

        assertTrue(state.visible)
        assertEquals("99+", state.label)
    }
}
