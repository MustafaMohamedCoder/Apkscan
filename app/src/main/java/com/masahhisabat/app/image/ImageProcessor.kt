package com.masahhisabat.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import java.io.File

/** أوضاع المعالجة */
enum class ProcessMode(val key: String, val label: String) {
    ORIGINAL("original", "الأصلية"),
    AUTO("auto", "تحسين تلقائي"),
    MAGIC_COLOR("magic_color", "Magic Color"),
    LOW_LIGHT("low_light", "إضاءة ضعيفة"),
    DOCUMENT("document", "تباين المستند"),
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
                    ProcessMode.AUTO -> enhanceAutomatically(src)
                    ProcessMode.MAGIC_COLOR -> magicColor(src)
                    ProcessMode.LOW_LIGHT -> correctLowLight(src)
                    ProcessMode.DOCUMENT -> documentContrast(src)
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
        ProcessMode.AUTO -> enhanceAutomatically(src)
        ProcessMode.MAGIC_COLOR -> magicColor(src)
        ProcessMode.LOW_LIGHT -> correctLowLight(src)
        ProcessMode.DOCUMENT -> documentContrast(src)
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

    /**
     * اختيار تحسين متوازن بعد قياس متوسط الإضاءة. الصور الداكنة تستفيد من تصحيح
     * محلي أقوى، أما المستندات الطبيعية فتستخدم تباينًا محافظًا يحافظ على ألوانها.
     */
    private fun enhanceAutomatically(src: Bitmap): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        var sum = 0L
        for (pixel in pixels) sum += luminance(pixel).toLong()
        val mean = sum.toFloat() / pixels.size.coerceAtLeast(1)
        return if (mean < 132f) correctLowLight(src) else magicColor(src)
    }

    /**
     * وضع Magic Color: تسوية موضعية لخلفية الورق ثم توازن بسيط للصبغة اللونية.
     * لا يستبدل الصورة الأصلية؛ بل ينتج نسخة معاينة/حفظ مستقلة بعد تصحيح المنظور.
     */
    private fun magicColor(src: Bitmap): Bitmap {
        val flattened = adaptiveDocumentEnhance(
            src = src,
            targetLuminance = 182f,
            illuminationStrength = 0.66f,
            contrast = 1.16f,
            gamma = 0.90f
        )
        return neutralizePaperTint(flattened)
    }

    /** يقلل اصفرار الورق أو ازرقاق الظلال اعتمادًا على مناطق الخلفية الساطعة منخفضة التشبع. */
    private fun neutralizePaperTint(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        // أخذ عينة كل بكسلين يقلل وقت التنفيذ مع المحافظة على استقرار التوازن.
        for (i in pixels.indices step 2) {
            val px = pixels[i]
            val r = (px shr 16) and 255
            val g = (px shr 8) and 255
            val b = px and 255
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            val lum = luminance(px)
            if (lum > 145f && maxChannel - minChannel < 66) {
                sumR += r.toLong()
                sumG += g.toLong()
                sumB += b.toLong()
                count++
            }
        }

        if (count < 24) return bmp
        val avgR = sumR.toFloat() / count
        val avgG = sumG.toFloat() / count
        val avgB = sumB.toFloat() / count
        val neutral = (avgR + avgG + avgB) / 3f
        // نطاق محافظ حتى لا تتغير ألوان الأختام أو الصور داخل المستند بقوة.
        val scaleR = (neutral / avgR.coerceAtLeast(1f)).coerceIn(0.86f, 1.14f)
        val scaleG = (neutral / avgG.coerceAtLeast(1f)).coerceIn(0.86f, 1.14f)
        val scaleB = (neutral / avgB.coerceAtLeast(1f)).coerceIn(0.86f, 1.14f)

        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (((px shr 16) and 255) * scaleR).toInt().coerceIn(0, 255)
            val g = (((px shr 8) and 255) * scaleG).toInt().coerceIn(0, 255)
            val b = ((px and 255) * scaleB).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * تصحيح الإضاءة الضعيفة أو غير المتجانسة محليًا، دون أي مكتبة أو خدمة خارجية.
     * يُحسب متوسط الإضاءة لكل بلاطة من الصورة ثم تُصحح البكسلات بالنسبة إليه، مما
     * يقلل الظلال الموضعية دون تحويل النص إلى كتلة سوداء أو تضخيم الضوضاء بشدة.
     */
    private fun correctLowLight(src: Bitmap): Bitmap = adaptiveDocumentEnhance(
        src = src,
        targetLuminance = 164f,
        illuminationStrength = 0.72f,
        contrast = 1.24f,
        gamma = 0.78f
    )

    /** فلتر مخصص للمستندات: يوازن الخلفية الورقية ويعطي النصوص تباينًا أوضح. */
    private fun documentContrast(src: Bitmap, strength: Float = 0.64f): Bitmap = adaptiveDocumentEnhance(
        src = src,
        targetLuminance = 174f,
        illuminationStrength = strength.coerceIn(0f, 1f),
        contrast = 1.36f,
        gamma = 0.90f
    )

    /**
     * توازن محلي للإضاءة مع تباين ناعم. تقسيم الصورة إلى بلاطات صغيرة يحقق نتيجة
     * قريبة من تصحيح الخلفية مع ذاكرة محدودة، وهو مناسب لصور الكاميرا الكبيرة.
     */
    private fun adaptiveDocumentEnhance(
        src: Bitmap,
        targetLuminance: Float,
        illuminationStrength: Float,
        contrast: Float,
        gamma: Float
    ): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // بلاطات بعرض يقارب 96px: حجم ثابت قليل الذاكرة حتى مع صور التابلت الكبيرة.
        val columns = (w / 96).coerceIn(4, 28)
        val rows = (h / 96).coerceIn(4, 28)
        val sums = LongArray(columns * rows)
        val counts = IntArray(columns * rows)
        for (y in 0 until h) {
            val tileY = (y * rows / h).coerceAtMost(rows - 1)
            val rowOffset = y * w
            for (x in 0 until w) {
                val tileX = (x * columns / w).coerceAtMost(columns - 1)
                val index = tileY * columns + tileX
                sums[index] += luminance(pixels[rowOffset + x]).toLong()
                counts[index]++
            }
        }
        val localMeans = FloatArray(sums.size) { index ->
            (sums[index].toFloat() / counts[index].coerceAtLeast(1)).coerceAtLeast(38f)
        }

        for (y in 0 until h) {
            val tileY = (y * rows / h).coerceAtMost(rows - 1)
            val rowOffset = y * w
            for (x in 0 until w) {
                val index = rowOffset + x
                val px = pixels[index]
                val lum = luminance(px)
                val tileX = (x * columns / w).coerceAtMost(columns - 1)
                val localMean = localMeans[tileY * columns + tileX]
                val illuminationCorrected = lum + ((lum * (targetLuminance / localMean)).coerceIn(0f, 255f) - lum) * illuminationStrength
                val contrasted = (targetLuminance + (illuminationCorrected - targetLuminance) * contrast).coerceIn(0f, 255f)
                val outputLum = (255.0 * Math.pow((contrasted / 255f).toDouble(), gamma.toDouble())).toFloat()
                    .coerceIn(0f, 255f)
                val colorScale = outputLum / lum.coerceAtLeast(1f)
                val r = (((px shr 16) and 255) * colorScale).toInt().coerceIn(0, 255)
                val g = (((px shr 8) and 255) * colorScale).toInt().coerceIn(0, 255)
                val b = ((px and 255) * colorScale).toInt().coerceIn(0, 255)
                pixels[index] = (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun luminance(pixel: Int): Float =
        0.299f * ((pixel shr 16) and 255) + 0.587f * ((pixel shr 8) and 255) + 0.114f * (pixel and 255)

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

    /**
     * مستند أبيض وأسود بعتبة تكيفية محلية؛ يتعامل مع الظلال والخلفية الورقية أفضل
     * من تحويل التدرج الرمادي الثابت، ويُنفذ بعد الالتقاط فقط.
     */
    private fun toBlackAndWhite(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val columns = (w / 112).coerceIn(4, 24)
        val rows = (h / 112).coerceIn(4, 24)
        val sums = LongArray(columns * rows)
        val counts = IntArray(columns * rows)
        for (y in 0 until h) {
            val tileY = (y * rows / h).coerceAtMost(rows - 1)
            val rowOffset = y * w
            for (x in 0 until w) {
                val tileX = (x * columns / w).coerceAtMost(columns - 1)
                val tile = tileY * columns + tileX
                sums[tile] += luminance(pixels[rowOffset + x]).toLong()
                counts[tile]++
            }
        }
        for (y in 0 until h) {
            val tileY = (y * rows / h).coerceAtMost(rows - 1)
            val rowOffset = y * w
            for (x in 0 until w) {
                val tileX = (x * columns / w).coerceAtMost(columns - 1)
                val tile = tileY * columns + tileX
                val threshold = (sums[tile].toFloat() / counts[tile].coerceAtLeast(1) - 15f)
                    .coerceIn(72f, 214f)
                val value = if (luminance(pixels[rowOffset + x]) >= threshold) 255 else 0
                pixels[rowOffset + x] = (0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
            }
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
        val confidence: Float,
        val corners: List<PointF>,
        val shouldAutoCorrect: Boolean
    )

    /** نتيجة خوارزمية المسح الكاملة: زوايا مقترحة، درجة ثقة، وصورة مستقيمة عند الأمان. */
    data class DocumentCorrection(
        val detection: EdgeDetection,
        val correctedBitmap: Bitmap?
    )

    /** كشف رباعي للمستند محلياً مع تقييم للثقة، دون خدمات Google أو اتصال بالشبكة. */
    fun detectDocumentEdges(src: Bitmap): EdgeDetection {
        val result = DocumentEdgeDetector.detect(src)
        return EdgeDetection(
            bounds = RectF(result.left, result.top, result.right, result.bottom),
            isDocumentDetected = result.isDocumentDetected,
            confidence = result.confidence,
            corners = result.corners,
            shouldAutoCorrect = result.shouldAutoCorrect
        )
    }

    /**
     * يشغل كشف الزوايا الأربع وتصحيح المنظور محليًا في عملية واحدة. إذا لم تتجاوز
     * النتيجة حد الثقة، يعيد صورة null مع إطار مقترح حتى يبقى القص اليدوي متاحًا.
     */
    fun detectAndCorrectDocument(src: Bitmap): DocumentCorrection {
        val result = DocumentEdgeDetector.detectAndStraighten(src)
        return DocumentCorrection(
            detection = EdgeDetection(
                bounds = RectF(
                    result.detection.left,
                    result.detection.top,
                    result.detection.right,
                    result.detection.bottom
                ),
                isDocumentDetected = result.detection.isDocumentDetected,
                confidence = result.detection.confidence,
                corners = result.detection.corners,
                shouldAutoCorrect = result.detection.shouldAutoCorrect
            ),
            correctedBitmap = result.correctedBitmap
        )
    }

    /** تصحيح منظور النتيجة الواثقة؛ يعيد null حتى يبقى الاقتصاص اليدوي هو البديل الآمن. */
    fun straightenDocument(src: Bitmap, corners: List<PointF>): Bitmap? = DocumentEdgeDetector.straighten(src, corners)

    /** توافق مع الاستدعاءات السابقة: يعيد الإطار المقترح فقط. */
    fun detectEdges(src: Bitmap): RectF = detectDocumentEdges(src).bounds

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
