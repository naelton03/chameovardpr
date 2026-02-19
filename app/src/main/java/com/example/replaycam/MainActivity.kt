package com.example.replaycam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
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
import androidx.lifecycle.lifecycleScope
import com.example.replaycam.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private lateinit var youtubeLiveHandler: YouTubeLiveHandler
    private var rtmpStreamEngine: RtmpStreamEngine? = null
    private var signedAccount: GoogleSignInAccount? = null
    private var activeLiveSession: LiveSessionInfo? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val segmentDurationMs = 5_000L
    private val maxSegments = 4
    private val segmentFiles = ArrayDeque<File>()
    private val segmentLock = Any()
    private val diagnosticsLogLock = Any()
    private var isContinuousRecording = false
    private var isStopping = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val tempTenths = intent.getIntExtra("temperature", -1)
            if (tempTenths <= 0) return
            val tempCelsius = tempTenths / 10f
            if (tempCelsius >= 45f) {
                Log.w(tag, "Thermal throttle triggered temp=$tempCelsius")
                appendDiagnosticLog("Thermal throttle acionado: ${tempCelsius}C")
                rtmpStreamEngine?.setBitrateOnFly(1_200_000)
                toast(getString(R.string.temperature_warning, tempCelsius))
            }
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

    private val manageAllFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ErrorFileLogger.canWriteRoot(this)) {
            appendDiagnosticLog("Permissão para log na raiz concedida: ${ErrorFileLogger.primaryRootPath()}")
            toast("Log na raiz habilitado")
        } else {
            appendDiagnosticLog("Permissão para log na raiz NÃO concedida")
            toast("Sem permissão para gravar log na raiz")
        }
    }

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val parsedAccount = youtubeLiveHandler.parseSignInResult(result.data)
        val fallbackAccount = GoogleSignIn.getLastSignedInAccount(this)
        val account = parsedAccount ?: fallbackAccount

        if (account != null) {
            signedAccount = account
            val idToken = youtubeLiveHandler.lastIdToken ?: account.idToken
            if (idToken.isNullOrBlank()) {
                appendDiagnosticLog("Google Sign-In concluído sem idToken. Verifique google_web_client_id (Web Client).")
                ErrorFileLogger.logInfo(this, "GOOGLE_SIGN_IN_ID_TOKEN", "idToken ausente")
            } else {
                ErrorFileLogger.logInfo(this, "GOOGLE_SIGN_IN_ID_TOKEN", "idToken capturado com sucesso")
            }
            ErrorFileLogger.logInfo(this, "GOOGLE_SIGN_IN_RESULT", "conta recebida com sucesso")
            lifecycleScope.launch {
                startLiveFlow(account)
            }
            return@registerForActivityResult
        }

        val signInStatusCode = youtubeLiveHandler.lastSignInStatusCode
        if (result.resultCode == RESULT_CANCELED && signInStatusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            Log.w(tag, "Google login cancelado pelo usuário")
            appendDiagnosticLog("Google Sign-In cancelado pelo usuário")
            toast("Login Google cancelado")
            return@registerForActivityResult
        }

        val hint = youtubeLiveHandler.signInErrorHint(signInStatusCode)
        Log.e(tag, "Falha ao autenticar Google. resultCode=${result.resultCode} statusCode=$signInStatusCode hint=$hint")
        appendDiagnosticLog("Falha ao autenticar Google. resultCode=${result.resultCode} statusCode=$signInStatusCode hint=$hint")
        if (signInStatusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
            val oauthDebugInfo = youtubeLiveHandler.oauthDebugInfo()
            appendDiagnosticLog("GOOGLE_OAUTH_DEBUG_INFO: $oauthDebugInfo")
            appendDiagnosticLog(youtubeLiveHandler.oauthSetupChecklist())
            status("Erro OAuth (10): ajuste package/SHA-1/SHA-256 no Google Cloud")
            toast(getString(R.string.oauth_developer_error))
        } else {
            toast(hint)
        }
    }

    private fun ensureRootLogPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (ErrorFileLogger.canWriteRoot(this)) {
            appendDiagnosticLog("Log na raiz disponível em ${ErrorFileLogger.primaryRootPath()}")
            return
        }

        appendDiagnosticLog("Solicitando permissão MANAGE_EXTERNAL_STORAGE para log na raiz")
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching {
            manageAllFilesPermissionLauncher.launch(intent)
        }.onFailure {
            manageAllFilesPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ErrorFileLogger.installGlobalHandlers(this)
        ErrorFileLogger.logInfo(this, "APP_START", "MainActivity criada")
        ensureRootLogPermission()

        cameraExecutor = Executors.newSingleThreadExecutor()
        youtubeLiveHandler = YouTubeLiveHandler(this)
        rtmpStreamEngine = RtmpStreamEngine(binding.streamSurface, streamCallbacks)
        signedAccount = GoogleSignIn.getLastSignedInAccount(this)

        binding.startButton.setOnClickListener { runUiAction("BTN_START_RECORDING") { startContinuousRecording() } }
        binding.stopButton.setOnClickListener { runUiAction("BTN_STOP_RECORDING") { stopContinuousRecording() } }
        binding.replayButton.setOnClickListener { runUiAction("BTN_SAVE_REPLAY") { saveReplayBundle() } }
        binding.openFolderButton.setOnClickListener { runUiAction("BTN_OPEN_FOLDER") { openVideoFolder() } }
        binding.toggleLiveButton.setOnClickListener { runUiAction("BTN_TOGGLE_LIVE") { handleLiveToggleClick() } }
        setupLiveConfigUi()
        updateLiveButtonUi(false)
        updateVideoPathLabel()
        clearSegmentCache()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private val streamCallbacks = object : RtmpStreamEngine.Callbacks {
        override fun onConnected() {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_CONNECTED", "RTMP conectado")
            runOnUiThread {
                updateLiveButtonUi(true)
                status("Status: live conectada")
            }
        }

        override fun onDisconnected() {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_DISCONNECTED", "RTMP desconectado")
            runOnUiThread {
                updateLiveButtonUi(false)
                status("Status: live desconectada")
            }
        }

        override fun onConnectionFailed(reason: String) {
            ErrorFileLogger.logError(this@MainActivity, "LIVE_CONNECTION_FAILED", IllegalStateException(reason))
            runOnUiThread {
                updateLiveButtonUi(false)
                status("Live falhou: $reason")
            }
        }

        override fun onAuthError() {
            ErrorFileLogger.logError(this@MainActivity, "LIVE_AUTH_ERROR", IllegalStateException("Erro de autenticação RTMP"))
            runOnUiThread { toast("Erro de autenticação RTMP") }
        }

        override fun onAuthSuccess() {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_AUTH_SUCCESS", "Autenticação RTMP OK")
            runOnUiThread { toast("Autenticação RTMP OK") }
        }

        override fun onRetrying(delayMs: Long, reason: String) {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_RETRYING", "delay=${delayMs}ms reason=$reason")
            runOnUiThread { status("Reconectando live em ${delayMs}ms ($reason)") }
        }
    }

    private fun runUiAction(action: String, block: () -> Unit) {
        runCatching {
            ErrorFileLogger.logInfo(this, action, "ação iniciada")
            block()
        }.onFailure { error ->
            ErrorFileLogger.logError(this, action, error)
            appendDiagnosticLog("Falha na ação $action: ${error.message}", error)
            status("Erro na ação $action: ${error.message}")
            toast("Erro na ação: $action")
        }
    }

    private fun handleLiveToggleClick() {
        val streamer = rtmpStreamEngine ?: return

        if (streamer.isStreaming()) {
            Log.i(tag, "Solicitado stop da live")
            streamer.stopStream()
            activeLiveSession = null
            updateLiveButtonUi(false)
            status(getString(R.string.live_stopped))
            return
        }

        if (signedAccount == null && !youtubeLiveHandler.isAuthenticated()) {
            toast(getString(R.string.live_requires_auth))
            Log.i(tag, "Iniciando fluxo OAuth Google Sign-In")
            signInLauncher.launch(youtubeLiveHandler.authIntent())
            return
        }

        lifecycleScope.launch {
            startLiveFlow(signedAccount ?: GoogleSignIn.getLastSignedInAccount(this@MainActivity))
        }
    }

    private suspend fun startLiveFlow(account: GoogleSignInAccount?) {
        val safeAccount = account ?: run {
            toast(getString(R.string.live_requires_auth))
            return
        }

        withContext(Dispatchers.Main) {
            if (activeLiveSession == null) {
                Log.i(tag, "Criando sessão YouTube Live")
                status("Status: criando sessão YouTube Live...")
            } else {
                Log.i(tag, "Reutilizando sessão YouTube Live existente")
                status("Status: reutilizando sessão de live existente...")
            }
            binding.toggleLiveButton.isEnabled = false
        }

        val liveTitle = selectedLiveTitle()
        val privacyStatus = selectedPrivacyStatus()

        runCatching {
            activeLiveSession ?: youtubeLiveHandler.createLiveSession(
                safeAccount,
                youtubeLiveHandler.lastIdToken ?: safeAccount.idToken,
                liveTitle,
                privacyStatus
            )
        }.onSuccess { session ->
            signedAccount = safeAccount
            activeLiveSession = session
            val endpoint = buildRtmpEndpoint(session.rtmpServerUrl, session.streamKey)
            Log.i(tag, "Sessão ativa broadcast=${session.broadcastId} stream=${session.streamId}")
            Log.i(tag, "Stream key desta sessão (prefixo)=${session.streamKey.take(6)}...")
            Log.d("RTMP_DEBUG", "URL Final: $endpoint")
            delay(1_500)
            Log.i(tag, "Iniciando envio RTMP para endpoint=$endpoint")
            rtmpStreamEngine?.startStream(endpoint)
            withContext(Dispatchers.Main) {
                updateLiveButtonUi(true)
                status(getString(R.string.live_created))
            }
        }.onFailure { error ->
            withContext(Dispatchers.Main) {
                updateLiveButtonUi(false)
                Log.e(tag, "Erro ao iniciar live", error)
                appendDiagnosticLog("Erro ao iniciar live: ${error.message}", error)
                status("Erro ao iniciar live: ${error.message}")
            }
        }

        withContext(Dispatchers.Main) {
            binding.toggleLiveButton.isEnabled = true
        }
    }

    private fun updateLiveButtonUi(isLive: Boolean) {
        binding.toggleLiveButton.text = if (isLive) getString(R.string.live_on) else getString(R.string.live_off)
        val color = if (isLive) android.R.color.holo_red_dark else android.R.color.darker_gray
        binding.toggleLiveButton.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun setupLiveConfigUi() {
        val privacyOptions = listOf(
            getString(R.string.privacy_public),
            getString(R.string.privacy_unlisted),
            getString(R.string.privacy_private)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, privacyOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.privacySpinner.adapter = adapter
        binding.privacySpinner.setSelection(1)
    }

    private fun selectedLiveTitle(): String {
        val typed = binding.liveTitleInput.text?.toString()?.trim().orEmpty()
        return typed.ifBlank { getString(R.string.default_live_title) }
    }

    private fun selectedPrivacyStatus(): String {
        return binding.privacySpinner.selectedItem?.toString()?.trim().orEmpty().ifBlank {
            getString(R.string.privacy_unlisted)
        }
    }

    private fun buildRtmpEndpoint(ingestionAddress: String, streamName: String): String {
        val server = ingestionAddress.trim().trimEnd('/').replaceFirst("rtmps://", "rtmp://")
        val key = streamName.trim().trimStart('/')
        return "$server/$key"
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
                ErrorFileLogger.logError(this, "START_CAMERA", exc)
                appendDiagnosticLog("Erro ao abrir câmera: ${exc.message}", exc)
                status("Erro ao abrir câmera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startContinuousRecording() {
        if (isContinuousRecording) return

        val capture = videoCapture ?: run {
            toast("Câmera não inicializada")
            return
        }

        clearSegmentCache()
        binding.replayButton.isEnabled = false
        isContinuousRecording = true
        isStopping = false
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.replayButton.isEnabled = true
        status("Status: gravando continuamente")

        startSegment(capture)
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
                    val segmentError = IllegalStateException("Finalize error code=${event.error}")
                    ErrorFileLogger.logError(this, "RECORD_SEGMENT_FINALIZE", segmentError)
                    appendDiagnosticLog("Erro no segmento: ${event.error}", segmentError)
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

        synchronized(segmentLock) {
            segmentFiles.addLast(file)
            while (segmentFiles.size > maxSegments) {
                val removed = segmentFiles.removeFirst()
                removed.delete()
            }
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
        status("Status: gravação parada")
        clearSegmentCache()
    }

    private fun saveReplayBundle() {
        val snapshot = synchronized(segmentLock) { segmentFiles.toList() }
        if (snapshot.size < maxSegments) {
            toast("Aguarde preencher 20s no buffer")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val stamp = timestamp()
            val mergedReplay = File(getSegmentsDirectory(), "replay_merged_${stamp}.mp4")
            val merged = mergeSegmentsIntoSingleVideo(snapshot, mergedReplay)
            if (!merged) {
                withContext(Dispatchers.Main) {
                    status("Erro ao montar replay único")
                    toast("Falha ao montar replay de 20s")
                }
                return@launch
            }

            val outputName = "replay_${stamp}.mp4"
            val savedUri = saveVideoToPublicGallery(mergedReplay, outputName)
            mergedReplay.delete()

            withContext(Dispatchers.Main) {
                if (savedUri == null) {
                    status("Erro ao salvar replay na galeria")
                    toast("Falha ao salvar replay na galeria")
                    return@withContext
                }

                status("Status: replay único salvo na galeria")
                toast("Replay salvo em ${getPublicReplayPathLabel()}")
            }
        }
    }

    private fun mergeSegmentsIntoSingleVideo(segments: List<File>, outputFile: File): Boolean {
        if (segments.isEmpty()) return false

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val rotationDegrees = 90
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
        } catch (error: Exception) {
            ErrorFileLogger.logError(this, "MERGE_SEGMENTS", error)
            appendDiagnosticLog("Erro ao juntar segmentos: ${error.message}", error)
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
        } catch (error: Exception) {
            ErrorFileLogger.logError(this, "SAVE_VIDEO_PUBLIC_GALLERY", error)
            appendDiagnosticLog("Erro ao salvar vídeo em galeria: ${error.message}", error)
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
        synchronized(segmentLock) {
            segmentFiles.forEach { it.delete() }
            segmentFiles.clear()
        }

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

    private fun status(text: String) {
        binding.statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun appendDiagnosticLog(message: String, error: Throwable? = null) {
        error?.let { ErrorFileLogger.logError(this, "DIAGNOSTIC", it) } ?: ErrorFileLogger.logInfo(this, "DIAGNOSTIC", message)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logFile = File(getExternalFilesDir(null) ?: filesDir, "replaycam_diagnostics.txt")
        val stacktrace = error?.stackTraceToString()?.trim().orEmpty()
        val entry = buildString {
            append("[")
            append(timestamp)
            append("] ")
            append(message)
            if (stacktrace.isNotBlank()) {
                append("\n")
                append(stacktrace)
            }
            append("\n")
        }

        synchronized(diagnosticsLogLock) {
            runCatching {
                FileWriter(logFile, true).use { writer -> writer.append(entry) }
            }.onFailure { writeError ->
                Log.e(tag, "Falha ao gravar log em arquivo", writeError)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        clearSegmentCache()
        rtmpStreamEngine?.close()
        ErrorFileLogger.logInfo(this, "APP_DESTROY", "MainActivity destruída")
        cameraExecutor.shutdown()
    }
}
