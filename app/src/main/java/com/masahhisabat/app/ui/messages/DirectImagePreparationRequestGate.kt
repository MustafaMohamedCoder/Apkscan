package com.masahhisabat.app.ui.messages

/**
 * يمنع تسليم نتيجة تجهيز صورة قديمة إلى شاشة مراسلة غادَرها المستخدم أو بدأ فيها طلب أحدث.
 * يبقى مستقلًا عن Android ليكون سلوكه قابلًا لاختبار الوحدة.
 */
class DirectImagePreparationRequestGate {
    private var generation = 0L

    @Synchronized
    fun beginRequest(): Long = ++generation

    @Synchronized
    fun canDeliver(request: Long): Boolean = request == generation

    @Synchronized
    fun invalidate() {
        ++generation
    }
}
