package com.masahhisabat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectMessageAttachmentIntegrityPolicyTest {
    @Test
    fun `commits an incoming direct message only after its expected image is restored`() {
        assertTrue(
            SyncAttachmentIntegrityPolicy.canCommitDirectMessage(
                sourceImagePath = "/source/message.jpg",
                imagePayloadPresent = true,
                imageRestored = true
            )
        )
    }

    @Test
    fun `rejects an incoming direct message when its expected image cannot be restored`() {
        assertFalse(
            SyncAttachmentIntegrityPolicy.canCommitDirectMessage(
                sourceImagePath = "/source/message.jpg",
                imagePayloadPresent = true,
                imageRestored = false
            )
        )
    }

    @Test
    fun `commits a text-only direct message without an attachment payload`() {
        assertTrue(
            SyncAttachmentIntegrityPolicy.canCommitDirectMessage(
                sourceImagePath = null,
                imagePayloadPresent = false,
                imageRestored = false
            )
        )
    }
}
