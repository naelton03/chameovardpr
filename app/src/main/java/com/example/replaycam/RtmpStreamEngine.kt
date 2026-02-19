package com.example.replaycam

import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RtmpStreamEngine(
    private val openGlView: OpenGlView,
    private val callbacks: Callbacks
) {

    private companion object {
        private const val YT_VIDEO_BITRATE = 2_500 * 1024
        private const val YT_AUDIO_BITRATE = 128 * 1024
        private const val YT_AUDIO_SAMPLE_RATE = 44_100
        private const val YT_FPS = 30
        private const val YT_KEYFRAME_INTERVAL_SEC = 2
    }

    interface Callbacks {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionFailed(reason: String)
        fun onAuthError()
        fun onAuthSuccess()
        fun onRetrying(delayMs: Long, reason: String)
        fun onMediaFlowing(bitrate: Long)
    }

    private val tag = "RtmpStreamEngine"
    private val isConnected = AtomicBoolean(false)
    private val mediaFlowBitrate = AtomicLong(0L)
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

        override fun onNewBitrate(bitrate: Long) {
            mediaFlowBitrate.set(bitrate)
            if (bitrate > 0L) {
                callbacks.onMediaFlowing(bitrate)
            }
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
    private val rtmpCamera: RtmpCamera2 = RtmpCamera2(openGlView, connectChecker)
    fun startStream(endpoint: String) {
        Log.i(tag, "startStream called endpoint=$endpoint")
        runCatching {
            mediaFlowBitrate.set(0L)
            configureNetworkBufferIfSupported()
            configureWriteLoopIntervalIfSupported()
            rtmpCamera.replaceView(openGlView)
            if (!rtmpCamera.isOnPreview) {
                Log.w(tag, "Preview RTMP não estava ativo. Iniciando preview antes do stream")
                rtmpCamera.startPreview()
            }

            if (!prepareVideo()) {
                throw IllegalStateException("Falha ao preparar vídeo RTMP (prepareVideo=false)")
            }
            if (!prepareAudio()) {
                throw IllegalStateException("Falha ao preparar áudio RTMP (prepareAudio=false)")
            }

            rtmpCamera.startStream(endpoint)
            rtmpCamera.setVideoBitrateOnFly(YT_VIDEO_BITRATE)
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
        mediaFlowBitrate.set(0L)
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
        val methods = rtmpCamera.javaClass.methods.filter { it.name == "prepareVideo" }

        val preferredSixArgs = methods.firstOrNull {
            it.parameterTypes.size == 6 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                it.parameterTypes[3] == Int::class.javaPrimitiveType &&
                it.parameterTypes[4] == Int::class.javaPrimitiveType &&
                it.parameterTypes[5] == Int::class.javaPrimitiveType
        }

        if (preferredSixArgs != null) {
            val prepared = runCatching {
                preferredSixArgs.invoke(rtmpCamera, 1280, 720, YT_FPS, YT_VIDEO_BITRATE, 0, YT_KEYFRAME_INTERVAL_SEC) as Boolean
            }.getOrElse {
                preferredSixArgs.invoke(rtmpCamera, 1280, 720, YT_FPS, YT_VIDEO_BITRATE, YT_KEYFRAME_INTERVAL_SEC, 0) as Boolean
            }
            configureYoutubeVideoProfileIfSupported()
            Log.i(tag, "prepareVideo(6 args) aplicado com 1280x720 ${YT_FPS}fps bitrate=${YT_VIDEO_BITRATE} keyframe=${YT_KEYFRAME_INTERVAL_SEC}s")
            return prepared
        }

        val preferredThreeArgs = methods.firstOrNull {
            it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }

        val prepared = if (preferredThreeArgs != null) {
            preferredThreeArgs.invoke(rtmpCamera, 1280, 720, YT_VIDEO_BITRATE) as Boolean
        } else {
            rtmpCamera.prepareVideo(1280, 720, YT_VIDEO_BITRATE)
        }

        configureYoutubeVideoProfileIfSupported()
        Log.i(tag, "prepareVideo fallback aplicado com 1280x720 bitrate=${YT_VIDEO_BITRATE}")
        return prepared
    }

    private fun prepareAudio(): Boolean {
        val methods = rtmpCamera.javaClass.methods.filter { it.name == "prepareAudio" }

        val preferredFiveArgs = methods.firstOrNull {
            it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[4] == Boolean::class.javaPrimitiveType
        }
        if (preferredFiveArgs != null) {
            return runCatching {
                preferredFiveArgs.invoke(rtmpCamera, YT_AUDIO_BITRATE, YT_AUDIO_SAMPLE_RATE, true, false, false) as Boolean
            }.onFailure { error ->
                Log.w(tag, "Falha prepareAudio(5 args): ${error.message}")
            }.getOrDefault(false)
        }

        val preferredThreeArgs = methods.firstOrNull {
            it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (preferredThreeArgs != null) {
            return runCatching {
                preferredThreeArgs.invoke(rtmpCamera, YT_AUDIO_BITRATE, YT_AUDIO_SAMPLE_RATE, true) as Boolean
            }.onFailure { error ->
                Log.w(tag, "Falha prepareAudio(3 args): ${error.message}")
            }.getOrDefault(false)
        }

        return rtmpCamera.prepareAudio()
    }

    private fun configureYoutubeVideoProfileIfSupported() {
        invokeIntMethodIfExists("setForceFpsLimit", YT_FPS)
        invokeIntMethodIfExists("setFps", YT_FPS)
        invokeIntMethodIfExists("setIFrameInterval", YT_KEYFRAME_INTERVAL_SEC)
        invokeIntMethodIfExists("setKeyFrameInterval", YT_KEYFRAME_INTERVAL_SEC)
        invokeProfileBaselineIfSupported()
    }

    private fun invokeIntMethodIfExists(methodName: String, value: Int) {
        val method = rtmpCamera.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
        } ?: return

        runCatching { method.invoke(rtmpCamera, value) }
            .onSuccess { Log.i(tag, "$methodName aplicado com valor=$value") }
            .onFailure { error -> Log.w(tag, "Falha ao aplicar $methodName: ${error.message}") }
    }

    private fun invokeProfileBaselineIfSupported() {
        val method = rtmpCamera.javaClass.methods.firstOrNull {
            it.name == "setProfile" && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
        } ?: return

        runCatching {
            method.invoke(rtmpCamera, android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            Log.i(tag, "H.264 profile definido para Baseline")
        }.onFailure { error ->
            Log.w(tag, "Falha ao definir profile Baseline: ${error.message}")
        }
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

    private fun configureWriteLoopIntervalIfSupported() {
        val method = rtmpCamera.javaClass.methods.firstOrNull {
            it.name == "setWriteLoopInterval" && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Int::class.javaObjectType)
        }

        if (method == null) {
            Log.i(tag, "setWriteLoopInterval indisponível nesta versão; mantendo intervalo padrão")
            return
        }

        runCatching {
            method.invoke(rtmpCamera, 100)
            Log.i(tag, "Write loop interval configurado para 100ms")
        }.onFailure { error ->
            Log.w(tag, "Falha ao configurar setWriteLoopInterval: ${error.message}")
        }
    }
}
