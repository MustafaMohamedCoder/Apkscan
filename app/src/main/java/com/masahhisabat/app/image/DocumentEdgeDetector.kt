package com.masahhisabat.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * كاشف مستند محلي يعتمد على OpenCV المضمن داخل APK. يصحح الإضاءة ثم يستخرج
 * المرشحات الرباعية من الحواف، ويقبل النتيجة فقط عند اجتياز فحص هندسي ودرجة ثقة.
 */
object DocumentEdgeDetector {
    private const val MAX_PROCESS_DIMENSION = 960
    private const val MAX_STRAIGHTEN_DIMENSION = 2200
    private const val FALLBACK_INSET = 0.035f
    private const val AUTO_CORRECT_CONFIDENCE = 0.82f
    private const val RETRY_CONFIDENCE = 0.78f
    private const val MIN_DOCUMENT_AREA_RATIO = 0.06f

    @Volatile private var initialized = false
    @Volatile private var openCvAvailable = false

    data class Result(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val corners: List<PointF>,
        val isDocumentDetected: Boolean,
        val confidence: Float,
        val shouldAutoCorrect: Boolean
    )

    /** نتيجة موحدة لمسار الالتقاط: الاكتشاف يبقى متاحًا حتى إن فشل التصحيح التلقائي. */
    data class CorrectionResult(
        val detection: Result,
        val correctedBitmap: Bitmap?
    )

    /** تهيئة آمنة؛ لا يؤدي فشل مكتبة الرؤية إلى منع المستخدم من الاقتصاص اليدوي. */
    fun initialize(): Boolean = synchronized(this) {
        if (!initialized) {
            openCvAvailable = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
            initialized = true
        }
        openCvAvailable
    }

