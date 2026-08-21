package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashStorageSafetyPolicyTest {

    @Test
    fun `يقبل معرف المجموعة المحلي البسيط فقط عند حذف مجلدها النهائي`() {
        assertEquals("m4zy8j4", TrashStorageSafetyPolicy.groupDirectoryNameForPurge("group", "m4zy8j4"))
    }

    @Test
    fun `يرفض المعرف الفارغ أو الذي يحاول تجاوز مسار الفواتير`() {
        assertNull(TrashStorageSafetyPolicy.groupDirectoryNameForPurge("group", ""))
        assertNull(TrashStorageSafetyPolicy.groupDirectoryNameForPurge("group", ".."))
        assertNull(TrashStorageSafetyPolicy.groupDirectoryNameForPurge("group", "../all-invoices"))
    }

    @Test
    fun `لا يعيد مجلد مجموعة عند حذف عنصر منفرد من السلة`() {
        assertNull(TrashStorageSafetyPolicy.groupDirectoryNameForPurge("item", "m4zy8j4"))
    }
}
