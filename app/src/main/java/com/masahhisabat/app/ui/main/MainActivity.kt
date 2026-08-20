package com.masahhisabat.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.LoginActivity
import com.masahhisabat.app.ui.auth.LockActivity
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.groups.GroupsFragment
import com.masahhisabat.app.ui.home.HomeFragment
import com.masahhisabat.app.ui.scanner.ScannerFragment
import com.masahhisabat.app.ui.search.SearchFragment
import com.masahhisabat.app.ui.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private var lastTab = 0
    private var renderedTab = -1
    private val tabIds = intArrayOf(
        R.id.nav_home, R.id.nav_groups, R.id.nav_scanner,
        R.id.nav_search, R.id.nav_settings
    )
    private val labelIds = intArrayOf(
        R.id.nav_home_label, R.id.nav_groups_label, R.id.nav_scanner_label,
        R.id.nav_search_label, R.id.nav_settings_label
    )

    private val fragments = listOf<Fragment>(
        HomeFragment(),
        GroupsFragment(),
        ScannerFragment(),
        SearchFragment(),
        SettingsFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        AppRepository.initAppContext(this)
        if (SessionStore.currentUser(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            val listeners = View.OnClickListener { v ->
                val idx = tabIds.indexOf(v.id)
                if (idx < 0) return@OnClickListener
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                switchTab(idx)
            }
            for (id in tabIds) {
                findViewById<View>(id)?.setOnClickListener(listeners)
            }
            findViewById<View>(R.id.nav_theme)?.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                ThemeHelper.toggleTheme(this)
                recreate()
            }

            lastTab = savedInstanceState?.getInt("last_tab", 0) ?: 0
            switchTab(lastTab, animate = false)
            applyTheme()
            // تبدأ كل جلسة مزامنة شاملة ثنائية الاتجاه؛ ملف mustafa السلطوي يبقى متاحًا أيضًا للتوافق القديم.
            SyncManager.startAutomaticUserSync(this)
            SyncManager.startAutomaticDataSyncAfterLogin(this)
        } catch (e: Exception) {
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val file = java.io.File(filesDir, "crash_log.txt")
                file.writeText(
                    "MAIN_FAIL ${System.currentTimeMillis()}: ${e.message}\n$sw\n---\n" +
                        (if (file.exists()) file.readText().take(50_000) else "")
                )
            } catch (_: Exception) {}
            android.widget.Toast.makeText(this, "حدث خطأ: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (SessionStore.requiresUnlock(this)) {
            startActivity(Intent(this, LockActivity::class.java))
            return
        }
        if (SessionStore.currentUser(this) != null) {
            SyncManager.startAutomaticUserSync(this)
            SyncManager.startAutomaticDataSyncAfterLogin(this)
        }
    }

    private fun switchTab(index: Int, animate: Boolean = true) {
        lastTab = index
        // لمس التبويب المفتوح لا يعيد إنشاء Fragment أو يصفّر موضع القوائم.
        if (index != renderedTab) {
            val transaction = supportFragmentManager.beginTransaction()
            if (animate) {
                transaction
                    .setCustomAnimations(
                        android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in, android.R.anim.fade_out
                    )
            }
            transaction.replace(R.id.fragment_container, fragments[index]).commit()
            renderedTab = index
        }
        // تحديث مظهر التبويب النشط بحركة ناعمة
        val activeColor = ThemeHelper.accent(this)
        val inactiveColor = ThemeHelper.inactiveLabel(this)
        for (i in tabIds.indices) {
            val iconView = findViewById<View>(tabIds[i])?.let { findIconInView(it) }
            val labelView = findViewById<TextView>(labelIds[i])
            if (animate) {
                iconView?.animate()?.scaleX(if (i == index) 1.15f else 1f)?.scaleY(if (i == index) 1.15f else 1f)?.setDuration(180)?.start()
                labelView?.animate()?.scaleX(if (i == index) 1.08f else 1f)?.scaleY(if (i == index) 1.08f else 1f)?.setDuration(180)?.start()
            } else {
                iconView?.scaleX = if (i == index) 1.15f else 1f
                iconView?.scaleY = if (i == index) 1.15f else 1f
                labelView?.scaleX = if (i == index) 1.08f else 1f
                labelView?.scaleY = if (i == index) 1.08f else 1f
            }
            iconView?.setColorFilter(if (i == index) activeColor else inactiveColor)
            labelView?.setTextColor(if (i == index) activeColor else inactiveColor)
            labelView?.typeface = if (i == index) resources.getFont(R.font.tajawal_bold) else resources.getFont(R.font.tajawal_regular)
            val tabView = findViewById<View>(tabIds[i])
            // حاوية زجاجية وحدّ خفيف يوضحان القسم الحالي حتى عند انخفاض سطوع الشاشة.
            tabView?.setBackgroundResource(if (i == index) R.drawable.nav_item_active_bg else R.drawable.nav_item_bg)
            tabView?.isSelected = i == index
            tabView?.contentDescription = if (i == index) {
                "التبويب الحالي: ${labelView?.text ?: ""}"
            } else {
                labelView?.text
            }
        }
    }

    private fun findIconInView(parent: View): ImageView? {
        var target: ImageView? = null
        if (parent is ImageView) return parent
        if (parent is LinearLayout) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is ImageView) {
                    target = child
                    break
                }
            }
        }
        return target
    }
    private fun applyTheme() {
        window.decorView.setBackgroundResource(ThemeHelper.backgroundRes())
        findViewById<View>(R.id.fragment_container)?.setBackgroundResource(ThemeHelper.backgroundRes())
        val bar = findViewById<LinearLayout>(R.id.bottom_nav)
        bar?.setBackgroundResource(R.drawable.nav_bar_bg)
        bar?.backgroundTintList = null
        val night = ThemeHelper.isNight(this)
        findViewById<ImageView>(R.id.nav_theme_icon)?.apply {
            setImageResource(if (night) R.drawable.ic_sun_filled else R.drawable.ic_moon)
            setColorFilter(ThemeHelper.inactiveLabel(this@MainActivity))
            contentDescription = if (night) getString(R.string.switch_to_light_theme) else getString(R.string.switch_to_dark_theme)
        }
        findViewById<TextView>(R.id.nav_theme_label)?.apply {
            text = if (night) "نهاري" else "ليلي"
            setTextColor(ThemeHelper.inactiveLabel(this@MainActivity))
        }
        switchTab(lastTab, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("last_tab", lastTab)
    }
}
