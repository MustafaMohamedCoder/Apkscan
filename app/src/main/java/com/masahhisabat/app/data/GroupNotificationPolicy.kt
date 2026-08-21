package com.masahhisabat.app.data

import java.util.Locale

/**
 * يفصل سياسة عدّاد إشعارات المجموعات عن التخزين والمزامنة كي لا تُحتسب
 * رسالة المستخدم نفسه أو نسخة إشعار المجموعة التي تصل مع الحمولة المتزامنة مرتين.
 */
object GroupNotificationPolicy {
    fun shouldCreateUnreadEvent(sender: String?, activeUsername: String?): Boolean {
        val active = activeUsername.normalized()
        return active.isNotBlank() && sender.normalized() != active
    }

    fun shouldImportSyncedEvent(type: String): Boolean = type != GROUP_MESSAGE_TYPE

    private fun String?.normalized(): String = orEmpty().trim().lowercase(Locale.ROOT)

    private const val GROUP_MESSAGE_TYPE = "group_message"
}
