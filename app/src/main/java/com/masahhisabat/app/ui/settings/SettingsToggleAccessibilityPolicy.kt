package com.masahhisabat.app.ui.settings

/** يبني وصفًا عربيًا كاملاً لمفتاح إعداد بحيث يوضح الغرض والحالة والإجراء التالي. */
object SettingsToggleAccessibilityPolicy {
    fun describe(title: String, summary: String, isEnabled: Boolean): String {
        val state = if (isEnabled) "مفعّل" else "غير مفعّل"
        val action = if (isEnabled) "اضغط للإيقاف." else "اضغط للتفعيل."
        return "$title. $summary. $state. $action"
    }
}
