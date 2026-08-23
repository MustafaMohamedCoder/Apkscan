package com.masahhisabat.app.ui.messages

/**
 * الحالة المرئية لمحرر رسالة مباشرة.
 * تفصل شروط الإرسال عن عناصر واجهة النشاط كي تبقى موحدة وقابلة لاختبار الوحدة.
 */
data class MessageComposerState(
    val hasRecipient: Boolean,
    val hasTypedText: Boolean,
    val hasPreparedImage: Boolean,
    val isImagePreparationInProgress: Boolean
) {
    val canSend: Boolean
        get() = hasRecipient && !isImagePreparationInProgress && (hasTypedText || hasPreparedImage)

    /** يسمح بإزالة المرفق المكتمل قبل الإرسال، ولا يعرض إجراءً مضللًا أثناء النسخ المحلي. */
    val canRemoveAttachment: Boolean
        get() = hasPreparedImage && !isImagePreparationInProgress
}
