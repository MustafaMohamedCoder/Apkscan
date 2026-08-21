package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupTrashDraftPolicyTest {
    @Test
    fun `keeps a nonblank group draft exactly as written for a later restore`() {
        val draft = "  فاتورة المورد — أضف السعر بعد المراجعة\n"

        assertEquals(draft, GroupTrashDraftPolicy.draftForTrash(draft))
    }

    @Test
    fun `does not persist an empty draft in the trash record`() {
        assertNull(GroupTrashDraftPolicy.draftForTrash("   "))
    }

    @Test
    fun `restores the archived draft when there is no local draft`() {
        val archivedDraft = "الكمية تحتاج تأكيدًا"

        assertEquals(archivedDraft, GroupTrashDraftPolicy.draftForRestore(archivedDraft, localDraft = ""))
    }

    @Test
    fun `keeps a newer local draft when a restored trash record arrives from sync`() {
        val archivedDraft = "ملاحظة قبل الحذف"
        val localDraft = "ملاحظة أحدث كُتبت بعد الاستعادة"

        assertEquals(localDraft, GroupTrashDraftPolicy.draftForRestore(archivedDraft, localDraft))
    }
}
