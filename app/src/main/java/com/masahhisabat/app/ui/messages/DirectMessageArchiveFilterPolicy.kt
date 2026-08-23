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

/** نطاقات زمنية مشتقة عند العرض فقط؛ لا تُحفظ ولا تغيّر طابع الرسالة الزمني. */
enum class DirectMessageArchiveTimeRange {
    ALL,
    LAST_24_HOURS,
    LAST_7_DAYS,
    LAST_30_DAYS
}

object DirectMessageArchiveFilterPolicy {
    fun filter(
        messages: List<DirectMessage>,
        type: DirectMessageArchiveFilter,
        timeRange: DirectMessageArchiveTimeRange = DirectMessageArchiveTimeRange.ALL,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DirectMessage> = messages.filter { message ->
        matchesType(message, type) && matchesTimeRange(message.createdAt, timeRange, nowMillis)
    }

    private fun matchesType(
        message: DirectMessage,
        type: DirectMessageArchiveFilter
    ): Boolean = when (type) {
            DirectMessageArchiveFilter.ALL -> true
            DirectMessageArchiveFilter.TEXT -> message.text?.isNotBlank() == true &&
                message.imagePath.isNullOrBlank() && message.shareCard == null
            DirectMessageArchiveFilter.IMAGES -> message.imagePath?.isNotBlank() == true
            DirectMessageArchiveFilter.SHARED_ITEMS -> message.shareCard != null
        }

    private fun matchesTimeRange(
        createdAt: Long,
        timeRange: DirectMessageArchiveTimeRange,
        nowMillis: Long
    ): Boolean {
        val durationMillis = when (timeRange) {
            DirectMessageArchiveTimeRange.ALL -> return true
            DirectMessageArchiveTimeRange.LAST_24_HOURS -> HOURS_24
            DirectMessageArchiveTimeRange.LAST_7_DAYS -> DAYS_7
            DirectMessageArchiveTimeRange.LAST_30_DAYS -> DAYS_30
        }
        return createdAt in (nowMillis - durationMillis)..nowMillis
    }

    private const val HOURS_24 = 24L * 60L * 60L * 1000L
    private const val DAYS_7 = 7L * HOURS_24
    private const val DAYS_30 = 30L * HOURS_24
}
