package com.masahhisabat.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.masahhisabat.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مولّد تقارير محلي: يحوّل تقرير لوحة التحكم إلى صورة أو PDF بدون نقل أي بيانات خارج الجهاز.
 * يُحفظ الملف في الصور أو المستندات العامة كي يظل متاحاً للمشاركة بعد إغلاق التطبيق.
 */
object LocalReportExporter {
    private const val IMAGE_WIDTH = 1440
    private const val IMAGE_HEIGHT = 1840
    private const val OUTER_MARGIN = 92f

    fun exportReportImage(context: Context, report: DashboardAnalytics.PeriodReport): Uri {
        val bitmap = renderImage(report)
        return try {
            writeImage(context, reportFileName(report, "jpg")) { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) { "تعذر ضغط صورة التقرير" }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun exportReportPdf(context: Context, report: DashboardAnalytics.PeriodReport): Uri {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(1240, 1754, 1).create()
            val page = document.startPage(pageInfo)
            drawReport(page.canvas, report, pageInfo.pageWidth, pageInfo.pageHeight)
            document.finishPage(page)
            return writePdf(context, reportFileName(report, "pdf")) { output -> document.writeTo(output) }
        } finally {
            document.close()
        }
    }

    private fun renderImage(report: DashboardAnalytics.PeriodReport): Bitmap =
        Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawReport(Canvas(bitmap), report, bitmap.width, bitmap.height)
        }

    private fun drawReport(canvas: Canvas, report: DashboardAnalytics.PeriodReport, width: Int, height: Int) {
        val scale = width / IMAGE_WIDTH.toFloat()
        canvas.drawColor(Color.rgb(247, 252, 252))
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 118, 110) }
        canvas.drawRect(0f, 0f, width.toFloat(), 270f * scale, headerPaint)

        fun textPaint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size * scale
            textAlign = Paint.Align.RIGHT
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        val title = textPaint(48f, Color.WHITE, true)
        val subTitle = textPaint(25f, 0xFFD1FAE5.toInt())
        canvas.drawText("تقرير ماسح الحسابات", width - OUTER_MARGIN * scale, 108f * scale, title)
        canvas.drawText("${report.period.label}  •  ${SimpleDateFormat("dd/MM/yyyy", Locale("ar")).format(Date())}", width - OUTER_MARGIN * scale, 168f * scale, subTitle)
        canvas.drawText("تقرير محلي — لا تُرسل البيانات إلى الإنترنت", width - OUTER_MARGIN * scale, 220f * scale, subTitle)

        val labelPaint = textPaint(28f, 0xFF466579.toInt())
        val valuePaint = textPaint(42f, 0xFF092338.toInt(), true)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            color = 0x3314B8A6
        }
        val cardWidth = (width - (OUTER_MARGIN * 2 + 26) * scale) / 2f
        fun metricCard(right: Float, top: Float, label: String, value: String) {
            val left = right - cardWidth
            val bottom = top + 180f * scale
            canvas.drawRoundRect(left, top, right, bottom, 30f * scale, 30f * scale, cardPaint)
            canvas.drawRoundRect(left, top, right, bottom, 30f * scale, 30f * scale, borderPaint)
            canvas.drawText(label, right - 24f * scale, top + 56f * scale, labelPaint)
            canvas.drawText(value, right - 24f * scale, top + 124f * scale, valuePaint)
        }

        var y = 326f * scale
        val right = width - OUTER_MARGIN * scale
        metricCard(right, y, "العناصر المسجلة", report.itemCount.toString())
        metricCard(right - cardWidth - 26f * scale, y, "المجموعات النشطة", report.activeGroups.toString())
        y += 206f * scale
        metricCard(right, y, "متوسط النشاط", "${"%.1f".format(Locale.US, report.dailyAverage)} / يوم")
        metricCard(right - cardWidth - 26f * scale, y, "محتوى مصوّر", "${report.imagePercent}% صور")
        y += 246f * scale

        canvas.drawText("القيم المالية المسجلة", right, y, textPaint(34f, 0xFF0F766E.toInt(), true))
        y += 52f * scale
        val values = report.amountsByCurrency.ifEmpty { listOf(DashboardAnalytics.MoneyBucket(null, 0.0)) }
        values.take(4).forEach { bucket ->
            val value = if (bucket.amount == 0.0 && report.amountsByCurrency.isEmpty()) "لا توجد قيم مسجلة" else {
                "${DashboardAnalytics.formatAmount(bucket.amount)}  ${bucket.currency ?: "عملة غير محددة"}"
            }
            canvas.drawText(value, right, y, textPaint(29f, 0xFF092338.toInt(), true))
            y += 48f * scale
        }
        y += 36f * scale
        canvas.drawText("ملخص النشاط", right, y, textPaint(34f, 0xFF0F766E.toInt(), true))
        y += 54f * scale
        val activityLines = listOf(
            report.topGroupName?.let { "المجموعة الأكثر نشاطاً: $it (${report.topGroupItems})" } ?: "لا توجد مجموعة نشطة ضمن الفترة",
            report.topSender?.let { "المستخدم الأكثر إضافة: $it (${report.topSenderItems})" } ?: "لا توجد بيانات مستخدمين ضمن الفترة",
            "العمليات المسجلة: ${report.actionCount}",
            "الصور: ${report.imageCount}  |  النصوص: ${report.textCount}"
        )
        activityLines.forEach { line ->
            canvas.drawText(line, right, y, textPaint(28f, 0xFF466579.toInt()))
            y += 48f * scale
        }
        canvas.drawText("تم الإنشاء بواسطة ماسح الحسابات", right, (height - 70f * scale).coerceAtLeast(y + 30f * scale), textPaint(23f, 0xFF608091.toInt()))
    }

    private fun reportFileName(report: DashboardAnalytics.PeriodReport, extension: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "MasahHisabat_Report_${report.period.days}d_$timestamp.$extension"
    }

    private fun writeImage(context: Context, name: String, write: (OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MasahHisabat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            return writeMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, write)
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MasahHisabat")
        return writeLegacy(context, directory, name, write)
    }

    private fun writePdf(context: Context, name: String, write: (OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/MasahHisabat")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return writeMediaStore(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI, values, write)
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MasahHisabat")
        return writeLegacy(context, directory, name, write)
    }

    private fun writeMediaStore(context: Context, collection: Uri, values: ContentValues, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("تعذر إنشاء ملف التصدير")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("تعذر فتح ملف التصدير")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun writeLegacy(context: Context, directory: File, name: String, write: (OutputStream) -> Unit): Uri {
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد التصدير")
        val file = File(directory, name)
        FileOutputStream(file).use(write)
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }
}
