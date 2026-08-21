package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceMergePolicyTest {
    @Test
    fun `keeps the most recent presence for the same normalized username`() {
        val local = UserPresence(username = "Ahmed", lastSeenAt = 100L)
        val incoming = UserPresence(username = "ahmed", lastSeenAt = 200L)

        val merged = PresenceMergePolicy.merge(local = listOf(local), incoming = listOf(incoming))

        assertEquals(1, merged.size)
        assertEquals("ahmed", merged.single().username)
        assertEquals(200L, merged.single().lastSeenAt)
    }

    @Test
    fun `does not replace a newer local presence with an older incoming one`() {
        val local = UserPresence(username = "sara", lastSeenAt = 300L)
        val incoming = UserPresence(username = "Sara", lastSeenAt = 200L)

        val merged = PresenceMergePolicy.merge(local = listOf(local), incoming = listOf(incoming))

        assertEquals(1, merged.size)
        assertEquals("sara", merged.single().username)
        assertEquals(300L, merged.single().lastSeenAt)
    }

    @Test
    fun `keeps the presence list bounded after merging`() {
        val merged = PresenceMergePolicy.merge(
            local = listOf(UserPresence(username = "first", lastSeenAt = 1L)),
            incoming = listOf(UserPresence(username = "second", lastSeenAt = 2L)),
            limit = 1
        )

        assertEquals(1, merged.size)
        assertEquals("second", merged.single().username)
    }
}
