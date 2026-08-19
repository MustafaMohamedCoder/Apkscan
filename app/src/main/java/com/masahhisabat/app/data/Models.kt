package com.masahhisabat.app.data

import com.google.gson.annotations.SerializedName

/** أدوار المستخدمين */
enum class Role(val label: String) {
    ADMIN("مدير أساسي"),
    SUPERVISOR("مشرف"),
    EDITOR("محرر"),
    VIEWER("مشاهد");
}

/** مستخدم محلي */
data class User(
    val username: String,
    @SerializedName("password_hash") val passwordHash: String,
    val role: Role,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("enabled") val enabled: Boolean = true
)

/** عنصر داخل المجموعة: صورة مستخرجة أو نص يدوي */
data class InvoiceItem(
    val id: String = generateId(),
    val type: String = "image", // image | text
    val imagePath: String? = null,
    val text: String? = null,
    @SerializedName("processed_path") val processedPath: String? = null,
    // البيانات المستخرجة
    @SerializedName("store_name") val storeName: String? = null,
    val date: String? = null,
    val total: String? = null,
    val currency: String? = null,
    @SerializedName("items_text") val itemsText: String? = null,
    @SerializedName("sender") val sender: String? = null, // اسم المستخدم الذي أرسل الرسالة
    @SerializedName("seen") val seen: Boolean = false, // true عندما يراها مستخدمون آخرون
    /** new | in_review | completed | paid. تحافظ القيمة الافتراضية على توافق الفواتير القديمة. */
    val status: String = "new",
    /** وسوم محلية تُستخدم في البحث والفلترة دون اتصال. */
    val tags: List<String> = emptyList(),
    @SerializedName("reminder_at") val reminderAt: Long? = null,
    @SerializedName("reminder_notified_at") val reminderNotifiedAt: Long? = null,
    /** نص مستخرج محلياً اختياري يدعم البحث والنسخ من المستند. */
    @SerializedName("document_text") val documentText: String? = null,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis()
)

/** تاجر أو مورد يضم أرشيف فواتيره وطلباته. بقي الاسم الفني Group متوافقاً مع بيانات الإصدارات السابقة. */
data class Group(
    val id: String = generateId(),
    val name: String,
    @SerializedName("supplier_phone") val supplierPhone: String? = null,
    @SerializedName("supplier_notes") val supplierNotes: String? = null,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("archived_at") val archivedAt: Long? = null
)

/** عنصر محفوظ في سلة المحذوفات المحلية حتى يستعيده المستخدم أو يحذفه نهائيًا. */
data class TrashEntry(
    val id: String = generateId(),
    /** group | item */
    val type: String,
    @SerializedName("group_id") val groupId: String,
    @SerializedName("group_name") val groupName: String,
    /** بيانات المجموعة مطلوبة فقط عندما يكون النوع group. */
    val group: Group? = null,
    /** رسالة واحدة أو كل رسائل المجموعة المحذوفة. */
    val items: List<InvoiceItem> = emptyList(),
    @SerializedName("deleted_by") val deletedBy: String? = null,
    @SerializedName("deleted_at") val deletedAt: Long = System.currentTimeMillis(),
    /** trashed | restored | purged. تُرسل الحالة بين الأجهزة لحل تعارض الحذف والاستعادة. */
    val state: String = "trashed",
    @SerializedName("state_changed_at") val stateChangedAt: Long = deletedAt
)

/** سجل نشاط المستخدم */
data class ActivityEntry(
    @SerializedName("user") val user: String,
    val action: String,
    @SerializedName("at") val at: Long = System.currentTimeMillis()
)

/** سجل مزامنة */
data class SyncEntry(
    val action: String,
    val detail: String,
    val success: Boolean,
    @SerializedName("at") val at: Long = System.currentTimeMillis()
)

/** حالة آخر اتصال معروف بجهاز على الشبكة المحلية. */
data class SyncDeviceStatus(
    val address: String,
    val name: String,
    @SerializedName("last_seen_at") val lastSeenAt: Long = System.currentTimeMillis(),
    @SerializedName("last_sync_at") val lastSyncAt: Long? = null,
    @SerializedName("last_sync_success") val lastSyncSuccess: Boolean? = null,
    @SerializedName("last_error") val lastError: String? = null
)

/** سجل مراجعة لتعارض حُلّ آلياً وفق سياسة «الأحدث مع احتفاظ بنسخة وقائية». */
data class SyncConflict(
    val id: String = generateId(),
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("device_name") val deviceName: String,
    val resolution: String,
    @SerializedName("at") val at: Long = System.currentTimeMillis()
)

fun generateId(): String =
    System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt().toString(36)
