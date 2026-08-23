package com.masahhisabat.app.ui.invoice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceExtractionRequestGateTest {

    @Test
    fun `rejects extraction result after screen invalidates request`() {
        val gate = InvoiceExtractionRequestGate()
        val requestId = gate.begin()

        gate.invalidate()

        assertFalse(gate.accepts(requestId))
    }

    @Test
    fun `accepts only the latest extraction request`() {
        val gate = InvoiceExtractionRequestGate()
        val firstRequest = gate.begin()
        val latestRequest = gate.begin()

        assertFalse(gate.accepts(firstRequest))
        assertTrue(gate.accepts(latestRequest))
    }
}
