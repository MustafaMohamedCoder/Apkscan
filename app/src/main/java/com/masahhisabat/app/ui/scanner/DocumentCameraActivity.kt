package com.masahhisabat.app.ui.scanner

import android.Manifest
import android.content.Intent
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
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private var captureInProgress = false
    private var cameraStartRequested = false
    private var cameraPermissionRequestInFlight = false
    private var returningFromAppSettings = false
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "document-preview-analyzer").apply { isDaemon = true }
    }
    private var analyzedFrameCount = 0
    private var lastAnalysisAt = 0L
    private var lastLightState = -1

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyGalleryImageAndEdit(uri)
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

        findViewById<View>(R.id.btn_close_camera).setOnClickListener { finish() }
        captureButton.setOnClickListener { captureDocument() }
        findViewById<View>(R.id.btn_camera_gallery).setOnClickListener {
            if (!captureInProgress) galleryLauncher.launch("image/*")
        }
        flashButton.setOnClickListener { toggleTorch() }

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
            .setTitle("قبل فتح الكاميرا")
            .setMessage("يحتاج الماسح إذن الكاميرا لالتقاط المستندات واكتشاف حوافها على جهازك. يمكنك بدلاً من ذلك اختيار صورة محفوظة من المعرض.")
            .setNegativeButton("اختيار صورة") { _, _ ->
                setCameraPermissionWaiting(false)
                showGalleryOnlyState()
            }
            .setPositiveButton("متابعة") { _, _ ->
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
            statusText.text = "بانتظار قرارك بشأن إذن الكاميرا…"
        }
    }

    /** يعرض مساراً آمناً عند الرفض، بما في ذلك الرفض الدائم من إعدادات أندرويد. */
    private fun showCameraPermissionFallback() {
        val canRequestAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        AlertDialog.Builder(this)
            .setTitle("يلزم إذن الكاميرا للمسح")
            .setMessage(
                if (canRequestAgain) {
                    "لم يتم السماح باستخدام الكاميرا. يمكنك فتح إعدادات التطبيق لتفعيلها يدويًا، أو إعادة طلب الإذن، أو اختيار صورة من المعرض."
                } else {
                    "تم منع إذن الكاميرا. افتح إعدادات التطبيق واسمح بالكاميرا لتصوير المستندات، أو اختر صورة من المعرض."
                }
            )
            .setNegativeButton("اختيار صورة") { _, _ -> showGalleryOnlyState() }
            .setNeutralButton(if (canRequestAgain) "إعادة طلب الإذن" else "إلغاء") { _, _ ->
                if (canRequestAgain) requestCameraPermission() else showGalleryOnlyState()
            }
            .setPositiveButton("فتح الإعدادات") { _, _ -> openAppSettings() }
            .show()
    }

    private fun showGalleryOnlyState() {
        captureButton.isEnabled = false
        captureButton.alpha = 0.45f
        flashButton.isEnabled = false
        flashButton.alpha = 0.45f
        statusText.text = "يمكنك اختيار صورة من المعرض ومتابعة تحسينها محلياً"
    }

    private fun openAppSettings() {
        returningFromAppSettings = true
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    /** تأكيد بصري قصير بعد العودة من الإعدادات؛ لا يلتقط صورة ولا يفتح المحرر تلقائيًا. */
    private fun showCameraPermissionGrantedFeedback() {
        val message = "✓ تم تفعيل إذن الكاميرا — الكاميرا جاهزة للمسح"
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
        Toast.makeText(this, "تم تفعيل إذن الكاميرا بنجاح", Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        if (cameraStartRequested || isFinishing || isDestroyed) return
        cameraStartRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
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
                    .setTargetResolution(Size(960, 540))
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
                setCaptureState(false, "وجّه المستند داخل الإطار ثم التقط الصورة")
            } catch (_: Exception) {
                cameraStartRequested = false
                showGalleryOnlyState()
                Toast.makeText(this, "تعذر تشغيل كاميرا المستندات، يمكنك اختيار صورة من المعرض", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * تحليل خفيف لقناة الإضاءة داخل إطار المستند فقط. لا يحوّل الإطار إلى Bitmap
     * ولا يشغّل كشف الزوايا الكامل؛ فالخوارزمية الثقيلة تعمل بعد الالتقاط على الصورة الأصلية.
     */
    private fun analyzeGuideBrightness(image: ImageProxy) {
        try {
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
            if (lightState == lastLightState) return
            lastLightState = lightState

            runOnUiThread {
                if (!isFinishing && !isDestroyed && !captureInProgress) {
                    statusText.text = if (lightState == 1) {
                        "إضاءة منخفضة — فعّل الفلاش أو قرّب المستند"
                    } else {
                        "وجّه المستند داخل الإطار ثم التقط الصورة"
                    }
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
        flashButton.text = if (torchEnabled) "فلاش: تشغيل" else "فلاش"
    }

    private fun captureDocument() {
        val capture = imageCapture ?: return
        if (captureInProgress) return
        captureInProgress = true
        setCaptureState(true, "يجري حفظ المستند…")
        val file = File(cacheDir, "document_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                if (!file.exists() || file.length() <= 0L) {
                    captureInProgress = false
                    setCaptureState(false, "تعذر حفظ الصورة، أعد المحاولة")
                    return
                }
                openEditor(file.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                captureInProgress = false
                setCaptureState(false, "تعذر التقاط المستند، أعد المحاولة")
            }
        })
    }

    private fun copyGalleryImageAndEdit(uri: Uri) {
        if (captureInProgress) return
        captureInProgress = true
        setCaptureState(true, "يجري تجهيز الصورة…")
        Thread {
            val file = File(cacheDir, "gallery_document_${System.currentTimeMillis()}.jpg")
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (copied && file.exists() && file.length() > 0L) openEditor(file.absolutePath)
                else {
                    file.delete()
                    captureInProgress = false
                    setCaptureState(false, "تعذر قراءة الصورة المختارة")
                }
            }
        }.apply { name = "document-camera-gallery-copy"; start() }
    }

    private fun openEditor(path: String) {
        startActivity(Intent(this, CropEditActivity::class.java).apply {
            putExtra(CropEditActivity.EXTRA_IMAGE_PATH, path)
            putExtra(CropEditActivity.EXTRA_ACTION, CropEditActivity.ACTION_NEW_INVOICE)
        })
        finish()
    }

    private fun setCaptureState(capturing: Boolean, message: String) {
        progress.visibility = if (capturing) View.VISIBLE else View.GONE
        val cameraReady = imageCapture != null
        captureButton.isEnabled = !capturing && cameraReady
        captureButton.alpha = if (capturing || !cameraReady) 0.45f else 1f
        statusText.text = message
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
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
        const val ANALYSIS_EVERY_N_FRAMES = 3
        const val MIN_ANALYSIS_INTERVAL_MS = 125L
        const val LUMA_SAMPLE_STEP = 16
        const val LOW_LIGHT_LUMA_THRESHOLD = 62L
    }

    /** طبقة رسم خفيفة توضح مساحة المستند وتُبقي مناطق الكاميرا المحيطة منخفضة التباين. */
    class DocumentFrameOverlay @JvmOverloads constructor(
        context: android.content.Context,
        attrs: android.util.AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(118, 0, 0, 0) }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2DD4BF")
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
