package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingSyncSafetyPolicyTest {

    @Test
    fun acceptsGeneratedIdsAndRejectsPathChangingIds() {
        assertEquals("m4zy8j4-abc_12", GroupStorageSafetyPolicy.safeDirectoryName("m4zy8j4-abc_12"))
        assertNull(GroupStorageSafetyPolicy.safeDirectoryName("../outside"))
        assertNull(GroupStorageSafetyPolicy.safeDirectoryName("group/child"))
        assertNull(StorageKeySafetyPolicy.safePart("../../outside"))
        assertNull(StorageKeySafetyPolicy.safePart("item\\child"))
    }

    @Test
    fun rejectsBlankNamesWhenAnIncomingItemWouldCreateItsGroup() {
        assertFalse(SyncPayloadSafetyPolicy.canCreateGroupForItem(""))
        assertFalse(SyncPayloadSafetyPolicy.canCreateGroupForItem("   "))
        assertTrue(SyncPayloadSafetyPolicy.canCreateGroupForItem("  متجر النور  "))
    }
}
