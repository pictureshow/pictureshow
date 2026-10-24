/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.Redaction
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import app.pictureshow.core.playback.TranscodeSeek
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** One factory is shared by the manifest, media segments and external subtitle media sources. */
internal object PlaybackMedia {
    // Reopens can happen faster than OkHttp's idle eviction. A pool/dispatcher per source keeps
    // sockets and workers from many retired players alive. Child clients share these bounded
    // process-owned resources, but keep their own origin-scoped request interceptor.
    private val transport by lazy {
        OkHttpClient.Builder()
            // Retiring and replacement manifest/media/subtitle loaders can overlap on one origin.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Network interceptors run again for redirect hops; default request properties cannot enforce origin. */
    fun httpFactory(server: Server, decision: PlayDecision): OkHttpDataSource.Factory {
        val client = transport.newBuilder()
            .addNetworkInterceptor { chain ->
                val incoming = chain.request()
                val cleanUrl = Redaction.stripApiKey(incoming.url.toString())
                val request = incoming.newBuilder().url(cleanUrl)
                // Required headers may themselves be credentials. Scope the entire map, including every redirect.
                (
                    decision.headers.keys +
                        listOf("Authorization", "X-Emby-Authorization", "ApiKey", "api_key", "X-Emby-Token", "X-MediaBrowser-Token")
                    )
                    .forEach(request::removeHeader)
                if (PlayDecisions.isSameOrigin(server, cleanUrl)) {
                    decision.headers.forEach { (name, value) -> request.header(name, value) }
                }
                chain.proceed(request.build())
            }
            .build()
        return OkHttpDataSource.Factory(client)
    }

    /** Child playlists may carry legacy ApiKey parameters too; sanitize at the final request boundary. */
    fun dataSourceFactory(
        server: Server,
        decision: PlayDecision,
        upstream: DataSource.Factory = httpFactory(server, decision)
    ): DataSource.Factory = ResolvingDataSource.Factory(upstream) { spec -> sanitized(spec) }

    fun sanitized(spec: DataSpec): DataSpec = spec.withUri(Redaction.stripApiKey(spec.uri.toString()).toUri())

    fun item(server: Server, decision: PlayDecision, title: String? = null, serverStartMs: Long? = null): MediaItem = MediaItem.Builder()
        .setMediaId(decision.itemId)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setUri(if (serverStartMs != null) TranscodeSeek.url(decision, serverStartMs) else Redaction.stripApiKey(decision.url))
        .apply {
            if (decision.method == PlayMethod.Transcode &&
                decision.mediaSource.transcodingSubProtocol == app.pictureshow.core.playback.MediaStreamProtocol.Hls
            ) {
                setMimeType(if (serverStartMs != null) MimeTypes.VIDEO_MP2T else MimeTypes.APPLICATION_M3U8)
            }
        }
        .setSubtitleConfigurations(
            decision.mediaSource.mediaStreams.filter {
                it.type == MediaStreamType.Subtitle &&
                    deliveredExternally(it) &&
                    it.deliveryMethod != SubtitleDeliveryMethod.Encode &&
                    !it.deliveryUrl.isNullOrBlank() &&
                    subtitleMime(it.codec) != null
            }.map { stream ->
                MediaItem.SubtitleConfiguration.Builder(
                    subtitleUrl(server, stream, serverStartMs).toUri()
                )
                    .setId(subtitleId(stream.index))
                    .setMimeType(subtitleMime(stream.codec))
                    .setLabel(stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}")
                    .setLanguage(stream.language)
                    .setSelectionFlags(
                        (if (stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                            (if (stream.isForced) C.SELECTION_FLAG_FORCED else 0)
                    )
                    .build()
            }
        )
        .build()

    fun subtitleUsesFullTimeline(server: Server, stream: MediaStream): Boolean {
        val url = stream.deliveryUrl?.let { PlayDecisions.resolve(server, Redaction.stripApiKey(it)) } ?: return false
        return deliveredExternally(stream) &&
            (
                !PlayDecisions.isSameOrigin(server, url) ||
                    !url.toUri().path.orEmpty().contains("/Subtitles/", ignoreCase = true)
                )
    }

    private fun subtitleUrl(server: Server, stream: MediaStream, startMs: Long?): String {
        val url = PlayDecisions.resolve(server, Redaction.stripApiKey(requireNotNull(stream.deliveryUrl)))
        val uri = url.toUri()
        if (startMs == null ||
            !PlayDecisions.isSameOrigin(server, url) ||
            !uri.path.orEmpty().contains("/Subtitles/", ignoreCase = true)
        ) {
            return url
        }
        return uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filter { !it.equals("StartPositionTicks", true) && !it.equals("CopyTimestamps", true) }
                .forEach { key -> uri.getQueryParameters(key).forEach { value -> appendQueryParameter(key, value) } }
            appendQueryParameter("StartPositionTicks", (startMs * 10_000).toString())
            appendQueryParameter("CopyTimestamps", "false")
        }.build().toString()
    }

    fun deliveredExternally(stream: MediaStream): Boolean =
        stream.isExternal || stream.isExternalUrl || stream.deliveryMethod == SubtitleDeliveryMethod.External

    fun subtitleId(index: Int): String = "server-subtitle:$index"

    fun subtitleMime(codec: String?): String? = when (codec?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    fun defaultStream(streams: List<MediaStream>, type: MediaStreamType, serverIndex: Int?, preferredLanguage: String): Int {
        // A null server subtitle index means its user mode selected no subtitles (for example None).
        if (type == MediaStreamType.Subtitle) return serverIndex ?: -1
        val preferred = PlaybackTracks.language(preferredLanguage)
        return serverIndex ?: streams.firstOrNull { it.type == type && it.isDefault }?.index
            ?: streams.firstOrNull { it.type == type && preferred != null && PlaybackTracks.language(it.language) == preferred }?.index
            ?: if (type == MediaStreamType.Audio) streams.firstOrNull { it.type == type }?.index ?: -1 else -1
    }
}
