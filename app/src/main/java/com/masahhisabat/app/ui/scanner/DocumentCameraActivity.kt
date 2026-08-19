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
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.masahhisabat.app.R
import java.io.File

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
    private var camera: Camera? = null
    private var torchEnabled = false
    private var captureInProgress = false

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyGalleryImageAndEdit(uri)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "يلزم السماح بالكاميرا لمسح المستندات", Toast.LENGTH_LONG).show()
            finish()
        }
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
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
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
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                val available = camera?.cameraInfo?.hasFlashUnit() == true
                flashButton.isEnabled = available
                flashButton.alpha = if (available) 1f else 0.45f
                statusText.text = "وجّه المستند داخل الإطار ثم التقط الصورة"
            } catch (_: Exception) {
                Toast.makeText(this, "تعذر تشغيل كاميرا المستندات", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
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
        captureButton.isEnabled = !capturing
        captureButton.alpha = if (capturing) 0.55f else 1f
        statusText.text = message
    }

    /** طبقة رسم خفيفة توضح مساحة المستند وتُبقي مناطق الكاميرا المحيطة منخفضة التباين. */
    class DocumentFrameOverlay(context: android.content.Context) : View(context) {
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
