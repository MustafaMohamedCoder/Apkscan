package com.masahhisabat.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يحمي الحدود الزمنية للتحذير قبل الإزالة المؤجلة من انزياح يومي أو حذف مبكر. */
class TrashRetentionPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun warning_startsAtThreeDaysBeforeDeletionAndEndsBeforeDeletion() {
        assertTrue(TrashRetentionPolicy.requiresDeletionWarning(
            now - TrashRetentionPolicy.RETENTION_MS + TrashRetentionPolicy.WARNING_WINDOW_MS,
            now
        ))
        assertFalse(TrashRetentionPolicy.requiresDeletionWarning(
            now - TrashRetentionPolicy.RETENTION_MS,
            now
        ))
    }

    @Test
    fun warning_doesNotShowBeforeItsThreeDayWindowOrForInvalidTime() {
        assertFalse(TrashRetentionPolicy.requiresDeletionWarning(
            now - TrashRetentionPolicy.RETENTION_MS + TrashRetentionPolicy.WARNING_WINDOW_MS + 1,
            now
        ))
        assertFalse(TrashRetentionPolicy.requiresDeletionWarning(0L, now))
    }

    @Test
    fun expired_entryIsDeletedAtThirtyDaysButNotOneMillisecondEarlier() {
        assertTrue(TrashRetentionPolicy.isExpired(now - TrashRetentionPolicy.RETENTION_MS, now))
        assertFalse(TrashRetentionPolicy.isExpired(now - TrashRetentionPolicy.RETENTION_MS + 1, now))
    }

    @Test
    fun invalidTimestampNeverQualifiesForPermanentDeletion() {
        assertFalse(TrashRetentionPolicy.isExpired(0L, now))
    }
}
