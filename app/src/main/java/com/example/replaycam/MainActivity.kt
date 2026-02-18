package com.example.replaycam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import androidx.core.content.FileProvider
import com.example.replaycam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            startCamera()
        } else {
            toast("Permissões de câmera e áudio são obrigatórias")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.startButton.setOnClickListener { startContinuousRecording() }
        binding.stopButton.setOnClickListener { stopContinuousRecording() }
        binding.replayButton.setOnClickListener { saveReplayBundle() }
        binding.openFolderButton.setOnClickListener { openVideoFolder() }
        updateVideoPathLabel()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        }
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
                status("Erro ao abrir câmera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startContinuousRecording() {
        if (isContinuousRecording) {
            return
        }

        val capture = videoCapture ?: run {
            toast("Câmera não inicializada")
            return
        }

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
        if (!isContinuousRecording) {
            return
        }

        isStopping = true
        isContinuousRecording = false
        activeRecording?.stop()
        activeRecording = null

        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.replayButton.isEnabled = segmentFiles.isNotEmpty()
        status("Status: gravação parada")
    }

    private fun saveReplayBundle() {
        if (segmentFiles.isEmpty()) {
            toast("Ainda não há 20s para replay")
            return
        }

        val replayDir = getReplayDirectory()
        if (!replayDir.exists()) {
            replayDir.mkdirs()
        }

        val stamp = timestamp()
        val replaySetDir = File(replayDir, "replay_$stamp")
        replaySetDir.mkdirs()

        segmentFiles.forEachIndexed { index, segment ->
            val out = File(replaySetDir, "part_${index + 1}.mp4")
            segment.copyTo(out, overwrite = true)
        }

        status("Status: replay salvo em ${replaySetDir.name}")
        toast("Replay salvo: ${replaySetDir.absolutePath}")
    }


    private fun updateVideoPathLabel() {
        val rootPath = getOutputDirectory().absolutePath
        val replayPath = getReplayDirectory().absolutePath
        binding.videoPathText.text = getString(
            R.string.video_path,
            rootPath,
            replayPath
        )
    }

    private fun openVideoFolder() {
        val replayDir = getReplayDirectory().apply { mkdirs() }
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            replayDir
        )

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (viewIntent.resolveActivity(packageManager) != null) {
            startActivity(viewIntent)
            return
        }

        val path = replayDir.absolutePath
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("video_path", path))
        toast(getString(R.string.video_folder_open_error))
        status(getString(R.string.video_path_copied, path))
    }

    private fun createSegmentFile(): File {
        val segmentsDir = getSegmentsDirectory()
        if (!segmentsDir.exists()) {
            segmentsDir.mkdirs()
        }
        return File(segmentsDir, "segment_${timestamp()}.mp4")
    }

    private fun getSegmentsDirectory(): File = File(getOutputDirectory(), "segments")

    private fun getReplayDirectory(): File = File(getOutputDirectory(), "replays")

    private fun getOutputDirectory(): File {
        val movieDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return movieDir ?: filesDir
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
        activeRecording?.close()
        cameraExecutor.shutdown()
    }
}
