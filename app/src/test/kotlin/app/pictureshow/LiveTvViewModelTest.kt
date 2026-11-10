/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveGuideInfo
import app.pictureshow.core.livetv.LiveInfo
import app.pictureshow.core.livetv.LivePage
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.LiveTvService
import app.pictureshow.core.livetv.LiveWrite
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("viewer", "Viewer"), "token")
    private val fake = LiveFake()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun staleAndRefreshingGuideCannotAct() {
        val state = LiveTvState(loading = false, fetchedAt = 1_000L)
        kotlin.test.assertTrue(state.actionsAvailable(2_000))
        kotlin.test.assertFalse(state.copy(loading = true).actionsAvailable(2_000))
        kotlin.test.assertFalse(state.copy(offline = true).actionsAvailable(2_000))
        kotlin.test.assertFalse(state.actionsAvailable(301_001))
        kotlin.test.assertFalse(state.actionsAvailable(999))
    }

    @Test fun failureRetainsLabeledGuideButRevocationPurgesIt() = runTest(dispatcher) {
        val vm = LiveTvViewModel(account, fake) { 1_000L }
        vm.refresh(0)
        advanceUntilIdle()
        assertEquals(listOf("p"), vm.state.value.programs.map { it.id })
        assertEquals(1_000L, vm.state.value.fetchedAt)
        fake.failPrograms = true
        vm.refresh(0)
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertEquals("p", vm.state.value.programs.single().id)
        fake.rights = LivePolicy(access = false)
        vm.refresh(0)
        advanceUntilIdle()
        assertTrue(vm.state.value.channels.isEmpty())
        assertTrue(vm.state.value.programs.isEmpty())
        assertTrue(vm.state.value.timers.isEmpty())
    }

    @Test fun channelRemovalPurgesCachedMetadataEvenWhenGuideFails() = runTest(dispatcher) {
        val vm = LiveTvViewModel(account, fake)
        vm.refresh(0)
        advanceUntilIdle()
        fake.channelRows = listOf(LiveChannel("different", "Other"))
        fake.failPrograms = true
        vm.refresh(0)
        advanceUntilIdle()
        assertTrue(vm.state.value.programs.isEmpty())
        assertTrue(vm.state.value.offline)
    }

    @Test fun revokedManagementCannotWriteButContentDeletionHasOwnPolicy() = runTest(dispatcher) {
        val vm = LiveTvViewModel(account, fake)
        val draft = LiveSchedule(buildJsonObject { put("Id", "timer") })
        fake.rights = LivePolicy(access = true, manage = false, deleteContent = true)
        vm.save(draft, false, false)
        advanceUntilIdle()
        assertEquals(0, fake.created)
        vm.delete(MediaItem("recording"))
        advanceUntilIdle()
        assertEquals(1, fake.deleted)
    }

    @Test fun unknownWriteRetainsReviewableDraftAndNeverSaysItSucceeded() = runTest(dispatcher) {
        val vm = LiveTvViewModel(account, fake)
        vm.defaults(fake.show, false)
        advanceUntilIdle()
        val draft = assertNotNull(vm.state.value.draft)
        fake.result = LiveWrite("unknown", message = "Could not confirm. Reconcile before recording again.")
        vm.save(draft, false, false)
        advanceUntilIdle()
        assertNotNull(vm.state.value.draft)
        assertTrue(vm.state.value.notice.orEmpty().contains("Reconcile"))
    }
}

internal class LiveFake : LiveTvService {
    var rights = LivePolicy(true, true, true, true)
    var channelRows = listOf(LiveChannel("c", "Generated", "2"))
    var show = LiveProgram("p", "Generated program", "c", startDate = "1970-01-01T00:00:00Z", endDate = "1970-01-01T01:00:00Z")
    var failPrograms = false
    var created = 0
    var deleted = 0
    var favoriteWrites = 0
    var result = LiveWrite("ok", message = "Confirmed by server")
    override suspend fun policy(account: Account) = ClientResult.Success(rights)
    override suspend fun info(account: Account) = ClientResult.Success(LiveInfo(true))
    override suspend fun guideInfo(account: Account) = ClientResult.Success(LiveGuideInfo())
    override suspend fun channels(account: Account, startIndex: Int, limit: Int, search: String?, favorites: Boolean) =
        ClientResult.Success(LivePage(channelRows, channelRows.size))
    override suspend fun channel(account: Account, id: String) = ClientResult.Success(channelRows.first { it.id == id })
    override suspend fun programs(
        account: Account,
        channelIds: List<String>,
        startUtc: String,
        endUtc: String,
        startIndex: Int,
        limit: Int
    ): ClientResult<LivePage<LiveProgram>> = if (failPrograms) {
        ClientResult.Failure(
            ClientError(ErrorKind.NETWORK, "Offline")
        )
    } else {
        ClientResult.Success(LivePage(listOf(show), 1))
    }
    override suspend fun program(account: Account, id: String) = ClientResult.Success(show)
    override suspend fun recordings(account: Account, startIndex: Int, limit: Int) = ClientResult.Success(ItemPage())
    override suspend fun timers(account: Account, series: Boolean) = ClientResult.Success(emptyList<LiveSchedule>())
    override suspend fun defaults(account: Account, programId: String) =
        ClientResult.Success(LiveSchedule(buildJsonObject { put("Priority", 0) }))
    override suspend fun createTimer(account: Account, draft: LiveSchedule, series: Boolean): LiveWrite {
        created++
        return result
    }
    override suspend fun updateTimer(account: Account, schedule: LiveSchedule, series: Boolean) = result
    override suspend fun cancelTimer(account: Account, id: String, series: Boolean) = result
    override suspend fun deleteRecording(account: Account, id: String): LiveWrite {
        deleted++
        return result
    }
    override suspend fun favorite(account: Account, id: String, favorite: Boolean): ClientResult<LiveChannel> {
        favoriteWrites++
        channelRows = channelRows.map { if (it.id == id) it.copy(userData = it.userData.copy(isFavorite = favorite)) else it }
        return channel(account, id)
    }
}
