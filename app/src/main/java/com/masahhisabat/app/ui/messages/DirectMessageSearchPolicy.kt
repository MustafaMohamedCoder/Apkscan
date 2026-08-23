package com.masahhisabat.app.ui.messages

import com.masahhisabat.app.data.DirectMessage

/**
 * فلترة محلية للمحادثة المباشرة؛ لا تغير الرسائل أو التخزين أو حمولة المزامنة.
 * يحافظ [filter] على ترتيب القائمة الوارد من المستودع.
 */
object DirectMessageSearchPolicy {
    fun filter(messages: List<DirectMessage>, query: String): List<DirectMessage> {
        val normalizedQuery = DirectMessageSearchTextNormalizer.normalize(query)
        if (normalizedQuery.isBlank()) return messages

        return messages.filter { message ->
            sequenceOf(
                message.text,
                message.shareCard?.title,
                message.shareCard?.preview
            )
                .filterNotNull()
                .any { value -> DirectMessageSearchTextNormalizer.normalize(value).contains(normalizedQuery) }
        }
    }
}
