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

    private fun showNewGroupDialog() {
        val ctx = requireContext()
        // إنشاء المجموعة فورًا باسم مؤقت، ثم فتح حوار التسمية مباشرة
        val g = Group(name = "")
        AppRepository.addGroup(g)
        val user = SessionStore.currentUser(ctx) ?: "?"
        AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_create_group, user, "مجموعة جديدة")))
        refresh()

        // فتح حوار إعادة تسمية فورًا لتسمية المجموعة الجديدة
        renameGroupDialog(g)
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
            val groups = AppRepository.groups()
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
                MaterialAlertDialogBuilder(ctx)
                    .setItems(arrayOf("إعادة تسمية", "حذف")) { _, which ->
                        when (which) {
                            0 -> renameGroupDialog(g)
                            1 -> confirmDelete(g)
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
                    AppRepository.removeGroup(g.id)
                    val user = SessionStore.currentUser(requireContext()) ?: "?"
                    AppRepository.logActivity(ActivityEntry(user, getString(R.string.log_delete)))
                    Toast.makeText(requireContext(), R.string.success, Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
