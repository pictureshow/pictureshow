/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackPreferences
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackStopInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A real ExoPlayer, real files over HTTP and the real store and reducers. The only stand-ins are the audio renderer, which
 * has no speaker to wait for, and the server's negotiation and reports, which are what these tests read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class MusicHostTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private lateinit var account: Account
    private lateinit var accounts: AccountStore
    private val scopes = mutableListOf<CoroutineScope>()
    private val hosts = mutableListOf<MusicHost>()
    private val log = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val negotiated = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var gone = emptySet<String>()
    private var remote: String? = null
    private var services = 0

    private inner class Gateway : MusicGateway {
        override suspend fun negotiate(account: Account, media: String, ceiling: Int): ClientResult<PlayOutcome> {
            negotiated += media
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        media,
                        MediaSourceInfo(id = media),
                        PlayMethod.DirectPlay,
                        server.url("/$media.wav").toString(),
                        emptyMap(),
                        "session-$media"
                    )
                )
            )
        }

        override suspend fun start(account: Account, info: PlaybackProgressInfo) {
            log += "start ${info.itemId}"
        }

        override suspend fun progress(account: Account, info: PlaybackProgressInfo) = Unit

        override suspend fun stopped(account: Account, info: PlaybackStopInfo) {
            log += "stop ${info.itemId}" + if (info.failed) " failed" else ""
        }

        override suspend fun check(account: Account, media: List<String>) = ClientResult.Success(media.filter { it !in gone })

        override suspend fun items(account: Account, media: List<String>) = ClientResult.Success(
            media.map {
                MediaItem(id = it, name = "Title $it", type = if (it.startsWith("v")) "Movie" else "Audio", runTimeTicks = 1_000_000)
            }
        )
    }

    private class Memory : TextPersistence {
        private var text: String? = null

        override suspend fun read() = text

        override suspend fun write(value: String) {
            text = value
        }
    }

    private var lastPlayer: ExoPlayer? = null

    private fun player(context: Context): ExoPlayer {
        val clock = FakeClock(true)
        return TestExoPlayerBuilder(context)
            .setClock(clock)
            .setRenderersFactory { handler, _, audio, _, _ ->
                arrayOf(FakeAudioRenderer(clock.createHandler(handler.looper, null), audio))
            }
            .build()
            .also { lastPlayer = it }
    }

    private fun host(): MusicHost {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scopes += scope
        return MusicHost(
            context,
            scope,
            accounts,
            MusicStore(context),
            Gateway(),
            preferences = { PlaybackPreferences() },
            remoteAttached = { remote },
            remoteTarget = { null },
            newPlayer = ::player,
            startService = { services += 1 },
            seed = { 42 },
            metered = { false }
        ).also { hosts += it }
    }

    @Before fun open() {
        val audio = wav()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/denied") -> MockResponse().setResponseCode(401)
                request.path!!.startsWith("/broken") -> MockResponse().setBody("this is not audio at all, not even close".repeat(40))
                else -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))
            }
        }
        server.start()
        account = Account(Server("s", "Test", server.url("/").toString(), "12"), User("v", "Viewer"), "token")
        accounts = AccountStore(Memory())
        runBlocking { accounts.remember(account) }
    }

    @After fun close() {
        scopes.forEach { it.cancel() }
        hosts.forEach { pump(200) }
        server.shutdown()
        java.io.File(context.filesDir, "music").deleteRecursively()
    }

    private fun wav(): ByteArray {
        val samples = ByteArray(8_820)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples.size)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(44_100)
            putInt(88_200)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(samples.size)
        }
        return ByteArrayOutputStream().apply {
            write(header.array())
            write(samples)
        }.toByteArray()
    }

    private fun pump(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!check()) {
            assertTrue(
                System.currentTimeMillis() < end,
                "Waited for: $what. Log: $log. Ui: ${hosts.lastOrNull()?.state?.value?.let {
                    it.message to it.queue?.order
                }}"
            )
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun tracks(vararg ids: String) = ids.map { MusicTrack(it, "Title $it", durationTicks = 1_000_000) }

    private fun album(vararg ids: String, start: Int? = null, context: String = "album", shuffle: Boolean = false) =
        MusicPlay(tracks(*ids), start, context, "Album", shuffle)

    private fun ready(host: MusicHost) = until("the host to know the account") { host.state.value.account != null }

    @Test fun anAlbumPlaysThroughWithOneViewingPerTrackAndStopsAtItsEnd() {
        val music = host()
        ready(music)
        music.request(album("a", "b", "c"))
        until("the album to end") { log.size >= 6 && !music.state.value.outputActive }
        assertEquals(listOf("start a", "stop a", "start b", "stop b", "start c", "stop c"), log)
        assertEquals(1, services)
        assertTrue(music.state.value.queue!!.paused)
    }

    @Test fun aSelectedTrackStartsThereAndKeepsItsAlbum() {
        val music = host()
        ready(music)
        music.request(album("a", "b", "c", start = 1))
        until("the second track to play") { "start b" in log }
        assertFalse("start a" in log)
        assertEquals(3, music.state.value.queue!!.entries.size)
    }

    @Test fun theSameSongTwiceIsTwoViewings() {
        val music = host()
        ready(music)
        music.request(album("a", "a"))
        until("both to play") { log.size >= 4 }
        assertEquals(listOf("start a", "stop a", "start a", "stop a"), log)
    }

    @Test fun repeatOneIsANewViewingEachTime() {
        val music = host()
        ready(music)
        music.setRepeat("one")
        music.request(album("a", "b"))
        until("it to come round twice") { log.count { it == "start a" } >= 2 }
        assertFalse("start b" in log)
        music.stop()
        until("the stop") { !music.state.value.outputActive }
    }

    @Test fun anotherAlbumAsksFirstAndCancelLeavesTheQueue() {
        val music = host()
        ready(music)
        music.request(album("a", "b"))
        until("the queue") { music.state.value.queue?.entries?.size == 2 }
        music.request(album("x", context = "other"))
        until("the question") { music.state.value.prompt?.kind == "replace" }
        val before = music.state.value.queue!!.order
        music.dismissPrompt()
        until("the answer") { music.state.value.prompt == null }
        assertEquals(before, music.state.value.queue!!.order)
        assertFalse("x" in negotiated)
    }

    @Test fun anAttachedRemoteIsNeverOverwrittenSilently() {
        remote = "Living room"
        val music = host()
        ready(music)
        music.request(album("a"))
        until("the question") { music.state.value.prompt != null }
        assertEquals("destination", music.state.value.prompt!!.kind)
        assertEquals("Living room", music.state.value.prompt!!.destination)
        assertTrue(negotiated.isEmpty(), "Nothing plays here until the listener says so")
    }

    @Test fun aRestoredQueueIsPausedSilentAndLeavesOutWhatsGone() {
        val first = host()
        ready(first)
        // The fixture is a tenth of a second long, so repeat holds the player on this row until Stop.
        first.setRepeat("one")
        first.request(album("a", "b", "c", start = 1))
        until("the second track") { "start b" in log }
        first.stop()
        until("the stop") { !first.state.value.outputActive }
        scopes.first().cancel()
        log.clear()
        negotiated.clear()
        gone = setOf("c")
        val second = host()
        until("the restore") { second.state.value.current != null }
        pump(100)
        val restored = second.state.value
        assertEquals("Title b", restored.current!!.track.title)
        assertEquals(2, restored.queue!!.entries.size)
        assertTrue(restored.queue!!.paused)
        assertFalse(restored.outputActive)
        assertTrue(log.isEmpty(), "A restore tells the server nothing: $log")
        assertTrue(negotiated.isEmpty(), "A restore opens nothing: $negotiated")
        assertEquals("1 saved track is no longer available.", restored.message)
        second.play()
        until("the first Play") { "start b" in log }
    }

    @Test fun signingOutEndsTheAudioAndForgetsTheQueue() {
        val music = host()
        ready(music)
        music.setRepeat("all")
        music.request(album("a", "b"))
        until("playing") { "start a" in log }
        runBlocking { accounts.signOut(account.key) }
        until("the sign-out") { music.state.value.account == null }
        assertFalse(music.state.value.outputActive)
        assertNull(music.state.value.current)
        assertNull(runBlocking { MusicStore(context).load(account) })
    }

    @Test fun aVideoStopsTheAudioOnceAndKeepsTheQueue() {
        val music = host()
        ready(music)
        music.setRepeat("all")
        music.request(album("a", "b"))
        until("playing") { "start a" in log }
        music.beforeVideo()
        music.beforeVideo()
        until("the stop") { !music.state.value.outputActive }
        pump(100)
        assertEquals(log.count { it.startsWith("start") }, log.count { it.startsWith("stop") })
        assertEquals(2, music.state.value.queue!!.entries.size)
        assertNotNull(runBlocking { MusicStore(context).load(account) })
    }

    @Test fun aFileThatWontPlayIsSkippedAndSaidOutLoud() {
        val music = host()
        ready(music)
        music.request(album("broken", "b"))
        until("the next track") { "start b" in log }
        assertEquals("b", music.state.value.current!!.track.id)
    }

    @Test fun aRevokedTokenEndsTheAudioAndTheQueue() {
        val music = host()
        ready(music)
        music.request(album("denied", "b"))
        until("the end") { music.state.value.message?.contains("Sign in again") == true }
        assertFalse(music.state.value.outputActive)
        assertNull(music.state.value.current)
        assertNull(runBlocking { MusicStore(context).load(account) })
    }

    @Test fun addingAheadDoesntTouchWhatsPlaying() {
        val music = host()
        ready(music)
        music.setRepeat("one")
        music.request(album("a"))
        until("playing") { "start a" in log }
        val heard = log.count { it == "start a" }
        music.append(tracks("z"))
        until("the row") { music.state.value.queue!!.entries.size == 2 }
        assertEquals("a", music.state.value.current!!.track.id)
        assertTrue(log.count { it == "start a" } >= heard)
        assertFalse("start z" in log)
        music.stop()
        until("the stop") { !music.state.value.outputActive }
    }

    @Test fun anAddThatCouldntBeSavedSaysSoAndCanBeRetried() {
        val blocked = java.io.File(context.filesDir, "music")
        blocked.parentFile!!.mkdirs()
        blocked.writeText("a file where the folder should be")
        val music = host()
        ready(music)
        music.append(tracks("a"))
        until("the failure") { music.state.value.canRetrySave }
        assertEquals("Couldn't save the queue on this device, so that change didn't apply.", music.state.value.message)
        assertNull(music.state.value.current)
        blocked.delete()
        music.retrySave()
        until("the row") { music.state.value.current != null }
        assertFalse(music.state.value.canRetrySave)
    }

    @Test fun aRemoteNamingMusicPlaysItHereAndAnythingElseIsLeftForTheVideoPath() {
        val music = host()
        ready(music)
        assertFalse(runBlocking { music.openFromRemote(account, listOf("v1", "a"), 0) })
        pump(50)
        assertTrue(negotiated.isEmpty())
        assertTrue(runBlocking { music.openFromRemote(account, listOf("a", "b"), 1) })
        until("the chosen track") { "start b" in log }
        assertFalse("start a" in log, "Index 1 starts at the second track")
        assertEquals(listOf("a", "b"), music.state.value.queue!!.entries.map { it.media })
    }

    /** A phone's Play next and Add to queue reach the queue that's playing, and its Previous goes back through it. */
    @Test fun aRemotesQueueCommandsActOnTheMusicQueue() {
        val music = host()
        ready(music)
        music.request(album("a", "b"))
        music.pause()
        until("the paused queue") { music.state.value.current?.track?.id == "a" && lastPlayer?.playWhenReady == false }
        assertTrue(music.remoteBridge.enqueue(listOf("c"), next = true))
        until("play next") { music.state.value.queue!!.entries.map { it.media } == listOf("a", "c", "b") }
        assertTrue(music.remoteBridge.enqueue(listOf("d", "v1"), next = false))
        until("add to queue, tracks only") { music.state.value.queue!!.entries.map { it.media } == listOf("a", "c", "b", "d") }
        music.next()
        until("the next row") { music.state.value.current?.track?.id == "c" }
        assertTrue(music.remoteBridge.previous())
        until("back a row") { music.state.value.current?.track?.id == "a" }
        music.stop()
        until("the stop") { !music.state.value.outputActive }
    }

    /** The real player holds a window ahead, so a track put next has to reach it before the one it already prepared. */
    @Test fun aTrackPutNextIsHeardNextEvenWhenTheFollowingOneWasPrepared() {
        val music = host()
        ready(music)
        music.request(album("a", "b"))
        music.pause()
        until("the next track prepared") { lastPlayer?.mediaItemCount == 2 }
        music.playNext(listOf(MusicTrack("c", "Title c")))
        until("the row") { music.state.value.queue!!.entries.map { it.media } == listOf("a", "c", "b") }
        music.play()
        until("all three") { "stop b" in log }
        assertEquals(listOf("start a", "stop a", "start c", "stop c", "start b", "stop b"), log)
    }

    @Test fun aPausedQueueStaysPausedWhenTheListenerSkips() {
        val music = host()
        ready(music)
        music.setRepeat("one")
        music.request(album("a", "b", "c"))
        until("playing") { "start a" in log }
        music.pause()
        until("the pause") { lastPlayer?.playWhenReady == false }
        music.next()
        until("the next row") { music.state.value.current?.track?.id == "b" }
        pump(300)
        assertFalse(lastPlayer!!.playWhenReady, "Skipping while paused cues the next track and doesn't start it")
        assertFalse("start b" in log, "Nothing was heard, so nothing was reported")
        music.stop()
        until("the stop") { !music.state.value.outputActive }
    }

    @Test fun addingToAnEmptyQueueWaitsForPlay() {
        val music = host()
        ready(music)
        music.append(tracks("a"))
        until("the row") { music.state.value.current != null }
        pump(100)
        assertTrue(music.state.value.queue!!.paused)
        assertFalse(music.state.value.outputActive)
        assertTrue(negotiated.isEmpty())
    }

    @Test fun theMusicPlayerIsAudioOnlyAndOwnsFocusAndTheHeadphoneRule() {
        val player = createMusicPlayer(context)
        try {
            assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, player.audioAttributes.contentType)
            assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
            assertTrue(C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes)
            player.playWhenReady = true
            val end = System.currentTimeMillis() + 10_000
            while (player.playWhenReady && System.currentTimeMillis() < end) {
                context.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                ShadowLooper.idleMainLooper()
                Thread.sleep(20)
            }
            assertFalse(player.playWhenReady, "Headphones out pauses")
        } finally {
            player.release()
        }
    }
}
