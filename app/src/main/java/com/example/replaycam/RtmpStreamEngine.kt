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
            retryCount = 0
            isConnected.set(true)
            callbacks.onConnected()
        }

        override fun onConnectionFailed(reason: String) {
            isConnected.set(false)
            callbacks.onConnectionFailed(reason)
            maybeRetry(reason)
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
    private var retryCount: Int = 0

    fun startStream(endpoint: String) {
        Log.i(tag, "startStream called endpoint=$endpoint")
        runCatching {
            if (!prepareVideo()) {
                throw IllegalStateException("Falha ao preparar vídeo RTMP (prepareVideo=false)")
            }
            if (!rtmpCamera.prepareAudio()) {
                throw IllegalStateException("Falha ao preparar áudio RTMP (prepareAudio=false)")
            }

            retryCount = 0
            rtmpCamera.startStream(endpoint)
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
        return rtmpCamera.prepareVideo(1280, 720, 2_500_000)
    }

    private fun maybeRetry(reason: String) {
        if (retryCount >= 5) return
        retryCount += 1
        val delayMs = 1_500L * retryCount
        callbacks.onRetrying(delayMs, reason)
        rtmpCamera.retry(delayMs)
    }
}
