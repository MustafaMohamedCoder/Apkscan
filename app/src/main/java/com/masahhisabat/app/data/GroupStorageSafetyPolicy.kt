package com.masahhisabat.app.data

/**
 * يحدد الأسماء التي يمكن استخدامها كمجلدات للمجموعات.
 *
 * معرّفات المجموعة تُولّد محليًا من محارف ASCII، لذلك نرفض المسافات والفواصل
 * والنقاط المنفردة وأي قيمة طويلة أو غير متوقعة قبل تركيب مسار على نظام الملفات.
 */
object GroupStorageSafetyPolicy {
    fun safeDirectoryName(groupId: String): String? = StorageKeySafetyPolicy.safePart(groupId)
}
