package com.masahhisabat.app.ui.messages

import com.masahhisabat.app.data.DirectMessage
import java.util.Locale

/**
 * فلترة محلية للمحادثة المباشرة؛ لا تغير الرسائل أو التخزين أو حمولة المزامنة.
 * يحافظ [filter] على ترتيب القائمة الوارد من المستودع.
 */
object DirectMessageSearchPolicy {
    fun filter(messages: List<DirectMessage>, query: String): List<DirectMessage> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return messages

        return messages.filter { message ->
            sequenceOf(
                message.text,
                message.shareCard?.title,
                message.shareCard?.preview
            )
                .filterNotNull()
                .any { value -> normalize(value).contains(normalizedQuery) }
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale("ar"))
        .replace(ARABIC_DIACRITICS, "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .trim()

    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u0652]")
}
