package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCallSessionPolicyTest {
    @Test
    fun `invite adds an online user once and keeps the host`() {
        val session = GroupCallSessionPolicy.create(host = "mustafa", initialPeer = "sara")

        val updated = GroupCallSessionPolicy.invite(session, "ahmed", isOnline = true)

        assertEquals(listOf("mustafa", "sara", "ahmed"), updated.participants)
        assertFalse(GroupCallSessionPolicy.canInvite(updated, "ahmed", isOnline = true))
    }

    @Test
    fun `offline users and full local mesh rooms cannot be invited`() {
        val session = GroupCallSessionPolicy.Session("mustafa", listOf("mustafa", "sara", "ahmed", "nour"))

        assertFalse(GroupCallSessionPolicy.canInvite(session, "ali", isOnline = true))
        assertFalse(GroupCallSessionPolicy.canInvite(GroupCallSessionPolicy.create("mustafa", "sara"), "ali", isOnline = false))
    }

    @Test
    fun `candidate matching an existing participant case insensitively is rejected`() {
        val session = GroupCallSessionPolicy.create(host = "Mustafa", initialPeer = "Sara")

        assertFalse(GroupCallSessionPolicy.canInvite(session, "mustafa", isOnline = true))
    }

    @Test
    fun `creating a session ignores blank participants`() {
        val session = GroupCallSessionPolicy.create(host = "mustafa", initialPeer = "")

        assertEquals(listOf("mustafa"), session.participants)
    }

    @Test
    fun `each pair uses a deterministic room scoped call id and one offer owner`() {
        val roomId = "room-17"

        assertEquals("room-17-ahmed-mustafa", GroupCallSessionPolicy.pairCallId(roomId, "mustafa", "ahmed"))
        assertTrue(GroupCallSessionPolicy.shouldCreateOffer("ahmed", "mustafa"))
        assertFalse(GroupCallSessionPolicy.shouldCreateOffer("mustafa", "ahmed"))
    }
}
