package com.example.replaycam

import android.util.Log
import android.view.SurfaceView
import java.lang.reflect.Proxy
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

    private val isConnected = AtomicBoolean(false)
    private var cameraInstance: Any? = null
    private var retryCount: Int = 0

    fun startStream(endpoint: String) {
        val instance = cameraInstance ?: createCameraInstance().also { cameraInstance = it }

        if (!invokeBoolean(instance, "prepareVideo", 1280, 720, 30, 2_500_000, 2, 0)) {
            throw IllegalStateException("Falha ao preparar vídeo para RTMP")
        }
        if (!invokeBoolean(instance, "prepareAudio")) {
            throw IllegalStateException("Falha ao preparar áudio para RTMP")
        }

        retryCount = 0
        invoke(instance, "startStream", endpoint)
        isConnected.set(true)
    }

    fun stopStream() {
        cameraInstance?.let {
            invoke(it, "stopStream")
        }
        isConnected.set(false)
    }

    fun isStreaming(): Boolean {
        return cameraInstance?.let {
            invokeBoolean(it, "isStreaming")
        } ?: false
    }

    fun setBitrateOnFly(bitrate: Int) {
        cameraInstance?.let {
            invoke(it, "setVideoBitrateOnFly", bitrate)
        }
    }

    fun close() {
        stopStream()
        cameraInstance = null
    }

    private fun createCameraInstance(): Any {
        val checkerClass = Class.forName("com.pedro.rtmp.utils.ConnectCheckerRtmp")
        val proxy = Proxy.newProxyInstance(
            checkerClass.classLoader,
            arrayOf(checkerClass)
        ) { _, method, args ->
            when (method.name) {
                "onConnectionSuccessRtmp" -> {
                    retryCount = 0
                    callbacks.onConnected()
                }

                "onDisconnectRtmp" -> {
                    isConnected.set(false)
                    callbacks.onDisconnected()
                }

                "onConnectionFailedRtmp" -> {
                    isConnected.set(false)
                    val reason = args?.firstOrNull()?.toString() ?: "unknown"
                    callbacks.onConnectionFailed(reason)
                    maybeRetry(reason)
                }

                "onAuthErrorRtmp" -> callbacks.onAuthError()
                "onAuthSuccessRtmp" -> callbacks.onAuthSuccess()
            }
            null
        }

        val clazz = Class.forName("com.pedro.library.rtmp.RtmpCamera2")
        return clazz.getConstructor(SurfaceView::class.java, checkerClass)
            .newInstance(surfaceView, proxy)
            .also { invoke(it, "setReTries", 10) }
    }

    private fun maybeRetry(reason: String) {
        val current = cameraInstance ?: return
        if (retryCount >= 5) return
        retryCount += 1
        val delayMs = (1_500L * retryCount)
        callbacks.onRetrying(delayMs, reason)
        try {
            invokeBoolean(current, "reTry", delayMs.toInt(), reason, null)
        } catch (error: Throwable) {
            Log.w("RtmpStreamEngine", "Auto retry fallback failed: ${error.message}")
        }
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?) {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException(methodName)
        method.invoke(target, *args)
    }

    private fun invokeBoolean(target: Any, methodName: String, vararg args: Any?): Boolean {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterTypes.size == args.size
        } ?: return false
        val result = method.invoke(target, *args)
        return result as? Boolean ?: true
    }
}
