package com.masahhisabat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRestoreSafetyPolicyTest {
    @Test
    fun `does not expose a restored group when its items could not be persisted`() {
        assertFalse(TrashRestoreSafetyPolicy.canExposeRestoredGroup(itemsPersisted = false))
    }

    @Test
    fun `exposes a restored group after its items are persisted`() {
        assertTrue(TrashRestoreSafetyPolicy.canExposeRestoredGroup(itemsPersisted = true))
    }
}
