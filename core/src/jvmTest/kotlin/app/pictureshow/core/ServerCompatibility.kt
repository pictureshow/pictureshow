/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.CodecLevel
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackReports
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.Redaction
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Opt-in, destructive to the dedicated test user's watch state. See tools/server-compat.md. */
fun main() = runBlocking {
    val url = requireNotNull(System.getenv("PS_COMPAT_URL")) { "Set PS_COMPAT_URL to an isolated fixture server." }
    val config = System.getenv("PS_COMPAT_CONFIG")?.let {
        try {
            Json.decodeFromString<CompatCredentials>(File(it).readText())
        } catch (_: Exception) {
            error("Cannot read compatibility credentials; expected a private JSON file with username and password strings.")
        }
    }
    val report = File(System.getenv("PS_COMPAT_REPORT") ?: "build/reports/server-compat/results.json")
    val expected = requireNotNull(System.getenv("PS_COMPAT_VERSION")) { "Set PS_COMPAT_VERSION to the expected runtime version." }
    val suite = CompatibilitySuite(url, config, expected)
    try {
        suite.run()
    } finally {
        report.parentFile?.mkdirs()
        report.writeText(Json { prettyPrint = true }.encodeToString(CompatReport(expected, suite.results)))
        suite.close()
    }
    check(suite.results.none { it.outcome == "FAIL" }) { "Server compatibility failed; see the sanitized report." }
}

@Serializable
private data class CompatReport(val serverVersion: String, val results: List<CompatResult>)

@Serializable
private data class CompatCredentials(val username: String, val password: String)

@Serializable
private data class CompatResult(val call: String, val outcome: String, val http: List<Int>, val detail: String)

