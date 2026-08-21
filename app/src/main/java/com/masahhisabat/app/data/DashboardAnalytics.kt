package com.masahhisabat.app.data

import java.util.Calendar
import java.util.Locale

/**
 * حسابات لوحة التحكم تُنفّذ محليًا من البيانات المخزنة، ولا تحتاج إلى إنترنت أو خدمة خارجية.
 * تجمع المؤشرات في مكان واحد حتى تتطابق البطاقات، شاشة التفاصيل، وتقرير المشاركة.
 */
object DashboardAnalytics {

    data class DayBucket(val label: String, val count: Int)

    enum class ReportPeriod(val days: Int, val label: String) {
        TODAY(1, "اليوم"),
        LAST_7_DAYS(7, "آخر 7 أيام"),
        LAST_30_DAYS(30, "آخر 30 يوماً")
    }

    /** قيمة مالية مجمعة بحسب العملة؛ لا تخلط الشاشة بين عملات مختلفة في رقم واحد. */
    data class MoneyBucket(val currency: String?, val amount: Double)

    data class PeriodReport(
        val period: ReportPeriod,
        val itemCount: Int,
        val imageCount: Int,
        val textCount: Int,
        val actionCount: Int,
        val activeGroups: Int,
        val amountsByCurrency: List<MoneyBucket>,
        val topGroupName: String?,
        val topGroupItems: Int,
        val topSender: String?,
        val topSenderItems: Int,
        val dailyAverage: Double,
        val trend: List<DayBucket>
    ) {
        val hasData: Boolean get() = itemCount > 0
        val imagePercent: Int get() = if (itemCount == 0) 0 else (imageCount * 100f / itemCount).toInt()
    }

    data class DailyReport(
        val todayItems: Int,
        val todayImages: Int,
        val todayTexts: Int,
        val todayActions: Int,
        val activeGroups: Int,
        val totalAmount: Double,
        val currency: String?,
        val topGroupName: String?,
        val topGroupItems: Int,
        val topSender: String?,
        val topSenderItems: Int,
        val weeklyAverage: Double,
        val weeklyTotal: Int,
        val trend: List<DayBucket>
    )

