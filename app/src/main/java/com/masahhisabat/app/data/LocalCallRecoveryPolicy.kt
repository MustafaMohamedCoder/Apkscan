package com.masahhisabat.app.data

/**
 * سياسة انتقالات فقد الاتصال في مكالمة محلية.
 *
 * لا تنفذ هذه الوحدة أي شبكة أو مؤقتات؛ يطبق المحرك القرار ويعرض النشاط الرسالة.
 * بذلك لا تنهي انقطاعات WebRTC العابرة المكالمة فورًا، ولا تستطيع أي إشارة متأخرة
 * إعادة إحياء مكالمة أنهى المستخدمُها صراحةً.
 */
object LocalCallRecoveryPolicy {
    const val RECOVERY_WINDOW_MS = 12_000L

    enum class State {
        ACTIVE,
        RECOVERING,
        RETRY_AVAILABLE,
        TERMINATED
    }

    enum class Event {
        CONNECTION_CONNECTED,
        CONNECTION_DISCONNECTED,
        ICE_DISCONNECTED,
        RECOVERY_TIMEOUT,
        RECOVERY_FAILED,
        MANUAL_RETRY,
        TERMINAL_FAILURE,
        USER_ENDED
    }

    data class Decision(
        val state: State,
        val userMessage: String? = null,
        val startsRecoveryWindow: Boolean = false,
        val cancelsRecoveryWindow: Boolean = false,
        val allowsManualRetry: Boolean = false,
        val allowsFreshCall: Boolean = false,
        val isTerminal: Boolean = false
    )

    fun transition(current: State, event: Event): Decision {
        if (current == State.TERMINATED) return Decision(state = State.TERMINATED)

        return when (event) {
            Event.USER_ENDED -> Decision(state = State.TERMINATED, cancelsRecoveryWindow = true, isTerminal = true)
            Event.TERMINAL_FAILURE -> Decision(
                state = State.TERMINATED,
                userMessage = "تعذر استعادة الاتصال المحلي. أنهِ الجلسة الفاشلة ثم ابدأ اتصالًا جديدًا بعد التحقق من الشبكة.",
                cancelsRecoveryWindow = true,
                allowsFreshCall = true,
                isTerminal = true
            )
            Event.CONNECTION_CONNECTED -> when (current) {
                State.RECOVERING, State.RETRY_AVAILABLE -> Decision(
                    state = State.ACTIVE,
                    userMessage = "عاد الاتصال المحلي — استمرت المكالمة.",
                    cancelsRecoveryWindow = true
                )
                else -> Decision(state = State.ACTIVE)
            }
            Event.CONNECTION_DISCONNECTED, Event.ICE_DISCONNECTED -> when (current) {
                State.ACTIVE -> Decision(
                    state = State.RECOVERING,
                    userMessage = "انقطع الاتصال مؤقتًا — جارٍ استعادته داخل الشبكة المحلية…",
                    startsRecoveryWindow = true
                )
                State.RETRY_AVAILABLE -> Decision(
                    state = State.RECOVERING,
                    userMessage = "انقطع الاتصال مجددًا — جارٍ انتظار عودة الشبكة المحلية…",
                    startsRecoveryWindow = true
                )
                else -> Decision(state = current)
            }
            Event.RECOVERY_TIMEOUT -> if (current == State.RECOVERING) {
                Decision(
                    state = State.RETRY_AVAILABLE,
                    userMessage = "لم يعد الاتصال بعد. تحقق من الشبكة المحلية ثم اضغط إعادة المحاولة.",
                    cancelsRecoveryWindow = true,
                    allowsManualRetry = true
                )
            } else {
                Decision(state = current, allowsManualRetry = current == State.RETRY_AVAILABLE)
            }
            Event.RECOVERY_FAILED -> if (current == State.RECOVERING || current == State.RETRY_AVAILABLE) {
                Decision(
                    state = State.RETRY_AVAILABLE,
                    userMessage = "تعذرت استعادة المسار تلقائيًا. تحقق من الشبكة المحلية ثم اضغط إعادة المحاولة.",
                    cancelsRecoveryWindow = true,
                    allowsManualRetry = true
                )
            } else {
                Decision(
                    state = State.TERMINATED,
                    userMessage = "تعذر إنشاء اتصال محلي. تحقق من الشبكة ثم أعد بدء المكالمة.",
                    isTerminal = true
                )
            }
            Event.MANUAL_RETRY -> if (current == State.RETRY_AVAILABLE) {
                Decision(
                    state = State.RECOVERING,
                    userMessage = "جارٍ طلب مسار اتصال محلي جديد…",
                    startsRecoveryWindow = true
                )
            } else {
                Decision(state = current, allowsManualRetry = current == State.RETRY_AVAILABLE)
            }
        }
    }
}
