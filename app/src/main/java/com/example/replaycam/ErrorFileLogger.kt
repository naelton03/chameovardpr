package com.example.replaycam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ErrorFileLogger {

    private const val TAG = "ErrorFileLogger"
    private const val LOG_FILE_NAME = "replaycam_errors.txt"
    private val lock = Any()
    private var initialized = false

    fun installGlobalHandlers(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logError(
                context = appContext,
                action = "UNCAUGHT_EXCEPTION(thread=${thread.name})",
                error = throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logError(context: Context, action: String, error: Throwable) {
        val entry = buildEntry(action, error)
        writeEntry(context, entry)
    }

    fun logInfo(context: Context, action: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] [INFO] $action -> $message\n"
        writeEntry(context, entry)
    }


    fun primaryRootPath(): String = resolvePrimaryLogFile().absolutePath

    fun canWriteRoot(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildEntry(action: String, error: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return buildString {
            append("[")
            append(timestamp)
            append("] [ERROR] ")
            append(action)
            append(" -> ")
            append(error.message ?: "sem mensagem")
            append("\n")
            append(error.stackTraceToString())
            append("\n")
        }
    }

    private fun writeEntry(context: Context, entry: String) {
        synchronized(lock) {
            val target = resolvePrimaryLogFile()
            val targetWritten = if (canWriteRoot(context)) {
                runCatching {
                    target.parentFile?.mkdirs()
                    FileWriter(target, true).use { it.append(entry) }
                }
            } else {
                Result.failure(IllegalStateException("Sem permissão para gravar na raiz"))
            }

            if (targetWritten.isFailure) {
                Log.e(TAG, "Falha ao gravar log em ${target.absolutePath}", targetWritten.exceptionOrNull())
                val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_FILE_NAME)
                runCatching {
                    fallback.parentFile?.mkdirs()
                    FileWriter(fallback, true).use { it.append(entry) }
                }.onFailure { fallbackError ->
                    Log.e(TAG, "Falha também no fallback de logs", fallbackError)
                }
            }
        }
    }

    private fun resolvePrimaryLogFile(): File {
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File("/storage/emulated/0")
        } else {
            Environment.getExternalStorageDirectory()
        }
        return File(root, LOG_FILE_NAME)
    }
}

