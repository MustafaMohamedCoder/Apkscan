package com.masahhisabat.app.ui

import android.content.Context
import android.content.res.ColorStateList
import com.google.android.material.color.MaterialColors
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository

/**
 * مدير الوضع الليلي/النهاري المخصص للتطبيق (خلفيات وألوان خاصة)
 * بحيث يطابق تصميم لقطات الشاشة المرجعية بطابع Teal عصري.
 */
object ThemeHelper {

    fun isNight(context: Context): Boolean = AppRepository.isNightMode()

    fun toggleTheme(context: Context) {
        AppRepository.setNightMode(!isNight(context))
    }

    fun bg(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_background)
        else context.getColor(R.color.day_background)

    fun surface(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_surface)
        else context.getColor(R.color.day_surface)

    fun surfaceHigh(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_surface_high)
        else context.getColor(R.color.day_surface_high)

    fun text(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_text)
        else context.getColor(R.color.day_text)

    fun textSecondary(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_text_secondary)
        else context.getColor(R.color.day_text_secondary)

    /** لون الأيقونة/النص النشط في الشريط السفلي (Teal فاتح) */
    fun accent(context: Context): Int = context.getColor(R.color.accent)

    fun primary(context: Context): Int = context.getColor(R.color.primary)

    /** لون الزر الرئيسي المتدرج (أخضر زمردي) */
    fun primaryAction(context: Context): Int = context.getColor(R.color.accent)

    fun cardStroke(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_card_stroke)
        else context.getColor(R.color.day_card_stroke)

    /** خلفية حقول الإدخال (تتغير حسب الوضع) */
    fun inputFill(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.input_fill_night)
        else context.getColor(R.color.input_fill)

    fun inputStroke(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.input_stroke_night)
        else context.getColor(R.color.input_stroke)

    /** شريط التنقل السفلي */
    fun navBarColor(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_nav_bar)
        else context.getColor(R.color.day_nav_bar)

    /** لون label غير النشط في الشريط السفلي */
    fun inactiveLabel(context: Context): Int =
        if (isNight(context)) 0xFF8FA3A6.toInt()
        else 0xFF9AA7A4.toInt()

    /** تظليل أيقونة ممتلئة للعنصر النشط (خلفية Teal شفافة) */
    fun activeTintList(context: Context): ColorStateList =
        ColorStateList.valueOf(accent(context))

    /** تدرج Teal احترافي للبطاقات البارزة (من teal-600 إلى teal-400) */
    fun primaryGradientColors(): IntArray = intArrayOf(
        0xFF0F766E.toInt(), 0xFF14B8A6.toInt()
    )

    /** لون الشريحة/الوسم الصغير الخلفي (Teal شفاف) */
    fun chipBgColor(context: Context): Int =
        if (isNight(context)) 0x2A14B8A6.toInt() else 0x1A0F766E.toInt()

    fun chipTextColor(context: Context): Int =
        if (isNight(context)) 0xFF5EEAD4.toInt() else 0xFF0F766E.toInt()

    /** خط رفيع للفاصل داخل البطاقات */
    fun dividerColor(context: Context): Int =
        if (isNight(context)) 0xFF1C423D.toInt() else 0xFFE4EEEA.toInt()

    /** لون الترحيب/الاسم في الشريط */
    fun greetingColor(context: Context): Int = accent(context)

    /** خلفية فقاعة الرسالة (تتغير حسب الوضع) */
    fun bubbleBgRes(context: Context): Int =
        if (isNight(context)) R.drawable.msg_bubble_bg_night
        else R.drawable.msg_bubble_bg

    /** لون نص الفقاعة */
    fun bubbleText(context: Context): Int = context.getColor(R.color.bubble_text)

    /** لون الوقت والتذييل داخل الفقاعة */
    fun bubbleTime(context: Context): Int =
        if (isNight(context)) 0xFF7FA8A2.toInt() else context.getColor(R.color.bubble_time)

    /** لون علامة المشاهدة الزرقاء */
    fun bubbleSeen(context: Context): Int = context.getColor(R.color.bubble_seen)

    /** مورد خلفية عداد الصور (دائري فاتح/داكن حسب الوضع) */
    fun counterBgRes(context: Context): Int =
        if (isNight(context)) R.drawable.viewer_counter_bg
        else R.drawable.viewer_counter_bg_light

    /** لون خلفية عداد الصور (قديم) */
    fun counterBg(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.counter_dark)
        else context.getColor(R.color.counter_light)

    /** لون نص عداد الصور */
    fun counterText(context: Context): Int =
        if (isNight(context)) 0xFFB9DCD6.toInt() else 0xFF0F4C47.toInt()
}
