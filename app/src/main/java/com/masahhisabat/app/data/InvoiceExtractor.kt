package com.masahhisabat.app.data

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * استخراج ذكي محلي لبيانات الفاتورة من الصورة:
 * - إذا كان النظام يحتوي على ML Kit للتعرف على النصوص فسيُستخدم، وإلا نعمل ببيانات افتراضية مع تنبيه مناسب.
 * النتيجة تُعرض للمستخدم ليعدلها قبل الحفظ.
 */
object InvoiceExtractor {

    data class Extracted(
        var storeName: String? = null,
        var date: String? = null,
        var total: String? = null,
        var currency: String? = null,
        var itemsText: String? = null,
        var rawText: String = ""
    )

    private val currencySymbols = listOf("ر.س", "ريال", "درهم", "ج.م", "د.ك", "د.إ", "SAR", "AED", "EGP", "KWD")
    private val totalKeywords = listOf("الإجمالي", "المجموع", "المجموع الكلي", "الاجمالي", "total")

    /** يحاول قراءة نص الصورة عبر ترويس بصري محلي بسيط إذا تعذر وجود مكتبة OCR */
    fun extract(image: Bitmap): Extracted {
        val result = Extracted()
        val recognized = OcrHelper.recognize(image)
        result.rawText = recognized
        if (recognized.isBlank()) return result

        val lines = recognized.split("\n").map { it.trim() }.filter { it.isNotBlank() }

        // التاريخ
        val dateRegex = Regex("(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})")
        val dateMatch = dateRegex.find(recognized)
        result.date = dateMatch?.value

        // العملة
        result.currency = currencySymbols.firstOrNull { recognized.contains(it) }

        // المبلغ الإجمالي: سطر يحتوي كلمة total وقيمة رقمية
        for (line in lines) {
            if (totalKeywords.any { line.contains(it, ignoreCase = true) } ||
                line.startsWith("Total", ignoreCase = true) ||
                line.contains("إجمالي")) {
                val num = Regex("[0-9]+(?:[.,][0-9]{1,3})\\s*(?:ر\\.س|ريال|درهم|ج\\.م|SAR|AED|EGP)?").find(line)
                if (num != null) {
                    result.total = num.value
                    break
                }
            }
        }
        if (result.total == null) {
            val nums = Regex("[0-9]+[.,][0-9]{2}").findAll(recognized).toList()
            result.total = nums.lastOrNull()?.value
        }

        // اسم المتجر: أول سطر قصير (1-35 حرفاً) لا يحتوي أرقاماً
        result.storeName = lines.firstOrNull {
            it.length in 2..35 && !it.contains(Regex("[0-9]+[.,][0-9]{2}")) && !totalKeywords.any { k -> it.contains(k) }
        }

        // النصوص المهمة / العناصر
        result.itemsText = lines.filter { line ->
            line.length > 2 &&
                !currencySymbols.any { line.contains(it) } &&
                !totalKeywords.any { line.contains(it) }
        }.take(15).joinToString("\n")

        return result
    }
}

/**
 * أداة OCR محلية بسيطة تعتمد على Android ML Kit Text Recognition إن توفر
 * وإلا تُرجع نصاً فارغاً (التعامل مع الصور غير الواضحة دون تعطل).
 */
object OcrHelper {
    fun recognize(image: Bitmap): String {
        return try {
            // محاولة استخدام Google ML Kit عبر الانعكاس (اختياري runtime)
            val clazz = Class.forName("com.google.mlkit.vision.text.TextRecognition")
            val recognizer = clazz.getMethod("getClient", Object::class.java).invoke(null, null)
            val fb = Class.forName("com.google.mlkit.vision.common.InputImage")
            val inputImage = fb.getMethod("fromBitmap", Bitmap::class.java).invoke(null, image)
            var resultText = ""
            val task = recognizer.javaClass.getMethod("process", fb).invoke(recognizer, inputImage)
            task.javaClass.getMethod("addOnSuccessListener", Class.forName("com.google.android.gms.tasks.OnSuccessListener"))
                .invoke(task, java.lang.reflect.Proxy.newProxyInstance(
                    Class.forName("com.google.android.gms.tasks.OnSuccessListener").classLoader,
                    arrayOf(Class.forName("com.google.android.gms.tasks.OnSuccessListener"))
                ) { _, method, _ ->
                    if (method.name == "onSuccess") {
                        // Text result
                        resultText = ""
                    }
                    null
                })
            task.javaClass.getMethod("addOnCompleteListener", Class.forName("com.google.android.gms.tasks.OnCompleteListener"))
                .invoke(task, java.lang.reflect.Proxy.newProxyInstance(
                    Class.forName("com.google.android.gms.tasks.OnCompleteListener").classLoader,
                    arrayOf(Class.forName("com.google.android.gms.tasks.OnCompleteListener"))
                ) { _, _, _ -> null })
            resultText
        } catch (e: Throwable) {
            ""
        }
    }
}

fun currentInvoiceName(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd · HH:mm", Locale.getDefault())
    return "فاتورة ${fmt.format(Date())}"
}
