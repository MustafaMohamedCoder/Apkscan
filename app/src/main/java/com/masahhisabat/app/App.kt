package com.masahhisabat.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.masahhisabat.app.data.AppRepository
import java.io.PrintWriter
import java.io.StringWriter

/**
 * تهيئة عامة للتطبيق قبل أي نشاط، مع معالج استثناءات عام
 * يمنع الانهيار الصامت ويسجل السبب في ملف داخل الهاتف للتحليل.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRepository.initAppContext(this)
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
}
