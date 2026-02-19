package com.example.replaycam

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YouTubeLiveHandler(private val context: Context) {

    private val tag = "YouTubeLiveHandler"
    var lastSignInStatusCode: Int? = null
        private set

    private val signInClient: GoogleSignInClient by lazy {
        // Não exigir ID token/serverAuthCode para evitar falhas por configuração do OAuth Web Client.
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(YouTubeScopes.YOUTUBE),
                Scope(YouTubeScopes.YOUTUBE_FORCE_SSL)
            )
            .build()

        GoogleSignIn.getClient(context, options)
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
            ErrorFileLogger.logError(context, "GOOGLE_SIGN_IN_PARSE", error)
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
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Login cancelado pelo usuário."
            null -> "Falha ao obter retorno do Google Sign-In."
            else -> "Falha Google Sign-In. statusCode=$statusCode"
        }
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
