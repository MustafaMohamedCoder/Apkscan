package com.masahhisabat.app.ui.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يحمي قرار تفعيل الإرسال داخل محرر الرسائل من الحالات المربكة للمستخدم. */
class MessageComposerStateTest {

    @Test
    fun canSend_requiresRecipientAndReadyContent() {
        assertFalse(
            MessageComposerState(
                hasRecipient = false,
                hasTypedText = true,
                hasPreparedImage = false,
                isImagePreparationInProgress = false
            ).canSend
        )

        assertFalse(
            MessageComposerState(
                hasRecipient = true,
                hasTypedText = false,
                hasPreparedImage = false,
                isImagePreparationInProgress = false
            ).canSend
        )

        assertTrue(
            MessageComposerState(
                hasRecipient = true,
                hasTypedText = false,
                hasPreparedImage = true,
                isImagePreparationInProgress = false
            ).canSend
        )
    }

    @Test
    fun canSend_blocksUntilSelectedImagePreparationCompletes() {
        assertFalse(
            MessageComposerState(
                hasRecipient = true,
                hasTypedText = true,
                hasPreparedImage = false,
                isImagePreparationInProgress = true
            ).canSend
        )
    }

    @Test
    fun canRemoveAttachment_requiresPreparedImageAndNoActivePreparation() {
        assertTrue(
            MessageComposerState(
                hasRecipient = false,
                hasTypedText = false,
                hasPreparedImage = true,
                isImagePreparationInProgress = false
            ).canRemoveAttachment
        )

        assertFalse(
            MessageComposerState(
                hasRecipient = true,
                hasTypedText = true,
                hasPreparedImage = false,
                isImagePreparationInProgress = false
            ).canRemoveAttachment
        )

        assertFalse(
            MessageComposerState(
                hasRecipient = true,
                hasTypedText = false,
                hasPreparedImage = true,
                isImagePreparationInProgress = true
            ).canRemoveAttachment
        )
    }
}