private class CompatibilitySuite(private val url: String, private val credentials: CompatCredentials?, private val expected: String) {
    val results = mutableListOf<CompatResult>()
    private val statuses = mutableListOf<Int>()
    private val identity = ClientIdentity("Compatibility check", "pictureshow-server-compat", "1")
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        HttpResponseValidator { validateResponse { statuses += it.status.value } }
    }
    private val client = JellyfinClient(http, identity)
    private val quickClient = JellyfinClient(http, identity.copy(deviceId = "pictureshow-server-compat-quick"))
    private val extras = PlaybackExtrasClient(http, identity)
    private val playback = PlaybackClient(http, identity)
    private val capabilities = DeviceCapabilities(
        "Compatibility H264 stereo",
        listOf(
            VideoDecoder(VideoCodec.H264, setOf("Baseline", "Constrained Baseline", "Main", "High"), CodecLevel(5, 1), 1920, 1080, 60.0)
        ),
        DisplayCapabilities(1920, 1080, 60.0),
        listOf(AudioDecoder(AudioCodec.AAC, 2)),
        AudioOutput(2)
    )

    suspend fun run() {
        val server = call("probe") {
            val probe = client.probe(url)
            if (expected.startsWith("10.9.")) {
                check(probe is ClientResult.Failure && probe.error.kind == ErrorKind.UNSUPPORTED_VERSION) {
                    "Expected unsupported-version refusal"
                }
                check(probe.error.message == "Jellyfin 10.10.0 or newer is required (server reports $expected).") {
                    "Incorrect refusal message"
                }
                note("Clear refusal: ${probe.error.message}")
                null
            } else {
                value(probe).also { check(it.version == expected) { "Unexpected runtime version" } }
            }
        } ?: return
        call("publicUsers") { check(value(client.publicUsers(server)).isNotEmpty()) { "Fixture needs a visible user" } }
        val account = call("authenticate") {
            val c = requireNotNull(credentials) { "Supply dedicated fixture credentials" }
            value(client.authenticate(server, c.username, c.password))
        } ?: return
        try {
            call("me") { check(value(client.me(account)).id == account.user.id) { "Wrong user" } }
            call("quickConnectEnabled") { check(value(client.quickConnectEnabled(server))) { "Enable Quick Connect in fixture" } }
            call("quickConnect round trip") {
                val end = withTimeout(20_000) {
                    quickClient.quickConnect(server).onEach { state ->
                        if (state is QuickConnectState.Code) {
                            val response = http.post("${server.url}/QuickConnect/Authorize") {
                                url.parameters.append("code", state.code)
                                header(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
                            }
                            check(response.status.value == 200) { "Fixture could not authorize Quick Connect" }
                        }
                    }.first { it is QuickConnectState.Authorized || it is QuickConnectState.Failed || it is QuickConnectState.Disabled }
                }
                check(end is QuickConnectState.Authorized) { "Quick Connect did not authorize" }
                check(end.account.user.id == account.user.id) { "Quick Connect returned wrong user" }
                value(quickClient.logout(end.account))
            }
            val views = call("views") { value(client.views(account)).items.also { check(it.isNotEmpty()) { "No fixture libraries" } } }
            call("resume") { value(client.resume(account)) }
            call("nextUp") { value(client.nextUp(account)) }
            val movies = views?.firstOrNull { it.collectionType == "movies" }
            val shows = views?.firstOrNull { it.collectionType == "tvshows" }
            val movieItems = call("items movies") {
                value(
                    client.items(
                        account,
                        BrowseQuery(
                            requireNotNull(movies) {
                                "Missing movie library"
                            }.id,
                            "Movie",
                            true,
                            limit = 200
                        )
                    )
                ).items
                    .also { check(it.isNotEmpty()) { "Empty fixture movie library" } }
            }
            call("latest") { check(value(client.latest(account, requireNotNull(movies).id)).isNotEmpty()) { "No latest fixture movies" } }
            val item = movieItems?.firstOrNull()
            call("item detail") { value(client.item(account, requireNotNull(item).id)) }
            call("search movies") {
                check(
                    value(client.search(account, requireNotNull(item).name, SearchKind.MOVIES)).items.any {
                        it.id ==
                            item.id
                    }
                ) { "Search omitted fixture movie" }
            }
            call("search people") { value(client.search(account, "compat", SearchKind.PEOPLE)) }
            call("played true") { check(value(client.played(account, requireNotNull(item).id, true)).played) { "Played flag not set" } }
            call("played false") {
                check(!value(client.played(account, requireNotNull(item).id, false)).played) { "Played flag not cleared" }
            }
            call("favorite true") {
                check(value(client.favorite(account, requireNotNull(item).id, true)).isFavorite) { "Favorite not set" }
            }
            call("favorite false") {
                check(!value(client.favorite(account, requireNotNull(item).id, false)).isFavorite) { "Favorite not cleared" }
            }
            val configuration = call("configuration read") { value(client.configuration(account)) }
            call("configuration write/read") {
                val original = requireNotNull(configuration)
                try {
                    value(
                        client.saveConfiguration(
                            account,
                            original.withPreference(
                                "SubtitleMode",
                                if (original.subtitleMode ==
                                    "Always"
                                ) {
                                    "None"
                                } else {
                                    "Always"
                                }
                            )
                        )
                    )
                    check(
                        value(client.configuration(account)).subtitleMode == if (original.subtitleMode ==
                            "Always"
                        ) {
                            "None"
                        } else {
                            "Always"
                        }
                    ) { "Preference did not persist" }
                } finally {
                    value(client.saveConfiguration(account, original))
                }
            }
            val series = call("items series") {
                value(client.items(account, BrowseQuery(requireNotNull(shows) { "Missing TV library" }.id, "Series", true))).items.first()
            }
            val season = call("seasons") { value(client.seasons(account, requireNotNull(series).id)).items.first() }
            val episodes =
                call("episodes") {
                    value(client.episodes(account, requireNotNull(series).id, requireNotNull(season).id)).items.also {
                        check(
                            it.size >= 2
                        ) { "Fixture needs two episodes" }
                    }
                }
            call("adjacent next episode") {
                val first = requireNotNull(episodes).first()
                check(value(extras.nextEpisode(account, first))?.id == episodes[1].id) { "Wrong adjacent episode" }
            }
            call("nextUp after played") {
                val first = requireNotNull(episodes).first()
                try {
                    value(client.played(account, first.id, true))
                    check(value(client.nextUp(account, requireNotNull(series).id)).items.firstOrNull()?.id == episodes[1].id) {
                        "NextUp did not advance"
                    }
                } finally {
                    value(client.played(account, first.id, false))
                }
            }
            val metadata = call("chapters/trickplay metadata") {
                var selected: MediaItem? = null
                for (candidate in movieItems.orEmpty()) {
                    val data = value(extras.item(account, candidate.id))
                    selected = data
                    if (data.chapters.isNotEmpty() && data.trickplay.isNotEmpty()) break
                }
                requireNotNull(selected).also {
                    note("chapters=${it.chapters.size}; trickplay sources=${it.trickplay.size}")
                    if (it.chapters.isEmpty() || it.trickplay.isEmpty()) degraded("Fixture has no chapters or extracted trickplay")
                }
            }
            call("trickplay sheet") {
                val data = requireNotNull(metadata)
                val source = data.trickplay.entries.firstOrNull()
                if (source == null) {
                    degraded("No extracted trickplay available")
                } else {
                    val info = source.value.values.first()
                    val response = http.get(trickplaySheetUrl(server, data.id, source.key, info.width, 0)) {
                        header(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
                    }
                    check(response.status.value == 200 && response.body<ByteArray>().take(2) == listOf(0xff.toByte(), 0xd8.toByte())) {
                        "Trickplay sheet was not a JPEG"
                    }
                }
            }
            call("chapter image") {
                val data = requireNotNull(metadata)
                val index = data.chapters.indexOfFirst { !it.imageTag.isNullOrBlank() }
                if (index < 0) {
                    degraded("No extracted chapter image available")
                } else {
                    val response = http.get(chapterImageUrl(server, data.id, index, data.chapters[index].imageTag!!)) {
                        header(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
                    }
                    check(response.status.value == 200 && response.body<ByteArray>().isNotEmpty()) { "Chapter image unavailable" }
                }
            }
            call("media segments") {
                val segments = value(extras.segments(account, requireNotNull(episodes).first().id))
                if (segments.isEmpty()) {
                    degraded("No segments for fixture episode")
                } else {
                    note(
                        "segments=${segments.size}; types=${segments.map {
                            it.type
                        }.distinct()}"
                    )
                }
            }
            // Search the real library with the real negotiator; no hand-written PlaybackInfo JSON.
            var direct: PlayDecision? = null
            call("PlaybackInfo direct") {
                for (candidate in movieItems.orEmpty()) {
                    val result = value(playback.prepare(account, candidate.id, capabilities))
                    if (result is PlayOutcome.Play && result.decision.method == PlayMethod.DirectPlay) {
                        direct = result.decision
                        break
                    }
                }
                check(direct != null) { "No direct-play fixture for H264/AAC profile" }
            }
            call("direct stream bytes") {
                val decision = requireNotNull(direct)
                val response = http.get(decision.url) {
                    decision.headers.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.Range, "bytes=0-1023")
                }
                check(response.status.value == 206 && response.body<ByteArray>().size == 1024) { "Direct range response invalid" }
            }
            val transcode = call("PlaybackInfo transcode") {
                val outcome =
                    value(
                        playback.prepare(
                            account,
                            requireNotNull(direct).itemId,
                            capabilities,
                            PlaybackRequest(enableDirectPlay = false, enableDirectStream = false, allowVideoStreamCopy = false)
                        )
                    )
                check(outcome is PlayOutcome.Play && outcome.decision.method == PlayMethod.Transcode) {
                    "Server did not negotiate transcode"
                }
                outcome.decision
            }
            try {
                call("transcode HLS manifest/segment") {
                    val decision = requireNotNull(transcode)
                    var manifestUrl = decision.url
                    var manifest = fetchText(server, decision, manifestUrl)
                    check(manifest.startsWith("#EXTM3U")) { "Transcode response was not HLS" }
                    if ("#EXT-X-STREAM-INF" in manifest) {
                        manifestUrl = resolve(manifestUrl, firstUri(manifest))
                        manifest = fetchText(server, decision, manifestUrl)
                    }
                    check("#EXTINF:" in manifest) { "HLS media playlist has no segments" }
                    val segmentUrl = resolve(manifestUrl, firstUri(manifest))
                    check(PlayDecisions.isSameOrigin(server, segmentUrl)) { "Off-origin media segment" }
                    val response = http.get(segmentUrl) {
                        decision.headers.forEach { (name, value) -> header(name, value) }
                    }
                    check(response.status.value == 200 && response.body<ByteArray>().isNotEmpty()) { "Empty or refused media segment" }
                }
            } finally {
                call("stop active encoding") { value(playback.stopEncoding(account, requireNotNull(transcode).playSessionId!!)) }
            }
            val decision = direct
            val reports = decision?.let(::PlaybackReports)
            val position = ((decision?.mediaSource?.runTimeTicks ?: 0) / 30_000).milliseconds
            call("session start") { value(playback.reportStart(account, requireNotNull(reports).progress(0.seconds, false, null, null))) }
            try {
                call("session progress") {
                    value(playback.reportProgress(account, requireNotNull(reports).progress(position, true, null, null)))
                }
            } finally {
                call("session stopped") { value(playback.reportStopped(account, requireNotNull(reports).stopped(position))) }
            }
            call("resume after progress") {
                val resumed = value(client.resume(account)).items.firstOrNull { it.id == requireNotNull(decision).itemId }
                check(resumed != null && resumed.userData.playbackPositionTicks > 0) {
                    "Resume omitted reported position; fixture must allow short-video resume"
                }
            }
            call("subtitle delivery") {
                var checked = false
                for (candidate in movieItems.orEmpty()) {
                    val prepared = value(playback.prepare(account, candidate.id, capabilities))
                    if (prepared !is PlayOutcome.Play) continue
                    val subtitle = prepared.decision.mediaSource.mediaStreams.firstOrNull {
                        it.type == MediaStreamType.Subtitle &&
                            it.codec in listOf("srt", "subrip", "vtt", "webvtt") &&
                            !it.deliveryUrl.isNullOrBlank()
                    } ?: continue
                    val subtitleUrl = PlayDecisions.resolve(server, Redaction.stripApiKey(subtitle.deliveryUrl!!))
                    check(PlayDecisions.isSameOrigin(server, subtitleUrl)) { "Off-origin subtitle" }
                    val text = fetchText(server, prepared.decision, subtitleUrl)
                    check("-->" in text) { "Text subtitle has no cues" }
                    checked = true
                    break
                }
                check(checked) { "Fixture needs a delivered text subtitle" }
            }
        } finally {
            call("logout") { value(client.logout(account)) }
        }
    }

    private suspend fun fetchText(server: Server, decision: PlayDecision, url: String): String {
        check(PlayDecisions.isSameOrigin(server, url)) { "Off-origin media URL" }
        val response = http.get(url) { decision.headers.forEach { (name, value) -> header(name, value) } }
        check(response.status.value == 200) { "Media request refused" }
        return response.bodyAsText()
    }

    private fun firstUri(manifest: String): String = manifest.lineSequence().map(String::trim).first {
        it.isNotEmpty() &&
            !it.startsWith("#")
    }

    private fun resolve(base: String, relative: String): String = URI(base).resolve(Redaction.stripApiKey(relative)).toString()

    private var detail = "Decoded and verified"
    private var outcome = "PASS"

    private fun note(message: String) {
        detail = message
    }

    private fun degraded(message: String) {
        outcome = "DEGRADED"
        detail = message
    }

    private suspend fun <T> call(name: String, action: suspend () -> T): T? {
        statuses.clear()
        detail = "Decoded and verified"
        outcome = "PASS"
        var result: T? = null
        try {
            result = action()
        } catch (error: Exception) {
            outcome = "FAIL"
            detail =
                if (error is IllegalStateException ||
                    error is IllegalArgumentException
                ) {
                    error.message.orEmpty()
                } else {
                    error.javaClass.simpleName
                }
        }
        results += CompatResult(name, outcome, statuses.toList(), detail)
        println("$name: $outcome HTTP ${statuses.joinToString()} $detail")
        return result
    }

    private fun <T> value(result: ClientResult<T>): T = when (result) {
        is ClientResult.Success -> result.value
        is ClientResult.Failure -> error("${result.error.kind}: ${result.error.message}")
    }

    fun close() = http.close()
}
