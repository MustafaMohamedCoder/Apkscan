package com.masahhisabat.app.data

/**
 * يحصر عمليات حذف مجلدات الفواتير في معرفات المجموعات التي ينشئها التطبيق محليًا.
 * لا يجوز أن يصبح سجل سلة تالف أو وارد من مزامنة محلية مسارًا لحذف خارج مجلد المجموعة.
 */
object TrashStorageSafetyPolicy {
    fun groupDirectoryNameForPurge(type: String, groupId: String): String? =
        groupId.takeIf { type == "group" }?.let(GroupStorageSafetyPolicy::safeDirectoryName)
}
