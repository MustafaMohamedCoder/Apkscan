package com.masahhisabat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNotificationPolicyTest {
    @Test
    fun `does not count the active users own group message as unread`() {
        assertFalse(
            GroupNotificationPolicy.shouldCreateUnreadEvent(
                sender = "Mustafa",
                activeUsername = "mustafa"
            )
        )
    }

    @Test
    fun `counts a group message received from a different user`() {
        assertTrue(
            GroupNotificationPolicy.shouldCreateUnreadEvent(
                sender = "أحمد",
                activeUsername = "mustafa"
            )
        )
    }

    @Test
    fun `keeps synced group notifications out because the received item creates one locally`() {
        assertFalse(GroupNotificationPolicy.shouldImportSyncedEvent(type = "group_message"))
        assertTrue(GroupNotificationPolicy.shouldImportSyncedEvent(type = "direct_message"))
        assertTrue(GroupNotificationPolicy.shouldImportSyncedEvent(type = "missed_call"))
    }
}
