package com.masahhisabat.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import androidx.biometric.BiometricManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.InvoiceReminderScheduler
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.data.SyncDeviceStatus
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

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.let {
            IntentCompat.getParcelableExtra(
                it,
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java
            )
        }
        AppRepository.setCallRingtoneUri(uri?.toString())
        view?.findViewById<TextView>(R.id.tv_call_ringtone_picker_title)?.text = callRingtoneTitle()
        context?.let { Toast.makeText(it, getString(R.string.settings_call_ringtone_saved), Toast.LENGTH_SHORT).show() }
    }

    private var pendingBackupPassphrase: String? = null
    private val activeOperationDialogs = mutableSetOf<androidx.appcompat.app.AlertDialog>()

    private fun trackOperationDialog(dialog: androidx.appcompat.app.AlertDialog) {
        activeOperationDialogs += dialog
        dialog.setOnDismissListener { activeOperationDialogs.remove(dialog) }
    }

    private val encryptedExportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && !passphrase.isNullOrBlank()) {
            createEncryptedBackup(uri, passphrase)
        }
    }

    private val encryptedImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(::showEncryptedRestoreDialog)
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { importZipBackup(it, contentOnly = false) }
    }

    private val contentImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { importZipBackup(it, contentOnly = true) }
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
        // سجل العمليات يتضمن تفاصيل تشغيل وإدارة؛ لذلك يبقى مرئياً للمالك فقط.
        view.findViewById<LinearLayout>(R.id.item_activity)?.visibility =
            if (SessionStore.currentUser(requireContext()) == "mustafa") View.VISIBLE else View.GONE
        // تم حذف خيار «تغيير كلمة المرور» نهائيًا من الإعدادات بأمر المستخدم
        setupItem(view, R.id.item_accounts, R.id.tv_accounts_title, getString(R.string.manage_accounts)) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canAdmin(role)) {
                startActivity(Intent(requireContext(), TeamActivity::class.java))
            } else {
                Toast.makeText(requireContext(), getString(R.string.settings_owner_admin_only), Toast.LENGTH_SHORT).show()
            }
        }
        setupItem(view, R.id.item_activity, R.id.tv_activity_title, getString(R.string.activity_log)) { showActivityLogDialog() }
        setupItem(view, R.id.item_trash, R.id.tv_trash_title, trashTitle()) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canDeleteContent(role)) {
                startActivity(Intent(requireContext(), TrashActivity::class.java))
            } else {
                Toast.makeText(requireContext(), getString(R.string.settings_trash_admin_only), Toast.LENGTH_SHORT).show()
            }
        }
        setupAutoTrashPurge(view)
        setupInvoiceReminders(view)
        setupCallFeedbackSettings(view)
        setupItem(view, R.id.item_help, R.id.tv_help_title, getString(R.string.help_center)) { showHelpDialog() }
        setupItem(view, R.id.item_export, R.id.tv_export_title, getString(R.string.export_data)) { showExportOptions() }
        setupItem(view, R.id.item_storage, R.id.tv_storage_title, getString(R.string.settings_storage_management)) { showStorageDialog() }
        setupItem(view, R.id.item_import, R.id.tv_import_title, getString(R.string.import_backup)) { showImportOptions() }
        setupItem(view, R.id.item_sync, R.id.tv_sync_title, getString(R.string.local_sync)) {
            val role = SessionStore.currentRole(requireContext())
            if (AppRepository.canSync(role)) showSyncDialog()
            else Toast.makeText(requireContext(), getString(R.string.settings_owner_admin_only), Toast.LENGTH_SHORT).show()
        }
        view.findViewById<TextView>(R.id.tv_sync_summary)?.text = syncSummary()
        // placeholder to keep edits valid
        setupItem(view, R.id.item_logout, R.id.tv_logout_title, getString(R.string.logout), errorText = true) { confirmLogout() }
    }

    override fun onDestroyView() {
        activeOperationDialogs.toList().forEach { dialog ->
            if (dialog.isShowing) dialog.dismiss()
        }
        activeOperationDialogs.clear()
        super.onDestroyView()
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
    private fun appLockTitle(): String = if (AppRepository.hasAppLock()) "قفل التطبيق: مفعّل (${lockTimeoutLabel()})" else "قفل التطبيق: غير مفعّل"
    private fun lockTimeoutLabel(): String = when (AppRepository.appLockTimeoutMs()) {
        0L -> "فوري"
        30_000L -> "30 ثانية"
        60_000L -> "دقيقة"
        else -> "5 دقائق"
    }
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
        updateToggleAccessibility(
            row, toggle, R.string.auto_trash_purge, R.string.auto_trash_purge_summary, toggle.isChecked
        )
        toggle.setOnCheckedChangeListener { _, enabled ->
            AppRepository.setAutoTrashPurgeEnabled(enabled)
            TrashCleanupScheduler.update(context)
            updateToggleAccessibility(
                row, toggle, R.string.auto_trash_purge, R.string.auto_trash_purge_summary, enabled
            )
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

    /** يتحكم في تذكيرات الفواتير المحلية، من دون أي اتصال بالإنترنت. */
    private fun setupInvoiceReminders(view: View) {
        val row = view.findViewById<LinearLayout>(R.id.item_invoice_reminders) ?: return
        val context = requireContext()
        val toggle = row.findViewById<MaterialSwitch>(R.id.switch_invoice_reminders)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = AppRepository.areInvoiceRemindersEnabled()
        updateToggleAccessibility(
            row, toggle, R.string.invoice_alerts, R.string.invoice_alerts_summary, toggle.isChecked
        )
        toggle.setOnCheckedChangeListener { _, enabled ->
            AppRepository.setInvoiceRemindersEnabled(enabled)
            InvoiceReminderScheduler.update(context)
            updateToggleAccessibility(
                row, toggle, R.string.invoice_alerts, R.string.invoice_alerts_summary, enabled
            )
            val text = if (enabled) "تم تفعيل تنبيهات الفواتير المحلية" else "تم إيقاف تنبيهات الفواتير المحلية"
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
        row.setOnClickListener { toggle.performClick() }
    }

    /** يتيح التحكم المحلي المستقل في الصوت والاهتزاز واختيار نغمة مكالمة واردة من الجهاز. */
    private fun setupCallFeedbackSettings(view: View) {
        val context = requireContext()
        val ringtoneRow = view.findViewById<LinearLayout>(R.id.item_call_ringtone) ?: return
        val ringtoneSwitch = ringtoneRow.findViewById<MaterialSwitch>(R.id.switch_call_ringtone)
        ringtoneSwitch.setOnCheckedChangeListener(null)
        ringtoneSwitch.isChecked = AppRepository.isCallRingtoneEnabled()
        updateToggleAccessibility(
            ringtoneRow, ringtoneSwitch, R.string.call_ringtones, R.string.call_ringtones_summary, ringtoneSwitch.isChecked
        )
        ringtoneSwitch.setOnCheckedChangeListener { _, enabled ->
            AppRepository.setCallRingtoneEnabled(enabled)
            updateToggleAccessibility(
                ringtoneRow, ringtoneSwitch, R.string.call_ringtones, R.string.call_ringtones_summary, enabled
            )
            Toast.makeText(context, if (enabled) "تم تفعيل نغمات المكالمات" else "تم إيقاف نغمات المكالمات", Toast.LENGTH_SHORT).show()
        }
        ringtoneRow.setOnClickListener { ringtoneSwitch.performClick() }

        val vibrationRow = view.findViewById<LinearLayout>(R.id.item_call_vibration) ?: return
        val vibrationSwitch = vibrationRow.findViewById<MaterialSwitch>(R.id.switch_call_vibration)
        vibrationSwitch.setOnCheckedChangeListener(null)
        vibrationSwitch.isChecked = AppRepository.isCallVibrationEnabled()
        updateToggleAccessibility(
            vibrationRow, vibrationSwitch, R.string.call_vibration, R.string.call_vibration_summary, vibrationSwitch.isChecked
        )
        vibrationSwitch.setOnCheckedChangeListener { _, enabled ->
            AppRepository.setCallVibrationEnabled(enabled)
            updateToggleAccessibility(
                vibrationRow, vibrationSwitch, R.string.call_vibration, R.string.call_vibration_summary, enabled
            )
            Toast.makeText(context, if (enabled) "تم تفعيل اهتزاز المكالمات" else "تم إيقاف اهتزاز المكالمات", Toast.LENGTH_SHORT).show()
        }
        vibrationRow.setOnClickListener { vibrationSwitch.performClick() }

        setupItem(view, R.id.item_call_ringtone_picker, R.id.tv_call_ringtone_picker_title, callRingtoneTitle()) {
            showCallRingtonePicker()
        }

        view.findViewById<LinearLayout>(R.id.item_call_ice_failure_alert)?.let { row ->
            val toggle = row.findViewById<MaterialSwitch>(R.id.switch_call_ice_failure_alert)
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = AppRepository.isIceFailureAlertEnabled()
            updateToggleAccessibility(
                row, toggle, R.string.repeated_connection_issue_alert, R.string.repeated_connection_issue_summary, toggle.isChecked
            )
            toggle.setOnCheckedChangeListener { _, enabled ->
                AppRepository.setIceFailureAlertEnabled(enabled)
                updateToggleAccessibility(
                    row, toggle, R.string.repeated_connection_issue_alert, R.string.repeated_connection_issue_summary, enabled
                )
                val message = if (enabled) {
                    "تم تفعيل تنبيه تكرار تعذر الاتصال"
                } else {
                    "تم إيقاف تنبيه تكرار تعذر الاتصال"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            row.setOnClickListener { toggle.performClick() }
        }
    }

    private fun updateToggleAccessibility(
        row: View,
        toggle: MaterialSwitch,
        titleRes: Int,
        summaryRes: Int,
        isEnabled: Boolean,
    ) {
        val description = SettingsToggleAccessibilityPolicy.describe(
            title = getString(titleRes),
            summary = getString(summaryRes),
            isEnabled = isEnabled,
        )
        row.contentDescription = description
        toggle.contentDescription = description
    }

    private fun callRingtoneTitle(): String {
        val context = requireContext()
        val uri = AppRepository.callRingtoneUri()?.let(Uri::parse)
        val name = uri?.let {
            runCatching { RingtoneManager.getRingtone(context, it)?.getTitle(context) }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: "نغمة النظام الافتراضية"
        return "نغمة المكالمة الواردة: $name"
    }

    private fun showCallRingtonePicker() {
        val existing = AppRepository.callRingtoneUri()?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtonePickerLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة المكالمة الواردة")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        })
    }

    private fun showAppLockDialog() {
        val ctx = requireContext()
        if (AppRepository.hasAppLock()) {
            val biometricLabel = if (AppRepository.isBiometricUnlockEnabled()) "مفعّل" else "غير مفعّل"
            val privacyLabel = if (AppRepository.isScreenPrivacyEnabled()) "مفعّلة" else "غير مفعّلة"
            MaterialAlertDialogBuilder(ctx).setTitle("قفل التطبيق")
                .setItems(arrayOf(
                    "تغيير رمز PIN",
                    "مهلة القفل: ${lockTimeoutLabel()}",
                    "حماية المعاينة ولقطات الشاشة: $privacyLabel",
                    "فتح بالبصمة: $biometricLabel",
                    "إيقاف القفل"
                )) { _, option ->
                    when (option) {
                        0 -> promptForNewPin()
                        1 -> showLockTimeoutDialog()
                        2 -> {
                            val enabled = !AppRepository.isScreenPrivacyEnabled()
                            AppRepository.setScreenPrivacyEnabled(enabled)
                            if (enabled) requireActivity().window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                            else requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            Toast.makeText(ctx, if (enabled) "تمت حماية المعاينة ولقطات الشاشة" else "تم إيقاف حماية المعاينة", Toast.LENGTH_SHORT).show()
                        }
                        3 -> toggleBiometricUnlock()
                        else -> MaterialAlertDialogBuilder(ctx)
                            .setTitle("إيقاف قفل التطبيق؟").setMessage("لن يُطلب رمز PIN عند العودة إلى التطبيق.")
                            .setPositiveButton("إيقاف") { _, _ -> AppRepository.clearAppLockPin(); SessionStore.unlock(ctx); activity?.recreate() }
                            .setNegativeButton("إلغاء", null).show()
                    }
                }.show()
        } else promptForNewPin()
    }

    private fun showLockTimeoutDialog() {
        val values = longArrayOf(0L, 30_000L, 60_000L, 300_000L)
        val labels = arrayOf("فور مغادرة التطبيق", "بعد 30 ثانية", "بعد دقيقة", "بعد 5 دقائق")
        val current = values.indexOf(AppRepository.appLockTimeoutMs()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext()).setTitle("مهلة قفل التطبيق")
            .setSingleChoiceItems(labels, current) { dialog, selected ->
                AppRepository.setAppLockTimeoutMs(values[selected])
                Toast.makeText(requireContext(), "تم ضبط مهلة القفل: ${labels[selected]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun toggleBiometricUnlock() {
        val ctx = requireContext()
        val manager = BiometricManager.from(ctx)
        val available = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        if (!available) {
            Toast.makeText(ctx, "البصمة غير مهيأة أو غير متاحة على هذا الجهاز؛ استخدم رمز PIN", Toast.LENGTH_LONG).show()
            return
        }
        val enabled = !AppRepository.isBiometricUnlockEnabled()
        AppRepository.setBiometricUnlockEnabled(enabled)
        Toast.makeText(ctx, if (enabled) "تم تفعيل فتح القفل بالبصمة" else "تم إيقاف فتح القفل بالبصمة", Toast.LENGTH_SHORT).show()
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
        view.setBackgroundResource(ThemeHelper.backgroundRes())
        val text = ThemeHelper.text(ctx)
        val textSec = ThemeHelper.textSecondary(ctx)
        // الصفوف تستخدم موردًا زجاجيًا له بديل ليلي تلقائي؛ لا نلوّن المورد كي لا نفقد الحد والانعكاس.
        listOf(R.id.item_theme, R.id.item_app_lock, R.id.item_accounts, R.id.item_activity, R.id.item_trash, R.id.item_auto_trash_purge, R.id.item_help, R.id.item_storage,
            R.id.item_export, R.id.item_import, R.id.item_sync, R.id.item_logout).forEach { id ->
            view.findViewById<LinearLayout>(id)?.apply {
                setBackgroundResource(R.drawable.card_surface_settings)
                backgroundTintList = null
            }
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
        val failedDevice = AppRepository.syncDevices()
            .filter { it.lastSyncAt != null }
            .maxByOrNull { it.lastSyncAt ?: 0L }
            ?.takeIf { it.lastSyncSuccess == false }
        if (failedDevice != null) {
            val error = failedDevice.lastError?.take(90)?.let { " — $it" }.orEmpty()
            return "آخر محاولة مع ${failedDevice.name} فشلت$error"
        }
        val entry = AppRepository.lastSuccessfulSync()
            ?: return if (AppRepository.syncDevices().isEmpty()) "لم تتم مزامنة بيانات بعد" else "تم اكتشاف أجهزة؛ اضغط لمراجعة حالتها"
        val time = SimpleDateFormat("dd/MM HH:mm", Locale("ar")).format(Date(entry.at))
        return "آخر نجاح $time • ${entry.detail}".take(170)
    }

    /** يعرض آخر اتصال لكل جهاز والتعارضات التي حسمها التطبيق آلياً دون تغيير أي بيانات. */
    private fun showSyncTransparencyDialog() {
        val ctx = requireContext()
        val devices = AppRepository.syncDevices()
        val conflicts = AppRepository.syncConflicts()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("حالة الأجهزة والتعارضات")
                .setMessage("لا توجد أجهزة مسجلة بعد. ابدأ البحث أو المزامنة مع جهاز آخر على الشبكة المحلية.")
                .setPositiveButton("بحث عن أجهزة") { _, _ -> showPeerDiscoveryDialog() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }
        val formatter = SimpleDateFormat("dd/MM HH:mm", Locale("ar"))
        val rows = devices.map { device ->
            val state = when (device.lastSyncSuccess) {
                true -> "✓ آخر مزامنة ناجحة"
                false -> "✕ فشلت آخر مزامنة"
                null -> "• تم اكتشاف الجهاز"
            }
            "${device.name} — ${device.address}\n$state • آخر ظهور ${formatter.format(Date(device.lastSeenAt))}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("حالة الأجهزة (${devices.size})")
            .setItems(rows) { _, which -> showDeviceStatusDialog(devices[which]) }
            .setNeutralButton("التعارضات (${conflicts.size})") { _, _ -> showSyncConflictsDialog() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** تفصيل جهاز محدد؛ لا يظهر إجراء الإعادة إلا وفق نتيجة آخر محاولة مسجلة. */
    private fun showDeviceStatusDialog(device: SyncDeviceStatus) {
        val ctx = requireContext()
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
        val lastSync = device.lastSyncAt?.let { formatter.format(Date(it)) } ?: "لم تتم بعد"
        val result = when (device.lastSyncSuccess) {
            true -> "ناجحة"
            false -> "فاشلة"
            null -> "لم تتم بعد"
        }
        val message = buildString {
            append("العنوان: ${device.address}\n")
            append("آخر ظهور: ${formatter.format(Date(device.lastSeenAt))}\n")
            append("آخر مزامنة: $lastSync\n")
            append("النتيجة: $result")
            device.lastError?.takeIf { it.isNotBlank() }?.let { append("\n\nسبب الفشل: $it") }
        }
        val action = if (device.lastSyncSuccess == false) "إعادة المحاولة" else "مزامنة الآن"
        MaterialAlertDialogBuilder(ctx)
            .setTitle(device.name)
            .setMessage(message)
            .setPositiveButton(action) { _, _ ->
                showSyncPreviewDialog(SyncManager.DiscoveredPeer(device.address, device.name))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** سجل موجز للتعارضات المُسوّاة، مع بيان مصدر القرار وسياسة الدمج المطبقة. */
    private fun showSyncConflictsDialog() {
        val ctx = requireContext()
        val formatter = SimpleDateFormat("dd/MM HH:mm", Locale("ar"))
        val conflicts = AppRepository.syncConflicts()
        val rows = if (conflicts.isEmpty()) {
            arrayOf("لا توجد تعارضات مُسجلة. عند اختلاف بيانات جهازين ستظهر هنا نتيجة الحل التلقائي.")
        } else {
            conflicts.take(100).map { conflict ->
                "${formatter.format(Date(conflict.at))} — ${conflict.entityType}: ${conflict.entityId}\n${conflict.resolution}\nالمصدر: ${conflict.deviceName}"
            }.toTypedArray()
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("تعارضات تمت تسويتها (${conflicts.size})")
            .setItems(rows, null)
            .setNegativeButton(R.string.close, null)
            .show()
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
                "نسخة احتياطية مشفرة (مستحسن)",
                "نسخة احتياطية كاملة (ZIP)",
                getString(R.string.export_groups_documents),
                "تقرير بيانات (CSV / Excel)"
            )) { _, which ->
                when (which) {
                    0 -> showEncryptedBackupDialog()
                    1 -> exportData()
                    2 -> exportGroupsAndDocuments()
                    else -> exportCsvReport()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showImportOptions() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("استيراد البيانات")
            .setItems(arrayOf(
                getString(R.string.import_backup),
                getString(R.string.import_groups_documents),
                "استعادة نسخة مشفرة"
            )) { _, which ->
                if (which == 0) {
                    openImportPicker(contentOnly = false)
                } else if (which == 1) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.import_groups_documents))
                        .setMessage("سيتم دمج المجموعات والمستندات مع البيانات الحالية دون حذفها، مع إنشاء نسخة وقائية تلقائية قبل الاستيراد.")
                        .setPositiveButton("اختيار ملف") { _, _ -> openImportPicker(contentOnly = true) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    openEncryptedImportPicker()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /** كلمة المرور تستخدم لتشفير هذه النسخة فقط، ولا تحفظ في الإعدادات أو في الملف نفسه. */
    private fun showEncryptedBackupDialog() {
        val ctx = requireContext()
        val field = EditText(ctx).apply {
            hint = "كلمة مرور النسخة (6 أحرف على الأقل)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            contentDescription = "كلمة مرور النسخة الاحتياطية المشفرة"
        }
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("إنشاء نسخة مشفرة")
            .setMessage("ستحتاج إلى كلمة المرور نفسها عند الاستعادة. لا يمكن استرجاعها إن فُقدت.")
            .setView(holder)
            .setPositiveButton("اختيار مكان الحفظ", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val passphrase = field.text?.toString().orEmpty()
                        if (passphrase.length < 6) {
                            field.error = "أدخل 6 أحرف على الأقل"
                        } else {
                            pendingBackupPassphrase = passphrase
                            openEncryptedExportPicker()
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun openEncryptedExportPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "masah_backup_${System.currentTimeMillis() / 1000}.masahbak")
        }
        try {
            encryptedExportLauncher.launch(intent)
        } catch (_: Exception) {
            pendingBackupPassphrase = null
            Toast.makeText(requireContext(), "تعذر فتح اختيار مكان الحفظ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEncryptedImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            encryptedImportLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "تعذر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEncryptedRestoreDialog(uri: Uri) {
        val ctx = requireContext()
        val field = EditText(ctx).apply {
            hint = "كلمة مرور النسخة"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            contentDescription = "كلمة مرور استعادة النسخة المشفرة"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("استعادة نسخة مشفرة")
            .setMessage("سيجري إنشاء نسخة وقائية قبل استبدال البيانات الحالية.")
            .setView(field)
            .setPositiveButton("استعادة", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val passphrase = field.text?.toString().orEmpty()
                        if (passphrase.length < 6) field.error = "أدخل كلمة مرور صحيحة"
                        else {
                            restoreEncryptedBackup(uri, passphrase)
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
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
        val totalBytes = usage.dataBytes + usage.backupBytes
        fun size(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024f * 1024f))
            bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024f)
            else -> "$bytes B"
        }
        MaterialAlertDialogBuilder(ctx).setTitle("إدارة التخزين")
            .setMessage("إجمالي مساحة التطبيق: ${size(totalBytes)}\n\nالبيانات والصور: ${size(usage.dataBytes)}\nالصور ضمن البيانات: ${size(usage.imageBytes)}\nالنسخ الوقائية: ${size(usage.backupBytes)}\n\nينظف الخيار التالي الملفات المؤقتة فقط؛ لا يحذف أي مجموعة أو صورة أو نسخة احتياطية محفوظة.")
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
        ctx.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun openImportPicker(contentOnly: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            if (contentOnly) contentImportLauncher.launch(intent) else backupImportLauncher.launch(intent)
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
            .setItems(arrayOf("مزامنة مع جهاز آخر", "حالة الأجهزة والتعارضات", "اختبار المزامنة والشبكة", "سجل أخطاء الشبكة")) { _, which ->
                when (which) {
                    0 -> showPeerDiscoveryDialog()
                    1 -> showSyncTransparencyDialog()
                    2 -> showNetworkSelfTest()
                    3 -> showNetworkLogDialog()
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
            .create()
            .also {
                trackOperationDialog(it)
                it.show()
            }

        // البحث في الخلفية
        Thread {
            val peers = SyncManager.discover(4000)
            activity?.runOnUiThread {
                if (!isAdded) {
                    if (dialog.isShowing) dialog.dismiss()
                    return@runOnUiThread
                }
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
            val result = SyncManager.syncWithHost(peer.address, peer.name) { percent, status ->
                activity?.runOnUiThread {
                    if (!isAdded || !syncDialog.isShowing) return@runOnUiThread
                    updateSyncProgress(syncDialog, percent, status)
                }
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
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
                activity?.runOnUiThread {
                    if (!isAdded || !testDialog.isShowing) return@runOnUiThread
                    updateSyncProgress(testDialog, percent, status)
                }
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
                textDirection = View.TEXT_DIRECTION_RTL
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                contentDescription = "حالة المزامنة: $initialStatus"
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
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                contentDescription = "نسبة تقدم المزامنة: 5 بالمئة"
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
            .also {
                trackOperationDialog(it)
                it.show()
            }
    }

    private fun updateSyncProgress(dialog: androidx.appcompat.app.AlertDialog, percent: Int, status: String) {
        if (!isAdded || !dialog.isShowing) return
        val progress = percent.coerceIn(0, 100)
        dialog.findViewById<android.widget.ProgressBar>(R.id.sync_progress_bar)?.progress = progress
        dialog.findViewById<TextView>(R.id.sync_progress_percent)?.apply {
            text = "$progress%"
            contentDescription = "نسبة تقدم المزامنة: $progress بالمئة"
        }
        dialog.findViewById<TextView>(R.id.sync_progress_status)?.apply {
            text = status
            contentDescription = "حالة المزامنة: $status"
        }
    }

    /** حالة مرئية ثابتة للعمليات الطويلة التي لا يتوافر لها تقدم دقيق، مثل استيراد النسخ. */
    private fun showBlockingOperationDialog(title: String, status: String): androidx.appcompat.app.AlertDialog {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            addView(android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
                contentDescription = "جارٍ التنفيذ"
                layoutParams = LinearLayout.LayoutParams(52, 52)
            })
            addView(TextView(ctx).apply {
                text = status
                textSize = 15f
                textDirection = View.TEXT_DIRECTION_RTL
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setTextColor(ThemeHelper.text(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = 24 }
            })
        }
        return MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
            .create()
            .also {
                trackOperationDialog(it)
                it.show()
            }
    }

    private fun importZipBackup(uri: Uri, contentOnly: Boolean) {
        val ctx = requireContext().applicationContext
        val progressDialog = showBlockingOperationDialog(
            title = if (contentOnly) "جارٍ استيراد المجموعات والمستندات" else "جارٍ استيراد النسخة الاحتياطية",
            status = "يتم فحص الملف وتجهيز البيانات محليًا…"
        )
        Thread {
            val tmp = java.io.File(ctx.cacheDir, "import_${System.currentTimeMillis() / 1000}.zip")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    tmp.outputStream().use { out -> stream.copyTo(out) }
                } ?: throw IllegalStateException("تعذر قراءة ملف النسخة")
                if (contentOnly) {
                    val result = AppRepository.importContentBackup(tmp)
                    val user = SessionStore.currentUser(ctx) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "استورد $user المجموعات والمستندات"))
                    activity?.runOnUiThread {
                        if (!isAdded) {
                            if (progressDialog.isShowing) progressDialog.dismiss()
                            return@runOnUiThread
                        }
                        if (progressDialog.isShowing) progressDialog.dismiss()
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
                    }
                } else {
                    AppRepository.importBackup(tmp)
                    val user = SessionStore.currentUser(ctx) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "استورد $user نسخة احتياطية كاملة"))
                    activity?.runOnUiThread {
                        if (!isAdded) {
                            if (progressDialog.isShowing) progressDialog.dismiss()
                            return@runOnUiThread
                        }
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("تم استيراد النسخة بنجاح")
                            .setMessage("اكتملت عملية الاستيراد محليًا. حدّث الواجهة لعرض البيانات المستوردة.")
                            .setPositiveButton("تحديث الآن") { _, _ -> activity?.recreate() }
                            .setNegativeButton("لاحقًا", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                val message = if (contentOnly) {
                    "فشل استيراد المجموعات والمستندات — الملف غير صالح أو غير مدعوم"
                } else {
                    "فشل الاستيراد — الملف تالف"
                }
                val user = SessionStore.currentUser(ctx) ?: "?"
                val action = if (contentOnly) "فشل استيراد المجموعات والمستندات" else "فشل استيراد النسخة الاحتياطية"
                runCatching { AppRepository.logActivity(ActivityEntry(user, "$action (${e.javaClass.simpleName})")) }
                activity?.runOnUiThread {
                    if (!isAdded) {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        return@runOnUiThread
                    }
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("تعذر الاستيراد")
                        .setMessage("$message\n\nتحقق من اختيار ملف النسخة الصحيح ثم أعد المحاولة.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            } finally {
                tmp.delete()
            }
        }.apply { name = "backup-import" }.start()
    }

    private fun createEncryptedBackup(destination: Uri, passphrase: String) {
        val ctx = requireContext().applicationContext
        val progressDialog = showBlockingOperationDialog(
            title = "جارٍ إنشاء النسخة المشفرة",
            status = "يتم تشفير البيانات وحفظ النسخة محليًا…"
        )
        Thread {
            var encryptedFile: java.io.File? = null
            try {
                encryptedFile = AppRepository.exportEncryptedData(ctx.cacheDir, passphrase)
                ctx.contentResolver.openOutputStream(destination)?.use { target ->
                    encryptedFile.inputStream().buffered().use { source -> source.copyTo(target) }
                } ?: throw IllegalStateException("تعذر الكتابة في موقع الحفظ")
                val user = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, "صدّر $user نسخة احتياطية مشفرة"))
                activity?.runOnUiThread {
                    if (!isAdded) {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        return@runOnUiThread
                    }
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("تم حفظ النسخة المشفرة")
                        .setMessage("اكتمل تشفير النسخة وحفظها في الموقع الذي اخترته.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            } catch (e: Exception) {
                val user = SessionStore.currentUser(ctx) ?: "?"
                runCatching {
                    AppRepository.logActivity(ActivityEntry(user, "فشل تصدير النسخة المشفرة (${e.javaClass.simpleName})"))
                }
                activity?.runOnUiThread {
                    if (!isAdded) {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        return@runOnUiThread
                    }
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("تعذر إنشاء النسخة المشفرة")
                        .setMessage("تحقق من توفر مساحة كافية ومن كلمة المرور، ثم أعد المحاولة.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            } finally {
                encryptedFile?.delete()
            }
        }.apply { name = "encrypted-backup-export" }.start()
    }

    private fun restoreEncryptedBackup(source: Uri, passphrase: String) {
        val ctx = requireContext().applicationContext
        val progressDialog = showBlockingOperationDialog(
            title = "جارٍ استعادة النسخة المشفرة",
            status = "يتم التحقق من الملف وفك تشفير البيانات محليًا…"
        )
        Thread {
            val encryptedFile = java.io.File(ctx.cacheDir, "encrypted_restore_${System.currentTimeMillis()}.masahbak")
            try {
                ctx.contentResolver.openInputStream(source)?.use { input ->
                    encryptedFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("تعذر قراءة الملف")
                AppRepository.importEncryptedBackup(encryptedFile, passphrase)
                val user = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, "استورد $user نسخة احتياطية مشفرة"))
                activity?.runOnUiThread {
                    if (!isAdded) {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        return@runOnUiThread
                    }
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("تمت استعادة النسخة بنجاح")
                        .setMessage("اكتملت الاستعادة محليًا. حدّث الواجهة لعرض البيانات المستعادة.")
                        .setPositiveButton("تحديث الآن") { _, _ -> activity?.recreate() }
                        .setNegativeButton("لاحقًا", null)
                        .show()
                }
            } catch (e: Exception) {
                val user = SessionStore.currentUser(ctx) ?: "?"
                runCatching {
                    AppRepository.logActivity(ActivityEntry(user, "فشل استيراد النسخة المشفرة (${e.javaClass.simpleName})"))
                }
                activity?.runOnUiThread {
                    if (!isAdded) {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        return@runOnUiThread
                    }
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("تعذرت استعادة النسخة")
                        .setMessage("تحقق من الملف وكلمة المرور ثم أعد المحاولة.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            } finally {
                encryptedFile.delete()
            }
        }.apply { name = "encrypted-backup-import" }.start()
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
                SyncManager.stopServer()
                ctx.stopService(Intent(ctx, com.masahhisabat.app.data.LocalCallService::class.java))
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
