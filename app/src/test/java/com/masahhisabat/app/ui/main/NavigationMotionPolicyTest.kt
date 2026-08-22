package com.masahhisabat.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionPolicyTest {

    @Test
    fun `animates a user initiated tab change when system animations are enabled`() {
        assertEquals(
            NavigationMotion.ANIMATED,
            NavigationMotionPolicy.resolve(requested = true, systemAnimationsEnabled = true)
        )
    }

    @Test
    fun `keeps initial tab selection instant`() {
        assertEquals(
            NavigationMotion.INSTANT,
            NavigationMotionPolicy.resolve(requested = false, systemAnimationsEnabled = true)
        )
    }

    @Test
    fun `keeps tab changes instant when the user disables system animations`() {
        assertEquals(
            NavigationMotion.INSTANT,
            NavigationMotionPolicy.resolve(requested = true, systemAnimationsEnabled = false)
        )
    }
}
