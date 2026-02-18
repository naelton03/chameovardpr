package com.pedro.rtplibrary.rtmp

import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.view.OpenGlView
import java.io.File

class RtmpCamera2(
    private val openGlView: OpenGlView,
    private val checker: ConnectCheckerRtmp
) {
    var isStreaming: Boolean = false
        private set

    var isRecording: Boolean = false
        private set

    var isOnPreview: Boolean = false
        private set

    fun prepareVideo(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): Boolean = true

    fun prepareAudio(
        bitrate: Int,
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean = true

    fun startPreview() {
        isOnPreview = true
    }

    fun stopPreview() {
        isOnPreview = false
    }

    fun startStream(url: String) {
        checker.onConnectionStartedRtmp(url)
        isStreaming = true
        checker.onConnectionSuccessRtmp()
    }

    fun stopStream() {
        if (isStreaming) {
            isStreaming = false
            checker.onDisconnectRtmp()
        }
    }

    fun startRecord(path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        isRecording = true
    }

    fun stopRecord() {
        isRecording = false
    }
}
