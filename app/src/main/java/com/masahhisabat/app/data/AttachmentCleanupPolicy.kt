package com.masahhisabat.app.data

/**
 * يحدد المرفقات التي لم تعد مرتبطة بعنصر فعّال أو بسجل قابل للاستعادة في السلة.
 * يبقى قرار حذف الملف خارج هذه السياسة حتى تظل قابلة لاختبارات الوحدة بلا Android.
 */
object AttachmentCleanupPolicy {
    fun pathsEligibleForDeletion(
        candidates: Collection<String?>,
        activeReferences: Set<String>,
        trashReferences: Set<String>
    ): List<String> = candidates
        .asSequence()
        .filterNotNull()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .filterNot { it in activeReferences || it in trashReferences }
        .toList()
}
