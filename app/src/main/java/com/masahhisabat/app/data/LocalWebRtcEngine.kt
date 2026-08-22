package com.masahhisabat.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList

/** محرك WebRTC محلي؛ لا ينقل الوسائط عبر السحابة، وتبقى الإشارة داخل الشبكة المحلية. */
class LocalWebRtcEngine(
    private val context: Context,
    private val currentUser: String,
    private val peerUser: String,
    private val callId: String,
    private val mediaType: String,
    private val localRenderer: SurfaceViewRenderer?,
    private val remoteRenderer: SurfaceViewRenderer?,
    private val onState: (String) -> Unit,
    private val onNetworkQuality: (NetworkQuality) -> Unit = {},
    private val onRecoveryState: (LocalCallRecoveryPolicy.Decision) -> Unit = {},
    private val initialLatencyMs: Long? = null,
    private val onDiagnostic: (String) -> Unit = {},
    private val onCallEnded: (String, String) -> Unit = { _, _ -> },
    private val roomId: String = callId,
    initialParticipants: List<String> = listOf(currentUser, peerUser)
) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val connection: PeerConnection
    private val videoCapturer: CameraVideoCapturer?
    private val localVideo: VideoTrack?
    private val localAudio: org.webrtc.AudioTrack
    private var audioSource: org.webrtc.AudioSource? = null
    private val signalListener: (CallSignal, String) -> Unit
    private val streamId = "local-call-$callId"
    private val roomMembers = CopyOnWriteArrayList(initialParticipants.filter { it.isNotBlank() }.distinct())
    private val diagnosticStartedAt = SystemClock.elapsedRealtime()
    private val diagnostics = CopyOnWriteArrayList<String>()
    @Volatile private var releasing = false
    @Volatile private var terminationReported = false
    @Volatile private var latestConnectionState = "NEW"
    @Volatile private var latestIceState = "NEW"
    @Volatile private var latestSignalingState = "STABLE"
    @Volatile private var hasConnected = false
    private var recoveryState = LocalCallRecoveryPolicy.State.ACTIVE
    private val recoveryLock = Any()
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val recoveryTimeout = Runnable {
        applyRecoveryEvent(LocalCallRecoveryPolicy.Event.RECOVERY_TIMEOUT)
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        recordDiagnostic("تهيئة", "محرك WebRTC محلي جاهز | الوسائط: ${if (mediaType == "video") "فيديو" else "صوت"}")
        initialLatencyMs?.let { recordDiagnostic("فحص الشبكة", "اختبار الوصول المحلي نجح خلال ${it}ms") }
        val observer = object : PeerConnection.Observer by EmptyPeerObserver() {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                latestSignalingState = newState.name
                recordDiagnostic("إشارة WebRTC", "الحالة: ${newState.name}")
            }
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                recordDiagnostic("ICE", "تم إنشاء مرشح محلي")
                sendSignal(CallSignal(kind = "candidate", callId = callId, roomId = roomId, participants = roomMembers.toList(), fromUser = currentUser, toUser = peerUser, candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex, mediaType = mediaType))
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                val track = receiver.track() as? VideoTrack ?: return
                remoteRenderer?.let { track.addSink(it) }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                latestConnectionState = newState.name
                recordDiagnostic("اتصال WebRTC", "الحالة: ${newState.name}")
                onState(newState.name)
                onNetworkQuality(
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> NetworkQuality.GOOD
                        PeerConnection.PeerConnectionState.CONNECTING,
                        PeerConnection.PeerConnectionState.NEW -> NetworkQuality.CHECKING
                        PeerConnection.PeerConnectionState.DISCONNECTED -> NetworkQuality.UNSTABLE
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> NetworkQuality.POOR
                    }
                )
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        hasConnected = true
                        applyRecoveryEvent(LocalCallRecoveryPolicy.Event.CONNECTION_CONNECTED)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        applyRecoveryEvent(LocalCallRecoveryPolicy.Event.CONNECTION_DISCONNECTED)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        if (hasConnected) {
                            applyRecoveryEvent(LocalCallRecoveryPolicy.Event.RECOVERY_FAILED)
                        } else {
                            terminateAfterRecoveryDecision("تعذر إنشاء اتصال WebRTC محلي", "WEBRTC_CONNECTION_FAILED")
                        }
                    }
                    PeerConnection.PeerConnectionState.CLOSED -> terminateAfterRecoveryDecision("أغلق الطرف الآخر الاتصال", "WEBRTC_CONNECTION_CLOSED")
                    else -> Unit
                }
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                latestIceState = newState.name
                recordDiagnostic("ICE", "حالة الاتصال: ${newState.name}")
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    onNetworkQuality(NetworkQuality.UNSTABLE)
                    applyRecoveryEvent(LocalCallRecoveryPolicy.Event.ICE_DISCONNECTED)
                } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                    onNetworkQuality(NetworkQuality.POOR)
                    if (hasConnected) {
                        applyRecoveryEvent(LocalCallRecoveryPolicy.Event.RECOVERY_FAILED)
                    } else {
                        terminateAfterRecoveryDecision("فشل مسار شبكة ICE المحلي", "ICE_CONNECTION_FAILED")
                    }
                }
            }
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                recordDiagnostic("ICE", "جمع المرشحات: ${newState.name}")
            }
        }
        connection = requireNotNull(factory.createPeerConnection(rtcConfig, observer))
        val audio = factory.createAudioSource(MediaConstraints())
        audioSource = audio
        localAudio = factory.createAudioTrack("audio-$callId", audio)
        connection.addTrack(localAudio, listOf(streamId))
        if (mediaType == "video") {
            val capturer = createCameraCapturer()
            if (capturer == null) {
                videoCapturer = null
                localVideo = null
                reportTermination("تعذر الوصول إلى كاميرا الجهاز", "CAMERA_UNAVAILABLE")
            } else {
                videoCapturer = capturer
                val source = factory.createVideoSource(capturer.isScreencast)
                val textureHelper = SurfaceTextureHelper.create("local-camera", egl.eglBaseContext)
                capturer.initialize(textureHelper, context, source.capturerObserver)
                capturer.startCapture(640, 480, 24)
                localVideo = factory.createVideoTrack("video-$callId", source)
                localRenderer?.init(egl.eglBaseContext, null)
                remoteRenderer?.init(egl.eglBaseContext, null)
                localVideo.addSink(localRenderer)
                connection.addTrack(localVideo, listOf(streamId))
            }
        } else {
            videoCapturer = null
            localVideo = null
        }
        signalListener = { signal, _ ->
            if (signal.callId == callId && signal.fromUser == peerUser && signal.toUser == currentUser) {
                when (signal.kind) {
                    "offer" -> {
                        recordDiagnostic("إشارة محلية", "تم استلام عرض اتصال")
                        acceptOffer(signal)
                    }
                    "answer" -> {
                        recordDiagnostic("إشارة محلية", "تم استلام رد اتصال")
                        setRemote(signal)
                    }
                    "candidate" -> signal.candidate?.let {
                        recordDiagnostic("ICE", "تم استلام مرشح من الطرف الآخر")
                        connection.addIceCandidate(org.webrtc.IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, it))
                    }
                    "hangup" -> reportTermination("أنهى الطرف الآخر المكالمة", "REMOTE_HANGUP")
                }
            }
        }
        SyncManager.addCallSignalListener(signalListener)
    }

    fun startOutgoing() {
        recordDiagnostic("إشارة محلية", "بدء إنشاء عرض اتصال")
        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                recordDiagnostic("إشارة WebRTC", "تم إنشاء عرض الاتصال")
                connection.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetFailure(error: String?) {
                        reportNegotiationFailure("تعذر حفظ عرض الاتصال محليًا", "SET_LOCAL_OFFER_FAILED: ${error.orEmpty()}")
                    }
                }, description)
                sendSignal(CallSignal(kind = "offer", callId = callId, roomId = roomId, participants = roomMembers.toList(), fromUser = currentUser, toUser = peerUser, sdp = description.description, mediaType = mediaType))
            }
            override fun onCreateFailure(error: String?) {
                reportNegotiationFailure("تعذر إنشاء عرض الاتصال", "CREATE_OFFER_FAILED: ${error.orEmpty()}")
            }
        }, MediaConstraints())
    }

    fun acceptIncoming(offerSdp: String) {
        recordDiagnostic("إشارة محلية", "قبول العرض الوارد")
        setRemote(CallSignal(kind = "offer", callId = callId, fromUser = peerUser, toUser = currentUser, sdp = offerSdp, mediaType = mediaType))
    }

    private fun acceptOffer(signal: CallSignal) {
        setRemote(signal)
        recordDiagnostic("إشارة محلية", "بدء إنشاء رد الاتصال")
        connection.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                recordDiagnostic("إشارة WebRTC", "تم إنشاء رد الاتصال")
                connection.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetFailure(error: String?) {
                        reportNegotiationFailure("تعذر حفظ رد الاتصال محليًا", "SET_LOCAL_ANSWER_FAILED: ${error.orEmpty()}")
                    }
                }, description)
                sendSignal(CallSignal(kind = "answer", callId = callId, roomId = roomId, participants = roomMembers.toList(), fromUser = currentUser, toUser = peerUser, sdp = description.description, mediaType = mediaType))
            }
            override fun onCreateFailure(error: String?) {
                reportNegotiationFailure("تعذر إنشاء رد الاتصال", "CREATE_ANSWER_FAILED: ${error.orEmpty()}")
            }
        }, MediaConstraints())
    }

    private fun setRemote(signal: CallSignal) {
        val sdp = signal.sdp ?: run {
            reportNegotiationFailure("وصلت إشارة اتصال ناقصة", "REMOTE_SDP_MISSING")
            return
        }
        connection.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                recordDiagnostic("إشارة WebRTC", "تم حفظ وصف الطرف الآخر")
            }
            override fun onSetFailure(error: String?) {
                reportNegotiationFailure("تعذر معالجة إشارة الطرف الآخر", "SET_REMOTE_DESCRIPTION_FAILED: ${error.orEmpty()}")
            }
        }, SessionDescription(if (signal.kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp))
    }

    private fun sendSignal(signal: CallSignal) {
        Thread {
            val delivered = SyncManager.broadcastCallSignal(signal)
            recordDiagnostic("إشارة محلية", "${signal.kind}: أُرسلت إلى $delivered جهاز")
            if (delivered == 0 && signal.kind in setOf("offer", "answer")) {
                reportNegotiationFailure("تعذر إرسال إشارة المكالمة داخل الشبكة المحلية", "SIGNAL_NOT_DELIVERED")
            }
        }.start()
    }

    /** يرسل دعوة غرفة إلى مستخدم متصل؛ القناة تبقى داخل شبكة الأجهزة المحلية. */
    fun inviteParticipant(candidate: String, participants: List<String>): Boolean {
        if (releasing || candidate.isBlank() || candidate == currentUser) return false
        roomMembers.clear()
        roomMembers.addAll(participants.distinct().filter { it.isNotBlank() })
        sendSignal(CallSignal(kind = "room_invite", callId = callId, roomId = roomId, participants = roomMembers.toList(), fromUser = currentUser, toUser = candidate, mediaType = mediaType))
        recordDiagnostic("غرفة المكالمة", "تم إرسال دعوة إلى $candidate (${roomMembers.size} مشاركين)")
        return true
    }

    fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        localAudio.setEnabled(enabled)
        return localAudio.enabled()
    }

    fun setCameraEnabled(enabled: Boolean): Boolean {
        localVideo?.setEnabled(enabled)
        return localVideo?.enabled() ?: false
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    /** يطلب تفاوض ICE جديدًا فقط بعد عرض مسار إعادة المحاولة للمستخدم. */
    fun retryLocalConnection(): Boolean {
        if (releasing || terminationReported) return false
        val decision = applyRecoveryEvent(LocalCallRecoveryPolicy.Event.MANUAL_RETRY)
        if (!decision.startsRecoveryWindow) return false
        recordDiagnostic("استعادة", "طلب المستخدم إعادة تفاوض ICE داخل الشبكة المحلية")
        connection.restartIce()
        startOutgoing()
        return true
    }

    /** يخطر الطرف الآخر صراحةً قبل تحرير الوسائط؛ لا يعتمد على خادم خارجي. */
    fun endLocalCall() {
        applyRecoveryEvent(LocalCallRecoveryPolicy.Event.USER_ENDED)
        sendSignal(CallSignal(kind = "hangup", callId = callId, roomId = roomId, participants = roomMembers.toList(), fromUser = currentUser, toUser = peerUser, mediaType = mediaType))
        release()
    }

    fun release() {
        if (releasing) return
        releasing = true
        recoveryHandler.removeCallbacks(recoveryTimeout)
        recordDiagnostic("إنهاء", "تحرير وسائط المكالمة محليًا")
        SyncManager.removeCallSignalListener(signalListener)
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        runCatching { videoCapturer?.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { localAudio.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { connection.close() }
        runCatching { connection.dispose() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    fun diagnosticLog(): String = diagnostics.take(80).joinToString("\n")

    private fun recordDiagnostic(stage: String, detail: String) {
        val elapsed = (SystemClock.elapsedRealtime() - diagnosticStartedAt).coerceAtLeast(0L)
        val entry = "+${elapsed}ms | $stage | $detail"
        diagnostics.add(entry)
        onDiagnostic(entry)
    }

    private fun reportTermination(reason: String, code: String) {
        if (releasing || terminationReported) return
        terminationReported = true
        recordDiagnostic("فشل", "$code | $reason | WebRTC=$latestConnectionState | ICE=$latestIceState | إشارة=$latestSignalingState")
        onCallEnded(reason, diagnosticLog())
    }

    /** قبل اتصالٍ ناجح تكون مشكلة التفاوض قاتلة؛ بعده تتحول إلى إعادة محاولة يختارها المستخدم. */
    private fun reportNegotiationFailure(reason: String, code: String) {
        if (hasConnected) {
            recordDiagnostic("استعادة", "$code | $reason")
            applyRecoveryEvent(LocalCallRecoveryPolicy.Event.RECOVERY_FAILED)
        } else {
            reportTermination(reason, code)
        }
    }

    private fun terminateAfterRecoveryDecision(reason: String, code: String) {
        val decision = applyRecoveryEvent(LocalCallRecoveryPolicy.Event.TERMINAL_FAILURE)
        if (decision.isTerminal) reportTermination(reason, code)
    }

    private fun applyRecoveryEvent(event: LocalCallRecoveryPolicy.Event): LocalCallRecoveryPolicy.Decision {
        val decision = synchronized(recoveryLock) {
            LocalCallRecoveryPolicy.transition(recoveryState, event).also { recoveryState = it.state }
        }
        if (decision.cancelsRecoveryWindow || decision.isTerminal) {
            recoveryHandler.removeCallbacks(recoveryTimeout)
        }
        if (decision.startsRecoveryWindow) {
            recoveryHandler.removeCallbacks(recoveryTimeout)
            recoveryHandler.postDelayed(recoveryTimeout, LocalCallRecoveryPolicy.RECOVERY_WINDOW_MS)
        }
        if (decision.userMessage != null) {
            recordDiagnostic("استعادة", decision.userMessage)
        }
        onRecoveryState(decision)
        return decision
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return runCatching { enumerator.createCapturer(name, null) }.getOrNull()
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
        override fun onSetSuccess() = Unit
    }

    private open class EmptyPeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
    }

    /** تقدير محافظ للحالة مستند إلى اتصال WebRTC الفعلي، بلا اعتماد على خادم خارجي. */
    enum class NetworkQuality { CHECKING, GOOD, UNSTABLE, POOR }
}
