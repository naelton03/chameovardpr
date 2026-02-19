package com.example.replaycam

import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import java.util.concurrent.atomic.AtomicBoolean

class RtmpStreamEngine(
    private val surfaceView: SurfaceView,
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
    private var cameraInstance: RtmpCamera2? = null
    private var retryCount: Int = 0

    fun startStream(endpoint: String) {
        Log.i(tag, "startStream called endpoint=$endpoint")
        runCatching {
            val instance = cameraInstance ?: createCameraInstance().also { cameraInstance = it }

            if (!instance.prepareVideo(1280, 720, 30, 2_500_000, 2, 0)) {
                throw IllegalStateException("Falha ao preparar vídeo RTMP (prepareVideo=false)")
            }
            if (!instance.prepareAudio()) {
                throw IllegalStateException("Falha ao preparar áudio RTMP (prepareAudio=false)")
            }

            retryCount = 0
            instance.startStream(endpoint)
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
        cameraInstance?.stopStream()
        isConnected.set(false)
    }

    fun isStreaming(): Boolean {
        return cameraInstance?.isStreaming ?: false
    }

    fun setBitrateOnFly(bitrate: Int) {
        Log.w(tag, "Applying bitrate throttle: $bitrate")
        cameraInstance?.setVideoBitrateOnFly(bitrate)
    }

    fun close() {
        stopStream()
        cameraInstance = null
    }

    private fun createCameraInstance(): RtmpCamera2 {
        val checker = object : ConnectChecker {
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

        return RtmpCamera2(surfaceView, checker).also {
            it.setReTries(10)
        }
    }

    private fun maybeRetry(reason: String) {
        val current = cameraInstance ?: return
        if (retryCount >= 5) return
        retryCount += 1
        val delayMs = 1_500L * retryCount
        callbacks.onRetrying(delayMs, reason)
        runCatching {
            current.reTry(delayMs.toInt(), reason, null)
        }.onFailure { error ->
            Log.w(tag, "Auto retry fallback failed: ${error.message}", error)
        }
    }
}
