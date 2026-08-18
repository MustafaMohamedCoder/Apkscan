package com.masahhisabat.app.data

import java.util.Calendar
import java.util.Locale

/**
 * حسابات لوحة التحكم تُنفّذ محليًا من البيانات المخزنة، ولا تحتاج إلى إنترنت أو خدمة خارجية.
 * تجمع المؤشرات في مكان واحد حتى تتطابق البطاقات، شاشة التفاصيل، وتقرير المشاركة.
 */
object DashboardAnalytics {

    data class DayBucket(val label: String, val count: Int)

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

    fun dailyReport(reference: Long = System.currentTimeMillis()): DailyReport {
        val todayStart = startOfDay(reference)
        val groups = AppRepository.groups()
        val groupById = groups.associateBy { it.id }
        val allItems = groups.flatMap { group -> AppRepository.items(group.id).map { group.id to it } }
        val today = allItems.filter { (_, item) -> item.createdAt >= todayStart && item.createdAt <= reference }
        val groupCounts = today.groupingBy { it.first }.eachCount()
        val senderCounts = today.mapNotNull { it.second.sender?.trim()?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
        val amountItems = today.mapNotNull { (_, item) -> parseAmount(item.total)?.let { amount -> amount to item.currency } }
        val firstCurrency = amountItems.firstOrNull()?.second
        val trend = (6 downTo 0).map { daysAgo ->
            val start = startOfDay(reference - daysAgo * DAY_MS)
            val end = start + DAY_MS
            DayBucket(dayLabel(start), allItems.count { (_, item) -> item.createdAt in start until end })
        }
        val topGroup = groupCounts.maxByOrNull { it.value }
        val topSender = senderCounts.maxByOrNull { it.value }
        return DailyReport(
            todayItems = today.size,
            todayImages = today.count { it.second.type == "image" },
            todayTexts = today.count { it.second.type == "text" },
            todayActions = AppRepository.activityLog().count { it.at >= todayStart && it.at <= reference },
            activeGroups = groupCounts.size,
            totalAmount = amountItems.sumOf { it.first },
            currency = firstCurrency,
            topGroupName = topGroup?.key?.let { groupById[it]?.name },
            topGroupItems = topGroup?.value ?: 0,
            topSender = topSender?.key,
            topSenderItems = topSender?.value ?: 0,
            weeklyAverage = trend.map { it.count }.average(),
            weeklyTotal = trend.sumOf { it.count },
            trend = trend
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

    fun formatAmount(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayLabel(time: Long): String = java.text.SimpleDateFormat("EEE", Locale("ar")).format(java.util.Date(time))

    /** يدعم فواصل الآلاف والأرقام العربية في حقل إجمالي الفاتورة إن وجد. */
    private fun parseAmount(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.map { char ->
            when (char) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                else -> char
            }
        }.joinToString("")
        val normalized = digits.replace(',', '.').replace(Regex("[^0-9.]"), "")
        return normalized.toDoubleOrNull()
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
