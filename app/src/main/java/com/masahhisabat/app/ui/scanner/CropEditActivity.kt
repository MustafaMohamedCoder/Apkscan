package com.masahhisabat.app.ui.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.currentInvoiceName
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.image.ProcessMode
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.InvoiceActivity

/**
 * شاشة الاقتصاص والمعالجة:
 * - اقتطاع يدوي بمقابض قابلة للسحب + سحب كامل منطقة الاقتطاع
 * - اقتطاع مركزي، اقتطاع تلقائي (كشف الحواف)
 * - شبكة قياس شفافة، تنبيه عند الوصول للحواف
 * - تبديل أوضاع المعالجة مع مقارنة تفاعلية بين الأصلية والمحسنة
 */
class CropEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_ACTION = "action"
        const val ACTION_NEW_INVOICE = "new_invoice"
        const val ACTION_ADD_TO_INVOICE = "add_to_invoice"
    }

    private lateinit var cropView: CropView
    private lateinit var originalBmp: android.graphics.Bitmap
    private var processedBmp: android.graphics.Bitmap? = null
    private var imagePath: String = ""
    private var action: String = ACTION_NEW_INVOICE
    private var lastMode = ProcessMode.AUTO
    private var processing = false
    private var edgeDetectionInProgress = false
    private var edgeDetectionToken = 0
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop_edit)
        applyTheme()

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_NEW_INVOICE
        lastMode = try { ProcessMode.valueOf(AppRepository.lastProcessMode()) } catch (e: Exception) { ProcessMode.AUTO }

        if (imagePath.isBlank()) {
            Toast.makeText(this, "الصورة غير متاحة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        originalBmp = ImageProcessor.loadBitmap(imagePath)

        cropView = findViewById(R.id.crop_view)
        cropView.setBitmap(originalBmp)
        loadingPanel = findViewById(R.id.loading_panel)
        loadingLabel = findViewById(R.id.loading_message)

        findViewById<MaterialButton>(R.id.btn_rotate).setOnClickListener {
            val croppedForRotation = cropView.getCroppedBitmap()
            val rotated = ImageProcessor.rotateBitmap(croppedForRotation, -90)
            if (rotated !== croppedForRotation && !croppedForRotation.isRecycled) croppedForRotation.recycle()
            if (!originalBmp.isRecycled) originalBmp.recycle()
            processedBmp?.takeIf { !it.isRecycled }?.recycle()
            cropView.setBitmap(rotated)
            originalBmp = rotated
            processedBmp = null
            cropView.setCropRect(0.035f, 0.035f, 0.965f, 0.965f)
            detectAndApplyEdges(showResult = false)
        }

        findViewById<MaterialButton>(R.id.btn_grid).setOnClickListener {
            cropView.toggleGrid()
        }

        findViewById<MaterialButton>(R.id.btn_crop_center).setOnClickListener {
            cropView.centerCrop()
            Toast.makeText(this, R.string.crop_center, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btn_crop_auto).setOnClickListener {
            detectAndApplyEdges(showResult = true)
        }

        findViewById<MaterialButton>(R.id.btn_compare).setOnClickListener {
            showComparison()
        }

        findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            if (processing) return@setOnClickListener
            processAndContinue()
        }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // يبدأ الاقتراح تلقائيًا بعد فتح الصورة؛ يظل الإطار قابلاً للتعديل دائمًا.
        detectAndApplyEdges(showResult = true)
    }

    override fun onDestroy() {
        edgeDetectionToken++
        if (::cropView.isInitialized) cropView.clearBitmap()
        if (::originalBmp.isInitialized && !originalBmp.isRecycled) originalBmp.recycle()
        processedBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
        processedBmp = null
        super.onDestroy()
    }

    private fun applyTheme() {
        window.decorView.setBackgroundColor(ThemeHelper.bg(this))
        findViewById<View>(R.id.crop_root).setBackgroundColor(Color.BLACK)
    }

    private fun processAndContinue() {
        if (edgeDetectionInProgress) return
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE

        val cropped = cropView.getCroppedBitmap()
        ImageProcessor.process(lastMode, cropped, object : ImageProcessor.Callback {
            override fun onDone(bitmap: android.graphics.Bitmap) {
                processing = false
                loadingPanel.visibility = View.GONE
                if (bitmap !== cropped && !cropped.isRecycled) cropped.recycle()
                processedBmp = bitmap
                AppRepository.setLastProcessMode(lastMode.key)
                showSuccessAndContinue(bitmap)
            }
            override fun onError() {
                processing = false
                loadingPanel.visibility = View.GONE
                Toast.makeText(this@CropEditActivity, "فشلت المعالجة — جاري استخدام الأصلية", Toast.LENGTH_SHORT).show()
                processedBmp = cropped
                showSuccessAndContinue(cropped)
            }
        })
    }

    /** يشغّل الكشف خارج خيط الواجهة ويطبق الإطار فقط إذا بقيت الصورة نفسها نشطة. */
    private fun detectAndApplyEdges(showResult: Boolean) {
        if (edgeDetectionInProgress || !::originalBmp.isInitialized || originalBmp.isRecycled) return
        val bitmap = originalBmp
        val token = ++edgeDetectionToken
        edgeDetectionInProgress = true
        setEdgeDetectionUi(detecting = true)

        Thread {
            val detection = try {
                ImageProcessor.detectDocumentEdges(bitmap)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != edgeDetectionToken || !::cropView.isInitialized) return@runOnUiThread
                edgeDetectionInProgress = false
                setEdgeDetectionUi(detecting = false)

                if (detection != null) {
                    val bounds = detection.bounds
                    cropView.setCropRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    if (showResult) {
                        Toast.makeText(
                            this,
                            if (detection.isDocumentDetected) R.string.auto_edge else R.string.edge_detection_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    cropView.setCropRect(0.035f, 0.035f, 0.965f, 0.965f)
                    if (showResult) Toast.makeText(this, R.string.edge_detection_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.apply {
            name = "document-edge-detector"
            start()
        }
    }

    private fun setEdgeDetectionUi(detecting: Boolean) {
        loadingLabel.setText(if (detecting) R.string.detecting_document_edges else R.string.loading)
        loadingPanel.visibility = if (detecting) View.VISIBLE else View.GONE
        intArrayOf(
            R.id.btn_rotate,
            R.id.btn_grid,
            R.id.btn_crop_center,
            R.id.btn_crop_auto,
            R.id.btn_compare,
            R.id.btn_done
        ).forEach { id -> findViewById<View>(id).isEnabled = !detecting }
    }

    private fun showSuccessAndContinue(bitmap: android.graphics.Bitmap) {
        val ctx = this
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()

        val options = arrayOf(
            getString(R.string.new_invoice),
            getString(R.string.add_to_invoice),
            getString(R.string.share)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.save_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> goToInvoice(newFile(bitmap), actionCreate = true)
                    1 -> goToInvoice(newFile(bitmap), actionCreate = false)
                    2 -> shareBitmap(bitmap)
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun newFile(bitmap: android.graphics.Bitmap): String {
        val dir = File(AppRepository.dataDir(this), "scans")
        return ImageProcessor.saveTo(bitmap, dir, "scan").absolutePath
    }

    private fun goToInvoice(path: String, actionCreate: Boolean) {
        val intent = Intent(this, InvoiceActivity::class.java)
        intent.putExtra(InvoiceActivity.EXTRA_IMAGE_PATH, path)
        intent.putExtra(InvoiceActivity.EXTRA_ACTION,
            if (actionCreate) InvoiceActivity.ACTION_CREATE else InvoiceActivity.ACTION_ADD)
        startActivity(intent)
        finish()
    }

    private fun shareBitmap(bitmap: android.graphics.Bitmap) {
        try {
            val file = File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.share)))
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showComparison() {
        val ctx = this
        val processed = processedBmp
        val items = listOf("الأصلية", "المحسنة")
        val bitmaps = listOf(originalBmp, processed)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("مقارنة تفاعلية")
            .setItems(items.toTypedArray()) { _, which ->
                if (bitmaps[which] == null) {
                    Toast.makeText(ctx, "لا توجد نسخة محسنة بعد", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                showCompareOverlay(bitmaps[which]!!)
            }
            .show()
    }

    private fun showCompareOverlay(bmp: android.graphics.Bitmap) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("مقارنة: مرر إصبعك لعرض الفرق")
            .setPositiveButton(R.string.close, null)
            .create()
        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 700
            )
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        dialog.setView(imageView)
        dialog.show()
    }

    /**
     * عرض الاقتصاص مع المقابض القابلة للسحب
     */
    class CropView(context: Context) : View(context) {

        private var bitmap: android.graphics.Bitmap? = null
        private val cropRect = RectF(0.05f, 0.05f, 0.95f, 0.95f)
        private val paintRect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#4FD1C5")
            strokeWidth = 4f
        }
        private val paintOverlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
        }
        private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 1.5f
        }
        private var showGrid = false
        private var draggingHandle = -1 // 0..3: TL, TR, BL, BR, 4: area
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        fun setBitmap(bmp: android.graphics.Bitmap) { bitmap = bmp; invalidate() }
        fun clearBitmap() { bitmap = null; invalidate() }
        fun toggleGrid() { showGrid = !showGrid; invalidate() }

        fun setCropRect(l: Float, t: Float, r: Float, b: Float) {
            cropRect.set(l, t, r, b); invalidate()
        }

        fun centerCrop() {
            val w = bitmap?.width ?: 1
            val h = bitmap?.height ?: 1
            val ratio = 0.85f
            val cw = w * ratio / w
            val ch = h * ratio / h
            cropRect.set((1f - cw) / 2, (1f - ch) / 2, (1f + cw) / 2, (1f + ch) / 2)
            invalidate()
        }

        fun getCroppedBitmap(): android.graphics.Bitmap {
            val bmp = bitmap ?: return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            return ImageProcessor.cropBitmap(bmp, cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = bitmap ?: return
            // رسم الصورة بمقياس fit-center
            val scale = Math.min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val x = (width - w) / 2
            val y = (height - h) / 2
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, 0f, 0f, null)

            // منطقة مظلمة خارج الاقتصاص
            canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paintOverlay)
            // مسح الظل داخل الاقتصاص
            canvas.drawRect(cropRect.left * bmp.width, cropRect.top * bmp.height,
                cropRect.right * bmp.width, cropRect.bottom * bmp.height, Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                })

            // الإطار
            canvas.drawRect(cropRect.left * bmp.width, cropRect.top * bmp.height,
                cropRect.right * bmp.width, cropRect.bottom * bmp.height, paintRect)

            // الشبكة
            if (showGrid) {
                for (i in 1..4) {
                    val gx = cropRect.left * bmp.width + (cropRect.width() * bmp.width) * i / 5
                    val gy = cropRect.top * bmp.height + (cropRect.height() * bmp.height) * i / 5
                    canvas.drawLine(gx, cropRect.top * bmp.height, gx, cropRect.bottom * bmp.height, paintGrid)
                    canvas.drawLine(cropRect.left * bmp.width, gy, cropRect.right * bmp.width, gy, paintGrid)
                }
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = bitmap ?: return super.onTouchEvent(event)
            val scale = Math.min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val ox = (width - bmp.width * scale) / 2
            val oy = (height - bmp.height * scale) / 2
            val rx = (event.x - ox) / scale / bmp.width
            val ry = (event.y - oy) / scale / bmp.height

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = rx; lastTouchY = ry
                    val hw = 0.04f
                    draggingHandle = when {
                        Math.abs(rx - cropRect.left) < hw && Math.abs(ry - cropRect.top) < hw -> 0
                        Math.abs(rx - cropRect.right) < hw && Math.abs(ry - cropRect.top) < hw -> 1
                        Math.abs(rx - cropRect.left) < hw && Math.abs(ry - cropRect.bottom) < hw -> 2
                        Math.abs(rx - cropRect.right) < hw && Math.abs(ry - cropRect.bottom) < hw -> 3
                        rx in cropRect.left..cropRect.right && ry in cropRect.top..cropRect.bottom -> 4
                        else -> -1
                    }
                    return draggingHandle != -1
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle == -1) return true
                    val dx = rx - lastTouchX
                    val dy = ry - lastTouchY
                    when (draggingHandle) {
                        0 -> { cropRect.left += dx; cropRect.top += dy }
                        1 -> { cropRect.right += dx; cropRect.top += dy }
                        2 -> { cropRect.left += dx; cropRect.bottom += dy }
                        3 -> { cropRect.right += dx; cropRect.bottom += dy }
                        4 -> {
                            cropRect.left += dx; cropRect.right += dx
                            cropRect.top += dy; cropRect.bottom += dy
                        }
                    }
                    clamp()
                    lastTouchX = rx; lastTouchY = ry
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    draggingHandle = -1
                    // تنبيه اهتزازي عند الوصول للحواف
                    if (cropRect.left <= 0.005f || cropRect.right >= 0.995f ||
                        cropRect.top <= 0.005f || cropRect.bottom >= 0.995f) {
                        (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                            ?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                        Toast.makeText(context, R.string.edge_reached, Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun clamp() {
            val min = 0.1f
            if (cropRect.left < 0f) cropRect.left = 0f
            if (cropRect.top < 0f) cropRect.top = 0f
            if (cropRect.right > 1f) cropRect.right = 1f
            if (cropRect.bottom > 1f) cropRect.bottom = 1f
            if (cropRect.width() < min) cropRect.right = (cropRect.left + min).coerceAtMost(1f)
            if (cropRect.height() < min) cropRect.bottom = (cropRect.top + min).coerceAtMost(1f)
        }
    }
}
