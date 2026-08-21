package com.masahhisabat.app.data

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/** يثبت أن الأعطال المحلية المتوقعة تبقى مفهومة ومحددة عند انقطاع المزامنة. */
class SyncErrorPolicyTest {
    @Test
    fun mapsConnectionAndNameFailuresToActionableArabicMessages() {
        assertEquals(
            "تعذر الوصول للجهاز الآخر. تحقق من اتصال الشبكة.",
            SyncErrorPolicy.userMessage(UnknownHostException())
        )
        assertEquals(
            "تعذر الاتصال بالجهاز الآخر. تأكد أن التطبيق مفتوح وأنكما على الشبكة نفسها.",
            SyncErrorPolicy.userMessage(ConnectException())
        )
    }

    @Test
    fun mapsTimeoutAndInterruptedRepliesWithoutLeakingInternalDetails() {
        assertEquals(
            "انتهت مهلة المزامنة. ربما انقطع الاتصال أو استغرق الجهاز الآخر وقتًا طويلًا.",
            SyncErrorPolicy.userMessage(SocketTimeoutException())
        )
        assertEquals("انقطع الاتصال قبل اكتمال المزامنة.", SyncErrorPolicy.userMessage(EOFException()))
    }

    @Test
    fun keepsSocketAndMalformedResponseFailuresDistinct() {
        assertEquals("انقطع اتصال الشبكة أثناء المزامنة.", SyncErrorPolicy.userMessage(SocketException()))
        assertEquals(
            "لم تكتمل المزامنة بسبب استجابة غير صالحة أو اتصال غير مستقر.",
            SyncErrorPolicy.userMessage(IOException())
        )
    }

    @Test
    fun mapsUnexpectedFailureToSafeRetryGuidance() {
        assertEquals(
            "حدث خطأ غير متوقع أثناء المزامنة. أعد المحاولة.",
            SyncErrorPolicy.userMessage(IllegalStateException())
        )
    }
}
