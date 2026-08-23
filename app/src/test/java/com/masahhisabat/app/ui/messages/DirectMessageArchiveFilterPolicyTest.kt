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

    @Test
    fun `intersects message type time range and Arabic search while keeping original order`() {
        val now = 1_700_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val messages = listOf(
            DirectMessage(
                id = "old-image",
                fromUser = "mustafa",
                toUser = "ahmed",
                text = "فاتورة قديمة",
                imagePath = "/images/old.jpg",
                createdAt = now - 8 * day
            ),
            DirectMessage(
                id = "matching-image",
                fromUser = "mustafa",
                toUser = "ahmed",
                text = "إيصال مصور",
                imagePath = "/images/recent.jpg",
                createdAt = now - day
            ),
            DirectMessage(
                id = "boundary-image",
                fromUser = "ahmed",
                toUser = "mustafa",
                text = "ايصال عند الحد",
                imagePath = "/images/boundary.jpg",
                createdAt = now - 7 * day
            ),
            DirectMessage(
                id = "recent-share",
                fromUser = "ahmed",
                toUser = "mustafa",
                shareCard = ShareCard(kind = "group", sourceGroupId = "group-1", title = "إيصال مشاركة"),
                createdAt = now - day
            )
        )

        val archive = DirectMessageArchiveFilterPolicy.filter(
            messages = messages,
            type = DirectMessageArchiveFilter.IMAGES,
            timeRange = DirectMessageArchiveTimeRange.LAST_7_DAYS,
            nowMillis = now
        )
        val result = DirectMessageSearchPolicy.filter(archive, "ايصال")

        assertEquals(listOf("matching-image", "boundary-image"), result.map { it.id })
    }
}
