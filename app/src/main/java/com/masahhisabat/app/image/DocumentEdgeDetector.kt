package com.masahhisabat.app.image

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * كاشف محلي خفيف لحدود المستندات.
 *
 * يعمل على نسخة مصغرة من الصورة، ثم يحلل التدرج الرمادي ومرشحاً بسيطاً
 * للحواف (Sobel) لاختيار أقوى أربعة حدود مستقيمة مرشحة للمستند. لا يعتمد
 * على خدمات Google أو الاتصال بالإنترنت، ويعيد إطاراً آمناً يغطي الصورة
 * تقريباً عندما لا تكون الثقة كافية.
 */
object DocumentEdgeDetector {
    private const val MAX_PROCESS_DIMENSION = 640
    private const val FALLBACK_INSET = 0.035f

    data class Result(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val isDocumentDetected: Boolean,
        val confidence: Float
    )

    fun detect(source: Bitmap): Result {
        if (source.isRecycled || source.width < 80 || source.height < 80) return fallback()

        val largest = max(source.width, source.height)
        val scale = min(1f, MAX_PROCESS_DIMENSION.toFloat() / largest.toFloat())
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        if (width < 60 || height < 60) return fallback()

        val working = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }

        return try {
            val pixels = IntArray(width * height)
            working.getPixels(pixels, 0, width, 0, 0, width, height)
            val grayscale = IntArray(pixels.size)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                grayscale[index] = (
                    0.299f * ((pixel shr 16) and 0xFF) +
                        0.587f * ((pixel shr 8) and 0xFF) +
                        0.114f * (pixel and 0xFF)
                    ).toInt()
            }

            val gradients = sobel(blur3x3(grayscale, width, height), width, height)
            val verticalProfile = smooth(verticalProfile(gradients, width, height))
            val horizontalProfile = smooth(horizontalProfile(gradients, width, height))

            val left = strongestIn(verticalProfile, width * 3 / 100, width * 45 / 100)
            val right = strongestIn(verticalProfile, width * 55 / 100, width * 97 / 100)
            val top = strongestIn(horizontalProfile, height * 3 / 100, height * 45 / 100)
            val bottom = strongestIn(horizontalProfile, height * 55 / 100, height * 97 / 100)

            if (left < 0 || right < 0 || top < 0 || bottom < 0) return fallback()

            val widthRatio = (right - left).toFloat() / width
            val heightRatio = (bottom - top).toFloat() / height
            val lineStrengths = floatArrayOf(
                verticalProfile[left], verticalProfile[right],
                horizontalProfile[top], horizontalProfile[bottom]
            )
            val averageGradient = gradients.average().toFloat()
            val minimumExpected = max(12f, averageGradient * 1.25f)
            val meanLineStrength = lineStrengths.average().toFloat()
            val weakestLine = lineStrengths.minOrNull() ?: 0f
            val geometryValid = widthRatio in 0.40f..0.96f && heightRatio in 0.40f..0.96f
            val strengthConfidence = (meanLineStrength / (minimumExpected * 1.8f)).coerceIn(0f, 1f)
            val consistencyConfidence = (weakestLine / minimumExpected).coerceIn(0f, 1f)
            val geometryConfidence = min(widthRatio, heightRatio).coerceIn(0f, 1f)
            val confidence = (
                strengthConfidence * 0.55f +
                    consistencyConfidence * 0.30f +
                    geometryConfidence * 0.15f
                ).coerceIn(0f, 1f)

            val isValid = geometryValid &&
                meanLineStrength >= minimumExpected &&
                weakestLine >= minimumExpected * 0.68f &&
                confidence >= 0.42f
            if (!isValid) return fallback(confidence)

            Result(
                left = (left.toFloat() / width).coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET),
                top = (top.toFloat() / height).coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET),
                right = (right.toFloat() / width).coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET),
                bottom = (bottom.toFloat() / height).coerceIn(FALLBACK_INSET, 1f - FALLBACK_INSET),
                isDocumentDetected = true,
                confidence = confidence
            )
        } catch (_: Throwable) {
            fallback()
        } finally {
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun fallback(confidence: Float = 0f): Result = Result(
        left = FALLBACK_INSET,
        top = FALLBACK_INSET,
        right = 1f - FALLBACK_INSET,
        bottom = 1f - FALLBACK_INSET,
        isDocumentDetected = false,
        confidence = confidence
    )

    private fun blur3x3(source: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (offsetY in -1..1) {
                    val row = (y + offsetY).coerceIn(0, height - 1)
                    for (offsetX in -1..1) {
                        val column = (x + offsetX).coerceIn(0, width - 1)
                        sum += source[row * width + column]
                        count++
                    }
                }
                out[y * width + x] = sum / count
            }
        }
        return out
    }

    private fun sobel(source: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val topLeft = source[(y - 1) * width + x - 1]
                val top = source[(y - 1) * width + x]
                val topRight = source[(y - 1) * width + x + 1]
                val left = source[y * width + x - 1]
                val right = source[y * width + x + 1]
                val bottomLeft = source[(y + 1) * width + x - 1]
                val bottom = source[(y + 1) * width + x]
                val bottomRight = source[(y + 1) * width + x + 1]
                val horizontal = -topLeft + topRight - 2 * left + 2 * right - bottomLeft + bottomRight
                val vertical = -topLeft - 2 * top - topRight + bottomLeft + 2 * bottom + bottomRight
                out[y * width + x] = abs(horizontal) + abs(vertical)
            }
        }
        return out
    }

    private fun verticalProfile(gradients: IntArray, width: Int, height: Int): FloatArray {
        val result = FloatArray(width)
        val startY = height * 8 / 100
        val endY = max(startY + 1, height * 92 / 100)
        for (x in 0 until width) {
            var sum = 0L
            for (y in startY until endY) sum += gradients[y * width + x]
            result[x] = sum.toFloat() / (endY - startY)
        }
        return result
    }

    private fun horizontalProfile(gradients: IntArray, width: Int, height: Int): FloatArray {
        val result = FloatArray(height)
        val startX = width * 8 / 100
        val endX = max(startX + 1, width * 92 / 100)
        for (y in 0 until height) {
            var sum = 0L
            for (x in startX until endX) sum += gradients[y * width + x]
            result[y] = sum.toFloat() / (endX - startX)
        }
        return result
    }

    private fun smooth(profile: FloatArray): FloatArray {
        val result = FloatArray(profile.size)
        for (index in profile.indices) {
            var sum = 0f
            var count = 0
            for (offset in -3..3) {
                val sourceIndex = index + offset
                if (sourceIndex in profile.indices) {
                    sum += profile[sourceIndex]
                    count++
                }
            }
            result[index] = sum / count
        }
        return result
    }

    private fun strongestIn(profile: FloatArray, from: Int, toExclusive: Int): Int {
        val start = from.coerceIn(0, profile.lastIndex)
        val end = toExclusive.coerceIn(start + 1, profile.size)
        var bestIndex = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (index in start until end) {
            if (profile[index] > bestValue) {
                bestValue = profile[index]
                bestIndex = index
            }
        }
        return bestIndex
    }
}
