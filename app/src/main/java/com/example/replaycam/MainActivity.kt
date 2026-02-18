package com.example.replaycam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.replaycam.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaExtractor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val segmentDurationMs = 5_000L
    private val maxSegments = 4
    private val segmentFiles = ArrayDeque<File>()
    private var isContinuousRecording = false
    private var isStopping = false
    private var recordingStartTime: Long = 0 // Para controle do tempo de gravação
    private var pendingYoutubeReturn = false
    private var pendingRecordingStart = false

    private val youtubeLiveDashboardUrl = "https://studio.youtube.com/channel/UC/livestreaming/dashboard"

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions -> 
        val granted = permissions.values.all { it }
        if (granted) {
            startCamera()
        } else {
            toast("Permissões obrigatórias não concedidas")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configuração do fluxo de live
        binding.startButton.setOnClickListener { beginYoutubeLiveFlow() }
        binding.stopButton.setOnClickListener { stopContinuousRecording() }
        binding.replayButton.setOnClickListener { saveReplayBundle() }
        binding.openFolderButton.setOnClickListener { openVideoFolder() }
        updateVideoPathLabel()
        clearSegmentCache()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }


    override fun onResume() {
        super.onResume()

        if (pendingYoutubeReturn && pendingRecordingStart && !isContinuousRecording) {
            pendingYoutubeReturn = false
            status(getString(R.string.status_back_from_youtube))
            startContinuousRecording()
        }
    }

    private fun beginYoutubeLiveFlow() {
        if (isContinuousRecording) return

        pendingRecordingStart = true
        pendingYoutubeReturn = true
        status(getString(R.string.status_opening_youtube))
        toast(getString(R.string.youtube_login_hint))

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeLiveDashboardUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            pendingYoutubeReturn = false
            pendingRecordingStart = false
            status(getString(R.string.status_youtube_open_error))
            toast(getString(R.string.youtube_open_error))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val base = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            base.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return base.toTypedArray()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Obter rotação do dispositivo
            val rotation = windowManager.defaultDisplay.rotation
            val rotationDegrees = when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            // Aplica a rotação corretamente durante a gravação
            videoCapture?.targetRotation = rotationDegrees

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
                status("Status: câmera pronta")
            } catch (exc: Exception) {
                status("Erro ao abrir câmera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startContinuousRecording() {
        if (isContinuousRecording) return

        val capture = videoCapture ?: run {
            pendingRecordingStart = false
            toast("Câmera não inicializada")
            return
        }

        pendingRecordingStart = false
        clearSegmentCache()
        binding.replayButton.isEnabled = false
        isContinuousRecording = true
        isStopping = false
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.replayButton.isEnabled = true
        status(getString(R.string.status_recording_live))

        // Iniciar o contador de tempo
        recordingStartTime = System.currentTimeMillis()
        mainHandler.post(updateTimerRunnable) // Começa a atualizar o contador

        startSegment(capture)
    }

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (isContinuousRecording) {
                val elapsedTime = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsedTime / 1000) % 60
                val minutes = (elapsedTime / (1000 * 60)) % 60
                val timeFormatted = String.format("%02d:%02d", minutes, seconds)
                binding.recordingTimerText.text = timeFormatted
                mainHandler.postDelayed(this, 1000) // Atualiza a cada 1 segundo
            }
        }
    }

    private fun startSegment(capture: VideoCapture<Recorder>) {
        val segmentFile = createSegmentFile()

        val outputOptions = FileOutputOptions.Builder(segmentFile).build()
        var pendingRecording: PendingRecording = capture.output.prepareRecording(this, outputOptions)
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError()) {
                    status("Erro no segmento: ${event.error}")
                    segmentFile.delete()
                } else {
                    onSegmentSaved(segmentFile, event.outputResults.outputUri)
                }

                if (isContinuousRecording && !isStopping) {
                    startSegment(capture)
                }
            }
        }

        mainHandler.postDelayed({
            if (isContinuousRecording && !isStopping) {
                activeRecording?.stop()
            }
        }, segmentDurationMs)
    }

    private fun onSegmentSaved(file: File, uri: Uri) {
        val hasValidFile = file.exists() && file.length() > 0
        val hasValidUri = uri != Uri.EMPTY

        if (!hasValidFile && !hasValidUri) {
            status("Segmento inválido, descartado")
            return
        }

        segmentFiles.addLast(file)
        while (segmentFiles.size > maxSegments) {
            val removed = segmentFiles.removeFirst()
            removed.delete()
        }
        status("Status: buffer ativo (${segmentFiles.size * 5}s)")
    }

    private fun stopContinuousRecording() {
        if (!isContinuousRecording) return

        isStopping = true
        isContinuousRecording = false
        activeRecording?.stop()
        activeRecording = null

        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.replayButton.isEnabled = false
        status(getString(R.string.status_live_stopped))
        clearSegmentCache()

        // Parar o contador
        mainHandler.removeCallbacks(updateTimerRunnable)
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
            status("Erro ao montar replay único")
            toast("Falha ao montar replay de 20s")
            return
        }

        val outputName = "replay_${stamp}.mp4"
        val savedUri = saveVideoToPublicGallery(mergedReplay, outputName)
        mergedReplay.delete()

        if (savedUri == null) {
            status("Erro ao salvar replay na galeria")
            toast("Falha ao salvar replay na galeria")
            return
        }

        status("Status: replay único salvo na galeria")
        toast("Replay salvo em ${getPublicReplayPathLabel()}")
    }

    private fun mergeSegmentsIntoSingleVideo(segments: List<File>, outputFile: File): Boolean {
        if (segments.isEmpty()) return false

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Aqui aplicamos a rotação ao muxer
        val rotation = windowManager.defaultDisplay.rotation
        val rotationDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        muxer.setOrientationHint(rotationDegrees)

        val bufferSize = 2 * 1024 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var started = false
        var videoPtsOffset = 0L
        var audioPtsOffset = 0L

        try {
            for (segment in segments) {
                if (!segment.exists() || segment.length() == 0L) continue

                val extractor = MediaExtractor()
                extractor.setDataSource(segment.absolutePath)

                var srcVideoTrack = -1
                var srcAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString("mime") ?: continue
                    if (mime.startsWith("video/")) {
                        srcVideoTrack = i
                        if (videoTrackIndex == -1) {
                            videoTrackIndex = muxer.addTrack(format)
                        }
                    } else if (mime.startsWith("audio/")) {
                        srcAudioTrack = i
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = muxer.addTrack(format)
                        }
                    }
                }

                if (!started && (videoTrackIndex != -1 || audioTrackIndex != -1)) {
                    muxer.start()
                    started = true
                }

                if (!started) {
                    extractor.release()
                    continue
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
        binding.videoPathText.text = getString(
            R.string.video_path,
            getPublicReplayPathLabel()
        )
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
        status(getString(R.string.video_path_copied, path))
    }

    private fun createSegmentFile(): File {
        val segmentsDir = getSegmentsDirectory()
        if (!segmentsDir.exists()) segmentsDir.mkdirs()
        return File(segmentsDir, "segment_${timestamp()}.mp4")
    }

    private fun getSegmentsDirectory(): File = File(cacheDir, "replay_segments_cache")

    private fun clearSegmentCache() {
        activeRecording?.close()
        activeRecording = null
        segmentFiles.forEach { it.delete() }
        segmentFiles.clear()

        val dir = getSegmentsDirectory()
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun getOutputDirectory(): File {
        val movieDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return movieDir ?: filesDir
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

    private fun status(text: String) {
        binding.statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSegmentCache()
        cameraExecutor.shutdown()
    }
}
