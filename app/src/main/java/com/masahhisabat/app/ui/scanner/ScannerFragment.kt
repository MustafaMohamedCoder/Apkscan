package com.masahhisabat.app.ui.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.currentInvoiceName
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.GroupActivity
import java.io.File

class ScannerFragment : Fragment() {

    private var lastCapturePath: String? = null
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturePath = lastCapturePath
        if (success && capturePath != null && isAdded) {
            showPostScanOptions(capturePath)
        } else if (!success) {
            lastCapturePath = null
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && isAdded) {
            // نسخ ملف المعرض قد يكون كبيرًا؛ لا نسمح له بحجب واجهة الماسح.
            val ctx = requireContext().applicationContext
            Thread {
                val copied = copyToInternal(ctx, uri)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (copied != null) showPostScanOptions(copied)
                    else Toast.makeText(requireContext(), "تعذر قراءة الصورة", Toast.LENGTH_SHORT).show()
                }
            }.apply { name = "scanner-gallery-copy"; start() }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera() else
            Toast.makeText(requireContext(), "تم رفض إذن الكاميرا", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)

        view.findViewById<FrameLayout>(R.id.btn_camera).setOnClickListener { checkCameraAndOpen() }
        view.findViewById<FrameLayout>(R.id.btn_gallery).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
    }

    private fun applyTheme(view: View) {
        view.setBackgroundColor(ThemeHelper.bg(requireContext()))
        view.findViewById<View>(R.id.scanner_root).setBackgroundColor(ThemeHelper.bg(requireContext()))
        view.findViewById<TextView>(R.id.title).setTextColor(ThemeHelper.text(requireContext()))
        view.findViewById<TextView>(R.id.hint).setTextColor(ThemeHelper.textSecondary(requireContext()))
        // البطاقات بتدرج Teal ثابت ونصوصها بيضاء — لا حاجة لتلوين ديناميكي
    }

    private fun checkCameraAndOpen() {
        val ctx = requireContext()
        val hasCamera = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            Toast.makeText(ctx, "لا توجد كاميرا في هذا الجهاز — اختر من المعرض", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                openCamera()
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val file = File(requireContext().cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        lastCapturePath = file.absolutePath
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        cameraLauncher.launch(uri)
    }

    private fun copyToInternal(context: Context, uri: Uri): String? {
        val file = File(context.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use {
                val inputStream = it
                file.outputStream().use { output -> inputStream.copyTo(output) }
            }
            if (file.exists() && file.length() > 0L) {
                file.absolutePath
            } else {
                file.delete()
                null
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun showPostScanOptions(imagePath: String) {
        val ctx = requireContext()
        // اهتزاز خفيف. كشف الحواف يبدأ عند فتح محرر القص حتى لا يعطّل اختيار الإجراء.
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))

        val options = arrayOf(
            getString(R.string.save_gallery),
            getString(R.string.new_invoice),
            getString(R.string.add_to_invoice),
            getString(R.string.share)
        )

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.save_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveToGallery(imagePath)
                    1 -> startCrop(imagePath, action = CropEditActivity.ACTION_NEW_INVOICE)
                    2 -> startCrop(imagePath, action = CropEditActivity.ACTION_ADD_TO_INVOICE)
                    3 -> shareImage(imagePath)
                }
            }
            .show()
    }

    private fun startCrop(imagePath: String, action: String) {
        val intent = Intent(requireContext(), CropEditActivity::class.java)
        intent.putExtra(CropEditActivity.EXTRA_IMAGE_PATH, imagePath)
        intent.putExtra(CropEditActivity.EXTRA_ACTION, action)
        startActivity(intent)
    }

    private fun saveToGallery(path: String) {
        val ctx = requireContext()
        Thread {
            var bmp: android.graphics.Bitmap? = null
            val error = try {
                bmp = ImageProcessor.loadBitmap(path, 2048)
                val image = requireNotNull(bmp) { "تعذر قراءة الصورة" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = ctx.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "masah_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MasahHisabat")
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("تعذر إنشاء ملف الصورة")
                    resolver.openOutputStream(uri)?.use { out ->
                        if (!image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)) {
                            throw IllegalStateException("تعذر ضغط الصورة")
                        }
                    } ?: throw IllegalStateException("تعذر فتح ملف الصورة")
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MasahHisabat")
                    dir.mkdirs()
                    val file = File(dir, "masah_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use {
                        if (!image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)) {
                            throw IllegalStateException("تعذر ضغط الصورة")
                        }
                    }
                    ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply { data = Uri.fromFile(file) })
                }
                null
            } catch (e: Exception) {
                e.message ?: "تعذر حفظ الصورة"
            } finally {
                bmp?.takeIf { !it.isRecycled }?.recycle()
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (error == null) Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()
                else Toast.makeText(ctx, "فشل الحفظ: $error", Toast.LENGTH_SHORT).show()
            }
        }.apply { name = "scanner-gallery-save"; start() }
    }

    private fun shareImage(path: String) {
        try {
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", File(path))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
        }
    }
}
