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
import com.google.android.material.materialswitch.MaterialSwitch
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.data.TrashCleanupScheduler
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.LoginActivity
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.team.TeamActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الإعدادات: تبديل الوضع، إدارة الحسابات المحلية، تغيير كلمة المرور،
 * سجل نشاط المستخدمين، تصدير/استيراد نسخة احتياطية، تسجيل الخروج.
 */
class SettingsFragment : Fragment() {

    companion object {
        private const val IMPORT_REQUEST = 101
        private const val CONTENT_IMPORT_REQUEST = 102
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)
        setupItem(view, R.id.item_theme, R.id.tv_theme_title, themeTitle()) { showThemeDialog() }
        setupItem(view, R.id.item_app_lock, R.id.tv_app_lock_title, appLockTitle()) { showAppLockDialog() }
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
        setupItem(view, R.id.item_trash, R.id.tv_trash_title, trashTitle()) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canDeleteContent(role)) {
                startActivity(Intent(requireContext(), TrashActivity::class.java))
            } else {
                Toast.makeText(requireContext(), "سلة المحذوفات متاحة للمدير والمشرف فقط", Toast.LENGTH_SHORT).show()
            }
        }
        setupAutoTrashPurge(view)
        setupItem(view, R.id.item_help, R.id.tv_help_title, "مركز المساعدة") { showHelpDialog() }
        setupItem(view, R.id.item_export, R.id.tv_export_title, getString(R.string.export_data)) { showExportOptions() }
        setupItem(view, R.id.item_storage, R.id.tv_storage_title, "إدارة التخزين") { showStorageDialog() }
        setupItem(view, R.id.item_import, R.id.tv_import_title, getString(R.string.import_backup)) { showImportOptions() }
        setupItem(view, R.id.item_sync, R.id.tv_sync_title, getString(R.string.local_sync)) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canSync(role)) showSyncDialog()
            else Toast.makeText(requireContext(), "هذه الخاصية للمدير والمشرف فقط", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<TextView>(R.id.tv_sync_summary)?.text = syncSummary()
        // placeholder to keep edits valid
        setupItem(view, R.id.item_logout, R.id.tv_logout_title, getString(R.string.logout), errorText = true) { confirmLogout() }
    }

    private fun setupItem(view: View, rowId: Int, titleId: Int, title: String, errorText: Boolean = false, action: () -> Unit) {
        val row = view.findViewById<LinearLayout>(rowId) ?: return
        val tv = row.findViewById<TextView>(titleId)
        tv.text = title
        if (errorText) tv.setTextColor(resources.getColor(R.color.error, null))
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = title
        row.setOnClickListener { action() }
    }

    private fun themeTitle(): String = "المظهر: ${ThemeHelper.mode().label}"
    private fun appLockTitle(): String = if (AppRepository.hasAppLock()) "قفل التطبيق: مفعّل" else "قفل التطبيق: غير مفعّل"
    private fun trashTitle(): String {
        val count = AppRepository.trashEntries().size
        return if (count == 0) "سلة المحذوفات" else "سلة المحذوفات ($count)"
    }

    /** يتيح للمدير والمشرف فقط التحكم في الحذف النهائي المؤجل لعناصر السلة. */
    private fun setupAutoTrashPurge(view: View) {
        val row = view.findViewById<LinearLayout>(R.id.item_auto_trash_purge) ?: return
        val context = requireContext()
        if (!AppRepository.canDeleteContent(SessionStore.currentRole(context))) {
            row.visibility = View.GONE
            return
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switch_auto_trash_purge)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = AppRepository.isAutoTrashPurgeEnabled()
        toggle.setOnCheckedChangeListener { _, enabled ->
            AppRepository.setAutoTrashPurgeEnabled(enabled)
            TrashCleanupScheduler.update(context)
            if (enabled) {
                Thread { try { AppRepository.purgeExpiredTrash() } catch (_: Exception) { } }.start()
            }
            val text = if (enabled) {
                "تم تفعيل الحذف التلقائي بعد 30 يومًا"
            } else {
                "تم إيقاف الحذف التلقائي؛ ستبقى عناصر السلة حتى تحذفها يدويًا"
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
        row.setOnClickListener { toggle.performClick() }
    }

    private fun showAppLockDialog() {
        val ctx = requireContext()
        if (AppRepository.hasAppLock()) {
            MaterialAlertDialogBuilder(ctx).setTitle("قفل التطبيق")
                .setItems(arrayOf("تغيير رمز PIN", "إيقاف القفل")) { _, option ->
                    if (option == 0) promptForNewPin() else MaterialAlertDialogBuilder(ctx)
                        .setTitle("إيقاف قفل التطبيق؟").setMessage("لن يُطلب رمز PIN عند العودة إلى التطبيق.")
                        .setPositiveButton("إيقاف") { _, _ -> AppRepository.clearAppLockPin(); SessionStore.unlock(ctx); activity?.recreate() }
                        .setNegativeButton("إلغاء", null).show()
                }.show()
        } else promptForNewPin()
    }

    private fun promptForNewPin() {
        val ctx = requireContext()
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0) }
        fun pinField(hint: String) = EditText(ctx).apply { this.hint = hint; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val pin = pinField("رمز PIN من 4 أرقام أو أكثر")
        val confirm = pinField("تأكيد رمز PIN")
        panel.addView(pin); panel.addView(confirm)
        val dialog = MaterialAlertDialogBuilder(ctx).setTitle("تفعيل قفل التطبيق").setView(panel)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = pin.text.toString()
                if (value.length < 4 || value != confirm.text.toString()) {
                    confirm.error = "رمز PIN غير مطابق أو أقصر من 4 أرقام"
                    Toast.makeText(ctx, "تحقق من رمز PIN؛ يجب أن يتكون من 4 أرقام على الأقل", Toast.LENGTH_LONG).show()
                } else {
                    AppRepository.setAppLockPin(value)
                    SessionStore.unlock(ctx)
                    dialog.dismiss()
                    activity?.recreate()
                }
            }
        }
        dialog.show()
    }

    /** يسمح باختيار مظهر الهاتف التلقائي إلى جانب الخيارين اليدويين. */
    private fun showThemeDialog() {
        val modes = ThemeHelper.Mode.entries.toTypedArray()
        val labels = modes.map { it.label }.toTypedArray()
        val current = modes.indexOf(ThemeHelper.mode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("مظهر التطبيق")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                ThemeHelper.setMode(requireContext(), modes[which])
                dialog.dismiss()
                // إعادة إنشاء الشاشة تضمن إعادة تلوين جميع العناصر المخصصة فورًا.
                activity?.recreate()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun applyTheme(view: View) {
        val ctx = requireContext()
        view.setBackgroundColor(ThemeHelper.bg(ctx))
        val text = ThemeHelper.text(ctx)
        val textSec = ThemeHelper.textSecondary(ctx)
        // الصفوف بخلفية card_surface_settings (قابلة للتكيف مع الوضع) — لا نكتب فوقها، بل نلوّنها ديناميكيًا
        val rowBg = ThemeHelper.surfaceHigh(ctx)
        listOf(R.id.item_theme, R.id.item_app_lock, R.id.item_accounts, R.id.item_activity, R.id.item_trash, R.id.item_auto_trash_purge, R.id.item_help, R.id.item_storage,
            R.id.item_export, R.id.item_import, R.id.item_sync, R.id.item_logout).forEach { id ->
            view.findViewById<LinearLayout>(id)?.background?.setTint(rowBg)
        }
        listOf(R.id.tv_theme_title, R.id.tv_app_lock_title, R.id.tv_accounts_title, R.id.tv_help_title, R.id.tv_storage_title,
            R.id.tv_activity_title, R.id.tv_trash_title, R.id.tv_auto_trash_purge_title, R.id.tv_export_title, R.id.tv_import_title, R.id.tv_sync_title).forEach { id ->
            view.findViewById<TextView>(id)?.setTextColor(text)
        }
        view.findViewById<TextView>(R.id.tv_auto_trash_purge_summary)?.setTextColor(textSec)
        view.findViewById<TextView>(R.id.tv_sync_summary)?.setTextColor(textSec)
        view.findViewById<TextView>(R.id.tv_title)?.setTextColor(text)
        view.findViewById<TextView>(R.id.version_text)?.setTextColor(textSec)
    }

    /** سطر موجز يوضح وقت ووجهة وعدّاد آخر تبادل ناجح للبيانات. */
    private fun syncSummary(): String {
        val entry = AppRepository.lastSuccessfulSync() ?: return "لم تتم مزامنة بيانات بعد"
        val time = SimpleDateFormat("dd/MM HH:mm", Locale("ar")).format(Date(entry.at))
        return "آخر نجاح $time • ${entry.detail}".take(170)
    }

    private fun showActivityLogDialog() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("عرض سجل النشاط")
            .setItems(arrayOf("كل النشاط", "المستخدم الحالي", "الرسائل والصور", "المزامنة")) { _, choice ->
                showFilteredActivityLog(choice)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showFilteredActivityLog(choice: Int) {
        val ctx = requireContext()
        val currentUser = SessionStore.currentUser(ctx).orEmpty()
        val log = AppRepository.activityLog().filter { entry ->
            when (choice) {
                1 -> entry.user == currentUser
                2 -> entry.action.contains("صورة") || entry.action.contains("نص") || entry.action.contains("رسالة")
                3 -> entry.action.contains("مزامنة") || entry.action.contains("نسخة احتياطية")
                else -> true
            }
        }
        val lines = if (log.isEmpty()) listOf("لا يوجد نشاط حتى الآن")
        else log.takeLast(50).map { "${it.at} — ${it.action}" }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.activity_log)
            .setItems(lines.toTypedArray(), null)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("مركز المساعدة")
            .setMessage(
                "• لإضافة رسالة: اكتب النص أو اضغط أيقونة الصورة ثم أرسل.\n\n" +
                    "• للمزامنة: افتح «المزامنة المحلية»، اختر الجهاز ثم راجع الملخص قبل البدء.\n\n" +
                    "• البيانات والنسخ الاحتياطية محفوظة في Documents/MasahHisabat.\n\n" +
                    "• تستطيع تفعيل رمز PIN من الإعدادات لحماية الوصول بعد ترك التطبيق."
            )
            .setPositiveButton("فهمت", null)
            .show()
    }

    private fun showExportOptions() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("تصدير البيانات")
            .setItems(arrayOf(
                "نسخة احتياطية كاملة (ZIP)",
                getString(R.string.export_groups_documents),
                "تقرير بيانات (CSV / Excel)"
            )) { _, which ->
                when (which) {
                    0 -> exportData()
                    1 -> exportGroupsAndDocuments()
                    else -> exportCsvReport()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showImportOptions() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("استيراد البيانات")
            .setItems(arrayOf(
                getString(R.string.import_backup),
                getString(R.string.import_groups_documents)
            )) { _, which ->
                if (which == 0) {
                    openImportPicker(IMPORT_REQUEST)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.import_groups_documents))
                        .setMessage("سيتم دمج المجموعات والمستندات مع البيانات الحالية دون حذفها، مع إنشاء نسخة وقائية تلقائية قبل الاستيراد.")
                        .setPositiveButton("اختيار ملف") { _, _ -> openImportPicker(CONTENT_IMPORT_REQUEST) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun exportCsvReport() {
        val ctx = requireContext()
        try {
            val csv = AppRepository.createCsvReport(ctx.cacheDir)
            val user = SessionStore.currentUser(ctx) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, "صدّر $user تقرير CSV"))
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", csv)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "مشاركة التقرير أو حفظه"))
        } catch (e: Exception) { Toast.makeText(ctx, "فشل إنشاء التقرير: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showStorageDialog() {
        val ctx = requireContext()
        val usage = AppRepository.storageUsage()
        fun size(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024f * 1024f))
            bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024f)
            else -> "$bytes B"
        }
        MaterialAlertDialogBuilder(ctx).setTitle("إدارة التخزين")
            .setMessage("البيانات والصور: ${size(usage.dataBytes)}\nالصور ضمن البيانات: ${size(usage.imageBytes)}\nالنسخ الوقائية: ${size(usage.backupBytes)}\n\nلا يحذف التنظيف أي مجموعة أو صورة محفوظة.")
            .setPositiveButton("تنظيف المؤقت") { _, _ ->
                val count = AppRepository.clearTemporaryFiles()
                Toast.makeText(ctx, "تم حذف $count ملف مؤقت", Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun exportData() {
        val ctx = requireContext()
        try {
            val zipFile = AppRepository.exportData(ctx.cacheDir.parentFile ?: ctx.cacheDir)
            val dest = copyToDownloads(zipFile, "masah_backup_${System.currentTimeMillis() / 1000}.zip")
            val user = SessionStore.currentUser(ctx) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, "صدّر $user نسخة احتياطية كاملة"))
            Toast.makeText(ctx, "تم التصدير: ${dest.absolutePath}", Toast.LENGTH_LONG).show()
            vibrateShort(ctx)
        } catch (e: Exception) {
            Toast.makeText(ctx, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportGroupsAndDocuments() {
        val ctx = requireContext()
        try {
            val zipFile = AppRepository.exportContentData(ctx.cacheDir)
            val dest = copyToDownloads(zipFile, "masah_groups_${System.currentTimeMillis() / 1000}.zip")
            val user = SessionStore.currentUser(ctx) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, "صدّر $user المجموعات والمستندات"))
            Toast.makeText(ctx, "تم تصدير المجموعات والمستندات: ${dest.absolutePath}", Toast.LENGTH_LONG).show()
            vibrateShort(ctx)
        } catch (e: Exception) {
            Toast.makeText(ctx, "فشل تصدير المجموعات والمستندات: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToDownloads(source: java.io.File, fileName: String): java.io.File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        return java.io.File(downloads, fileName).also { source.copyTo(it, overwrite = true) }
    }

    private fun vibrateShort(ctx: Context) {
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun openImportPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "تعذر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    /** حوار المزامنة عبر الشبكة المحلية: اكتشاف الأجهزة ثم المزامنة بالنقر عليها */
    private fun showSyncDialog() {
        val ctx = requireContext()
        SyncManager.ensureServer(ctx)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.local_sync)
            .setItems(arrayOf("مزامنة مع جهاز آخر", "اختبار المزامنة والشبكة", "سجل أخطاء الشبكة")) { _, which ->
                when (which) {
                    0 -> showPeerDiscoveryDialog()
                    1 -> showNetworkSelfTest()
                    2 -> showNetworkLogDialog()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** اكتشاف الأجهزة المتاحة ثم بدء نقل البيانات عند اختيار أحدها. */
    private fun showPeerDiscoveryDialog() {
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
                            showSyncPreviewDialog(peer)
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
        }.start()
    }

    /** موافقة صريحة ومعاينة للبيانات المحلية قبل إرسالها إلى الجهاز المختار. */
    private fun showSyncPreviewDialog(peer: SyncManager.DiscoveredPeer) {
        val ctx = requireContext()
        val groups = AppRepository.groups().size
        val items = AppRepository.totalInvoiceCount()
        val users = AppRepository.users().size
        MaterialAlertDialogBuilder(ctx)
            .setTitle("مراجعة قبل المزامنة")
            .setMessage(
                "سيُرسل هذا الجهاز إلى ${peer.name}:\n\n" +
                    "• $groups مجموعات\n• $items عناصر ورسائل\n• $users مستخدمين\n\n" +
                    "لا تحذف المزامنة بيانات على الجهاز الآخر. سيُنشئ الجهاز المستقبِل نسخة احتياطية تلقائية قبل إضافة أي بيانات جديدة."
            )
            .setPositiveButton("بدء المزامنة") { _, _ -> startConfirmedSync(peer) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startConfirmedSync(peer: SyncManager.DiscoveredPeer) {
        val ctx = requireContext()
        val syncDialog = showSyncProgressDialog(peer.name)
        Thread {
            val result = SyncManager.syncWithHost(ctx, peer.address, peer.name) { percent, status ->
                activity?.runOnUiThread { updateSyncProgress(syncDialog, percent, status) }
            }
            activity?.runOnUiThread {
                if (syncDialog.isShowing) syncDialog.dismiss()
                val (title, message) = if (result.ok) {
                    "تمت المزامنة بنجاح" to
                        "اكتملت مزامنة ${peer.name}. استُقبلت ${result.itemsReceived} عناصر و${result.usersReceived} مستخدمين."
                } else {
                    "فشلت المزامنة" to
                        (result.errorMessage ?: "تعذر الاتصال بـ ${peer.name}. تأكد أن التطبيق مفتوح وأنكما على الشبكة نفسها.")
                }
                if (isAdded) MaterialAlertDialogBuilder(ctx)
                    .setTitle(title).setMessage(message).setPositiveButton("حسنًا", null).show()
                view?.findViewById<TextView>(R.id.tv_sync_summary)?.text = syncSummary()
            }
        }.apply { name = "confirmed-local-sync" }.start()
    }

    /** يشغل سيناريوهات المزامنة المحلية الآمنة دون إرسال أو تغيير أي بيانات فعلية. */
    private fun showNetworkSelfTest() {
        val ctx = requireContext()
        val testDialog = showSyncProgressDialog(
            peerName = "",
            title = "اختبار المزامنة والشبكة",
            initialStatus = "جارٍ تجهيز الاختبار الذاتي الآمن..."
        )
        Thread {
            val report = SyncManager.runNetworkSelfTest { percent, status ->
                activity?.runOnUiThread { updateSyncProgress(testDialog, percent, status) }
            }
            activity?.runOnUiThread {
                if (testDialog.isShowing) testDialog.dismiss()
                if (!isAdded) return@runOnUiThread
                val lines = report.results.joinToString("\n\n") { result ->
                    val mark = if (result.success) "✓" else "✕"
                    "$mark ${result.label}\n${result.detail}"
                }
                val title = if (report.isSuccessful) "اكتمل اختبار الشبكة بنجاح" else "اكتمل الاختبار مع ملاحظات"
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(title)
                    .setMessage("نجح ${report.passedCount} من ${report.results.size} اختبارات.\n\n$lines")
                    .setPositiveButton("عرض السجل") { _, _ -> showNetworkLogDialog() }
                    .setNegativeButton("حسنًا", null)
                    .show()
            }
        }.apply { name = "sync-network-self-test" }.start()
    }

    /** يعرض آخر أحداث الشبكة بما فيها الأخطاء الفعلية ونتائج الاختبار الذاتي. */
    private fun showNetworkLogDialog() {
        val ctx = requireContext()
        val formatter = SimpleDateFormat("dd/MM HH:mm:ss", Locale("ar"))
        val records = AppRepository.syncLog().takeLast(60).asReversed()
        val lines = if (records.isEmpty()) {
            arrayOf("لا يوجد سجل مزامنة أو أخطاء شبكة حتى الآن.")
        } else {
            records.map { entry ->
                val mark = if (entry.success) "✓" else "✕"
                "$mark ${formatter.format(Date(entry.at))} — ${entry.action}\n${entry.detail}"
            }.toTypedArray()
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("سجل أخطاء الشبكة")
            .setItems(lines, null)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** نافذة حالة المزامنة: نسبة مرئية تُحدّث من مراحل النقل الفعلية. */
    private fun showSyncProgressDialog(
        peerName: String,
        title: String = "جارٍ المزامنة",
        initialStatus: String = "جارٍ تجهيز المزامنة مع $peerName..."
    ): androidx.appcompat.app.AlertDialog {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 38, 48, 32)

            addView(TextView(ctx).apply {
                id = R.id.sync_progress_status
                text = initialStatus
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
            .setTitle(title)
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
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode != IMPORT_REQUEST && requestCode != CONTENT_IMPORT_REQUEST) return
        val uri: Uri = data?.data ?: return
        requireActivity().contentResolver.openInputStream(uri)?.use { stream ->
            val tmp = java.io.File(requireContext().cacheDir, "import_${System.currentTimeMillis() / 1000}.zip")
            try {
                tmp.outputStream().use { out -> stream.copyTo(out) }
                if (requestCode == CONTENT_IMPORT_REQUEST) {
                    val result = AppRepository.importContentBackup(tmp)
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "استورد $user المجموعات والمستندات"))
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("اكتمل استيراد المجموعات والمستندات")
                        .setMessage(getString(
                            R.string.content_backup_imported,
                            result.groupsImported,
                            result.itemsImported,
                            result.attachmentsImported
                        ))
                        .setPositiveButton(R.string.ok) { _, _ -> activity?.recreate() }
                        .show()
                } else {
                    AppRepository.importBackup(tmp)
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "استورد $user نسخة احتياطية كاملة"))
                    Toast.makeText(requireContext(), "تم الاستيراد بنجاح", Toast.LENGTH_LONG).show()
                    activity?.recreate()
                }
            } catch (e: Exception) {
                val message = if (requestCode == CONTENT_IMPORT_REQUEST) {
                    "فشل استيراد المجموعات والمستندات — الملف غير صالح أو غير مدعوم"
                } else {
                    "فشل الاستيراد — الملف تالف"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } finally {
                tmp.delete()
            }
        }
    }

    private fun confirmLogout() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.logout)
            .setMessage("هل أنت متأكد من تسجيل الخروج؟")
            .setPositiveButton(R.string.logout) { _, _ ->
                // نلغي الجلسة و«تذكرني» معًا، وإلا ستعيد شاشة الدخول فتح الحساب تلقائيًا.
                val user = SessionStore.currentUser(ctx) ?: "مستخدم"
                AppRepository.logActivity(ActivityEntry(user, "سجّل $user خروج"))
                AppRepository.clearRemember()
                SessionStore.clear(ctx)
                val intent = Intent(ctx, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                activity?.finishAffinity()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