    fun periodReport(
        period: ReportPeriod = ReportPeriod.LAST_7_DAYS,
        reference: Long = System.currentTimeMillis()
    ): PeriodReport {
        val periodStart = startOfDay(reference - (period.days - 1) * DAY_MS)
        val groups = AppRepository.groups()
        val groupById = groups.associateBy { it.id }
        val allItems = groups.flatMap { group -> AppRepository.items(group.id).map { group.id to it } }
        val periodItems = allItems.filter { (_, item) -> item.createdAt in periodStart..reference }
        val groupCounts = periodItems.groupingBy { it.first }.eachCount()
        val senderCounts = periodItems.mapNotNull { it.second.sender?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount()
        val amountsByCurrency = periodItems.mapNotNull { (_, item) ->
            parseAmount(item.total)?.let { amount -> normalizedCurrency(item.currency) to amount }
        }.groupBy({ it.first }, { it.second }).map { (currency, amounts) ->
            MoneyBucket(currency, amounts.sum())
        }.sortedBy { it.currency ?: "" }
        val trend = (period.days - 1 downTo 0).map { daysAgo ->
            val start = startOfDay(reference - daysAgo * DAY_MS)
            val end = start + DAY_MS
            DayBucket(dayLabel(start), allItems.count { (_, item) -> item.createdAt in start until end })
        }
        val topGroup = groupCounts.maxByOrNull { it.value }
        val topSender = senderCounts.maxByOrNull { it.value }
        return PeriodReport(
            period = period,
            itemCount = periodItems.size,
            imageCount = periodItems.count { it.second.type == "image" },
            textCount = periodItems.count { it.second.type == "text" },
            actionCount = AppRepository.activityLog().count { it.at in periodStart..reference },
            activeGroups = groupCounts.size,
            amountsByCurrency = amountsByCurrency,
            topGroupName = topGroup?.key?.let { groupById[it]?.name },
            topGroupItems = topGroup?.value ?: 0,
            topSender = topSender?.key,
            topSenderItems = topSender?.value ?: 0,
            dailyAverage = trend.map { it.count }.average(),
            trend = trend
        )
    }

    /** توافق للخدمات الموجودة التي تعرض تقرير اليوم في لوحة التحكم أو المشاركة القديمة. */
    fun dailyReport(reference: Long = System.currentTimeMillis()): DailyReport {
        val report = periodReport(ReportPeriod.TODAY, reference)
        val weekly = periodReport(ReportPeriod.LAST_7_DAYS, reference)
        return DailyReport(
            todayItems = report.itemCount,
            todayImages = report.imageCount,
            todayTexts = report.textCount,
            todayActions = report.actionCount,
            activeGroups = report.activeGroups,
            totalAmount = report.amountsByCurrency.firstOrNull()?.amount ?: 0.0,
            currency = report.amountsByCurrency.firstOrNull()?.currency,
            topGroupName = report.topGroupName,
            topGroupItems = report.topGroupItems,
            topSender = report.topSender,
            topSenderItems = report.topSenderItems,
            weeklyAverage = weekly.dailyAverage,
            weeklyTotal = weekly.itemCount,
            trend = weekly.trend
        )
    }

    fun shareText(report: DailyReport = dailyReport()): String = buildString {
        appendLine("تقرير ماسح الحسابات اليومي")
        appendLine("العناصر المضافة اليوم: ${report.todayItems}")
        appendLine("الصور: ${report.todayImages} | النصوص: ${report.todayTexts}")
        appendLine("المجموعات النشطة: ${report.activeGroups}")
        if (report.totalAmount > 0) appendLine("إجمالي القيم: ${formatAmount(report.totalAmount)} ${report.currency.orEmpty()}")
        appendLine("متوسط النشاط لآخر 7 أيام: ${"%.1f".format(Locale.US, report.weeklyAverage)} عنصر يوميًا")
        report.topGroupName?.let { appendLine("المجموعة الأكثر نشاطًا: $it (${report.topGroupItems})") }
        report.topSender?.let { appendLine("أكثر مستخدم نشاطًا: $it (${report.topSenderItems})") }
    }

    fun shareText(report: PeriodReport): String = buildString {
        appendLine("تقرير ماسح الحسابات — ${report.period.label}")
        appendLine("العناصر المسجلة: ${report.itemCount}")
        appendLine("الصور: ${report.imageCount} | النصوص: ${report.textCount}")
        appendLine("المجموعات النشطة: ${report.activeGroups} | العمليات: ${report.actionCount}")
        if (report.amountsByCurrency.isNotEmpty()) {
            appendLine("القيم المالية المسجلة بحسب العملة:")
            report.amountsByCurrency.forEach { bucket ->
                appendLine("- ${formatAmount(bucket.amount)} ${bucket.currency ?: "عملة غير محددة"}")
            }
        }
        appendLine("متوسط النشاط: ${"%.1f".format(Locale.US, report.dailyAverage)} عنصر يوميًا")
        report.topGroupName?.let { appendLine("المجموعة الأكثر نشاطًا: $it (${report.topGroupItems})") }
        report.topSender?.let { appendLine("المستخدم الأكثر إضافة: $it (${report.topSenderItems})") }
    }

    fun formatAmount(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayLabel(time: Long): String = java.text.SimpleDateFormat("EEE", Locale("ar")).format(java.util.Date(time))

    private fun normalizedCurrency(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)

    /** يدعم الأرقام العربية وفواصل الآلاف العربية أو الغربية في حقل إجمالي الفاتورة إن وجد. */
    internal fun parseAmount(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val normalizedDigits = raw.map { char ->
            when (char) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                else -> char
            }
        }.joinToString("")
            .replace('٬', ',')
            .replace('٫', '.')
        // استخراج مقطع رقمي متصل يمنع نقاط اختصارات العملات مثل «ج.م» من التحول إلى فاصلة عشرية إضافية.
        val numeric = Regex("[0-9][0-9,.]*").find(normalizedDigits)?.value.orEmpty()
        if (numeric.isBlank()) return null
        val normalized = when {
            numeric.contains(',') && numeric.contains('.') -> {
                // الفاصل الأخير هو العشري؛ أما الآخر فهو فاصل آلاف.
                if (numeric.lastIndexOf('.') > numeric.lastIndexOf(',')) {
                    numeric.replace(",", "")
                } else {
                    numeric.replace(".", "").replace(',', '.')
                }
            }
            numeric.count { it == ',' } > 1 ||
                (numeric.count { it == ',' } == 1 && numeric.substringAfterLast(',').length == 3) -> {
                numeric.replace(",", "")
            }
            else -> numeric.replace(',', '.')
        }
        return normalized.toDoubleOrNull()
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
