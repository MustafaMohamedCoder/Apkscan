package com.masahhisabat.app.ui.common

/**
 * تحدد حركة دخول عنصر القائمة من دون إعادة تحريك العنصر عند إعادة تدويره أثناء التمرير.
 * يحترم القرار إعداد المستخدم لتعطيل رسوم النظام.
 */
enum class ListItemMotion {
    ANIMATED,
    INSTANT
}

object ListItemMotionPolicy {
    fun resolve(animationsEnabled: Boolean, isFirstPresentation: Boolean): ListItemMotion =
        if (animationsEnabled && isFirstPresentation) ListItemMotion.ANIMATED else ListItemMotion.INSTANT
}
