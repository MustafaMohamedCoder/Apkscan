package com.masahhisabat.app.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.InvoiceWorkflow
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.invoice.InboxActivity
import com.masahhisabat.app.ui.messages.DirectMessagesActivity
import com.masahhisabat.app.ui.notifications.NotificationsActivity

/**
 * بداية مبسطة للاستخدام اليومي: المسح أولًا، ثم نظرة سريعة وصندوق الوارد.
 * أما البحث والمزامنة والإعدادات المتقدمة فتظل في أقسامها المخصصة.
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

        view.findViewById<MaterialButton>(R.id.btn_start_scan).setOnClickListener { openTab(R.id.nav_scanner) }
        val openGroups = View.OnClickListener { openTab(R.id.nav_groups) }
        view.findViewById<View>(R.id.card_groups).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_count).setOnClickListener(openGroups)
        view.findViewById<TextView>(R.id.groups_label).setOnClickListener(openGroups)

        val openInbox = View.OnClickListener { openInbox() }
        view.findViewById<View>(R.id.card_invoices).setOnClickListener(openInbox)
        view.findViewById<TextView>(R.id.invoices_count).setOnClickListener(openInbox)
        view.findViewById<TextView>(R.id.invoices_label).setOnClickListener(openInbox)
        view.findViewById<View>(R.id.inbox_card).setOnClickListener(openInbox)
        view.findViewById<MaterialButton>(R.id.btn_open_inbox).setOnClickListener(openInbox)
        val openNotifications = View.OnClickListener { startActivity(Intent(requireContext(), NotificationsActivity::class.java)) }
        view.findViewById<View>(R.id.notifications_card).setOnClickListener(openNotifications)
        val openMessages = View.OnClickListener { startActivity(Intent(requireContext(), DirectMessagesActivity::class.java)) }
        view.findViewById<View>(R.id.direct_messages_card).setOnClickListener(openMessages)

        refresh(force = true)
    }

    override fun onResume() {
        super.onResume()
        // عند العودة من الوارد أو الماسح، أعِد حساب الشارة فورًا كي لا يبقى المستخدم
        // أمام عدد قديم للفواتير الجديدة.
        if (view != null) refresh(force = true)
    }

    private fun openTab(id: Int) {
        (activity as? com.masahhisabat.app.ui.main.MainActivity)?.findViewById<View>(id)?.performClick()
    }

    private fun openInbox() {
        startActivity(Intent(requireContext(), InboxActivity::class.java))
    }

    private fun applyTheme(view: View) {
        val context = requireContext()
        val text = ThemeHelper.text(context)
        val textSecondary = ThemeHelper.textSecondary(context)
        view.setBackgroundResource(ThemeHelper.backgroundRes())
        view.findViewById<View>(R.id.home_root).setBackgroundResource(ThemeHelper.backgroundRes())

        listOf(R.id.card_groups, R.id.card_invoices, R.id.inbox_card, R.id.notifications_card, R.id.direct_messages_card).forEach { id ->
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
        view.findViewById<android.widget.ImageView>(R.id.welcome_badge).setColorFilter(heroTitle)

        listOf(R.id.home_overview_title, R.id.inbox_title, R.id.notifications_summary, R.id.direct_messages_summary).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(text)
        }
        listOf(R.id.groups_label, R.id.invoices_label, R.id.inbox_summary).forEach { id ->
            view.findViewById<TextView>(id).setTextColor(textSecondary)
        }
        view.findViewById<TextView>(R.id.groups_count).setTextColor(ThemeHelper.accent(context))
        view.findViewById<TextView>(R.id.invoices_count).setTextColor(ThemeHelper.accent(context))
        view.findViewById<TextView>(R.id.new_invoices_badge).apply {
            setTextColor(ThemeHelper.chipTextColor(context))
            backgroundTintList = ColorStateList.valueOf(ThemeHelper.chipBgColor(context))
        }
    }

    private fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (isRefreshing || (!force && now - lastRefreshAt < 900L)) return
        val root = view ?: return
        isRefreshing = true
        try {
            root.findViewById<TextView>(R.id.invoices_count).text = AppRepository.totalInvoiceCount().toString()
            root.findViewById<TextView>(R.id.groups_count).text = AppRepository.groups().size.toString()
            root.findViewById<TextView>(R.id.notifications_summary).text = if (AppRepository.unreadNotificationCount() > 0) "${AppRepository.unreadNotificationCount()} إشعارات جديدة" else "لا توجد إشعارات جديدة"
            root.findViewById<TextView>(R.id.direct_messages_summary).text = if (AppRepository.directMessages().isNotEmpty()) "${AppRepository.directMessages().size} رسالة محفوظة" else "إرسال نصوص وصور"
            val workflow = AppRepository.invoiceWorkItems(includePaid = false)
            val newCount = workflow.count { (_, item) -> item.status == InvoiceWorkflow.NEW }
            val reviewCount = workflow.count { (_, item) -> item.status == InvoiceWorkflow.IN_REVIEW }
            root.findViewById<TextView>(R.id.new_invoices_badge).apply {
                if (newCount > 0) {
                    text = if (newCount > 99) "99+ جديدة" else "$newCount جديدة"
                    contentDescription = "$newCount فواتير جديدة في صندوق الوارد"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            root.findViewById<TextView>(R.id.inbox_summary).text = when {
                newCount > 0 && reviewCount > 0 -> "$newCount جديدة و$reviewCount قيد المراجعة"
                newCount > 0 -> "$newCount فواتير جديدة تحتاج فرزًا"
                reviewCount > 0 -> "$reviewCount فواتير تحتاج متابعة"
                workflow.isNotEmpty() -> "لا توجد فواتير معلّقة للمراجعة"
                else -> "سيظهر هنا كل مستند جديد بعد مسحه"
            }
        } finally {
            lastRefreshAt = SystemClock.elapsedRealtime()
            isRefreshing = false
        }
    }
}
