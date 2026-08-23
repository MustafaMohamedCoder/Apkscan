package com.masahhisabat.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectMessageSearchNavigationPolicyTest {

    @Test
    fun `returns no selected result when the search has no matches`() {
        assertEquals(null, DirectMessageSearchNavigationPolicy.initialIndex(resultCount = 0))
        assertEquals(null, DirectMessageSearchNavigationPolicy.nextIndex(currentIndex = null, resultCount = 0))
        assertEquals(null, DirectMessageSearchNavigationPolicy.previousIndex(currentIndex = null, resultCount = 0))
    }

    @Test
    fun `wraps next and previous result navigation`() {
        assertEquals(0, DirectMessageSearchNavigationPolicy.initialIndex(resultCount = 3))
        assertEquals(0, DirectMessageSearchNavigationPolicy.nextIndex(currentIndex = 2, resultCount = 3))
        assertEquals(2, DirectMessageSearchNavigationPolicy.previousIndex(currentIndex = 0, resultCount = 3))
    }

    @Test
    fun `keeps navigation index valid when the result set becomes smaller`() {
        assertEquals(1, DirectMessageSearchNavigationPolicy.nextIndex(currentIndex = 4, resultCount = 2))
        assertEquals(1, DirectMessageSearchNavigationPolicy.previousIndex(currentIndex = 4, resultCount = 2))
    }
}
