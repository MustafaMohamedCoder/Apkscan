package com.masahhisabat.app.data

/** تحافظ على مسودة المجموعة عند النقل إلى السلة دون استبدال مسودة محلية أحدث عند الاستعادة. */
object GroupTrashDraftPolicy {
    fun draftForTrash(draft: String): String? = draft.takeIf { it.isNotBlank() }

    fun draftForRestore(archivedDraft: String, localDraft: String): String =
        localDraft.takeIf { it.isNotBlank() } ?: archivedDraft
}
