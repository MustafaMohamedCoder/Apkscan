package com.masahhisabat.app.data

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** يحول أعطال المزامنة المتوقعة إلى رسائل عربية مفهومة من دون تسريب تفاصيل الشبكة الداخلية. */
internal object SyncErrorPolicy {
    fun userMessage(error: Throwable): String = when (error) {
        is UnknownHostException -> "تعذر الوصول للجهاز الآخر. تحقق من اتصال الشبكة."
        is SocketTimeoutException -> "انتهت مهلة المزامنة. ربما انقطع الاتصال أو استغرق الجهاز الآخر وقتًا طويلًا."
        is ConnectException -> "تعذر الاتصال بالجهاز الآخر. تأكد أن التطبيق مفتوح وأنكما على الشبكة نفسها."
        is EOFException -> "انقطع الاتصال قبل اكتمال المزامنة."
        is SocketException -> "انقطع اتصال الشبكة أثناء المزامنة."
        is IOException -> "لم تكتمل المزامنة بسبب استجابة غير صالحة أو اتصال غير مستقر."
        else -> "حدث خطأ غير متوقع أثناء المزامنة. أعد المحاولة."
    }
}
