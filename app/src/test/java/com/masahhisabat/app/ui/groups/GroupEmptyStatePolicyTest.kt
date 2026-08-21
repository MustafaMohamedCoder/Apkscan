package com.masahhisabat.app.ui.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يحمي دلالة الحالة الفارغة من دعوة إنشاء لا يستطيع المستخدم تنفيذها. */
class GroupEmptyStatePolicyTest {

    @Test
    fun noGroups_offersCreateOnlyToUsersWhoCanManage() {
        val managerState = GroupEmptyStatePolicy.resolve(
            hasAnyGroups = false,
            query = "",
            filterMode = "all",
            canCreate = true
        )
        val readOnlyState = GroupEmptyStatePolicy.resolve(
            hasAnyGroups = false,
            query = "",
            filterMode = "all",
            canCreate = false
        )

        assertEquals(GroupEmptyMessage.NO_GROUPS_WITH_CREATE_ACTION, managerState.message)
        assertTrue(managerState.canCreate)
        assertEquals(GroupEmptyMessage.NO_GROUPS_READ_ONLY, readOnlyState.message)
        assertFalse(readOnlyState.canCreate)
    }

    @Test
    fun searchAndFilterStates_neverOfferCreateAction() {
        val state = GroupEmptyStatePolicy.resolve(
            hasAnyGroups = true,
            query = "مورد غير موجود",
            filterMode = "pinned",
            canCreate = true
        )

        assertEquals(GroupEmptyMessage.NO_SEARCH_AND_FILTER_RESULTS, state.message)
        assertFalse(state.canCreate)
    }
}
