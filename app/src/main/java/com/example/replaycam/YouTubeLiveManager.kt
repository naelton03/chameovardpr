package com.example.replaycam

import android.accounts.Account
import android.content.Context
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.LiveBroadcast
import com.google.api.services.youtube.model.LiveBroadcastContentDetails
import com.google.api.services.youtube.model.LiveBroadcastSnippet
import com.google.api.services.youtube.model.LiveBroadcastStatus
import com.google.api.services.youtube.model.LiveStream
import com.google.api.services.youtube.model.LiveStreamCdn
import com.google.api.services.youtube.model.LiveStreamSnippet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class YouTubeLiveManager(private val context: Context) {

    data class LiveSession(
        val broadcastId: String,
        val streamId: String,
        val streamUrl: String,
        val streamKey: String
    )

    suspend fun startLive(account: Account): Result<LiveSession> = withContext(Dispatchers.IO) {
        runCatching {
            val youtube = buildYouTubeService(account)
            val startTime = com.google.api.client.util.DateTime(Date(System.currentTimeMillis() + 60_000))

            val broadcast = LiveBroadcast().apply {
                snippet = LiveBroadcastSnippet().apply {
                    title = "ReplayCam Live ${System.currentTimeMillis()}"
                    scheduledStartTime = startTime
                }
                status = LiveBroadcastStatus().apply {
                    privacyStatus = "unlisted"
                    selfDeclaredMadeForKids = false
                }
                contentDetails = LiveBroadcastContentDetails().apply {
                    enableAutoStart = true
                    enableAutoStop = true
                    enableLowLatency = true
                }
            }

            val insertedBroadcast = youtube.liveBroadcasts()
                .insert("snippet,status,contentDetails", broadcast)
                .execute()

            val stream = LiveStream().apply {
                snippet = LiveStreamSnippet().apply {
                    title = "ReplayCam Stream ${System.currentTimeMillis()}"
                }
                cdn = LiveStreamCdn().apply {
                    ingestionType = "rtmp"
                    resolution = "720p"
                    frameRate = "30fps"
                }
            }

            val insertedStream = youtube.liveStreams()
                .insert("snippet,cdn,status", stream)
                .execute()

            youtube.liveBroadcasts()
                .bind("id,contentDetails", insertedBroadcast.id)
                .setStreamId(insertedStream.id)
                .execute()

            val ingestionInfo = insertedStream.cdn?.ingestionInfo
                ?: error("YouTube não retornou endpoint de ingestão")
            val broadcastId = insertedBroadcast.id ?: error("Broadcast sem ID")
            val streamId = insertedStream.id ?: error("Stream sem ID")
            val streamUrl = ingestionInfo.ingestionAddress ?: error("URL RTMP ausente")
            val streamKey = ingestionInfo.streamName ?: error("Chave de stream ausente")

            LiveSession(
                broadcastId = broadcastId,
                streamId = streamId,
                streamUrl = streamUrl,
                streamKey = streamKey
            )
        }
    }

    suspend fun stopLive(account: Account, broadcastId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(broadcastId.isNotBlank()) { "broadcastId inválido" }
            val youtube = buildYouTubeService(account)
            youtube.liveBroadcasts()
                .transition("complete", "id,status", broadcastId)
                .execute()
            Unit
        }
    }

    private fun buildYouTubeService(account: Account): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(YouTubeScopes.YOUTUBE)
        ).setSelectedAccount(account)

        val requestInitializer = HttpRequestInitializer { request: HttpRequest ->
            credential.initialize(request)
            request.connectTimeout = 30_000
            request.readTimeout = 30_000
        }

        return YouTube.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer
        )
            .setApplicationName("ReplayCam")
            .build()
    }
}
