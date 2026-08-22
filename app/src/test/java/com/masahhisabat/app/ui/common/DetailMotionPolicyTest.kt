package com.masahhisabat.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailMotionPolicyTest {

    @Test
    fun `uses animated transition when system animations are available`() {
        assertEquals(DetailMotion.ANIMATED, DetailMotionPolicy.resolve(animationsEnabled = true))
    }

    @Test
    fun `uses instant transition when user disables animations`() {
        assertEquals(DetailMotion.INSTANT, DetailMotionPolicy.resolve(animationsEnabled = false))
    }
}
