package com.masahhisabat.app.ui.messages

import com.masahhisabat.app.data.DirectMessage
import com.masahhisabat.app.data.ShareCard
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectMessageSearchPolicyTest {

    @Test
    fun `matches Arabic text after normalizing hamza variants`() {
        val messages = listOf(
            DirectMessage(id = "receipt", fromUser = "mustafa", toUser = "ahmed", text = "إيصال المورد"),
            DirectMessage(id = "other", fromUser = "mustafa", toUser = "ahmed", text = "موعد التسليم")
        )

        val result = DirectMessageSearchPolicy.filter(messages, "ايصال")

        assertEquals(listOf("receipt"), result.map { it.id })
    }

    @Test
    fun `matches Arabic text despite tatweel formatting marks and nonbreaking spaces`() {
        val messages = listOf(
            DirectMessage(
                id = "decorated",
                fromUser = "mustafa",
                toUser = "ahmed",
                text = "فـاتورة\u200F\u00A0المورد"
            )
        )

        val result = DirectMessageSearchPolicy.filter(messages, "فاتورة المورد")

        assertEquals(listOf("decorated"), result.map { it.id })
    }

    @Test
    fun `matches a shared card title or preview while keeping chronological order`() {
        val messages = listOf(
            DirectMessage(id = "text", fromUser = "mustafa", toUser = "ahmed", text = "فاتورة الشهر"),
            DirectMessage(
                id = "share",
                fromUser = "ahmed",
                toUser = "mustafa",
                shareCard = ShareCard(
                    kind = "group",
                    sourceGroupId = "g1",
                    title = "ملف المورد",
                    preview = "فاتورة مستحقة"
                )
            ),
            DirectMessage(id = "other", fromUser = "mustafa", toUser = "ahmed", text = "تم الاستلام")
        )

        val result = DirectMessageSearchPolicy.filter(messages, "فاتورة")

        assertEquals(listOf("text", "share"), result.map { it.id })
    }

    @Test
    fun `returns every message for a blank query`() {
        val messages = listOf(
            DirectMessage(id = "first", fromUser = "mustafa", toUser = "ahmed"),
            DirectMessage(id = "second", fromUser = "ahmed", toUser = "mustafa")
        )

        assertEquals(messages, DirectMessageSearchPolicy.filter(messages, "   "))
    }
}
