package com.masahhisabat.app.ui.search

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.image.ImageProcessor
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.GroupActivity

/**
 * بحث متقدم على مستوى التطبيق:
 * - بحث بالمتجر والمبلغ والتاريخ والنصوص اليدوية والبيانات المستخرجة
 * - فلاتر (التاريخ، المتجر، المبلغ)
 * - اقتراحات تلقائية مع تمييز التطابقات المتكررة
 * - تأثير تحميل أثناء جلب النتائج
 */
class SearchFragment : Fragment() {

    private lateinit var searchInput: EditText
    private lateinit var suggestionsPanel: LinearLayout
    private lateinit var resultsPanel: LinearLayout
    private lateinit var loadingBar: ProgressBar
    private lateinit var emptyText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var query = ""
    private var filterDate = ""
    private var filterStore = ""
    private var filterAmount = ""
    private var filterGroup = ""
    private var filterSender = ""
    private var filterType = "" // image | text | فارغ لكل الأنواع

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)

        searchInput = view.findViewById(R.id.et_search)
        suggestionsPanel = view.findViewById(R.id.suggestions_panel)
        resultsPanel = view.findViewById(R.id.results_panel)
        loadingBar = view.findViewById(R.id.loading_bar)
        emptyText = view.findViewById(R.id.empty_text)

        // استعادة آخر سياق للبحث حتى لا يفقد المستخدم عمله عند التنقل بين التبويبات.
        filterDate = AppRepository.lastSavedSearch("global_filter_date")
        filterStore = AppRepository.lastSavedSearch("global_filter_store")
        filterAmount = AppRepository.lastSavedSearch("global_filter_amount")
        filterGroup = AppRepository.lastSavedSearch("global_filter_group")
        filterSender = AppRepository.lastSavedSearch("global_filter_sender")
        filterType = AppRepository.lastSavedSearch("global_filter_type")
        searchInput.setText(AppRepository.lastSavedSearch("global_query"))

        view.findViewById<MaterialButton>(R.id.btn_filter).setOnClickListener { showFilterDialog() }
        view.findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            searchInput.setText("")
            filterDate = ""; filterStore = ""; filterAmount = ""
            filterGroup = ""; filterSender = ""; filterType = ""
            saveSearchContext()
            suggestionsPanel.removeAllViews()
            resultsPanel.removeAllViews()
            emptyText.visibility = View.GONE
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    query = s.toString().trim()
                    AppRepository.setLastSavedSearch("global_query", query)
                    if (query.isNotEmpty() || hasActiveFilters()) {
                        loadingBar.visibility = View.VISIBLE
                        searchDebounced()
                    } else {
                        suggestionsPanel.removeAllViews()
                        resultsPanel.removeAllViews()
                        loadingBar.visibility = View.GONE
                        emptyText.visibility = View.GONE
                    }
                }, 250)
            }
        })
    }

    private fun applyTheme(view: View) {
        view.setBackgroundColor(ThemeHelper.bg(requireContext()))
        view.findViewById<View>(R.id.search_root).setBackgroundColor(ThemeHelper.bg(requireContext()))
        val ctx = requireContext()
        val text = ThemeHelper.text(ctx)
        view.findViewById<TextView>(R.id.title).setTextColor(text)
        view.findViewById<EditText>(R.id.et_search).setTextColor(text)
        view.findViewById<EditText>(R.id.et_search).setHintTextColor(ThemeHelper.textSecondary(ctx))
        // خلفية حقل البحث من input_bg (تتكيف مع الوضع) — نلوّنها ديناميكيًا بدل الكتابة فوقها
        view.findViewById<EditText>(R.id.et_search).background?.setTint(ThemeHelper.inputFill(ctx))
        // أيقونة البحث داخل الحقل بلون ثانوي
        view.findViewById<ImageView>(R.id.search_icon)?.setColorFilter(ThemeHelper.textSecondary(ctx))
    }

    private fun searchDebounced() {
        Thread {
            try {
                Thread.sleep(350) // محاكاة تأثير تحميل
            } catch (_: InterruptedException) {}
        }.start()
        performSearch()
    }

    private fun performSearch() {
        val q = query.lowercase()
        if (q.isBlank() && !hasActiveFilters()) {
            resultsPanel.removeAllViews()
            emptyText.visibility = View.GONE
            loadingBar.visibility = View.GONE
            return
        }
        loadingBar.visibility = View.VISIBLE

        val results = mutableListOf<Pair<String, InvoiceItem>>()
        for (g in AppRepository.groups()) {
            for (item in AppRepository.items(g.id)) {
                if ((q.isBlank() || matches(item, q)) && matchesFilters(item, g.name)) {
                    results.add(Pair(g.name, item))
                }
            }
        }

        // اقتراحات: كلمات متكررة في البيانات المستخرجة
        if (q.isNotBlank()) showSuggestions(q) else suggestionsPanel.removeAllViews()

        handler.post {
            loadingBar.visibility = View.GONE
            resultsPanel.removeAllViews()
            if (results.isEmpty()) {
                emptyText.visibility = View.VISIBLE
            } else {
                emptyText.visibility = View.GONE
                results.forEach { (groupName, item) ->
                    resultsPanel.addView(buildResultCard(groupName, item))
                }
            }
        }
    }

    private fun matches(item: InvoiceItem, q: String): Boolean =
        (item.storeName?.lowercase()?.contains(q) == true) ||
            (item.date?.contains(q) == true) ||
            (item.total?.contains(q) == true) ||
            (item.currency?.lowercase()?.contains(q) == true) ||
            (item.text?.lowercase()?.contains(q) == true) ||
            (item.itemsText?.lowercase()?.contains(q) == true)

    private fun matchesFilters(item: InvoiceItem, groupName: String): Boolean {
        if (filterDate.isNotBlank() && (item.date?.contains(filterDate) != true)) return false
        if (filterStore.isNotBlank() && (item.storeName?.lowercase()?.contains(filterStore.lowercase()) != true)) return false
        if (filterAmount.isNotBlank() && (item.total?.contains(filterAmount) != true)) return false
        if (filterGroup.isNotBlank() && !groupName.lowercase().contains(filterGroup.lowercase())) return false
        if (filterSender.isNotBlank() && (item.sender?.lowercase()?.contains(filterSender.lowercase()) != true)) return false
        if (filterType.isNotBlank() && item.type != filterType) return false
        return true
    }

    private fun hasActiveFilters(): Boolean = listOf(
        filterDate, filterStore, filterAmount, filterGroup, filterSender, filterType
    ).any { it.isNotBlank() }

    private fun saveSearchContext() {
        AppRepository.setLastSavedSearch("global_filter_date", filterDate)
        AppRepository.setLastSavedSearch("global_filter_store", filterStore)
        AppRepository.setLastSavedSearch("global_filter_amount", filterAmount)
        AppRepository.setLastSavedSearch("global_filter_group", filterGroup)
        AppRepository.setLastSavedSearch("global_filter_sender", filterSender)
        AppRepository.setLastSavedSearch("global_filter_type", filterType)
    }

    private fun showSuggestions(q: String) {
        suggestionsPanel.removeAllViews()
        // جمع الكلمات المتكررة
        val wordCount = mutableMapOf<String, Int>()
        for (g in AppRepository.groups()) {
            for (item in AppRepository.items(g.id)) {
                val texts = listOfNotNull(item.storeName, item.text, item.itemsText, item.currency)
                for (t in texts) {
                    t.split(" ", "·", "/", "-").forEach { w ->
                        val w2 = w.trim()
                        if (w2.length >= 2) wordCount[w2] = (wordCount[w2] ?: 0) + 1
                    }
                }
            }
        }
        val repeated = wordCount.filter { it.key.lowercase().contains(q) && it.value > 1 }
            .toList().sortedByDescending { it.second }.take(6)
        val ctx = requireContext()
        repeated.forEach { (word, count) ->
            val chip = MaterialButton(ctx).apply {
                text = "$word ($count)"
                textSize = 12f
                setPadding(24, 0, 24, 0)
                setTextColor(ThemeHelper.text(ctx))
            }
            chip.setOnClickListener { searchInput.setText(word) }
            suggestionsPanel.addView(chip)
        }
    }

    private fun buildResultCard(groupName: String, item: InvoiceItem): View {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
            radius = 20f
            cardElevation = 2f
            strokeColor = ThemeHelper.cardStroke(ctx)
            strokeWidth = 1
            setCardBackgroundColor(ThemeHelper.surface(ctx))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(18, 16, 18, 16)
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(56, 56)
            setImageResource(if (item.type == "text") R.drawable.ic_text else R.drawable.ic_invoice)
            if (item.type == "text") setColorFilter(ThemeHelper.accent(ctx))
        }
        if (item.type == "image") {
            val path = item.processedPath ?: item.imagePath
            if (path != null) {
                try {
                    icon.setImageBitmap(com.masahhisabat.app.image.ImageProcessor.loadBitmap(path, 300))
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                } catch (e: Exception) {}
            }
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
        }
        val name = TextView(ctx).apply {
            text = item.storeName ?: item.text ?: "—"
            textSize = 16f
            setTextColor(ThemeHelper.text(ctx))
            typeface = ctx.resources.getFont(R.font.tajawal_bold)
            includeFontPadding = false
            isSingleLine = true
        }
        val details = TextView(ctx).apply {
            text = listOfNotNull(item.date, item.total?.let { "$it ${item.currency ?: ""}".trim() })
                .joinToString(" · ")
                .ifBlank { groupName }
            textSize = 13f
            setTextColor(ThemeHelper.textSecondary(ctx))
            typeface = ctx.resources.getFont(R.font.tajawal_regular)
            includeFontPadding = false
        }
        info.addView(name)
        info.addView(details)
        row.addView(icon)
        row.addView(info)
        card.addView(row)
        card.setOnClickListener {
            startActivity(Intent(ctx, GroupActivity::class.java).putExtra("group_id",
                AppRepository.groups().find { g -> AppRepository.items(g.id).any { it.id == item.id } }?.id))
        }
        return card
    }

    private fun showFilterDialog() {
        val ctx = requireContext()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(38, 12, 38, 0)
        }
        fun field(hint: String, value: String): EditText = EditText(ctx).apply {
            this.hint = hint; setText(value); textSize = 15f
            setTextColor(ThemeHelper.text(ctx)); setHintTextColor(ThemeHelper.textSecondary(ctx))
        }
        val date = field("التاريخ (مثل 2026-08)", filterDate)
        val store = field("المتجر أو العنوان", filterStore)
        val amount = field("المبلغ", filterAmount)
        val group = field("اسم المجموعة", filterGroup)
        val sender = field("اسم المرسل", filterSender)
        val type = field("النوع: صورة أو نص", when (filterType) { "image" -> "صورة"; "text" -> "نص"; else -> "" })
        listOf(date, store, amount, group, sender, type).forEach(panel::addView)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("فلاتر البحث المتقدمة")
            .setView(panel)
            .setPositiveButton("تطبيق") { _, _ ->
                filterDate = date.text.toString().trim()
                filterStore = store.text.toString().trim()
                filterAmount = amount.text.toString().trim()
                filterGroup = group.text.toString().trim()
                filterSender = sender.text.toString().trim()
                filterType = when (type.text.toString().trim()) {
                    "صورة", "صور", "image" -> "image"
                    "نص", "نصوص", "text" -> "text"
                    else -> ""
                }
                saveSearchContext()
                performSearch()
            }
            .setNeutralButton("مسح الفلاتر") { _, _ ->
                filterDate = ""; filterStore = ""; filterAmount = ""
                filterGroup = ""; filterSender = ""; filterType = ""
                saveSearchContext()
                if (query.isNotBlank()) performSearch()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
