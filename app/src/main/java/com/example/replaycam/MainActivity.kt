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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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

    data class SegmentEntry(
        val file: File,
        val startUs: Long,
        val endUs: Long
    )

    data class SegmentSlice(
        val file: File,
        val trimStartUs: Long,
        val trimEndUs: Long
    )

    data class RuntimeCameraCapability(
        val cameraId: String,
        val lensLabel: String,
        val hasLogicalMultiCamera: Boolean,
        val minZoomRatio: Float,
        val maxZoomRatio: Float,
        val zoomRangeText: String,
        val maxFps: Int,
        val maxResolution: String,
        val fpsRanges: List<Range<Int>>,
        val supportsUhd: Boolean,
        val supportsFhd: Boolean,
        val supportsHd: Boolean
    ) {
        fun displayLabel(): String {
            val multi = if (hasLogicalMultiCamera) "multi" else "single"
            return "$lensLabel • $zoomRangeText • ${maxFps}fps • $maxResolution • $multi"
        }
    }

    private val tag = "MainActivity"
    private val prefsName = "replaycam_prefs"
    private val prefAutoUpload = "auto_upload_enabled"
    private val prefReplayDurationSec = "replay_duration_sec"
    private val prefVideoQuality = "video_quality"
    private val prefVideoFps = "video_fps"

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var youtubeLiveHandler: YouTubeLiveHandler? = null
    private var rtmpStreamEngine: RtmpStreamEngine? = null
    private var signedAccount: GoogleSignInAccount? = null
    private lateinit var driveUploadManager: DriveUploadManager
    private lateinit var appPrefs: android.content.SharedPreferences
    private var autoUploadEnabled = false
    private val replayDurationOptionsSec = intArrayOf(10, 15, 20, 25, 30, 35, 40)
    private val replayTypeOptions = arrayOf("GOL", "DEFESA", "LANCE")
    private var replayDurationSec = 20
    private var selectedVideoQuality = "AUTO"
    private var selectedVideoFps = 30
    private var activeLiveSession: LiveSessionInfo? = null
    private var transitionToLiveJob: Job? = null
    private var transitionRequestedAfterMediaFlow = false
    private var hasRetriedWithPlainRtmp = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionSegments = ArrayDeque<SegmentEntry>()
    private val segmentLock = Any()
    private val diagnosticsLogLock = Any()
    private var isContinuousRecording = false
    private var isStopping = false
    private var pendingSegmentFinalize: CompletableDeferred<Unit>? = null
    private var recordingStartedAtMs: Long = 0L
    private var activeSegmentStartedAtMs: Long = 0L
    private var finalizedSessionDurationUs: Long = 0L
    private var pausedByBackground = false
    private var shouldResumeAfterBackground = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var selectedCameraCapability: RuntimeCameraCapability? = null
    private var availableCameraCapabilities: List<RuntimeCameraCapability> = emptyList()
    private var availableZoomRatios: List<Float> = listOf(1f)
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (!isContinuousRecording) return
            updateRecordingTimer()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

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
        if (!FeatureToggles.isLiveEnabled) return@registerForActivityResult

        val liveHandler = youtubeLiveHandler ?: return@registerForActivityResult
        val parsedAccount = liveHandler.parseSignInResult(result.data)
        val fallbackAccount = GoogleSignIn.getLastSignedInAccount(this)
        val account = parsedAccount ?: fallbackAccount

        if (account != null) {
            signedAccount = account
            val idToken = liveHandler.lastIdToken ?: account.idToken
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

        val signInStatusCode = liveHandler.lastSignInStatusCode
        if (result.resultCode == RESULT_CANCELED && signInStatusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            Log.w(tag, "Google login cancelado pelo usuário")
            appendDiagnosticLog("Google Sign-In cancelado pelo usuário")
            toast("Login Google cancelado")
            return@registerForActivityResult
        }

        val hint = liveHandler.signInErrorHint(signInStatusCode)
        Log.e(tag, "Falha ao autenticar Google. resultCode=${result.resultCode} statusCode=$signInStatusCode hint=$hint")
        appendDiagnosticLog("Falha ao autenticar Google. resultCode=${result.resultCode} statusCode=$signInStatusCode hint=$hint")
        if (signInStatusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
            val oauthDebugInfo = liveHandler.oauthDebugInfo()
            appendDiagnosticLog("GOOGLE_OAUTH_DEBUG_INFO: $oauthDebugInfo")
            appendDiagnosticLog(liveHandler.oauthSetupChecklist())
            status("Erro OAuth (10): ajuste package/SHA-1/SHA-256 no Google Cloud")
            toast(getString(R.string.oauth_developer_error))
        } else {
            toast(hint)
        }
    }


    private val driveSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = driveUploadManager.parseSignInResult(result.data)
        if (account != null) {
            ErrorFileLogger.logInfo(this, "DRIVE_SIGN_IN", "Conta vinculada com sucesso email=${account.email ?: "sem-email"}")
            appendDiagnosticLog("Drive vinculado com sucesso: ${account.email ?: "sem-email"}")
            toast("Conta Google vinculada para uploads no Drive")
            status("Status: conta Drive vinculada (${account.email ?: "sem e-mail"})")
            return@registerForActivityResult
        }

        val statusCode = driveUploadManager.lastSignInStatusCode
        val statusHint = driveUploadManager.signInErrorHint(statusCode)
        val errorMessage = driveUploadManager.lastSignInErrorMessage ?: "sem mensagem"
        val reasonLog = "Drive vinculação falhou/cancelada. resultCode=${result.resultCode} statusCode=$statusCode hint=$statusHint message=$errorMessage"

        appendDiagnosticLog(reasonLog)
        ErrorFileLogger.logInfo(this, "DRIVE_SIGN_IN", reasonLog)

        if (result.resultCode == RESULT_CANCELED && statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
            toast("Vinculação com Google Drive cancelada")
            return@registerForActivityResult
        }

        if (statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
            val oauthDebugInfo = driveUploadManager.oauthDebugInfo()
            appendDiagnosticLog("DRIVE_OAUTH_DEBUG_INFO: $oauthDebugInfo")
            appendDiagnosticLog(driveUploadManager.oauthSetupChecklist())
            ErrorFileLogger.logInfo(this, "DRIVE_OAUTH_DEBUG_INFO", oauthDebugInfo)
            ErrorFileLogger.logInfo(this, "DRIVE_OAUTH_CHECKLIST", driveUploadManager.oauthSetupChecklist())
        }

        toast("Falha ao vincular conta Google Drive: $statusHint")
        status("Falha vinculação Drive: $statusHint")
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
        appPrefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        autoUploadEnabled = appPrefs.getBoolean(prefAutoUpload, false)
        replayDurationSec = appPrefs.getInt(prefReplayDurationSec, 20).let { configured ->
            if (replayDurationOptionsSec.contains(configured)) configured else 20
        }
        selectedVideoQuality = appPrefs.getString(prefVideoQuality, "AUTO") ?: "AUTO"
        selectedVideoFps = appPrefs.getInt(prefVideoFps, 30).let { configured -> if (configured == 60) 60 else 30 }
        driveUploadManager = DriveUploadManager(this)
        if (FeatureToggles.isLiveEnabled) {
            youtubeLiveHandler = YouTubeLiveHandler(this)
            rtmpStreamEngine = RtmpStreamEngine(binding.streamSurface, streamCallbacks)
        }
        signedAccount = GoogleSignIn.getLastSignedInAccount(this)

        binding.startButton.setOnClickListener {
            runUiAction("BTN_TOGGLE_RECORDING") {
                if (isContinuousRecording) stopContinuousRecording() else startContinuousRecording(resetBuffer = true)
            }
        }
        binding.replayButton.setOnClickListener { runUiAction("BTN_SAVE_REPLAY") { showReplayTypeDialog() } }
        updateReplayButtonLabel()
        binding.toggleLiveButton.setOnClickListener { runUiAction("BTN_TOGGLE_LIVE") { handleLiveToggleClick() } }
        binding.menuButton.setOnClickListener { showMainMenu(it) }
        binding.zoomOptionsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val zoomRatio = availableZoomRatios.getOrNull(position) ?: return
                applyZoomRatio(zoomRatio)
            }
        }
        binding.openFolderButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE
        binding.videoPathText.visibility = View.GONE
        configureLiveUi()
        updatePrimaryRecordButton()
        setupCameraCapabilityUi()
        clearSegmentCache()
        updateRecordingTimer()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }


    private fun showMainMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_config_auto_upload -> {
                    showAutoUploadConfigDialog()
                    true
                }
                R.id.menu_config_replay_duration -> {
                    showReplayDurationDialog()
                    true
                }
                R.id.menu_config_quality -> {
                    showQualityConfigDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAutoUploadConfigDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_upload_config, null)
        val accountText = dialogView.findViewById<android.widget.TextView>(R.id.driveAccountText)
        val linkButton = dialogView.findViewById<android.widget.Button>(R.id.linkGoogleButton)
        val autoUploadSwitch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.autoUploadSwitch)

        val linkedEmail = driveUploadManager.linkedEmail()
        accountText.text = if (linkedEmail.isNullOrBlank()) {
            "Nenhuma conta vinculada"
        } else {
            "Conta vinculada: $linkedEmail"
        }
        linkButton.text = if (linkedEmail.isNullOrBlank()) "Vincular conta Google" else "Desvincular conta"

        autoUploadSwitch.isChecked = autoUploadEnabled
        autoUploadSwitch.setOnCheckedChangeListener { _, checked ->
            autoUploadEnabled = checked
            appPrefs.edit().putBoolean(prefAutoUpload, checked).apply()
        }

        linkButton.setOnClickListener {
            if (driveUploadManager.isLinked()) {
                lifecycleScope.launch {
                    driveUploadManager.signOut()
                    toast("Conta Google Drive desvinculada")
                }
            } else {
                driveSignInLauncher.launch(driveUploadManager.authIntent())
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Configurar upload automático")
            .setView(dialogView)
            .setPositiveButton("Fechar", null)
            .show()
    }


    private fun showReplayDurationDialog() {
        val labels = replayDurationOptionsSec.map { seconds -> getString(R.string.replay_duration_option, seconds) }.toTypedArray()
        val selectedIndex = replayDurationOptionsSec.indexOf(replayDurationSec).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.replay_duration_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                replayDurationSec = replayDurationOptionsSec[which]
                appPrefs.edit().putInt(prefReplayDurationSec, replayDurationSec).apply()
                updateReplayButtonLabel()
                toast(getString(R.string.replay_duration_updated, replayDurationSec))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showQualityConfigDialog() {
        val capability = selectedCameraCapability
        val availableQualities = supportedQualityOptions(capability)
        val availableFps = supportedFpsOptions(capability)

        var selectedQualityIdx = availableQualities.indexOf(selectedVideoQuality).let { if (it >= 0) it else 0 }
        var selectedFpsIdx = availableFps.indexOf(selectedVideoFps).let { if (it >= 0) it else 0 }

        val qualityLabels = availableQualities.map { qualityOptionLabel(it) }.toTypedArray()
        val fpsLabels = availableFps.map { "$it FPS" }.toTypedArray()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 12, 24, 0)
        }

        val qualityTitle = android.widget.TextView(this).apply {
            text = getString(R.string.quality_resolution_title)
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        container.addView(qualityTitle)

        val qualityGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        qualityLabels.forEachIndexed { index, label ->
            qualityGroup.addView(android.widget.RadioButton(this).apply {
                id = index
                text = label
                isChecked = index == selectedQualityIdx
            })
        }
        qualityGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId >= 0) selectedQualityIdx = checkedId
        }
        container.addView(qualityGroup)

        val fpsTitle = android.widget.TextView(this).apply {
            text = getString(R.string.quality_fps_title)
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        container.addView(fpsTitle)

        val fpsGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        fpsLabels.forEachIndexed { index, label ->
            fpsGroup.addView(android.widget.RadioButton(this).apply {
                id = index + 100
                text = label
                isChecked = index == selectedFpsIdx
            })
        }
        fpsGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId >= 100) selectedFpsIdx = checkedId - 100
        }
        container.addView(fpsGroup)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_config_quality)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                selectedVideoQuality = availableQualities[selectedQualityIdx]
                selectedVideoFps = availableFps[selectedFpsIdx]
                appPrefs.edit()
                    .putString(prefVideoQuality, selectedVideoQuality)
                    .putInt(prefVideoFps, selectedVideoFps)
                    .apply()

                cameraProvider?.let { provider -> bindSelectedCamera(provider) }
                toast(getString(R.string.quality_updated, qualityOptionLabel(selectedVideoQuality), selectedVideoFps))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun maybeUploadVideoToDrive(savedUri: Uri, displayName: String) {
        if (!autoUploadEnabled) return
        if (!driveUploadManager.isLinked()) {
            status("Status: upload automático ativo, mas sem conta Drive vinculada")
            return
        }

        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        lifecycleScope.launch {
            val result = driveUploadManager.uploadVideo(
                videoUri = savedUri,
                displayName = displayName,
                dateFolderName = dateFolder
            )

            result.onSuccess {
                status("Status: vídeo enviado ao Google Drive")
                toast("Upload para Drive concluído")
            }.onFailure { error ->
                status("Erro upload Drive: ${error.message}")
                appendDiagnosticLog("Falha upload Drive: ${error.message}", error)
                toast("Falha no upload para Drive")
            }
        }
    }

    private fun configureLiveUi() {
        if (!FeatureToggles.isLiveEnabled) {
            binding.liveConfigContainer.visibility = View.GONE
            binding.toggleLiveButton.visibility = View.GONE
            binding.streamSurface.visibility = View.GONE
            return
        }

        binding.liveConfigContainer.visibility = View.VISIBLE
        binding.toggleLiveButton.visibility = View.VISIBLE
        binding.streamSurface.visibility = View.VISIBLE
        setupLiveConfigUi()
        updateLiveButtonUi(false)
    }

    private val streamCallbacks = object : RtmpStreamEngine.Callbacks {
        override fun onConnected() {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_CONNECTED", "RTMP conectado")
            transitionToLiveJob?.cancel()
            transitionRequestedAfterMediaFlow = false
            runOnUiThread {
                updateLiveButtonUi(true)
                status("Status: RTMP conectado; aguardando envio de mídia...")
            }
        }

        override fun onMediaFlowing(bitrate: Long) {
            if (transitionRequestedAfterMediaFlow) return
            transitionRequestedAfterMediaFlow = true
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_MEDIA_FLOW", "bitrate=$bitrate")

            transitionToLiveJob?.cancel()
            transitionToLiveJob = lifecycleScope.launch {
                delay(1_000)
                val session = activeLiveSession ?: return@launch
                val account = signedAccount ?: GoogleSignIn.getLastSignedInAccount(this@MainActivity) ?: return@launch

                withContext(Dispatchers.Main) {
                    status("Status: mídia detectada, verificando transição no YouTube...")
                }

                val liveHandler = youtubeLiveHandler ?: return@launch
                val transitioned = liveHandler.transitionBroadcastToLive(
                    account = account,
                    idToken = liveHandler.lastIdToken ?: account.idToken,
                    broadcastId = session.broadcastId
                )

                withContext(Dispatchers.Main) {
                    if (transitioned) {
                        status("Status: ao vivo no YouTube")
                    } else {
                        val fallbackApplied = retryWithPlainRtmpIfNeeded()
                        transitionRequestedAfterMediaFlow = false
                        if (fallbackApplied) {
                            status("Status: retry automático em RTMP (sem TLS) aplicado; aguardando mídia...")
                        } else {
                            status("Status: mídia enviada, YouTube ainda em 'programado' (ver logs YOUTUBE_LIVE_*)")
                        }
                    }
                }
            }
        }

        override fun onDisconnected() {
            ErrorFileLogger.logInfo(this@MainActivity, "LIVE_DISCONNECTED", "RTMP desconectado")
            transitionRequestedAfterMediaFlow = false
            transitionToLiveJob?.cancel()
            runOnUiThread {
                updateLiveButtonUi(false)
                status("Status: live desconectada")
            }
        }

        override fun onConnectionFailed(reason: String) {
            ErrorFileLogger.logError(this@MainActivity, "LIVE_CONNECTION_FAILED", IllegalStateException(reason))
            transitionRequestedAfterMediaFlow = false
            transitionToLiveJob?.cancel()
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
        if (!FeatureToggles.isLiveEnabled) return

        val streamer = rtmpStreamEngine ?: return
        val liveHandler = youtubeLiveHandler ?: return

        if (streamer.isStreaming()) {
            Log.i(tag, "Solicitado stop da live")
            transitionRequestedAfterMediaFlow = false
            hasRetriedWithPlainRtmp = false
            transitionToLiveJob?.cancel()
            streamer.stopStream()
            activeLiveSession = null
            updateLiveButtonUi(false)
            status(getString(R.string.live_stopped))
            return
        }

        if (signedAccount == null && !liveHandler.isAuthenticated()) {
            toast(getString(R.string.live_requires_auth))
            Log.i(tag, "Iniciando fluxo OAuth Google Sign-In")
            signInLauncher.launch(liveHandler.authIntent())
            return
        }

        lifecycleScope.launch {
            startLiveFlow(signedAccount ?: GoogleSignIn.getLastSignedInAccount(this@MainActivity))
        }
    }

    private suspend fun startLiveFlow(account: GoogleSignInAccount?) {
        if (!FeatureToggles.isLiveEnabled) return

        val liveHandler = youtubeLiveHandler ?: return
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
            activeLiveSession ?: liveHandler.createLiveSession(
                safeAccount,
                liveHandler.lastIdToken ?: safeAccount.idToken,
                liveTitle,
                privacyStatus
            )
        }.onSuccess { session ->
            signedAccount = safeAccount
            activeLiveSession = session
            hasRetriedWithPlainRtmp = false
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
        if (!FeatureToggles.isLiveEnabled) return
        binding.toggleLiveButton.text = if (isLive) getString(R.string.live_on) else getString(R.string.live_off)
        val color = if (isLive) android.R.color.holo_red_dark else android.R.color.darker_gray
        binding.toggleLiveButton.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun setupLiveConfigUi() {
        if (!FeatureToggles.isLiveEnabled) return
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
        if (!FeatureToggles.isLiveEnabled) return getString(R.string.default_live_title)
        val typed = binding.liveTitleInput.text?.toString()?.trim().orEmpty()
        return typed.ifBlank { getString(R.string.default_live_title) }
    }

    private fun selectedPrivacyStatus(): String {
        if (!FeatureToggles.isLiveEnabled) return getString(R.string.privacy_unlisted)
        return binding.privacySpinner.selectedItem?.toString()?.trim().orEmpty().ifBlank {
            getString(R.string.privacy_unlisted)
        }
    }

    private fun buildRtmpEndpoint(ingestionAddress: String, streamName: String): String {
        val server = ingestionAddress.trim().trimEnd('/')
        val key = streamName.trim().trimStart('/')
        return "$server/$key"
    }

    private fun retryWithPlainRtmpIfNeeded(): Boolean {
        val session = activeLiveSession ?: return false
        if (hasRetriedWithPlainRtmp) return false
        if (!session.rtmpServerUrl.startsWith("rtmps://", ignoreCase = true)) return false

        val streamer = rtmpStreamEngine ?: return false
        hasRetriedWithPlainRtmp = true

        val fallbackServer = session.rtmpServerUrl.replaceFirst("rtmps://", "rtmp://")
        val fallbackEndpoint = buildRtmpEndpoint(fallbackServer, session.streamKey)
        ErrorFileLogger.logInfo(this, "LIVE_RTMP_FALLBACK", "Aplicando fallback para endpoint=$fallbackEndpoint")
        Log.w(tag, "Aplicando fallback RTMP sem TLS para tentar ativar ingestão YouTube")

        streamer.stopStream()
        streamer.startStream(fallbackEndpoint)
        return true
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
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            if (availableCameraCapabilities.isEmpty()) {
                setupCameraCapabilityUi()
            }
            bindSelectedCamera(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraCapabilityUi() {
        val capabilities = runCatching { detectCameraCapabilities() }.getOrElse { error ->
            appendDiagnosticLog("Falha ao detectar câmeras: ${error.message}", error)
            emptyList()
        }
        availableCameraCapabilities = capabilities

        // Oculto para UX simplificada: seleção manual de câmera não é mais exibida ao usuário.
        binding.cameraOptionsLabel.visibility = View.GONE
        binding.cameraOptionsSpinner.visibility = View.GONE

        selectedCameraCapability = capabilities.firstOrNull()
        setupZoomUiForCapability(selectedCameraCapability)
    }

    private fun setupZoomUiForCapability(capability: RuntimeCameraCapability?) {
        if (capability == null) {
            binding.zoomOptionsLabel.visibility = View.GONE
            binding.zoomOptionsSpinner.visibility = View.GONE
            availableZoomRatios = listOf(1f)
            return
        }

        val zoomLevels = buildZoomRatios(capability.minZoomRatio, capability.maxZoomRatio)
        availableZoomRatios = zoomLevels

        val labels = zoomLevels.map { "${formatZoomRatio(it)}x" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.zoomOptionsSpinner.adapter = adapter

        binding.zoomOptionsLabel.visibility = View.VISIBLE
        binding.zoomOptionsSpinner.visibility = View.VISIBLE

        val defaultIdx = zoomLevels.indexOfFirst { kotlin.math.abs(it - 1f) < 0.01f }.let { if (it >= 0) it else 0 }
        binding.zoomOptionsSpinner.setSelection(defaultIdx)
    }

    private fun buildZoomRatios(minZoom: Float, maxZoom: Float): List<Float> {
        val clampedMin = minZoom.coerceAtLeast(0.5f)
        val clampedMax = maxZoom.coerceAtLeast(clampedMin)
        val presets = listOf(0.5f, 0.7f, 1f, 1.2f, 1.5f, 2f, 3f, 4f, 5f, 8f, 10f)
        val dynamic = presets.filter { it in clampedMin..clampedMax }.toMutableSet()
        dynamic.add(clampedMin)
        dynamic.add(clampedMax)
        if (1f in clampedMin..clampedMax) dynamic.add(1f)
        return dynamic.toList().sorted()
    }

    private fun formatZoomRatio(value: Float): String {
        return if (kotlin.math.abs(value - value.toInt().toFloat()) < 0.01f) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }


    private fun qualityOptionLabel(value: String): String {
        return when (value) {
            "4K" -> "4K"
            "1080" -> "1080p"
            "720" -> "720p"
            else -> "Auto"
        }
    }

    private fun qualityToCameraX(value: String): Quality {
        return when (value) {
            "4K" -> Quality.UHD
            "1080" -> Quality.FHD
            "720" -> Quality.HD
            else -> Quality.UHD
        }
    }

    private fun supportedQualityOptions(capability: RuntimeCameraCapability?): List<String> {
        if (capability == null) return listOf("1080", "720")
        val options = mutableListOf<String>()
        if (capability.supportsUhd) options.add("4K")
        if (capability.supportsFhd) options.add("1080")
        if (capability.supportsHd) options.add("720")
        if (options.isEmpty()) options.add("1080")
        return options
    }

    private fun supportedFpsOptions(capability: RuntimeCameraCapability?): List<Int> {
        if (capability == null) return listOf(30)
        val options = mutableListOf<Int>()
        if (capability.fpsRanges.any { it.upper >= 30 }) options.add(30)
        if (capability.fpsRanges.any { it.upper >= 60 }) options.add(60)
        if (options.isEmpty()) options.add(30)
        return options
    }

    private fun applyZoomRatio(zoomRatio: Float) {
        boundCamera?.cameraControl?.setZoomRatio(zoomRatio)
        status("Status: zoom ${formatZoomRatio(zoomRatio)}x")
    }

    private fun detectCameraCapabilities(): List<RuntimeCameraCapability> {
        val cameraManager = getSystemService(CameraManager::class.java)
        val result = mutableListOf<RuntimeCameraCapability>()

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) continue

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasLogicalMulti = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

            val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }
            val minZoomRatio: Float
            val maxZoomRatio: Float
            val zoomText = if (zoomRange != null) {
                minZoomRatio = zoomRange.lower
                maxZoomRatio = zoomRange.upper
                "zoom %.1fx-%.1fx".format(Locale.US, minZoomRatio, maxZoomRatio)
            } else {
                val maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                minZoomRatio = 1f
                maxZoomRatio = maxDigitalZoom
                "zoom 1.0x-%.1fx".format(Locale.US, maxDigitalZoom)
            }

            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
                ?: emptyList()
            val maxFps = fpsRanges.maxOfOrNull { range: Range<Int> -> range.upper } ?: 30

            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewMaxResolution = streamMap
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?.maxByOrNull { it.width * it.height }
                ?.let { "${it.width}x${it.height}" }
                ?: "n/a"
            val recorderSizes = streamMap?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
            val supportsUhd = recorderSizes.any { it.width >= 3840 && it.height >= 2160 }
            val supportsFhd = recorderSizes.any { it.width >= 1920 && it.height >= 1080 }
            val supportsHd = recorderSizes.any { it.width >= 1280 && it.height >= 720 }

            val lensLabel = when (characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()) {
                null -> "Cam $cameraId"
                else -> "Cam $cameraId"
            }

            result.add(
                RuntimeCameraCapability(
                    cameraId = cameraId,
                    lensLabel = lensLabel,
                    hasLogicalMultiCamera = hasLogicalMulti,
                    minZoomRatio = minZoomRatio,
                    maxZoomRatio = maxZoomRatio,
                    zoomRangeText = zoomText,
                    maxFps = maxFps,
                    maxResolution = previewMaxResolution,
                    fpsRanges = fpsRanges,
                    supportsUhd = supportsUhd,
                    supportsFhd = supportsFhd,
                    supportsHd = supportsHd
                )
            )
        }

        return result.sortedBy { it.cameraId }
    }

    private fun bindSelectedCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val currentCapability = selectedCameraCapability
        val supportedQualityNames = supportedQualityOptions(currentCapability)
        val preferredQuality = if (selectedVideoQuality in supportedQualityNames) {
            selectedVideoQuality
        } else {
            supportedQualityNames.firstOrNull() ?: "1080"
        }
        val orderedQualityNames = listOf(preferredQuality) + supportedQualityNames.filterNot { it == preferredQuality }
        val qualitySelector = QualitySelector.fromOrderedList(
            orderedQualityNames.map { qualityToCameraX(it) }
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        val selectedId = selectedCameraCapability?.cameraId
        val selector = if (selectedId == null) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.Builder()
                .addCameraFilter { infos: MutableList<CameraInfo> ->
                    infos.filter {
                        runCatching { Camera2CameraInfo.from(it).cameraId == selectedId }.getOrDefault(false)
                    }.toMutableList()
                }
                .build()
        }

        try {
            provider.unbindAll()
            boundCamera = provider.bindToLifecycle(this, selector, preview, videoCapture)
            if (currentCapability != null) {
                val currentZoom = availableZoomRatios.getOrNull(binding.zoomOptionsSpinner.selectedItemPosition) ?: 1f
                val targetZoom = currentZoom.coerceIn(currentCapability.minZoomRatio, currentCapability.maxZoomRatio)
                boundCamera?.cameraControl?.setZoomRatio(targetZoom)
            }

            val fpsOptions = supportedFpsOptions(currentCapability)
            val requestedFps = if (selectedVideoFps in fpsOptions) selectedVideoFps else fpsOptions.firstOrNull() ?: 30
            selectedVideoFps = requestedFps
            currentCapability?.fpsRanges
                ?.filter { it.upper >= requestedFps }
                ?.maxWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
                ?.let { fpsRange ->
                    Camera2CameraControl.from(boundCamera?.cameraControl ?: return@let)
                        .setCaptureRequestOptions(
                            CaptureRequestOptions.Builder()
                                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                .build()
                        )
                }

            val cameraDescription = selectedCameraCapability?.displayLabel() ?: "traseira padrão"
            status("Status: câmera pronta ($cameraDescription) • ${qualityOptionLabel(preferredQuality)} • ${requestedFps}FPS")
        } catch (exc: Exception) {
            boundCamera = null
            ErrorFileLogger.logError(this, "START_CAMERA", exc)
            appendDiagnosticLog("Erro ao abrir câmera: ${exc.message}", exc)
            status("Erro ao abrir câmera: ${exc.message}")
        }
    }

    private fun startContinuousRecording(resetBuffer: Boolean) {
        if (isContinuousRecording) return

        val capture = videoCapture ?: run {
            toast("Câmera não inicializada")
            return
        }

        if (resetBuffer) {
            clearSegmentCache()
        }
        updateRecordingTimer()
        binding.replayButton.isEnabled = false
        isContinuousRecording = true
        isStopping = false
        binding.startButton.isEnabled = true
        binding.replayButton.isEnabled = true
        updatePrimaryRecordButton()
        pausedByBackground = false
        shouldResumeAfterBackground = false
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(recordingTimerRunnable)
        mainHandler.post(recordingTimerRunnable)
        updateRecordingTimer()
        status("Status: gravando continuamente")

        startSegment(capture)
    }

    private fun startSegment(capture: VideoCapture<Recorder>) {
        val segmentFile = createSegmentFile()
        val segmentStartedAtMs = SystemClock.elapsedRealtime()
        activeSegmentStartedAtMs = segmentStartedAtMs

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
                    onSegmentSaved(segmentFile, event.outputResults.outputUri, segmentStartedAtMs)
                }

                pendingSegmentFinalize?.complete(Unit)
                pendingSegmentFinalize = null

                if (isContinuousRecording && !isStopping) {
                    startSegment(capture)
                }
            }
        }

    }

    private fun onSegmentSaved(file: File, uri: Uri, segmentStartedAtMs: Long) {
        val hasValidFile = file.exists() && file.length() > 0
        val hasValidUri = uri != Uri.EMPTY

        if (!hasValidFile && !hasValidUri) {
            status("Segmento inválido, descartado")
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val elapsedMs = (nowMs - segmentStartedAtMs).coerceAtLeast(0L)
        val fallbackDurationUs = elapsedMs * 1_000L
        val measuredDurationUs = getDurationUs(file)
        val segmentDurationUs = maxOf(measuredDurationUs, fallbackDurationUs)
        if (segmentDurationUs <= 0L) {
            status("Segmento inválido (sem duração), descartado")
            file.delete()
            return
        }

        synchronized(segmentLock) {
            val segmentStartUs = finalizedSessionDurationUs
            val segmentEndUs = segmentStartUs + segmentDurationUs
            sessionSegments.addLast(
                SegmentEntry(
                    file = file,
                    startUs = segmentStartUs,
                    endUs = segmentEndUs
                )
            )
            finalizedSessionDurationUs = segmentEndUs
        }
        val bufferedSeconds = (finalizedSessionDurationUs / 1_000_000L)
        status("Status: gravação contínua (${bufferedSeconds}s)")
    }

    private fun pauseContinuousRecordingForBackground() {
        if (!isContinuousRecording) return

        shouldResumeAfterBackground = true
        pausedByBackground = true
        isStopping = true
        isContinuousRecording = false
        activeRecording?.stop()
        activeRecording = null

        mainHandler.removeCallbacks(recordingTimerRunnable)
        recordingStartedAtMs = 0L
        updateRecordingTimer()

        binding.startButton.isEnabled = true
        binding.replayButton.isEnabled = false
        updatePrimaryRecordButton()
        status("Status: gravação pausada (app em segundo plano)")
    }

    private fun stopContinuousRecording() {
        if (!isContinuousRecording) return

        lifecycleScope.launch {
            isStopping = true
            isContinuousRecording = false
            pausedByBackground = false
            shouldResumeAfterBackground = false

            val waitForFinalize = CompletableDeferred<Unit>()
            val recordingToStop = activeRecording
            pendingSegmentFinalize = waitForFinalize
            recordingToStop?.stop()
            activeRecording = null
            if (recordingToStop != null) {
                runCatching { waitForFinalize.await() }
            } else {
                pendingSegmentFinalize = null
            }

            binding.startButton.isEnabled = true
            binding.replayButton.isEnabled = false
            updatePrimaryRecordButton()
            mainHandler.removeCallbacks(recordingTimerRunnable)
            recordingStartedAtMs = 0L
            updateRecordingTimer()

            saveFullRecordingFromSession()
            status("Status: gravação parada")
            clearSegmentCache()
        }
    }

    private suspend fun saveFullRecordingFromSession() {
        val fullSlices = synchronized(segmentLock) {
            sessionSegments.map {
                SegmentSlice(
                    file = it.file,
                    trimStartUs = 0L,
                    trimEndUs = (it.endUs - it.startUs).coerceAtLeast(0L)
                )
            }
        }
        if (fullSlices.isEmpty()) {
            toast("Nenhum vídeo completo para salvar")
            return
        }

        val stamp = timestamp()
        val mergedOutput = File(getSegmentsDirectory(), "full_recording_${stamp}.mp4")
        val merged = withContext(Dispatchers.IO) {
            muxSegmentSlices(fullSlices, mergedOutput)
        }

        if (!merged) {
            status("Erro ao salvar gravação completa")
            toast("Falha ao salvar gravação completa")
            return
        }

        val outputName = "recording_${stamp}.mp4"
        val savedUri = withContext(Dispatchers.IO) {
            saveVideoToPublicGallery(mergedOutput, outputName)
        }
        mergedOutput.delete()

        if (savedUri == null) {
            status("Erro ao salvar gravação completa na galeria")
            toast("Falha ao salvar gravação completa")
            return
        }

        toast("Gravação completa salva")
        maybeUploadVideoToDrive(savedUri, outputName)
    }

    private fun showReplayTypeDialog() {
        var selectedTypeIndex = -1
        val dialog = AlertDialog.Builder(this)
            .setTitle("Selecione o tipo do replay")
            .setSingleChoiceItems(replayTypeOptions, selectedTypeIndex) { _, which ->
                selectedTypeIndex = which
            }
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.isEnabled = false

            val listView = dialog.listView
            listView.setOnItemClickListener { _, _, position, _ ->
                selectedTypeIndex = position
                saveButton.isEnabled = true
            }

            saveButton.setOnClickListener {
                if (selectedTypeIndex < 0) return@setOnClickListener
                val replayType = replayTypeOptions[selectedTypeIndex]
                dialog.dismiss()
                saveReplayBundle(replayType)
            }
        }

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun saveReplayBundle(replayType: String) {
        lifecycleScope.launch {
            binding.replayButton.isEnabled = false

            try {
                rotateSegmentForReplayIfNeeded()
                val requestedWindowUs = replayDurationSec * 1_000_000L
                val replaySlice = synchronized(segmentLock) {
                    buildReplaySliceFromLatestRecordingLocked(requestedWindowUs)
                }
                if (replaySlice == null) {
                    toast("Ainda não há vídeo para salvar")
                    return@launch
                }

                val stamp = timestamp()
                val mergedReplay = File(getSegmentsDirectory(), "replay_merged_${stamp}.mp4")
                val merged = withContext(Dispatchers.IO) {
                    muxSegmentSlices(listOf(replaySlice), mergedReplay)
                }

                if (!merged) {
                    status("Erro ao montar replay único")
                    toast("Falha ao montar replay")
                    return@launch
                }

                val outputName = "replay_${replayType.lowercase()}_${stamp}.mp4"
                val savedUri = withContext(Dispatchers.IO) {
                    saveVideoToPublicGallery(mergedReplay, outputName)
                }
                mergedReplay.delete()

                if (savedUri == null) {
                    status("Erro ao salvar replay na galeria")
                    toast("Falha ao salvar replay na galeria")
                    return@launch
                }

                status("Status: replay ${replayType.lowercase()} salvo na galeria")
                toast("Replay $replayType salvo")
                maybeUploadVideoToDrive(savedUri, outputName)
            } finally {
                binding.replayButton.isEnabled = isContinuousRecording
            }
        }
    }

    private suspend fun rotateSegmentForReplayIfNeeded() {
        if (!isContinuousRecording || isStopping) return

        val waitForFinalize = CompletableDeferred<Unit>()
        pendingSegmentFinalize = waitForFinalize
        activeRecording?.stop()

        runCatching { waitForFinalize.await() }
            .onFailure { error ->
                ErrorFileLogger.logError(this, "ROTATE_SEGMENT_REPLAY", error)
                appendDiagnosticLog("Falha ao rotacionar segmento para replay: ${error.message}", error)
            }
    }

    private fun buildReplaySliceFromLatestRecordingLocked(targetWindowUs: Long): SegmentSlice? {
        val latestSegment = sessionSegments.lastOrNull() ?: return null
        if (!latestSegment.file.exists() || latestSegment.file.length() <= 0L) return null

        val segmentDurationUs = (latestSegment.endUs - latestSegment.startUs).coerceAtLeast(0L)
        if (segmentDurationUs <= 0L) return null

        val windowUs = targetWindowUs.coerceAtLeast(1L)
        val trimStartUs = (segmentDurationUs - windowUs).coerceAtLeast(0L)
        val trimEndUs = segmentDurationUs

        return SegmentSlice(
            file = latestSegment.file,
            trimStartUs = trimStartUs,
            trimEndUs = trimEndUs
        )
    }

    private fun muxSegmentSlices(slices: List<SegmentSlice>, outputFile: File): Boolean {
        if (slices.isEmpty()) return false
        return muxSegments(
            segmentSlices = slices,
            outputFile = outputFile
        )
    }

    private fun getDurationUs(file: File): Long {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val trackDuration = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    if (trackDuration > durationUs) durationUs = trackDuration
                }
            }
            durationUs
        }.getOrDefault(0L).also {
            extractor.release()
        }
    }

    private fun muxSegments(segmentSlices: List<SegmentSlice>, outputFile: File): Boolean {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(90)

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var started = false
        var videoPtsOffset = 0L
        var audioPtsOffset = 0L

        try {
            segmentSlices.forEach { slice ->
                val segment = slice.file
                if (!segment.exists() || segment.length() <= 0L) return@forEach
                val extractor = MediaExtractor()
                extractor.setDataSource(segment.absolutePath)

                var srcVideoTrack = -1
                var srcAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString("mime") ?: continue
                    if (mime.startsWith("video/")) {
                        srcVideoTrack = i
                        if (videoTrackIndex == -1) videoTrackIndex = muxer.addTrack(format)
                    } else if (mime.startsWith("audio/")) {
                        srcAudioTrack = i
                        if (audioTrackIndex == -1) audioTrackIndex = muxer.addTrack(format)
                    }
                }

                if (!started && (videoTrackIndex != -1 || audioTrackIndex != -1)) {
                    muxer.start()
                    started = true
                }

                if (!started) {
                    extractor.release()
                    return@forEach
                }

                val trimStartUs = slice.trimStartUs.coerceAtLeast(0L)
                val trimEndUs = slice.trimEndUs.coerceAtLeast(trimStartUs)

                if (srcVideoTrack != -1 && videoTrackIndex != -1) {
                    extractor.selectTrack(srcVideoTrack)
                    var lastPts = videoPtsOffset
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTime = extractor.sampleTime
                        if (sampleTime >= trimStartUs && sampleTime < trimEndUs) {
                            videoInfo.offset = 0
                            videoInfo.size = sampleSize
                            videoInfo.presentationTimeUs = videoPtsOffset + (sampleTime - trimStartUs)
                            videoInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(videoTrackIndex, buffer, videoInfo)
                            lastPts = videoInfo.presentationTimeUs
                        }
                        extractor.advance()
                    }
                    extractor.unselectTrack(srcVideoTrack)
                    videoPtsOffset = if (lastPts > 0L) lastPts + 1L else videoPtsOffset
                }

                if (srcAudioTrack != -1 && audioTrackIndex != -1) {
                    extractor.selectTrack(srcAudioTrack)
                    var lastPts = audioPtsOffset
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTime = extractor.sampleTime
                        if (sampleTime >= trimStartUs && sampleTime < trimEndUs) {
                            audioInfo.offset = 0
                            audioInfo.size = sampleSize
                            audioInfo.presentationTimeUs = audioPtsOffset + (sampleTime - trimStartUs)
                            audioInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(audioTrackIndex, buffer, audioInfo)
                            lastPts = audioInfo.presentationTimeUs
                        }
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
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
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

    private fun updatePrimaryRecordButton() {
        if (isContinuousRecording) {
            binding.startButton.text = getString(R.string.stop_live)
            binding.startButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            binding.startButton.text = getString(R.string.start_live)
            binding.startButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    private fun updateReplayButtonLabel() {
        binding.replayButton.text = getString(R.string.save_replay_with_duration, replayDurationSec)
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
        activeSegmentStartedAtMs = 0L
        finalizedSessionDurationUs = 0L
        synchronized(segmentLock) {
            sessionSegments.clear()
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

    private fun updateRecordingTimer() {
        val elapsedMs = if (isContinuousRecording) {
            (SystemClock.elapsedRealtime() - recordingStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val totalSeconds = elapsedMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        binding.recordingTimerText.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
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

    override fun onStop() {
        if (isContinuousRecording) {
            pauseContinuousRecordingForBackground()
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (shouldResumeAfterBackground && pausedByBackground && allPermissionsGranted()) {
            mainHandler.postDelayed({
                if (shouldResumeAfterBackground && pausedByBackground && !isContinuousRecording) {
                    runUiAction("AUTO_RESUME_RECORDING") { startContinuousRecording(resetBuffer = false) }
                    status("Status: gravação retomada automaticamente")
                }
            }, 500L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(batteryReceiver)
        mainHandler.removeCallbacks(recordingTimerRunnable)
        clearSegmentCache()
        rtmpStreamEngine?.close()
        ErrorFileLogger.logInfo(this, "APP_DESTROY", "MainActivity destruída")
        cameraExecutor.shutdown()
    }
}
