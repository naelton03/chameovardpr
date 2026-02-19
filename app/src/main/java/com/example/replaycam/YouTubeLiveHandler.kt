package com.example.replaycam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.replaycam.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.CdnSettings
import com.google.api.services.youtube.model.LiveBroadcast
import com.google.api.services.youtube.model.LiveBroadcastContentDetails
import com.google.api.services.youtube.model.LiveBroadcastSnippet
import com.google.api.services.youtube.model.LiveBroadcastStatus
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.LiveStreamContentDetails
import com.google.api.services.youtube.model.LiveStreamSnippet
import com.google.api.services.youtube.model.MonitorStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YouTubeLiveHandler(private val context: Context) {

    private val tag = "YouTubeLiveHandler"
    var lastSignInStatusCode: Int? = null
        private set

    private val signInClient: GoogleSignInClient by lazy {
        val webClientId = context.getString(R.string.google_web_client_id).trim()
        val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(YouTubeScopes.YOUTUBE_FORCE_SSL),
                Scope(YouTubeScopes.YOUTUBE)
            )

        if (webClientId.isNotBlank()) {
            optionsBuilder
                .requestIdToken(webClientId)
                .requestServerAuthCode(webClientId)
        } else {
            Log.w(tag, "google_web_client_id não configurado. ID token/serverAuthCode não serão solicitados.")
        }

        GoogleSignIn.getClient(context, optionsBuilder.build())
    }

    fun authIntent(): Intent = signInClient.signInIntent

    fun parseSignInResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val account = task.getResult(ApiException::class.java)
            lastSignInStatusCode = null
            account
        } catch (error: ApiException) {
            lastSignInStatusCode = error.statusCode
            Log.w(tag, "Google Sign-In parse falhou. statusCode=${error.statusCode}", error)
            if (error.statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
                ErrorFileLogger.logInfo(
                    context,
                    "GOOGLE_SIGN_IN_PARSE",
                    "DEVELOPER_ERROR (10) detectado. Verifique OAuth Android: ${oauthDebugInfo()}"
                )
            } else {
                ErrorFileLogger.logError(context, "GOOGLE_SIGN_IN_PARSE", error)
            }
            null
        } catch (error: Exception) {
            lastSignInStatusCode = null
            Log.w(tag, "Google Sign-In parse falhou", error)
            ErrorFileLogger.logError(context, "GOOGLE_SIGN_IN_PARSE", error)
            null
        }
    }

    fun isAuthenticated(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null

    fun signInErrorHint(statusCode: Int?): String {
        return when (statusCode) {
            GoogleSignInStatusCodes.DEVELOPER_ERROR -> "Erro 10 (DEVELOPER_ERROR): configure OAuth Android no Google Cloud com packageName e SHA-1/ SHA-256 corretos."
            GoogleSignInStatusCodes.NETWORK_ERROR -> "Sem rede no dispositivo para autenticar no Google."
            GoogleSignInStatusCodes.SIGN_IN_REQUIRED -> "É necessário entrar na conta Google novamente."
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Falha no Google Sign-In (12500). Confirme que google_web_client_id usa o OAuth Web Client ID."
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Login cancelado pelo usuário."
            null -> "Falha ao obter retorno do Google Sign-In."
            else -> "Falha Google Sign-In. statusCode=$statusCode"
        }
    }

    fun oauthSetupChecklist(): String {
        return """
            Checklist OAuth Android:
            1) Configure OAuth Android com packageName e SHA-1/SHA-256 do APK instalado.
            2) Configure também um OAuth Web Client e use esse client ID em google_web_client_id.
            3) Verifique se a YouTube Data API v3 está ativada no mesmo projeto.
            4) Garanta que o escopo youtube.force-ssl está sendo solicitado.
            5) Reinstale o app após ajustar credenciais.
        """.trimIndent()
    }

    fun oauthDebugInfo(): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.toList().orEmpty()
            }

            val first = signatures.firstOrNull()?.toByteArray()
            val sha1 = first?.let { digestHex("SHA-1", it) } ?: "indisponível"
            val sha256 = first?.let { digestHex("SHA-256", it) } ?: "indisponível"
            "package=${context.packageName}, sha1=$sha1, sha256=$sha256"
        }.getOrElse { error ->
            "package=${context.packageName}, fingerprint_error=${error.message}"
        }
    }

    private fun digestHex(algorithm: String, bytes: ByteArray): String {
        return MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    }

    suspend fun createLiveSession(account: GoogleSignInAccount): LiveSessionInfo = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(tag, "createLiveSession start for account=${account.email}")
            ErrorFileLogger.logInfo(context, "YOUTUBE_CREATE_LIVE_SESSION", "iniciado para ${account.email}")
            val youtube = buildYouTubeService(account)
            val stream = createLiveStream(youtube)
            val broadcast = createLiveBroadcast(youtube)
            bindBroadcastToStream(youtube, broadcast.id, stream.id)
            Log.i(tag, "createLiveSession done broadcastId=${broadcast.id} streamId=${stream.id}")

            val ingestion = stream.cdn?.ingestionInfo
                ?: error("YouTube retornou stream sem ingestionInfo")

            LiveSessionInfo(
                broadcastId = broadcast.id,
                streamId = stream.id,
                rtmpServerUrl = ingestion.ingestionAddress,
                streamKey = ingestion.streamName
            )
        }.onFailure { error ->
            ErrorFileLogger.logError(context, "YOUTUBE_CREATE_LIVE_SESSION", error)
        }.getOrThrow()
    }

    private fun buildYouTubeService(account: GoogleSignInAccount): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(YouTubeScopes.YOUTUBE, YouTubeScopes.YOUTUBE_FORCE_SSL)
        ).apply {
            selectedAccount = account.account
        }

        val initializer = HttpRequestInitializer { request ->
            credential.initialize(request)
            request.connectTimeout = 20_000
            request.readTimeout = 20_000
        }

        return YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName("ReplayCam")
            .build()
    }

    private fun createLiveBroadcast(youtube: YouTube): LiveBroadcast {
        val titleStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val snippet = LiveBroadcastSnippet().apply {
            title = "ReplayCam Live $titleStamp"
            scheduledStartTime = com.google.api.client.util.DateTime(System.currentTimeMillis() + 60_000)
        }
        val status = LiveBroadcastStatus().apply {
            privacyStatus = "unlisted"
            selfDeclaredMadeForKids = false
        }
        val contentDetails = LiveBroadcastContentDetails().apply {
            monitorStream = MonitorStreamInfo().setEnableMonitorStream(false)
            enableAutoStart = true
            enableAutoStop = false
        }

        Log.i(tag, "Creating YouTube liveBroadcast")
        return youtube.liveBroadcasts()
            .insert(mutableListOf("snippet", "status", "contentDetails"), LiveBroadcast().apply {
                this.snippet = snippet
                this.status = status
                this.contentDetails = contentDetails
            })
            .execute()
    }

    private fun createLiveStream(youtube: YouTube): LiveStream {
        val snippet = LiveStreamSnippet().apply {
            title = "ReplayCam Stream ${System.currentTimeMillis()}"
        }
        val cdn = CdnSettings().apply {
            ingestionType = "rtmp"
            resolution = "720p"
            frameRate = "30fps"
        }
        val contentDetails = LiveStreamContentDetails().apply {
            isReusable = true
        }

        Log.i(tag, "Creating YouTube liveStream (720p/30fps)")
        return youtube.liveStreams()
            .insert(mutableListOf("snippet", "cdn", "contentDetails"), LiveStream().apply {
                this.snippet = snippet
                this.cdn = cdn
                this.contentDetails = contentDetails
            })
            .execute()
    }

    private fun bindBroadcastToStream(youtube: YouTube, broadcastId: String, streamId: String): LiveBroadcast {
        Log.i(tag, "Binding broadcast=$broadcastId to stream=$streamId")
        return youtube.liveBroadcasts()
            .bind(broadcastId, mutableListOf("id", "contentDetails"))
            .setStreamId(streamId)
            .execute()
    }
}

data class LiveSessionInfo(
    val broadcastId: String,
    val streamId: String,
    val rtmpServerUrl: String,
    val streamKey: String
)
