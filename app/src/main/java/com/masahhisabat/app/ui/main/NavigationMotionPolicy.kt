package com.masahhisabat.app.ui.main

/** قرار نقي لحركة تبديل تبويبات الغلاف، قابل للاختبار ويحترم إعداد رسوم النظام. */
enum class NavigationMotion { ANIMATED, INSTANT }

object NavigationMotionPolicy {
    fun resolve(requested: Boolean, systemAnimationsEnabled: Boolean): NavigationMotion =
        if (requested && systemAnimationsEnabled) NavigationMotion.ANIMATED else NavigationMotion.INSTANT
}
