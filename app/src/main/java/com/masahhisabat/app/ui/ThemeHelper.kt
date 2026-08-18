package com.masahhisabat.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.MaterialColors
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository

/**
 * مدير الوضع الليلي/النهاري المخصص للتطبيق (خلفيات وألوان خاصة)
 * بحيث يطابق تصميم لقطات الشاشة المرجعية بطابع Teal عصري.
 */
object ThemeHelper {

    /** الخيارات المتاحة للمستخدم: تلقائي من الجهاز أو نهاري أو ليلي يدوي. */
    enum class Mode(val preferenceValue: String, val label: String) {
        SYSTEM("system", "تلقائي (إعداد الجهاز)"),
        LIGHT("light", "الوضع النهاري"),
        DARK("dark", "الوضع الليلي");

        companion object {
            fun fromPreference(value: String): Mode = entries.firstOrNull {
                it.preferenceValue == value
            } ?: SYSTEM
        }
    }

    fun mode(): Mode = Mode.fromPreference(AppRepository.themeMode())

    /** يقرأ لون النظام الحقيقي عند اختيار الوضع التلقائي. */
    fun isNight(context: Context): Boolean = when (mode()) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** تطبيق اختيار AppCompat قبل إنشاء أو إعادة إنشاء الواجهات. */
    fun applyTheme(context: Context) {
        val nightMode = when (mode()) {
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun setMode(context: Context, selected: Mode) {
        AppRepository.setThemeMode(selected.preferenceValue)
        applyTheme(context)
    }

    fun toggleTheme(context: Context) {
        // زر الشريط السفلي يوفّر تبديلًا سريعًا يدويًا. إذا كان المظهر تلقائيًا
        // يبدأ من العكس الفعلي لمظهر الجهاز حتى تكون النتيجة ظاهرة فورًا.
        setMode(context, if (isNight(context)) Mode.LIGHT else Mode.DARK)
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

    /** ألوان الكتابة المضمونة للحقول، منفصلة عن لون النص الثانوي للبطاقات. */
    fun inputText(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.input_text_night)
        else context.getColor(R.color.input_text_day)

    fun inputHint(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.input_hint_night)
        else context.getColor(R.color.input_hint_day)

    /** شريط التنقل السفلي */
    fun navBarColor(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.night_nav_bar)
        else context.getColor(R.color.day_nav_bar)

    /** لون label غير النشط في الشريط السفلي */
    fun inactiveLabel(context: Context): Int =
        if (isNight(context)) context.getColor(R.color.navigation_inactive_night)
        else context.getColor(R.color.navigation_inactive_day)

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
