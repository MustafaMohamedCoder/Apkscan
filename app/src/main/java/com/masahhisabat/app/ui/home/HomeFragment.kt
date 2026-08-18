package com.masahhisabat.app.ui.home

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.masahhisabat.app.ui.ThemeHelper
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
    }

    private fun applyTheme(view: View) {
        val bg = ThemeHelper.bg(requireContext())
        view.setBackgroundColor(bg)
        view.findViewById<View>(R.id.home_root).setBackgroundColor(bg)
        val surface = ThemeHelper.surface(requireContext())
        val text = ThemeHelper.text(requireContext())
        val textSec = ThemeHelper.textSecondary(requireContext())

        listOf(R.id.card_invoices, R.id.card_groups).forEach { id ->
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
