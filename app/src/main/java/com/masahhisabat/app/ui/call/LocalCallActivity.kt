package com.masahhisabat.app.ui.call

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private var engine: LocalWebRtcEngine? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var micButton: Button? = null
    private var cameraButton: Button? = null
    private var switchCameraButton: Button? = null
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
        if (mediaType == "video") {
            remoteRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.BLACK) }
            localRenderer = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.DKGRAY) }
            root.addView(remoteRenderer, LinearLayout.LayoutParams(-1, 520))
            root.addView(localRenderer, LinearLayout.LayoutParams(-1, 220))
        }
        val accept = Button(this).apply {
            text = if (intent.hasExtra(EXTRA_INCOMING_SDP)) "قبول المكالمة" else "بدء المكالمة"
            setOnClickListener {
                startCall()
                isEnabled = false
            }
        }
        micButton = Button(this).apply {
            text = "كتم الصوت"
            setOnClickListener { toggleMicrophone() }
        }
        cameraButton = Button(this).apply {
            text = "إيقاف الكاميرا"
            visibility = if (mediaType == "video") View.VISIBLE else View.GONE
            setOnClickListener { toggleCamera() }
        }
        switchCameraButton = Button(this).apply {
            text = "تبديل الكاميرا"
            visibility = if (mediaType == "video") View.VISIBLE else View.GONE
            setOnClickListener { switchCamera() }
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
        root.addView(accept)
        root.addView(controlsLayout)
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
        micButton?.text = if (microphoneEnabled) "كتم الصوت" else "تشغيل الصوت"
        statusView?.text = if (microphoneEnabled) "الميكروفون يعمل" else "الميكروفون مكتوم"
    }

    private fun toggleCamera() {
        if (mediaType != "video") return
        cameraEnabled = !(engine?.setCameraEnabled(cameraEnabled) ?: cameraEnabled)
        cameraButton?.text = if (cameraEnabled) "إيقاف الكاميرا" else "تشغيل الكاميرا"
        localRenderer?.visibility = if (cameraEnabled) View.VISIBLE else View.INVISIBLE
        statusView?.text = if (cameraEnabled) "الكاميرا تعمل" else "الكاميرا متوقفة"
    }

    private fun switchCamera() {
        if (mediaType != "video") return
        engine?.switchCamera()
        statusView?.text = "تم التبديل بين الكاميرا الأمامية والخلفية"
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
    }
}
