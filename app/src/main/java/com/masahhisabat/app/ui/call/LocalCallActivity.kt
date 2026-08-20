package com.masahhisabat.app.ui.call

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.Button
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.masahhisabat.app.R
import com.masahhisabat.app.data.AppRepository
import com.masahhisabat.app.data.CallLog
import com.masahhisabat.app.data.LocalWebRtcEngine
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
    private var engine: LocalWebRtcEngine? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var micButton: Button? = null
    private var cameraButton: Button? = null
    private var switchCameraButton: Button? = null
    private var minimizeButton: Button? = null
    private var controlsLayout: LinearLayout? = null
    private var microphoneEnabled = true
    private var cameraEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = SessionStore.currentUser(this).orEmpty()
        peerUser = intent.getStringExtra(EXTRA_PEER_USER).orEmpty().ifBlank { "مستخدم" }
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty().ifBlank { "voice" }
        startedAt = System.currentTimeMillis()
        requestPermissionsIfNeeded()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val title = TextView(this).apply {
            text = if (mediaType == "video") "مكالمة فيديو محلية" else "مكالمة صوتية محلية"
            textSize = 22f
        }
        val peer = TextView(this).apply {
            text = "المستخدم: $peerUser"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        }
        statusView = TextView(this).apply {
            text = if (intent.hasExtra(EXTRA_INCOMING_SDP)) "مكالمة واردة من $peerUser — اضغط قبول للرد" else "جاهز للاتصال داخل الشبكة"
            textSize = 16f
        }
        muteIndicator = TextView(this).apply {
            text = "الميكروفون مكتوم"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(198, 40, 40))
            setPadding(22, 12, 22, 12)
            contentDescription = "تنبيه: الميكروفون مكتوم"
            visibility = View.GONE
        }
        cameraIndicator = TextView(this).apply {
            text = "الكاميرا متوقفة"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(217, 119, 6))
            setPadding(22, 12, 22, 12)
            contentDescription = "تنبيه: الكاميرا متوقفة"
            visibility = View.GONE
        }
        if (mediaType == "video") {
            remoteRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.BLACK) }
            localRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.DKGRAY) }
        }
        val accept = Button(this).apply {
            text = if (intent.hasExtra(EXTRA_INCOMING_SDP)) "قبول المكالمة" else "بدء المكالمة"
            setOnClickListener {
                startCall()
                isEnabled = false
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
        val end = Button(this).apply { text = "إنهاء المكالمة"; setOnClickListener { finishCall("ended") } }
        controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isEnabled = false
            setPadding(0, 16, 0, 16)
            addView(micButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cameraButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchCameraButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(title)
        root.addView(peer)
        root.addView(statusView)
        root.addView(muteIndicator, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(cameraIndicator, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (mediaType == "video") {
            root.addView(remoteRenderer, LinearLayout.LayoutParams(-1, 520))
            root.addView(localRenderer, LinearLayout.LayoutParams(-1, 220))
        }
        root.addView(accept)
        root.addView(controlsLayout)
        root.addView(minimizeButton)
        root.addView(end)
        setContentView(root)
    }

    private fun startCall() {
        if (engine != null) return
        val incoming = intent.getStringExtra(EXTRA_INCOMING_SDP)
        val log = CallLog(
            caller = if (incoming.isNullOrBlank()) currentUser else peerUser,
            callee = if (incoming.isNullOrBlank()) peerUser else currentUser,
            type = mediaType,
            direction = if (incoming.isNullOrBlank()) "outgoing" else "incoming",
            status = "ringing"
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
            onState = { state -> runOnUiThread { statusView?.text = "حالة الاتصال: $state" } }
        )
        controlsLayout?.isEnabled = true
        statusView?.text = if (incoming.isNullOrBlank()) "جارٍ الاتصال داخل الشبكة المحلية…" else "تم قبول المكالمة، جارٍ فتح الوسائط…"
        if (incoming.isNullOrBlank()) engine?.startOutgoing() else engine?.acceptIncoming(incoming)
        AppRepository.updateCallLog(log.id) { it.copy(status = "accepted") }
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
        return listOf(micAction, videoAction)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            ACTION_PIP_TOGGLE_MIC -> toggleMicrophone()
            ACTION_PIP_TOGGLE_VIDEO -> toggleCamera()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        val compact = isInPictureInPictureMode
        statusView?.visibility = if (compact) View.GONE else View.VISIBLE
        minimizeButton?.visibility = if (compact) View.GONE else View.VISIBLE
        controlsLayout?.visibility = if (compact) View.GONE else View.VISIBLE
        updateMuteIndicator()
        updateCameraIndicator()
        // في وضع PiP يتولى Android السحب وتغيير الحجم، وتبقى الوسائط والمحرك مستمرين.
        if (!compact) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun finishCall(status: String) {
        val ended = System.currentTimeMillis()
        logId?.let { id ->
            AppRepository.updateCallLog(id) {
                it.copy(status = status, endedAt = ended, durationSeconds = ((ended - startedAt) / 1000L).coerceAtLeast(0L))
            }
        }
        engine?.release()
        engine = null
        finish()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_MEDIA)
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onBackPressed() { finishCall("ended") }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER_USER = "peer_user"
        const val EXTRA_PEER_ADDRESS = "peer_address"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_INCOMING_SDP = "incoming_sdp"
        private const val REQUEST_MEDIA = 903
        private const val ACTION_PIP_TOGGLE_MIC = "com.masahhisabat.app.action.PIP_TOGGLE_MIC"
        private const val ACTION_PIP_TOGGLE_VIDEO = "com.masahhisabat.app.action.PIP_TOGGLE_VIDEO"
    }
}
