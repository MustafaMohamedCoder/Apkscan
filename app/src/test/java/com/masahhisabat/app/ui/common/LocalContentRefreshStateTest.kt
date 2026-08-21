package com.masahhisabat.app.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContentRefreshStateTest {

    @Test
    fun `starts one refresh and suppresses repeated gestures until completion`() {
        val state = LocalContentRefreshState()

        assertTrue(state.tryStart())
        assertTrue(state.isRefreshing)
        assertFalse(state.tryStart())

        state.finish()

        assertFalse(state.isRefreshing)
        assertTrue(state.tryStart())
    }

    @Test
    fun `cancelling refresh leaves view ready for a later gesture`() {
        val state = LocalContentRefreshState()
        state.tryStart()

        state.cancel()

        assertFalse(state.isRefreshing)
        assertTrue(state.tryStart())
    }
}
