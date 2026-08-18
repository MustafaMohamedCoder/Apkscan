package com.masahhisabat.app.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.TrashEntry
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * فلسفة الواجهة: بطاقة سلة هادئة ضمن هوية Teal، تُقدّم الاستعادة كإجراء أساسي
 * وتضع الحذف النهائي خلف تأكيد واضح لمنع فقد البيانات دون قصد.
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var summary: TextView
    private lateinit var emptyButton: MaterialButton
    private lateinit var searchInput: EditText
    private lateinit var clearSearch: MaterialButton
    private val adapter = TrashAdapter()
    private var allEntries: List<TrashEntry> = emptyList()
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(createContent())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::recycler.isInitialized) refresh()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeHelper.bg(this@TrashActivity))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val back = MaterialButton(this).apply {
            text = "رجوع"
            isAllCaps = false
            setTextColor(ThemeHelper.text(this@TrashActivity))
            setOnClickListener { finish() }
        }
        val titlePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "سلة المحذوفات"
            textSize = 25f
            typeface = resources.getFont(R.font.tajawal_bold)
            setTextColor(ThemeHelper.text(this@TrashActivity))
            includeFontPadding = false
        }
        summary = TextView(this).apply {
            textSize = 13f
            typeface = resources.getFont(R.font.tajawal_regular)
            setTextColor(ThemeHelper.textSecondary(this@TrashActivity))
            includeFontPadding = false
        }
        titlePanel.addView(title)
        titlePanel.addView(summary)
        header.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titlePanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        root.addView(header)

        val searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        searchInput = EditText(this).apply {
            hint = "ابحث باسم المجموعة أو محتوى الرسالة"
            textSize = 15f
            typeface = resources.getFont(R.font.tajawal_regular)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_RTL
            setTextColor(ThemeHelper.text(this@TrashActivity))
            setHintTextColor(ThemeHelper.textSecondary(this@TrashActivity))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(9)
            backgroundTintList = ColorStateList.valueOf(ThemeHelper.accent(this@TrashActivity))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    clearSearch.visibility = if (searchQuery.isBlank()) View.GONE else View.VISIBLE
                    applySearch()
                }
            })
        }
        clearSearch = MaterialButton(this).apply {
            text = "مسح"
            isAllCaps = false
            visibility = View.GONE
            setTextColor(ThemeHelper.accent(this@TrashActivity))
            setOnClickListener {
                searchInput.setText("")
                searchInput.requestFocus()
            }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(searchRow)

        emptyState = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "سلة المحذوفات فارغة\nستظهر هنا الرسائل والمجموعات التي تحذفها."
            textSize = 16f
            typeface = resources.getFont(R.font.tajawal_regular)
            setTextColor(ThemeHelper.textSecondary(this@TrashActivity))
            visibility = View.GONE
            setPadding(dp(20), dp(52), dp(20), dp(52))
        }
        root.addView(emptyState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            adapter = this@TrashActivity.adapter
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyButton = MaterialButton(this).apply {
            text = "إفراغ السلة"
            isAllCaps = false
            setTextColor(ThemeHelper.text(this@TrashActivity))
            backgroundTintList = ColorStateList.valueOf(ThemeHelper.surfaceHigh(this@TrashActivity))
            setIconResource(R.drawable.ic_delete)
            setOnClickListener { confirmEmptyTrash() }
        }
        root.addView(emptyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun refresh() {
        allEntries = AppRepository.trashEntries()
        applySearch()
    }

    private fun applySearch() {
        if (!::searchInput.isInitialized) return
        val filtered = if (searchQuery.isBlank()) allEntries else allEntries.filter { entry ->
            normalizeSearch(entry.groupName).contains(normalizeSearch(searchQuery)) ||
                normalizeSearch(entry.group?.name.orEmpty()).contains(normalizeSearch(searchQuery)) ||
                normalizeSearch(entry.deletedBy.orEmpty()).contains(normalizeSearch(searchQuery)) ||
                entry.items.any { item ->
                    listOf(item.text, item.storeName, item.itemsText, item.sender, item.date, item.total, item.currency)
                        .any { value -> normalizeSearch(value.orEmpty()).contains(normalizeSearch(searchQuery)) }
                }
        }
        adapter.submit(filtered)
        val hasEntries = allEntries.isNotEmpty()
        val hasResults = filtered.isNotEmpty()
        emptyState.text = if (!hasEntries) {
            "سلة المحذوفات فارغة\nستظهر هنا الرسائل والمجموعات التي تحذفها."
        } else {
            "لا توجد نتائج مطابقة\nجرّب اسم المجموعة أو جزءًا من محتوى الرسالة."
        }
        emptyState.visibility = if (hasResults) View.GONE else View.VISIBLE
        recycler.visibility = if (hasResults) View.VISIBLE else View.GONE
        emptyButton.visibility = if (hasEntries && searchQuery.isBlank()) View.VISIBLE else View.GONE
        summary.text = when {
            !hasEntries -> "لا توجد عناصر بانتظار الاستعادة أو الحذف النهائي"
            searchQuery.isNotBlank() && !hasResults -> "لا توجد نتائج للبحث داخل ${allEntries.size} عنصر"
            searchQuery.isNotBlank() -> "${filtered.size} نتيجة من أصل ${allEntries.size} عنصر في السلة"
            else -> "${allEntries.size} عنصر محفوظ محليًا — يمكن استعادته في أي وقت"
        }
    }

    private fun normalizeSearch(value: String): String =
        value.lowercase(Locale("ar"))
            .replace(Regex("[ًٌٍَُِّْ]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .trim()

    private fun restore(entry: TrashEntry) {
        if (AppRepository.restoreTrashEntry(entry.id)) {
            val user = SessionStore.currentUser(this) ?: "?"
            AppRepository.logActivity(ActivityEntry(user, "استعاد ${entryLabel(entry)} من سلة المحذوفات"))
            Toast.makeText(this, "تمت الاستعادة بنجاح", Toast.LENGTH_SHORT).show()
            refresh()
        } else {
            Toast.makeText(this, "تعذرت الاستعادة؛ قد تكون المجموعة موجودة أو حُذفت نهائيًا", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmPermanentDelete(entry: TrashEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف نهائي؟")
            .setMessage("سيُحذف ${entryLabel(entry)} نهائيًا من السلة، وقد تُحذف الصور المرتبطة غير المستخدمة. لا يمكن التراجع عن هذا الإجراء.")
            .setPositiveButton("حذف نهائي") { _, _ ->
                if (AppRepository.permanentlyDeleteTrashEntry(entry.id)) {
                    val user = SessionStore.currentUser(this) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, "حذف ${entryLabel(entry)} نهائيًا من سلة المحذوفات"))
                    Toast.makeText(this, "تم الحذف النهائي", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmEmptyTrash() {
        val count = AppRepository.trashEntries().size
        if (count == 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle("إفراغ سلة المحذوفات؟")
            .setMessage("سيُحذف $count عنصر نهائيًا مع أي صور لا تستخدمها عناصر أخرى. لا يمكن التراجع عن هذا الإجراء.")
            .setPositiveButton("إفراغ السلة") { _, _ ->
                val removed = AppRepository.emptyTrash()
                val user = SessionStore.currentUser(this) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, "أفرغ سلة المحذوفات ($removed عنصر)"))
                Toast.makeText(this, "تم إفراغ السلة", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun entryLabel(entry: TrashEntry): String =
        if (entry.type == "group") "المجموعة «${entry.groupName}»" else "رسالة من «${entry.groupName}»"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class TrashAdapter : RecyclerView.Adapter<TrashAdapter.Holder>() {
        private var entries: List<TrashEntry> = emptyList()

        fun submit(value: List<TrashEntry>) {
            entries = value
            notifyDataSetChanged()
        }

        inner class Holder(val card: MaterialCardView, val title: TextView, val detail: TextView, val restore: MaterialButton, val delete: MaterialButton) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = MaterialCardView(parent.context).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                useCompatPadding = false
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val title = TextView(parent.context).apply {
                textSize = 17f
                typeface = resources.getFont(R.font.tajawal_bold)
                includeFontPadding = false
            }
            val detail = TextView(parent.context).apply {
                textSize = 13f
                typeface = resources.getFont(R.font.tajawal_regular)
                includeFontPadding = false
                setPadding(0, dp(5), 0, dp(10))
            }
            val actions = LinearLayout(parent.context).apply { gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL }
            val restore = MaterialButton(parent.context).apply { text = "استعادة"; isAllCaps = false; setIconResource(R.drawable.ic_history) }
            val delete = MaterialButton(parent.context).apply { text = "حذف نهائي"; isAllCaps = false; setIconResource(R.drawable.ic_delete) }
            actions.addView(restore)
            actions.addView(delete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            content.addView(title)
            content.addView(detail)
            content.addView(actions)
            card.addView(content)
            return Holder(card, title, detail, restore, delete)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            val ctx = holder.card.context
            holder.card.setCardBackgroundColor(ThemeHelper.surface(ctx))
            holder.card.strokeColor = ThemeHelper.cardStroke(ctx)
            holder.title.setTextColor(ThemeHelper.text(ctx))
            holder.detail.setTextColor(ThemeHelper.textSecondary(ctx))
            holder.restore.setTextColor(ThemeHelper.accent(ctx))
            holder.delete.setTextColor(resources.getColor(R.color.error, null))
            holder.title.text = if (entry.type == "group") "مجموعة محذوفة: ${entry.groupName}" else "رسالة محذوفة من: ${entry.groupName}"
            val time = SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale("ar")).format(Date(entry.deletedAt))
            val actor = entry.deletedBy?.takeIf { it.isNotBlank() }?.let { " • بواسطة $it" }.orEmpty()
            val body = if (entry.type == "group") "${entry.items.size} رسالة داخل المجموعة" else entry.items.firstOrNull()?.text?.take(54)?.ifBlank { "مرفق أو صورة" } ?: "مرفق أو صورة"
            holder.detail.text = "$body\nحُذف في $time$actor"
            holder.restore.setOnClickListener { restore(entry) }
            holder.delete.setOnClickListener { confirmPermanentDelete(entry) }
        }

        override fun getItemCount(): Int = entries.size
    }
}
