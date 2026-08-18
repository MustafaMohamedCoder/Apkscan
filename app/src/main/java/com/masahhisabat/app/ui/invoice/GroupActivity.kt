package com.masahhisabat.app.ui.invoice

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.image.ImageProcessor
import android.os.Build
import android.graphics.Color
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.io.File

/**
 * شاشة المجموعة بأسلوب تليجرام:
 * فقاعات رسائل (أقصى اليمين) تدعم: صورة فقط / نص فقط / صورة ونص معًا في بطاقة واحدة.
 * أيقونة القلم أعلى الرسالة للتعديل، وأيقونة الحذف أسفلها.
 */
class GroupActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private var groupId: String = ""
    private var groupName: String = ""
    private val selected = mutableSetOf<String>()
    private var isSelecting = false
    private lateinit var adapter: ItemsAdapter
    private lateinit var searchInput: EditText
    private lateinit var messageInput: EditText
    private var savedQuery: String = ""
    private var isSending = false
    private var isPreparingAttachment = false
    private val draftHandler = Handler(Looper.getMainLooper())
    private val saveDraftTask = Runnable {
        if (::messageInput.isInitialized) {
            AppRepository.setMessageDraft(groupId, messageInput.text.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.masahhisabat.app.data.AppRepository.initAppContext(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group)
        applyTheme()

        groupId = intent.getStringExtra("group_id") ?: ""
        val group = AppRepository.groups().find { it.id == groupId }
        if (group == null) {
            Toast.makeText(this, "المجموعة غير موجودة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        groupName = group.name
        AppRepository.setLastOpenedGroupId(groupId)

        findViewById<TextView>(R.id.group_title).text = groupName
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        recycler = findViewById(R.id.items_list)
        recycler.layoutManager = LinearLayoutManager(this)

        searchInput = findViewById(R.id.et_search)
        savedQuery = AppRepository.lastSavedSearch(groupId)
        searchInput.setText(savedQuery)

        adapter = ItemsAdapter()
        recycler.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                AppRepository.setLastSavedSearch(groupId, q)
                applyFilter()
            }
        })

        // شريط الإرسال السفلي (مثل تليجرام/واتساب)
        val etMessage = findViewById<EditText>(R.id.et_message)
        messageInput = etMessage
        val btnAttach = findViewById<ImageView>(R.id.btn_attach)
        val btnSend = findViewById<ImageView>(R.id.btn_send)
        findViewById<MaterialButton>(R.id.btn_add_attachment).setOnClickListener {
            btnSend.performClick()
        }
        findViewById<ImageView>(R.id.btn_remove_attachment).setOnClickListener {
            clearPendingAttachment(deleteFile = true)
        }

        // تحفظ مسودة النص محليًا بعد توقف قصير عن الكتابة حتى لا يفقدها المستخدم عند العودة أو الإغلاق.
        val restoredDraft = AppRepository.messageDraft(groupId)
        if (restoredDraft.isNotBlank()) {
            etMessage.setText(restoredDraft)
            etMessage.setSelection(restoredDraft.length)
            Snackbar.make(etMessage, "تمت استعادة مسودة غير مرسلة", Snackbar.LENGTH_LONG).show()
        }
        etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draftHandler.removeCallbacks(saveDraftTask)
                draftHandler.postDelayed(saveDraftTask, 450L)
            }
        })

        btnAttach.setOnClickListener { attachLauncher.launch("image/*") }

        btnSend.setOnClickListener {
            val ctx = this
            if (isSending) {
                Toast.makeText(ctx, "يجري حفظ الرسالة…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPreparingAttachment) {
                Toast.makeText(ctx, "يجري تجهيز الصورة…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val role = SessionStore.currentRole(ctx)
            if (!AppRepository.canEdit(role)) {
                Toast.makeText(ctx, "لا تملك صلاحية للإضافة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = etMessage.text.toString().trim()
            val textAttachment = pendingAttach
            if (text.isBlank() && textAttachment == null) {
                Toast.makeText(ctx, "اكتب نصًا أو أرفق صورة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isSending = true
            btnSend.isEnabled = false
            btnSend.alpha = 0.55f
            // النسخ إلى Documents والكتابة إلى JSON عمليتان قد تكونان بطيئتين؛ تنفذان بعيدًا عن الواجهة.
            Thread {
                val error = try {
                    if (textAttachment != null) {
                        val permanentPath = AppRepository.persistAppImage(textAttachment)
                            ?: throw IllegalStateException("تعذر حفظ الصورة بشكل دائم. فعّل إذن الوصول إلى الملفات ثم أعد المحاولة.")
                        AppRepository.addItem(groupId, InvoiceItem(
                            type = "image",
                            imagePath = permanentPath,
                            processedPath = null,
                            text = text.ifBlank { null }
                        ))
                        val user = SessionStore.currentUser(ctx) ?: "?"
                        AppRepository.logActivity(ActivityEntry(user, "أضاف $user صورة${if (text.isNotBlank()) " ونصًا" else ""} في $groupName"))
                    } else {
                        AppRepository.addItem(groupId, InvoiceItem(type = "text", text = text))
                        val user = SessionStore.currentUser(ctx) ?: "?"
                        AppRepository.logActivity(ActivityEntry(user, "أضاف $user نصًا يدويًا في $groupName"))
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: "تعذر حفظ الرسالة. أعد المحاولة."
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isSending = false
                    btnSend.isEnabled = true
                    btnSend.alpha = 1f
                    if (error == null) {
                        if (pendingAttach == textAttachment) {
                            clearPendingAttachment(deleteFile = true)
                        } else {
                            textAttachment?.let { File(it).delete() }
                        }
                        etMessage.setText("")
                        draftHandler.removeCallbacks(saveDraftTask)
                        AppRepository.clearMessageDraft(groupId)
                        hideSoftKeyboard(etMessage)
                        refresh()
                        Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, error, Toast.LENGTH_LONG).show()
                    }
                }
            }.apply { name = "group-message-save"; start() }
        }

        // إرسال بالضغط على زر الإرسال في لوحة المفاتيح
        etMessage.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend.performClick()
                true
            } else false
        }

        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            searchInput.setText("")
            selected.clear(); isSelecting = false; refresh()
        }

        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (::messageInput.isInitialized) {
            draftHandler.removeCallbacks(saveDraftTask)
            AppRepository.setMessageDraft(groupId, messageInput.text.toString())
        }
    }

    override fun onDestroy() {
        draftHandler.removeCallbacks(saveDraftTask)
        super.onDestroy()
    }

    private var pendingAttach: String? = null

    private val attachLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || isPreparingAttachment) return@registerForActivityResult
        isPreparingAttachment = true
        findViewById<ImageView>(R.id.btn_attach).isEnabled = false
        Toast.makeText(this, "يجري تجهيز الصورة…", Toast.LENGTH_SHORT).show()
        Thread {
            val copied = copyToInternal(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                isPreparingAttachment = false
                findViewById<ImageView>(R.id.btn_attach).isEnabled = true
                if (copied != null) {
                    pendingAttach = copied
                    showAttachmentPreview(copied)
                } else {
                    Toast.makeText(this, "تعذر قراءة الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun copyToInternal(uri: Uri): String? {
        var raw: File? = null
        var compressed: File? = null
        var bitmap: android.graphics.Bitmap? = null
        var complete = false
        return try {
            val rawFile = File(cacheDir, "attach_raw_${System.currentTimeMillis()}.jpg")
            raw = rawFile
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { stream -> rawFile.outputStream().use { output -> stream.copyTo(output) } }
            // فك الصورة بحجم محدود في الخلفية قبل ضغطها، لتجنب ضغط الذاكرة مع صور الكاميرا الكبيرة.
            val decodedBitmap = ImageProcessor.loadBitmap(rawFile.absolutePath, ImageProcessor.ATTACHMENT_MAX_DIM)
            bitmap = decodedBitmap
            val compressedFile = ImageProcessor.saveTo(
                decodedBitmap,
                cacheDir,
                "attach",
                quality = ImageProcessor.ATTACHMENT_JPEG_QUALITY
            )
            compressed = compressedFile
            complete = true
            compressedFile.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            bitmap?.recycle()
            raw?.delete()
            if (!complete) compressed?.delete()
        }
    }

    /** معاينة ثابتة تتيح كتابة نص مصاحب ثم تأكيد الإضافة أو إلغاء المرفق. */
    private fun showAttachmentPreview(path: String) {
        val thumb = findViewById<ImageView>(R.id.attachment_thumb)
        val previewBitmap = try {
            ImageProcessor.loadBitmap(path, 480)
        } catch (_: Exception) {
            clearPendingAttachment(deleteFile = true)
            Toast.makeText(this, "تعذر تجهيز معاينة الصورة", Toast.LENGTH_SHORT).show()
            return
        }
        thumb.setImageDrawable(null)
        thumb.setImageBitmap(previewBitmap)
        findViewById<TextView>(R.id.attachment_label).text = "الصورة جاهزة — اكتب نصًا اختياريًا ثم أضفها"
        findViewById<View>(R.id.attachment_preview).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.btn_add_attachment).isEnabled = true
        findViewById<EditText>(R.id.et_message).hint = "اكتب نصًا مصاحبًا للصورة..."
    }

    private fun clearPendingAttachment(deleteFile: Boolean) {
        val path = pendingAttach
        pendingAttach = null
        if (deleteFile) path?.let { File(it).delete() }
        findViewById<ImageView>(R.id.attachment_thumb).setImageDrawable(null)
        findViewById<View>(R.id.attachment_preview).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btn_add_attachment).isEnabled = false
        findViewById<EditText>(R.id.et_message).hint = "اكتب رسالة..."
    }

    private fun showSoftKeyboard(view: android.view.View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) { }
    }

    private fun hideSoftKeyboard(view: android.view.View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) { }
    }

    private fun applyFilter() {
        val q = searchInput.text.toString().trim().lowercase()
        adapter.submit(if (q.isBlank()) AppRepository.items(groupId)
        else AppRepository.items(groupId).filter { itemMatches(it, q) })
    }

    private fun itemMatches(item: InvoiceItem, q: String): Boolean =
        (item.storeName?.lowercase()?.contains(q) == true) ||
            (item.date?.contains(q) == true) ||
            (item.total?.contains(q) == true) ||
            (item.currency?.lowercase()?.contains(q) == true) ||
            (item.text?.lowercase()?.contains(q) == true) ||
            (item.itemsText?.lowercase()?.contains(q) == true)

    private fun showAddTextDialog() {
        val ctx = this
        val input = EditText(ctx).apply {
            setPadding(24, 24, 24, 24)
            hint = getString(R.string.manual_text)
            minHeight = 120
            gravity = android.view.Gravity.START
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.add_text)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    AppRepository.addItem(groupId, InvoiceItem(type = "text", text = text))
                    val user = SessionStore.currentUser(ctx) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "أضاف $user نصاً يدوياً في $groupName"))
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        applyFilter()
        updateSelectionBar()
        adapter.markOthersSeen()
    }

    private fun updateSelectionBar() {
        val bar = findViewById<View>(R.id.selection_bar)
        val count = findViewById<TextView>(R.id.selected_count)
        if (isSelecting && selected.isNotEmpty()) {
            bar.visibility = View.VISIBLE
            count.text = getString(R.string.selected_items, selected.size)
        } else {
            bar.visibility = View.GONE
        }
        findViewById<MaterialButton>(R.id.btn_delete_selected).setOnClickListener { confirmDeleteSelected() }
        findViewById<MaterialButton>(R.id.btn_share_selected).setOnClickListener { shareSelected() }
        findViewById<MaterialButton>(R.id.btn_cancel_select).setOnClickListener {
            selected.clear(); isSelecting = false; refresh()
        }
    }

    private fun confirmDeleteSelected() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(R.string.delete_confirm_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteWithUndo(selected.toList())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** يحافظ على الملفات خلال مهلة قصيرة ويعرض زر تراجع قبل الحذف النهائي. */
    private fun deleteWithUndo(ids: List<String>) {
        val user = SessionStore.currentUser(this) ?: "?"
        val removed = AppRepository.moveItemsToTrash(groupId, groupName, ids, user)
        if (removed.isEmpty()) return
        AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_delete)))
        selected.clear()
        isSelecting = false
        refresh()

        Snackbar.make(recycler, "نُقلت ${removed.size} ${if (removed.size == 1) "رسالة" else "رسائل"} إلى سلة المحذوفات", Snackbar.LENGTH_LONG)
            .setAction("تراجع") {
                val restored = removed.count { AppRepository.restoreTrashEntry(it.id) }
                if (restored > 0) {
                    AppRepository.logActivity(ActivityEntry(user, "تراجع عن حذف $restored رسالة في $groupName"))
                    refresh()
                    Toast.makeText(this, "تمت استعادة ${if (restored == 1) "الرسالة" else "$restored رسائل"}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareSelected() {
        if (selected.isEmpty()) return
        try {
            val paths = AppRepository.items(groupId).filter { it.id in selected && it.imagePath != null }
                .mapNotNull { it.imagePath }
            if (paths.isEmpty()) {
                Toast.makeText(this, "لا توجد صور في العناصر المحددة", Toast.LENGTH_SHORT).show()
                return
            }
            val uris = paths.map {
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(it))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyTheme() {
        window.decorView.setBackgroundColor(ThemeHelper.bg(this))
        findViewById<View>(R.id.group_root).setBackgroundColor(ThemeHelper.bg(this))
        val text = ThemeHelper.text(this)
        val textSec = ThemeHelper.textSecondary(this)
        val surface = ThemeHelper.surface(this)
        findViewById<TextView>(R.id.group_title).setTextColor(text)
        findViewById<EditText>(R.id.et_search).setTextColor(text)
        findViewById<EditText>(R.id.et_search).setHintTextColor(textSec)
        findViewById<EditText>(R.id.et_search).background?.setTint(ThemeHelper.inputFill(this))
        findViewById<View>(R.id.selection_bar)?.setBackgroundColor(ThemeHelper.surfaceHigh(this))
        findViewById<EditText>(R.id.et_message).setTextColor(ThemeHelper.text(this))
        findViewById<EditText>(R.id.et_message).background?.setTint(ThemeHelper.inputFill(this))
        findViewById<View>(R.id.compose_bar)?.background?.setTint(ThemeHelper.surface(this))
    }

    inner class ItemsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<InvoiceItem> = emptyList()

        fun submit(list: List<InvoiceItem>) {
            val newItems = list.toList()
            val previousItems = items
            if (previousItems == newItems) return
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousItems[oldItemPosition].id == newItems[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    previousItems[oldItemPosition] == newItems[newItemPosition]
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_invoice, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            val text = ThemeHelper.text(ctx)
            val textSec = ThemeHelper.textSecondary(ctx)

            val bubble = holder.itemView.findViewById<View>(R.id.msg_bubble)
            val img = holder.itemView.findViewById<ImageView>(R.id.item_image)
            val tvName = holder.itemView.findViewById<TextView>(R.id.item_name)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.item_date)
            val tvDetails = holder.itemView.findViewById<TextView>(R.id.item_details)
            val metaRow = holder.itemView.findViewById<View>(R.id.meta_row)
            val checkbox = holder.itemView.findViewById<ImageView>(R.id.item_check)
            val msgFooter = holder.itemView.findViewById<View>(R.id.msg_footer)
            val tvTimeDate = holder.itemView.findViewById<TextView>(R.id.msg_time_date)
            val tvSender = holder.itemView.findViewById<TextView>(R.id.msg_sender)
            val ivSeen = holder.itemView.findViewById<ImageView>(R.id.msg_seen)
            val pencil = holder.itemView.findViewById<ImageView>(R.id.item_edit_pencil)
            val share = holder.itemView.findViewById<ImageView>(R.id.item_share)

            // لون الفقاعة: تمييز متباين عند الاختيار، وإيقاع واضح/هادئ بين الرسائل المتجاورة.
            val itemSelected = isSelecting && item.id in selected
            val isNight = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (itemSelected) {
                bubble.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 24f * ctx.resources.displayMetrics.density
                    setColor(Color.parseColor(if (isNight) "#164B45" else "#CCFBF1"))
                    setStroke(
                        (3f * ctx.resources.displayMetrics.density).toInt(),
                        Color.parseColor(if (isNight) "#5EEAD4" else "#0F766E")
                    )
                }
                bubble.elevation = 7f * ctx.resources.displayMetrics.density
            } else {
                // كل فقاعة ثانية أخف قليلًا؛ الموضع داخل القائمة يضمن تناوبًا ثابتًا من رسالة لأخرى.
                val useSoftBubble = position % 2 != 0
                val bubbleBgRes = if (useSoftBubble) {
                    if (isNight) R.drawable.msg_bubble_bg_alt_night else R.drawable.msg_bubble_bg_alt
                } else {
                    ThemeHelper.bubbleBgRes(ctx)
                }
                val bubbleBg = androidx.appcompat.content.res.AppCompatResources
                    .getDrawable(ctx, bubbleBgRes)
                if (bubbleBg != null) {
                    bubbleBg.mutate()
                    bubble.background = bubbleBg
                }
                bubble.elevation = 2f * ctx.resources.displayMetrics.density
            }
            tvName.setTextColor(ThemeHelper.bubbleText(ctx))
            tvDate.setTextColor(ThemeHelper.bubbleTime(ctx))
            tvDetails.setTextColor(ThemeHelper.bubbleTime(ctx))
            tvTimeDate.setTextColor(ThemeHelper.bubbleTime(ctx))
            tvSender.setTextColor(ThemeHelper.bubbleText(ctx))
            ivSeen?.setColorFilter(ThemeHelper.bubbleSeen(ctx))

            val primaryText = ThemeHelper.text(ctx)
            val secondaryText = ThemeHelper.textSecondary(ctx)

            // أيقونتا الإجراءات بيضاوان داخل دوائر كبيرة لتظلّا واضحتين خارج الفقاعة.
            pencil.setColorFilter(Color.WHITE)
            share.setColorFilter(Color.WHITE)

            if (item.type == "text") {
                // فقاعة نص فقط
                img.visibility = View.GONE
                tvName.text = item.text ?: ""
                tvName.setTextColor(ThemeHelper.bubbleText(ctx))
                tvName.visibility = View.VISIBLE
                metaRow.visibility = View.GONE
            } else {
                // فقاعة صورة (مع أو بدون نص)
                img.visibility = View.VISIBLE
                val path = AppRepository.availableImagePath(item)
                if (path != null) {
                    try {
                        val bmp = ImageProcessor.loadBitmap(path, 600)
                        img.setImageBitmap(bmp)
                    } catch (e: Exception) { img.setImageResource(R.drawable.ic_invoice) }
                } else {
                    img.setImageResource(R.drawable.ic_invoice)
                }

                // إذا كان هناك نص إضافي مع الصورة
                val extraText = item.text?.takeIf { it.isNotBlank() }
                    ?: item.storeName?.takeIf { it.isNotBlank() }
                if (extraText != null) {
                    tvName.text = extraText
                    tvName.setTextColor(ThemeHelper.bubbleText(ctx))
                    tvName.visibility = View.VISIBLE
                } else {
                    tvName.visibility = View.GONE
                }

                // سطر التفاصيل أسفل الرسالة (تاريخ + مبلغ)
                val details = listOfNotNull(
                    item.total?.let { "${it} ${item.currency ?: ""}".trim() },
                    item.itemsText?.take(40)
                ).joinToString(" · ").ifBlank { "" }
                if (details.isNotBlank() || item.date != null) {
                    tvDetails.text = details
                    tvDetails.setTextColor(ThemeHelper.bubbleTime(ctx))
                    val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                    tvDate.text = try {
                        item.date?.let { rawDate ->
                            val parsedDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(rawDate)
                            parsedDate?.let(fmt::format) ?: rawDate
                        } ?: ""
                    } catch (e: Exception) { item.date ?: "" }
                    tvDate.setTextColor(ThemeHelper.bubbleTime(ctx))
                    metaRow.visibility = View.VISIBLE
                } else {
                    metaRow.visibility = View.GONE
                }
            }

            // تذييل الرسالة: وقت وتاريخ الإرسال + اسم المرسل + علامة ✓ (زرقاء عند المشاهدة)
            val me = SessionStore.currentUser(ctx)
            val sender = item.sender?.takeIf { it.isNotBlank() } ?: me
            val fmtTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            val fmtDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            tvTimeDate.text = "${fmtTime.format(java.util.Date(item.createdAt))} ${fmtDate.format(java.util.Date(item.createdAt))}"
            tvTimeDate.setTextColor(ThemeHelper.bubbleTime(ctx))
            tvSender.text = sender ?: ""
            tvSender.setTextColor(ThemeHelper.bubbleText(ctx))
            if (sender != null) {
                msgFooter.visibility = View.VISIBLE
                // علامة ✓: بنفس لون نص الشريط عند الإرسال، زرقاء عند مشاهدة مستخدمين آخرين
                ivSeen.visibility = View.VISIBLE
                if (item.seen) {
                    ivSeen.background = androidx.appcompat.content.res.AppCompatResources
                        .getDrawable(ctx, R.drawable.message_seen_badge)
                    ivSeen.setColorFilter(Color.WHITE)
                } else {
                    ivSeen.background = null
                    ivSeen.setColorFilter(ThemeHelper.bubbleTime(ctx))
                }
            } else {
                msgFooter.visibility = View.GONE
                ivSeen.visibility = View.GONE
            }

            // التحديد الجماعي
            if (isSelecting) {
                checkbox.visibility = View.VISIBLE
                checkbox.setImageResource(if (item.id in selected) R.drawable.ic_check else R.drawable.ic_visibility_off)
                checkbox.setColorFilter(ThemeHelper.text(ctx))
            } else {
                checkbox.visibility = View.GONE
            }

            // النقر على الفقاعة: معاينة الصورة أو تحديد
            val openOrSelectItem: (View) -> Unit = {
                if (isSelecting) {
                    if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
                    if (selected.isEmpty()) isSelecting = false
                    refresh()
                } else {
                    if (item.type == "image") showImagePreview(item)
                }
            }
            holder.itemView.setOnClickListener(openOrSelectItem)
            // هدف نقر صريح للصورة نفسها؛ بعض واجهات الأجهزة لا تمرر اللمسة من ImageView إلى فقاعة الرسالة.
            img.setOnClickListener(openOrSelectItem)

            holder.itemView.setOnLongClickListener {
                if (!isSelecting) {
                    isSelecting = true
                    selected.add(item.id)
                    refresh()
                }
                true
            }

            // أيقونة القلم أعلى الرسالة للتعديل
            pencil.setOnClickListener {
                val role = SessionStore.currentRole(ctx)
                if (!AppRepository.canEdit(role)) {
                    Toast.makeText(ctx, "لا تملك صلاحية", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                editItem(item)
            }

            // أيقونة المشاركة أسفل الرسالة
            share.setOnClickListener { shareItem(item) }
        }

        override fun getItemCount() = items.size

        /** عند فتح المجموعة: تعليم كل الرسائل من مستخدمين آخرين بأنها شوهدت */
        fun markOthersSeen() {
            val me = SessionStore.currentUser(this@GroupActivity)
            val others = AppRepository.items(groupId).filter { it.sender != me && !it.seen }
            if (others.isNotEmpty()) {
                others.forEach { AppRepository.updateItem(groupId, it.copy(seen = true)) }
                refresh()
            }
        }

        private fun showImagePreview(item: InvoiceItem) {
            val ctx = this@GroupActivity
            val selectedPath = AppRepository.availableImagePath(item)
            if (selectedPath == null) {
                Toast.makeText(ctx, "ملف الصورة غير متوفر على الجهاز", Toast.LENGTH_SHORT).show()
                return
            }
            // نمرر المسارات القابلة للقراءة مباشرة. لا يعتمد العارض على إعادة قراءة قائمة الرسائل
            // بعد فتح نشاط جديد، وهو ما كان يجعل العارض فارغًا على بعض الأجهزة.
            val imagePaths = items
                .filter { it.type == "image" }
                .mapNotNull { AppRepository.availableImagePath(it) }
                .distinct()
                .ifEmpty { listOf(selectedPath) }
            val imageIndex = imagePaths.indexOf(selectedPath).coerceAtLeast(0)
            val intent = android.content.Intent(ctx, ImageViewerActivity::class.java).apply {
                putExtra("group_id", groupId)
                putExtra("image_index", imageIndex)
                putExtra("image_path", selectedPath)
                putStringArrayListExtra("image_paths", ArrayList(imagePaths))
            }
            ctx.startActivity(intent)
        }

        /** تعديل الرسالة: نص أو صورة+نص أو بيانات الصورة */
        private fun editItem(item: InvoiceItem) {
            val ctx = this@GroupActivity
            val isTextOnly = item.type == "text"

            val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_message, null)
            val etText = view.findViewById<EditText>(R.id.et_message_text)
            val etStore = view.findViewById<EditText>(R.id.et_store)
            val etDate = view.findViewById<EditText>(R.id.et_date)
            val etTotal = view.findViewById<EditText>(R.id.et_total)
            val etItems = view.findViewById<EditText>(R.id.et_items)
            val tvImgSection = view.findViewById<TextView>(R.id.tv_img_section)

            etText.setText(item.text ?: "")
            etStore.setText(item.storeName ?: "")
            etDate.setText(item.date ?: "")
            etTotal.setText(item.total ?: "")
            etItems.setText(item.itemsText ?: "")

            if (isTextOnly) {
                // رسالة نصية فقط: إظهار حقل النص فقط
                tvImgSection?.visibility = View.GONE
                etStore.visibility = View.GONE
                etDate.visibility = View.GONE
                etTotal.visibility = View.GONE
                etItems.visibility = View.GONE
            } else {
                // صورة (+ نص اختياري): إظهار كل الحقول
                tvImgSection?.visibility = View.VISIBLE
                etStore.visibility = View.VISIBLE
                etDate.visibility = View.VISIBLE
                etTotal.visibility = View.VISIBLE
                etItems.visibility = View.VISIBLE
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.edit)
                .setView(view)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newText = etText.text.toString().trim().ifBlank { null }
                    if (isTextOnly) {
                        if (newText == null) {
                            Toast.makeText(ctx, "النص لا يمكن أن يكون فارغًا", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        AppRepository.updateItem(groupId, item.copy(text = newText))
                    } else {
                        AppRepository.updateItem(groupId, item.copy(
                            text = newText,
                            storeName = etStore.text.toString().trim().ifBlank { null },
                            date = etDate.text.toString().trim().ifBlank { null },
                            total = etTotal.text.toString().trim().ifBlank { null },
                            itemsText = etItems.text.toString().trim().ifBlank { null }
                        ))
                    }
                    Toast.makeText(ctx, R.string.success, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun confirmDeleteItem(item: InvoiceItem) {
            MaterialAlertDialogBuilder(this@GroupActivity)
                .setTitle(R.string.confirm_delete)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    deleteWithUndo(listOf(item.id))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun shareItem(item: InvoiceItem) {
            try {
                val path = item.imagePath ?: item.processedPath
                if (path != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@GroupActivity, "${this@GroupActivity.packageName}.fileprovider", java.io.File(path))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share)))
                } else if (item.text != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, item.text)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share)))
                } else {
                    Toast.makeText(this@GroupActivity, "لا يوجد محتوى للمشاركة", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@GroupActivity, "تعذرت المشاركة", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
