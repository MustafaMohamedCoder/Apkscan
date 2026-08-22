package com.masahhisabat.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ListItemMotionPolicyTest {

    @Test
    fun `animates only for the first presentation when system motion is enabled`() {
        assertEquals(
            ListItemMotion.ANIMATED,
            ListItemMotionPolicy.resolve(
                animationsEnabled = true,
                isFirstPresentation = true
            )
        )
        assertEquals(
            ListItemMotion.INSTANT,
            ListItemMotionPolicy.resolve(
                animationsEnabled = true,
                isFirstPresentation = false
            )
        )
    }

    @Test
    fun `skips entry animation when system motion is disabled`() {
        assertEquals(
            ListItemMotion.INSTANT,
            ListItemMotionPolicy.resolve(
                animationsEnabled = false,
                isFirstPresentation = true
            )
        )
    }
}
