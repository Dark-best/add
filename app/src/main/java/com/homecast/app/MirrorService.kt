package com.homecast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.*
import java.net.URI

class MirrorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var wsClient: WebSocketClient? = null
    private var eglBase: EglBase? = null

    private val roomId = "default"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        val serverIp = intent?.getStringExtra("serverIp") ?: ""

        if (resultCode != -1 && data != null && serverIp.isNotEmpty()) {
            startCastPipeline(resultCode, data, serverIp)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "homecast_mirror"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Mirroring en cours", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Home Cast")
            .setContentText("Diffusion de l'écran en cours")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

        startForeground(1, notification)
    }

    private fun startCastPipeline(resultCode: Int, data: Intent, serverIp: String) {
        eglBase = EglBase.create()

        // 1. Init WebRTC factory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // 2. Récupération du MediaProjection à partir du résultat de permission
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

        val videoSource = peerConnectionFactory!!.createVideoSource(false)

        // windowManager n'existe pas dans un Service, on le récupère explicitement
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        // ScreenCapturerAndroid attend l'Intent original (permissionResultData), pas
        // l'objet MediaProjection déjà résolu
        val capturer = ScreenCapturerAndroid(
            data,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
        )
        videoCapturer = capturer
        capturer.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
        capturer.startCapture(width, height, dpi)

        val videoTrack = peerConnectionFactory!!.createVideoTrack("homecast_video", videoSource)

        // 3. Peer connection (pas de STUN/TURN externe, LAN only)
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        peerConnection = peerConnectionFactory!!.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(JSONObject().apply {
                        put("type", "candidate")
                        put("candidate", JSONObject().apply {
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                        })
                    })
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        peerConnection?.addTrack(videoTrack, listOf("homecast_stream"))

        // 4. Connexion WebSocket au serveur de signaling
        val wsUrl = "ws://$serverIp:8090/signal?room=$roomId&role=sender"
        wsClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                createOffer()
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                val json = JSONObject(message)
                when (json.getString("type")) {
                    "answer" -> {
                        val sdp = json.getJSONObject("sdp")
                        peerConnection?.setRemoteDescription(
                            SimpleSdpObserver(),
                            SessionDescription(SessionDescription.Type.ANSWER, sdp.getString("sdp"))
                        )
                    }
                    "candidate" -> {
                        val c = json.getJSONObject("candidate")
                        peerConnection?.addIceCandidate(
                            IceCandidate(
                                c.optString("sdpMid"),
                                c.optInt("sdpMLineIndex"),
                                c.getString("candidate")
                            )
                        )
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: Exception?) {}
        }
        wsClient?.connect()
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sdp)
                sendSignal(JSONObject().apply {
                    put("type", "offer")
                    put("sdp", JSONObject().apply {
                        put("type", "offer")
                        put("sdp", sdp.description)
                    })
                })
            }
        }, constraints)
    }

    private fun sendSignal(json: JSONObject) {
        wsClient?.send(json.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        peerConnection?.close()
        mediaProjection?.stop()
        wsClient?.close()
        eglBase?.release()
    }
}

// Observer SDP minimal, signature exacte de l'interface SdpObserver de la lib WebRTC
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
