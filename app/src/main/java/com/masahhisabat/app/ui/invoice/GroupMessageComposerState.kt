package com.masahhisabat.app.ui.invoice

/**
 * سياسة حالة محرر المجموعة. لا تعرف تفاصيل الواجهة أو التخزين، بل تحمي شروط الإضافة فقط.
 */
data class GroupMessageComposerState(
    val canEdit: Boolean,
    val hasTypedText: Boolean,
    val hasPreparedAttachment: Boolean,
    val isPreparingAttachment: Boolean,
    val isSaving: Boolean
) {
    val canSend: Boolean
        get() = canEdit && !isPreparingAttachment && !isSaving && (hasTypedText || hasPreparedAttachment)

    val canRemoveAttachment: Boolean
        get() = hasPreparedAttachment && !isPreparingAttachment && !isSaving
}
