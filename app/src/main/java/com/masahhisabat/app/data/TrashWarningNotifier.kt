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
import com.masahhisabat.app.R
import com.masahhisabat.app.ui.settings.TrashActivity

/**
 * إشعار محلي واحد مجمّع لعناصر السلة القريبة من الحذف النهائي.
 * لا يرسل أي بيانات إلى الإنترنت، وتُحفظ حالة الإشعار في AppRepository لكل جهاز.
 */
object TrashWarningNotifier {
    private const val CHANNEL_ID = "trash_deletion_warning"
    private const val NOTIFICATION_ID = 30130

    fun show(context: Context, entries: List<TrashEntry>): Boolean {
        if (entries.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        return try {
            createChannel(context)
            val first = entries.first()
            val text = if (entries.size == 1) {
                context.getString(R.string.trash_warning_text_one, entryLabel(first))
            } else {
                context.getString(R.string.trash_warning_text_many, entries.size)
            }
            val detail = entries.take(3).joinToString("\n") { "• ${entryLabel(it)}" }
            val intent = Intent(context, TrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setColor(ContextCompat.getColor(context, R.color.primary))
                .setContentTitle(context.getString(R.string.trash_warning_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail"))
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.trash_warning_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.trash_warning_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun entryLabel(entry: TrashEntry): String = when (entry.type) {
        "group" -> "المجموعة «${entry.groupName}»"
        else -> "رسالة من مجموعة «${entry.groupName}»"
    }
}
