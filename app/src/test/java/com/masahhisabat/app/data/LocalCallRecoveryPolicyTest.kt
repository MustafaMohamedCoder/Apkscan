package com.masahhisabat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** يثبت أن فقد الاتصال العابر لا ينهي المكالمة فورًا، وأن الاستعادة لا تحيي مكالمة أُنهيت صراحةً. */
class LocalCallRecoveryPolicyTest {
    @Test
    fun transientDisconnectStartsOneVisibleRecoveryWindow() {
        val decision = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.ACTIVE,
            LocalCallRecoveryPolicy.Event.CONNECTION_DISCONNECTED
        )

        assertEquals(LocalCallRecoveryPolicy.State.RECOVERING, decision.state)
        assertTrue(decision.startsRecoveryWindow)
        assertEquals("انقطع الاتصال مؤقتًا — جارٍ استعادته داخل الشبكة المحلية…", decision.userMessage)
        assertFalse(decision.isTerminal)
    }

    @Test
    fun reconnectCancelsWindowAndReturnsToActiveCall() {
        val decision = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.RECOVERING,
            LocalCallRecoveryPolicy.Event.CONNECTION_CONNECTED
        )

        assertEquals(LocalCallRecoveryPolicy.State.ACTIVE, decision.state)
        assertTrue(decision.cancelsRecoveryWindow)
        assertEquals("عاد الاتصال المحلي — استمرت المكالمة.", decision.userMessage)
    }

    @Test
    fun timeoutExposesManualRetryInsteadOfEndingTheCall() {
        val decision = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.RECOVERING,
            LocalCallRecoveryPolicy.Event.RECOVERY_TIMEOUT
        )

        assertEquals(LocalCallRecoveryPolicy.State.RETRY_AVAILABLE, decision.state)
        assertTrue(decision.allowsManualRetry)
        assertEquals("لم يعد الاتصال بعد. تحقق من الشبكة المحلية ثم اضغط إعادة المحاولة.", decision.userMessage)
        assertFalse(decision.isTerminal)
    }

    @Test
    fun failedAutomaticRecoveryKeepsManualRetryAvailable() {
        val decision = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.RECOVERING,
            LocalCallRecoveryPolicy.Event.RECOVERY_FAILED
        )

        assertEquals(LocalCallRecoveryPolicy.State.RETRY_AVAILABLE, decision.state)
        assertTrue(decision.cancelsRecoveryWindow)
        assertTrue(decision.allowsManualRetry)
        assertFalse(decision.isTerminal)
    }

    @Test
    fun explicitUserEndCannotBeResurrectedByLateConnectionCallback() {
        val ended = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.RECOVERING,
            LocalCallRecoveryPolicy.Event.USER_ENDED
        )
        val lateConnected = LocalCallRecoveryPolicy.transition(
            ended.state,
            LocalCallRecoveryPolicy.Event.CONNECTION_CONNECTED
        )

        assertEquals(LocalCallRecoveryPolicy.State.TERMINATED, ended.state)
        assertEquals(LocalCallRecoveryPolicy.State.TERMINATED, lateConnected.state)
        assertFalse(lateConnected.startsRecoveryWindow)
    }

    @Test
    fun terminalNetworkFailureOffersFreshCallOnlyAfterEndingTheOldSession() {
        val decision = LocalCallRecoveryPolicy.transition(
            LocalCallRecoveryPolicy.State.RETRY_AVAILABLE,
            LocalCallRecoveryPolicy.Event.TERMINAL_FAILURE
        )

        assertEquals(LocalCallRecoveryPolicy.State.TERMINATED, decision.state)
        assertTrue(decision.cancelsRecoveryWindow)
        assertTrue(decision.isTerminal)
        assertTrue(decision.allowsFreshCall)
    }
}
