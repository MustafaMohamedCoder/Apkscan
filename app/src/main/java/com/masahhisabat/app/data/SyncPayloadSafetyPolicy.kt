package com.masahhisabat.app.data

/** يتحقق من الحد الأدنى للبيانات اللازمة لإنشاء مجموعة من عنصر وارد. */
object SyncPayloadSafetyPolicy {
    fun canCreateGroupForItem(groupName: String): Boolean = groupName.trim().isNotBlank()
}
