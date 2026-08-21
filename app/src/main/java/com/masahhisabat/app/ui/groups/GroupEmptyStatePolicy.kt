package com.masahhisabat.app.ui.groups

/** الرسالة التي ينبغي للواجهة عرضها عند عدم وجود بطاقات ظاهرة في قسم المجموعات. */
enum class GroupEmptyMessage {
    NO_GROUPS_WITH_CREATE_ACTION,
    NO_GROUPS_READ_ONLY,
    NO_SEARCH_AND_FILTER_RESULTS,
    NO_SEARCH_RESULTS,
    NO_FILTER_RESULTS
}

/**
 * تفصل دلالة الحالة الفارغة عن التخطيط كي لا تعرض الواجهة دعوة إنشاء لمستخدم لا يملك الصلاحية.
 */
data class GroupEmptyState(
    val message: GroupEmptyMessage,
    val canCreate: Boolean
)

object GroupEmptyStatePolicy {
    fun resolve(
        hasAnyGroups: Boolean,
        query: String,
        filterMode: String,
        canCreate: Boolean
    ): GroupEmptyState = when {
        !hasAnyGroups && canCreate -> GroupEmptyState(
            message = GroupEmptyMessage.NO_GROUPS_WITH_CREATE_ACTION,
            canCreate = true
        )
        !hasAnyGroups -> GroupEmptyState(
            message = GroupEmptyMessage.NO_GROUPS_READ_ONLY,
            canCreate = false
        )
        query.isNotBlank() && filterMode != "all" -> GroupEmptyState(
            message = GroupEmptyMessage.NO_SEARCH_AND_FILTER_RESULTS,
            canCreate = false
        )
        query.isNotBlank() -> GroupEmptyState(
            message = GroupEmptyMessage.NO_SEARCH_RESULTS,
            canCreate = false
        )
        else -> GroupEmptyState(
            message = GroupEmptyMessage.NO_FILTER_RESULTS,
            canCreate = false
        )
    }
}
