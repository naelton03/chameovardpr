package com.example.replaycam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.replaycam.databinding.ActivityMainBinding
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectCheckerRtmp {

    private lateinit var binding: ActivityMainBinding
    private lateinit var rtmpCamera2: RtmpCamera2

    private val mainHandler = Handler(Looper.getMainLooper())
    private val segmentDurationMs = 5_000L
    private val maxSegments = 4
    private val segmentFiles = ArrayDeque<File>()
    private var currentSegmentFile: File? = null
    private var isContinuousRecording = false
    private var recordingStartTime: Long = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startPreview()
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rtmpCamera2 = RtmpCamera2(binding.openGlView, this)

        binding.toggleStreamButton.setOnClickListener { toggleStream() }
        binding.startButton.setOnClickListener { startContinuousRecording() }
        binding.stopButton.setOnClickListener { stopContinuousRecording() }
        binding.replayButton.setOnClickListener { saveReplayBundle() }
        binding.openFolderButton.setOnClickListener { openVideoFolder() }

        updateVideoPathLabel()
        clearSegmentCache()

        if (hasCapturePermissions()) {
            startPreview()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun toggleStream() {
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
            stopStreamingService()
            binding.toggleStreamButton.setText(R.string.start_stream)
            binding.statusText.setText(R.string.status_disconnected)
            return
        }

        val streamUrl = binding.streamUrlEditText.text?.toString()?.trim().orEmpty()
        if (!(streamUrl.startsWith("rtmp://") || streamUrl.startsWith("rtmps://"))) {
            binding.streamUrlLayout.error = getString(R.string.stream_url_required)
            return
        }

        binding.streamUrlLayout.error = null
        startPreview()

        if (!prepareEncodersIfNeeded()) {
            toast("Falha ao preparar encoders H.264/AAC")
            return
        }

        startStreamingService()
        binding.statusText.setText(R.string.status_connecting)
        rtmpCamera2.startStream(streamUrl)
        binding.toggleStreamButton.setText(R.string.stop_stream)
    }

    private fun startContinuousRecording() {
        if (isContinuousRecording) return

        startPreview()
        if (!prepareEncodersIfNeeded()) {
            toast("Falha ao preparar encoders H.264/AAC")
            return
        }

        clearSegmentCache()
        isContinuousRecording = true
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.replayButton.isEnabled = true
        binding.statusText.text = "Status: gravando continuamente"

        recordingStartTime = System.currentTimeMillis()
        mainHandler.post(updateTimerRunnable)

        startSegmentLoop()
    }

    private fun startSegmentLoop() {
        if (!isContinuousRecording) return

        val segmentFile = createSegmentFile()
        currentSegmentFile = segmentFile

        try {
            rtmpCamera2.startRecord(segmentFile.absolutePath)
        } catch (error: Exception) {
            binding.statusText.text = "Erro ao iniciar segmento: ${error.message}"
            stopContinuousRecording()
            return
        }

        mainHandler.postDelayed({
            finalizeCurrentSegment()
            if (isContinuousRecording) {
                startSegmentLoop()
            }
        }, segmentDurationMs)
    }

    private fun finalizeCurrentSegment() {
        val segmentFile = currentSegmentFile ?: return
        currentSegmentFile = null

        if (rtmpCamera2.isRecording) {
            try {
                rtmpCamera2.stopRecord()
            } catch (_: Exception) {
            }
        }

        val valid = segmentFile.exists() && segmentFile.length() > 0
        if (!valid) {
            segmentFile.delete()
            return
        }

        segmentFiles.addLast(segmentFile)
        while (segmentFiles.size > maxSegments) {
            val removed = segmentFiles.removeFirst()
            removed.delete()
        }
        binding.statusText.text = "Status: buffer ativo (${segmentFiles.size * 5}s)"
    }

    private fun stopContinuousRecording() {
        if (!isContinuousRecording) return

        isContinuousRecording = false
        mainHandler.removeCallbacks(updateTimerRunnable)
        binding.recordingTimerText.text = "00:00"

        finalizeCurrentSegment()

        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.replayButton.isEnabled = false
        binding.statusText.text = "Status: gravação parada"

        clearSegmentCache()
    }

    private fun saveReplayBundle() {
        if (segmentFiles.size < maxSegments) {
            toast("Aguarde preencher 20s no buffer")
            return
        }

        val stamp = timestamp()
        val mergedReplay = File(getSegmentsDirectory(), "replay_merged_${stamp}.mp4")
        val merged = mergeSegmentsIntoSingleVideo(segmentFiles.toList(), mergedReplay)
        if (!merged) {
            binding.statusText.text = "Erro ao montar replay único"
            toast("Falha ao montar replay de 20s")
            return
        }

        val outputName = "replay_${stamp}.mp4"
        val savedUri = saveVideoToPublicGallery(mergedReplay, outputName)
        mergedReplay.delete()

        if (savedUri == null) {
            binding.statusText.text = "Erro ao salvar replay na galeria"
            toast("Falha ao salvar replay na galeria")
            return
        }

        binding.statusText.text = "Status: replay único salvo na galeria"
        toast("Replay salvo em ${getPublicReplayPathLabel()}")
    }

    private fun prepareEncodersIfNeeded(): Boolean {
        if (rtmpCamera2.isStreaming || rtmpCamera2.isRecording) return true
        val videoPrepared = rtmpCamera2.prepareVideo(1280, 720, 30, 2_500_000)
        val audioPrepared = rtmpCamera2.prepareAudio(128_000, 44_100, true, false, false)
        return videoPrepared && audioPrepared
    }

    private fun startPreview() {
        if (!hasCapturePermissions()) return
        if (!rtmpCamera2.isOnPreview) {
            rtmpCamera2.startPreview()
        }
    }

    private fun hasCapturePermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (!isContinuousRecording) return
            val elapsedTime = System.currentTimeMillis() - recordingStartTime
            val seconds = (elapsedTime / 1000) % 60
            val minutes = (elapsedTime / (1000 * 60)) % 60
            binding.recordingTimerText.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreamingService() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        runOnUiThread {
            binding.statusText.setText(R.string.status_connecting)
        }
    }

    override fun onConnectionSuccessRtmp() {
        runOnUiThread {
            binding.statusText.setText(R.string.stream_connected)
        }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        runOnUiThread {
            binding.statusText.text = getString(R.string.stream_connection_failed) + ": $reason"
            binding.toggleStreamButton.setText(R.string.start_stream)
            if (rtmpCamera2.isStreaming) rtmpCamera2.stopStream()
            stopStreamingService()
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) = Unit

    override fun onDisconnectRtmp() {
        runOnUiThread {
            binding.statusText.setText(R.string.status_disconnected)
            binding.toggleStreamButton.setText(R.string.start_stream)
            stopStreamingService()
        }
    }

    override fun onAuthErrorRtmp() {
        runOnUiThread {
            binding.statusText.setText(R.string.stream_auth_error)
            toast(getString(R.string.stream_auth_error))
        }
    }

    override fun onAuthSuccessRtmp() {
        runOnUiThread {
            binding.statusText.setText(R.string.stream_auth_success)
        }
    }

    private fun mergeSegmentsIntoSingleVideo(segments: List<File>, outputFile: File): Boolean {
        if (segments.isEmpty()) return false

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var started = false
        var videoPtsOffset = 0L
        var audioPtsOffset = 0L

        try {
            segments.forEach { segment ->
                if (!segment.exists() || segment.length() == 0L) return@forEach

                val extractor = MediaExtractor()
                extractor.setDataSource(segment.absolutePath)

                var srcVideoTrack = -1
                var srcAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormatKeys.MIME).orEmpty()
                    if (mime.startsWith("video/")) srcVideoTrack = i
                    if (mime.startsWith("audio/")) srcAudioTrack = i
                }

                if (srcVideoTrack != -1 && videoTrackIndex == -1) {
                    videoTrackIndex = muxer.addTrack(extractor.getTrackFormat(srcVideoTrack))
                }
                if (srcAudioTrack != -1 && audioTrackIndex == -1) {
                    audioTrackIndex = muxer.addTrack(extractor.getTrackFormat(srcAudioTrack))
                }

                if (!started && (videoTrackIndex != -1 || audioTrackIndex != -1)) {
                    muxer.start()
                    started = true
                }

                if (srcVideoTrack != -1 && videoTrackIndex != -1) {
                    extractor.selectTrack(srcVideoTrack)
                    var lastPts = 0L
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        videoInfo.offset = 0
                        videoInfo.size = sampleSize
                        videoInfo.presentationTimeUs = videoPtsOffset + extractor.sampleTime
                        videoInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(videoTrackIndex, buffer, videoInfo)
                        lastPts = videoInfo.presentationTimeUs
                        extractor.advance()
                    }
                    extractor.unselectTrack(srcVideoTrack)
                    videoPtsOffset = if (lastPts > 0L) lastPts + 1L else videoPtsOffset
                }

                if (srcAudioTrack != -1 && audioTrackIndex != -1) {
                    extractor.selectTrack(srcAudioTrack)
                    var lastPts = 0L
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        audioInfo.offset = 0
                        audioInfo.size = sampleSize
                        audioInfo.presentationTimeUs = audioPtsOffset + extractor.sampleTime
                        audioInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(audioTrackIndex, buffer, audioInfo)
                        lastPts = audioInfo.presentationTimeUs
                        extractor.advance()
                    }
                    extractor.unselectTrack(srcAudioTrack)
                    audioPtsOffset = if (lastPts > 0L) lastPts + 1L else audioPtsOffset
                }

                extractor.release()
            }
        } catch (_: Exception) {
            return false
        } finally {
            try {
                if (started) muxer.stop()
            } catch (_: Exception) {
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }

        return outputFile.exists() && outputFile.length() > 0
    }

    private fun saveVideoToPublicGallery(source: File, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ReplayCam")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null

                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: return null

                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "ReplayCam"
                )
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, displayName)
                source.inputStream().use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }

                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, out.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateVideoPathLabel() {
        binding.videoPathText.text = getString(R.string.video_path, getPublicReplayPathLabel())
    }

    private fun openVideoFolder() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            return
        }

        val path = getPublicReplayPathLabel()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("video_path", path))
        toast(getString(R.string.video_folder_open_error))
        binding.statusText.text = getString(R.string.video_path_copied, path)
    }

    private fun createSegmentFile(): File {
        val segmentsDir = getSegmentsDirectory()
        if (!segmentsDir.exists()) segmentsDir.mkdirs()
        return File(segmentsDir, "segment_${timestamp()}.mp4")
    }

    private fun getSegmentsDirectory(): File = File(cacheDir, "replay_segments_cache")

    private fun clearSegmentCache() {
        currentSegmentFile = null
        segmentFiles.forEach { it.delete() }
        segmentFiles.clear()

        val dir = getSegmentsDirectory()
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun getPublicReplayPathLabel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Movies/ReplayCam (Galeria)"
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ReplayCam"
            ).absolutePath
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (rtmpCamera2.isRecording) {
            try {
                rtmpCamera2.stopRecord()
            } catch (_: Exception) {
            }
        }
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
            stopStreamingService()
        }
        if (rtmpCamera2.isOnPreview) {
            rtmpCamera2.stopPreview()
        }
        clearSegmentCache()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private object MediaFormatKeys {
        const val MIME = "mime"
    }
}
