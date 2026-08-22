package com.masahhisabat.app.ui.common

/** قرار مستقل قابل للاختبار لحركة فتح وإغلاق شاشات التفاصيل. */
enum class DetailMotion { ANIMATED, INSTANT }

object DetailMotionPolicy {
    fun resolve(animationsEnabled: Boolean): DetailMotion =
        if (animationsEnabled) DetailMotion.ANIMATED else DetailMotion.INSTANT
}
