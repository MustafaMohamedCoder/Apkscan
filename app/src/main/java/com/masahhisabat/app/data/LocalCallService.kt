package com.masahhisabat.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.masahhisabat.app.R
import com.masahhisabat.app.ui.call.LocalCallActivity
import com.masahhisabat.app.ui.auth.SessionStore

/**
 * خدمة أمامية للمكالمات المحلية. تبقي مستمع الإشارة وموعد المزامنة فعالين
 * عندما ينتقل التطبيق إلى الخلفية، مع احترام قيود الطاقة في أجهزة هواوي.
 */
class LocalCallService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingMissedCalls = mutableMapOf<String, Runnable>()
    private val handledIncomingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(EXTRA_HANDLED_CALL_ID)?.let(::cancelMissedCallTimeout)
        }
    }
    private val signalListener: (CallSignal, String) -> Unit = { signal, address ->
        if (signal.toUser == SessionStore.currentUser(this).orEmpty()) {
            when (signal.kind) {
                "offer" -> showIncomingCall(signal, address)
                "room_invite" -> showIncomingRoomInvite(signal, address)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_ID, ongoingNotification())
        SyncManager.startServer(applicationContext)
        SyncManager.addCallSignalListener(signalListener)
        ContextCompat.registerReceiver(this, handledIncomingReceiver, IntentFilter(ACTION_INCOMING_CALL_HANDLED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        SyncManager.removeCallSignalListener(signalListener)
        unregisterReceiver(handledIncomingReceiver)
        pendingMissedCalls.values.forEach(mainHandler::removeCallbacks)
        pendingMissedCalls.clear()
        CallFeedback.stopTone()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showIncomingCall(signal: CallSignal, address: String) {
        CallFeedback.startIncomingRinging(applicationContext)
        cancelMissedCallTimeout(signal.callId)
        val timeout = Runnable { recordMissedCall(signal) }
        pendingMissedCalls[signal.callId] = timeout
        mainHandler.postDelayed(timeout, MISSED_CALL_TIMEOUT_MS)
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

    private fun showIncomingRoomInvite(signal: CallSignal, address: String) {
        val intent = Intent(this, LocalCallActivity::class.java).apply {
            putExtra(LocalCallActivity.EXTRA_CALL_ID, signal.callId)
            putExtra(LocalCallActivity.EXTRA_ROOM_ID, signal.roomId)
            putExtra(LocalCallActivity.EXTRA_PEER_USER, signal.fromUser)
            putExtra(LocalCallActivity.EXTRA_PEER_ADDRESS, address)
            putExtra(LocalCallActivity.EXTRA_MEDIA_TYPE, signal.mediaType)
            putExtra(LocalCallActivity.EXTRA_ROOM_PARTICIPANTS, signal.participants.joinToString(","))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, (signal.callId + signal.fromUser).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("دعوة إلى مكالمة جماعية")
            .setContentText("${signal.fromUser} دعاك إلى غرفة محلية — اضغط للانضمام")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java).notify((signal.callId + signal.fromUser).hashCode(), notification)
    }

    private fun cancelMissedCallTimeout(callId: String) {
        pendingMissedCalls.remove(callId)?.let(mainHandler::removeCallbacks)
    }

    private fun recordMissedCall(signal: CallSignal) {
        pendingMissedCalls.remove(signal.callId)
        CallFeedback.stopTone()
        getSystemService(NotificationManager::class.java).cancel(signal.callId.hashCode())
        if (AppRepository.callLogs().any { it.id == signal.callId }) return
        val now = System.currentTimeMillis()
        AppRepository.addCallLog(CallLog(
            id = signal.callId,
            caller = signal.fromUser,
            callee = signal.toUser,
            type = signal.mediaType,
            direction = "incoming",
            status = "missed",
            startedAt = signal.createdAt,
            endedAt = now,
            endReason = "لم يتم الرد"
        ))
        AppRepository.addNotification(NotificationEvent(
            title = "مكالمة فائتة من ${signal.fromUser}",
            body = if (signal.mediaType == "video") "لم يتم الرد على مكالمة فيديو" else "لم يتم الرد على مكالمة صوتية",
            type = "missed_call",
            actor = signal.fromUser
        ))
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
        const val ACTION_INCOMING_CALL_HANDLED = "com.masahhisabat.app.INCOMING_CALL_HANDLED"
        const val EXTRA_HANDLED_CALL_ID = "handled_call_id"
        private const val SERVICE_ID = 4821
        private const val SERVICE_CHANNEL = "local_sync_service"
        private const val CALL_CHANNEL = "local_calls"
        private const val MISSED_CALL_TIMEOUT_MS = 30_000L

        fun markIncomingCallHandled(context: Context, callId: String) {
            context.sendBroadcast(Intent(ACTION_INCOMING_CALL_HANDLED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_HANDLED_CALL_ID, callId)
            })
        }

        fun start(context: android.content.Context) {
            val intent = Intent(context, LocalCallService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
