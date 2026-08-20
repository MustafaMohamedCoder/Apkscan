package com.masahhisabat.app.ui.messages

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var input: EditText
    private var selectedImage: String? = null
    private var currentUser = ""
    private var targetUser = ""
    private var callCheckInProgress = false
    private var lastCallFailure: String? = null
    private var lastLatencyMs: Long? = null
    private val pickerCode = 431
    private val diagnosticExportCode = 432
    private var pendingDiagnosticExport: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppRepository.initAppContext(this)
        currentUser = SessionStore.currentUser(this).orEmpty()
        AppRepository.touchPresence(currentUser)
        val requestedTarget = intent.getStringExtra(EXTRA_TARGET_USER)?.trim().orEmpty()
        if (requestedTarget.isNotBlank()) targetUser = requestedTarget
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 16); setBackgroundColor(getColor(com.masahhisabat.app.R.color.day_background)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply { setImageResource(R.drawable.ic_arrow_back); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() }; contentDescription = "رجوع" }
        toolbar.addView(back, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(TextView(this).apply {
            text = targetUser.ifBlank { "رسائل المستخدمين" }
            textSize = 21f
            setTextColor(ThemeHelper.text(this@DirectMessagesActivity))
            typeface = resources.getFont(R.font.tajawal_bold)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, 56, 1f))
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_call)
            setColorFilter(ThemeHelper.accent(this@DirectMessagesActivity))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "بدء مكالمة صوتية مع ${targetUser.ifBlank { "المستخدم" }}"
            setOnClickListener { openCall("voice") }
        }, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_videocam)
            setColorFilter(ThemeHelper.accent(this@DirectMessagesActivity))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "بدء مكالمة فيديو مع ${targetUser.ifBlank { "المستخدم" }}"
            setOnClickListener { openCall("video") }
        }, LinearLayout.LayoutParams(48, 48))
        root.addView(toolbar)

        if (targetUser.isBlank()) {
            recipient = Spinner(this)
            root.addView(recipient, LinearLayout.LayoutParams(-1, 52))
        }
        val callBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val history = MaterialButton(this).apply {
            text = "سجل المكالمات"
            setOnClickListener { showCallHistory() }
        }
        callBar.addView(history, LinearLayout.LayoutParams(-1, 44))
        root.addView(callBar)
        status = TextView(this).apply { textSize = 13f; setPadding(12, 2, 12, 10) }
        root.addView(status)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@DirectMessagesActivity); setPadding(4, 8, 4, 8); clipToPadding = false }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply { hint = "اكتب رسالة…"; setTextColor(ThemeHelper.text(this@DirectMessagesActivity)); setHintTextColor(ThemeHelper.textSecondary(this@DirectMessagesActivity)); minHeight = 52; setPadding(14, 0, 14, 0); background = getDrawable(R.drawable.compose_bar_bg) }
        composer.addView(input, LinearLayout.LayoutParams(0, 56, 1f))
        val attach = ImageButton(this).apply { setImageResource(R.drawable.ic_image_attach); background = getDrawable(R.drawable.nav_item_bg); contentDescription = "إضافة صورة"; setOnClickListener { chooseImage() } }
        composer.addView(attach, LinearLayout.LayoutParams(52, 52))
        val send = ImageButton(this).apply { setImageResource(R.drawable.ic_send); background = getDrawable(R.drawable.nav_item_bg); contentDescription = "إرسال"; setOnClickListener { sendMessage() } }
        composer.addView(send, LinearLayout.LayoutParams(52, 52))
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
        if (users.isEmpty()) status.text = "لا يوجد مستخدمون آخرون متاحون للمراسلة"
        else if (targetUser.isBlank()) status.text = "اختر مستخدمًا لبدء المحادثة"
        else refresh()
    }

    override fun onResume() { super.onResume(); if (currentUser.isNotBlank()) { AppRepository.touchPresence(currentUser); refresh() } }

    private fun refresh() {
        if (!::adapter.isInitialized || targetUser.isBlank()) return
        val online = AppRepository.isUserOnline(targetUser)
        val presence = if (online) "●  متصل الآن" else "○  غير متصل الآن"
        val latency = lastLatencyMs?.let { " | زمن الاستجابة: ${it}ms" }.orEmpty()
        val failure = lastCallFailure?.let { "\nآخر فشل اتصال: $it" }.orEmpty()
        status.text = "$presence$latency$failure"
        status.setTextColor(if (online) Color.rgb(35, 160, 85) else ThemeHelper.textSecondary(this))
        adapter.submit(AppRepository.directConversation(currentUser, targetUser))
        list.post { list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0)) }
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
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "masah-call-diagnostic-${System.currentTimeMillis()}.txt")
        }, diagnosticExportCode)
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
        status.text = "جارٍ التحقق من اتصال $targetUser داخل الشبكة المحلية…"
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
                if (!reachable || address == null) {
                    lastCallFailure = "تعذر الوصول محليًا. تأكد من اتصال الجهازين بالشبكة نفسها ومن فتح التطبيق."
                    refresh()
                    Toast.makeText(this, "فشل اختبار الاتصال المحلي", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                lastLatencyMs = latencyMs
                status.text = "تم اختبار الاتصال المحلي بنجاح | زمن الاستجابة: ${latencyMs}ms — جارٍ فتح المكالمة"
                startActivity(Intent(this, com.masahhisabat.app.ui.call.LocalCallActivity::class.java).apply {
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_USER, targetUser)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_ADDRESS, address)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_MEDIA_TYPE, type)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_NETWORK_LATENCY_MS, latencyMs)
                })
            }
        }.start()
    }

    private fun sendMessage() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank() && selectedImage == null || targetUser.isBlank()) return
        AppRepository.addDirectMessage(DirectMessage(fromUser = currentUser, toUser = targetUser, text = text.takeIf { it.isNotBlank() }, imagePath = selectedImage))
        AppRepository.addNotification(com.masahhisabat.app.data.NotificationEvent("رسالة من $currentUser", text.takeIf { it.isNotBlank() } ?: "تم إرسال صورة", "direct_message", currentUser))
        input.setText(""); selectedImage = null; refresh()
    }

    private fun chooseImage() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, pickerCode) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            pickerCode -> runCatching {
                val temp = File(cacheDir, "direct_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                selectedImage = AppRepository.persistAppImage(temp.absolutePath)
                Toast.makeText(this, "الصورة جاهزة للإرسال", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "تعذر تجهيز الصورة", Toast.LENGTH_SHORT).show() }
            diagnosticExportCode -> {
                val details = pendingDiagnosticExport ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(details.toByteArray(Charsets.UTF_8))
                    } ?: error("تعذر فتح الملف")
                    Toast.makeText(this, "تم حفظ سجل التشخيص كنص محليًا", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(this, "تعذر حفظ سجل التشخيص", Toast.LENGTH_SHORT).show() }
                pendingDiagnosticExport = null
            }
        }
    }

    private class MessageAdapter(private val me: String) : RecyclerView.Adapter<MessageHolder>() {
        private var data = emptyList<DirectMessage>()
        fun submit(items: List<DirectMessage>) { data = items; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MessageHolder(MaterialCardView(parent.context).apply { radius = 18f; setContentPadding(14, 10, 14, 10) })
        override fun getItemCount() = data.size
        override fun onBindViewHolder(holder: MessageHolder, position: Int) = holder.bind(data[position], me)
    }
    private class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(message: DirectMessage, me: String) {
            val card = itemView as MaterialCardView
            val box = LinearLayout(itemView.context).apply { orientation = LinearLayout.VERTICAL }
            val who = TextView(itemView.context).apply { text = if (message.fromUser == me) "أنت" else message.fromUser; textSize = 12f; setTextColor(ThemeHelper.accent(itemView.context)) }
            box.addView(who)
            message.text?.let { box.addView(TextView(itemView.context).apply { text = it; textSize = 16f; setTextColor(ThemeHelper.text(itemView.context)); setPadding(0, 5, 0, 5) }) }
            message.imagePath?.let { path -> box.addView(ImageView(itemView.context).apply { layoutParams = LinearLayout.LayoutParams(-1, 180); scaleType = ImageView.ScaleType.CENTER_CROP; setImageURI(Uri.fromFile(File(path))); contentDescription = "صورة الرسالة" }) }
            box.addView(TextView(itemView.context).apply { text = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(message.createdAt)); textSize = 11f; setTextColor(ThemeHelper.textSecondary(itemView.context)) })
            card.removeAllViews(); card.addView(box)
        }
    }
}
