package com.masahhisabat.app.ui.messages

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var list: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var recipient: Spinner
    private lateinit var status: TextView
    private lateinit var input: EditText
    private var selectedImage: String? = null
    private var currentUser = ""
    private var targetUser = ""
    private var callCheckInProgress = false
    private val pickerCode = 431

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AppRepository.initAppContext(this)
        currentUser = SessionStore.currentUser(this).orEmpty()
        AppRepository.touchPresence(currentUser)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 12, 18, 16); setBackgroundColor(getColor(com.masahhisabat.app.R.color.day_background)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply { setImageResource(R.drawable.ic_arrow_back); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() }; contentDescription = "رجوع" }
        toolbar.addView(back, LinearLayout.LayoutParams(48, 48))
        toolbar.addView(TextView(this).apply { text = "رسائل المستخدمين"; textSize = 21f; setTextColor(ThemeHelper.text(this@DirectMessagesActivity)); typeface = resources.getFont(R.font.tajawal_bold) }, LinearLayout.LayoutParams(0, 56, 1f))
        root.addView(toolbar)
        recipient = Spinner(this)
        root.addView(recipient, LinearLayout.LayoutParams(-1, 52))
        val callBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val voiceCall = MaterialButton(this).apply {
            text = "مكالمة صوتية"
            setOnClickListener { openCall("voice") }
        }
        val videoCall = MaterialButton(this).apply {
            text = "مكالمة فيديو"
            setOnClickListener { openCall("video") }
        }
        callBar.addView(voiceCall, LinearLayout.LayoutParams(0, 48, 1f))
        callBar.addView(videoCall, LinearLayout.LayoutParams(0, 48, 1f))
        val history = MaterialButton(this).apply {
            text = "سجل المكالمات"
            setOnClickListener { showCallHistory() }
        }
        callBar.addView(history, LinearLayout.LayoutParams(0, 48, 1f))
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
        recipient.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, users.map { it.username })
        recipient.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { targetUser = users.getOrNull(position)?.username.orEmpty(); refresh() }
        }
        adapter = MessageAdapter(currentUser)
        list.adapter = adapter
        if (users.isEmpty()) status.text = "لا يوجد مستخدمون آخرون متاحون للمراسلة"
    }

    override fun onResume() { super.onResume(); if (currentUser.isNotBlank()) { AppRepository.touchPresence(currentUser); refresh() } }

    private fun refresh() {
        if (!::adapter.isInitialized || targetUser.isBlank()) return
        val online = AppRepository.isUserOnline(targetUser)
        status.text = if (online) "●  متصل الآن" else "○  غير متصل الآن"
        status.setTextColor(if (online) Color.rgb(35, 160, 85) else ThemeHelper.textSecondary(this))
        adapter.submit(AppRepository.directConversation(currentUser, targetUser))
        list.post { list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0)) }
    }

    private fun showCallHistory() {
        val formatter = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val rows = AppRepository.callLogs().take(30).map {
            val kind = if (it.type == "video") "فيديو" else "صوتية"
            val minutes = it.durationSeconds / 60L
            val seconds = it.durationSeconds % 60L
            val duration = if (it.durationSeconds > 0) " | ${"%02d:%02d".format(minutes, seconds)}" else ""
            val reason = it.endReason?.takeIf { value -> value.isNotBlank() }?.let { value -> " | $value" }.orEmpty()
            "${it.caller} ← ${it.callee} | $kind | ${it.status}$duration$reason | ${formatter.format(Date(it.startedAt))}"
        }
        AlertDialog.Builder(this)
            .setTitle("سجل المكالمات")
            .setMessage(if (rows.isEmpty()) "لا توجد مكالمات مسجلة" else rows.joinToString("\\n"))
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun openCall(type: String) {
        if (targetUser.isBlank()) {
            Toast.makeText(this, "اختر مستخدمًا أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        if (callCheckInProgress) return
        callCheckInProgress = true
        status.text = "جارٍ التحقق من اتصال $targetUser داخل الشبكة المحلية…"
        Thread {
            val peer = SyncManager.discover(1200).firstOrNull { it.name.equals(targetUser, ignoreCase = true) }
            val address = peer?.address ?: AppRepository.syncDevices().firstOrNull { it.name.equals(targetUser, ignoreCase = true) }?.address
            val reachable = address != null && SyncManager.sendCallSignal(
                address,
                CallSignal(kind = "probe", callId = "probe-${System.currentTimeMillis()}", fromUser = currentUser, toUser = targetUser)
            )
            runOnUiThread {
                callCheckInProgress = false
                if (!reachable || address == null) {
                    status.text = "تعذر الوصول إلى $targetUser محليًا. تأكد من اتصال الجهازين بالشبكة نفسها ومن فتح التطبيق."
                    Toast.makeText(this, "فشل اختبار الاتصال المحلي", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                status.text = "تم اختبار الاتصال المحلي بنجاح — جارٍ فتح المكالمة"
                startActivity(Intent(this, com.masahhisabat.app.ui.call.LocalCallActivity::class.java).apply {
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_USER, targetUser)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_PEER_ADDRESS, address)
                    putExtra(com.masahhisabat.app.ui.call.LocalCallActivity.EXTRA_MEDIA_TYPE, type)
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
        if (requestCode != pickerCode || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching {
            val temp = File(cacheDir, "direct_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            selectedImage = AppRepository.persistAppImage(temp.absolutePath)
            Toast.makeText(this, "الصورة جاهزة للإرسال", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "تعذر تجهيز الصورة", Toast.LENGTH_SHORT).show() }
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
