package com.masahhisabat.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.masahhisabat.app.R
import com.masahhisabat.app.ui.main.MainActivity
import java.util.concurrent.TimeUnit

/**
 * يفحص تذكيرات الفواتير محلياً مرة يومياً. الموعد مرن عمداً كي يراعي توفير البطارية،
 * ولا يرسل أي اسم فاتورة أو بيانات إلى الإنترنت.
 */
class InvoiceReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        AppRepository.initAppContext(applicationContext)
        val reminders = AppRepository.dueInvoiceReminders()
        if (InvoiceReminderNotifier.show(applicationContext, reminders)) {
            AppRepository.markInvoiceRemindersShown(reminders)
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

object InvoiceReminderScheduler {
    private const val WORK_NAME = "masah_invoice_reminders"

    fun update(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!AppRepository.areInvoiceRemindersEnabled()) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<InvoiceReminderWorker>(1, TimeUnit.DAYS).build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

private object InvoiceReminderNotifier {
    private const val CHANNEL_ID = "invoice_reminders"
    private const val NOTIFICATION_ID = 30140

    fun show(context: Context, reminders: List<Pair<Group, InvoiceItem>>): Boolean {
        if (reminders.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            createChannel(context)
            val first = reminders.first().second
            val title = first.storeName ?: first.text ?: context.getString(R.string.invoice_reminder_default_label)
            val content = if (reminders.size == 1) {
                context.getString(R.string.invoice_reminder_one, title)
            } else {
                context.getString(R.string.invoice_reminder_many, reminders.size)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setContentTitle(context.getString(R.string.invoice_reminder_title))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.invoice_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.invoice_reminder_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
