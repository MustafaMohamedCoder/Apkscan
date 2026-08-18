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
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opts)
        val scale = maxOf(opts.outWidth / maxDim, opts.outHeight / maxDim, 1)
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = scale
        return BitmapFactory.decodeFile(path, o2)!!
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

    /** الحفظ كملف JPEG */
    fun saveTo(bitmap: Bitmap, dir: File, prefix: String): File {
        dir.mkdirs()
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    /** كشف الحواف التلقائي البسيط: عتبة أوتسو على التدرج الرمادي لإيجاد أكبر مستطيل داكن */
    fun detectEdges(src: Bitmap): RectF {
        val w = src.width
        val h = src.height
        // تصغير للمعالجة السريعة
        val sw = w.coerceAtMost(320)
        val sh = (h * sw / w).coerceAtMost(320)
        val small = Bitmap.createScaledBitmap(src, sw, sh, false)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val gray = FloatArray(sw * sh)
        var sum = 0f
        for (i in pixels.indices) {
            val px = pixels[i]
            val g = 0.299f * ((px shr 16) and 255) + 0.587f * (((px shr 8) and 255)) + 0.114f * (px and 255)
            gray[i] = g
            sum += g
        }
        val mean = sum / (sw * sh)
        val threshold = mean.coerceIn(60f, 200f)
        // حدود المستند: أول/آخر صف وعمود فيه نسبة عالية من "الورق"
        fun isPaper(v: Float) = v > threshold
        var top = 0f; var bottom = sh.toFloat()
        var left = 0f; var right = sw.toFloat()
        val paperRatioTh = 0.65f
        var count = 0
        for (x in 0 until sw) {
            var paper = 0
            for (y in 0 until sh) if (isPaper(gray[y * sw + x])) paper++
            if (paper.toFloat() / sh > paperRatioTh) count++
        }
        val colPaper = count.toFloat() / sw
        if (colPaper > 0.85f) {
            // المستند يغطي أغلب الصورة
            return RectF(0.04f, 0.04f, 0.96f, 0.96f)
        }
        return RectF(0.02f, 0.02f, 0.98f, 0.98f)
    }

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
