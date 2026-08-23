package com.masahhisabat.app.ui.messages

/** تنقل محلي دائري بين نتائج البحث المعروضة، من دون تغيير الرسائل أو مصدرها. */
object DirectMessageSearchNavigationPolicy {
    fun initialIndex(resultCount: Int): Int? = if (resultCount > 0) 0 else null

    fun nextIndex(currentIndex: Int?, resultCount: Int): Int? {
        if (resultCount <= 0) return null
        val current = currentIndex ?: return 0
        if (current !in 0 until resultCount) return resultCount - 1
        return (current + 1) % resultCount
    }

    fun previousIndex(currentIndex: Int?, resultCount: Int): Int? {
        if (resultCount <= 0) return null
        val current = currentIndex ?: return resultCount - 1
        if (current !in 0 until resultCount) return resultCount - 1
        return if (current == 0) resultCount - 1 else current - 1
    }
}
