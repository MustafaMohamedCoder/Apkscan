package com.masahhisabat.app.ui.home

import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.masahhisabat.app.R
import com.masahhisabat.app.data.DashboardAnalytics
import com.masahhisabat.app.data.LocalReportExporter
import com.masahhisabat.app.ui.ThemeHelper
import java.util.Locale

/**
 * لوحة التقارير المحلية: تعرض مؤشرات نشاط وقيم فواتير مسجلة فقط.
 * لا تُخلط العملات ولا تُرسل البيانات خارج الجهاز.
 */
class AnalyticsActivity : AppCompatActivity() {
    private var selectedPeriod = DashboardAnalytics.ReportPeriod.LAST_7_DAYS
    private var exportInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        // تعكس الشاشة أي عنصر أو قيمة مالية أضيفت محلياً عند العودة إليها.
        if (window.decorView.findViewWithTag<View>("analytics_root") != null) render()
    }

    private fun render() {
        val report = DashboardAnalytics.periodReport(selectedPeriod)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val textColor = ThemeHelper.text(this)
        val secondary = ThemeHelper.textSecondary(this)

        fun bodyText(value: String, size: Float, bold: Boolean = false, color: Int = textColor): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = resources.getFont(if (bold) R.font.tajawal_bold else R.font.tajawal_regular)
            includeFontPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun glassCard(title: String, body: String, accent: Boolean = false): MaterialCardView = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = dp(if (accent) 2 else 1)
            strokeColor = if (accent) getColor(R.color.accent) else ThemeHelper.cardStroke(this@AnalyticsActivity)
            setCardBackgroundColor(if (accent) ThemeHelper.surfaceHigh(this@AnalyticsActivity) else ThemeHelper.surface(this@AnalyticsActivity))
            addView(LinearLayout(this@AnalyticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                addView(bodyText(title, 14f, true, if (accent) getColor(R.color.water_deep) else textColor))
                addView(bodyText(body, 18f, true).apply { setPadding(0, dp(8), 0, 0) })
            })
        }

        fun panel(title: String, content: View): MaterialCardView = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ThemeHelper.cardStroke(this@AnalyticsActivity)
            setCardBackgroundColor(ThemeHelper.surfaceHigh(this@AnalyticsActivity))
            addView(LinearLayout(this@AnalyticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(bodyText(title, 17f, true))
                addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                })
            })
        }

        fun actionButton(label: String, selected: Boolean = false): MaterialButton = MaterialButton(this).apply {
            text = label
            textSize = 13f
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            isAllCaps = false
            cornerRadius = dp(18)
            if (selected) {
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.primary))
                setTextColor(getColor(R.color.white))
                strokeWidth = 0
            } else {
                backgroundTintList = ColorStateList.valueOf(ThemeHelper.surfaceHigh(this@AnalyticsActivity))
                setTextColor(textColor)
                strokeColor = ColorStateList.valueOf(ThemeHelper.cardStroke(this@AnalyticsActivity))
                strokeWidth = dp(1)
            }
        }

        fun addSection(target: LinearLayout, child: View, top: Int = 14) {
            target.addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(top)
            })
        }

        val root = LinearLayout(this).apply {
            tag = "analytics_root"
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(22), dp(18), dp(38))
            setBackgroundResource(ThemeHelper.backgroundRes())
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(bodyText("التقارير المالية", 25f, true))
            addView(bodyText("قراءة محلية للنشاط والقيم المسجلة", 13f, false, secondary).apply {
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("تصدير", true).apply {
            contentDescription = "تصدير تقرير ${selectedPeriod.label}"
            setOnClickListener { showExportOptions(report) }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        root.addView(header)

        val filterPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundResource(R.drawable.glass_panel_bg)
        }
        DashboardAnalytics.ReportPeriod.entries.forEachIndexed { index, period ->
            filterPanel.addView(actionButton(period.label, period == selectedPeriod).apply {
                contentDescription = "عرض تقارير ${period.label}"
                setOnClickListener {
                    selectedPeriod = period
                    render()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                if (index > 0) marginStart = dp(7)
            })
        }
        addSection(root, filterPanel, 20)

        if (!report.hasData) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(24), dp(28), dp(24), dp(28))
                setBackgroundResource(R.drawable.empty_state_panel)
                addView(bodyText("لا توجد بيانات ضمن ${report.period.label}", 18f, true))
                addView(bodyText("أضف مسحاً أو رسالة أو فاتورة، وستظهر مؤشرات هذه الفترة محلياً هنا.", 14f, false, secondary).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                })
            }
            empty.contentDescription = "لا توجد بيانات للتقارير ضمن ${report.period.label}"
            addSection(root, empty, 16)
        } else {
            val metrics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun metricRow(left: MaterialCardView, right: MaterialCardView) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
                metrics.addView(row)
            }
            metricRow(
                glassCard("العناصر المسجلة", "${report.itemCount}", true),
                glassCard("المجموعات النشطة", "${report.activeGroups}")
            )
            metricRow(
                glassCard("متوسط النشاط", "${"%.1f".format(Locale.US, report.dailyAverage)} / يوم"),
                glassCard("محتوى مصوّر", "${report.imagePercent}% صور")
            ).also { metrics.getChildAt(1).setPadding(0, dp(12), 0, 0) }
            addSection(root, metrics, 16)

            val financialLines = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                if (report.amountsByCurrency.isEmpty()) {
                    addView(bodyText("لم تُسجّل قيم مالية في عناصر هذه الفترة.", 14f, false, secondary))
                } else {
                    addView(bodyText("تفصل القيم بحسب العملة ولا تُجمع معاً.", 13f, false, secondary))
                    report.amountsByCurrency.forEach { bucket ->
                        addView(bodyText(
                            "${DashboardAnalytics.formatAmount(bucket.amount)}  ${bucket.currency ?: "عملة غير محددة"}",
                            20f,
                            true,
                            getColor(R.color.water_deep)
                        ).apply { setPadding(0, dp(10), 0, 0) })
                    }
                }
            }
            addSection(root, panel("القيم المالية المسجلة", financialLines))

            val chartPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                addView(bodyText("عدد العناصر المسجلة لكل يوم", 13f, false, secondary))
                addView(TrendChartView(this@AnalyticsActivity, report.trend, ThemeHelper.isNight(this@AnalyticsActivity)).apply {
                    contentDescription = "مخطط النشاط: ${report.trend.joinToString("، ") { "${it.label} ${it.count}" }}"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(176)).apply { topMargin = dp(10) })
            }
            addSection(root, panel("اتجاه النشاط", chartPanel))

            val sourceText = buildString {
                append(report.topGroupName?.let { "المجموعة الأكثر نشاطاً: $it (${report.topGroupItems} عناصر)" } ?: "لا توجد مجموعة نشطة ضمن الفترة")
                append('\n')
                append(report.topSender?.let { "المستخدم الأكثر إضافة: $it (${report.topSenderItems} عناصر)" } ?: "لا توجد بيانات مستخدمين ضمن الفترة")
                append('\n')
                append("العمليات المسجلة: ${report.actionCount}")
            }
            addSection(root, panel("مصادر النشاط", bodyText(sourceText, 14f, false, secondary)))
        }

        addSection(root, actionButton("رجوع إلى لوحة التحكم").apply {
            setOnClickListener { finish() }
        }, 18)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundResource(ThemeHelper.backgroundRes())
            addView(root)
        })
    }

    private fun shareReport(report: DashboardAnalytics.PeriodReport) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, DashboardAnalytics.shareText(report))
        }, "مشاركة تقرير ${report.period.label}"))
    }

    private fun showExportOptions(report: DashboardAnalytics.PeriodReport) {
        MaterialAlertDialogBuilder(this)
            .setTitle("تصدير التقرير")
            .setItems(arrayOf("مشاركة كنص", "تصدير كصورة", "تصدير PDF")) { _, selected ->
                when (selected) {
                    0 -> shareReport(report)
                    1 -> exportReportImage(report)
                    2 -> exportReportPdf(report)
                }
            }
            .show()
    }

    private fun exportReportImage(report: DashboardAnalytics.PeriodReport) {
        exportInBackground(
            busyMessage = "يتم تجهيز صورة التقرير…",
            failureMessage = "تعذر تصدير صورة التقرير"
        ) {
            LocalReportExporter.exportReportImage(this, report)
        }.onSuccess { uri ->
            shareExport(uri, "image/jpeg", "صورة تقرير ${report.period.label}")
        }
    }

    private fun exportReportPdf(report: DashboardAnalytics.PeriodReport) {
        exportInBackground(
            busyMessage = "يتم تجهيز ملف PDF…",
            failureMessage = "تعذر تصدير ملف PDF"
        ) {
            LocalReportExporter.exportReportPdf(this, report)
        }.onSuccess { uri ->
            shareExport(uri, "application/pdf", "PDF تقرير ${report.period.label}")
        }
    }

    private fun exportInBackground(
        busyMessage: String,
        failureMessage: String,
        export: () -> android.net.Uri
    ): ExportCallback {
        if (exportInProgress) {
            Toast.makeText(this, "التصدير جارٍ بالفعل، انتظر لحظات.", Toast.LENGTH_SHORT).show()
            return ExportCallback {}
        }
        exportInProgress = true
        Toast.makeText(this, busyMessage, Toast.LENGTH_SHORT).show()
        lateinit var callback: ExportCallback
        callback = ExportCallback { onSuccess ->
            Thread {
                val result = runCatching(export)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    exportInProgress = false
                    result.onSuccess(onSuccess).onFailure {
                        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        return callback
    }

    private class ExportCallback(private val execute: ((android.net.Uri) -> Unit) -> Unit) {
        fun onSuccess(action: (android.net.Uri) -> Unit) = execute(action)
    }

    private fun shareExport(uri: android.net.Uri, mimeType: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    /** رسم أعمدة محلي خفيف لا يعتمد على مكتبات أو خدمات خارجية. */
    private class TrendChartView(
        context: android.content.Context,
        private val values: List<DashboardAnalytics.DayBucket>,
        private val isNight: Boolean
    ) : View(context) {
        private val density = context.resources.displayMetrics.density
        private fun dp(value: Float) = value * density
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15C6C8.toInt() }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (isNight) 0x424DD8E8 else 0x48A1DFE6 }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNight) 0xFFBFE3EB.toInt() else 0xFF466579.toInt()
            textAlign = Paint.Align.CENTER
            textSize = dp(11f)
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isNight) 0xFFF3FCFF.toInt() else 0xFF092338.toInt()
            textAlign = Paint.Align.CENTER
            textSize = dp(11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val backgroundBarRect = RectF()
        private val valueBarRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (values.isEmpty()) return
            val side = dp(4f)
            val bottom = height - dp(25f)
            val top = dp(20f)
            val availableHeight = (bottom - top).coerceAtLeast(dp(1f))
            val gap = dp(8f)
            val barWidth = ((width - side * 2 - gap * (values.size - 1)) / values.size).coerceAtLeast(dp(8f))
            val max = values.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            values.forEachIndexed { index, bucket ->
                val left = side + index * (barWidth + gap)
                val ratio = bucket.count.toFloat() / max
                val barTop = bottom - availableHeight * ratio
                backgroundBarRect.set(left, top, left + barWidth, bottom)
                valueBarRect.set(left, barTop, left + barWidth, bottom)
                canvas.drawRoundRect(backgroundBarRect, dp(9f), dp(9f), backgroundPaint)
                canvas.drawRoundRect(valueBarRect, dp(9f), dp(9f), barPaint)
                val center = left + barWidth / 2
                canvas.drawText(bucket.count.toString(), center, (barTop - dp(7f)).coerceAtLeast(dp(12f)), valuePaint)
                canvas.drawText(bucket.label, center, height - dp(6f), labelPaint)
            }
        }
    }
}
