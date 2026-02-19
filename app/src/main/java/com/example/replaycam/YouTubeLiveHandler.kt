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
import com.google.api.client.googleapis.json.GoogleJsonResponseException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YouTubeLiveHandler(private val context: Context) {

    private companion object {
        private const val REQUIRED_WEB_CLIENT_ID = "698685113444-d1926mfoqamcqehcp5bug9423ql8p1fg.apps.googleusercontent.com"
    }

    private val tag = "YouTubeLiveHandler"
    var lastSignInStatusCode: Int? = null
        private set
    var lastIdToken: String? = null
        private set

    private val signInClient: GoogleSignInClient by lazy {
        val resourceWebClientId = context.getString(R.string.google_web_client_id).trim()
        val webClientId = resourceWebClientId.ifBlank { REQUIRED_WEB_CLIENT_ID }
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
            lastIdToken = account.idToken
            account
        } catch (error: ApiException) {
            lastSignInStatusCode = error.statusCode
            lastIdToken = null
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
            lastIdToken = null
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
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Falha no Google Sign-In (12500). Confirme o OAuth Web Client ID e se seu e-mail está em Usuários de Teste na tela de consentimento OAuth."
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
            3) Verifique se a YouTube Data API v3 está ativada no projeto replaycam.
            4) Garanta que o escopo youtube.force-ssl está sendo solicitado.
            5) Na Tela de Permissão OAuth, adicione seu e-mail em Usuários de Teste (escopo sensível).
            6) Confirme que o SHA-1 do Google Cloud é o mesmo do APK em execução (GOOGLE_OAUTH_DEBUG_INFO).
            7) Se trocar ambiente/chave de build debug, atualize o SHA-1 manualmente no Google Cloud.
            8) Reinstale o app após ajustar credenciais.
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

    suspend fun createLiveSession(
        account: GoogleSignInAccount,
        idToken: String?,
        title: String,
        privacyStatus: String
    ): LiveSessionInfo = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(tag, "createLiveSession start for account=${account.email}")
            ErrorFileLogger.logInfo(context, "YOUTUBE_CREATE_LIVE_SESSION", "iniciado para ${account.email}")
            val youtube = buildYouTubeService(account, idToken)
            val stream = createLiveStream(youtube)
            val broadcast = createLiveBroadcast(youtube, title, privacyStatus)
            bindBroadcastToStream(youtube, broadcast.id, stream.id)
            Log.i(tag, "createLiveSession done broadcastId=${broadcast.id} streamId=${stream.id}")

            val ingestion = stream.cdn?.ingestionInfo
                ?: error("YouTube retornou stream sem ingestionInfo")
            val ingestionAddress = ingestion.ingestionAddress?.trim().orEmpty()
            val streamName = ingestion.streamName?.trim().orEmpty()
            require(ingestionAddress.isNotBlank()) { "YouTube retornou ingestionAddress vazio" }
            require(streamName.isNotBlank()) { "YouTube retornou streamName vazio" }
            Log.i(tag, "Ingestion recebido address=$ingestionAddress streamName=$streamName")

            LiveSessionInfo(
                broadcastId = broadcast.id,
                streamId = stream.id,
                rtmpServerUrl = ingestionAddress,
                streamKey = streamName
            )
        }.onFailure { error ->
            ErrorFileLogger.logError(context, "YOUTUBE_CREATE_LIVE_SESSION", error)
        }.getOrThrow()
    }

    private fun buildYouTubeService(account: GoogleSignInAccount, idToken: String?): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(YouTubeScopes.YOUTUBE, YouTubeScopes.YOUTUBE_FORCE_SSL)
        ).apply {
            selectedAccount = account.account
        }

        if (!idToken.isNullOrBlank()) {
            Log.i(tag, "idToken recebido no sign-in, mas autenticação YouTube usa access token OAuth2 do GoogleAccountCredential.")
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

    private fun createLiveBroadcast(youtube: YouTube, title: String, privacyStatus: String): LiveBroadcast {
        val titleStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val normalizedTitle = title.trim().ifBlank { "ReplayCam Live $titleStamp" }
        val normalizedPrivacy = privacyStatus.trim().lowercase(Locale.US).ifBlank { "unlisted" }
        val snippet = LiveBroadcastSnippet().apply {
            this.title = normalizedTitle
            scheduledStartTime = com.google.api.client.util.DateTime(System.currentTimeMillis() - 10_000)
        }
        val status = LiveBroadcastStatus().apply {
            this.privacyStatus = normalizedPrivacy
            selfDeclaredMadeForKids = false
        }
        val contentDetails = LiveBroadcastContentDetails().apply {
            monitorStream = MonitorStreamInfo().setEnableMonitorStream(false)
            enableAutoStart = true
            enableAutoStop = true
            latencyPreference = "ultraLow"
        }

        Log.i(tag, "Creating YouTube liveBroadcast title=$normalizedTitle privacy=$normalizedPrivacy")
        return youtube.liveBroadcasts()
            .insert(mutableListOf("snippet", "status", "contentDetails"), LiveBroadcast().apply {
                this.snippet = snippet
                this.status = status
                this.contentDetails = contentDetails
            })
            .execute()
    }

    suspend fun transitionBroadcastToLive(
        account: GoogleSignInAccount,
        idToken: String?,
        broadcastId: String,
        maxAttempts: Int = 15,
        pollDelayMs: Long = 4_000
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val youtube = buildYouTubeService(account, idToken)
            repeat(maxAttempts) { attempt ->
                val status = fetchBroadcastRuntimeStatus(youtube, broadcastId)
                Log.i(
                    tag,
                    "Aguardando broadcast ficar pronto (tentativa ${attempt + 1}/$maxAttempts): " +
                        "lifecycle=${status.broadcastLifeCycleStatus}, stream=${status.streamStatus}"
                )
                ErrorFileLogger.logInfo(
                    context,
                    "YOUTUBE_LIVE_STATUS_POLL",
                    "attempt=${attempt + 1}/$maxAttempts lifecycle=${status.broadcastLifeCycleStatus} stream=${status.streamStatus}"
                )

                if (status.broadcastLifeCycleStatus == "live") {
                    Log.i(tag, "Broadcast já está em LIVE.")
                    return@withContext true
                }

                if (status.streamStatus != "active") {
                    ErrorFileLogger.logInfo(
                        context,
                        "YOUTUBE_LIVE_WAITING_STREAM",
                        "streamStatus=${status.streamStatus.ifBlank { "vazio" }}; aguardando ${pollDelayMs}ms"
                    )
                    delay(pollDelayMs)
                    return@repeat
                }

                val transitionedToTesting = runCatching {
                    youtube.liveBroadcasts()
                        .transition("testing", broadcastId, mutableListOf("id", "status", "snippet"))
                        .execute()
                    Log.d("YT_API", "Comando de transição para TESTING enviado!")
                    true
                }.getOrElse { error ->
                    val googleError = error as? GoogleJsonResponseException
                    val reason = googleError?.details?.errors?.firstOrNull()?.reason
                    if (reason == "invalidTransition") {
                        Log.i(tag, "Transição para TESTING não aplicável (reason=invalidTransition). Seguindo para LIVE.")
                        ErrorFileLogger.logInfo(context, "YOUTUBE_TRANSITION_LIVE", "transição TESTING não aplicável; tentando LIVE")
                        false
                    } else {
                        throw error
                    }
                }

                if (transitionedToTesting) {
                    delay(2_000)
                }

                youtube.liveBroadcasts()
                    .transition("live", broadcastId, mutableListOf("id", "status", "snippet"))
                    .execute()
                Log.d("YT_API", "Comando de transição para LIVE enviado!")
                ErrorFileLogger.logInfo(context, "YOUTUBE_TRANSITION_LIVE", "comando enviado com sucesso")

                val updatedStatus = fetchBroadcastRuntimeStatus(youtube, broadcastId)
                if (updatedStatus.broadcastLifeCycleStatus == "live") {
                    Log.i(tag, "Broadcast confirmado em LIVE após transição.")
                    ErrorFileLogger.logInfo(context, "YOUTUBE_TRANSITION_LIVE", "broadcast confirmado em LIVE")
                    return@withContext true
                }

                delay(pollDelayMs)
            }

            ErrorFileLogger.logInfo(context, "YOUTUBE_TRANSITION_LIVE", "timeout aguardando broadcast entrar em LIVE")
            false
        }.onFailure { error ->
            val googleError = error as? GoogleJsonResponseException
            val reason = googleError?.details?.errors?.firstOrNull()?.reason
            Log.e(tag, "Falha ao enviar transição para LIVE reason=$reason", error)
            ErrorFileLogger.logError(context, "YOUTUBE_TRANSITION_LIVE", error)
            if (!reason.isNullOrBlank()) {
                ErrorFileLogger.logInfo(context, "YOUTUBE_TRANSITION_LIVE_REASON", reason)
            }
        }.getOrDefault(false)
    }

    private fun fetchBroadcastRuntimeStatus(youtube: YouTube, broadcastId: String): BroadcastRuntimeStatus {
        val broadcast = youtube.liveBroadcasts()
            .list(mutableListOf("id", "status", "contentDetails"))
            .setId(mutableListOf(broadcastId))
            .execute()
            .items
            ?.firstOrNull()
            ?: error("Broadcast $broadcastId não encontrado")

        val broadcastLifeCycleStatus = broadcast.status?.lifeCycleStatus.orEmpty()
        val streamId = broadcast.contentDetails?.boundStreamId.orEmpty()

        val streamStatus = if (streamId.isBlank()) {
            ""
        } else {
            youtube.liveStreams()
                .list(mutableListOf("id", "status"))
                .setId(mutableListOf(streamId))
                .execute()
                .items
                ?.firstOrNull()
                ?.status
                ?.streamStatus
                .orEmpty()
        }

        return BroadcastRuntimeStatus(
            broadcastLifeCycleStatus = broadcastLifeCycleStatus,
            streamStatus = streamStatus
        )
    }

    private data class BroadcastRuntimeStatus(
        val broadcastLifeCycleStatus: String,
        val streamStatus: String
    )

    private fun createLiveStream(youtube: YouTube): LiveStream {
        val snippet = LiveStreamSnippet().apply {
            title = "ReplayCam Stream ${System.currentTimeMillis()}"
        }
        val contentDetails = LiveStreamContentDetails().apply {
            isReusable = true
        }

        return createLiveStreamWithIngestionType(
            youtube = youtube,
            snippet = snippet,
            contentDetails = contentDetails
        )
    }

    private fun createLiveStreamWithIngestionType(
        youtube: YouTube,
        snippet: LiveStreamSnippet,
        contentDetails: LiveStreamContentDetails
    ): LiveStream {
        val cdnSettings = CdnSettings()
        cdnSettings.setIngestionType("rtmp")
        cdnSettings.setResolution("720p")
        cdnSettings.setFrameRate("30fps")

        Log.i(tag, "Creating YouTube liveStream (ingestionType=rtmp, 720p/30fps)")
        return youtube.liveStreams()
            .insert(mutableListOf("snippet", "cdn", "contentDetails"), LiveStream().apply {
                this.snippet = snippet
                this.cdn = cdnSettings
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
