package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentCleanupPolicyTest {
    @Test
    fun `keeps attachment still referenced by another active item`() {
        val removable = AttachmentCleanupPolicy.pathsEligibleForDeletion(
            candidates = listOf("/images/shared.jpg"),
            activeReferences = setOf("/images/shared.jpg"),
            trashReferences = emptySet()
        )

        assertEquals(emptyList<String>(), removable)
    }

    @Test
    fun `keeps attachment retained for a trashed item`() {
        val removable = AttachmentCleanupPolicy.pathsEligibleForDeletion(
            candidates = listOf("/images/restore-me.jpg"),
            activeReferences = emptySet(),
            trashReferences = setOf("/images/restore-me.jpg")
        )

        assertEquals(emptyList<String>(), removable)
    }

    @Test
    fun `deletes unique nonblank attachment only once`() {
        val removable = AttachmentCleanupPolicy.pathsEligibleForDeletion(
            candidates = listOf("/images/unique.jpg", "/images/unique.jpg", "", null),
            activeReferences = emptySet(),
            trashReferences = emptySet()
        )

        assertEquals(listOf("/images/unique.jpg"), removable)
    }
}
