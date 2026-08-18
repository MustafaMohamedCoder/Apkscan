package com.masahhisabat.app.ui.groups

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.masahhisabat.app.ui.invoice.GroupActivity

class GroupsFragment : Fragment() {

    private lateinit var recycler: RecyclerView

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

            val role = SessionStore.currentRole(requireContext())
            val canManage = AppRepository.canEdit(role)

            view.findViewById<View>(R.id.btn_add_group).setOnClickListener {
                if (!canManage) {
                    Toast.makeText(requireContext(), "لا تملك صلاحية لإنشاء المجموعات", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showNewGroupDialog()
            }
            view.findViewById<View>(R.id.btn_sort_groups).setOnClickListener { showSortGroupsDialog() }
            view.findViewById<TextView>(R.id.groups_empty).setOnClickListener {
                if (canManage) showNewGroupDialog()
                else Toast.makeText(requireContext(), "لا تملك صلاحية إنشاء المجموعات", Toast.LENGTH_SHORT).show()
            }

            if (!canManage) view.findViewById<View>(R.id.btn_add_group).visibility = View.GONE

            refresh()
        } catch (e: Exception) {
            logAndToast(e, "المجموعات")
        }
    }

    private fun applyTheme(view: View) {
        val bg = ThemeHelper.bg(requireContext())
        val surface = ThemeHelper.surface(requireContext())
        val text = ThemeHelper.text(requireContext())
        view.setBackgroundColor(bg)
        view.findViewById<View>(R.id.groups_root).setBackgroundColor(bg)
        view.findViewById<TextView>(R.id.title).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = resources.getFont(R.font.tajawal_bold)
            setTextColor(text)
        }
        view.findViewById<TextView>(R.id.subtitle)?.apply {
            typeface = resources.getFont(R.font.tajawal_medium)
            setTextColor(ThemeHelper.textSecondary(requireContext()))
        }
        // أيقونة زر الإضافة سوداء داخل دائرة خضراء — لا نلوّنها ديناميكيًا
    }

    override fun onResume() {
        super.onResume()
        if (::recycler.isInitialized) refresh()
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
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("مجموعة جديدة")
            .setMessage("اكتب اسمًا واضحًا للمجموعة قبل إنشائها.")
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
                AppRepository.addGroup(Group(name = name))
                val user = SessionStore.currentUser(ctx) ?: "?"
                AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_create_group, user, name)))
                dialog.dismiss()
                refresh()
                Toast.makeText(ctx, "تم إنشاء المجموعة: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                creating = false
                inputLayout.error = "تعذر حفظ المجموعة، حاول مرة أخرى"
                logAndToast(e, "حفظ اسم المجموعة")
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
            .setNegativeButton(R.string.cancel) { _, _ ->
                // إذا أُلغي التسمية ولم يبق اسم للمجموعة الجديدة: حذفها
                val group = AppRepository.groups().find { it.id == g.id }
                if (group != null && group.name.isBlank()) AppRepository.removeGroup(g.id)
                refresh()
            }
            .setOnDismissListener { refresh() }
            .show()
    }

private fun refresh() {
        try {
            val groups = sortedGroups(AppRepository.groups())
            val empty = requireView().findViewById<TextView>(R.id.groups_empty)
            if (groups.isEmpty()) {
                empty?.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } else {
                empty?.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.adapter = GroupsAdapter(groups)
            }
        } catch (e: Exception) {
            logAndToast(e, "قراءة المجموعات")
        }
    }

    /** تثبت المفضلة أولًا ثم تطبق أسلوب الترتيب الذي اختاره المستخدم. */
    private fun sortedGroups(groups: List<Group>): List<Group> {
        val pinned = AppRepository.favoriteGroupIds()
        val mode = AppRepository.groupSortMode()
        val lastActivity = if (mode == "recent") groups.associateWith { group ->
            AppRepository.items(group.id).maxOfOrNull { it.createdAt } ?: group.createdAt
        } else emptyMap()
        val order = when (mode) {
            "name" -> compareBy<Group> { it.name.trim() }
            "created" -> compareByDescending<Group> { it.createdAt }
            else -> compareByDescending<Group> { lastActivity[it] ?: it.createdAt }
        }
        return groups.sortedWith(compareByDescending<Group> { it.id in pinned }.then(order))
    }

    private fun showSortGroupsDialog() {
        val modes = arrayOf("آخر نشاط", "الاسم", "تاريخ الإنشاء")
        val values = arrayOf("recent", "name", "created")
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

    inner class GroupsAdapter(private val groups: List<Group>) :
        RecyclerView.Adapter<GroupsAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.group_card)
            val name: TextView = view.findViewById(R.id.group_name)
            val date: TextView = view.findViewById(R.id.group_date)
            val icon: ImageView = view.findViewById(R.id.group_icon)
            val pin: ImageView = view.findViewById(R.id.group_pin)
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
            val g = groups[position]
            val ctx = requireContext()
            val surface = ThemeHelper.surface(ctx)
            val text = ThemeHelper.text(ctx)
            val textSec = ThemeHelper.textSecondary(ctx)
            holder.card.setCardBackgroundColor(surface)
            holder.card.strokeColor = ThemeHelper.cardStroke(ctx)
            holder.card.strokeWidth = 1
            holder.name.text = g.name
            holder.name.setTextColor(text)
            holder.name.typeface = ctx.resources.getFont(R.font.tajawal_bold)
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            holder.date.text = fmt.format(java.util.Date(g.createdAt))
            holder.date.setTextColor(textSec)
            holder.date.typeface = ctx.resources.getFont(R.font.tajawal_medium)
            holder.icon.setColorFilter(android.graphics.Color.WHITE)
            holder.more.setColorFilter(textSec)
            holder.pin.visibility = if (g.id in AppRepository.favoriteGroupIds()) View.VISIBLE else View.GONE
            holder.pin.setColorFilter(ThemeHelper.accent(ctx))

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
                val actions = arrayOf(if (isPinned) "إلغاء التثبيت" else "تثبيت أعلى القائمة", "إعادة تسمية", "حذف")
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
                            1 -> renameGroupDialog(g)
                            2 -> confirmDelete(g)
                        }
                    }
                    .show()
            }
        }

        override fun getItemCount() = groups.size

        private fun confirmDelete(g: Group) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_delete)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val removed = AppRepository.removeGroupForUndo(g.id) ?: return@setPositiveButton
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_delete)))
                    refresh()
                    var restored = false
                    Snackbar.make(requireView(), "تم حذف مجموعة ${g.name}", Snackbar.LENGTH_LONG)
                        .setAction("تراجع") {
                            restored = true
                            AppRepository.restoreGroup(removed)
                            AppRepository.logActivity(ActivityEntry(user, "تراجع عن حذف المجموعة ${g.name}"))
                            refresh()
                            Toast.makeText(requireContext(), "تمت استعادة المجموعة", Toast.LENGTH_SHORT).show()
                        }
                        .addCallback(object : Snackbar.Callback() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                if (!restored) AppRepository.finalizeRemovedGroup(removed.id)
                            }
                        })
                        .show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
