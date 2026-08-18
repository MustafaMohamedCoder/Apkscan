package com.masahhisabat.app.ui.home

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.DashboardAnalytics
import com.masahhisabat.app.data.Group
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.GroupActivity
import com.masahhisabat.app.ui.invoice.InvoiceActivity

class HomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyTheme(view)
        // إخفاء «آخر العمليات» عن كل الحسابات عدا mustafa فقط
        val isMustafa = com.masahhisabat.app.ui.auth.SessionStore.currentUser(requireContext()) == "mustafa"
        view.findViewById<View>(R.id.recent_header)?.visibility = if (isMustafa) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.recent_panel)?.visibility = if (isMustafa) View.VISIBLE else View.GONE
        refresh()

        view.findViewById<MaterialButton>(R.id.btn_start_scan).setOnClickListener {
            // الانتقال إلى تبويب السكانر (الشريط السفلي مخصص - LinearLayout)
            (activity as? com.masahhisabat.app.ui.main.MainActivity)?.let {
                it.findViewById<View>(R.id.nav_scanner)?.performClick()
            }
        }

        // كلمة/بطاقة «المجموعات»: الضغط عليها يفتح قسم المجموعات مباشرة
        val openGroups = View.OnClickListener {
            (activity as? com.masahhisabat.app.ui.main.MainActivity)?.let {
                it.findViewById<View>(R.id.nav_groups)?.performClick()
            }
        }
        view.findViewById<View>(R.id.card_groups).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_label).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_count).setOnClickListener(openGroups)

        // الاختصار يبقي إعدادات الشبكة في مكان واحد، ثم يفتح صفحة المزامنة مباشرة للمستخدم.
        view.findViewById<MaterialButton>(R.id.btn_quick_sync).setOnClickListener {
            (activity as? com.masahhisabat.app.ui.main.MainActivity)?.let {
                it.findViewById<View>(R.id.nav_settings)?.performClick()
            }
            android.widget.Toast.makeText(requireContext(), "اختر «المزامنة المحلية» لبدء المزامنة", android.widget.Toast.LENGTH_SHORT).show()
        }
        view.findViewById<MaterialButton>(R.id.btn_quick_scan).setOnClickListener { openTab(R.id.nav_scanner) }
        view.findViewById<MaterialButton>(R.id.btn_quick_group).setOnClickListener { openTab(R.id.nav_groups) }
        view.findViewById<MaterialButton>(R.id.btn_quick_search).setOnClickListener { openTab(R.id.nav_search) }
        view.findViewById<MaterialButton>(R.id.btn_continue_work).setOnClickListener { openLastGroup() }
        view.findViewById<MaterialButton>(R.id.btn_manage_favorites).setOnClickListener { showFavoritePicker() }
        view.findViewById<MaterialButton>(R.id.btn_report_details).setOnClickListener {
            startActivity(Intent(requireContext(), AnalyticsActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btn_share_daily_report).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, DashboardAnalytics.shareText())
            }
            startActivity(Intent.createChooser(share, "مشاركة التقرير اليومي"))
        }
        view.findViewById<View>(R.id.data_health_card).setOnClickListener { openTab(R.id.nav_settings) }
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
        } else openGroup(group)
    }

    private fun openGroup(group: Group) {
        startActivity(Intent(requireContext(), GroupActivity::class.java).putExtra("group_id", group.id))
    }

    private fun showFavoritePicker() {
        val groups = AppRepository.groups()
        if (groups.isEmpty()) { openTab(R.id.nav_groups); return }
        val current = AppRepository.favoriteGroupIds()
        val labels = groups.map { it.name }.toTypedArray()
        val checked = groups.map { it.id in current }.toBooleanArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("اختيار المجموعات المفضلة")
            .setMultiChoiceItems(labels, checked) { _, which, selected -> checked[which] = selected }
            .setPositiveButton("حفظ") { _, _ ->
                AppRepository.setFavoriteGroupIds(groups.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet())
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun applyTheme(view: View) {
        val bg = ThemeHelper.bg(requireContext())
        view.setBackgroundColor(bg)
        view.findViewById<View>(R.id.home_root).setBackgroundColor(bg)
        val surface = ThemeHelper.surface(requireContext())
        val text = ThemeHelper.text(requireContext())
        val textSec = ThemeHelper.textSecondary(requireContext())

        listOf(R.id.card_invoices, R.id.card_groups, R.id.dashboard_info_card, R.id.continue_card, R.id.quick_actions_card, R.id.favorites_card, R.id.data_health_card, R.id.today_activity_card, R.id.alerts_card, R.id.daily_report_card, R.id.advanced_stats_card).forEach { id ->
            val card = view.findViewById<MaterialCardView>(id)
            card.setCardBackgroundColor(surface)
            card.strokeColor = ThemeHelper.cardStroke(requireContext())
        }
        view.findViewById<TextView>(R.id.title).setTextColor(text)
        view.findViewById<TextView>(R.id.subtitle).setTextColor(textSec)
        // شريط الترحيب
        view.findViewById<TextView>(R.id.greeting)?.setTextColor(ThemeHelper.accent(requireContext()))
        view.findViewById<ImageView>(R.id.welcome_badge)?.setColorFilter(ThemeHelper.accent(requireContext()))
        view.findViewById<TextView>(R.id.recent_title).setTextColor(text)
        view.findViewById<TextView>(R.id.dashboard_title).setTextColor(text)
        view.findViewById<TextView>(R.id.dashboard_last_group).setTextColor(textSec)
        view.findViewById<TextView>(R.id.dashboard_sync_status).setTextColor(textSec)
        listOf(R.id.continue_title, R.id.quick_actions_title, R.id.favorites_title).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        view.findViewById<TextView>(R.id.continue_detail).setTextColor(textSec)
        listOf(R.id.data_health_title, R.id.today_activity_title, R.id.alerts_title).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        listOf(R.id.daily_report_title, R.id.advanced_stats_title).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        listOf(R.id.data_health_sync, R.id.data_health_storage, R.id.today_activity_detail).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(textSec)
        }
        listOf(R.id.daily_items_label, R.id.daily_groups_label, R.id.daily_actions_label, R.id.weekly_average, R.id.top_group_stat, R.id.top_sender_stat).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(textSec)
        }
        view.findViewById<TextView>(R.id.daily_amount).setTextColor(text)
        view.findViewById<TextView>(R.id.invoices_label).setTextColor(textSec)
        view.findViewById<TextView>(R.id.groups_label).setTextColor(textSec)
        listOf(R.id.invoices_count, R.id.groups_count).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        // قسم آخر العمليات: مخفي افتراضيًا، يظهر عند الضغط على أيقونة ٧
        val recentPanel = view.findViewById<View>(R.id.recent_panel)
        val recentToggle = view.findViewById<ImageView>(R.id.recent_toggle)
        val expanded = AppRepository.lastSavedSearch("recent_panel") == "1"
        recentPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        recentToggle.rotation = if (expanded) 180f else 0f
        recentToggle.setOnClickListener {
            val nowExpanded = recentPanel.visibility == View.GONE
            recentPanel.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            recentToggle.rotation = if (nowExpanded) 180f else 0f
            AppRepository.setLastSavedSearch("recent_panel", if (nowExpanded) "1" else "0")
        }

        // أيقونات الإحصائيات بلون Teal
        listOf(R.id.card_invoices, R.id.card_groups).forEach { id ->
            view.findViewById<View>(id)?.let { card ->
                if (card is ViewGroup) {
                    var i = 0
                    while (i < card.childCount) {
                        val c = card.getChildAt(i)
                        if (c is ImageView) c.setColorFilter(ThemeHelper.accent(requireContext()))
                        i++
                    }
                }
            }
        }
        // تحديث خلفية حقل الإدخال في حالة فارغة حسب الوضع
        view.findViewById<View>(R.id.recent_empty)?.background?.setTint(ThemeHelper.inputFill(requireContext()))
        view.findViewById<TextView>(R.id.recent_empty)?.setTextColor(textSec)
    }

    private fun refresh() {
        val view = try { requireView() } catch (e: Exception) { return }
        try {
        val invCount = AppRepository.totalInvoiceCount()
        val grpCount = AppRepository.groups().size
        val invView = view.findViewById<TextView>(R.id.invoices_count)
        val grpView = view.findViewById<TextView>(R.id.groups_count)
        // عدّاد تصاعدي أنيق عند تغيير القيم
        animateCounter(invView, invCount)
        animateCounter(grpView, grpCount)

        val lastGroup = AppRepository.lastOpenedGroupId()?.let { id ->
            AppRepository.groups().find { it.id == id }?.name
        }
        view.findViewById<TextView>(R.id.dashboard_last_group).text =
            if (lastGroup == null) "آخر مجموعة: لم تُفتح مجموعة بعد" else "آخر مجموعة: $lastGroup"
        val lastSync = AppRepository.syncLog().lastOrNull()
        view.findViewById<TextView>(R.id.dashboard_sync_status).text = when {
            lastSync == null -> "آخر مزامنة: لا توجد عملية مسجلة"
            lastSync.success -> "آخر مزامنة: تمت بنجاح"
            else -> "آخر مزامنة: تحتاج إلى مراجعة"
        }
        updateDataHealth(view, lastSync)
        updateTodayActivity(view)
        updateDailyReport(view)
        populateAlerts(view, lastSync)
        val lastGroupObject = AppRepository.lastOpenedGroupId()?.let { id -> AppRepository.groups().find { it.id == id } }
        view.findViewById<TextView>(R.id.continue_detail).text = if (lastGroupObject == null) {
            "لم تُفتح مجموعة بعد — ابدأ من قائمة المجموعات"
        } else "آخر عمل: ${lastGroupObject.name}"
        view.findViewById<MaterialButton>(R.id.btn_continue_work).text = if (lastGroupObject == null) "فتح المجموعات" else "متابعة"
        populateFavorites(view)

        val recent = view.findViewById<RecyclerView>(R.id.recent_list)
        recent.layoutManager = LinearLayoutManager(requireContext())
        val entries = AppRepository.activityLog().take(5)
        if (entries.isEmpty()) {
            view.findViewById<TextView>(R.id.recent_empty).visibility = View.VISIBLE
            recent.visibility = View.GONE
        } else {
            view.findViewById<TextView>(R.id.recent_empty).visibility = View.GONE
            recent.visibility = View.VISIBLE
            recent.adapter = RecentAdapter(entries)
        }

        // تلوين أيقونة ٧ بألوان الوضع
        val recentToggle = view.findViewById<ImageView>(R.id.recent_toggle)
        recentToggle?.setColorFilter(ThemeHelper.textSecondary(requireContext()))
        // تلوين خلفية أيقونة ٧ حسب الوضع
        recentToggle?.background?.setTint(ThemeHelper.surfaceHigh(requireContext()))
        } catch (e: Exception) {
            try {
                view.findViewById<TextView>(R.id.recent_empty)?.visibility = View.VISIBLE
                view.findViewById<RecyclerView>(R.id.recent_list)?.visibility = View.GONE
            } catch (_: Exception) {}
        }
    }

    private fun populateFavorites(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.favorites_container)
        container.removeAllViews()
        val favorites = AppRepository.groups().filter { it.id in AppRepository.favoriteGroupIds() }
        if (favorites.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "لا توجد مجموعات مفضلة بعد. اخترها من زر «المفضلة»."
                setTextColor(ThemeHelper.textSecondary(requireContext()))
                textSize = 14f
                setPadding(0, 8, 0, 4)
            })
        } else favorites.forEach { group ->
            val button = MaterialButton(requireContext()).apply {
                text = "★  ${group.name}"
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setTextColor(ThemeHelper.text(requireContext()))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeHelper.surfaceHigh(requireContext()))
                setOnClickListener { openGroup(group) }
            }
            container.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46).apply { bottomMargin = 6 })
        }
    }

    private fun updateDataHealth(view: View, lastSync: com.masahhisabat.app.data.SyncEntry?) {
        val now = System.currentTimeMillis()
        view.findViewById<TextView>(R.id.data_health_sync).text = when {
            lastSync == null -> "المزامنة: لم تُنفذ بعد"
            !lastSync.success -> "المزامنة: تحتاج إلى مراجعة"
            else -> "المزامنة: آخر نجاح منذ ${relativeTime(now - lastSync.at)}"
        }
        val usage = AppRepository.storageUsage()
        view.findViewById<TextView>(R.id.data_health_storage).text =
            "التخزين: ${formatBytes(usage.dataBytes)} للبيانات والصور · ${formatBytes(usage.backupBytes)} للنسخ الوقائية"
    }

    private fun updateTodayActivity(view: View) {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val count = AppRepository.activityLog().count { it.at >= calendar.timeInMillis }
        view.findViewById<TextView>(R.id.today_activity_count).text = count.toString()
        view.findViewById<TextView>(R.id.today_activity_detail).text = when (count) {
            0 -> "لم تُسجّل عمليات اليوم بعد"
            1 -> "تم تسجيل عملية واحدة اليوم"
            else -> "تم تسجيل $count عمليات اليوم"
        }
    }

    private fun updateDailyReport(view: View) {
        val report = DashboardAnalytics.dailyReport()
        view.findViewById<TextView>(R.id.daily_items_count).text = report.todayItems.toString()
        view.findViewById<TextView>(R.id.daily_groups_count).text = report.activeGroups.toString()
        view.findViewById<TextView>(R.id.daily_actions_count).text = report.todayActions.toString()
        view.findViewById<TextView>(R.id.daily_amount).text = if (report.totalAmount > 0) {
            "إجمالي القيم المسجلة اليوم: ${DashboardAnalytics.formatAmount(report.totalAmount)} ${report.currency.orEmpty()}"
        } else "لا توجد قيم مالية مسجلة اليوم"
        view.findViewById<TextView>(R.id.weekly_average).text =
            "آخر 7 أيام: ${report.weeklyTotal} عناصر · متوسط ${"%.1f".format(java.util.Locale.US, report.weeklyAverage)} يوميًا"
        view.findViewById<TextView>(R.id.top_group_stat).text = report.topGroupName?.let {
            "المجموعة الأكثر نشاطًا: $it (${report.topGroupItems})"
        } ?: "المجموعة الأكثر نشاطًا: لا توجد بيانات اليوم"
        view.findViewById<TextView>(R.id.top_sender_stat).text = report.topSender?.let {
            "المستخدم الأكثر نشاطًا: $it (${report.topSenderItems})"
        } ?: "المستخدم الأكثر نشاطًا: لا توجد بيانات اليوم"
        renderWeeklyTrend(view.findViewById(R.id.week_trend_container), report)
    }

    /** أعمدة بسيطة محلية بدل مكتبة رسوميات إضافية؛ متجاوبة للهاتف والتابلت. */
    private fun renderWeeklyTrend(container: LinearLayout, report: DashboardAnalytics.DailyReport) {
        container.removeAllViews()
        val max = report.trend.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        report.trend.forEach { bucket ->
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }
            val height = (12 + (45f * bucket.count / max)).toInt()
            val bar = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(if (bucket.count == max && max > 0) ThemeHelper.accent(requireContext()) else ThemeHelper.cardStroke(requireContext()))
                }
                contentDescription = "${bucket.label}: ${bucket.count}"
            }
            val label = TextView(requireContext()).apply {
                text = bucket.label
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(ThemeHelper.textSecondary(requireContext()))
            }
            column.addView(bar, LinearLayout.LayoutParams(20, height))
            column.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 18))
            container.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun populateAlerts(view: View, lastSync: com.masahhisabat.app.data.SyncEntry?) {
        val container = view.findViewById<LinearLayout>(R.id.alerts_container)
        container.removeAllViews()
        val messages = mutableListOf<String>()
        val now = System.currentTimeMillis()
        when {
            lastSync == null -> messages += "لم تُجرَ مزامنة بعد — احفظ نسخة احتياطية أو اربط جهازًا آخر عند الحاجة."
            !lastSync.success -> messages += "آخر مزامنة لم تكتمل — افتح الإعدادات لمراجعة السجل وإعادة المحاولة."
            now - lastSync.at > 7L * 24 * 60 * 60 * 1000 -> messages += "مر أكثر من 7 أيام على آخر مزامنة ناجحة."
        }
        val usage = AppRepository.storageUsage()
        if (usage.dataBytes > 250L * 1024 * 1024) messages += "استهلاك الصور والبيانات كبير؛ راجع «إدارة التخزين» عند الحاجة."
        if (messages.isEmpty()) messages += "كل شيء يبدو جيدًا. البيانات المحلية والمزامنة في حالة مستقرة."
        messages.forEach { message ->
            container.addView(TextView(requireContext()).apply {
                text = "•  $message"
                setTextColor(ThemeHelper.textSecondary(requireContext()))
                textSize = 14f
                setPadding(0, 5, 0, 5)
                setOnClickListener { openTab(R.id.nav_settings) }
            })
        }
    }

    private fun relativeTime(milliseconds: Long): String = when {
        milliseconds < 60_000L -> "لحظات"
        milliseconds < 3_600_000L -> "${milliseconds / 60_000L} دقيقة"
        milliseconds < 86_400_000L -> "${milliseconds / 3_600_000L} ساعة"
        else -> "${milliseconds / 86_400_000L} يوم"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.US, bytes / (1024f * 1024f))
        bytes >= 1024L -> "%.1f KB".format(java.util.Locale.US, bytes / 1024f)
        else -> "$bytes B"
    }

    /** عدّاد تصاعدي ناعم من القيمة القديمة إلى الجديدة */
    private fun animateCounter(tv: TextView, target: Int) {
        val start = (tv.tag as? Int) ?: 0
        if (start == target) return
        tv.tag = target
        val duration = 500L
        val stepMs = 16L
        val steps = (duration / stepMs).toInt()
        var step = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                step++
                val ratio = if (steps > 0) step.toFloat() / steps else 1f
                val eased = 1f - (1f - ratio) * (1f - ratio) // ease-out مربع
                val current = (start + (target - start) * eased).toInt()
                tv.text = current.toString()
                if (step < steps) handler.postDelayed(this, stepMs)
                else tv.text = target.toString()
            }
        })
    }

    class RecentAdapter(private val items: List<com.masahhisabat.app.data.ActivityEntry>) :
        RecyclerView.Adapter<RecentAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(24, 18, 24, 18)
                textSize = 14f
                val bg = GradientDrawable().apply {
                    setColor(ThemeHelper.surface(parent.context))
                    cornerRadius = 18f
                }
                background = bg
                setTextColor(ThemeHelper.text(parent.context))
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(16, 0, 16, 10)
                layoutParams = lp
            }
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            (holder.itemView as TextView).text = "${fmt.format(java.util.Date(e.at))} — ${e.user}: ${e.action}"
        }
        override fun getItemCount() = items.size
    }
}
