package com.masahhisabat.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.masahhisabat.app.R
import com.masahhisabat.app.ui.call.LocalCallActivity
import com.masahhisabat.app.ui.auth.SessionStore

/**
 * خدمة أمامية للمكالمات المحلية. تبقي مستمع الإشارة وموعد المزامنة فعالين
 * عندما ينتقل التطبيق إلى الخلفية، مع احترام قيود الطاقة في أجهزة هواوي.
 */
class LocalCallService : Service() {
    private val signalListener: (CallSignal, String) -> Unit = { signal, address ->
        if (signal.kind == "offer" && signal.toUser == SessionStore.currentUser(this).orEmpty()) showIncomingCall(signal, address)
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_ID, ongoingNotification())
        SyncManager.startServer(applicationContext)
        SyncManager.addCallSignalListener(signalListener)
    }

    override fun onDestroy() {
        SyncManager.removeCallSignalListener(signalListener)
        CallFeedback.stopTone()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showIncomingCall(signal: CallSignal, address: String) {
        CallFeedback.startIncomingRinging(applicationContext)
        val intent = Intent(this, LocalCallActivity::class.java).apply {
            putExtra(LocalCallActivity.EXTRA_CALL_ID, signal.callId)
            putExtra(LocalCallActivity.EXTRA_PEER_USER, signal.fromUser)
            putExtra(LocalCallActivity.EXTRA_PEER_ADDRESS, address)
            putExtra(LocalCallActivity.EXTRA_MEDIA_TYPE, signal.mediaType)
            putExtra(LocalCallActivity.EXTRA_INCOMING_SDP, signal.sdp)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, signal.callId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (signal.mediaType == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة"
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText("${signal.fromUser} — اضغط للرد")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java).notify(signal.callId.hashCode(), notification)
    }

    private fun ongoingNotification(): Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_sync)
        .setContentTitle("الاتصال المحلي يعمل")
        .setContentText("المزامنة وإشعارات المكالمات مفعّلة داخل الشبكة")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            SERVICE_CHANNEL, "المزامنة والاتصال المحلي", NotificationManager.IMPORTANCE_LOW
        ))
        manager.createNotificationChannel(NotificationChannel(
            CALL_CHANNEL, "المكالمات المحلية", NotificationManager.IMPORTANCE_HIGH
        ))
    }

    companion object {
        private const val SERVICE_ID = 4821
        private const val SERVICE_CHANNEL = "local_sync_service"
        private const val CALL_CHANNEL = "local_calls"

        fun start(context: android.content.Context) {
            val intent = Intent(context, LocalCallService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
