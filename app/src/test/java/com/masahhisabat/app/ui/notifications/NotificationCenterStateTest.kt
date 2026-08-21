package com.masahhisabat.app.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterStateTest {
    @Test
    fun `shows the unread count in the title state`() {
        val state = NotificationCenterState.fromUnreadCount(3)

        assertEquals("3 جديدة", state.unreadLabel)
        assertTrue(state.canMarkAllRead)
    }

    @Test
    fun `clears the title state after all notifications become read`() {
        val state = NotificationCenterState.fromUnreadCount(0)

        assertEquals("لا توجد إشعارات جديدة", state.unreadLabel)
        assertFalse(state.canMarkAllRead)
    }
}
