package com.masahhisabat.app.data

import java.util.Locale

/**
 * يدمج نبضات الحضور القادمة من أجهزة الشبكة المحلية بصورة حتمية.
 *
 * حالة الحضور ليست سجلًا تاريخيًا؛ لكل مستخدم حالة واحدة فقط. لذلك تفضّل السياسة
 * الطابع الزمني الأحدث وتطبع اسم المستخدم كي لا يتحول اختلاف حالة الأحرف إلى سجلين.
 */
object PresenceMergePolicy {
    fun merge(
        local: List<UserPresence>,
        incoming: List<UserPresence>,
        limit: Int = Int.MAX_VALUE
    ): List<UserPresence> {
        if (limit <= 0) return emptyList()

        val latestByUsername = LinkedHashMap<String, UserPresence>()
        (local + incoming).forEach { presence ->
            val username = presence.username.trim().lowercase(Locale.ROOT)
            if (username.isBlank()) return@forEach

            val normalized = presence.copy(username = username)
            val current = latestByUsername[username]
            if (current == null || normalized.lastSeenAt > current.lastSeenAt) {
                latestByUsername[username] = normalized
            }
        }

        return latestByUsername.values
            .sortedWith(compareByDescending<UserPresence> { it.lastSeenAt }.thenBy { it.username })
            .take(limit)
    }
}
