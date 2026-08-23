package com.masahhisabat.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectMessageSearchHighlightPolicyTest {

    @Test
    fun `returns the displayed range for an Arabic match despite ignored formatting`() {
        val displayedText = "فـاتورة\u200F\u00A0المورد"

        val ranges = DirectMessageSearchHighlightPolicy.matchRanges(displayedText, "فاتورة المورد")

        assertEquals(listOf(0..displayedText.lastIndex), ranges)
    }
}
