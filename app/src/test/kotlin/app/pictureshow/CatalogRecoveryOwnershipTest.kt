/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRecoveryOwnershipTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val service = CatalogModelsTest.FakeCatalog()
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    })
    private val session = CatalogSession(service, accounts)

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun retryOwnsAPendingReadInsteadOfThePreviousFailure() = runTest(dispatcher) {
        service.detail = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Old failure"))
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        vm.reload()
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.readFailed, "A pending new request cannot still claim its predecessor failed")
        held.complete(ClientResult.Success(film))
        runCurrent()
    }

    @Test fun openingAfterOldSessionRefreshDoesNotRestartItsConstructorRead() = runTest(dispatcher) {
        session.refresh()
        var reads = 0
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = {
            reads++
            held.await()
        }
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        vm.resumed()
        runCurrent()
        assertEquals(1, reads, "A historical revision is the new destination's baseline, not another refresh")
        held.complete(ClientResult.Success(film))
        runCurrent()
    }

    @Test fun pendingOnlySessionUpdatesDoNotReloadHome() = runTest(dispatcher) {
        CatalogHomeViewModel(account, session).also { store.put("home", it) }
        runCurrent()
        assertEquals(1, service.resumeCalls)
        session.playbackPending("film", "report1")
        runCurrent()
        session.playbackPending("episode", "report2", setOf("series"))
        runCurrent()
        assertEquals(1, service.resumeCalls, "Report bookkeeping contains no new catalog data")
        session.refresh("film", "report1")
        runCurrent()
        assertEquals(2, service.resumeCalls, "An acknowledged report still refreshes retained Home")
        session.changed(film)
        runCurrent()
        assertEquals(3, service.resumeCalls, "An acknowledged mutation still refreshes Home")
    }

    @Test fun newestPostStopReadCanFinishCompletionBeforeItsOlderReadReturns() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        val fresh = film.copy(userData = ItemUserData(played = true))
        service.detailBlock = { if (++reads == 1) old.await() else ClientResult.Success(fresh) }
        session.playbackPending(film.id, "report")
        val completion = launch {
            session.completePlayback(account, film.id)
            session.refresh(film.id, "report")
        }
        runCurrent()
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        try {
            assertTrue(vm.state.value.readSucceeded, "The destination's newer full read succeeded")
            assertTrue(completion.isCompleted, "A newer full post-Stop read must replace the older completion read")
            assertFalse(vm.state.value.playbackPending, "No pending report remains after authoritative completion")
            assertTrue(service.actions.isEmpty(), "Fresh played=true must not increment the count again")
        } finally {
            old.complete(ClientResult.Success(film))
            runCurrent()
        }
        assertEquals(fresh.userData, vm.state.value.item?.userData)
    }

    @Test fun aReadStartedBeforeStopCannotFinishTheNewCompletionCheck() = runTest(dispatcher) {
        val beforeStop = CompletableDeferred<ClientResult<MediaItem>>()
        val postStop = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = { if (++reads == 1) beforeStop.await() else postStop.await() }
        val prior = launch { session.readItem(account, film.id) }
        runCurrent()
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        beforeStop.complete(ClientResult.Success(film.copy(userData = ItemUserData(played = true))))
        runCurrent()
        assertTrue(prior.isCompleted)
        assertFalse(completion.isCompleted, "Pre-Stop data cannot authorize completion")
        postStop.complete(ClientResult.Success(film.copy(userData = ItemUserData(played = true))))
        runCurrent()
        assertTrue(completion.isCompleted)
        assertTrue(service.actions.isEmpty())
    }

    @Test fun aNewFailedReadCannotReleaseTheOlderCompletionGuard() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            if (++reads == 1) old.await() else ClientResult.Failure(ClientError(ErrorKind.NETWORK, "New 503"))
        }
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        session.readItem(account, film.id)
        runCurrent()
        assertFalse(completion.isCompleted)
        old.complete(ClientResult.Success(film.copy(userData = ItemUserData(played = true))))
        runCurrent()
        assertTrue(completion.isCompleted)
    }

    @Test fun aCanceledNewReadCannotPublishItsLateSuccessfulSnapshotToCompletion() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<MediaItem>>()
        val canceled = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            if (++reads == 1) old.await() else withContext(NonCancellable) { canceled.await() }
        }
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        val obsolete = launch { session.readItem(account, film.id) }
        runCurrent()
        obsolete.cancel()
        canceled.complete(ClientResult.Success(film.copy(userData = ItemUserData(played = true))))
        runCurrent()
        assertFalse(completion.isCompleted)
        old.complete(ClientResult.Success(film.copy(userData = ItemUserData(played = true))))
        runCurrent()
        assertTrue(completion.isCompleted)
    }

    @Test fun aNewReadStillWaitsForTheRequiredPlayedWriteBeforeReleasingActions() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<MediaItem>>()
        val write = CompletableDeferred<ClientResult<ItemUserData>>()
        var reads = 0
        service.detailBlock = { if (++reads == 1) old.await() else ClientResult.Success(film) }
        service.actionBlock = { write.await() }
        session.playbackPending(film.id, "report")
        val completion = launch {
            session.completePlayback(account, film.id)
            session.refresh(film.id, "report")
        }
        runCurrent()
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        try {
            assertEquals(listOf(Triple("played", film.id, true)), service.actions)
            assertFalse(completion.isCompleted)
            assertTrue(vm.state.value.playbackPending, "Fresh read does not bypass a still-pending write")
            service.detailBlock = { ClientResult.Success(film.copy(userData = ItemUserData(played = true))) }
            write.complete(ClientResult.Success(ItemUserData(played = true)))
            runCurrent()
            assertTrue(completion.isCompleted)
            assertFalse(vm.state.value.playbackPending)
            assertTrue(vm.state.value.item?.userData?.played == true)
        } finally {
            old.complete(ClientResult.Success(film))
            write.complete(ClientResult.Success(ItemUserData(played = true)))
            runCurrent()
        }
    }

    @Test fun homeKeepsItsConstructorReadThroughFirstResumeAndRefreshesARealReturn() = runTest(dispatcher) {
        val vm = CatalogHomeViewModel(account, session).also { store.put("home", it) }
        runCurrent()
        vm.resumed()
        runCurrent()
        assertEquals(1, service.resumeCalls)
        vm.resumed()
        runCurrent()
        assertEquals(2, service.resumeCalls)
    }

    @Test fun obsoleteCompletionReadCannotDelayReadinessOrExpireTheSuccessfulAccount() = runTest(dispatcher) {
        for (kind in listOf(ErrorKind.INVALID_CREDENTIALS, ErrorKind.EXPIRED)) {
            accounts.remember(account)
            val old = CompletableDeferred<ClientResult<MediaItem>>()
            var reads = 0
            service.detailBlock = {
                if (++reads == 1) {
                    withContext(NonCancellable) { old.await() }
                } else {
                    ClientResult.Success(film.copy(userData = ItemUserData(played = true)))
                }
            }
            val outcome = CompletableDeferred<ClientResult<Unit>>()
            session.playbackPending(film.id, kind)
            val completion = launch {
                outcome.complete(session.completePlayback(account, film.id))
                session.refresh(film.id, kind)
            }
            runCurrent()
            session.readItem(account, film.id)
            runCurrent()
            try {
                assertTrue(outcome.isCompleted, "The new read must release reporting before obsolete cleanup finishes")
                assertTrue(session.changes.value.pendingPlayback.isEmpty())
                assertEquals(account, accounts.snapshots.value.currentAccount)
            } finally {
                old.complete(ClientResult.Failure(ClientError(kind, "Obsolete authentication response")))
                runCurrent()
            }
            assertTrue(completion.isCompleted)
            assertEquals(account, accounts.snapshots.value.currentAccount, "A canceled read cannot sign out a successfully read account")
        }
    }

    @Test fun advancingDoesNotJoinTheObsoleteCompletionTransport() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            if (++reads == 1) {
                withContext(NonCancellable) { old.await() }
            } else {
                ClientResult.Success(film.copy(userData = ItemUserData(played = true)))
            }
        }
        val decision = app.pictureshow.core.playback.PlayDecision(
            film.id,
            app.pictureshow.core.playback.MediaSourceInfo(id = "source"),
            app.pictureshow.core.playback.PlayMethod.DirectPlay,
            "https://example.test/video",
            emptyMap(),
            "play-session"
        )
        val gateway = object : PlaybackGateway {
            override suspend fun prepare(request: app.pictureshow.core.playback.PlaybackRequest) =
                ClientResult.Success(app.pictureshow.core.playback.PlayOutcome.Play(decision))
            override suspend fun start(info: app.pictureshow.core.playback.PlaybackProgressInfo) = ClientResult.Success(Unit)
            override suspend fun progress(info: app.pictureshow.core.playback.PlaybackProgressInfo) = ClientResult.Success(Unit)
            override suspend fun stopped(info: app.pictureshow.core.playback.PlaybackStopInfo) = ClientResult.Success(Unit)
            override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
            override suspend fun played() = session.completePlayback(account, film.id)
        }
        val output = object : PlaybackOutput {
            override fun position() = PlaybackPosition(1_000, false)
            override fun open(
                decision: app.pictureshow.core.playback.PlayDecision,
                positionMs: Long,
                audioIndex: Int,
                subtitleIndex: Int,
                paused: Boolean
            ) {}
            override fun select(type: app.pictureshow.core.playback.MediaStreamType, index: Int) = false
            override fun pause() {}
            override fun release() {}
        }
        var finished = false
        var advanced = false
        var cleaned = false
        val player = PlaybackSession(
            gateway,
            output,
            backgroundScope,
            "en",
            onFinished = {
                session.refresh(film.id, "advance")
                finished = true
            },
            onCompletionPending = { session.playbackPending(film.id, "advance") },
            onCleanupDone = { cleaned = true }
        )
        player.start(0)
        runCurrent()
        player.ready()
        runCurrent()
        player.closeForNext({ true }) { advanced = true }
        runCurrent()
        session.readItem(account, film.id)
        runCurrent()
        try {
            assertTrue(finished, "A newer successful snapshot must release the joined reporting job")
            assertTrue(advanced, "The deliberate advance cannot wait for obsolete transport cleanup")
            assertTrue(cleaned)
            assertTrue(session.changes.value.pendingPlayback.isEmpty())
            assertFalse(old.isCompleted)
            assertTrue(service.actions.isEmpty())
        } finally {
            old.complete(ClientResult.Success(film))
            runCurrent()
        }
    }

    @Test fun otherContainersStillFinishTheirOwnReadGuard() = runTest(dispatcher) {
        val collection = film.copy(id = "collection", type = "BoxSet", isFolder = true)
        session.playbackPending(collection.id, "report")
        session.refresh(collection.id, "report")
        service.detail = ClientResult.Success(collection)
        val vm = CatalogDetailViewModel(account, collection, session).also { store.put("collection", it) }
        runCurrent()
        assertTrue(vm.state.value.readSucceeded, "Only Series and Season wait for dependent episode reads")
        assertFalse(vm.state.value.playbackPending, "The completed full read must resolve this container's read guard")
    }
}
