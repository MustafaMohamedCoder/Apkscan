package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** يتحقق من ثبات ترتيب الرسائل عند دمج سجلات أجهزة لها توقيتات متساوية. */
class DirectMessageOrderTest {

    @Test
    fun stableOrder_sortsByCreationTimeThenId() {
        val messages = listOf(
            DirectMessage(id = "m-2", fromUser = "mustafa", toUser = "ahmed", text = "ثانية", createdAt = 100L),
            DirectMessage(id = "m-1", fromUser = "ahmed", toUser = "mustafa", text = "أولى", createdAt = 100L),
            DirectMessage(id = "old", fromUser = "mustafa", toUser = "ahmed", text = "قديمة", createdAt = 99L)
        )

        assertEquals(listOf("old", "m-1", "m-2"), AppRepository.stableDirectMessageOrder(messages).map { it.id })
    }
}
