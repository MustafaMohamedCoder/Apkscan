package com.masahhisabat.app

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.InvoiceReminderScheduler
import com.masahhisabat.app.data.SyncManager
import com.masahhisabat.app.data.TrashCleanupScheduler
import com.masahhisabat.app.image.DocumentEdgeDetector
import com.masahhisabat.app.ui.ThemeHelper
import com.masahhisabat.app.ui.auth.SessionStore
import com.masahhisabat.app.ui.auth.LockActivity
import java.io.PrintWriter
import java.io.StringWriter

/**
 * تهيئة عامة للتطبيق قبل أي نشاط، مع معالج استثناءات عام
 * يمنع الانهيار الصامت ويسجل السبب في ملف داخل الهاتف للتحليل.
 */
class App : Application() {
    private val foregroundHandler = Handler(Looper.getMainLooper())
    private var startedActivities = 0
    private val lockWhenBackgrounded = Runnable {
        if (startedActivities == 0 && AppRepository.hasAppLock()) SessionStore.lock(this)
    }
    override fun onCreate() {
        super.onCreate()
        AppRepository.initAppContext(this)
        // تحميل مكتبة الرؤية المضمنة محلياً. فشل التحميل لا يوقف التطبيق؛ يعود الماسح للقص اليدوي الآمن.
        DocumentEdgeDetector.initialize()
        // تنظيف السلة المؤجل لا يعمل إلا إذا أبقاه المستخدم مفعّلًا في الإعدادات.
        TrashCleanupScheduler.update(this)
        // تذكيرات الفواتير تعمل محلياً عبر مهمة يومية مرنة تراعي البطارية.
        InvoiceReminderScheduler.update(this)
        if (AppRepository.isAutoTrashPurgeEnabled()) {
            Thread { try { AppRepository.purgeExpiredTrash() } catch (_: Exception) { } }.start()
        }
        // يبقى الجهاز المستقبل مستعدًا لملف مستخدمي mustafa حتى قبل تسجيل الدخول.
        SyncManager.ensureServer(this)
        // يفحص الإصدار بين الأجهزة المفتوحة على الشبكة المحلية مرة واحدة عند بدء التطبيق.
        SyncManager.startAutomaticUpdateCheck(this)
        // يُطبَّق قبل عرض شاشة الدخول حتى يتبع التطبيق مظهر النظام من أول إطار.
        ThemeHelper.applyTheme(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                foregroundHandler.removeCallbacks(lockWhenBackgrounded)
                applyScreenPrivacy(activity)
                if (activity !is LockActivity && SessionStore.requiresUnlock(activity)) {
                    activity.startActivity(Intent(activity, LockActivity::class.java))
                }
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    foregroundHandler.postDelayed(lockWhenBackgrounded, AppRepository.appLockTimeoutMs())
                }
            }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) { applyScreenPrivacy(a) }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val file = java.io.File(filesDir, "crash_log.txt")
                file.writeText(
                    "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n$sw\n---\n" +
                        (file.readText().take(50_000))
                )
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    Toast.makeText(this, "حدث خطأ غير متوقع: ${throwable.message ?: throwable.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** يمنع لقطات الشاشة ومعاينة المحتوى في قائمة التطبيقات الحديثة عند اختيار المستخدم للحماية. */
    private fun applyScreenPrivacy(activity: Activity) {
        if (AppRepository.isScreenPrivacyEnabled()) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
