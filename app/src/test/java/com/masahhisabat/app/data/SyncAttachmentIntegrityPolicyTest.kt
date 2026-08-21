package com.masahhisabat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يثبت أن بيانات العنصر لا تسبق مرفقاتها عند استقبال لقطة مزامنة محلية. */
class SyncAttachmentIntegrityPolicyTest {
    @Test
    fun acceptsTextOnlyItemWithoutAttachmentPayload() {
        assertTrue(
            SyncAttachmentIntegrityPolicy.canCommitItem(
                sourceOriginalPath = null,
                originalPayloadPresent = false,
                originalRestored = false,
                sourceProcessedPath = null,
                processedPayloadPresent = false,
                processedRestored = false
            )
        )
    }

    @Test
    fun rejectsItemWhenExpectedOriginalAttachmentWasNotRestored() {
        assertFalse(
            SyncAttachmentIntegrityPolicy.canCommitItem(
                sourceOriginalPath = "/source/original.jpg",
                originalPayloadPresent = true,
                originalRestored = false,
                sourceProcessedPath = null,
                processedPayloadPresent = false,
                processedRestored = false
            )
        )
    }

    @Test
    fun rejectsItemWhenExpectedProcessedAttachmentWasNotRestored() {
        assertFalse(
            SyncAttachmentIntegrityPolicy.canCommitItem(
                sourceOriginalPath = null,
                originalPayloadPresent = false,
                originalRestored = false,
                sourceProcessedPath = "/source/processed.jpg",
                processedPayloadPresent = true,
                processedRestored = false
            )
        )
    }

    @Test
    fun acceptsItemWhenEveryExpectedAttachmentWasRestored() {
        assertTrue(
            SyncAttachmentIntegrityPolicy.canCommitItem(
                sourceOriginalPath = "/source/original.jpg",
                originalPayloadPresent = true,
                originalRestored = true,
                sourceProcessedPath = "/source/processed.jpg",
                processedPayloadPresent = true,
                processedRestored = true
            )
        )
    }
}
