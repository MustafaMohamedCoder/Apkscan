package com.masahhisabat.app.ui.messages

import com.masahhisabat.app.data.DirectMessage

/**
 * فلاتر عرض محلية للأرشيف داخل المحادثة. لا تحفظ اختيار المستخدم ولا تغيّر الرسائل أو ترتيبها.
 * الرسالة التي تحمل صورة تُعرض ضمن الصور، ولو احتوت على تعليق نصي، حتى لا تتكرر عبر أقسام الأرشيف.
 */
enum class DirectMessageArchiveFilter {
    ALL,
    TEXT,
    IMAGES,
    SHARED_ITEMS
}

object DirectMessageArchiveFilterPolicy {
    fun filter(
        messages: List<DirectMessage>,
        selectedFilter: DirectMessageArchiveFilter
    ): List<DirectMessage> = messages.filter { message ->
        when (selectedFilter) {
            DirectMessageArchiveFilter.ALL -> true
            DirectMessageArchiveFilter.TEXT -> message.text?.isNotBlank() == true &&
                message.imagePath.isNullOrBlank() && message.shareCard == null
            DirectMessageArchiveFilter.IMAGES -> message.imagePath?.isNotBlank() == true
            DirectMessageArchiveFilter.SHARED_ITEMS -> message.shareCard != null
        }
    }
}
