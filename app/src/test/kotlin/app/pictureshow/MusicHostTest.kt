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
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
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
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

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

    private val tokens = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val reportedTo = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var checks = 0
    private var checkFails = false
    private var itemsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var checkGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var negotiateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private inner class Gateway : MusicGateway {
        override suspend fun negotiate(account: Account, media: String, ceiling: Int): ClientResult<PlayOutcome> {
            negotiateGate?.await()
            negotiated += media
            tokens += "${account.accessToken}:$media"
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
            reportedTo += "${account.user.id} start ${info.itemId}"
        }

        override suspend fun progress(account: Account, info: PlaybackProgressInfo) = Unit

        override suspend fun stopped(account: Account, info: PlaybackStopInfo) {
            log += "stop ${info.itemId}" + if (info.failed) " failed" else ""
            reportedTo += "${account.user.id} stop ${info.itemId}"
        }

        override suspend fun check(account: Account, media: List<String>): ClientResult<List<String>> {
            checkGate?.await()
            checks += 1
            if (checkFails) return ClientResult.Failure(app.pictureshow.core.ClientError(app.pictureshow.core.ErrorKind.NETWORK, "offline"))
            return ClientResult.Success(media.filter { it !in gone })
        }

        override suspend fun items(account: Account, media: List<String>): ClientResult<List<MediaItem>> {
            itemsGate?.await()
            return ClientResult.Success(
                media.map {
                    MediaItem(id = it, name = "Title $it", type = if (it.startsWith("v")) "Movie" else "Audio", runTimeTicks = 1_000_000)
                }
            )
        }
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
        MusicPlay(account.key, tracks(*ids), start, context, "Album", shuffle)

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

    /** As with video: the account is marked expired, so a sign-in replaces its token, and the same viewer's queue waits. */
    @Test fun aRevokedTokenEndsTheAudioAndMarksTheAccountExpired() {
        val music = host()
        ready(music)
        music.request(album("denied", "b"))
        until("the end") { music.state.value.message?.contains("sign in again") == true }
        assertFalse(music.state.value.outputActive)
        until("the account marked expired") { accounts.snapshots.value.currentAccount?.expired == true }
        assertEquals(account.key, accounts.snapshots.value.currentKey)
        assertEquals(listOf("denied", "b"), music.state.value.queue!!.entries.map { it.media })
        assertTrue(music.state.value.queue!!.paused)
        assertEquals(listOf("denied", "b"), runBlocking { MusicStore(context).load(account) }!!.queue.entries.map { it.media })
    }

    /** The same viewer signing in again, or a device upgrade, gives a new token: nothing opened with the old one is reused. */
    @Test fun aNewTokenForTheSameViewerReopensWhatWasPrepared() {
        val music = host()
        ready(music)
        music.setRepeat("all")
        music.request(album("a", "b"))
        until("both prepared with the first token") { "token:a" in tokens && "token:b" in tokens }
        runBlocking { accounts.remember(account.copy(accessToken = "fresh")) }
        until("both opened again with the new token") { "fresh:a" in tokens && "fresh:b" in tokens }
        assertEquals(listOf("a", "b"), music.state.value.queue!!.entries.map { it.media })
        assertTrue(music.state.value.outputActive)
    }

    @Test fun addingAheadDoesntTouchWhatsPlaying() {
        val music = host()
        ready(music)
        music.setRepeat("one")
        music.request(album("a"))
        until("playing") { "start a" in log }
        val heard = log.count { it == "start a" }
        music.append(account, tracks("z"))
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
        music.append(account, tracks("a"))
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
        // Auto-advancing 100ms media must not finish before the queued pause commits.
        val prepared = kotlinx.coroutines.CompletableDeferred<Unit>().also { negotiateGate = it }
        music.request(album("a", "b"))
        music.pause()
        until("the committed pause") { music.state.value.current?.track?.id == "a" && music.state.value.queue?.paused == true }
        prepared.complete(Unit)
        until("the paused queue") {
            music.state.value.current?.track?.id == "a" &&
                music.state.value.queue?.paused == true &&
                lastPlayer?.playbackState == androidx.media3.common.Player.STATE_READY &&
                lastPlayer?.playWhenReady == false
        }
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
        music.playNext(account, listOf(MusicTrack("c", "Title c")))
        until("the row") { music.state.value.queue!!.entries.map { it.media } == listOf("a", "c", "b") }
        music.play()
        until("all three") { "stop b" in log }
        assertEquals(listOf("start a", "stop a", "start c", "stop c", "start b", "stop b"), log)
    }

    private val other get() = Account(account.server, User("w", "Other viewer"), "other-token")

    private fun switchTo(next: Account, music: MusicHost) {
        runBlocking { accounts.remember(next) }
        until("the switch") { music.state.value.account?.key == next.key }
    }

    @Test fun aPhonesAdditionStillBeingReadWhenTheAccountChangesStaysOutOfTheNextQueue() {
        val music = host()
        ready(music)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { itemsGate = it }
        assertTrue(music.remoteBridge.enqueue(listOf("a"), next = false))
        pump(50)
        switchTo(other, music)
        gate.complete(Unit)
        pump(300)
        assertEquals(null, music.state.value.queue!!.entries.firstOrNull()?.media)
        assertTrue(music.state.value.message == null, "Nothing claims it was added")
    }

    @Test fun aPhonesPlayStillBeingReadWhenTheAccountChangesStaysOutOfTheNextQueue() {
        val music = host()
        ready(music)
        val first = account
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { itemsGate = it }
        val opening = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch { music.openFromRemote(first, listOf("a"), 0) }
        pump(50)
        switchTo(other, music)
        gate.complete(Unit)
        until("the read") { opening.isCompleted }
        pump(300)
        assertTrue(negotiated.isEmpty(), "Nothing of the old account's was opened")
        assertEquals(null, music.state.value.queue!!.entries.firstOrNull()?.media)
    }

    @Test fun whatAScreenReadForTheOldAccountStaysOutOfTheNextQueue() {
        val music = host()
        ready(music)
        val stale = album("a")
        switchTo(other, music)
        music.request(stale)
        music.playHere(stale)
        music.playNext(account, tracks("b"))
        music.append(account, tracks("c"))
        pump(300)
        assertTrue(negotiated.isEmpty())
        assertEquals(null, music.state.value.prompt)
        assertEquals(null, music.state.value.queue!!.entries.firstOrNull()?.media)
    }

    /** Play on a screen read for the old account asks the next account nothing, even when its queue would need a Replace. */
    @Test fun anOldAccountsPlayDoesNotAskTheNextAccountToReplaceItsQueue() {
        val music = host()
        ready(music)
        val stale = album("a")
        switchTo(other, music)
        music.append(other, tracks("x"))
        until("the other queue") { music.state.value.queue?.entries?.size == 1 }
        music.request(stale)
        pump(300)
        assertEquals(null, music.state.value.prompt)
        assertEquals(listOf("x"), music.state.value.queue!!.entries.map { it.media })
        // The same request made for this account does ask, so the check above isn't passing for want of a question.
        music.request(MusicPlay(other.key, tracks("a"), null, "album", "Album", false))
        until("the question") { music.state.value.prompt?.kind == "replace" }
    }

    /** A saved queue still being checked when the viewer switches again lands nowhere: not on the account that follows. */
    @Test fun aRestoreStillCheckingWhenTheAccountChangesAgainLandsNowhere() {
        val music = host()
        ready(music)
        music.append(account, tracks("a"))
        until("the saved queue") { music.state.value.queue?.entries?.size == 1 }
        switchTo(other, music)
        music.append(other, tracks("x"))
        until("the other queue") { music.state.value.queue?.entries?.size == 1 }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { checkGate = it }
        runBlocking { accounts.select(account.key) }
        pump(100)
        runBlocking { accounts.select(other.key) }
        until("back on the other account") { music.state.value.account?.key == other.key }
        gate.complete(Unit)
        checkGate = null
        pump(300)
        assertEquals(listOf("x"), music.state.value.queue!!.entries.map { it.media })
    }

    /** Play for the account the viewer just switched to runs, even when it is asked for before the switch reaches the host. */
    @Test fun aPlayForTheAccountJustSwitchedToIsNotDropped() {
        val music = host()
        ready(music)
        music.append(account, tracks("a"))
        until("the saved queue") { music.state.value.queue?.entries?.size == 1 }
        switchTo(other, music)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { checkGate = it }
        runBlocking { accounts.select(account.key) }
        pump(100)
        runBlocking { accounts.select(other.key) }
        music.request(MusicPlay(other.key, tracks("x"), null, "album", "Album", false))
        gate.complete(Unit)
        checkGate = null
        until("the other account's play") { music.state.value.account?.key == other.key && "x" in negotiated }
        assertEquals(listOf("x"), music.state.value.queue!!.entries.map { it.media })
    }

    /**
     * A check that finishes while a switch is already waiting behind a slow step still lands nowhere: the switch runs first,
     * and the late restore finds a new account with an empty queue, which isn't the queue it was read for.
     */
    @Test fun aRestoreThatFinishesBehindASwitchLandsNowhere() {
        val music = host()
        ready(music)
        music.append(account, tracks("a"))
        until("the saved queue") { music.state.value.queue?.entries?.size == 1 }
        switchTo(other, music)
        val check = kotlinx.coroutines.CompletableDeferred<Unit>().also { checkGate = it }
        runBlocking { accounts.select(account.key) }
        until("back on the first account") { music.state.value.account?.key == account.key }
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>().also { negotiateGate = it }
        music.playHere(MusicPlay(account.key, tracks("b"), null, "album", "Album", false))
        pump(100)
        val third = Account(account.server, User("z", "Third viewer"), "third-token")
        runBlocking { accounts.remember(third) }
        pump(100)
        check.complete(Unit)
        checkGate = null
        pump(100)
        slow.complete(Unit)
        negotiateGate = null
        until("the third account") { music.state.value.account?.key == third.key }
        pump(300)
        assertEquals(emptyList(), music.state.value.queue!!.entries.map { it.media })
    }

    /** A screen already showing the new account can ask before the host has seen the switch; the play isn't dropped. */
    @Test fun aPlayAskedBeforeTheHostSeesTheSwitchRunsForTheNewAccount() {
        val music = host()
        ready(music)
        // Off the main thread, as a stored switch lands, so the host's own collector hasn't run when the screen asks.
        runBlocking(Dispatchers.IO) { accounts.remember(other) }
        music.playHere(MusicPlay(other.key, tracks("x"), null, "album", "Album", false))
        until("the play, for the new account") { music.state.value.account?.key == other.key && "x" in negotiated }
        assertEquals(listOf("x"), music.state.value.queue!!.entries.map { it.media })
    }

    /** The server check of a saved queue is off the step road: Play and Stop don't wait on a slow server. */
    @Test fun aRestoreStillCheckingDoesNotHoldPlay() {
        val first = host()
        ready(first)
        first.append(account, tracks("a"))
        until("the saved queue") { first.state.value.queue?.entries?.size == 1 }
        scopes.first().cancel()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { checkGate = it }
        val second = host()
        ready(second)
        second.request(album("b"))
        until("the new play, while the check still waits") { "b" in negotiated }
        second.stop()
        until("the stop") { !second.state.value.outputActive }
        gate.complete(Unit)
        checkGate = null
        pump(300)
        // What the viewer started meanwhile isn't replaced by the late restore.
        assertEquals(listOf("b"), second.state.value.queue!!.entries.map { it.media })
    }

    /** One failed check means every saved track stays, so the rest of a long queue isn't asked about. */
    @Test fun aRestoreStopsAtTheFirstFailedCheck() {
        val first = host()
        ready(first)
        val many = (1..150).map { "t$it" }.toTypedArray()
        first.append(account, tracks(*many))
        until("the saved queue") { first.state.value.queue?.entries?.size == 150 }
        scopes.first().cancel()
        checkFails = true
        checks = 0
        val second = host()
        until("the restore") { second.state.value.queue?.entries?.size == 150 }
        assertEquals(1, checks)
    }

    /** Removing a viewer who isn't the current one still deletes their saved queue (and a server, all of its viewers'). */
    @Test fun removingAnotherViewerDeletesTheirSavedQueue() {
        val music = host()
        ready(music)
        music.append(account, tracks("a"))
        until("the saved queue") { music.state.value.queue?.entries?.size == 1 }
        switchTo(other, music)
        music.append(other, tracks("x"))
        until("the other queue") { music.state.value.queue?.entries?.size == 1 }
        runBlocking { accounts.removeAccount(account.key) }
        until("the removed viewer's queue gone") { runBlocking { MusicStore(context).load(account) } == null }
        assertNotNull(runBlocking { MusicStore(context).load(other) })
        val elsewhere = Account(Server("s2", "Other", "https://other.example.test/", "12"), User("v", "Viewer"), "t2")
        runBlocking { MusicStore(context).save(elsewhere, MusicSnapshot(app.pictureshow.core.control.QueueState("s2", "v"))) }
        runBlocking { accounts.remember(elsewhere) }
        until("on the other server") { music.state.value.account?.key == elsewhere.key }
        runBlocking { accounts.select(other.key) }
        runBlocking { accounts.removeServer(elsewhere.server) }
        until("the removed server's queue gone") { runBlocking { MusicStore(context).load(elsewhere) } == null }
        assertNotNull(runBlocking { MusicStore(context).load(other) })
    }

    /** A switch while playing ends the old viewing on the old account; nothing about it reaches the next one. */
    @Test fun aSwitchWhilePlayingStopsTheOldViewingOnTheOldAccount() {
        val music = host()
        ready(music)
        music.setRepeat("all")
        music.request(album("a", "b"))
        until("playing") { reportedTo.any { it.startsWith("v start") } }
        switchTo(other, music)
        until("the audio ends") { !music.state.value.outputActive }
        pump(200)
        val starts = reportedTo.count { it.contains(" start ") }
        val stops = reportedTo.count { it.contains(" stop ") }
        assertEquals(starts, stops, "Every viewing closed: $reportedTo")
        assertTrue(reportedTo.all { it.startsWith("v ") }, "Only the old account heard about its viewings: $reportedTo")
        assertTrue(music.state.value.queue!!.entries.isEmpty())
        assertNotNull(runBlocking { MusicStore(context).load(account) })
    }

    /** Reading a phone's long queue goes in batches: 500 ids in one URL is twice the server's request-line limit. */
    @Test fun aLongReadGoesInBatches() {
        val asked = mutableListOf<List<String>>()
        val service = object : app.pictureshow.core.music.MusicService {
            override suspend fun music(account: Account, request: app.pictureshow.core.music.MusicRequest) =
                request.parameters.getValue("ids").split(",").also { asked += it }.let { ids ->
                    if (asked.size == 3 && failThird) {
                        ClientResult.Failure(app.pictureshow.core.ClientError(app.pictureshow.core.ErrorKind.NETWORK, "offline"))
                    } else {
                        ClientResult.Success(app.pictureshow.core.ItemPage(ids.map { MediaItem(id = it, name = it, type = "Audio") }))
                    }
                }
            override suspend fun instantMix(account: Account, item: String) = error("unused")
            override suspend fun playlistCanEdit(account: Account, playlist: String) = error("unused")
            override suspend fun createPlaylist(account: Account, name: String, items: List<String>, isPublic: Boolean) = error("unused")
            override suspend fun renamePlaylist(account: Account, playlist: String, name: String) = error("unused")
            override suspend fun addToPlaylist(account: Account, playlist: String, items: List<String>) = error("unused")
            override suspend fun removeFromPlaylist(account: Account, playlist: String, entries: List<String>) = error("unused")
            override suspend fun movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int) = error("unused")
        }
        val gateway = ClientMusicGateway({ error("unused") }, service) { emptySet() }
        val ids = (1..500).map { it.toString().padStart(32, '0') }
        val items = runBlocking { gateway.items(account, ids) } as ClientResult.Success
        assertEquals(ids, items.value.map(MediaItem::id))
        assertEquals(listOf(100, 100, 100, 100, 100), asked.map { it.size })
        asked.clear()
        assertEquals(ids, (runBlocking { gateway.check(account, ids) } as ClientResult.Success).value)
        asked.clear()
        failThird = true
        assertTrue(runBlocking { gateway.items(account, ids) } is ClientResult.Failure)
        assertEquals(3, asked.size, "The first failure ends the read")
    }

    private var failThird = false

    private fun sessionPlayer(music: MusicHost): Pair<androidx.media3.common.Player, androidx.media3.session.MediaSession> {
        var wrapped: androidx.media3.common.Player? = null
        val service = org.robolectric.Robolectric.buildService(MusicPlaybackService::class.java).get()
        val session = music.openSession(service) { player ->
            wrapped = player
            androidx.media3.session.MediaSession.Builder(context, player).setId("test-${System.nanoTime()}").build()
        }
        return wrapped!! to session!!
    }

    private fun pausedOnThree(): MusicHost {
        val music = host()
        ready(music)
        music.request(album("a", "b", "c"))
        music.pause()
        until("the paused output holding the album") {
            lastPlayer?.mediaItemCount == 3 && music.state.value.queue?.paused == true && music.state.value.outputActive
        }
        return music
    }

    /** A controller picking another row of the output (a watch or car queue list) picks that queue entry, not just audio. */
    @Test fun aControllersRowPickGoesThroughTheQueue() {
        val music = pausedOnThree()
        val (player, session) = sessionPlayer(music)
        player.seekTo(2, 0)
        until("the queue on the picked row") { music.state.value.current?.track?.id == "c" }
        session.release()
    }

    @Test fun aControllersDefaultPositionPickGoesThroughTheQueue() {
        val music = pausedOnThree()
        val (player, session) = sessionPlayer(music)
        player.seekToDefaultPosition(1)
        until("the queue on the picked row") { music.state.value.current?.track?.id == "b" }
        session.release()
    }

    /** setPlayWhenReady from a controller is Play and Pause, so the queue's own paused state follows it. */
    @Test fun aControllersPlayWhenReadyGoesThroughTheQueue() {
        val music = pausedOnThree()
        val (player, session) = sessionPlayer(music)
        player.playWhenReady = true
        until("the queue playing") { music.state.value.queue?.paused == false }
        player.playWhenReady = false
        until("the queue paused") { music.state.value.queue?.paused == true }
        session.release()
    }

    /** One press acts once, whichever route it arrives by; a release and a press without a key aren't presses. */
    @Test fun aMediaButtonActsOnce() {
        val music = pausedOnThree()
        val (_, session) = sessionPlayer(music)
        val callback = MusicSessionCallback(music)
        val controller = androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
            123,
            2000,
            androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            0,
            false,
            android.os.Bundle.EMPTY,
            false
        )
        fun press(action: Int, down: Long) = callback.onMediaButtonEvent(
            session,
            controller,
            android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).putExtra(
                android.content.Intent.EXTRA_KEY_EVENT,
                android.view.KeyEvent(down, down, action, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, 0)
            )
        )
        assertFalse(press(android.view.KeyEvent.ACTION_DOWN, 5), "The first press goes on to act")
        assertTrue(press(android.view.KeyEvent.ACTION_DOWN, 5), "The same press again is consumed")
        assertFalse(press(android.view.KeyEvent.ACTION_UP, 5))
        assertFalse(press(android.view.KeyEvent.ACTION_DOWN, 9), "A new press acts")
        assertFalse(callback.onMediaButtonEvent(session, controller, android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)))
        session.release()
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
        music.append(account, tracks("a"))
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

    /** A real music host/renderer connected to the real SyncPlay coordinator; only the server is a request recorder. */
    private inner class MusicSyncRig(val music: MusicHost) : SyncPlayGateway {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        private val epoch = 1_762_776_000_000L
        val sent = mutableListOf<SyncRequest>()
        val sync = SyncPlayHost(scope, this, { epoch }, { 0L })

        init {
            music.syncHost = { sync }
            sync.bind(account)
            sync.socket(true)
            sync.refresh()
        }

        override suspend fun groups(account: Account) =
            ClientResult.Success(listOf(SyncGroup("music-group", "Music", "Paused", listOf("Viewer"), epoch * 10_000)))

        override suspend fun access(account: Account) = ClientResult.Success("CreateAndJoinGroups")

        override suspend fun time(account: Account) = ClientResult.Success(SyncUtcTime(epoch, epoch))

        override suspend fun send(account: Account, request: SyncRequest): Int {
            assertEquals(this@MusicHostTest.account.key, account.key, "Group requests use this participant's viewer")
            sent += request
            return 204
        }

        fun join(entries: List<Pair<String, String>> = listOf("a" to "group-a", "b" to "group-b"), repeat: String = "RepeatNone") {
            until("the group access policy") { sync.state.value.policy == "CreateAndJoinGroups" }
            sync.join("music-group")
            joined()
            queue(entries, repeat = repeat)
            assertTrue(sync.participating)
        }

        fun joined() = sync.receive(
            update(
                "GroupJoined",
                buildJsonObject {
                    put("GroupId", "music-group")
                    put("GroupName", "Music")
                    put("State", "Paused")
                    put("Participants", JsonArray(listOf(JsonPrimitive("Viewer"))))
                    put("LastUpdatedAt", syncUtcTimestamp(epoch))
                }
            )
        )

        fun queue(entries: List<Pair<String, String>>, index: Int = 0, ticks: Long = 200_000L, repeat: String = "RepeatNone") =
            sync.receive(
                update(
                    "PlayQueue",
                    buildJsonObject {
                        put("Reason", "NewPlaylist")
                        put("LastUpdate", syncUtcTimestamp(epoch))
                        put(
                            "Playlist",
                            JsonArray(
                                entries.map { (media, entry) ->
                                    buildJsonObject {
                                        put("ItemId", media)
                                        put("PlaylistItemId", entry)
                                    }
                                }
                            )
                        )
                        put("PlayingItemIndex", index)
                        put("StartPositionTicks", ticks)
                        put("IsPlaying", false)
                        put("RepeatMode", repeat)
                        put("ShuffleMode", "Sorted")
                    }
                )
            )

        private fun update(type: String, data: JsonObject): String = buildJsonObject {
            put("MessageType", "SyncPlayGroupUpdate")
            put(
                "Data",
                buildJsonObject {
                    put("GroupId", "music-group")
                    put("Type", type)
                    put("Data", data)
                }
            )
        }.toString()

        fun controls() = sent.filter { it.operation !in setOf("Ping", "Ready", "Buffering") }
    }

    private fun groupedMusic(repeat: String = "RepeatNone"): Pair<MusicHost, MusicSyncRig> {
        val music = host()
        ready(music)
        val rig = MusicSyncRig(music)
        rig.join(repeat = repeat)
        assertTrue(runBlocking { music.openSync(account, "a", 200_000L) })
        until("the server-selected audio entry to be prepared paused") {
            music.state.value.current?.entry == "group-a" && music.syncBridge.snapshot().ready
        }
        return music to rig
    }

    @Test fun aSyncAudioEntryUsesTheServerPlaylistIdentityAndPreparesWithoutPlayingOrReporting() {
        val (music, rig) = groupedMusic()
        assertEquals(listOf("group-a"), music.state.value.queue!!.entries.map { it.id })
        assertEquals("a", music.state.value.current!!.track.id)
        assertEquals("group-a", lastPlayer!!.currentMediaItem!!.mediaId)
        assertEquals(20L, music.positionMs())
        assertTrue(music.state.value.queue!!.paused)
        assertFalse(lastPlayer!!.playWhenReady)
        assertTrue(log.isEmpty(), "A prepared but unheard group entry is not a started viewing")

        rig.queue(listOf("a" to "another-copy-of-a", "b" to "group-b"))
        assertTrue(runBlocking { music.openSync(account, "a", 300_000L) })
        until("a repeated media ID to render under its new server entry") {
            lastPlayer?.currentMediaItem?.mediaId == "another-copy-of-a" && music.syncBridge.snapshot().ready
        }
        assertEquals("another-copy-of-a", music.state.value.queue!!.current)
        assertEquals(30L, music.positionMs())
        assertFalse(lastPlayer!!.playWhenReady)
        assertTrue(log.isEmpty())
    }

    @Test fun groupedMusicUiAndRemoteControlsOnlyRequestServerChanges() {
        val (music, rig) = groupedMusic()
        val before = music.state.value.queue!!
        val at = music.positionMs()
        rig.sent.clear()
        music.play()
        music.pause()
        music.seekTo(70)
        music.next()
        music.previous()
        music.request(album("b", "c", context = "another-album"))
        music.playNext(account, tracks("c"))
        music.append(account, tracks("d"))
        music.move("group-b", 0)
        music.remove("group-b")
        music.jump("group-b")
        music.clearUpcoming()
        music.setRepeat("one")
        music.setShuffle(true)
        music.clear()
        music.remoteBridge.pause()
        music.remoteBridge.seekTo(60)
        music.remoteBridge.next(null)
        music.stop()
        until("all group UI and remote requests") { rig.controls().size >= 19 }
        assertEquals(before, music.state.value.queue, "Server dispatch does not replace, reorder or clear the local rendering slot")
        assertEquals(at, music.positionMs(), "A requested group seek has not yet been scheduled by the server")
        assertFalse(lastPlayer!!.playWhenReady, "Unpause is a request until the server commands it")
        assertTrue(music.state.value.outputActive, "A group Stop request does not locally tear down ahead of the server")
        assertEquals(
            listOf(
                "Unpause", "Pause", "Seek", "NextItem", "PreviousItem", "SetNewQueue", "Queue", "Queue",
                "MovePlaylistItem", "RemoveFromPlaylist", "SetPlaylistItem", "RemoveFromPlaylist", "SetRepeatMode",
                "SetShuffleMode", "RemoveFromPlaylist", "Pause", "Seek", "NextItem", "Stop"
            ),
            rig.controls().map { it.operation }
        )
        assertEquals(JsonPrimitive("group-a"), rig.sent.first { it.operation == "NextItem" }.body!!["PlaylistItemId"])
        assertTrue(log.isEmpty())
    }

    @Test fun groupedMusicOsControlsCannotBypassTheServerOrChangePlaybackRate() {
        val (music, rig) = groupedMusic()
        val before = music.state.value.queue!!
        val at = music.positionMs()
        val (os, session) = sessionPlayer(music)
        try {
            rig.sent.clear()
            os.playWhenReady = true
            os.pause()
            os.seekTo(80)
            os.seekToDefaultPosition()
            os.seekBack()
            os.seekForward()
            os.seekToNextMediaItem()
            os.seekToPreviousMediaItem()
            os.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            os.shuffleModeEnabled = true
            os.setPlaybackSpeed(1.5f)
            os.playbackParameters = androidx.media3.common.PlaybackParameters(0.5f)
            os.stop()
            until("the OS commands to dispatch to SyncPlay") { rig.controls().size >= 11 }
            assertEquals(
                listOf("Unpause", "Pause", "Seek", "Seek", "Seek", "Seek", "NextItem", "PreviousItem", "SetRepeatMode", "SetShuffleMode", "Stop"),
                rig.controls().map { it.operation }
            )
            assertEquals(before, music.state.value.queue)
            assertEquals(at, music.positionMs())
            assertFalse(lastPlayer!!.playWhenReady)
            assertEquals(1f, lastPlayer!!.playbackParameters.speed)
            assertFalse(os.isCommandAvailable(androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH))
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, lastPlayer!!.repeatMode)
            assertFalse(lastPlayer!!.shuffleModeEnabled)
        } finally {
            session.release()
        }
    }

    @Test fun incomingMusicCorrectionDoesNotEchoAndReadyReportsItsActualEntryAndPosition() {
        val (music, rig) = groupedMusic()
        rig.sent.clear()
        music.syncBridge.apply(300_000L, false, 1.0)
        until("the incoming seek to settle") { music.positionMs() == 30L && music.syncBridge.snapshot().ready }
        rig.sync.bindRenderer(music, music.syncBridge)
        rig.sync.tick()
        until("the actual music renderer readiness report") { rig.sent.any { it.operation == "Ready" } }
        val body = rig.sent.last { it.operation == "Ready" }.body!!
        assertEquals(JsonPrimitive("group-a"), body["PlaylistItemId"])
        assertEquals(300_000L, body.getValue("PositionTicks").jsonPrimitive.long)
        assertFalse(body.getValue("IsPlaying").jsonPrimitive.boolean)
        assertTrue(rig.controls().isEmpty(), "Applying the server's correction does not send Pause, Seek or Unpause back")
        assertEquals(1, lastPlayer!!.mediaItemCount)
        assertFalse(lastPlayer!!.playWhenReady)
    }

    @Test fun groupedMusicEosRequestsNextOnceAndDoesNotRepeatOrAutoAdvanceItsLocalQueue() {
        val (music, rig) = groupedMusic(repeat = "RepeatOne")
        rig.sent.clear()
        music.syncBridge.apply(0L, true, 1.0)
        until("one group Next at actual audio EOS") { rig.sent.any { it.operation == "NextItem" } }
        pump(400)
        assertEquals(1, rig.sent.count { it.operation == "NextItem" }, "One rendering completion produces one request")
        assertEquals(JsonPrimitive("group-a"), rig.sent.single { it.operation == "NextItem" }.body!!["PlaylistItemId"])
        assertEquals("group-a", music.state.value.queue!!.current)
        assertEquals(listOf("a"), negotiated.toList(), "The server has not yet chosen the following entry")
        assertFalse("start b" in log)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, lastPlayer!!.repeatMode)
        assertEquals(1, lastPlayer!!.mediaItemCount)
    }

    @Test fun leavingAfterAPausedGroupSeekPersistsTheObservedPausedResume() {
        val (music, rig) = groupedMusic()
        music.syncBridge.apply(400_000L, false, 1.0)
        until("the paused group seek") { music.positionMs() == 40L && music.syncBridge.snapshot().ready }
        rig.sent.clear()
        rig.sync.leave()
        until("the group Leave request") { rig.sent.any { it.operation == "Leave" } }
        var restored: MusicSnapshot? = null
        until("the observed paused group position to be saved for process death") {
            restored = runBlocking { MusicStore(context).load(account) }
            restored?.queue?.positionTicks == 400_000L && restored?.queue?.paused == true
        }
        val saved = assertNotNull(restored)
        assertEquals("group-a", saved.queue.current)
        assertTrue(saved.queue.paused)
        assertEquals(
            400_000L,
            saved.queue.positionTicks,
            "Process death after Leave resumes at the observed group seek, not the original join position"
        )
        assertFalse(lastPlayer!!.playWhenReady)
        assertTrue(music.state.value.outputActive)
        assertFalse(rig.sent.any { it.operation == "Stop" }, "Leaving this participant never stops the group")
    }

    @Test fun anAudioLookupThatCompletesAfterLeaveCannotStartOrReplaceLocalMusic() {
        val music = host()
        ready(music)
        val rig = MusicSyncRig(music)
        rig.join()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { itemsGate = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val opening = scope.launch { music.openSync(account, "a", 200_000L) }
        pump(30)
        rig.sync.leave()
        gate.complete(Unit)
        until("the late audio lookup") { opening.isCompleted }
        pump(100)
        assertTrue(music.state.value.queue!!.entries.isEmpty())
        assertFalse(music.state.value.outputActive)
        assertTrue(negotiated.isEmpty())
        assertTrue(log.isEmpty())
    }

    @Test fun aSlowAudioOpenCannotStealANewerServerEntryInTheSameMembership() {
        val music = host()
        ready(music)
        val rig = MusicSyncRig(music)
        rig.join()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { itemsGate = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val old = scope.launch { music.openSync(account, "a", 200_000L) }
        pump(30)
        itemsGate = null
        rig.queue(listOf("b" to "new-entry-b"))
        assertTrue(runBlocking { music.openSync(account, "b", 300_000L) })
        until("the newer paused audio entry") {
            music.state.value.queue?.current == "new-entry-b" && music.syncBridge.snapshot().ready
        }
        gate.complete(Unit)
        until("the old item lookup") { old.isCompleted }
        pump(100)
        assertEquals("new-entry-b", music.state.value.queue!!.current)
        assertEquals("b", music.state.value.current!!.track.id)
        assertEquals(listOf("b"), negotiated.toList())
        assertFalse(lastPlayer!!.playWhenReady)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun musicActionsQueuedDuringMembershipAreDiscardedAfterLeave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val music = host()
            runCurrent()
            assertNotNull(music.state.value.account)
            val rig = MusicSyncRig(music)
            runCurrent()
            rig.sync.join("music-group")
            runCurrent()
            rig.joined()
            rig.queue(listOf("a" to "group-a", "b" to "group-b"))
            rig.sent.clear()
            music.request(album("b", "c"))
            music.append(account, tracks("d"))
            music.play()
            music.seekTo(50)
            music.next()
            // The test dispatcher has not run the music actor: Leave invalidates these requests first.
            rig.sync.leave()
            runCurrent()
            assertEquals(listOf("Leave"), rig.controls().map { it.operation })
            assertTrue(music.state.value.queue!!.entries.isEmpty())
            assertFalse(music.state.value.outputActive)
            assertTrue(negotiated.isEmpty())
        } finally {
            scopes.forEach { it.cancel() }
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
