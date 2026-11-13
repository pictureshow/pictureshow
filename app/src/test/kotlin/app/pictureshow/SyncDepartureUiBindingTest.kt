/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncDeparture
import app.pictureshow.core.sync.SyncDepartureCause
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * Root-owned regression. Production modifier, panel and binding are called directly.
 * Only gateway/time boundaries are fake; no copied reducer or acknowledgement wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncDepartureUiBindingTest {
    @get:Rule(order = -2) val timeLimit = PerTestTimeLimit(180)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixtures = mutableListOf<HostFixture>()

    @Before fun nativeModeMatchesConfiguration() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
    }
    @After fun closeFixtureScopes() {
        compose.runOnIdle { fixtures.forEach { it.close() } }
    }
    @Composable private fun Theme(content: @Composable () -> Unit) {
        app.pictureshow.ui.PictureshowTheme(content = content)
    }
    private fun change(fixture: HostFixture, block: () -> Unit) {
        compose.runOnIdle { block(); fixture.scheduler.runCurrent() }
        compose.waitForIdle()
    }
    private fun fixture(): HostFixture = HostFixture().also { fixtures += it }
    private fun dialogBack() {
        compose.runOnIdle {
            // Actual Compose Dialog event path, not direct invocation of a fabricated onDismiss.
            (requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    @Test fun productionObserverNeedsPlacementAfterCompositionBeforeAcknowledging() {
        var placed by mutableStateOf(false)
        var compositions = 0
        val acknowledgements = mutableListOf<Long>()
        compose.setContent {
            SideEffect { compositions++ }
            Layout(content = {
                Box(Modifier.requiredSize(32.dp).syncDeparturePresentation(73L, { acknowledgements += it }))
            }) { children, _ ->
                val pixels = 32.dp.roundToPx()
                val child = children.single().measure(Constraints.fixed(pixels, pixels))
                layout(96.dp.roundToPx(), 96.dp.roundToPx()) {
                    if (placed) child.placeRelative(0, 0)
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(compositions > 0, "The real production observer was composed and measured")
            assertTrue(acknowledgements.isEmpty(), "An unplaced banner is not a visible layout")
            placed = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(acknowledgements.isNotEmpty(), "Visible placement must report the departure")
            assertEquals(setOf(73L), acknowledgements.toSet())
        }
    }

    @Test fun productionObserverRejectsZeroAreaAndFullyClippedLayoutBeforeVisibility() {
        var viewport by mutableStateOf(0.dp)
        var offset by mutableStateOf(80.dp)
        val acknowledgements = mutableListOf<Long>()
        compose.setContent {
            Box(Modifier.size(viewport).clipToBounds()) {
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.roundToPx(), 0) }.requiredSize(20.dp)
                    .syncDeparturePresentation(95L, { acknowledgements += it }))
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty(), "Zero visible area must not acknowledge")
            viewport = 40.dp
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty(), "Nonempty child fully outside the clipped viewport must not acknowledge")
            offset = 0.dp
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(acknowledgements.isNotEmpty())
            assertEquals(setOf(95L), acknowledgements.toSet())
        }
    }

    @Test fun actualPhonePanelReportsOnlyItsSelectedVisibleDepartureId() = panelSelection()

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualTvPanelReportsOnlyItsSelectedVisibleDepartureId() = panelSelection()

    private fun panelSelection() {
        val fact = SyncDeparture(147L, "Evening", SyncDepartureCause.PLAYBACK_STOPPED)
        var state by mutableStateOf(SyncPlayPresentation(
            server = "Cinema", viewer = "Ana", policyKnown = true, policy = "CreateAndJoinGroups",
            departure = fact, notice = SyncNotice(SyncNoticeKind.ACCESS_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.REFRESH)
        ))
        val acknowledgements = mutableListOf<Long>()
        compose.setContent {
            Theme { SyncPlayPanel(state, { _, _ -> }, {}, fixtureLabel = null,
                onDeparturePresented = { acknowledgements += it }) }
        }
        compose.onNodeWithTag("sync:recovery").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty(), "Composed pending fact is covered by the actual error banner")
            state = state.copy(notice = null, groupId = "g", groupName = "Evening", phase = "Paused")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty(), "Joined mode is not a departure chooser")
            state = state.copy(groupId = null, groupName = null, phase = "Reconnecting")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(acknowledgements.isEmpty(), "Reconnecting recovery owns the banner")
            state = state.copy(phase = "Idle")
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(acknowledgements.isNotEmpty())
            assertEquals(setOf(fact.id), acknowledgements.toSet(), "Callback must carry selected record id, never a generic LEFT id")
        }
    }

    @Test fun realBindingUsesExactIdentityAndPhoneCloseConsumesOnlyAfterVisibleAcknowledgement() {
        val rig = fixture()
        change(rig) { rig.prepareHiddenStop() }
        val fact = assertNotNull(rig.host.state.value.departure)
        val accountScope = rig.host.departureState.scope
        compose.setContent { Theme { SyncPlayChooser(rig.host) } }
        compose.onNodeWithTag("sync:panel").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(null, rig.host.departureState.presentedIn, "Hidden real binding must not acknowledge on composition")
            assertEquals(fact, rig.host.state.value.departure)
        }
        change(rig) { rig.host.show() }
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        val session = rig.host.chooserSession
        compose.runOnIdle {
            // Production host accepts Presented only for exact scope/id/current session.
            assertEquals(accountScope, rig.host.departureState.scope)
            assertEquals(fact.id, rig.host.departureState.pending?.id)
            assertEquals(session, rig.host.departureState.presentedIn)
        }
        compose.onNodeWithTag("sync:close").performClick()
        compose.runOnIdle {
            assertFalse(rig.host.panelOpen)
            assertEquals(null, rig.host.state.value.departure)
            assertEquals(listOf("Leave"), rig.departureRequests())
        }
        change(rig) { rig.host.show() }
        compose.runOnIdle {
            assertTrue(rig.host.chooserSession > session)
            assertEquals(null, rig.host.state.value.departure, "Consumed fact must not recur")
        }
        compose.onNodeWithTag("sync:status").assertDoesNotExist()
    }

    @Test fun realBindingBackWithoutPresentationRetainsCoveredFactThenVisibleCloseConsumes() = coveredDismissalRetains(useBack = true)

    @Test fun realBindingCloseWithoutPresentationRetainsCoveredFactThenVisibleBackConsumes() = coveredDismissalRetains(useBack = false)

    private fun coveredDismissalRetains(useBack: Boolean) {
        val rig = fixture()
        change(rig) { rig.prepareHiddenStop(); rig.failAccess = true }
        val fact = assertNotNull(rig.host.state.value.departure)
        compose.setContent { Theme { SyncPlayChooser(rig.host) } }
        change(rig) { rig.host.show() }
        compose.onNodeWithTag("sync:recovery").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(null, rig.host.departureState.presentedIn)
            assertEquals(fact, rig.host.state.value.departure)
        }
        if (useBack) dialogBack() else compose.onNodeWithTag("sync:close").performClick()
        compose.runOnIdle {
            assertFalse(rig.host.panelOpen)
            assertEquals(fact, rig.host.state.value.departure, "Unshown fact survives an actual Close/Back dismissal")
        }
        change(rig) { rig.failAccess = false; rig.host.show() }
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle { assertEquals(rig.host.chooserSession, rig.host.departureState.presentedIn) }
        if (useBack) compose.onNodeWithTag("sync:close").performClick() else dialogBack()
        compose.runOnIdle { assertEquals(null, rig.host.state.value.departure) }
    }

    @Test fun realBindingCompositionDisposalAndConfigurationRekeyDoNotDismissAcknowledgedFact() {
        val rig = fixture()
        change(rig) { rig.prepareHiddenStop() }
        var mounted by mutableStateOf(true)
        var configurationKey by mutableStateOf(0)
        compose.setContent {
            Theme { if (mounted) key(configurationKey) { SyncPlayChooser(rig.host) } }
        }
        change(rig) { rig.host.show() }
        val fact = assertNotNull(rig.host.state.value.departure)
        val scope = rig.host.departureState.scope; val session = rig.host.chooserSession
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(session, rig.host.departureState.presentedIn)
            mounted = false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:panel").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(rig.host.panelOpen, "Disposal must not route through deliberate dismissChooser")
            assertEquals(fact, rig.host.state.value.departure)
            assertEquals(session, rig.host.departureState.presentedIn)
            configurationKey++
            mounted = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(scope, rig.host.departureState.scope)
            assertEquals(session, rig.host.chooserSession)
            assertEquals(fact, rig.host.state.value.departure)
            configurationKey++ // actual production subtree is disposed/recreated again, still no dismissal
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(fact, rig.host.state.value.departure) }
        compose.onNodeWithTag("sync:close").performClick()
        compose.runOnIdle { assertEquals(null, rig.host.state.value.departure) }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun realTvBindingBackConsumesTheAcknowledgedMatchingDeparture() {
        val rig = fixture()
        change(rig) { rig.prepareHiddenStop() }
        compose.setContent { Theme { SyncPlayChooser(rig.host) } }
        change(rig) { rig.host.show() }
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle { assertEquals(rig.host.chooserSession, rig.host.departureState.presentedIn) }
        dialogBack()
        compose.runOnIdle {
            assertFalse(rig.host.panelOpen)
            assertEquals(null, rig.host.state.value.departure)
            assertEquals(listOf("Leave"), rig.departureRequests())
        }
    }

    @Test fun mountedRealBindingRebindUsesNewAccountScopeAndNewDepartureId() {
        val rig = fixture()
        change(rig) { rig.prepareHiddenStop() }
        compose.setContent { Theme { SyncPlayChooser(rig.host) } }
        change(rig) { rig.host.show() }
        val first = assertNotNull(rig.host.state.value.departure)
        val oldScope = rig.host.departureState.scope
        compose.runOnIdle { assertEquals(rig.host.chooserSession, rig.host.departureState.presentedIn) }
        change(rig) {
            rig.account = rig.account.copy(user = User("b", "Bea"), accessToken = "different-test-token")
            rig.prepareHiddenStop() // real bind, Join/accepted metadata, then a different real departure
        }
        val second = assertNotNull(rig.host.state.value.departure)
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(rig.host.departureState.scope > oldScope)
            assertTrue(second.id > first.id)
            assertEquals(second, rig.host.departureState.pending)
            assertEquals(rig.host.chooserSession, rig.host.departureState.presentedIn,
                "Actual binding must capture the new scope/id/session, not remember the old closure")
        }
        compose.onNodeWithTag("sync:close").performClick()
        compose.runOnIdle { assertEquals(null, rig.host.state.value.departure) }
    }

    /** Only external gateway/time boundaries are fake. Every membership/departure transition is production. */
    private class HostFixture {
        val scheduler = TestCoroutineScheduler()
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var account = Account(Server("s", "Cinema", "https://media.example.test", "12"), User("a", "Ana"), "test-token")
        var failAccess = false
        private val epoch = 1_762_776_000_000L
        private val sent = mutableListOf<String>()
        private val gateway = object : SyncPlayGateway {
            override suspend fun groups(account: Account) = ClientResult.Success(
                listOf(SyncGroup("g", "Evening", "Paused", emptyList(), epoch * 10_000)))
            override suspend fun access(account: Account): ClientResult<String> = if (failAccess) {
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Access check unavailable"))
            } else ClientResult.Success("CreateAndJoinGroups")
            override suspend fun time(account: Account) = ClientResult.Success(
                SyncUtcTime(epoch + scheduler.currentTime, epoch + scheduler.currentTime))
            override suspend fun send(account: Account, request: SyncRequest): Int { sent += request.operation; return 204 }
        }
        val host = SyncPlayHost(scope, gateway, { epoch + scheduler.currentTime }, { scheduler.currentTime })
        fun prepareHiddenStop() {
            host.bind(account); host.socket(true); host.refresh(); scheduler.runCurrent()
            host.join("g"); scheduler.runCurrent()
            host.receive(buildJsonObject {
                put("MessageType", "SyncPlayGroupUpdate")
                put("Data", buildJsonObject {
                    put("GroupId", "g"); put("Type", "GroupJoined")
                    put("Data", buildJsonObject {
                        put("GroupId", "g"); put("GroupName", "Evening"); put("State", "Paused")
                        put("Participants", JsonArray(emptyList())); put("LastUpdatedAt", syncUtcTimestamp(epoch))
                    })
                })
            }.toString())
            host.leave(SyncDepartureCause.PLAYBACK_STOPPED); scheduler.runCurrent()
        }
        fun departureRequests() = sent.filter { it == "Leave" }
        fun close() { scope.cancel(); scheduler.runCurrent() }
    }
}
