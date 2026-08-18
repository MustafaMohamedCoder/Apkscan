package com.masahhisabat.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import java.io.File

/** أوضاع المعالجة */
enum class ProcessMode(val key: String, val label: String) {
    ORIGINAL("original", "الأصلية"),
    AUTO("auto", "تحسين تلقائي"),
    HIGH_CONTRAST("high", "تباين عالي"),
    BW("bw", "أبيض وأسود");
}

/**
 * معالجة محلية للصور: تحسين الإضاءة والتباين، تباين عالي، أبيض وأسود.
 * تعمل في خيط خلفي بالكامل دون تجميد الواجهة.
 */
object ImageProcessor {

    /** إعداد متوازن لمرفقات المحادثات: حجم أقل مع وضوح كافٍ للمستندات والصور. */
    const val ATTACHMENT_MAX_DIM = 1280
    const val ATTACHMENT_JPEG_QUALITY = 78

    interface Callback {
        fun onDone(bitmap: Bitmap)
        fun onError()
    }

    fun process(mode: ProcessMode, src: Bitmap, callback: Callback) {
        Thread {
            try {
                val result = when (mode) {
                    ProcessMode.ORIGINAL -> src.copy(Bitmap.Config.ARGB_8888, false)
                    ProcessMode.AUTO -> enhance(src)
                    ProcessMode.HIGH_CONTRAST -> highContrast(src)
                    ProcessMode.BW -> toBlackAndWhite(src)
                }
                Handler(Looper.getMainLooper()).post { callback.onDone(result) }
            } catch (e: Throwable) {
                Handler(Looper.getMainLooper()).post { callback.onError() }
            }
        }.start()
    }

    fun processSync(mode: ProcessMode, src: Bitmap): Bitmap = when (mode) {
        ProcessMode.ORIGINAL -> src.copy(Bitmap.Config.ARGB_8888, false)
        ProcessMode.AUTO -> enhance(src)
        ProcessMode.HIGH_CONTRAST -> highContrast(src)
        ProcessMode.BW -> toBlackAndWhite(src)
    }

    fun loadBitmap(path: String, maxDim: Int = 2048): Bitmap {
        require(maxDim > 0) { "maxDim must be greater than zero" }
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IllegalArgumentException("تعذر قراءة أبعاد الصورة")
        }

        // inSampleSize يجب أن يكون قوة للعدد 2. نحسبه قبل فك الصورة بالكامل
        // حتى لا تؤدي صورة كاميرا كبيرة إلى استهلاك ذاكرة غير ضروري.
        var sample = 1
        while (maxOf(opts.outWidth / sample, opts.outHeight / sample) > maxDim) {
            sample *= 2
        }
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return requireNotNull(BitmapFactory.decodeFile(path, o2)) { "تعذر فك ترميز الصورة" }
    }

    /** تحسين تلقائي للإضاءة والتباين: تمديد المدى الديناميكي + تصحيح غاما خفيف */
    private fun enhance(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // العثور على min/max للسطوع
        var minV = 255f
        var maxV = 0f
        for (i in pixels.indices) {
            val px = pixels[i]
            val lum = 0.299f * ((px shr 16) and 255) + 0.587f * (((px shr 8) and 255)) + 0.114f * (px and 255)
            if (lum < minV) minV = lum
            if (lum > maxV) maxV = lum
        }
        val range = maxV - minV
        val gamma = 0.92f

        for (i in pixels.indices) {
            val px = pixels[i]
            var r = ((px shr 16) and 255).toFloat()
            var g = ((px shr 8) and 255).toFloat()
            var b = (px and 255).toFloat()
            if (range > 20) {
                r = ((r - minV) / range * 255f).coerceIn(0f, 255f)
                g = ((g - minV) / range * 255f).coerceIn(0f, 255f)
                b = ((b - minV) / range * 255f).coerceIn(0f, 255f)
            }
            // غاما
            r = 255f * Math.pow((r / 255f).toDouble(), gamma.toDouble()).toFloat()
            g = 255f * Math.pow((g / 255f).toDouble(), gamma.toDouble()).toFloat()
            b = 255f * Math.pow((b / 255f).toDouble(), gamma.toDouble()).toFloat()
            pixels[i] = (0xFF000000.toInt()) or
                (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** تباين عالي */
    private fun highContrast(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            var r = ((px shr 16) and 255).toFloat()
            var g = ((px shr 8) and 255).toFloat()
            var b = (px and 255).toFloat()
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val factor = 2.2f
            r = (factor * (r - 128f) + 128f).coerceIn(0f, 255f)
            g = (factor * (g - 128f) + 128f).coerceIn(0f, 255f)
            b = (factor * (b - 128f) + 128f).coerceIn(0f, 255f)
            // زيادة إشباع اللومينات
            if (lum > 190) { r = 255f; g = 255f; b = 255f }
            else if (lum < 60) { r = 10f; g = 10f; b = 12f }
            pixels[i] = (0xFF000000.toInt()) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** تحويل إلى أبيض وأسود */
    private fun toBlackAndWhite(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val lum = (0.299f * ((px shr 16) and 255) +
                0.587f * (((px shr 8) and 255)) +
                0.114f * (px and 255)).toInt()
            pixels[i] = (0xFF000000.toInt()) or (lum shl 16) or (lum shl 8) or lum
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** اقتصاص حسب إحداثيات نسبية (0..1) */
    fun cropBitmap(src: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val l = (left * src.width).toInt().coerceIn(0, src.width)
        val t = (top * src.height).toInt().coerceIn(0, src.height)
        val r = (right * src.width).toInt().coerceIn(l + 1, src.width)
        val b = (bottom * src.height).toInt().coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /**
     * الحفظ كملف JPEG. يظل مالك الـ Bitmap مسؤولاً عنه افتراضيًا؛ يمكن للمهام
     * الخلفية التي انتهت منه تمامًا اختيار تحريره فورًا لتقليل ضغط الذاكرة.
     */
    fun saveTo(
        bitmap: Bitmap,
        dir: File,
        prefix: String,
        quality: Int = ATTACHMENT_JPEG_QUALITY,
        recycleAfterSave: Boolean = false
    ): File {
        dir.mkdirs()
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
        try {
            val saved = file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it)
            }
            if (!saved || file.length() <= 0L) {
                file.delete()
                throw IllegalStateException("تعذر حفظ الصورة")
            }
            return file
        } finally {
            if (recycleAfterSave && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** نتيجة كشف الحواف كي يعرض محرر القص إطاراً مقترحاً أو بديلًا آمناً. */
    data class EdgeDetection(
        val bounds: RectF,
        val isDocumentDetected: Boolean,
        val confidence: Float
    )

    /** كشف حواف مستند محلي عبر التدرج الرمادي ومرشح Sobel دون أي خدمة خارجية. */
    fun detectDocumentEdges(src: Bitmap): EdgeDetection {
        val result = DocumentEdgeDetector.detect(src)
        return EdgeDetection(
            bounds = RectF(result.left, result.top, result.right, result.bottom),
            isDocumentDetected = result.isDocumentDetected,
            confidence = result.confidence
        )
    }

    /** توافق مع الاستدعاءات السابقة: يعيد الإطار المقترح فقط. */
    fun detectEdges(src: Bitmap): RectF = detectDocumentEdges(src).bounds

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
