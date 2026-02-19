package com.example.replaycam

import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.atomic.AtomicBoolean

class RtmpStreamEngine(
    private val openGlView: OpenGlView,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionFailed(reason: String)
        fun onAuthError()
        fun onAuthSuccess()
        fun onRetrying(delayMs: Long, reason: String)
    }

    private val tag = "RtmpStreamEngine"
    private val isConnected = AtomicBoolean(false)
    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.i(tag, "RTMP connection started url=$url")
        }

        override fun onConnectionSuccess() {
            isConnected.set(true)
            callbacks.onConnected()
        }

        override fun onConnectionFailed(reason: String) {
            isConnected.set(false)
            Log.e(tag, "RTMP connection failed. reason=$reason")
            callbacks.onConnectionFailed(reason)
            maybeRetry()
        }

        override fun onNewBitrate(bitrate: Long) = Unit

        override fun onDisconnect() {
            isConnected.set(false)
            callbacks.onDisconnected()
        }

        override fun onAuthError() {
            callbacks.onAuthError()
        }

        override fun onAuthSuccess() {
            callbacks.onAuthSuccess()
        }
    }
    private val rtmpCamera: RtmpCamera2 = RtmpCamera2(openGlView, connectChecker)
    fun startStream(endpoint: String) {
        Log.i(tag, "startStream called endpoint=$endpoint")
        runCatching {
            configureNetworkBufferIfSupported()
            rtmpCamera.replaceView(openGlView)
            if (!rtmpCamera.isOnPreview) {
                Log.w(tag, "Preview RTMP não estava ativo. Iniciando preview antes do stream")
                rtmpCamera.startPreview()
            }

            if (!prepareVideo()) {
                throw IllegalStateException("Falha ao preparar vídeo RTMP (prepareVideo=false)")
            }
            if (!rtmpCamera.prepareAudio()) {
                throw IllegalStateException("Falha ao preparar áudio RTMP (prepareAudio=false)")
            }

            rtmpCamera.startStream(endpoint)
            rtmpCamera.setVideoBitrateOnFly(5_000_000)
            isConnected.set(true)
            Log.i(tag, "RTMP stream started")
        }.onFailure { error ->
            isConnected.set(false)
            val reason = error.message ?: "Falha desconhecida ao iniciar RTMP"
            Log.e(tag, "startStream falhou: $reason", error)
            callbacks.onConnectionFailed(reason)
        }
    }

    fun stopStream() {
        Log.i(tag, "stopStream called")
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
        }
        isConnected.set(false)
    }

    fun isStreaming(): Boolean {
        return rtmpCamera.isStreaming
    }

    fun setBitrateOnFly(bitrate: Int) {
        Log.w(tag, "Applying bitrate throttle: $bitrate")
        rtmpCamera.setVideoBitrateOnFly(bitrate)
    }

    fun close() {
        stopStream()
    }

    private fun prepareVideo(): Boolean {
        return rtmpCamera.prepareVideo(1280, 720, 5_000_000)
    }

    private fun maybeRetry() {
        Log.e("RtmpStreamEngine", "Conexão perdida")
    }

    private fun configureNetworkBufferIfSupported() {
        val candidates = listOf("setSocketSendBuffer", "setSocketBufferSize", "setBufferSize")
        for (name in candidates) {
            val method = rtmpCamera.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
            } ?: continue

            runCatching {
                method.invoke(rtmpCamera, 512 * 1024)
                Log.i(tag, "Network buffer configurado via $name")
            }.onFailure { error ->
                Log.w(tag, "Falha ao configurar buffer via $name: ${error.message}")
            }
            return
        }

        Log.i(tag, "API de ajuste de buffer não disponível nesta versão; mantendo padrão da biblioteca")
    }
}
