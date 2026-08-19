package com.masahhisabat.app.ui.home

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.Group
import com.masahhisabat.app.data.InvoiceItem
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.GroupActivity
import java.util.Calendar

/**
 * الصفحة الرئيسية المختصرة: إجراء مسح واضح، اختصارات أساسية، وآخر مجموعة فقط.
 * تبقى التفاصيل المتقدمة في الأقسام المتخصصة حتى لا تصبح البداية مزدحمة.
 */
class HomeFragment : Fragment() {

    private var lastRefreshAt = 0L
    private var isRefreshing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)

        val isMustafa = com.masahhisabat.app.ui.auth.SessionStore.currentUser(requireContext()) == "mustafa"
        view.findViewById<View>(R.id.recent_card).visibility = if (isMustafa) View.VISIBLE else View.GONE
        if (isMustafa) configureRecentToggle(view)

        view.findViewById<MaterialButton>(R.id.btn_start_scan).setOnClickListener { openTab(R.id.nav_scanner) }
        view.findViewById<MaterialButton>(R.id.btn_quick_group).setOnClickListener { openTab(R.id.nav_groups) }
        view.findViewById<MaterialButton>(R.id.btn_quick_search).setOnClickListener { openTab(R.id.nav_search) }

        val openGroups = View.OnClickListener { openTab(R.id.nav_groups) }
        view.findViewById<View>(R.id.card_groups).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_count).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_label).setOnClickListener(openGroups)

        view.findViewById<MaterialButton>(R.id.btn_continue_work).setOnClickListener { openLastGroup() }
        view.findViewById<MaterialButton>(R.id.btn_today_tasks).setOnClickListener { openNextTodayTask() }
        view.findViewById<MaterialButton>(R.id.btn_quick_sync).setOnClickListener {
            openTab(R.id.nav_settings)
            android.widget.Toast.makeText(requireContext(), "اختر «المزامنة المحلية» لبدء المزامنة", android.widget.Toast.LENGTH_SHORT).show()
        }

        refresh(force = true)
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refresh()
    }

    private fun openTab(id: Int) {
        (activity as? com.masahhisabat.app.ui.main.MainActivity)?.findViewById<View>(id)?.performClick()
    }

    private fun openLastGroup() {
        val group = AppRepository.lastOpenedGroupId()?.let { id -> AppRepository.groups().find { it.id == id } }
        if (group == null) {
            openTab(R.id.nav_groups)
            android.widget.Toast.makeText(requireContext(), "أنشئ أو اختر مجموعة أولًا", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            openGroup(group)
        }
    }

    private fun openGroup(group: Group) {
        startActivity(Intent(requireContext(), GroupActivity::class.java).putExtra("group_id", group.id))
    }

    private fun openNextTodayTask() {
        val group = scheduledTasks().firstOrNull()?.first
        if (group == null) {
            openTab(R.id.nav_groups)
            android.widget.Toast.makeText(requireContext(), "أضف تاريخ استحقاق إلى فاتورة لظهورها هنا", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            openGroup(group)
        }
    }

    private fun configureRecentToggle(view: View) {
        val panel = view.findViewById<View>(R.id.recent_panel)
        val toggle = view.findViewById<ImageView>(R.id.recent_toggle)
        val expanded = AppRepository.lastSavedSearch("recent_panel") == "1"
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.rotation = if (expanded) 180f else 0f
        toggle.setOnClickListener {
            val nowExpanded = panel.visibility == View.GONE
            panel.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            toggle.rotation = if (nowExpanded) 180f else 0f
            AppRepository.setLastSavedSearch("recent_panel", if (nowExpanded) "1" else "0")
        }
    }

    private fun applyTheme(view: View) {
        val context = requireContext()
        val text = ThemeHelper.text(context)
        val textSecondary = ThemeHelper.textSecondary(context)
        view.setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<View>(R.id.home_root).setBackgroundResource(ThemeHelper.backgroundRes())

        listOf(R.id.card_groups, R.id.card_invoices, R.id.quick_actions_card, R.id.continue_card, R.id.today_tasks_card, R.id.sync_card, R.id.recent_card).forEach { id ->
            view.findViewById<MaterialCardView>(id).apply {
                setCardBackgroundColor(ThemeHelper.surface(context))
                strokeColor = ThemeHelper.cardStroke(context)
            }
        }

        view.findViewById<MaterialCardView>(R.id.welcome_banner).apply {
            setCardBackgroundColor(ThemeHelper.surfaceHigh(context))
            strokeColor = ThemeHelper.cardStroke(context)
        }

        val heroTitle = if (ThemeHelper.isNight(context)) android.graphics.Color.WHITE else text
        val heroSecondary = if (ThemeHelper.isNight(context)) 0xD9E6FAFF.toInt() else textSecondary
        view.findViewById<TextView>(R.id.title).setTextColor(heroTitle)
        view.findViewById<TextView>(R.id.greeting).setTextColor(heroSecondary)
        view.findViewById<TextView>(R.id.subtitle).setTextColor(heroSecondary)
        view.findViewById<ImageView>(R.id.welcome_badge).setColorFilter(heroTitle)

        listOf(R.id.home_overview_title, R.id.quick_actions_title, R.id.continue_title, R.id.today_tasks_title, R.id.sync_title, R.id.recent_title).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        listOf(R.id.groups_label, R.id.invoices_label, R.id.continue_detail, R.id.today_tasks_summary, R.id.sync_status, R.id.recent_empty).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(textSecondary)
        }
        view.findViewById<TextView>(R.id.groups_count).setTextColor(ThemeHelper.accent(context))
        view.findViewById<TextView>(R.id.invoices_count).setTextColor(ThemeHelper.accent(context))
        view.findViewById<ImageView>(R.id.recent_toggle).apply {
            setColorFilter(textSecondary)
            background?.setTint(ThemeHelper.surfaceHigh(context))
        }
        view.findViewById<TextView>(R.id.recent_empty).background?.setTint(ThemeHelper.inputFill(context))
    }

    private fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (isRefreshing || (!force && now - lastRefreshAt < 900L)) return
        val root = view ?: return
        isRefreshing = true
        try {
            root.findViewById<TextView>(R.id.invoices_count).text = AppRepository.totalInvoiceCount().toString()
            root.findViewById<TextView>(R.id.groups_count).text = AppRepository.groups().size.toString()

            val lastGroup = AppRepository.lastOpenedGroupId()?.let { id -> AppRepository.groups().find { it.id == id } }
            root.findViewById<TextView>(R.id.continue_detail).text = when (lastGroup) {
                null -> "لم تُفتح مجموعة بعد"
                else -> "آخر مجموعة: ${lastGroup.name}"
            }
            root.findViewById<MaterialButton>(R.id.btn_continue_work).text = if (lastGroup == null) "المجموعات" else "فتح"

            val tasks = scheduledTasks()
            val startToday = startOfToday()
            val endToday = startToday + 24L * 60L * 60L * 1000L
            val overdue = tasks.count { (_, item) -> (item.reminderAt ?: Long.MAX_VALUE) < startToday }
            val today = tasks.count { (_, item) ->
                val reminderAt = item.reminderAt ?: Long.MAX_VALUE
                reminderAt in startToday until endToday
            }
            root.findViewById<TextView>(R.id.today_tasks_summary).text = when {
                overdue > 0 && today > 0 -> "$overdue متأخرة و$today مستحقة اليوم"
                overdue > 0 -> "$overdue فواتير تحتاج متابعة"
                today > 0 -> "$today فواتير مستحقة اليوم"
                else -> "لا توجد فواتير مستحقة اليوم"
            }
            root.findViewById<MaterialButton>(R.id.btn_today_tasks).text = if (tasks.isEmpty()) "إضافة" else "عرض"

            val lastSync = AppRepository.syncLog().lastOrNull()
            root.findViewById<TextView>(R.id.sync_status).text = when {
                lastSync == null -> "لا توجد مزامنة مسجلة"
                lastSync.success -> "آخر مزامنة تمت بنجاح"
                else -> "آخر مزامنة تحتاج إلى مراجعة"
            }

            populateRecent(root)
        } finally {
            lastRefreshAt = SystemClock.elapsedRealtime()
            isRefreshing = false
        }
    }

    private fun populateRecent(root: View) {
        if (root.findViewById<View>(R.id.recent_card).visibility != View.VISIBLE) return
        val recentList = root.findViewById<RecyclerView>(R.id.recent_list)
        if (recentList.layoutManager == null) recentList.layoutManager = LinearLayoutManager(requireContext())
        val entries = AppRepository.activityLog().take(5)
        root.findViewById<TextView>(R.id.recent_empty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recentList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isNotEmpty()) recentList.adapter = RecentAdapter(entries)
    }

    /** يعرض لوحة البداية الاستحقاقات المفتوحة من المجموعات غير المؤرشفة فقط. */
    private fun scheduledTasks(): List<Pair<Group, InvoiceItem>> {
        return AppRepository.groups()
            .asSequence()
            .filter { it.archivedAt == null }
            .flatMap { group ->
                AppRepository.items(group.id).asSequence()
                    .filter { item -> item.reminderAt != null && item.status != "paid" }
                    .map { item -> group to item }
            }
            .sortedBy { (_, item) -> item.reminderAt }
            .toList()
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    class RecentAdapter(private val items: List<com.masahhisabat.app.data.ActivityEntry>) :
        RecyclerView.Adapter<RecentAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val item = TextView(parent.context).apply {
                setPadding(20, 16, 20, 16)
                textSize = 13f
                setTextColor(ThemeHelper.text(parent.context))
                background = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(ThemeHelper.surfaceHigh(parent.context))
                }
            }
            item.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            return ViewHolder(item)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            (holder.itemView as TextView).text = "${formatter.format(java.util.Date(entry.at))} — ${entry.user}: ${entry.action}"
        }

        override fun getItemCount(): Int = items.size
    }
}
