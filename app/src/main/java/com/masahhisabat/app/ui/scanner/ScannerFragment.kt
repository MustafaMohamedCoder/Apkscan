package com.masahhisabat.app.ui.scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.GroupActivity
import java.io.File

class ScannerFragment : Fragment() {

    private var isScannerBusy = false

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && isAdded) {
            // نسخ ملف المعرض قد يكون كبيرًا؛ لا نسمح له بحجب واجهة الماسح.
            val ctx = requireContext().applicationContext
            setScannerBusy(true, "يجري تجهيز الصورة للمعالجة…")
            Thread {
                val copied = copyToInternal(ctx, uri)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    setScannerBusy(false)
                    if (copied != null) showPostScanOptions(copied)
                    else Toast.makeText(requireContext(), "تعذر قراءة الصورة", Toast.LENGTH_SHORT).show()
                }
            }.apply { name = "scanner-gallery-copy"; start() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)
        refreshRecentScans()

        view.findViewById<FrameLayout>(R.id.btn_camera).setOnClickListener { checkCameraAndOpen() }
        view.findViewById<FrameLayout>(R.id.btn_gallery).setOnClickListener {
            if (isScannerBusy) return@setOnClickListener
            try {
                galleryLauncher.launch("image/*")
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "تعذر فتح المعرض على هذا الجهاز", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && view != null) refreshRecentScans()
    }

    private fun applyTheme(view: View) {
        val ctx = requireContext()
        view.setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<View>(R.id.scanner_root).setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<TextView>(R.id.title).apply {
            setTextColor(ThemeHelper.text(ctx))
            typeface = ctx.resources.getFont(R.font.tajawal_bold)
        }
        view.findViewById<TextView>(R.id.hint).setTextColor(ThemeHelper.textSecondary(ctx))
        view.findViewById<TextView>(R.id.scanner_summary).setTextColor(ThemeHelper.textSecondary(ctx))
        view.findViewById<TextView>(R.id.scan_methods_label).setTextColor(ThemeHelper.text(ctx))
        view.findViewById<TextView>(R.id.recent_title).setTextColor(ThemeHelper.text(ctx))
        view.findViewById<TextView>(R.id.recent_scans_empty).setTextColor(ThemeHelper.textSecondary(ctx))
        view.findViewById<TextView>(R.id.scan_status_text).setTextColor(ThemeHelper.textSecondary(ctx))
        view.findViewById<MaterialCardView>(R.id.recent_scans_card).apply {
            setCardBackgroundColor(ThemeHelper.surface(ctx))
            strokeColor = ThemeHelper.cardStroke(ctx)
        }
        // البطاقات الرئيسية بتدرج Teal ثابت ونصوصها بيضاء — لا حاجة لتلوين ديناميكي
    }

    /** يعرض آخر المستندات المضافة من السكانر ويوفر اختصارًا للعودة إلى مجموعتها. */
    private fun refreshRecentScans() {
        if (!isAdded || view == null) return
        try {
            val groups = AppRepository.groups()
            val recent = groups.flatMap { group ->
                AppRepository.items(group.id)
                    .filter { it.type == "image" && !it.imagePath.isNullOrBlank() }
                    .map { group to it }
            }.sortedByDescending { it.second.createdAt }
            val ctx = requireContext()
            view?.findViewById<TextView>(R.id.scanner_summary)?.text =
                "${recent.size} مستند ممسوح · ${groups.size} مجموعات"
            val container = view?.findViewById<LinearLayout>(R.id.recent_scans_container) ?: return
            val empty = view?.findViewById<TextView>(R.id.recent_scans_empty) ?: return
            container.removeAllViews()
            val visible = recent.take(3)
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            visible.forEach { (group, item) ->
                val card = MaterialCardView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8.dp(ctx) }
                    radius = 18.dp(ctx).toFloat()
                    cardElevation = 2.dp(ctx).toFloat()
                    setCardBackgroundColor(ThemeHelper.surface(ctx))
                    strokeColor = ThemeHelper.cardStroke(ctx)
                    strokeWidth = 1.dp(ctx)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(Intent(ctx, GroupActivity::class.java).putExtra("group_id", group.id))
                    }
                }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(14.dp(ctx), 12.dp(ctx), 14.dp(ctx), 12.dp(ctx))
                }
                val icon = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(42.dp(ctx), 42.dp(ctx))
                    setImageResource(R.drawable.ic_invoice)
                    setColorFilter(ThemeHelper.accent(ctx))
                    contentDescription = "مستند ممسوح"
                }
                val textColumn = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(12.dp(ctx), 0, 0, 0)
                }
                val title = TextView(ctx).apply {
                    text = group.name
                    setTextColor(ThemeHelper.text(ctx))
                    typeface = ctx.resources.getFont(R.font.tajawal_bold)
                    textSize = 15f
                    maxLines = 1
                }
                val date = TextView(ctx).apply {
                    val formatted = java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(item.createdAt))
                    text = "مستند ممسوح · $formatted"
                    setTextColor(ThemeHelper.textSecondary(ctx))
                    typeface = ctx.resources.getFont(R.font.tajawal_medium)
                    textSize = 12f
                    setPadding(0, 4.dp(ctx), 0, 0)
                }
                textColumn.addView(title)
                textColumn.addView(date)
                row.addView(icon)
                row.addView(textColumn)
                card.addView(row)
                container.addView(card)
            }
        } catch (_: Exception) {
            // لا نمنع فتح السكانر إذا تعذر تحميل القائمة الأخيرة.
        }
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun checkCameraAndOpen() {
        val ctx = requireContext()
        val hasCamera = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            Toast.makeText(ctx, "لا توجد كاميرا في هذا الجهاز — اختر من المعرض", Toast.LENGTH_SHORT).show()
            return
        }
        if (isScannerBusy) return
        try {
            startActivity(Intent(ctx, DocumentCameraActivity::class.java))
        } catch (_: Exception) {
            Toast.makeText(ctx, "تعذر فتح الكاميرا — اختر صورة من المعرض", Toast.LENGTH_LONG).show()
        }
    }

    private fun setScannerBusy(busy: Boolean, message: String = "") {
        isScannerBusy = busy
        val screen = view ?: return
        val camera = screen.findViewById<FrameLayout>(R.id.btn_camera)
        val gallery = screen.findViewById<FrameLayout>(R.id.btn_gallery)
        camera.isEnabled = !busy
        gallery.isEnabled = !busy
        camera.alpha = if (busy) 0.55f else 1f
        gallery.alpha = if (busy) 0.55f else 1f
        screen.findViewById<View>(R.id.scanner_status).visibility = if (busy) View.VISIBLE else View.GONE
        screen.findViewById<TextView>(R.id.scan_status_text).text = message
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
        // يفتح محرر القص مباشرةً: خيارات إخراج النسخة المحسنة تظهر بعد المعالجة.
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        startCrop(imagePath, action = CropEditActivity.ACTION_NEW_INVOICE)
    }

    private fun startCrop(imagePath: String, action: String) {
        val intent = Intent(requireContext(), CropEditActivity::class.java)
        intent.putExtra(CropEditActivity.EXTRA_IMAGE_PATH, imagePath)
        intent.putExtra(CropEditActivity.EXTRA_ACTION, action)
        startActivity(intent)
    }

}
