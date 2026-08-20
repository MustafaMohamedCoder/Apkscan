package com.masahhisabat.app.data

import android.content.Context
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
    private val onCallEnded: (String) -> Unit = {}
) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val connection: PeerConnection
    private val videoCapturer: CameraVideoCapturer?
    private val localVideo: VideoTrack?
    private val localAudio: org.webrtc.AudioTrack
    private val signalListener: (CallSignal, String) -> Unit
    private val streamId = "local-call-$callId"
    @Volatile private var releasing = false
    @Volatile private var terminationReported = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val observer = object : PeerConnection.Observer by EmptyPeerObserver() {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                sendSignal(CallSignal(kind = "candidate", callId = callId, fromUser = currentUser, toUser = peerUser, candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex, mediaType = mediaType))
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                val track = receiver.track() as? VideoTrack ?: return
                remoteRenderer?.let { track.addSink(it) }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
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
                    PeerConnection.PeerConnectionState.FAILED -> reportTermination("تعذر إنشاء اتصال WebRTC محلي")
                    PeerConnection.PeerConnectionState.CLOSED -> reportTermination("أغلق الطرف الآخر الاتصال")
                    else -> Unit
                }
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    onNetworkQuality(NetworkQuality.UNSTABLE)
                } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                    onNetworkQuality(NetworkQuality.POOR)
                }
            }
        }
        connection = requireNotNull(factory.createPeerConnection(rtcConfig, observer))
        val audio = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("audio-$callId", audio)
        connection.addTrack(localAudio, listOf(streamId))
        if (mediaType == "video") {
            videoCapturer = createCameraCapturer()
            val source = factory.createVideoSource(videoCapturer!!.isScreencast)
            val textureHelper = SurfaceTextureHelper.create("local-camera", egl.eglBaseContext)
            videoCapturer.initialize(textureHelper, context, source.capturerObserver)
            videoCapturer.startCapture(640, 480, 24)
            localVideo = factory.createVideoTrack("video-$callId", source)
            localRenderer?.init(egl.eglBaseContext, null)
            remoteRenderer?.init(egl.eglBaseContext, null)
            localVideo.addSink(localRenderer)
            connection.addTrack(localVideo, listOf(streamId))
        } else {
            videoCapturer = null
            localVideo = null
        }
        signalListener = { signal, _ ->
            if (signal.callId == callId && signal.fromUser == peerUser && signal.toUser == currentUser) {
                when (signal.kind) {
                    "offer" -> acceptOffer(signal)
                    "answer" -> setRemote(signal)
                    "candidate" -> signal.candidate?.let { connection.addIceCandidate(org.webrtc.IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, it)) }
                    "hangup" -> reportTermination("أنهى الطرف الآخر المكالمة")
                }
            }
        }
        SyncManager.addCallSignalListener(signalListener)
    }

    fun startOutgoing() {
        connection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(SdpObserverAdapter(), description)
                sendSignal(CallSignal(kind = "offer", callId = callId, fromUser = currentUser, toUser = peerUser, sdp = description.description, mediaType = mediaType))
            }
        }, MediaConstraints())
    }

    fun acceptIncoming(offerSdp: String) {
        setRemote(CallSignal(kind = "offer", callId = callId, fromUser = peerUser, toUser = currentUser, sdp = offerSdp, mediaType = mediaType))
    }

    private fun acceptOffer(signal: CallSignal) {
        setRemote(signal)
        connection.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(SdpObserverAdapter(), description)
                sendSignal(CallSignal(kind = "answer", callId = callId, fromUser = currentUser, toUser = peerUser, sdp = description.description, mediaType = mediaType))
            }
        }, MediaConstraints())
    }

    private fun setRemote(signal: CallSignal) {
        val sdp = signal.sdp ?: return
        connection.setRemoteDescription(SdpObserverAdapter(), SessionDescription(if (signal.kind == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, sdp))
    }

    private fun sendSignal(signal: CallSignal) { Thread { SyncManager.broadcastCallSignal(signal) }.start() }

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

    /** يخطر الطرف الآخر صراحةً قبل تحرير الوسائط؛ لا يعتمد على خادم خارجي. */
    fun endLocalCall() {
        sendSignal(CallSignal(kind = "hangup", callId = callId, fromUser = currentUser, toUser = peerUser, mediaType = mediaType))
        release()
    }

    fun release() {
        releasing = true
        SyncManager.removeCallSignalListener(signalListener)
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        videoCapturer?.dispose()
        localVideo?.dispose()
        connection.close()
        factory.dispose()
        egl.release()
    }

    private fun reportTermination(reason: String) {
        if (releasing || terminationReported) return
        terminationReported = true
        onCallEnded(reason)
    }

    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.first()
        return enumerator.createCapturer(name, null)
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
