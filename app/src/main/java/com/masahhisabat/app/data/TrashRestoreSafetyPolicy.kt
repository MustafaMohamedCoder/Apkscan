package com.masahhisabat.app.data

/** تحمي استعادة المجموعات من إظهار مجموعة لا يمكن قراءة عناصرها بعد الاستعادة. */
object TrashRestoreSafetyPolicy {
    fun canExposeRestoredGroup(itemsPersisted: Boolean): Boolean = itemsPersisted
}
