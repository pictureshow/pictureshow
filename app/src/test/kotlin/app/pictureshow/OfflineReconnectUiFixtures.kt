/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientResult
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineReconnectFacts
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

internal val reconnectEntry = OfflineEntry(
    "episode:original",
    "episode",
    OfflineMediaKind.episode,
    preset = OfflinePreset.original,
    labels = OfflineLabels("An episode"),
    tracks = listOf(
        OfflineTrack(1, "audio", "English", "eng", codec = "aac"),
        OfflineTrack(6, "audio", "Japanese", "jpn", codec = "aac", available = false),
        OfflineTrack(4, "subtitle", "Spanish", "spa", codec = "subrip", available = false)
    )
)

internal val reconnectLocalSource = MediaSourceInfo(
    id = "source",
    defaultAudioStreamIndex = 1,
    defaultSubtitleStreamIndex = -1,
    runTimeTicks = 600_000_000,
    mediaStreams = listOf(
        MediaStream(type = MediaStreamType.Video, index = 0, codec = "h264"),
        MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", displayTitle = "English", title = "English")
    )
)

/** Where a test sees what the offer did, at the seams the real one uses. */
internal class ReconnectFixture(scope: CoroutineScope, var network: String = "unmetered") {
    var facts = OfflineReconnectFacts("ok", true, true)
    var settled: OfflineSettleResult = OfflineSettleResult.Settled
    var settleGate: CompletableDeferred<Unit>? = null
    val settles = mutableListOf<Long>()
    var reopens = 0
    val streams = mutableListOf<OfflineStreamStart>()
    val reconnect = OfflineReconnect(
        reconnectEntry,
        scope,
        { network },
        { facts },
        object : OfflineReconnectHandoff {
            override suspend fun settle(positionTicks: Long): OfflineSettleResult {
                settles += positionTicks
                settleGate?.await()
                return settled
            }
            override fun reopen() {
                reopens++
            }
        },
        { streams += it }
    )
}

/** A local session as the player sees it, over a held stream so nothing decodes. */
internal class ReconnectLocalGateway : PlaybackGateway {
    override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> = ClientResult.Success(
        PlayOutcome.Play(
            PlayDecision(
                "episode",
                reconnectLocalSource,
                PlayMethod.Transcode,
                "https://example.test/Videos/episode/master.m3u8",
                emptyMap(),
                "viewing"
            )
        )
    )
    override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
    override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
    override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit)
    override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
}
