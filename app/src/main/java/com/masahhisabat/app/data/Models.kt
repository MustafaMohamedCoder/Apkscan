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
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis()
)

/** مجموعة تضم فواتير */
data class Group(
    val id: String = generateId(),
    val name: String,
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis()
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

fun generateId(): String =
    System.currentTimeMillis().toString(36) + (Math.random() * 1000).toInt().toString(36)