    fun detect(source: Bitmap): Result {
        if (source.isRecycled || source.width < 100 || source.height < 100 || !initialize()) return fallback()
        val largest = max(source.width, source.height)
        val scale = min(1f, MAX_PROCESS_DIMENSION.toFloat() / largest.toFloat())
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val working = if (width == source.width && height == source.height) source
        else Bitmap.createScaledBitmap(source, width, height, true)

        val input = Mat()
        val grayscale = Mat()
        val enhanced = Mat()
        val blurred = Mat()
        val edges = Mat()
        val adaptive = Mat()
        val kernel = Mat.ones(Size(3.0, 3.0), CvType.CV_8U)
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        return try {
            Utils.bitmapToMat(working, input)
            Imgproc.cvtColor(input, grayscale, Imgproc.COLOR_RGBA2GRAY)
            clahe.apply(grayscale, enhanced)
            Imgproc.GaussianBlur(enhanced, blurred, Size(5.0, 5.0), 0.0)

            // المسار الأساسي سريع ومناسب للصور ذات الحدود الواضحة.
            val mean = Core.mean(blurred).`val`[0]
            val lower = (mean * 0.66).coerceIn(22.0, 145.0)
            val upper = (mean * 1.33).coerceIn(lower + 24.0, 245.0)
            Imgproc.Canny(blurred, edges, lower, upper)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            var candidate = findBestCandidate(edges, width, height)

            // إعادة المحاولة فقط عند الثقة المنخفضة؛ هذا يحسن الصور البيضاء أو الظليلة
            // دون دفع كل عملية مسح إلى تكلفة مسارين كاملين.
            if (candidate == null || candidate.confidence < RETRY_CONFIDENCE) {
                Imgproc.adaptiveThreshold(
                    blurred,
                    adaptive,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV,
                    31,
                    12.0
                )
                Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
                val alternative = findBestCandidate(adaptive, width, height)
                if (alternative != null && (candidate == null || alternative.confidence > candidate.confidence)) {
                    candidate = alternative
                }
            }
            candidate?.result ?: fallback()
        } catch (_: Throwable) {
            fallback()
        } finally {
            clahe.collectGarbage()
            input.release(); grayscale.release(); enhanced.release(); blurred.release(); edges.release(); adaptive.release(); kernel.release()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    /**
     * يطبق اكتشاف الشكل الرباعي ثم تصحيح المنظور في المسار ذاته، ولكن فقط عندما
     * تتجاوز النتيجة حد الثقة. النتيجة منخفضة الثقة لا تفقد الصورة الأصلية وتبقى
     * قابلة للقص اليدوي في المحرر.
     */
    fun detectAndStraighten(source: Bitmap): CorrectionResult {
        val detection = detect(source)
        val corrected = if (detection.shouldAutoCorrect) {
            straighten(source, detection.corners)
        } else {
            null
        }
        return CorrectionResult(detection, corrected)
    }

    private fun findBestCandidate(mask: Mat, width: Int, height: Int): Candidate? {
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        return try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            contours
                .asSequence()
                .filter { Imgproc.contourArea(it) >= width * height * MIN_DOCUMENT_AREA_RATIO }
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(32)
                .mapNotNull { scoreQuadrilateral(it, width, height) }
                .maxByOrNull { it.confidence }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    /** يحول رباعي الزوايا المرتب (أعلى يسار، أعلى يمين، أسفل يمين، أسفل يسار) إلى مستند مستقيم. */
    fun straighten(source: Bitmap, corners: List<PointF>): Bitmap? {
        if (source.isRecycled || corners.size != 4 || !initialize()) return null
        val sourceMat = Mat()
        val targetMat = Mat()
        val sourcePoints = MatOfPoint2f()
        val targetPoints = MatOfPoint2f()
        var transform: Mat? = null
        return try {
            val points = corners.map { Point(it.x.toDouble() * source.width, it.y.toDouble() * source.height) }
            var targetWidth = max(distance(points[0], points[1]), distance(points[2], points[3])).toInt().coerceIn(140, source.width * 2)
            var targetHeight = max(distance(points[0], points[3]), distance(points[1], points[2])).toInt().coerceIn(140, source.height * 2)
            val downscale = min(1.0, MAX_STRAIGHTEN_DIMENSION.toDouble() / max(targetWidth, targetHeight).toDouble())
            targetWidth = max(1, (targetWidth * downscale).toInt())
            targetHeight = max(1, (targetHeight * downscale).toInt())
            sourcePoints.fromArray(*points.toTypedArray())
            targetPoints.fromArray(
                Point(0.0, 0.0), Point((targetWidth - 1).toDouble(), 0.0),
                Point((targetWidth - 1).toDouble(), (targetHeight - 1).toDouble()), Point(0.0, (targetHeight - 1).toDouble())
            )
            Utils.bitmapToMat(source, sourceMat)
            transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
            Imgproc.warpPerspective(sourceMat, targetMat, transform, Size(targetWidth.toDouble(), targetHeight.toDouble()), Imgproc.INTER_CUBIC)
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(targetMat, it) }
        } catch (_: Throwable) {
            null
        } finally {
            sourceMat.release(); targetMat.release(); sourcePoints.release(); targetPoints.release(); transform?.release()
        }
    }

    private data class Candidate(val result: Result, val confidence: Float)

    private fun scoreQuadrilateral(contour: MatOfPoint, width: Int, height: Int): Candidate? {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        val polygon = MatOfPoint()
        return try {
            Imgproc.approxPolyDP(contour2f, approx, Imgproc.arcLength(contour2f, true) * 0.02, true)
            val points = approx.toArray()
            if (points.size != 4) return null
            polygon.fromArray(*points)
            if (!Imgproc.isContourConvex(polygon)) return null
            val ordered = orderCorners(points) ?: return null
            val area = abs(Imgproc.contourArea(polygon))
            val areaRatio = area / (width.toDouble() * height.toDouble())
            if (areaRatio !in 0.10..0.98) return null

            val areaScore = ((areaRatio - 0.12) / 0.70).toFloat().coerceIn(0f, 1f)
            val angleScore = cornerAngleScore(ordered)
            val sideScore = sideBalanceScore(ordered)
            val margin = ordered.minOf { min(min(it.x, width - it.x), min(it.y, height - it.y)) } / min(width, height).toDouble()
            val marginScore = if (margin > 0.008) 1f else 0.78f
            val confidence = (areaScore * 0.40f + angleScore * 0.35f + sideScore * 0.15f + marginScore * 0.10f).coerceIn(0f, 1f)
            if (confidence < 0.52f) return null

            val normalized = ordered.map { PointF((it.x / width).toFloat(), (it.y / height).toFloat()) }
            val left = normalized.minOf { it.x }.coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET)
            val top = normalized.minOf { it.y }.coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET)
            val right = normalized.maxOf { it.x }.coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET)
            val bottom = normalized.maxOf { it.y }.coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET)
            Candidate(
                Result(left, top, right, bottom, normalized, true, confidence, confidence >= AUTO_CORRECT_CONFIDENCE),
                confidence
            )
        } finally {
            contour2f.release(); approx.release(); polygon.release()
        }
    }

    private fun orderCorners(points: Array<Point>): List<Point>? {
        if (points.size != 4) return null
        // ترتيب ثابت: أعلى يسار، أعلى يمين، أسفل يمين، أسفل يسار.
        // يعتمد على مجموع/فارق الإحداثيات ليعمل مع الصور المائلة أيضًا.
        val topLeft = points.minByOrNull { it.x + it.y } ?: return null
        val bottomRight = points.maxByOrNull { it.x + it.y } ?: return null
        val topRight = points.minByOrNull { it.y - it.x } ?: return null
        val bottomLeft = points.maxByOrNull { it.y - it.x } ?: return null
        val ordered = listOf(topLeft, topRight, bottomRight, bottomLeft)
        return if (ordered.toSet().size == 4) ordered else null
    }

    private fun cornerAngleScore(points: List<Point>): Float {
        val deviations = (0 until 4).map { index ->
            val previous = points[(index + 3) % 4]
            val current = points[index]
            val next = points[(index + 1) % 4]
            val ax = previous.x - current.x; val ay = previous.y - current.y
            val bx = next.x - current.x; val by = next.y - current.y
            abs((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(0.0001))
        }
        return (1f - (deviations.average() / 0.55).toFloat()).coerceIn(0f, 1f)
    }

    private fun sideBalanceScore(points: List<Point>): Float {
        val sides = (0 until 4).map { distance(points[it], points[(it + 1) % 4]) }
        return ((sides.minOrNull()!! / sides.maxOrNull()!! - 0.18) / 0.62).toFloat().coerceIn(0f, 1f)
    }

    private fun distance(first: Point, second: Point): Double = hypot(first.x - second.x, first.y - second.y)

    private fun fallback(confidence: Float = 0f): Result = Result(
        FALLBACK_INSET, FALLBACK_INSET, 1f - FALLBACK_INSET, 1f - FALLBACK_INSET,
        emptyList(), false, confidence, false
    )
}
