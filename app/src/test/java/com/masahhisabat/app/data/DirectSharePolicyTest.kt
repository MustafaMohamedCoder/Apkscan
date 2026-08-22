package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectSharePolicyTest {
    @Test
    fun `message share preserves group source text and preferred processed attachment`() {
        val item = InvoiceItem(id = "item-1", text = "فاتورة شهر أغسطس", imagePath = "/raw.jpg", processedPath = "/clean.jpg")

        val payload = DirectSharePolicy.fromItem(Group(id = "group-1", name = "شركة النور"), item)

        assertEquals(DirectSharePolicy.Kind.GROUP_MESSAGE, payload.kind)
        assertEquals("group-1", payload.sourceGroupId)
        assertEquals("item-1", payload.sourceItemId)
        assertEquals("فاتورة شهر أغسطس", payload.preview)
        assertEquals("/clean.jpg", payload.imagePath)
    }

    @Test
    fun `group share creates a concise preview without attachment duplication`() {
        val payload = DirectSharePolicy.fromGroup(Group(id = "group-2", name = "مورد المدينة"), itemCount = 7)

        assertEquals(DirectSharePolicy.Kind.GROUP, payload.kind)
        assertEquals("مورد المدينة", payload.title)
        assertEquals("تضم 7 رسائل ومستندات", payload.preview)
        assertNull(payload.imagePath)
    }
}
