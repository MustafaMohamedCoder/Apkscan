package com.masahhisabat.app.ui.scanner

import android.content.Context
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import java.io.File
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.data.InvoiceExtractor
import com.masahhisabat.app.data.currentInvoiceName
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.image.ProcessMode
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore

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
        private const val REQUEST_SAVE_GALLERY = 319
    }

    private lateinit var cropView: CropView
    private lateinit var originalBmp: android.graphics.Bitmap
    private var processedBmp: android.graphics.Bitmap? = null
    private var previewBmp: android.graphics.Bitmap? = null
    private var imagePath: String = ""
    private var action: String = ACTION_NEW_INVOICE
    private var lastMode = ProcessMode.AUTO
    private var processing = false
    private var filterPreviewInProgress = false
    private var previewMode: ProcessMode? = null
    private var pendingGalleryBitmap: Bitmap? = null
    private var edgeDetectionInProgress = false
    private var edgeDetectionToken = 0
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var editorStatus: TextView
    private lateinit var filterModeLabel: TextView

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

        originalBmp = try {
            ImageProcessor.loadBitmap(imagePath)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر فتح الصورة الملتقطة، أعد المحاولة", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        cropView = findViewById(R.id.crop_view)
        cropView.setBitmap(originalBmp)
        loadingPanel = findViewById(R.id.loading_panel)
        loadingLabel = findViewById(R.id.loading_message)
        editorStatus = findViewById(R.id.editor_status)
        filterModeLabel = findViewById(R.id.filter_mode_label)
        updateFilterModeLabel()

        findViewById<MaterialButton>(R.id.btn_rotate).setOnClickListener {
            val croppedForRotation = cropView.getCroppedBitmap()
            val rotated = ImageProcessor.rotateBitmap(croppedForRotation, -90)
            if (rotated !== croppedForRotation && !croppedForRotation.isRecycled) croppedForRotation.recycle()
            if (!originalBmp.isRecycled) originalBmp.recycle()
            processedBmp?.takeIf { !it.isRecycled }?.recycle()
            previewBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
            cropView.setBitmap(rotated)
            originalBmp = rotated
            processedBmp = null
            previewBmp = null
            previewMode = null
            lastMode = ProcessMode.AUTO
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

        findViewById<MaterialButton>(R.id.btn_filter).setOnClickListener {
            showFilterPicker()
        }

        findViewById<MaterialButton>(R.id.btn_compare).setOnClickListener {
            showComparison()
        }

        findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            if (processing || filterPreviewInProgress) return@setOnClickListener
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
        previewBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
        processedBmp?.takeIf { it !== originalBmp && it !== previewBmp && !it.isRecycled }?.recycle()
        processedBmp = null
        previewBmp = null
        super.onDestroy()
    }

    private fun applyTheme() {
        window.decorView.setBackgroundColor(ThemeHelper.bg(this))
        findViewById<View>(R.id.crop_root).setBackgroundColor(Color.BLACK)
    }

    private fun processAndContinue() {
        if (edgeDetectionInProgress) return
        processing = true
        setEditorControlsEnabled(false)
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE

        editorStatus.text = "يجري تجهيز المستند للحفظ"
        // نقتطع من الصورة الأصلية حتى لا يتكرر الفلتر عند تطبيقه للحفظ النهائي.
        val cropped = cropView.getCroppedBitmap(originalBmp)
        // المعاينة لا تُحفظ مباشرة: يعاد تطبيق الفلتر على الجزء المقصوص بالحجم الكامل.
        ImageProcessor.process(lastMode, cropped, object : ImageProcessor.Callback {
            override fun onDone(bitmap: android.graphics.Bitmap) {
                processing = false
                setEditorControlsEnabled(true)
                loadingPanel.visibility = View.GONE
                if (bitmap !== cropped && !cropped.isRecycled) cropped.recycle()
                processedBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
                processedBmp = bitmap
                AppRepository.setLastProcessMode(lastMode.key)
                showSuccessAndContinue(bitmap)
            }
            override fun onError() {
                processing = false
                setEditorControlsEnabled(true)
                loadingPanel.visibility = View.GONE
                Toast.makeText(this@CropEditActivity, "فشلت المعالجة — جاري استخدام الأصلية", Toast.LENGTH_SHORT).show()
                processedBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
                processedBmp = cropped
                showSuccessAndContinue(cropped)
            }
        })
    }

    /** يتيح تبديل الفلاتر مع معاينة حقيقية للصورة قبل الحفظ. */
    private fun showFilterPicker() {
        if (edgeDetectionInProgress || processing || filterPreviewInProgress) return
        val modes = arrayOf(
            ProcessMode.ORIGINAL,
            ProcessMode.AUTO,
            ProcessMode.MAGIC_COLOR,
            ProcessMode.NATURAL,
            ProcessMode.WARM_PAPER,
            ProcessMode.SOFT_GRAY,
            ProcessMode.BLUE_INK,
            ProcessMode.DARK_INK,
            ProcessMode.LOW_LIGHT,
            ProcessMode.DOCUMENT,
            ProcessMode.HIGH_CONTRAST,
            ProcessMode.BW,
            ProcessMode.CLEAN_BW,
            ProcessMode.INK_BW
        )
        val checked = modes.indexOf(lastMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_document_filter)
            .setSingleChoiceItems(
                modes.map { "${it.label}\n${it.description}" }.toTypedArray(),
                checked
            ) { dialog, which ->
                dialog.dismiss()
                applyFilterPreview(modes[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyFilterPreview(mode: ProcessMode) {
        if (filterPreviewInProgress || originalBmp.isRecycled) return
        filterPreviewInProgress = true
        setFilterUi(loading = true)
        ImageProcessor.process(mode, originalBmp, object : ImageProcessor.Callback {
            override fun onDone(bitmap: Bitmap) {
                if (isFinishing || isDestroyed) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return
                }
                filterPreviewInProgress = false
                setFilterUi(loading = false)
                previewBmp?.takeIf { it !== originalBmp && !it.isRecycled }?.recycle()
                previewBmp = if (mode == ProcessMode.ORIGINAL) null else bitmap
                if (mode == ProcessMode.ORIGINAL && !bitmap.isRecycled) bitmap.recycle()
                cropView.setBitmap(previewBmp ?: originalBmp)
                lastMode = mode
                previewMode = mode
                updateFilterModeLabel()
                editorStatus.text = "معاينة ${mode.label} — عدّل الإطار أو تابع"
                Toast.makeText(this@CropEditActivity, getString(R.string.filter_preview_ready, mode.label), Toast.LENGTH_SHORT).show()
            }

            override fun onError() {
                if (isFinishing || isDestroyed) return
                filterPreviewInProgress = false
                setFilterUi(loading = false)
                Toast.makeText(this@CropEditActivity, "تعذر تطبيق الفلتر. يمكنك المتابعة بالصورة الأصلية.", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setEditorControlsEnabled(enabled: Boolean) {
        intArrayOf(
            R.id.btn_rotate,
            R.id.btn_grid,
            R.id.btn_crop_center,
            R.id.btn_crop_auto,
            R.id.btn_filter,
            R.id.btn_compare,
            R.id.btn_done
        ).forEach { id -> findViewById<View>(id).isEnabled = enabled }
    }

    private fun setFilterUi(loading: Boolean) {
        loadingLabel.setText(if (loading) R.string.filter_preview_loading else R.string.loading)
        loadingPanel.visibility = if (loading) View.VISIBLE else View.GONE
        setEditorControlsEnabled(!loading)
    }

    private fun updateFilterModeLabel() {
        if (::filterModeLabel.isInitialized) {
            filterModeLabel.text = when (lastMode) {
                ProcessMode.AUTO -> "التحسين التلقائي جاهز"
                ProcessMode.ORIGINAL -> "الصورة الأصلية دون تحسين"
                ProcessMode.MAGIC_COLOR -> "Magic Color — ألوان الورق والنصوص محسّنة"
                ProcessMode.BW -> "مستند أبيض وأسود عالي الوضوح"
                ProcessMode.CLEAN_BW -> "أبيض وأسود نظيف — جاهز للطباعة والمشاركة"
                ProcessMode.INK_BW -> "حبر شديد الوضوح — مناسب للخطوط والكتابة اليدوية"
                else -> "${lastMode.label} — ${lastMode.description}"
            }
        }
    }

    /** يشغّل الكشف خارج خيط الواجهة ويطبق الإطار فقط إذا بقيت الصورة نفسها نشطة. */
    private fun detectAndApplyEdges(showResult: Boolean) {
        if (edgeDetectionInProgress || !::originalBmp.isInitialized || originalBmp.isRecycled) return
        val bitmap = originalBmp
        val token = ++edgeDetectionToken
        edgeDetectionInProgress = true
        if (::editorStatus.isInitialized) editorStatus.text = "يجري اكتشاف حواف المستند…"
        setEdgeDetectionUi(detecting = true)

        Thread {
            val correction = try {
                ImageProcessor.detectAndCorrectDocument(bitmap)
            } catch (_: Throwable) {
                null
            }
            val detection = correction?.detection
            val straightened = correction?.correctedBitmap
            runOnUiThread {
                if (isFinishing || isDestroyed || token != edgeDetectionToken || !::cropView.isInitialized) {
                    straightened?.takeIf { !it.isRecycled }?.recycle()
                    return@runOnUiThread
                }
                edgeDetectionInProgress = false
                setEdgeDetectionUi(detecting = false)

                if (straightened != null) {
                    val oldOriginal = originalBmp
                    previewBmp?.takeIf { it !== oldOriginal && !it.isRecycled }?.recycle()
                    previewBmp = null
                    processedBmp?.takeIf { it !== oldOriginal && !it.isRecycled }?.recycle()
                    processedBmp = null
                    originalBmp = straightened
                    cropView.setBitmap(straightened)
                    cropView.setCropRect(0.035f, 0.035f, 0.965f, 0.965f)
                    val confidencePercent = (((detection?.confidence ?: 0f) * 100f).toInt())
                    editorStatus.text = "تم تصحيح منظور المستند تلقائيًا — دقة الكشف $confidencePercent%"
                    if (!oldOriginal.isRecycled) oldOriginal.recycle()
                    if (showResult) Toast.makeText(this, R.string.document_perspective_fixed, Toast.LENGTH_LONG).show()
                } else if (detection != null) {
                    val bounds = detection.bounds
                    cropView.setCropRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    editorStatus.text = if (detection.isDocumentDetected) {
                        "تم اقتراح حواف المستند — يمكنك ضبطها"
                    } else {
                        "اضبط إطار المستند يدويًا"
                    }
                    if (showResult) {
                        Toast.makeText(
                            this,
                            when {
                                !detection.isDocumentDetected -> R.string.edge_detection_failed
                                detection.confidence < 0.86f -> R.string.edge_detection_low_confidence
                                else -> R.string.auto_edge
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    cropView.setCropRect(0.035f, 0.035f, 0.965f, 0.965f)
                    editorStatus.text = "لم تتضح الحواف — استخدم مقابض الإطار يدويًا"
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
            R.id.btn_filter,
            R.id.btn_compare,
            R.id.btn_done
        ).forEach { id -> findViewById<View>(id).isEnabled = !detecting }
    }

    private fun showSuccessAndContinue(bitmap: android.graphics.Bitmap) {
        val ctx = this
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()

        val existingPages = PdfSessionManager.pageCount(this)
        val options = mutableListOf(
            getString(R.string.save_gallery),
            getString(R.string.add_to_group),
            getString(R.string.share),
            "استخراج النص محلياً",
            "تصدير كملف PDF${if (existingPages > 0) " ($existingPages صفحات محفوظة)" else ""}",
            "إضافة صفحة إلى جلسة PDF متعددة الصفحات"
        )
        if (existingPages > 0) options += "إلغاء جلسة PDF الحالية"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.save_options)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> requestSaveToGallery(bitmap)
                    1 -> chooseGroupAndAdd(bitmap)
                    2 -> shareBitmap(bitmap)
                    3 -> extractTextLocally(bitmap)
                    4 -> exportPdf(bitmap)
                    5 -> addPageToPdfSession(bitmap)
                    6 -> {
                        PdfSessionManager.clear(this)
                        Toast.makeText(this, "ألغيت جلسة PDF", Toast.LENGTH_SHORT).show()
                        showSuccessAndContinue(bitmap)
                    }
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun addPageToPdfSession(bitmap: Bitmap) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            val result = runCatching { PdfSessionManager.appendPage(this, bitmap) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                result.onSuccess { count ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("جلسة PDF متعددة الصفحات")
                        .setMessage("أضيفت الصفحة $count. التقط الصفحة التالية ثم اختر تصدير PDF عند اكتمال المستند.")
                        .setPositiveButton("التقاط صفحة أخرى") { _, _ ->
                            startActivity(Intent(this, DocumentCameraActivity::class.java).apply {
                                putExtra("pdf_page_count", count)
                            })
                            finish()
                        }
                        .setNegativeButton(R.string.close) { _, _ -> finish() }
                        .show()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "تعذر إضافة الصفحة", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "pdf-session-add-page"; start() }
    }

    private fun extractTextLocally(bitmap: Bitmap) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            val extracted = runCatching { InvoiceExtractor.extract(this, bitmap) }.getOrNull()
            val text = extracted?.rawText.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                MaterialAlertDialogBuilder(this)
                    .setTitle("النص المستخرج محلياً")
                    .setMessage(text.ifBlank { "لم يتم العثور على نص واضح في المستند. جرّب فلتر التباين أو صورة أوضح." })
                    .setPositiveButton("نسخ النص") { _, _ ->
                        if (text.isNotBlank()) {
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("نص المستند", text))
                            Toast.makeText(this, "تم نسخ النص", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNeutralButton("إضافة إلى مجموعة") { _, _ -> chooseGroupAndAdd(bitmap, text.ifBlank { null }) }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }.apply { name = "local-ocr"; start() }
    }

    private fun exportPdf(bitmap: Bitmap) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            val result = runCatching { PdfSessionManager.exportCurrentSession(this, bitmap) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                result.onSuccess { message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }.onFailure { error ->
                    Toast.makeText(this, error.message ?: "تعذر تصدير PDF", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "pdf-session-export"; start() }
    }

    private fun newFile(bitmap: android.graphics.Bitmap): String {
        val dir = File(AppRepository.dataDir(this), "scans")
        return ImageProcessor.saveTo(bitmap, dir, "scan").absolutePath
    }

    private fun chooseGroupAndAdd(bitmap: Bitmap, documentText: String? = null) {
        val groups = AppRepository.groups()
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.no_groups_for_image, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_group)
            .setItems(groups.map { it.name }.toTypedArray()) { _, which ->
                addImageToGroup(bitmap, groups[which].id, groups[which].name, documentText)
            }
            .show()
    }

    private fun addImageToGroup(bitmap: Bitmap, groupId: String, groupName: String, documentText: String? = null) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            val error = try {
                val scanPath = newFile(bitmap)
                val persistentPath = AppRepository.persistAppImage(scanPath)
                    ?: throw IllegalStateException("تعذر حفظ الصورة في التخزين الدائم")
                AppRepository.addItem(groupId, InvoiceItem(
                    type = "image",
                    imagePath = persistentPath,
                    processedPath = null,
                    documentText = documentText
                ))
                val user = SessionStore.currentUser(this) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, "أضاف $user مستندًا ممسوحًا إلى $groupName"))
                if (persistentPath != scanPath) File(scanPath).delete()
                null
            } catch (e: Exception) {
                e.message ?: "تعذر إضافة الصورة إلى المجموعة"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                if (error == null) {
                    Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "scan-add-to-group"; start() }
    }

    /** يطلب إذن الكتابة فقط للإصدارات التي تحتاجه قبل Android 10. */
    private fun requestSaveToGallery(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGalleryBitmap = bitmap
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_SAVE_GALLERY
            )
            return
        }
        saveToGallery(bitmap)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SAVE_GALLERY) return
        val bitmap = pendingGalleryBitmap
        pendingGalleryBitmap = null
        if (bitmap != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            saveToGallery(bitmap)
        } else {
            Toast.makeText(this, "يلزم السماح بالوصول للتخزين لحفظ الصورة في المعرض.", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToGallery(bitmap: Bitmap) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            var pendingMediaUri: Uri? = null
            val error = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "masah_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MasahHisabat")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("تعذر إنشاء ملف الصورة")
                    pendingMediaUri = uri
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw IllegalStateException("تعذر حفظ الصورة")
                    } ?: throw IllegalStateException("تعذر فتح ملف الصورة")
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                    pendingMediaUri = null
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MasahHisabat")
                    dir.mkdirs()
                    val file = File(dir, "masah_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw IllegalStateException("تعذر حفظ الصورة")
                    }
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = Uri.fromFile(file) })
                }
                null
            } catch (e: Exception) {
                pendingMediaUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
                e.message ?: "تعذر حفظ الصورة في المعرض"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                if (error == null) {
                    Toast.makeText(this, R.string.success, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "فشل الحفظ: $error", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "scan-gallery-save"; start() }
    }

    private fun shareBitmap(bitmap: Bitmap) {
        processing = true
        loadingLabel.setText(R.string.loading)
        loadingPanel.visibility = View.VISIBLE
        Thread {
            val file = try { ImageProcessor.saveTo(bitmap, cacheDir, "share", quality = 90) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                processing = false
                loadingPanel.visibility = View.GONE
                if (file == null) {
                    Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share)))
                    finish()
                } catch (_: Exception) {
                    Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "scan-image-share"; start() }
    }

    private fun showComparison() {
        val ctx = this
        val processed = previewBmp ?: processedBmp
        val items = listOf("الأصل بعد تصحيح المنظور", "نتيجة ${lastMode.label}")
        val bitmaps = listOf(originalBmp, processed)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("قارن الأصل بالنتيجة")
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
    class CropView @JvmOverloads constructor(
        context: Context,
        attrs: android.util.AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {

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
        private val paintHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val paintHandleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F766E")
            style = Paint.Style.STROKE
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

        fun getCroppedBitmap(source: android.graphics.Bitmap? = bitmap): android.graphics.Bitmap {
            val bmp = source ?: return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
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
            // نرسم الأجزاء الأربعة المحيطة مباشرةً بدل طبقة كاملة ثم مسحها؛
            // هذا يمنع تخصيص Paint وPorterDuffXfermode جديدين في كل إطار رسم.
            val left = cropRect.left * bmp.width
            val top = cropRect.top * bmp.height
            val right = cropRect.right * bmp.width
            val bottom = cropRect.bottom * bmp.height
            val bitmapWidth = bmp.width.toFloat()
            val bitmapHeight = bmp.height.toFloat()
            canvas.drawRect(0f, 0f, bitmapWidth, top, paintOverlay)
            canvas.drawRect(0f, bottom, bitmapWidth, bitmapHeight, paintOverlay)
            canvas.drawRect(0f, top, left, bottom, paintOverlay)
            canvas.drawRect(right, top, bitmapWidth, bottom, paintOverlay)

            // الإطار
            canvas.drawRect(left, top, right, bottom, paintRect)

            // مقابض بارزة تسهّل ضبط إطار المستند على الهاتف والتابلت.
            val handleRadius = 12f / scale
            paintHandleBorder.strokeWidth = 3f / scale
            arrayOf(left to top, right to top, left to bottom, right to bottom).forEach { (hx, hy) ->
                canvas.drawCircle(hx, hy, handleRadius, paintHandle)
                canvas.drawCircle(hx, hy, handleRadius, paintHandleBorder)
            }

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
                    performClick()
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

        override fun performClick(): Boolean {
            super.performClick()
            return true
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
