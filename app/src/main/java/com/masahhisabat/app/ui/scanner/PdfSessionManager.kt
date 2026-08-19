package com.masahhisabat.app.ui.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.masahhisabat.app.data.AppRepository
import java.io.File
import java.io.FileOutputStream

/**
 * يحتفظ بجلسة الصفحات في تخزين التطبيق ويصدرها إلى PDF صفحةً صفحةً لتفادي تحميل
 * جميع الصور الكبيرة في الذاكرة في الوقت نفسه. لا تُغادر أي صورة الجهاز.
 */
object PdfSessionManager {
    private const val PREFS = "document_pdf_session"
    private const val KEY_PAGES = "page_paths"
    private const val PAGE_MARGIN = 54f
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sessionDirectory(context: Context): File =
        File(AppRepository.dataDir(context), "pdf_session").apply { mkdirs() }

    @Synchronized
    fun pageCount(context: Context): Int = storedPages(context).size

    @Synchronized
    fun appendPage(context: Context, bitmap: Bitmap): Int {
        val file = File(sessionDirectory(context), "page_${System.currentTimeMillis()}_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw IllegalStateException("تعذر حفظ صفحة المستند")
            }
        }
        val pages = storedPages(context).toMutableList().apply { add(file.absolutePath) }
        savePages(context, pages)
        return pages.size
    }

    /** يضيف الصفحة الحالية ثم ينشئ PDF في مجلد المستندات ويزيل ملفات الجلسة عند النجاح فقط. */
    @Synchronized
    fun exportCurrentSession(context: Context, currentPage: Bitmap): String {
        appendPage(context, currentPage)
        val pdf = createPdf(context, storedPages(context))
        try {
            publishToDocuments(context, pdf)
        } finally {
            pdf.delete()
        }
        clear(context)
        return "تم حفظ ملف PDF في المستندات"
    }

    @Synchronized
    fun clear(context: Context) {
        storedPages(context).forEach { path -> runCatching { File(path).delete() } }
        preferences(context).edit().remove(KEY_PAGES).apply()
        sessionDirectory(context).listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }

    private fun storedPages(context: Context): List<String> = preferences(context)
        .getStringSet(KEY_PAGES, emptySet())
        .orEmpty()
        .filter { File(it).isFile }
        .sorted()

    private fun savePages(context: Context, pages: List<String>) {
        preferences(context).edit().putStringSet(KEY_PAGES, pages.toSet()).apply()
    }

    private fun createPdf(context: Context, paths: List<String>): File {
        require(paths.isNotEmpty()) { "لا توجد صفحات لتصديرها" }
        val output = File(context.cacheDir, "masah_${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        try {
            paths.forEachIndexed { index, path ->
                val bitmap = decodeForPdf(path)
                    ?: throw IllegalStateException("تعذر قراءة إحدى صفحات المستند")
                try {
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, index + 1).create()
                    )
                    drawPage(page.canvas, bitmap)
                    document.finishPage(page)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            FileOutputStream(output).use { document.writeTo(it) }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            document.close()
        }
    }

    private fun decodeForPdf(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun drawPage(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.WHITE)
        val availableWidth = A4_WIDTH - PAGE_MARGIN * 2
        val availableHeight = A4_HEIGHT - PAGE_MARGIN * 2
        val scale = minOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (A4_WIDTH - width) / 2f
        val top = (A4_HEIGHT - height) / 2f
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun publishToDocuments(context: Context, pdf: File) {
        val name = "MasahHisabat_${System.currentTimeMillis()}.pdf"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/MasahHisabat")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء ملف PDF")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output -> pdf.inputStream().use { it.copyTo(output) } }
                    ?: throw IllegalStateException("تعذر فتح ملف PDF")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MasahHisabat")
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد المستندات")
            pdf.copyTo(File(directory, name), overwrite = true)
        }
    }
}
