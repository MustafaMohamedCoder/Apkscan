package com.masahhisabat.app.ui.call

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.CallFeedback
import com.masahhisabat.app.data.CallLog
import com.masahhisabat.app.data.LocalCallRecoveryPolicy
import com.masahhisabat.app.data.LocalWebRtcEngine
import com.masahhisabat.app.data.NotificationEvent
import com.masahhisabat.app.ui.auth.SessionStore
import org.webrtc.SurfaceViewRenderer

/** شاشة مكالمة صوت/فيديو محلية؛ تبقى الإشارة والوسائط داخل الشبكة المحلية. */
class LocalCallActivity : Activity() {
    private var logId: String? = null
    private var startedAt = 0L
    private var mediaType = "voice"
    private var peerUser = "مستخدم"
    private var currentUser = ""
    private var statusView: TextView? = null
    private var muteIndicator: TextView? = null
    private var cameraIndicator: TextView? = null
    private var networkIndicator: TextView? = null
    private var durationView: TextView? = null
    private var engine: LocalWebRtcEngine? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var micButton: Button? = null
    private var cameraButton: Button? = null
    private var switchCameraButton: Button? = null
    private var minimizeButton: Button? = null
    private var acceptButton: Button? = null
    private var endButton: Button? = null
    private var retryConnectionButton: Button? = null
    private var recoveryRetryAvailable = false
    private var controlsLayout: LinearLayout? = null
    private var microphoneEnabled = true
    private var cameraEnabled = true
    private var networkQuality = LocalWebRtcEngine.NetworkQuality.CHECKING
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private var callStartedElapsed = 0L
    private var ending = false
    private var answerFeedbackDelivered = false
    private var mediaPermissionDialogVisible = false
    private var mediaPermissionRequestInFlight = false
    private var returningFromAppSettings = false
    private var permissionWaitPanel: LinearLayout? = null
    private var permissionWaitText: TextView? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private val callTimer = object : Runnable {
        override fun run() {
            updateDuration()
            callTimerHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = SessionStore.currentUser(this).orEmpty()
        peerUser = intent.getStringExtra(EXTRA_PEER_USER).orEmpty().ifBlank { "مستخدم" }
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty().ifBlank { "voice" }
        startedAt = System.currentTimeMillis()
        render()
        requestPermissionsIfNeeded()
    }

    /** لا يستأنف الاتصال تلقائيًا بعد الإعدادات؛ يوضح فقط نتيجة اختيار المستخدم ويترك البدء بيده. */
    override fun onResume() {
        super.onResume()
        if (!returningFromAppSettings) return
        returningFromAppSettings = false
        val granted = hasRequiredMediaPermissions()
        acceptButton?.isEnabled = true
        if (granted) {
            showMediaPermissionsGrantedFeedback()
        } else {
            statusView?.text = "لم تُفعّل كل الأذونات بعد — يمكنك فتح الإعدادات مجددًا أو المحاولة لاحقًا"
        }
    }

    /** تأكيد بصري قصير بعد الإعدادات؛ لا يستدعي محرك WebRTC ولا يبدأ الاتصال تلقائيًا. */
    private fun showMediaPermissionsGrantedFeedback() {
        val permissionSummary = if (mediaType == "video") "الميكروفون والكاميرا" else "الميكروفون"
        val message = "✓ تم تفعيل إذن $permissionSummary — اضغط لبدء الاتصال"
        statusView?.apply {
            text = message
            alpha = 0f
            translationY = resources.displayMetrics.density * 10f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        }
        Toast.makeText(this, "تم تفعيل الصلاحيات بنجاح", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = if (mediaType == "video") "مكالمة فيديو محلية" else "مكالمة صوتية محلية"
            textSize = 22f
        }
        val peer = TextView(this).apply {
            text = "المستخدم: $peerUser"
            textSize = 18f
            setPadding(0, dp(12), 0, dp(6))
        }
        statusView = TextView(this).apply {
            text = if (intent.hasExtra(EXTRA_INCOMING_SDP)) "مكالمة واردة من $peerUser — اضغط قبول للرد" else "جاهز للاتصال داخل الشبكة"
            textSize = 16f
        }
        retryConnectionButton = Button(this).apply {
            text = "إعادة محاولة الاتصال"
            visibility = View.GONE
            setOnClickListener { retryLocalConnection() }
        }
        permissionWaitPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(Color.rgb(8, 95, 100))
            contentDescription = "مؤشر انتظار قرار إذن الوسائط"
            visibility = View.GONE
            addView(ProgressBar(this@LocalCallActivity).apply {
                isIndeterminate = true
                contentDescription = "جارٍ الانتظار"
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            permissionWaitText = TextView(this@LocalCallActivity).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
                setPaddingRelative(16, 0, 0, 0)
            }
            addView(permissionWaitText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        muteIndicator = TextView(this).apply {
            text = "الميكروفون مكتوم"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(198, 40, 40))
            setPadding(dp(11), dp(6), dp(11), dp(6))
            contentDescription = "تنبيه: الميكروفون مكتوم"
            visibility = View.GONE
        }
        cameraIndicator = TextView(this).apply {
            text = "الكاميرا متوقفة"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(217, 119, 6))
            setPadding(dp(11), dp(6), dp(11), dp(6))
            contentDescription = "تنبيه: الكاميرا متوقفة"
            visibility = View.GONE
        }
        networkIndicator = TextView(this).apply {
            text = "جودة الشبكة: جارٍ القياس"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            contentDescription = "جودة الشبكة: جارٍ القياس"
            visibility = View.GONE
        }
        durationView = TextView(this).apply {
            text = "مدة المكالمة: 00:00"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(15, 118, 110))
            setPadding(dp(11), dp(5), dp(11), dp(5))
            contentDescription = "مدة المكالمة صفر دقيقة وصفر ثانية"
            visibility = View.GONE
        }
        if (mediaType == "video") {
            remoteRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.BLACK) }
            localRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.DKGRAY) }
        }
        acceptButton = Button(this).apply {
            text = if (intent.hasExtra(EXTRA_INCOMING_SDP)) "قبول المكالمة" else "بدء المكالمة"
            setOnClickListener {
                if (startCall()) {
                    if (!intent.getStringExtra(EXTRA_INCOMING_SDP).isNullOrBlank()) {
                        intent.getStringExtra(EXTRA_CALL_ID)?.let { callId ->
                            com.masahhisabat.app.data.LocalCallService.markIncomingCallHandled(applicationContext, callId)
                        }
                    }
                    isEnabled = false
                }
            }
        }
        micButton = Button(this).apply {
            text = "كتم الميكروفون"
            setOnClickListener { toggleMicrophone() }
        }
        cameraButton = Button(this).apply {
            text = "إيقاف الفيديو"
            visibility = if (mediaType == "video") View.VISIBLE else View.GONE
            setOnClickListener { toggleCamera() }
        }
        switchCameraButton = Button(this).apply {
            text = "تبديل الكاميرا"
            visibility = if (mediaType == "video") View.VISIBLE else View.GONE
            setOnClickListener { switchCamera() }
        }
        minimizeButton = Button(this).apply {
            text = "تصغير المكالمة"
            setOnClickListener { minimizeCall() }
        }
        endButton = Button(this).apply { text = "إنهاء المكالمة"; setOnClickListener { finishCall("ended", "أنهى المستخدم المكالمة") } }
        controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isEnabled = false
            setPadding(0, dp(8), 0, dp(8))
            addView(micButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cameraButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchCameraButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(title)
        root.addView(peer)
        root.addView(statusView)
        root.addView(retryConnectionButton)
        root.addView(permissionWaitPanel, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(muteIndicator, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(cameraIndicator, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(networkIndicator, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(durationView, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (mediaType == "video") {
            root.addView(remoteRenderer, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(localRenderer, LinearLayout.LayoutParams(-1, dp(120)))
        }
        root.addView(acceptButton)
        root.addView(controlsLayout)
        root.addView(minimizeButton)
        root.addView(endButton)
        setContentView(root)
    }

    private fun startCall(): Boolean {
        if (engine != null) return true
        if (!hasRequiredMediaPermissions()) {
            requestPermissionsIfNeeded()
            statusView?.text = "يلزم السماح بالميكروفون${if (mediaType == "video") " والكاميرا" else ""} لبدء المكالمة"
            Toast.makeText(this, "امنح أذونات المكالمة المطلوبة ثم حاول مجددًا", Toast.LENGTH_LONG).show()
            return false
        }
        startedAt = System.currentTimeMillis()
        callStartedElapsed = SystemClock.elapsedRealtime()
        startDurationTimer()
        val incoming = intent.getStringExtra(EXTRA_INCOMING_SDP)
        if (incoming.isNullOrBlank()) {
            CallFeedback.startOutgoingWaiting(applicationContext)
        } else {
            CallFeedback.stopTone()
            clearIncomingNotification()
        }
        val log = CallLog(
            caller = if (incoming.isNullOrBlank()) currentUser else peerUser,
            callee = if (incoming.isNullOrBlank()) peerUser else currentUser,
            type = mediaType,
            direction = if (incoming.isNullOrBlank()) "outgoing" else "incoming",
            status = "ringing",
            startedAt = startedAt,
            latencyMs = intent.getLongExtra(EXTRA_NETWORK_LATENCY_MS, -1L).takeIf { it >= 0L },
            peerAddress = intent.getStringExtra(EXTRA_PEER_ADDRESS)
        )
        logId = log.id
        AppRepository.addCallLog(log)
        engine = LocalWebRtcEngine(
            context = this,
            currentUser = currentUser,
            peerUser = peerUser,
            callId = log.id,
            mediaType = mediaType,
            localRenderer = localRenderer,
            remoteRenderer = remoteRenderer,
            onState = { state -> runOnUiThread {
                if (isFinishing || isDestroyed || ending) return@runOnUiThread
                statusView?.text = "حالة الاتصال: $state"
                when (state) {
                    "CONNECTED" -> {
                        CallFeedback.stopTone()
                        if (!answerFeedbackDelivered) {
                            answerFeedbackDelivered = true
                            CallFeedback.vibrateAnswered(applicationContext)
                        }
                    }
                    "FAILED", "CLOSED" -> CallFeedback.stopTone()
                }
            } },
            onNetworkQuality = { quality ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || ending) return@runOnUiThread
                    networkQuality = quality
                    updateNetworkIndicator()
                }
            },
            onRecoveryState = { decision ->
                runOnUiThread { applyRecoveryUi(decision) }
            },
            initialLatencyMs = log.latencyMs,
            onDiagnostic = { entry ->
                AppRepository.updateCallLog(log.id) { current ->
                    current.copy(diagnosticLog = appendDiagnostic(current.diagnosticLog, entry))
                }
            },
            onCallEnded = { reason, diagnostics ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || ending) return@runOnUiThread
                    finishCall("failed", reason, notifyPeer = false, diagnosticLog = diagnostics)
                }
            }
        )
        controlsLayout?.isEnabled = true
        statusView?.text = if (incoming.isNullOrBlank()) "جارٍ الاتصال داخل الشبكة المحلية…" else "تم قبول المكالمة، جارٍ فتح الوسائط…"
        if (incoming.isNullOrBlank()) engine?.startOutgoing() else engine?.acceptIncoming(incoming)
        AppRepository.updateCallLog(log.id) { it.copy(status = "accepted") }
        return true
    }

    private fun startDurationTimer() {
        callTimerHandler.removeCallbacks(callTimer)
        updateDuration()
        callTimerHandler.postDelayed(callTimer, 1000L)
    }

    private fun stopDurationTimer() = callTimerHandler.removeCallbacks(callTimer)

    private fun updateDuration() {
        if (callStartedElapsed <= 0L) return
        val seconds = ((SystemClock.elapsedRealtime() - callStartedElapsed) / 1000L).coerceAtLeast(0L)
        val text = "مدة المكالمة: ${formatDuration(seconds)}"
        durationView?.apply {
            this.text = text
            contentDescription = text
            visibility = if (engine != null) View.VISIBLE else View.GONE
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remainder = seconds % 60L
        return if (hours > 0L) "%02d:%02d:%02d".format(hours, minutes, remainder) else "%02d:%02d".format(minutes, remainder)
    }

    private fun toggleMicrophone() {
        microphoneEnabled = !(engine?.setMicrophoneEnabled(microphoneEnabled) ?: microphoneEnabled)
        micButton?.text = if (microphoneEnabled) "كتم الميكروفون" else "تشغيل الميكروفون"
        statusView?.text = if (microphoneEnabled) "الميكروفون يعمل" else "الميكروفون مكتوم"
        updateMuteIndicator()
        updatePictureInPictureActions()
    }

    private fun toggleCamera() {
        if (mediaType != "video") return
        cameraEnabled = !(engine?.setCameraEnabled(cameraEnabled) ?: cameraEnabled)
        cameraButton?.text = if (cameraEnabled) "إيقاف الفيديو" else "تشغيل الفيديو"
        localRenderer?.visibility = if (cameraEnabled) View.VISIBLE else View.INVISIBLE
        statusView?.text = if (cameraEnabled) "الفيديو يعمل" else "الفيديو متوقف"
        updateCameraIndicator()
        updatePictureInPictureActions()
    }

    private fun switchCamera() {
        if (mediaType != "video") return
        engine?.switchCamera()
        statusView?.text = "تم التبديل بين الكاميرا الأمامية والخلفية"
    }

    /** يشرح للمستخدم النافذة الزمنية للاستعادة ولا يعرض إعادة المحاولة إلا بعد المهلة. */
    private fun applyRecoveryUi(decision: LocalCallRecoveryPolicy.Decision) {
        if (isFinishing || isDestroyed || ending) return
        decision.userMessage?.let { statusView?.text = it }
        recoveryRetryAvailable = decision.allowsManualRetry
        retryConnectionButton?.visibility = if (recoveryRetryAvailable && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        if (decision.startsRecoveryWindow || decision.allowsManualRetry) {
            networkQuality = LocalWebRtcEngine.NetworkQuality.UNSTABLE
            updateNetworkIndicator()
        }
    }

    private fun retryLocalConnection() {
        if (engine?.retryLocalConnection() == true) {
            recoveryRetryAvailable = false
            retryConnectionButton?.visibility = View.GONE
        } else {
            statusView?.text = "لا توجد محاولة استعادة معلقة. استمر في المكالمة أو أنشئ مكالمة جديدة."
        }
    }

    private fun minimizeCall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            statusView?.text = "التصغير غير مدعوم على هذا الإصدار"
            return
        }
        if (engine == null) {
            statusView?.text = "ابدأ المكالمة أولًا ثم صغّر نافذتها"
            return
        }
        enterPictureInPictureMode(buildPictureInPictureParams())
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val compactRatio = if (mediaType == "video") Rational(16, 9) else Rational(4, 3)
        val builder = PictureInPictureParams.Builder()
            // PiP النظامي يسمح بسحب النافذة إلى أي زاوية وتغيير حجمها بإيماءة القرص.
            .setAspectRatio(compactRatio)
            .setActions(buildPictureInPictureActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // يتيح للمستخدم توسيع نافذة المكالمة ثم تصغيرها بحرية دون فرض حجم واحد.
            builder.setExpandedAspectRatio(compactRatio)
        }
        return builder.build()
    }

    private fun buildPictureInPictureActions(): List<RemoteAction> {
        val micTitle = if (microphoneEnabled) "كتم الميكروفون" else "الميكروفون مكتوم — تشغيل"
        val videoTitle = when {
            mediaType != "video" -> "الفيديو غير متاح في المكالمة الصوتية"
            cameraEnabled -> "إيقاف الفيديو"
            else -> "تشغيل الفيديو"
        }
        val micAction = RemoteAction(
            Icon.createWithResource(this, if (microphoneEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off),
            micTitle,
            micTitle,
            createPipActionIntent(ACTION_PIP_TOGGLE_MIC, 301)
        )
        val videoAction = RemoteAction(
            Icon.createWithResource(this, if (cameraEnabled) R.drawable.ic_video_call else R.drawable.ic_video_off),
            videoTitle,
            videoTitle,
            createPipActionIntent(ACTION_PIP_TOGGLE_VIDEO, 302)
        ).apply { isEnabled = mediaType == "video" }
        val fullScreenAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_open_in_full),
            "فتح المكالمة بالحجم الكامل",
            "فتح المكالمة بالحجم الكامل",
            createPipActionIntent(ACTION_PIP_RETURN_FULL, 303)
        )
        val endAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_call_end),
            "إنهاء المكالمة",
            "إنهاء المكالمة وإنهاء الوسائط المحلية",
            createPipActionIntent(ACTION_PIP_END_CALL, 304)
        )
        return listOf(micAction, videoAction, fullScreenAction, endAction)
    }

    private fun createPipActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LocalCallActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updatePictureInPictureActions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
            setPictureInPictureParams(buildPictureInPictureParams())
        }
    }

    /** شارة حمراء داخل محتوى PiP تمنع الالتباس عندما يكون الصوت المحلي متوقفًا. */
    private fun updateMuteIndicator() {
        muteIndicator?.visibility = if (isInPictureInPictureMode && !microphoneEnabled) View.VISIBLE else View.GONE
    }

    /** شارة كهرمانية داخل محتوى PiP توضح أن بث الكاميرا المحلي متوقف. */
    private fun updateCameraIndicator() {
        cameraIndicator?.visibility = if (isInPictureInPictureMode && mediaType == "video" && !cameraEnabled) View.VISIBLE else View.GONE
    }

    /** يظهر في PiP فقط كي تبقى نافذة المكالمة الكاملة مركزة على الوسائط وأزرارها. */
    private fun updateNetworkIndicator() {
        val qualityUi = when (networkQuality) {
            LocalWebRtcEngine.NetworkQuality.GOOD -> Triple("جودة الشبكة: جيدة", Color.rgb(13, 148, 136), "جودة الشبكة جيدة")
            LocalWebRtcEngine.NetworkQuality.CHECKING -> Triple("جودة الشبكة: جارٍ القياس", Color.rgb(8, 145, 178), "يجري قياس جودة الشبكة")
            LocalWebRtcEngine.NetworkQuality.UNSTABLE -> Triple("جودة الشبكة: غير مستقرة", Color.rgb(217, 119, 6), "تنبيه: جودة الشبكة غير مستقرة")
            LocalWebRtcEngine.NetworkQuality.POOR -> Triple("جودة الشبكة: ضعيفة", Color.rgb(198, 40, 40), "تنبيه: جودة الشبكة ضعيفة")
        }
        networkIndicator?.apply {
            text = qualityUi.first
            setBackgroundColor(qualityUi.second)
            contentDescription = qualityUi.third
            visibility = if (engine != null && (isInPictureInPictureMode || networkQuality != LocalWebRtcEngine.NetworkQuality.GOOD)) View.VISIBLE else View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            ACTION_PIP_TOGGLE_MIC -> toggleMicrophone()
            ACTION_PIP_TOGGLE_VIDEO -> toggleCamera()
            ACTION_PIP_RETURN_FULL -> returnToFullCall()
            ACTION_PIP_END_CALL -> finishCall("ended", "أنهى المستخدم المكالمة من النافذة المصغّرة")
        }
    }

    /** يعيد مهمة المكالمة نفسها إلى المقدمة، فيخرج من PiP دون إنشاء مكالمة أو محرك جديدين. */
    private fun returnToFullCall() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        manager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val compact = isInPictureInPictureMode
        statusView?.visibility = if (compact) View.GONE else View.VISIBLE
        minimizeButton?.visibility = if (compact) View.GONE else View.VISIBLE
        controlsLayout?.visibility = if (compact) View.GONE else View.VISIBLE
        retryConnectionButton?.visibility = if (!compact && recoveryRetryAvailable) View.VISIBLE else View.GONE
        acceptButton?.visibility = if (compact) View.GONE else View.VISIBLE
        endButton?.visibility = if (compact) View.GONE else View.VISIBLE
        updateMuteIndicator()
        updateCameraIndicator()
        updateNetworkIndicator()
        updateDuration()
        // في وضع PiP يتولى Android السحب وتغيير الحجم، وتبقى الوسائط والمحرك مستمرين.
        if (!compact) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    private fun appendDiagnostic(current: String?, entry: String): String {
        return (current.orEmpty().lineSequence().filter { it.isNotBlank() }.toList() + entry)
            .takeLast(80)
            .joinToString("\n")
    }

    private fun finishCall(
        status: String,
        reason: String = "انتهت المكالمة",
        notifyPeer: Boolean = true,
        diagnosticLog: String? = null
    ) {
        if (ending) return
        ending = true
        stopDurationTimer()
        CallFeedback.stopTone()
        clearIncomingNotification()
        CallFeedback.playCallEnded(applicationContext)
        val ended = System.currentTimeMillis()
        val activeEngine = engine
        val finalDiagnostics = diagnosticLog?.takeIf { it.isNotBlank() }
            ?: activeEngine?.diagnosticLog()?.takeIf { it.isNotBlank() }
        logId?.let { id ->
            AppRepository.updateCallLog(id) {
                it.copy(
                    status = status,
                    endedAt = ended,
                    durationSeconds = ((ended - startedAt) / 1000L).coerceAtLeast(0L),
                    endReason = reason,
                    diagnosticLog = finalDiagnostics ?: it.diagnosticLog
                )
            }
        }
        val iceFailure = status == "failed" && (
            reason.contains("ICE", ignoreCase = true) ||
                finalDiagnostics?.contains("ICE", ignoreCase = true) == true
            )
        if (iceFailure && AppRepository.recordIceFailureAndShouldAlert()) {
            AppRepository.addNotification(
                NotificationEvent(
                    title = "تنبيه اتصال محلي",
                    body = "تكرر تعذر فتح اتصال ICE ثلاث مرات خلال 15 دقيقة. راجع تشخيص المكالمات والشبكة المحلية.",
                    type = "call_diagnostic",
                    actor = currentUser
                )
            )
            Toast.makeText(this, "تكرر تعذر الاتصال محليًا — راجع سجل التشخيص", Toast.LENGTH_LONG).show()
        }
        engine = null
        if (notifyPeer) activeEngine?.endLocalCall() else activeEngine?.release()
        finish()
    }

    private fun requiredMediaPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (mediaType == "video") add(Manifest.permission.CAMERA)
    }

    private fun hasRequiredMediaPermissions(): Boolean = requiredMediaPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        val needed = requiredMediaPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty() || mediaPermissionDialogVisible || mediaPermissionRequestInFlight || isFinishing) return

        val needsCamera = needed.contains(Manifest.permission.CAMERA)
        val permissionSummary = if (needsCamera) "الميكروفون والكاميرا" else "الميكروفون"
        val purpose = if (needsCamera) {
            "سنستخدم الميكروفون للصوت والكاميرا لإرسال الفيديو للطرف الآخر داخل الشبكة المحلية فقط."
        } else {
            "سنستخدم الميكروفون لإرسال صوتك للطرف الآخر داخل الشبكة المحلية فقط."
        }

        mediaPermissionDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle("قبل بدء المكالمة")
            .setMessage("تحتاج هذه المكالمة إذن $permissionSummary. $purpose لن يبدأ الاتصال قبل موافقتك.")
            .setNegativeButton("ليس الآن") { _, _ ->
                mediaPermissionDialogVisible = false
                setMediaPermissionWaiting(false)
                acceptButton?.isEnabled = true
                statusView?.text = "لم يتم طلب إذن $permissionSummary — يمكنك منحه لاحقًا عند المتابعة"
            }
            .setPositiveButton("متابعة") { _, _ ->
                mediaPermissionDialogVisible = false
                setMediaPermissionWaiting(true, permissionSummary)
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_MEDIA)
            }
            .setOnCancelListener {
                mediaPermissionDialogVisible = false
                setMediaPermissionWaiting(false)
                acceptButton?.isEnabled = true
                statusView?.text = "تم إلغاء طلب الإذن — لن تبدأ المكالمة قبل منح الإذن المطلوب"
            }
            .show()
    }

    /** يعرض حالة صريحة بعد فتح نافذة أندرويد حتى يعرف المستخدم أن التطبيق ينتظر قراره. */
    private fun setMediaPermissionWaiting(waiting: Boolean, permissionSummary: String = "الأذونات المطلوبة") {
        mediaPermissionRequestInFlight = waiting
        permissionWaitPanel?.visibility = if (waiting) View.VISIBLE else View.GONE
        if (waiting) {
            val message = "بانتظار قرارك بشأن إذن $permissionSummary…"
            permissionWaitText?.text = message
            permissionWaitPanel?.contentDescription = message
            acceptButton?.isEnabled = false
            statusView?.text = "تم إرسال طلب الإذن — اختر السماح أو الرفض من نافذة النظام"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        setMediaPermissionWaiting(false)
        val granted = hasRequiredMediaPermissions()
        acceptButton?.isEnabled = granted
        if (granted) {
            statusView?.text = "تم منح أذونات المكالمة — يمكنك البدء"
        } else {
            showMediaPermissionFallback()
        }
    }

    /** يبقي مسار الإعدادات متاحًا بعد أي رفض، حتى لو كان أندرويد يسمح بإعادة الطلب. */
    private fun showMediaPermissionFallback() {
        val permissionSummary = if (mediaType == "video") "الميكروفون والكاميرا" else "الميكروفون"
        val canRequestAgain = requiredMediaPermissions().any { shouldShowRequestPermissionRationale(it) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("يلزم إذن $permissionSummary للمكالمة")
            .setMessage("تم رفض الإذن. افتح إعدادات التطبيق واسمح بـ $permissionSummary لتشغيل المكالمة محليًا، ولن يبدأ الاتصال تلقائيًا عند الرجوع.")
            .setNegativeButton("ليس الآن") { _, _ ->
                acceptButton?.isEnabled = true
                statusView?.text = "يمكنك منح إذن $permissionSummary من الإعدادات عند الحاجة"
            }
            .setPositiveButton("فتح الإعدادات") { _, _ -> openAppSettings() }

        if (canRequestAgain) {
            dialog.setNeutralButton("إعادة طلب الإذن") { _, _ -> requestPermissionsIfNeeded() }
        }
        dialog.show()
    }

    private fun openAppSettings() {
        setMediaPermissionWaiting(false)
        returningFromAppSettings = true
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        } catch (_: Exception) {
            returningFromAppSettings = false
            acceptButton?.isEnabled = true
            statusView?.text = "تعذر فتح إعدادات التطبيق — يمكنك تعديل الأذونات من إعدادات الجهاز"
            Toast.makeText(this, "تعذر فتح إعدادات التطبيق", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopDurationTimer()
        CallFeedback.stopTone()
        if (!ending && !isChangingConfigurations) {
            logId?.let { id ->
                val ended = System.currentTimeMillis()
                val diagnostics = engine?.diagnosticLog()?.takeIf { it.isNotBlank() }
                AppRepository.updateCallLog(id) {
                    it.copy(
                        status = "ended",
                        endedAt = ended,
                        durationSeconds = ((ended - startedAt) / 1000L).coerceAtLeast(0L),
                        endReason = "أُغلقت شاشة المكالمة",
                        diagnosticLog = diagnostics ?: it.diagnosticLog
                    )
                }
            }
        }
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onBackPressed() { finishCall("ended", "أنهى المستخدم المكالمة") }

    private fun clearIncomingNotification() {
        intent.getStringExtra(EXTRA_CALL_ID)?.let { callId ->
            getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER_USER = "peer_user"
        const val EXTRA_PEER_ADDRESS = "peer_address"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_NETWORK_LATENCY_MS = "network_latency_ms"
        const val EXTRA_INCOMING_SDP = "incoming_sdp"
        private const val REQUEST_MEDIA = 903
        private const val ACTION_PIP_TOGGLE_MIC = "com.masahhisabat.app.action.PIP_TOGGLE_MIC"
        private const val ACTION_PIP_TOGGLE_VIDEO = "com.masahhisabat.app.action.PIP_TOGGLE_VIDEO"
        private const val ACTION_PIP_RETURN_FULL = "com.masahhisabat.app.action.PIP_RETURN_FULL"
        private const val ACTION_PIP_END_CALL = "com.masahhisabat.app.action.PIP_END_CALL"
    }
}
