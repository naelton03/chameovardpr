package com.example.replaycam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.util.Log
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.LiveBroadcast
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var shouldStartRecordingAfterYoutube = false
    private var isYoutubeLiveRequested = false
    private var activeBroadcastId: String? = null

    private val youtubeScope = Scope("https://www.googleapis.com/auth/youtube")
    private lateinit var googleSignInOptions: GoogleSignInOptions

    private val youtubeSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            triggerYoutubeLiveStart(account.id ?: account.email ?: "")
        } catch (exc: ApiException) {
            shouldStartRecordingAfterYoutube = false
            isYoutubeLiveRequested = false
            status(getString(R.string.status_youtube_login_failed))
            toast(getString(R.string.youtube_login_failed))
            Log.e("ReplayCam", "Falha no login do Google para YouTube", exc)
        }
    }

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

        googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(youtubeScope)
            .build()

        // Gravar e fazer live usam o mesmo botão inicial
        binding.startButton.setOnClickListener { onStartActionClicked() }
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


    private fun onStartActionClicked() {
        if (isContinuousRecording) return

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.live_prompt_title))
            .setMessage(getString(R.string.live_prompt_message))
            .setNegativeButton(getString(R.string.live_prompt_no)) { _, _ ->
                shouldStartRecordingAfterYoutube = false
                isYoutubeLiveRequested = false
                status(getString(R.string.status_recording_only))
                startContinuousRecording()
            }
            .setPositiveButton(getString(R.string.live_prompt_yes)) { _, _ ->
                shouldStartRecordingAfterYoutube = true
                isYoutubeLiveRequested = true
                startYoutubeIntegration()
            }
            .show()
    }

    private fun startYoutubeIntegration() {
        status(getString(R.string.status_opening_youtube))
        toast(getString(R.string.youtube_login_hint))

        val account = GoogleSignIn.getLastSignedInAccount(this)
        val hasPermission = account != null && GoogleSignIn.hasPermissions(account, youtubeScope)
        if (hasPermission) {
            triggerYoutubeLiveStart(account.id ?: account.email ?: "")
            return
        }

        status(getString(R.string.status_youtube_login_needed))
        val signInClient = GoogleSignIn.getClient(this, googleSignInOptions)
        youtubeSignInLauncher.launch(signInClient.signInIntent)
    }

    private fun triggerYoutubeLiveStart(accountTag: String) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val googleAccount = account?.account
        if (googleAccount == null) {
            shouldStartRecordingAfterYoutube = false
            isYoutubeLiveRequested = false
            status(getString(R.string.status_youtube_login_failed))
            toast(getString(R.string.youtube_login_failed))
            return
        }

        status(getString(R.string.status_starting_live_from_app))
        cameraExecutor.execute {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(this, listOf(youtubeScope.scopeUri)).apply {
                    selectedAccount = googleAccount
                }
                val youtube = YouTube.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                val broadcast = findStartableBroadcast(youtube)
                if (broadcast == null) {
                    runOnUiThread {
                        shouldStartRecordingAfterYoutube = false
                        isYoutubeLiveRequested = false
                        status(getString(R.string.status_no_broadcast_ready))
                        toast(getString(R.string.status_no_broadcast_ready))
                    }
                    return@execute
                }

                val broadcastId = broadcast.id ?: run {
                    runOnUiThread {
                        shouldStartRecordingAfterYoutube = false
                        isYoutubeLiveRequested = false
                        status(getString(R.string.status_no_broadcast_ready))
                        toast(getString(R.string.status_no_broadcast_ready))
                    }
                    return@execute
                }

                val lifecycle = broadcast.status?.lifeCycleStatus.orEmpty()
                if (lifecycle == "live") {
                    activeBroadcastId = broadcastId
                    runOnUiThread {
                        status(getString(R.string.status_back_from_youtube))
                        startContinuousRecording()
                    }
                    return@execute
                }

                if (lifecycle == "ready" || lifecycle == "testing") {
                    youtube.liveBroadcasts().transition("live", broadcastId, "status").execute()
                    activeBroadcastId = broadcastId
                    runOnUiThread {
                        status(getString(R.string.status_back_from_youtube))
                        startContinuousRecording()
                    }
                    return@execute
                }

                runOnUiThread {
                    shouldStartRecordingAfterYoutube = false
                    isYoutubeLiveRequested = false
                    status(getString(R.string.status_broadcast_not_ready, lifecycle))
                    toast(getString(R.string.status_broadcast_not_ready, lifecycle))
                }
            } catch (exc: Exception) {
                Log.e("ReplayCam", "Falha ao iniciar live pelo app ($accountTag)", exc)
                runOnUiThread {
                    shouldStartRecordingAfterYoutube = false
                    isYoutubeLiveRequested = false
                    status(getString(R.string.status_start_live_failed))
                    toast(getString(R.string.status_start_live_failed))
                }
            }
        }
    }

    private fun findStartableBroadcast(youtube: YouTube): LiveBroadcast? {
        val broadcasts = youtube.liveBroadcasts()
            .list("id,snippet,status")
            .setMine(true)
            .setBroadcastStatus("all")
            .setMaxResults(25L)
            .execute()
            .items
            .orEmpty()

        return broadcasts.firstOrNull {
            val state = it.status?.lifeCycleStatus.orEmpty()
            state == "live" || state == "ready" || state == "testing"
        }
            ?: broadcasts.firstOrNull { it.status?.lifeCycleStatus == "created" }
    }

    private fun pauseYoutubeLiveIfNeeded() {
        if (!isYoutubeLiveRequested) return

        val broadcastId = activeBroadcastId
        isYoutubeLiveRequested = false
        activeBroadcastId = null

        if (broadcastId == null) {
            toast(getString(R.string.youtube_pause_open_error))
            return
        }

        cameraExecutor.execute {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this)
                val googleAccount = account?.account ?: return@execute
                val credential = GoogleAccountCredential.usingOAuth2(this, listOf(youtubeScope.scopeUri)).apply {
                    selectedAccount = googleAccount
                }
                val youtube = YouTube.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(getString(R.string.app_name)).build()

                youtube.liveBroadcasts().transition("complete", broadcastId, "status").execute()
                runOnUiThread { toast(getString(R.string.youtube_pause_hint)) }
            } catch (exc: Exception) {
                Log.e("ReplayCam", "Falha ao pausar/encerrar live pelo app", exc)
                runOnUiThread { toast(getString(R.string.youtube_pause_open_error)) }
            }
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
            shouldStartRecordingAfterYoutube = false
            toast("Câmera não inicializada")
            return
        }

        shouldStartRecordingAfterYoutube = false
        clearSegmentCache()
        binding.replayButton.isEnabled = false
        isContinuousRecording = true
        isStopping = false
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.replayButton.isEnabled = true
        val recordingStatus = if (isYoutubeLiveRequested) {
            getString(R.string.status_recording_live)
        } else {
            getString(R.string.status_recording_only)
        }
        status(recordingStatus)

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
        shouldStartRecordingAfterYoutube = false
        activeRecording?.stop()
        activeRecording = null

        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.replayButton.isEnabled = false
        status(getString(R.string.status_live_stopped))
        pauseYoutubeLiveIfNeeded()
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
