package com.masahhisabat.app.ui.invoice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMessageComposerStateTest {

    @Test
    fun `can send text when editor is permitted and idle`() {
        val state = GroupMessageComposerState(
            canEdit = true,
            hasTypedText = true,
            hasPreparedAttachment = false,
            isPreparingAttachment = false,
            isSaving = false
        )

        assertTrue(state.canSend)
    }

    @Test
    fun `can send prepared attachment without text`() {
        val state = GroupMessageComposerState(
            canEdit = true,
            hasTypedText = false,
            hasPreparedAttachment = true,
            isPreparingAttachment = false,
            isSaving = false
        )

        assertTrue(state.canSend)
    }

    @Test
    fun `cannot send while attachment preparation is in progress`() {
        val state = GroupMessageComposerState(
            canEdit = true,
            hasTypedText = true,
            hasPreparedAttachment = false,
            isPreparingAttachment = true,
            isSaving = false
        )

        assertFalse(state.canSend)
    }

    @Test
    fun `cannot send when user cannot edit or a save is already running`() {
        val readOnlyState = GroupMessageComposerState(
            canEdit = false,
            hasTypedText = true,
            hasPreparedAttachment = false,
            isPreparingAttachment = false,
            isSaving = false
        )
        val savingState = GroupMessageComposerState(
            canEdit = true,
            hasTypedText = true,
            hasPreparedAttachment = false,
            isPreparingAttachment = false,
            isSaving = true
        )

        assertFalse(readOnlyState.canSend)
        assertFalse(savingState.canSend)
    }

    @Test
    fun `cannot remove a prepared attachment while it is being saved`() {
        val state = GroupMessageComposerState(
            canEdit = true,
            hasTypedText = false,
            hasPreparedAttachment = true,
            isPreparingAttachment = false,
            isSaving = true
        )

        assertFalse(state.canRemoveAttachment)
    }
}
