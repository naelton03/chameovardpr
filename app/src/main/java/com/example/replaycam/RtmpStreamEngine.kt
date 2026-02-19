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

    private val tag = "RtmpStreamEngine"
    private val isConnected = AtomicBoolean(false)
    private var cameraInstance: Any? = null
    private var retryCount: Int = 0

    fun startStream(endpoint: String) {
        Log.i(tag, "startStream called endpoint=$endpoint")
        runCatching {
            val instance = cameraInstance ?: createCameraInstance().also { cameraInstance = it }

            if (!prepareVideo(instance)) {
                throw IllegalStateException("Falha ao preparar vídeo RTMP (prepareVideo=false)")
            }
            if (!invokeBoolean(instance, "prepareAudio")) {
                throw IllegalStateException("Falha ao preparar áudio RTMP (prepareAudio=false)")
            }

            retryCount = 0
            invoke(instance, "startStream", endpoint)
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
        cameraInstance?.let { invoke(it, "stopStream") }
        isConnected.set(false)
    }

    fun isStreaming(): Boolean {
        return cameraInstance?.let { invokeBoolean(it, "isStreaming") } ?: false
    }

    fun setBitrateOnFly(bitrate: Int) {
        Log.w(tag, "Applying bitrate throttle: $bitrate")
        cameraInstance?.let {
            if (!invokeSafely(it, "setVideoBitrateOnFly", bitrate)) {
                invokeSafely(it, "setVideoBitrate", bitrate)
            }
        }
    }

    fun close() {
        stopStream()
        cameraInstance = null
    }

    private fun createCameraInstance(): Any {
        val checkerClass = runCatching { Class.forName("com.pedro.common.ConnectChecker") }
            .getOrElse {
                throw IllegalStateException("SDK RTMP ausente no APK. Classe ConnectChecker não encontrada.", it)
            }

        val proxy = Proxy.newProxyInstance(
            checkerClass.classLoader,
            arrayOf(checkerClass)
        ) { _, method, args ->
            when (method.name) {
                "onConnectionStarted", "onConnectionStartedRtmp" -> {
                    val url = args?.firstOrNull()?.toString().orEmpty()
                    Log.i(tag, "RTMP connection started url=$url")
                }

                "onConnectionSuccess", "onConnectionSuccessRtmp" -> {
                    retryCount = 0
                    isConnected.set(true)
                    callbacks.onConnected()
                }

                "onConnectionFailed", "onConnectionFailedRtmp" -> {
                    isConnected.set(false)
                    val reason = args?.firstOrNull()?.toString() ?: "unknown"
                    callbacks.onConnectionFailed(reason)
                    maybeRetry(reason)
                }

                "onDisconnect", "onDisconnectRtmp" -> {
                    isConnected.set(false)
                    callbacks.onDisconnected()
                }

                "onAuthError", "onAuthErrorRtmp" -> callbacks.onAuthError()
                "onAuthSuccess", "onAuthSuccessRtmp" -> callbacks.onAuthSuccess()
            }
            null
        }

        val classCandidates = listOf(
            "com.pedro.library.rtmp.RtmpCamera2",
            "com.pedro.library.rtmp.RtmpCamera1",
            "com.pedro.library.base.Camera2Base",
            "com.pedro.library.base.Camera1Base"
        )

        val cameraClass = classCandidates
            .asSequence()
            .mapNotNull { name -> runCatching { Class.forName(name) }.getOrNull() }
            .firstOrNull()
            ?: throw IllegalStateException("SDK RTMP ausente no APK. Nenhuma classe de câmera RTMP compatível foi encontrada.")

        val constructor = cameraClass.constructors.firstOrNull { constructor ->
            val parameterTypes = constructor.parameterTypes
            parameterTypes.size == 2 && parameterTypes[0].isAssignableFrom(SurfaceView::class.java)
        } ?: throw IllegalStateException("Construtor RTMP compatível não encontrado em ${cameraClass.name}")

        return constructor.newInstance(surfaceView, proxy).also {
            invokeSafely(it, "setReTries", 10)
        }
    }

    private fun prepareVideo(instance: Any): Boolean {
        // Assinaturas comuns entre versões
        val attempts = listOf(
            arrayOf(1280, 720, 30, 2_500_000, 2, 0),
            arrayOf(1280, 720, 30, 2_500_000),
            arrayOf(1280, 720, 30)
        )

        attempts.forEach { args ->
            val result = invokeBoolean(instance, "prepareVideo", *args)
            if (result) return true
        }
        return false
    }

    private fun maybeRetry(reason: String) {
        val current = cameraInstance ?: return
        if (retryCount >= 5) return
        retryCount += 1
        val delayMs = 1_500L * retryCount
        callbacks.onRetrying(delayMs, reason)

        val retried = invokeBoolean(current, "reTry", delayMs.toInt(), reason, null)
        if (!retried) {
            invokeBoolean(current, "retry", delayMs.toInt(), reason, null)
        }
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?) {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException("Method not found: $methodName/${args.size}")
        method.invoke(target, *args)
    }

    private fun invokeSafely(target: Any, methodName: String, vararg args: Any?): Boolean {
        return runCatching {
            invoke(target, methodName, *args)
            true
        }.getOrDefault(false)
    }

    private fun invokeBoolean(target: Any, methodName: String, vararg args: Any?): Boolean {
        val method = target.javaClass.methods.firstOrNull { method ->
            method.name == methodName && method.parameterTypes.size == args.size
        } ?: return false

        return runCatching {
            val result = method.invoke(target, *args)
            result as? Boolean ?: true
        }.getOrDefault(false)
    }
}
