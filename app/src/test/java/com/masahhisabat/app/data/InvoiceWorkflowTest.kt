package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** تحمي الحالات المعتمدة في الوارد من قيم مزامنة قديمة أو تالفة. */
class InvoiceWorkflowTest {

    @Test
    fun canonicalStatus_keepsKnownStatusesAfterWhitespaceNormalization() {
        assertEquals(InvoiceWorkflow.PAID, InvoiceWorkflow.canonicalStatus("  paid  "))
        assertEquals(InvoiceWorkflow.IN_REVIEW, InvoiceWorkflow.canonicalStatus("in_review"))
    }

    @Test
    fun canonicalStatus_fallsBackToNewForUnknownOrBlankStatuses() {
        assertEquals(InvoiceWorkflow.NEW, InvoiceWorkflow.canonicalStatus("archived"))
        assertEquals(InvoiceWorkflow.NEW, InvoiceWorkflow.canonicalStatus("   "))
        assertEquals(InvoiceWorkflow.NEW, InvoiceWorkflow.canonicalStatus(null))
    }
}
