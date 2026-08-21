package com.masahhisabat.app.ui.common

/**
 * تمنع تكرار إيماءة السحب بينما يجري تحديث المحتوى المحلي.
 *
 * الحالة مستقلة عن واجهة Android كي تبقى قابلة لاختبار الانحدار وتُستخدم
 * بنفس السلوك في قوائم التطبيق الأخرى لاحقًا.
 */
class LocalContentRefreshState {
    var isRefreshing: Boolean = false
        private set

    fun tryStart(): Boolean {
        if (isRefreshing) return false
        isRefreshing = true
        return true
    }

    fun finish() {
        isRefreshing = false
    }

    fun cancel() = finish()
}
