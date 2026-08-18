package com.masahhisabat.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.LoginActivity
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.team.TeamActivity

/**
 * الإعدادات: تبديل الوضع، إدارة الحسابات المحلية، تغيير كلمة المرور،
 * سجل نشاط المستخدمين، تصدير/استيراد نسخة احتياطية، تسجيل الخروج.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val IMPORT_REQUEST = 101
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)
        setupItem(view, R.id.item_theme, R.id.tv_theme_title, getString(R.string.toggle_theme)) {
            ThemeHelper.toggleTheme(requireContext())
            activity?.recreate()
        }
        // إخفاء «إدارة الفريق» عن كل الحسابات عدا mustafa فقط
        // إخفاء «إدارة الفريق» وخيار تغيير كلمة المرور عن كل الحسابات عدا mustafa فقط
        view.findViewById<LinearLayout>(R.id.item_accounts)?.visibility =
            if (SessionStore.currentUser(requireContext()) == "mustafa") View.VISIBLE else View.GONE
        // تم حذف خيار «تغيير كلمة المرور» نهائيًا من الإعدادات بأمر المستخدم
        setupItem(view, R.id.item_accounts, R.id.tv_accounts_title, getString(R.string.manage_accounts)) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canAdmin(role)) {
                startActivity(Intent(requireContext(), TeamActivity::class.java))
            } else {
                Toast.makeText(requireContext(), "هذه الخاصية للمالك والمشرف فقط", Toast.LENGTH_SHORT).show()
            }
        }
        setupItem(view, R.id.item_activity, R.id.tv_activity_title, getString(R.string.activity_log)) { showActivityLogDialog() }
        setupItem(view, R.id.item_export, R.id.tv_export_title, getString(R.string.export_data)) { exportData() }
        setupItem(view, R.id.item_import, R.id.tv_import_title, getString(R.string.import_backup)) { importBackup() }
        setupItem(view, R.id.item_sync, R.id.tv_sync_title, getString(R.string.local_sync)) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canSync(role)) showSyncDialog()
            else Toast.makeText(requireContext(), "هذه الخاصية للمدير والمشرف فقط", Toast.LENGTH_SHORT).show()
        }
        // placeholder to keep edits valid
        setupItem(view, R.id.item_logout, R.id.tv_logout_title, getString(R.string.logout), errorText = true) { confirmLogout() }
    }

    private fun setupItem(view: View, rowId: Int, titleId: Int, title: String, errorText: Boolean = false, action: () -> Unit) {
        val row = view.findViewById<LinearLayout>(rowId) ?: return
        val tv = row.findViewById<TextView>(titleId)
        tv.text = title
        if (errorText) tv.setTextColor(resources.getColor(R.color.error, null))
        row.setOnClickListener { action() }
    }

    private fun applyTheme(view: View) {
        val ctx = requireContext()
        view.setBackgroundColor(ThemeHelper.bg(ctx))
        val text = ThemeHelper.text(ctx)
        val textSec = ThemeHelper.textSecondary(ctx)
        // الصفوف بخلفية card_surface_settings (قابلة للتكيف مع الوضع) — لا نكتب فوقها، بل نلوّنها ديناميكيًا
        val rowBg = ThemeHelper.surfaceHigh(ctx)
        listOf(R.id.item_theme, R.id.item_accounts, R.id.item_activity,
            R.id.item_export, R.id.item_import, R.id.item_sync, R.id.item_logout).forEach { id ->
            view.findViewById<LinearLayout>(id)?.background?.setTint(rowBg)
        }
        listOf(R.id.tv_theme_title, R.id.tv_accounts_title,
            R.id.tv_activity_title, R.id.tv_export_title, R.id.tv_import_title, R.id.tv_sync_title).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(text)
        }
        view.findViewById<TextView>(R.id.tv_title)?.setTextColor(text)
        view.findViewById<TextView>(R.id.version_text)?.setTextColor(textSec)
    }

    private fun showActivityLogDialog() {
        val ctx = requireContext()
        val log = AppRepository.activityLog()
        val lines = if (log.isEmpty()) listOf("لا يوجد نشاط حتى الآن")
        else log.takeLast(50).map { "${it.at} — ${it.action}" }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.activity_log)
            .setItems(lines.toTypedArray(), null)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun exportData() {
        val ctx = requireContext()
        try {
            val zipFile = AppRepository.exportData(ctx.cacheDir.parentFile ?: ctx.cacheDir)
            // نسخ الملف إلى المجلد المشترك Downloads
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val dest = java.io.File(downloads, "masah_backup_${System.currentTimeMillis() / 1000}.zip")
            zipFile.copyTo(dest, overwrite = true)
            val user = SessionStore.currentUser(ctx) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, "صدّر $user نسخة احتياطية"))
            Toast.makeText(ctx, "تم التصدير: ${dest.absolutePath}", Toast.LENGTH_LONG).show()
            (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Toast.makeText(ctx, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, IMPORT_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "تعذر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    /** حوار المزامنة عبر الشبكة المحلية: اكتشاف الأجهزة ثم المزامنة بالنقر عليها */
    private fun showSyncDialog() {
        val ctx = requireContext()
        SyncManager.ensureServer(ctx)

        // واجهة التقدم أثناء البحث
        val progress = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            val bar = android.widget.ProgressBar(ctx)
            bar.layoutParams = android.widget.LinearLayout.LayoutParams(96, 96).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            addView(bar)
            val tv = android.widget.TextView(ctx)
            tv.text = "جارٍ البحث عن أجهزة على الشبكة..."
            tv.gravity = android.view.Gravity.CENTER
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 16 }
            addView(tv)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.sync_available_devices)
            .setView(progress)
            .setNegativeButton(R.string.close) { _, _ -> }
            .show()

        // البحث في الخلفية
        Thread {
            val peers = SyncManager.discover(4000)
            activity?.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (peers.isEmpty()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.sync_available_devices)
                        .setMessage("لم يُعثر على أجهزة. تأكد أن التطبيق مفتوح على الجهاز الآخر وأنهما على نفس شبكة Wi-Fi.")
                        .setNegativeButton(R.string.close, null)
                        .show()
                } else {
                    val names = peers.map { "${it.name} — ${it.address}" }.toTypedArray()
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("الأجهزة المكتشفة — اضغط للمزامنة")
                        .setItems(names) { _, which ->
                            val peer = peers[which]
                            val syncDialog = showSyncProgressDialog(peer.name)
                            Thread {
                                val result = SyncManager.syncWithHost(ctx, peer.address) { percent, status ->
                                    activity?.runOnUiThread {
                                        updateSyncProgress(syncDialog, percent, status)
                                    }
                                }
                                activity?.runOnUiThread {
                                    if (syncDialog.isShowing) syncDialog.dismiss()
                                    val (title, message) = if (result.ok) {
                                        "تمت المزامنة بنجاح" to
                                            "اكتملت مزامنة ${peer.name}. استُقبلت ${result.itemsReceived} عناصر و${result.usersReceived} مستخدمين."
                                    } else {
                                        "فشلت المزامنة" to
                                            "تعذر الاتصال بـ ${peer.name}. تأكد أن التطبيق مفتوح على الجهاز الآخر وأنكما على نفس شبكة Wi-Fi."
                                    }
                                    if (isAdded) {
                                        MaterialAlertDialogBuilder(ctx)
                                            .setTitle(title)
                                            .setMessage(message)
                                            .setPositiveButton("حسنًا", null)
                                            .show()
                                    }
                                }
                            }.start()
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
        }.start()
    }

    /** نافذة حالة المزامنة: نسبة مرئية تُحدّث من مراحل النقل الفعلية. */
    private fun showSyncProgressDialog(peerName: String): androidx.appcompat.app.AlertDialog {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 38, 48, 32)

            addView(TextView(ctx).apply {
                id = R.id.sync_progress_status
                text = "جارٍ تجهيز المزامنة مع $peerName..."
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setTextColor(ThemeHelper.text(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            addView(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = R.id.sync_progress_bar
                isIndeterminate = false
                max = 100
                progress = 5
                progressTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.accent(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    12
                ).apply { topMargin = 26 }
            })

            addView(TextView(ctx).apply {
                id = R.id.sync_progress_percent
                text = "5%"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                setTextColor(ThemeHelper.accent(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            })
        }
        return MaterialAlertDialogBuilder(ctx)
            .setTitle("جارٍ المزامنة")
            .setView(content)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun updateSyncProgress(dialog: androidx.appcompat.app.AlertDialog, percent: Int, status: String) {
        if (!dialog.isShowing) return
        val progress = percent.coerceIn(0, 100)
        dialog.findViewById<android.widget.ProgressBar>(R.id.sync_progress_bar)?.progress = progress
        dialog.findViewById<TextView>(R.id.sync_progress_percent)?.text = "$progress%"
        dialog.findViewById<TextView>(R.id.sync_progress_status)?.text = status
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_REQUEST && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri == null) return
            requireActivity().contentResolver.openInputStream(uri)?.use { stream ->
                val tmp = java.io.File(requireContext().cacheDir, "import_${System.currentTimeMillis() / 1000}.zip")
                tmp.outputStream().use { out -> stream.copyTo(out) }
                val result = try { AppRepository.importBackup(tmp); true } catch (e: Exception) { false }
                tmp.delete()
                if (result) {
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "استورد $user نسخة احتياطية"))
                    Toast.makeText(requireContext(), "تم الاستيراد بنجاح", Toast.LENGTH_LONG).show()
                    activity?.recreate()
                } else {
                    Toast.makeText(requireContext(), "فشل الاستيراد — الملف تالف", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmLogout() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.logout)
            .setMessage("هل أنت متأكد من تسجيل الخروج؟")
            .setPositiveButton(R.string.logout) { _, _ ->
                SessionStore.logout(ctx)
                AppRepository.logActivity(ActivityEntry(SessionStore.currentUser(ctx) ?: "?", "سجّل المستخدم خروج"))
                val intent = Intent(ctx, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
