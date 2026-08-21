package com.masahhisabat.app.data

/**
 * سياسة زمنية نقية لسلة المحذوفات. فصلها عن التخزين يجعل حدود الثلاثة والثلاثين يومًا
 * قابلة للاختبار محليًا ويمنع اختلافًا غير مقصود بين التحذير والتنظيف النهائي.
 */
internal object TrashRetentionPolicy {
    const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    const val WARNING_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L

    fun requiresDeletionWarning(stateChangedAt: Long, now: Long): Boolean {
        if (stateChangedAt <= 0L) return false
        val remaining = (stateChangedAt + RETENTION_MS) - now
        return remaining in 1..WARNING_WINDOW_MS
    }

    fun isExpired(stateChangedAt: Long, now: Long): Boolean {
        if (stateChangedAt <= 0L) return false
        return stateChangedAt <= now - RETENTION_MS
    }
}
