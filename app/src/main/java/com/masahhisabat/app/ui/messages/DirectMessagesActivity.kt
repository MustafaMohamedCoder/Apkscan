package com.masahhisabat.app.ui.messages

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.CallSignal
import com.masahhisabat.app.data.DirectMessage
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.data.User
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.ThemeHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** محادثات مباشرة محلية بين مستخدمي التطبيق، وتنتقل عبر المزامنة المحلية. */
class DirectMessagesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TARGET_USER = "target_user"
    }

    private lateinit var list: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var recipient: Spinner
    private lateinit var status: TextView
    private lateinit var headerPresence: TextView
    private lateinit var input: EditText
    private lateinit var attachButton: ImageButton
    private lateinit var sendButton: ImageButton
    private var selectedImage: String? = null
    private var imagePreparationInProgress = false
    private var currentUser = ""
    private var targetUser = ""
    private var callCheckInProgress = false
    private var lastCallFailure: String? = null
    private var lastLatencyMs: Long? = null
    private var pendingDiagnosticExport: String? = null

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::prepareImageForSend)
    }

    private val diagnosticExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val details = pendingDiagnosticExport
        pendingDiagnosticExport = null
        if (uri != null && !details.isNullOrBlank()) writeCallDiagnostic(uri, details)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppRepository.initAppContext(this)
        currentUser = SessionStore.currentUser(this).orEmpty()
        AppRepository.touchPresence(currentUser)
        val requestedTarget = intent.getStringExtra(EXTRA_TARGET_USER)?.trim().orEmpty()
        if (requestedTarget.isNotBlank()) targetUser = requestedTarget
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 16)
            setBackgroundColor(ThemeHelper.bg(this@DirectMessagesActivity))
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                finish()
            }
            contentDescription = getString(R.string.direct_back)
        }
        toolbar.addView(back, LinearLayout.LayoutParams(48, 48))
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBlock.addView(TextView(this).apply {
            text = targetUser.ifBlank { getString(R.string.direct_messages_title) }
            textSize = 20f
            setTextColor(ThemeHelper.text(this@DirectMessagesActivity))
            typeface = resources.getFont(R.font.tajawal_bold)
            maxLines = 1
        })
        headerPresence = TextView(this).apply {
            text = if (targetUser.isBlank()) getString(R.string.direct_select_conversation) else getString(R.string.direct_checking_presence)
            textSize = 11f
            setPadding(0, 2, 0, 0)
            textDirection = View.TEXT_DIRECTION_RTL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        titleBlock.addView(headerPresence)
        toolbar.addView(titleBlock, LinearLayout.LayoutParams(0, 60, 1f))
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(ThemeHelper.accent(this@DirectMessagesActivity))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.direct_voice_call_description, targetUser.ifBlank { getString(R.string.direct_default_user) })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openCall("voice")
            }
        }, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_videocam)
            setColorFilter(ThemeHelper.accent(this@DirectMessagesActivity))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.direct_video_call_description, targetUser.ifBlank { getString(R.string.direct_default_user) })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                openCall("video")
            }
        }, LinearLayout.LayoutParams(48, 48))
        root.addView(toolbar)

        if (targetUser.isBlank()) {
            recipient = Spinner(this)
            root.addView(recipient, LinearLayout.LayoutParams(-1, 52))
        }
        val callBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val history = MaterialButton(this).apply {
            text = getString(R.string.direct_call_history)
            contentDescription = getString(R.string.direct_view_call_history)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showCallHistory()
            }
        }
        callBar.addView(history, LinearLayout.LayoutParams(-1, 44))
        root.addView(callBar)
        status = TextView(this).apply {
            textSize = 13f
            setPadding(12, 2, 12, 10)
            textDirection = View.TEXT_DIRECTION_RTL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@DirectMessagesActivity); setPadding(4, 8, 4, 8); clipToPadding = false }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = getString(R.string.direct_message_hint)
            setTextColor(ThemeHelper.text(this@DirectMessagesActivity))
            setHintTextColor(ThemeHelper.textSecondary(this@DirectMessagesActivity))
            minHeight = 52
            setPadding(14, 0, 14, 0)
            background = AppCompatResources.getDrawable(this@DirectMessagesActivity, R.drawable.compose_bar_bg)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = updateComposerState()
                override fun afterTextChanged(value: Editable?) = Unit
            })
        }
        composer.addView(input, LinearLayout.LayoutParams(0, 56, 1f))
        attachButton = ImageButton(this).apply { setImageResource(R.drawable.ic_image_attach); background = AppCompatResources.getDrawable(this@DirectMessagesActivity, R.drawable.nav_item_bg); contentDescription = getString(R.string.direct_attach_image);             setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                chooseImage()
            } }
        composer.addView(attachButton, LinearLayout.LayoutParams(52, 52))
        sendButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = AppCompatResources.getDrawable(this@DirectMessagesActivity, R.drawable.nav_item_bg)
            contentDescription = getString(R.string.direct_send)
            setOnClickListener {
                if (imagePreparationInProgress) {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    Toast.makeText(this@DirectMessagesActivity, getString(R.string.direct_wait_for_image), Toast.LENGTH_SHORT).show()
                } else {
                    sendMessage(it)
                }
            }
        }
        composer.addView(sendButton, LinearLayout.LayoutParams(52, 52))
        root.addView(composer)
        setContentView(root)
        val users = AppRepository.users().filter { it.username != currentUser && it.enabled }
        if (targetUser.isBlank()) {
            recipient.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, users.map { it.username })
            recipient.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    targetUser = users.getOrNull(position)?.username.orEmpty()
                    refresh()
                }
            }
        }
        adapter = MessageAdapter(currentUser)
        list.adapter = adapter
        updateComposerState()
        if (users.isEmpty()) status.text = getString(R.string.direct_no_users_available)
        else if (targetUser.isBlank()) status.text = getString(R.string.direct_select_user_to_start)
        else refresh()
    }

    override fun onResume() { super.onResume(); if (currentUser.isNotBlank()) { AppRepository.touchPresence(currentUser); refresh() } }

    private fun refresh() {
        if (!::adapter.isInitialized || targetUser.isBlank()) return
        val online = AppRepository.isUserOnline(targetUser)
        val presence = getString(if (online) R.string.direct_presence_online_bullet else R.string.direct_presence_offline_bullet)
        val latency = lastLatencyMs?.let { getString(R.string.direct_connection_latency, it) }.orEmpty()
        val failure = lastCallFailure?.let { getString(R.string.direct_last_call_failure, it) }.orEmpty()
        status.text = "$presence$latency$failure"
        status.setTextColor(if (online) Color.rgb(35, 160, 85) else ThemeHelper.textSecondary(this))
        headerPresence.text = presence
        headerPresence.setTextColor(if (online) Color.rgb(35, 160, 85) else ThemeHelper.textSecondary(this))
        adapter.submit(AppRepository.directConversation(currentUser, targetUser))
        list.post { list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0)) }
        updateComposerState()
    }

    private fun updateComposerState() {
        if (!::sendButton.isInitialized || !::attachButton.isInitialized || !::input.isInitialized) return
        val state = MessageComposerState(
            hasRecipient = targetUser.isNotBlank(),
            hasTypedText = !input.text.isNullOrBlank(),
            hasPreparedImage = selectedImage != null,
            isImagePreparationInProgress = imagePreparationInProgress
        )
        sendButton.isEnabled = state.canSend
        sendButton.alpha = if (state.canSend) 1f else 0.42f
        attachButton.contentDescription = if (state.hasPreparedImage) {
            getString(R.string.direct_attachment_ready_description)
        } else {
            getString(R.string.direct_attach_image)
        }
        attachButton.setColorFilter(if (state.hasPreparedImage) ThemeHelper.accent(this) else ThemeHelper.textSecondary(this))
    }

    private data class CallHistoryFilter(
        val failedOnly: Boolean = false,
        val user: String? = null,
        val rangeDays: Int = 0
    )

    private fun showCallHistory(filter: CallHistoryFilter = CallHistoryFilter()) {
        val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val rangeStart = if (filter.rangeDays > 0) {
            System.currentTimeMillis() - filter.rangeDays * 24L * 60L * 60L * 1000L
        } else {
            Long.MIN_VALUE
        }
        val logs = AppRepository.callLogs()
            .filter { log -> !filter.failedOnly || isFailedCall(log) }
            .filter { log -> filter.user == null || log.caller.equals(filter.user, true) || log.callee.equals(filter.user, true) }
            .filter { log -> log.startedAt >= rangeStart }
            .take(30)
        val rows = logs.map {
            val kind = if (it.type == "video") "فيديو" else "صوتية"
            val minutes = it.durationSeconds / 60L
            val seconds = it.durationSeconds % 60L
            val duration = if (it.durationSeconds > 0) " | ${"%02d:%02d".format(minutes, seconds)}" else ""
            val reason = it.endReason?.takeIf { value -> value.isNotBlank() }?.let { value -> " | $value" }.orEmpty()
            val latency = it.latencyMs?.let { value -> " | ${value}ms" }.orEmpty()
            val diagnostic = it.diagnosticLog?.takeIf { value -> value.isNotBlank() }?.let { " | تشخيص محفوظ" }.orEmpty()
            "${it.caller} ← ${it.callee} | $kind | ${it.status}$duration$latency$reason$diagnostic | ${formatter.format(Date(it.startedAt))}"
        }
        var selectedIndex = 0
        val filterTitle = buildList {
            if (filter.failedOnly) add("الفاشلة")
            filter.user?.let { add(it) }
            if (filter.rangeDays > 0) add("آخر ${filter.rangeDays} يومًا")
        }.joinToString(" · ")
        AlertDialog.Builder(this)
            .setTitle(if (filterTitle.isBlank()) "سجل المكالمات" else "سجل المكالمات — $filterTitle")
            .setMessage(
                when {
                    rows.isEmpty() && filter.failedOnly -> "لا توجد اتصالات فاشلة مطابقة للفلتر الحالي."
                    rows.isEmpty() -> "لا توجد مكالمات مسجلة"
                    filter.failedOnly -> "يعرض هذا الفلتر الاتصالات التي تعذرت أو فشلت فقط. اختر سجلاً لعرض تشخيصه أو إعادة الاتصال."
                    else -> "اختر سجلاً ثم اضغط إعادة الاتصال"
                }
            )
            .apply {
                if (rows.isNotEmpty()) {
                    setSingleChoiceItems(rows.toTypedArray(), 0) { _, which -> selectedIndex = which }
                    setPositiveButton("إعادة الاتصال") { _, _ ->
                        logs.getOrNull(selectedIndex)?.let { log ->
                            targetUser = if (log.caller.equals(currentUser, ignoreCase = true)) log.callee else log.caller
                            lastCallFailure = null
                            lastLatencyMs = null
                            refresh()
                            openCall(log.type)
                        }
                    }
                    setNeutralButton("التشخيص") { _, _ ->
                        logs.getOrNull(selectedIndex)?.let(::showCallDiagnostic)
                    }
                } else {
                    setPositiveButton("إغلاق", null)
                }
                setNegativeButton("فلترة") { _, _ ->
                    showCallHistoryFilter(filter)
                }
            }
            .show()
    }

    /** فلتر محلي مركب بحسب المستخدم والفترة وحالة الفشل، دون تعديل السجلات نفسها. */
    private fun showCallHistoryFilter(current: CallHistoryFilter) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 6)
        }
        val userLabel = TextView(this).apply { text = "المستخدم"; textSize = 14f }
        val users = listOf("كل المستخدمين") + AppRepository.callLogs()
            .flatMap { listOf(it.caller, it.callee) }
            .filter { it.isNotBlank() }
            .distinct()
        val userSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@DirectMessagesActivity, android.R.layout.simple_spinner_dropdown_item, users)
            setSelection(users.indexOf(current.user ?: "كل المستخدمين").coerceAtLeast(0))
        }
        val periodLabel = TextView(this).apply { text = "الفترة"; textSize = 14f; setPadding(0, 18, 0, 0) }
        val periods = listOf("كل الفترات", "آخر 24 ساعة", "آخر 7 أيام", "آخر 30 يومًا")
        val periodDays = listOf(0, 1, 7, 30)
        val periodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@DirectMessagesActivity, android.R.layout.simple_spinner_dropdown_item, periods)
            setSelection(periodDays.indexOf(current.rangeDays).coerceAtLeast(0))
        }
        val failedCheck = CheckBox(this).apply {
            text = "الاتصالات الفاشلة فقط"
            isChecked = current.failedOnly
            setPadding(0, 16, 0, 0)
        }
        box.addView(userLabel); box.addView(userSpinner)
        box.addView(periodLabel); box.addView(periodSpinner); box.addView(failedCheck)
        AlertDialog.Builder(this)
            .setTitle("فلترة سجل المكالمات")
            .setView(box)
            .setPositiveButton("تطبيق") { _, _ ->
                val selectedUser = users.getOrNull(userSpinner.selectedItemPosition)
                    ?.takeUnless { it == "كل المستخدمين" }
                showCallHistory(
                    CallHistoryFilter(
                        failedOnly = failedCheck.isChecked,
                        user = selectedUser,
                        rangeDays = periodDays.getOrElse(periodSpinner.selectedItemPosition) { 0 }
                    )
                )
            }
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("مسح الفلترة") { _, _ -> showCallHistory() }
            .show()
    }

    /** تُعامل السجلات القديمة ذات سبب فشل صريح كسجل فاشل حتى لو حفظت بحالة نهائية عامة. */
    private fun isFailedCall(log: com.masahhisabat.app.data.CallLog): Boolean {
        if (log.status.equals("failed", ignoreCase = true)) return true
        val reason = log.endReason.orEmpty()
        return reason.contains("فشل") || reason.contains("تعذر")
    }

    /** تفاصيل محلية للمراحل التي سبقت فشل الاتصال أو انتهائه، دون إرسالها إلى أي خدمة خارجية. */
    private fun showCallDiagnostic(log: com.masahhisabat.app.data.CallLog) {
        val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val details = buildString {
            append("المكالمة: ${if (log.type == "video") "فيديو" else "صوت"}\n")
            append("الحالة: ${log.status}\n")
            append("الوقت: ${formatter.format(Date(log.startedAt))}\n")
            log.latencyMs?.let { append("زمن اختبار الوصول: ${it}ms\n") }
            log.endReason?.takeIf { it.isNotBlank() }?.let { append("سبب الانتهاء: $it\n") }
            append("\nسجل مراحل الاتصال:\n")
            append(log.diagnosticLog?.takeIf { it.isNotBlank() }
                ?: "لا تتوفر تفاصيل لهذا السجل لأنه أُنشئ قبل إضافة التشخيص الموسّع.")
        }
        AlertDialog.Builder(this)
            .setTitle("تشخيص المكالمة المحلية")
            .setMessage(details)
            .setPositiveButton("حفظ كنص") { _, _ -> exportCallDiagnostic(details) }
            .setNeutralButton("نسخ السجل") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("تشخيص مكالمة محلية", details))
                Toast.makeText(this, "تم نسخ سجل التشخيص محليًا", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    /** يفتح موفر الملفات في النظام لحفظ ملف نصي محلي؛ لا يُرفع المحتوى إلى الإنترنت أو إلى خادم. */
    private fun exportCallDiagnostic(details: String) {
        pendingDiagnosticExport = details
        diagnosticExportLauncher.launch("masah-call-diagnostic-${System.currentTimeMillis()}.txt")
    }

    private fun openCall(type: String) {
        if (targetUser.isBlank()) {
            Toast.makeText(this, "اختر مستخدمًا أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        if (callCheckInProgress) return
        callCheckInProgress = true
        lastCallFailure = null
        lastLatencyMs = null
        status.text = getString(R.string.direct_connection_checking, targetUser)
        Thread {
            val peer = SyncManager.discover(1200).firstOrNull { it.name.equals(targetUser, ignoreCase = true) }
            val address = peer?.address ?: AppRepository.syncDevices().firstOrNull { it.name.equals(targetUser, ignoreCase = true) }?.address
            val probeStartedAt = android.os.SystemClock.elapsedRealtime()
            val reachable = address != null && SyncManager.sendCallSignal(
                address,
                CallSignal(kind = "probe", callId = "probe-${System.currentTimeMillis()}", fromUser = currentUser, toUser = targetUser)
            )
            val latencyMs = (android.os.SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(0L)
            runOnUiThread {
                callCheckInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!reachable || address == null) {
                    lastCallFailure = getString(R.string.direct_connection_unavailable)
                    refresh()
                    Toast.makeText(this, getString(R.string.direct_connection_test_failed), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                lastLatencyMs = latencyMs
                status.text = getString(R.string.direct_connection_ready, latencyMs)
                startActivity(Intent(this, com.masahhisabat.app.ui.call.LocalCallActivity::class.java).apply {
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_USER, targetUser)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_ADDRESS, address)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_MEDIA_TYPE, type)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_NETWORK_LATENCY_MS, latencyMs)
                })
            }
        }.start()
    }

    private fun sendMessage(trigger: View? = null) {
        val text = input.text?.toString()?.trim().orEmpty()
        val state = MessageComposerState(
            hasRecipient = targetUser.isNotBlank(),
            hasTypedText = text.isNotBlank(),
            hasPreparedImage = selectedImage != null,
            isImagePreparationInProgress = imagePreparationInProgress
        )
        if (!state.canSend) return
        trigger?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        AppRepository.addDirectMessage(DirectMessage(fromUser = currentUser, toUser = targetUser, text = text.takeIf { it.isNotBlank() }, imagePath = selectedImage))
        AppRepository.addNotification(com.masahhisabat.app.data.NotificationEvent("رسالة من $currentUser", text.takeIf { it.isNotBlank() } ?: "تم إرسال صورة", "direct_message", currentUser))
        input.setText(""); selectedImage = null; updateComposerState(); refresh()
    }

    private fun chooseImage() {
        if (imagePreparationInProgress) {
            Toast.makeText(this, "جارٍ تجهيز صورة مختارة بالفعل", Toast.LENGTH_SHORT).show()
            return
        }
        imagePickerLauncher.launch(arrayOf("image/*"))
    }

    private fun prepareImageForSend(uri: Uri) {
        if (imagePreparationInProgress) return
        imagePreparationInProgress = true
        attachButton.isEnabled = false
        attachButton.alpha = 0.55f
        status.text = getString(R.string.direct_image_preparing)
        status.setTextColor(ThemeHelper.textSecondary(this))
        updateComposerState()
        Thread {
            val imagePath = runCatching {
                val temp = File(cacheDir, "direct_${System.currentTimeMillis()}.jpg")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("تعذر قراءة الصورة")
                    AppRepository.persistAppImage(temp.absolutePath)
                } catch (e: Exception) {
                    temp.delete()
                    throw e
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                imagePreparationInProgress = false
                attachButton.isEnabled = true
                attachButton.alpha = 1f
                if (imagePath != null) {
                    selectedImage = imagePath
                    updateComposerState()
                    refresh()
                    Toast.makeText(this, getString(R.string.direct_image_ready), Toast.LENGTH_SHORT).show()
                } else {
                    updateComposerState()
                    refresh()
                    Toast.makeText(this, getString(R.string.direct_image_preparation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "direct-message-image-copy"; start() }
    }

    private fun writeCallDiagnostic(uri: Uri, details: String) {
        Thread {
            val saved = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(details.toByteArray(Charsets.UTF_8))
                } ?: error("تعذر فتح الملف")
            }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(
                    this,
                    if (saved) "تم حفظ سجل التشخيص كنص محليًا" else "تعذر حفظ سجل التشخيص",
                    if (saved) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }.apply { name = "call-diagnostic-export"; start() }
    }

    private class MessageAdapter(private val me: String) : RecyclerView.Adapter<MessageHolder>() {
        private var data = emptyList<DirectMessage>()
        fun submit(items: List<DirectMessage>) {
            val newData = items.toList()
            val oldData = data
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size
                override fun getNewListSize() = newData.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    oldData[oldPosition].id == newData[newPosition].id

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                    oldData[oldPosition] == newData[newPosition]
            })
            data = newData
            diff.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageHolder(MaterialCardView(parent.context).apply { radius = 18f; setContentPadding(14, 10, 14, 10) })
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: MessageHolder, position: Int) = holder.bind(data[position], me)
    }
    private class MessageHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        fun bind(message: DirectMessage, me: String) {
            val context = card.context
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val who = TextView(context).apply { text = if (message.fromUser == me) "أنت" else message.fromUser; textSize = 12f; setTextColor(ThemeHelper.accent(context)) }
            box.addView(who)
            message.text?.let { box.addView(TextView(context).apply { text = it; textSize = 16f; setTextColor(ThemeHelper.text(context)); setPadding(0, 5, 0, 5) }) }
            message.imagePath?.let { path ->
                box.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, 220)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    runCatching {
                        setImageURI(androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            File(path)
                        ))
                    }.onFailure {
                        setImageResource(R.drawable.ic_image_attach)
                    }
                    contentDescription = context.getString(R.string.direct_message_image_description)
                    isClickable = true
                    setOnClickListener {
                        val imageFile = File(path)
                        if (!imageFile.isFile || imageFile.length() <= 0L) {
                            Toast.makeText(context, context.getString(R.string.direct_message_image_missing), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        context.startActivity(Intent(context, com.masahhisabat.app.ui.invoice.ImageViewerActivity::class.java).apply {
                            putExtra("image_index", 0)
                            putExtra("image_path", imageFile.absolutePath)
                            putStringArrayListExtra("image_paths", arrayListOf(imageFile.absolutePath))
                        })
                    }
                })
            }
            box.addView(TextView(context).apply { text = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(message.createdAt)); textSize = 11f; setTextColor(ThemeHelper.textSecondary(context)) })
            card.removeAllViews(); card.addView(box)
        }
    }
}
