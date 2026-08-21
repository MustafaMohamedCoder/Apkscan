package com.masahhisabat.app.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.masahhisabat.app.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * كاميرا مستندات محلية: معاينة CameraX، إطار توجيه واضح، تحكم بالفلاش والالتقاط،
 * ثم انتقال مباشر إلى محرر القص والتحسين. لا تعتمد على Google أو على اتصال بالشبكة.
 */
class DocumentCameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var progress: View
    private lateinit var flashButton: TextView
    private lateinit var pdfSessionLabel: TextView
    private lateinit var statusDot: View
    private lateinit var documentFrameOverlay: DocumentFrameOverlay
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private var captureInProgress = false
    private var cameraStartRequested = false
    private var cameraPermissionRequestInFlight = false
    private var returningFromAppSettings = false
    @Volatile private var analysisActive = true
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "document-preview-analyzer").apply { isDaemon = true }
    }
    private var analyzedFrameCount = 0
    private var lastAnalysisAt = 0L
    private var lastLightState = -1
    private var brightLightSamples = 0
    private var guideReady = false

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyGalleryImageAndEdit(uri)
    }

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeCaptureAfterEditor()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setCameraPermissionWaiting(false)
        if (granted) startCamera()
        else showCameraPermissionFallback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_document_camera)

        previewView = findViewById(R.id.camera_preview)
        captureButton = findViewById(R.id.btn_capture_document)
        statusText = findViewById(R.id.camera_status_text)
        progress = findViewById(R.id.capture_progress)
        flashButton = findViewById(R.id.btn_flash)
        pdfSessionLabel = findViewById(R.id.pdf_session_label)
        statusDot = findViewById(R.id.camera_status_dot)
        documentFrameOverlay = findViewById(R.id.document_frame_overlay)
        val sessionPageCount = intent.getIntExtra(EXTRA_PDF_PAGE_COUNT, 0)
        if (sessionPageCount > 0) {
            pdfSessionLabel.visibility = View.VISIBLE
            pdfSessionLabel.text = getString(R.string.camera_pdf_session_progress, sessionPageCount)
        }

        findViewById<View>(R.id.btn_close_camera).setOnClickListener { button ->
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
        captureButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            captureDocument()
        }
        findViewById<View>(R.id.btn_camera_gallery).setOnClickListener { button ->
            if (!captureInProgress) {
                button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                galleryLauncher.launch("image/*")
            }
        }
        flashButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleTorch()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    /** يشرح سبب الإذن قبل كل طلب، ولا يحرم المستخدم من خيار المعرض. */
    private fun requestCameraPermission() {
        if (cameraPermissionRequestInFlight || isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_intro_title)
            .setMessage(R.string.camera_permission_intro_message)
            .setNegativeButton(R.string.camera_choose_image) { _, _ ->
                setCameraPermissionWaiting(false)
                showGalleryOnlyState()
            }
            .setPositiveButton(R.string.camera_continue) { _, _ ->
                setCameraPermissionWaiting(true)
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setOnCancelListener {
                setCameraPermissionWaiting(false)
                showGalleryOnlyState()
            }
            .show()
    }

    /** يستخدم طبقة التحميل الموجودة لإيضاح أن النظام ينتظر قرار المستخدم بشأن الكاميرا. */
    private fun setCameraPermissionWaiting(waiting: Boolean) {
        cameraPermissionRequestInFlight = waiting
        progress.visibility = if (waiting) View.VISIBLE else View.GONE
        if (waiting) {
            captureButton.isEnabled = false
            captureButton.alpha = 0.45f
            flashButton.isEnabled = false
            flashButton.alpha = 0.45f
            statusText.text = getString(R.string.camera_permission_waiting)
        }
    }

    /** يعرض مساراً آمناً عند الرفض، بما في ذلك الرفض الدائم من إعدادات أندرويد. */
    private fun showCameraPermissionFallback() {
        val canRequestAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_required_title)
            .setMessage(
                if (canRequestAgain) {
                    getString(R.string.camera_permission_rejected_retry)
                } else {
                    getString(R.string.camera_permission_rejected_settings)
                }
            )
            .setNegativeButton(R.string.camera_choose_image) { _, _ -> showGalleryOnlyState() }
            .setNeutralButton(if (canRequestAgain) R.string.camera_retry_permission else R.string.cancel) { _, _ ->
                if (canRequestAgain) requestCameraPermission() else showGalleryOnlyState()
            }
            .setPositiveButton(R.string.camera_open_settings) { _, _ -> openAppSettings() }
            .show()
    }

    private fun showGalleryOnlyState() {
        captureButton.isEnabled = false
        captureButton.alpha = 0.45f
        flashButton.isEnabled = false
        flashButton.alpha = 0.45f
        statusText.text = getString(R.string.camera_gallery_only_status)
    }

    private fun openAppSettings() {
        returningFromAppSettings = true
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    /** تأكيد بصري قصير بعد العودة من الإعدادات؛ لا يلتقط صورة ولا يفتح المحرر تلقائيًا. */
    private fun showCameraPermissionGrantedFeedback() {
        val message = getString(R.string.camera_permission_granted_status)
        statusText.apply {
            text = message
            alpha = 0f
            translationY = resources.displayMetrics.density * 10f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        }
        Toast.makeText(this, R.string.camera_permission_granted_toast, Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        if (cameraStartRequested || isFinishing || isDestroyed) return
        cameraStartRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) {
                cameraStartRequested = false
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(92)
                    .build()
                imageAnalysis = ImageAnalysis.Builder()
                    // دقة منخفضة للمحلل فقط؛ لا تؤثر في دقة صورة المستند النهائية.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(960, 540),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { image -> analyzeGuideBrightness(image) }
                    }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                val available = camera?.cameraInfo?.hasFlashUnit() == true
                flashButton.isEnabled = available
                flashButton.alpha = if (available) 1f else 0.45f
                updateGuideState(ready = false, lowLight = false, message = getString(R.string.camera_guide_center_document))
                setCaptureState(false, getString(R.string.camera_guide_capture_document))
            } catch (_: Exception) {
                cameraStartRequested = false
                showGalleryOnlyState()
                Toast.makeText(this, R.string.camera_start_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * تحليل خفيف لقناة الإضاءة داخل إطار المستند فقط. لا يحوّل الإطار إلى Bitmap
     * ولا يشغّل كشف الزوايا الكامل؛ فالخوارزمية الثقيلة تعمل بعد الالتقاط على الصورة الأصلية.
     */
    private fun analyzeGuideBrightness(image: ImageProxy) {
        try {
            if (!analysisActive || captureInProgress) return
            analyzedFrameCount++
            val now = SystemClock.elapsedRealtime()
            if (analyzedFrameCount % ANALYSIS_EVERY_N_FRAMES != 0 || now - lastAnalysisAt < MIN_ANALYSIS_INTERVAL_MS) {
                return
            }
            lastAnalysisAt = now

            val lumaPlane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            // نحلل منطقة الإطار المرئي فقط وبعينة متباعدة لتقليل الحمل على الأجهزة الضعيفة.
            val left = (width * 0.12f).toInt()
            val right = (width * 0.88f).toInt()
            val top = (height * 0.14f).toInt()
            val bottom = (height * 0.86f).toInt()
            val buffer = lumaPlane.buffer
            var total = 0L
            var count = 0
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val index = y * lumaPlane.rowStride + x * lumaPlane.pixelStride
                    if (index in 0 until buffer.capacity()) {
                        total += buffer.get(index).toInt() and 0xFF
                        count++
                    }
                    x += LUMA_SAMPLE_STEP
                }
                y += LUMA_SAMPLE_STEP
            }

            if (count == 0) return
            val lightState = if ((total / count) < LOW_LIGHT_LUMA_THRESHOLD) 1 else 0
            val lowLight = lightState == 1
            brightLightSamples = if (lowLight) 0 else brightLightSamples + 1
            val readyNow = !lowLight && brightLightSamples >= MIN_READY_LIGHT_SAMPLES
            if (lightState == lastLightState && readyNow == guideReady) return
            lastLightState = lightState

            runOnUiThread {
                if (!isFinishing && !isDestroyed && !captureInProgress) {
                    updateGuideState(
                        ready = readyNow,
                        lowLight = lowLight,
                        message = when {
                            lowLight -> getString(R.string.camera_low_light)
                            readyNow -> getString(R.string.camera_ready_to_capture)
                            else -> getString(R.string.camera_light_good)
                        }
                    )
                }
            }
        } finally {
            image.close()
        }
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) return
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        flashButton.text = getString(if (torchEnabled) R.string.camera_flash_on else R.string.camera_flash_off)
    }

    private fun captureDocument() {
        val capture = imageCapture ?: return
        if (captureInProgress) return
        captureInProgress = true
        analysisActive = false
        updateGuideState(ready = false, lowLight = false, message = getString(R.string.camera_saving_document))
        setCaptureState(true, getString(R.string.camera_saving_document))
        val file = File(cacheDir, "document_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (isFinishing || isDestroyed) {
                    file.delete()
                    return
                }
                if (!file.exists() || file.length() <= 0L) {
                    file.delete()
                    captureInProgress = false
                    analysisActive = true
                    setCaptureState(false, getString(R.string.camera_save_failed))
                    return
                }
                openEditor(file.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                if (isFinishing || isDestroyed) return
                captureInProgress = false
                analysisActive = true
                setCaptureState(false, getString(R.string.camera_capture_failed))
            }
        })
    }

    private fun copyGalleryImageAndEdit(uri: Uri) {
        if (captureInProgress) return
        captureInProgress = true
        setCaptureState(true, getString(R.string.camera_preparing_image))
        Thread {
            val file = File(cacheDir, "gallery_document_${System.currentTimeMillis()}.jpg")
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    file.delete()
                    return@runOnUiThread
                }
                if (copied && file.exists() && file.length() > 0L) openEditor(file.absolutePath)
                else {
                    file.delete()
                    captureInProgress = false
                    setCaptureState(false, getString(R.string.camera_read_selected_failed))
                }
            }
        }.apply { name = "document-camera-gallery-copy"; start() }
    }

    private fun openEditor(path: String) {
        editorLauncher.launch(Intent(this, CropEditActivity::class.java).apply {
            putExtra(CropEditActivity.EXTRA_IMAGE_PATH, path)
            putExtra(CropEditActivity.EXTRA_ACTION, CropEditActivity.ACTION_NEW_INVOICE)
            putExtra(CropEditActivity.EXTRA_PDF_PAGE_COUNT, PdfSessionManager.pageCount(this@DocumentCameraActivity))
        })
    }

    /** يظل الماسح حاضرًا بعد إغلاق المحرر كي يتمكن المستخدم من التقاط صفحة أخرى بدل الرجوع خارج التدفق. */
    private fun resumeCaptureAfterEditor() {
        if (isFinishing || isDestroyed) return
        captureInProgress = false
        analysisActive = true
        setCaptureState(false, getString(R.string.camera_guide_capture_document))
        if (camera == null) startCamera()
    }

    /** يربط لون الإطار ونقطة الحالة بجاهزية الإضاءة؛ لا يدّعي اكتشاف الورقة قبل الالتقاط. */
    private fun updateGuideState(ready: Boolean, lowLight: Boolean, message: String) {
        guideReady = ready
        val color = when {
            ready -> Color.parseColor("#2DD4BF")
            lowLight -> Color.parseColor("#FBBF24")
            else -> Color.parseColor("#94A3B8")
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        documentFrameOverlay.setGuideReady(ready)
        statusText.text = message
    }

    private fun setCaptureState(capturing: Boolean, message: String) {
        progress.visibility = if (capturing) View.VISIBLE else View.GONE
        val cameraReady = imageCapture != null
        captureButton.isEnabled = !capturing && cameraReady
        captureButton.alpha = if (capturing || !cameraReady) 0.45f else 1f
        statusText.text = message
    }

    override fun onDestroy() {
        analysisActive = false
        imageAnalysis?.clearAnalyzer()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onPause() {
        analysisActive = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        analysisActive = !captureInProgress
        val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (returningFromAppSettings) {
            returningFromAppSettings = false
            if (hasCameraPermission) showCameraPermissionGrantedFeedback() else showGalleryOnlyState()
        }
        if (hasCameraPermission && camera == null) {
            startCamera()
        }
    }

    private companion object {
        const val EXTRA_PDF_PAGE_COUNT = "pdf_page_count"
        const val ANALYSIS_EVERY_N_FRAMES = 4
        const val MIN_ANALYSIS_INTERVAL_MS = 240L
        const val LUMA_SAMPLE_STEP = 20
        const val LOW_LIGHT_LUMA_THRESHOLD = 62L
        const val MIN_READY_LIGHT_SAMPLES = 2
    }

    /** طبقة رسم خفيفة توضح مساحة المستند وتُبقي مناطق الكاميرا المحيطة منخفضة التباين. */
    class DocumentFrameOverlay @JvmOverloads constructor(
        context: android.content.Context,
        attrs: android.util.AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(118, 0, 0, 0) }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2.2f
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 4f
            strokeCap = Paint.Cap.ROUND
        }
        private val frame = RectF()
        private var guideReady = false

        fun setGuideReady(ready: Boolean) {
            if (guideReady == ready) return
            guideReady = ready
            framePaint.color = Color.parseColor(if (ready) "#2DD4BF" else "#94A3B8")
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val horizontalInset = width * 0.095f
            val targetHeight = (width - horizontalInset * 2f) * 1.30f
            val availableHeight = height * 0.64f
            val frameHeight = minOf(targetHeight, availableHeight)
            val top = (height - frameHeight) * 0.44f
            frame.set(horizontalInset, top, width - horizontalInset, top + frameHeight)

            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shadePaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shadePaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shadePaint)
            canvas.drawRoundRect(frame, 20f, 20f, framePaint)

            val corner = minOf(frame.width(), frame.height()) * 0.10f
            fun drawCorner(x: Float, y: Float, sx: Float, sy: Float) {
                canvas.drawLine(x, y, x + corner * sx, y, cornerPaint)
                canvas.drawLine(x, y, x, y + corner * sy, cornerPaint)
            }
            drawCorner(frame.left, frame.top, 1f, 1f)
            drawCorner(frame.right, frame.top, -1f, 1f)
            drawCorner(frame.left, frame.bottom, 1f, -1f)
            drawCorner(frame.right, frame.bottom, -1f, -1f)
        }
    }
}
