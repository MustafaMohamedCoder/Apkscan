package com.masahhisabat.app.ui.messages

import com.masahhisabat.app.data.DirectMessage
import com.masahhisabat.app.data.ShareCard
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectMessageArchiveFilterPolicyTest {

    @Test
    fun `filters message types and search intersection while preserving conversation order`() {
        val messages = listOf(
            DirectMessage(id = "text-first", fromUser = "mustafa", toUser = "ahmed", text = "فاتورة شهرية"),
            DirectMessage(
                id = "image-second",
                fromUser = "ahmed",
                toUser = "mustafa",
                text = "فاتورة مصورة",
                imagePath = "/images/receipt.jpg"
            ),
            DirectMessage(
                id = "share-third",
                fromUser = "mustafa",
                toUser = "ahmed",
                shareCard = ShareCard(kind = "group", sourceGroupId = "group-1", title = "فاتورة المورد")
            ),
            DirectMessage(id = "image-fourth", fromUser = "ahmed", toUser = "mustafa", imagePath = "/images/other.jpg")
        )

        val archive = DirectMessageArchiveFilterPolicy.filter(messages, DirectMessageArchiveFilter.IMAGES)
        val result = DirectMessageSearchPolicy.filter(archive, "فاتورة")

        assertEquals(listOf("image-second"), result.map { it.id })
        assertEquals(
            listOf("text-first"),
            DirectMessageArchiveFilterPolicy.filter(messages, DirectMessageArchiveFilter.TEXT).map { it.id }
        )
        assertEquals(
            listOf("share-third"),
            DirectMessageArchiveFilterPolicy.filter(messages, DirectMessageArchiveFilter.SHARED_ITEMS).map { it.id }
        )
        assertEquals(messages, DirectMessageArchiveFilterPolicy.filter(messages, DirectMessageArchiveFilter.ALL))
    }
}
