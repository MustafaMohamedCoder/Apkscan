package com.masahhisabat.app.data

/**
 * يحصر عمليات حذف مجلدات الفواتير في معرفات المجموعات التي ينشئها التطبيق محليًا.
 * لا يجوز أن يصبح سجل سلة تالف أو وارد من مزامنة محلية مسارًا لحذف خارج مجلد المجموعة.
 */
object TrashStorageSafetyPolicy {
    private val safeGroupDirectoryName = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

    fun groupDirectoryNameForPurge(type: String, groupId: String): String? =
        groupId.takeIf { type == "group" && safeGroupDirectoryName.matches(it) }
}
