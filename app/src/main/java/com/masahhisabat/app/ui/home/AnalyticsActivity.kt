package com.masahhisabat.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.masahhisabat.app.data.DashboardAnalytics
import com.masahhisabat.app.ui.ThemeHelper

/** عرض تفصيلي للبيانات المحلية يشرح الأرقام بدل الاكتفاء ببطاقات الملخص. */
class AnalyticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        // تعكس الشاشة أي عنصر جديد أُضيف إذا عاد المستخدم إليها دون إنهاء النشاط.
        if (window.decorView.findViewWithTag<View>("analytics_root") != null) render()
    }

    private fun render() {
        val report = DashboardAnalytics.dailyReport()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            tag = "analytics_root"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
            setBackgroundColor(ThemeHelper.bg(this@AnalyticsActivity))
        }
        fun text(value: String, size: Float, bold: Boolean = false, color: Int = ThemeHelper.text(this)): TextView = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color)
            typeface = resources.getFont(if (bold) com.masahhisabat.app.R.font.tajawal_bold else com.masahhisabat.app.R.font.tajawal_regular)
        }
        fun card(title: String, body: String): MaterialCardView = MaterialCardView(this).apply {
            radius = dp(20).toFloat(); cardElevation = dp(2).toFloat(); strokeWidth = 1
            strokeColor = ThemeHelper.cardStroke(this@AnalyticsActivity)
            setCardBackgroundColor(ThemeHelper.surface(this@AnalyticsActivity))
            addView(LinearLayout(this@AnalyticsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(15), dp(18), dp(15))
                addView(text(title, 16f, true))
                addView(text(body, 14f, false, ThemeHelper.textSecondary(this@AnalyticsActivity)).apply { setPadding(0, dp(6), 0, 0) })
            })
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        header.addView(text("التقارير والإحصاءات", 24f, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(this).apply {
            text = "مشاركة"
            setOnClickListener { shareReport(report) }
        })
        content.addView(header)
        content.addView(text("ملخص محلي مباشر لبيانات اليوم وآخر 7 أيام", 14f, false, ThemeHelper.textSecondary(this)).apply { setPadding(0, dp(4), 0, dp(16)) })
        content.addView(card("ملخص اليوم", "${report.todayItems} عنصرًا جديدًا · ${report.todayImages} صور · ${report.todayTexts} نصوص\n${report.activeGroups} مجموعات نشطة · ${report.todayActions} عمليات مسجلة"))
        content.addView(card("القيم المسجلة", if (report.totalAmount > 0) "${DashboardAnalytics.formatAmount(report.totalAmount)} ${report.currency.orEmpty()}" else "لا توجد قيم مالية مسجلة ضمن عناصر اليوم").apply { setMargins(dp(12)) })
        content.addView(card("أكثر مصادر النشاط", buildString {
            append(report.topGroupName?.let { "المجموعة: $it (${report.topGroupItems} عناصر)" } ?: "لا توجد مجموعة نشطة اليوم")
            append('\n')
            append(report.topSender?.let { "المستخدم: $it (${report.topSenderItems} عناصر)" } ?: "لا توجد بيانات عن مستخدم نشط اليوم")
        }).apply { setMargins(dp(12)) })
        content.addView(card("الاتجاه الأسبوعي", "إجمالي آخر 7 أيام: ${report.weeklyTotal} عنصرًا\nالمتوسط: ${"%.1f".format(java.util.Locale.US, report.weeklyAverage)} عنصرًا يوميًا\n${report.trend.joinToString(" · ") { "${it.label}: ${it.count}" }}").apply { setMargins(dp(12)) })
        content.addView(MaterialButton(this).apply {
            text = "رجوع إلى لوحة التحكم"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun MaterialCardView.setMargins(top: Int) {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    }

    private fun shareReport(report: DashboardAnalytics.DailyReport) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, DashboardAnalytics.shareText(report))
        }, "مشاركة التقرير اليومي"))
    }
}
