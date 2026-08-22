package com.masahhisabat.app.data

/**
 * يتحقق من جزء اسم ملف أو مجلد لا يجوز أن يحتوي على فاصل مسار أو مسار نسبي.
 * الصيغة توافق معرّفات التطبيق التي تُولّد من محارف ASCII والشرطة والشرطة السفلية.
 */
object StorageKeySafetyPolicy {
    private val safePartPattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

    fun safePart(value: String): String? =
        value.takeIf { safePartPattern.matches(it) }
}
