package com.masahhisabat.app.ui.groups

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.ActivityEntry
import com.masahhisabat.app.data.Group
import com.masahhisabat.app.data.generateId
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.common.LocalContentRefreshState
import com.masahhisabat.app.ui.invoice.GroupActivity

class GroupsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private var groupQuery = ""
    private var allGroups: List<Group> = emptyList()
    private lateinit var tradersAdapter: GroupsAdapter
    private lateinit var contentRefresh: SwipeRefreshLayout
    private val refreshState = LocalContentRefreshState()

    private data class GroupSnapshot(
        val documentCount: Int,
        val lastActivity: Long,
        val lastPreview: String,
        val openOrders: Int,
        val unpaidInvoices: Int
    )

    private data class GroupRow(
        val group: Group,
        val snapshot: GroupSnapshot,
        val isPinned: Boolean
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_groups, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            applyTheme(view)

            recycler = view.findViewById(R.id.groups_list)
            // البطاقات تظهر بعمودين على التابلت لتستفيد من العرض المتاح، وبعمود واحد على الهاتف.
            val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 2 else 1
            recycler.layoutManager = if (columns == 1) LinearLayoutManager(requireContext())
            else GridLayoutManager(requireContext(), columns)
            recycler.setHasFixedSize(true)
            tradersAdapter = GroupsAdapter()
            recycler.adapter = tradersAdapter
            contentRefresh = view.findViewById(R.id.groups_refresh)
            contentRefresh.setOnChildScrollUpCallback { _, _ -> recycler.canScrollVertically(-1) }
            contentRefresh.setOnRefreshListener { refreshFromSwipeGesture() }

            val search = view.findViewById<EditText>(R.id.et_group_search)
            val clearSearch = view.findViewById<ImageView>(R.id.btn_clear_group_search)
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    groupQuery = s?.toString().orEmpty()
                    clearSearch.visibility = if (groupQuery.isBlank()) View.GONE else View.VISIBLE
                    refresh()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            clearSearch.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                search.text?.clear()
            }

            val role = SessionStore.currentRole(requireContext())
            val canManage = AppRepository.canEdit(role)

            view.findViewById<View>(R.id.btn_add_group).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (!canManage) {
                    Toast.makeText(requireContext(), R.string.groups_create_permission_denied, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showNewGroupDialog()
            }
            view.findViewById<View>(R.id.btn_sort_groups).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showSortGroupsDialog()
            }
            view.findViewById<View>(R.id.btn_filter_groups).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                showFilterGroupsDialog()
            }
            view.findViewById<TextView>(R.id.groups_empty).setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (canManage) showNewGroupDialog()
                else Toast.makeText(requireContext(), R.string.groups_create_permission_denied, Toast.LENGTH_SHORT).show()
            }

            if (!canManage) view.findViewById<View>(R.id.btn_add_group).visibility = View.GONE

            refresh()
        } catch (e: Exception) {
            logAndToast(e, "المجموعات")
        }
    }

    private fun applyTheme(view: View) {
        val text = ThemeHelper.text(requireContext())
        view.setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<View>(R.id.groups_root).setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<TextView>(R.id.title).apply {
            this.text = getString(R.string.groups_title)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = resources.getFont(R.font.tajawal_bold)
            setTextColor(text)
        }
        view.findViewById<TextView>(R.id.subtitle)?.apply {
            this.text = getString(R.string.groups_subtitle)
            typeface = resources.getFont(R.font.tajawal_medium)
            setTextColor(ThemeHelper.textSecondary(requireContext()))
        }
        view.findViewById<TextView>(R.id.groups_empty)?.apply {
            setTextColor(ThemeHelper.textSecondary(requireContext()))
            setBackgroundResource(ThemeHelper.glassPanelRes())
            backgroundTintList = null
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_invoice, 0, 0)
            compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
            TextViewCompat.setCompoundDrawableTintList(
                this,
                android.content.res.ColorStateList.valueOf(ThemeHelper.accent(requireContext()))
            )
        }
        view.findViewById<TextView>(R.id.groups_summary)?.setTextColor(ThemeHelper.textSecondary(requireContext()))
        view.findViewById<TextView>(R.id.groups_sort_label)?.setTextColor(ThemeHelper.textSecondary(requireContext()))
        view.findViewById<EditText>(R.id.et_group_search)?.apply {
            setTextColor(ThemeHelper.inputText(requireContext()))
            setHintTextColor(ThemeHelper.inputHint(requireContext()))
            TextViewCompat.setCompoundDrawableTintList(
                this,
                android.content.res.ColorStateList.valueOf(ThemeHelper.textSecondary(requireContext()))
            )
        }
        view.findViewById<ImageView>(R.id.btn_clear_group_search)?.setColorFilter(ThemeHelper.textSecondary(requireContext()))
        view.findViewById<SwipeRefreshLayout>(R.id.groups_refresh)?.setColorSchemeColors(
            ThemeHelper.accent(requireContext())
        )
        view.findViewById<android.widget.ImageButton>(R.id.btn_filter_groups)?.setColorFilter(
            ThemeHelper.textSecondary(requireContext())
        )
        view.findViewById<android.widget.ImageButton>(R.id.btn_sort_groups)?.setColorFilter(
            ThemeHelper.textSecondary(requireContext())
        )
        // أيقونة زر الإضافة سوداء داخل دائرة خضراء — لا نلوّنها ديناميكيًا
    }

    override fun onResume() {
        super.onResume()
        if (::recycler.isInitialized) refresh()
    }

    override fun onDestroyView() {
        refreshState.cancel()
        super.onDestroyView()
    }

    private fun showNewGroupDialog() {
        val ctx = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val inputLayout = TextInputLayout(ctx).apply {
            hint = "اسم المجموعة"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColor(ThemeHelper.accent(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val input = TextInputEditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(ThemeHelper.inputText(ctx))
            setHintTextColor(ThemeHelper.inputHint(ctx))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "حقل اسم المجموعة"
        }
        inputLayout.addView(input)
        container.addView(inputLayout)
        fun optionalField(hint: String): TextInputEditText {
            val layout = TextInputLayout(ctx).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxStrokeColor(ThemeHelper.accent(ctx))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            return TextInputEditText(ctx).apply {
                setTextColor(ThemeHelper.inputText(ctx))
                setHintTextColor(ThemeHelper.inputHint(ctx))
                layout.addView(this)
                container.addView(layout)
            }
        }
        val phone = optionalField("هاتف المورد (اختياري)")
        val notes = optionalField("ملاحظات المجموعة (اختياري)")
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("مجموعة جديدة")
            .setMessage("أنشئ مجموعة مستقلة لفواتير وطلبات المحل.")
            .setView(container)
            .setPositiveButton("✓ تأكيد", null)
            .setNegativeButton(R.string.cancel, null)
            .setCancelable(false)
            .create()

        var creating = false
        fun createGroup() {
            if (creating) return
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                inputLayout.error = "اكتب اسم المجموعة أولًا"
                input.requestFocus()
                return
            }
            creating = true
            try {
                AppRepository.addGroup(Group(
                    name = name,
                    supplierPhone = phone.text?.toString(),
                    supplierNotes = notes.text?.toString()
                ))
                val user = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_create_group, user, name)))
                dialog.dismiss()
                refresh()
                Toast.makeText(ctx, "تمت إضافة المجموعة: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                creating = false
                inputLayout.error = "تعذر حفظ بيانات المجموعة، حاول مرة أخرى"
                logAndToast(e, "حفظ بيانات المجموعة")
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                createGroup()
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    createGroup()
                    true
                } else false
            }
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.postDelayed({
                input.requestFocus()
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 180)
        }
        dialog.show()
    }

    private fun showTraderDetailsDialog(g: Group) {
        val ctx = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        fun field(hint: String, value: String?, singleLine: Boolean = true): TextInputEditText {
            val layout = TextInputLayout(ctx).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxStrokeColor(ThemeHelper.accent(ctx))
            }
            return TextInputEditText(ctx).apply {
                setText(value.orEmpty())
                setSingleLine(singleLine)
                setTextColor(ThemeHelper.inputText(ctx))
                setHintTextColor(ThemeHelper.inputHint(ctx))
                layout.addView(this)
                container.addView(layout)
            }
        }
        val name = field("اسم الفاتورة", g.name)
        val phone = field("هاتف المورد", g.supplierPhone)
        val notes = field("ملاحظات", g.supplierNotes, false)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("بيانات الفاتورة")
            .setMessage("تُحفظ هذه المعلومات محليًا ضمن أرشيف الفاتورة.")
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                try {
                    AppRepository.updateTraderDetails(
                        g.id,
                        name.text?.toString().orEmpty(),
                        phone.text?.toString(),
                        notes.text?.toString()
                    )
                    refresh()
                    Toast.makeText(ctx, "تم حفظ بيانات الفاتورة", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(ctx, error.message ?: "تعذر حفظ بيانات الفاتورة", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameGroupDialog(g: Group) {
        val ctx = requireContext()
        val input = EditText(ctx).apply { setText(g.name) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    AppRepository.renameGroup(g.id, name)
                    refresh()
                }
            }
            // الإلغاء لا يحذف المجموعة؛ جميع عمليات الحذف الصريحة تمر عبر سلة المحذوفات.
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { refresh() }
            .show()
    }

    private fun refresh() {
        try {
            allGroups = AppRepository.groups()
            val snapshots = allGroups.associate { group ->
                val items = AppRepository.items(group.id)
                val latest = items.maxByOrNull { it.createdAt }
                group.id to GroupSnapshot(
                    documentCount = items.size,
                    lastActivity = latest?.createdAt ?: group.createdAt,
                    lastPreview = messagePreview(latest),
                    openOrders = items.count { it.status == "new" || it.status == "in_review" },
                    unpaidInvoices = items.count { it.status != "paid" && !it.total.isNullOrBlank() }
                )
            }
            val totalDocuments = snapshots.values.sumOf { it.documentCount }
            val pinned = AppRepository.favoriteGroupIds()
            val filterMode = AppRepository.groupFilterMode()
            val sortMode = AppRepository.groupSortMode()
            val summary = requireView().findViewById<TextView>(R.id.groups_summary)
            val archivedCount = allGroups.count { it.archivedAt != null }
            summary.text = "${allGroups.size - archivedCount} فاتورة نشطة · $archivedCount مؤرشفة · $totalDocuments عنصرًا"
            requireView().findViewById<TextView>(R.id.groups_sort_label).text =
                "${sortLabel(sortMode)} · ${filterLabel(filterMode)}"

            val query = groupQuery.trim()
            val groups = sortedGroups(allGroups, snapshots, pinned).filter { group ->
                (query.isBlank() || group.name.contains(query, ignoreCase = true)) &&
                    matchesFilter(group, snapshots[group.id], pinned, filterMode)
            }
            val empty = requireView().findViewById<TextView>(R.id.groups_empty)
            if (groups.isEmpty()) {
                val emptyState = GroupEmptyStatePolicy.resolve(
                    hasAnyGroups = allGroups.isNotEmpty(),
                    query = query,
                    filterMode = filterMode,
                    canCreate = AppRepository.canEdit(SessionStore.currentRole(requireContext()))
                )
                empty?.text = emptyStateText(emptyState.message, query)
                empty?.isEnabled = emptyState.canCreate
                empty?.isClickable = emptyState.canCreate
                empty?.isFocusable = emptyState.canCreate
                empty?.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                empty?.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                tradersAdapter.submit(groups, snapshots, pinned)
            }
        } catch (e: Exception) {
            logAndToast(e, "قراءة المجموعات")
        }
    }

    /** يعيد قراءة البيانات المحلية وينهي مؤشر السحب في النجاح أو عند معالجة الخطأ. */
    private fun refreshFromSwipeGesture() {
        if (!refreshState.tryStart()) {
            contentRefresh.isRefreshing = false
            return
        }
        try {
            refresh()
        } finally {
            refreshState.finish()
            contentRefresh.isRefreshing = false
        }
    }

    /** تثبت المفضلة أولًا ثم تطبق أسلوب الترتيب الذي اختاره المستخدم. */
    private fun sortedGroups(
        groups: List<Group>,
        snapshots: Map<String, GroupSnapshot>,
        pinned: Set<String>
    ): List<Group> {
        val order = when (AppRepository.groupSortMode()) {
            "name" -> compareBy<Group> { it.name.trim().lowercase(java.util.Locale.getDefault()) }
            "created" -> compareByDescending<Group> { it.createdAt }
            "created_oldest" -> compareBy<Group> { it.createdAt }
            "size_desc" -> compareByDescending<Group> { snapshots[it.id]?.documentCount ?: 0 }
                .thenByDescending { snapshots[it.id]?.lastActivity ?: it.createdAt }
            "size_asc" -> compareBy<Group> { snapshots[it.id]?.documentCount ?: 0 }
                .thenByDescending { snapshots[it.id]?.lastActivity ?: it.createdAt }
            else -> compareByDescending<Group> { snapshots[it.id]?.lastActivity ?: it.createdAt }
        }
        return groups.sortedWith(compareByDescending<Group> { it.id in pinned }.then(order))
    }

    private fun matchesFilter(
        group: Group,
        snapshot: GroupSnapshot?,
        pinned: Set<String>,
        filterMode: String
    ): Boolean {
        if (filterMode == "archived") return group.archivedAt != null
        if (group.archivedAt != null) return false
        return when (filterMode) {
            "with_documents" -> (snapshot?.documentCount ?: 0) > 0
            "empty" -> (snapshot?.documentCount ?: 0) == 0
            "pinned" -> group.id in pinned
            "recent_30d" -> group.createdAt >= System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
            else -> true
        }
    }

    private fun messagePreview(item: com.masahhisabat.app.data.InvoiceItem?): String {
        if (item == null) return "لا توجد رسائل بعد"
        val text = item.text?.trim().orEmpty()
        return when {
            item.type == "image" && text.isNotBlank() -> "📷 $text"
            item.type == "image" -> "📷 صورة"
            text.isNotBlank() -> text.replace('\n', ' ').take(60)
            else -> "رسالة جديدة"
        }
    }

    private fun activityTime(time: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return when {
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) ->
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR) == 1 -> "أمس"
            else -> java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()).format(java.util.Date(time))
        }
    }

    private fun showSortGroupsDialog() {
        val modes = arrayOf(
            "آخر نشاط",
            "الاسم (أ-ي)",
            "تاريخ الإنشاء (الأحدث)",
            "تاريخ الإنشاء (الأقدم)",
            "الحجم (الأكبر أولًا)",
            "الحجم (الأصغر أولًا)"
        )
        val values = arrayOf("recent", "name", "created", "created_oldest", "size_desc", "size_asc")
        val selected = values.indexOf(AppRepository.groupSortMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("ترتيب المجموعات")
            .setSingleChoiceItems(modes, selected) { dialog, which ->
                AppRepository.setGroupSortMode(values[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFilterGroupsDialog() {
        val modes = arrayOf(
            "كل المجموعات",
            "تحتوي على مستندات",
            "فارغة",
            "المجموعات المثبتة",
            "أُنشئت خلال آخر 30 يومًا",
            "المجموعات المؤرشفة"
        )
        val values = arrayOf("all", "with_documents", "empty", "pinned", "recent_30d", "archived")
        val selected = values.indexOf(AppRepository.groupFilterMode()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("تصفية المجموعات")
            .setSingleChoiceItems(modes, selected) { dialog, which ->
                AppRepository.setGroupFilterMode(values[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sortLabel(mode: String): String = when (mode) {
        "name" -> "الاسم"
        "created" -> "الأحدث"
        "created_oldest" -> "الأقدم"
        "size_desc" -> "الحجم الأكبر"
        "size_asc" -> "الحجم الأصغر"
        else -> "آخر نشاط"
    }

    private fun filterLabel(mode: String): String = when (mode) {
        "with_documents" -> "بها مستندات"
        "empty" -> "فارغة"
        "pinned" -> "مثبتة"
        "recent_30d" -> "آخر 30 يومًا"
        "archived" -> "مؤرشفة"
        else -> "الكل"
    }

    private fun emptyStateText(message: GroupEmptyMessage, query: String): String = when (message) {
        GroupEmptyMessage.NO_GROUPS_WITH_CREATE_ACTION -> getString(R.string.groups_empty_action)
        GroupEmptyMessage.NO_GROUPS_READ_ONLY -> getString(R.string.groups_empty_read_only)
        GroupEmptyMessage.NO_SEARCH_AND_FILTER_RESULTS -> getString(R.string.groups_empty_search_and_filter)
        GroupEmptyMessage.NO_SEARCH_RESULTS -> getString(R.string.groups_empty_search_result, query)
        GroupEmptyMessage.NO_FILTER_RESULTS -> getString(R.string.groups_empty_filter_result)
    }

    private fun logAndToast(e: Exception, tag: String) {
        try {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val file = java.io.File(requireContext().filesDir, "crash_log.txt")
            file.writeText("GROUPS_FAIL $tag ${System.currentTimeMillis()}: ${e.message}\n$sw\n---\n" +
                (if (file.exists()) file.readText().take(50_000) else ""))
        } catch (_: Exception) {}
        Toast.makeText(requireContext(), "خطأ في $tag: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }

    private inner class GroupsAdapter :
        RecyclerView.Adapter<GroupsAdapter.VH>() {
        private var rows: List<GroupRow> = emptyList()

        fun submit(
            newGroups: List<Group>,
            newSnapshots: Map<String, GroupSnapshot>,
            pinned: Set<String>
        ) {
            val newRows = newGroups.map { group ->
                GroupRow(
                    group = group,
                    snapshot = newSnapshots[group.id] ?: GroupSnapshot(
                        documentCount = 0,
                        lastActivity = group.createdAt,
                        lastPreview = "لا توجد مستندات بعد",
                        openOrders = 0,
                        unpaidInvoices = 0
                    ),
                    isPinned = group.id in pinned
                )
            }
            val previousRows = rows
            if (previousRows == newRows) return
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previousRows.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previousRows[oldItemPosition].group.id == newRows[newItemPosition].group.id

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previousRows[oldItemPosition] == newRows[newItemPosition]
            })
            rows = newRows
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.group_card)
            val name: TextView = view.findViewById(R.id.group_name)
            val preview: TextView = view.findViewById(R.id.group_preview)
            val date: TextView = view.findViewById(R.id.group_date)
            val icon: ImageView = view.findViewById(R.id.group_icon)
            val pin: ImageView = view.findViewById(R.id.group_pin)
            val itemCount: TextView = view.findViewById(R.id.group_items_count)
            val more: ImageView = view.findViewById(R.id.group_more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
            // حركة دخول ناعمة للعناصر
            view.alpha = 0f
            view.translationY = 30f
            return VH(view)
        }

        override fun onViewAttachedToWindow(holder: VH) {
            super.onViewAttachedToWindow(holder)
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val g = row.group
            val ctx = requireContext()
            val surface = ThemeHelper.surface(ctx)
            val text = ThemeHelper.text(ctx)
            val textSec = ThemeHelper.textSecondary(ctx)
            holder.card.setCardBackgroundColor(surface)
            holder.card.strokeColor = ThemeHelper.cardStroke(ctx)
            holder.card.strokeWidth = 1
            holder.name.text = if (g.archivedAt != null) "${g.name} · مؤرشف" else g.name
            holder.name.setTextColor(text)
            holder.name.typeface = ctx.resources.getFont(R.font.tajawal_bold)
            val snapshot = row.snapshot
            holder.preview.text = buildString {
                append("${snapshot.documentCount} فاتورة/طلب")
                if (snapshot.openOrders > 0) append(" · ${snapshot.openOrders} قيد المتابعة")
                if (snapshot.unpaidInvoices > 0) append(" · ${snapshot.unpaidInvoices} غير مسددة")
                if (!g.supplierPhone.isNullOrBlank()) append(" · ${g.supplierPhone}")
            }
            holder.preview.setTextColor(textSec)
            holder.preview.typeface = ctx.resources.getFont(R.font.tajawal_regular)
            holder.date.text = activityTime(snapshot.lastActivity)
            holder.date.setTextColor(textSec)
            holder.date.typeface = ctx.resources.getFont(R.font.tajawal_medium)
            val documents = snapshot.documentCount
            holder.itemCount.text = documents.toString()
            holder.itemCount.setTextColor(ThemeHelper.chipTextColor(ctx))
            holder.itemCount.background?.setTint(ThemeHelper.chipBgColor(ctx))
            holder.icon.setColorFilter(android.graphics.Color.WHITE)
            holder.more.setColorFilter(textSec)
            holder.pin.visibility = if (row.isPinned) View.VISIBLE else View.GONE
            holder.pin.setColorFilter(ThemeHelper.accent(ctx))
            holder.itemView.contentDescription = "فتح أرشيف الفاتورة ${g.name}"
            holder.more.contentDescription = "إجراءات الفاتورة ${g.name}"

            // النقر على البطاقة (أو الاسم) يفتح المجموعة
            holder.itemView.setOnClickListener {
                startActivity(Intent(ctx, GroupActivity::class.java).putExtra("group_id", g.id))
            }

            holder.more.setOnClickListener {
                val role = SessionStore.currentRole(ctx)
                if (!AppRepository.canEdit(role)) {
                    Toast.makeText(ctx, "لا تملك صلاحية", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isPinned = g.id in AppRepository.favoriteGroupIds()
                val archiveAction = if (g.archivedAt == null) "أرشفة الفاتورة" else "إلغاء الأرشفة"
                val actions = arrayOf(if (isPinned) "إلغاء التثبيت" else "تثبيت أعلى القائمة", archiveAction, "تعديل بيانات الفاتورة", "حذف")
                MaterialAlertDialogBuilder(ctx)
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> {
                                val pinned = AppRepository.favoriteGroupIds().toMutableSet()
                                if (isPinned) pinned.remove(g.id) else pinned.add(g.id)
                                AppRepository.setFavoriteGroupIds(pinned)
                                refresh()
                                Toast.makeText(ctx, if (isPinned) "تم إلغاء تثبيت المجموعة" else "تم تثبيت المجموعة", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                val archive = g.archivedAt == null
                                AppRepository.setGroupArchived(g.id, archive)
                                val user = SessionStore.currentUser(ctx) ?: "?"
                                AppRepository.logActivity(ActivityEntry(user, if (archive) "أرشف $user المجموعة ${g.name}" else "ألغى $user أرشفة المجموعة ${g.name}"))
                                refresh()
                                Toast.makeText(ctx, if (archive) "تمت أرشفة المجموعة" else "أعيدت المجموعة إلى القائمة", Toast.LENGTH_SHORT).show()
                            }
                            2 -> showTraderDetailsDialog(g)
                            3 -> confirmDelete(g)
                        }
                    }
                    .show()
            }
        }

        override fun getItemCount() = rows.size

        private fun confirmDelete(g: Group) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_delete)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    val removed = AppRepository.moveGroupToTrash(g.id, user) ?: return@setPositiveButton
                    AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_delete)))
                    refresh()
                    Snackbar.make(requireView(), "نُقلت مجموعة ${g.name} إلى سلة المحذوفات", Snackbar.LENGTH_LONG)
                        .setAction("تراجع") {
                            if (AppRepository.restoreTrashEntry(removed.id)) {
                                AppRepository.logActivity(ActivityEntry(user, "تراجع عن حذف المجموعة ${g.name}"))
                                refresh()
                                Toast.makeText(requireContext(), "تمت استعادة المجموعة", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
