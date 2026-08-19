package com.masahhisabat.app.ui.invoice

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * صورة قابلة للتكبير بالسحب بأصبعين (pinch-to-zoom) ودعم التمرير عند التكبير،
 * مع تعطيل السحب الأفقي عند عدم التكبير حتى يعمل السحب بين الصور في ViewPager2.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var origMatrix = Matrix()

    // قيم التكبير
    private var scale = 1f
    private var maxScale = 4f
    private var minScale = 1f

    // تتبع السحب
    private var mode = Mode.NONE
    private val start = PointF()
    private val last = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    private var saveScale = 1f
    private var width = 0f
    private var height = 0f

    // إزاحة الصورة داخل العرض (center fit)
    private var viewWidth = 0f
    private var viewHeight = 0f

    // تتبع النقر المزدوج
    private var lastTapTime = 0L
    private val doubleTapThreshold = 300L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var movedBeyondTouchSlop = false

    enum class Mode { NONE, DRAG, ZOOM }

    init {
        scaleType = ScaleType.MATRIX
    }

    /** إعادة تمركز الصورة بعد تغيير الحجم أو ضبط الصورة */
    fun resetZoom() {
        scale = 1f
        saveScale = 1f
        matrix.reset()
        fitImageCenter()
        imageMatrix = matrix
    }

    private fun fitImageCenter() {
        val w = drawable?.intrinsicWidth ?: return
        val h = drawable?.intrinsicHeight ?: return
        if (w <= 0 || h <= 0 || viewWidth <= 0f || viewHeight <= 0f) return
        val sx = viewWidth / w.toFloat()
        val sy = viewHeight / h.toFloat()
        scale = minOf(sx, sy)
        minScale = scale
        maxScale = minScale * 4f
        saveScale = scale
        val tx = (viewWidth - w * scale) / 2f
        val ty = (viewHeight - h * scale) / 2f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
    }

    fun getScale(): Float = saveScale

    val isZoomed: Boolean get() = saveScale > minScale * 1.02f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewWidth = measuredWidth.toFloat()
        viewHeight = measuredHeight.toFloat()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
        if (drawable != null) {
            fitImageCenter()
            imageMatrix = matrix
        }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap) {
        super.setImageBitmap(bm)
        if (viewWidth > 0 && viewHeight > 0) {
            fitImageCenter()
            imageMatrix = matrix
        } else {
            post {
                viewWidth = width.toFloat()
                viewHeight = height.toFloat()
                fitImageCenter()
                imageMatrix = matrix
            }
        }
    }

    /** التمرير داخل الصورة عند التكبير */
    private fun translateMatrix(dx: Float, dy: Float) {
        matrix.postTranslate(dx, dy)
    }

    /** حدود التمرير حتى لا تخرج الصورة عن الشاشة */
    private fun fixTrans() {
        matrix.getValues(values)
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val drawableWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
        val drawableHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f

        val getFixTransX = getFixTrans(transX, viewWidth.toInt(), drawableWidth * saveScale)
        val getFixTransY = getFixTrans(transY, viewHeight.toInt(), drawableHeight * saveScale)

        if (getFixTransX != 0f || getFixTransY != 0f) matrix.postTranslate(getFixTransX, getFixTransY)
    }

    private fun getFixTrans(trans: Float, viewSize: Int, contentSize: Float): Float {
        return when {
            contentSize <= viewSize -> 0f
            trans > 0 -> -trans
            trans < viewSize - contentSize -> viewSize - contentSize - trans
            else -> 0f
        }
    }

    private fun getFixDragTrans(delta: Float, viewSize: Int, contentSize: Float): Float {
        return if (contentSize <= viewSize) 0f else delta
    }

    private val values = FloatArray(9)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start.set(event.x, event.y)
                movedBeyondTouchSlop = false
                if (!isZoomed) {
                    // يسمح للـ ViewPager باعتراض السحب أحادي الإصبع، مع بقاء الصورة مستقبلة للنقر المزدوج والتكبير.
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapThreshold) {
                        // نقرة مزدوجة: تكبير/تصغير
                        if (isZoomed) {
                            smoothScaleTo(minScale)
                        } else {
                            smoothScaleTo(minScale * 2.5f, x, y)
                        }
                        lastTapTime = 0
                        return true
                    }
                    lastTapTime = now
                }
                mode = Mode.DRAG
                last.set(event.x, event.y)
                if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                movedBeyondTouchSlop = true
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    midPoint(mid, event)
                    origMatrix.set(matrix)
                    mode = Mode.ZOOM
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(x - start.x) > touchSlop || abs(event.y - start.y) > touchSlop) {
                    movedBeyondTouchSlop = true
                }
                if (mode == Mode.ZOOM) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(origMatrix)
                        val newScale = saveScale * (newDist / oldDist)
                        val newScaleClamped = newScale.coerceIn(minScale, maxScale)
                        matrix.postScale(newScaleClamped / saveScale, newScaleClamped / saveScale, mid.x, mid.y)
                        scale = newScaleClamped
                        saveScale = newScaleClamped
                        imageMatrix = matrix
                    }
                    return true
                } else if (mode == Mode.DRAG && isZoomed) {
                    val dx = x - last.x
                    val dy = y - last.y
                    val drawableWidth = drawable?.intrinsicWidth?.toFloat() ?: 0f
                    val drawableHeight = drawable?.intrinsicHeight?.toFloat() ?: 0f
                    translateMatrix(getFixDragTrans(dx, viewWidth.toInt(), drawableWidth * saveScale),
                                    getFixDragTrans(dy, viewHeight.toInt(), drawableHeight * saveScale))
                    last.set(x, y)
                    imageMatrix = matrix
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = !movedBeyondTouchSlop
                if (mode == Mode.DRAG || mode == Mode.ZOOM) {
                    fixTrans()
                    imageMatrix = matrix
                    mode = Mode.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (wasTap) performClick()
                    return true
                }
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** تكبير فوري نحو نقطة معينة (نقرة مزدوجة) مع ضمان حدود الصورة */
    private fun smoothScaleTo(target: Float, focusX: Float = viewWidth / 2f, focusY: Float = viewHeight / 2f) {
        val targetClamped = target.coerceIn(minScale, maxScale)
        val startScale = saveScale
        matrix.postScale(targetClamped / startScale, targetClamped / startScale, focusX, focusY)
        saveScale = targetClamped
        fixTrans()
        imageMatrix = matrix
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2f, y / 2f)
    }
}
