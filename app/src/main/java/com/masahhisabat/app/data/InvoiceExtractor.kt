package com.masahhisabat.app.data

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * استخراج ذكي محلي لبيانات الفاتورة من الصورة:
 * - يستخدم محرك Tesseract محلياً مع نموذج اللغة العربية المضمّن في التطبيق.
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

    /** يقرأ نص الصورة محلياً ثم يستخرج الحقول المقترحة منها. */
    fun extract(context: Context, image: Bitmap): Extracted {
        val result = Extracted()
        val recognized = OcrHelper.recognize(context, image)
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
 * محرك OCR محلي يعتمد Tesseract. تُنسخ بيانات اللغة إلى مساحة التطبيق الخاصة
 * في أول استخدام فقط، لذلك يظل التنفيذ متوافقاً مع Android 10+ ودون شبكة.
 */
object OcrHelper {
    private const val LANGUAGE = "ara"
    private const val ASSET_MODEL = "tessdata/ara.traineddata"

    @Synchronized
    fun recognize(context: Context, image: Bitmap): String {
        val root = File(context.filesDir, "tesseract")
        return runCatching {
            ensureLanguageModel(context, root)
            val tess = TessBaseAPI()
            try {
                check(tess.init(root.absolutePath, LANGUAGE)) { "تعذر تهيئة قراءة النص المحلية" }
                tess.setImage(image)
                tess.getUTF8Text().orEmpty().trim()
            } finally {
                tess.recycle()
            }
        }.getOrDefault("")
    }

    private fun ensureLanguageModel(context: Context, root: File) {
        val model = File(root, ASSET_MODEL)
        if (model.exists() && model.length() > 0L) return
        model.parentFile?.mkdirs()
        context.assets.open(ASSET_MODEL).use { input ->
            model.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

fun currentInvoiceName(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd · HH:mm", Locale.getDefault())
    return "فاتورة ${fmt.format(Date())}"
}
