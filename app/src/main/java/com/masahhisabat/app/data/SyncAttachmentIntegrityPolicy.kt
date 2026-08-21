package com.masahhisabat.app.data

/**
 * يحمي دمج عنصر وارد من إنشاء سجل يشير إلى مرفق لم يصل كاملًا إلى الجهاز المحلي.
 *
 * مسار العنصر من الجهاز المصدر هو مرجع الحقيقة لتوقع المرفق. لذلك لا تكفي
 * حمولة Base64 وحدها: لا بد أن تكون متوقعة، موجودة، وأن تنجح الكتابة الدائمة.
 */
object SyncAttachmentIntegrityPolicy {
    fun canCommitItem(
        sourceOriginalPath: String?,
        originalPayloadPresent: Boolean,
        originalRestored: Boolean,
        sourceProcessedPath: String?,
        processedPayloadPresent: Boolean,
        processedRestored: Boolean
    ): Boolean =
            isAttachmentReady(sourceOriginalPath, originalPayloadPresent, originalRestored) &&
            isAttachmentReady(sourceProcessedPath, processedPayloadPresent, processedRestored)

    fun canCommitDirectMessage(
        sourceImagePath: String?,
        imagePayloadPresent: Boolean,
        imageRestored: Boolean
    ): Boolean = isAttachmentReady(sourceImagePath, imagePayloadPresent, imageRestored)

    private fun isAttachmentReady(
        sourcePath: String?,
        payloadPresent: Boolean,
        restored: Boolean
    ): Boolean {
        val expected = !sourcePath.isNullOrBlank()
        return if (expected) payloadPresent && restored else !payloadPresent
    }
}
