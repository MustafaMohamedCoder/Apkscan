package com.masahhisabat.app.data

/** بطاقة مشاركة محلية تحفظ المرجع بدلاً من نسخ الصور والمستندات بين المسارات. */
object DirectSharePolicy {
    enum class Kind { GROUP_MESSAGE, GROUP }

    data class Payload(
        val kind: Kind,
        val sourceGroupId: String,
        val sourceItemId: String? = null,
        val title: String,
        val preview: String,
        val imagePath: String? = null
    )

    fun fromItem(group: Group, item: InvoiceItem): Payload = Payload(
        kind = Kind.GROUP_MESSAGE,
        sourceGroupId = group.id,
        sourceItemId = item.id,
        title = group.name,
        preview = item.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: when {
                !item.documentText.isNullOrBlank() -> item.documentText.trim().take(160)
                !item.processedPath.isNullOrBlank() || !item.imagePath.isNullOrBlank() -> "صورة أو مستند من المجموعة"
                else -> "رسالة من المجموعة"
            },
        imagePath = item.processedPath ?: item.imagePath
    )

    fun fromGroup(group: Group, itemCount: Int): Payload = Payload(
        kind = Kind.GROUP,
        sourceGroupId = group.id,
        title = group.name,
        preview = "تضم ${itemCount.coerceAtLeast(0)} رسائل ومستندات"
    )
}
