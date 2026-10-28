/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemPage
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise both real removal branches and their merged actionable accessibility nodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConfirmationInputFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("server", "Fixture server", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("viewer-a", "Viewer A"), "fixture-token")
    private val other = account.copy(user = User("viewer-b", "Viewer B"))
    private var failWrites = false
    private val accounts = AccountStore(object : TextPersistence {
        private var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) {
            check(!failWrites) { "Fixture storage failure" }
            this.value = value
        }
    })
    private lateinit var host: View
    private var koinStarted = false
    private val manager get() = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        if (koinStarted) stopKoin()
        shadowOf(manager).apply {
            setTouchExplorationEnabled(false)
            setEnabled(false)
            setEnabledAccessibilityServiceList(emptyList())
        }
    }

    private fun chooser(accessible: Boolean = true) {
        shadowOf(manager).apply {
            setEnabledAccessibilityServiceList(
                listOf(
                    AccessibilityServiceInfo().apply {
                        feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
                    }
                )
            )
            setEnabled(accessible)
            setTouchExplorationEnabled(accessible)
        }
        runBlocking {
            accounts.remember(other)
            accounts.remember(account)
        }
        val service = object : JellyfinService {
            override suspend fun probe(address: String) = ClientResult.Success(server)
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun authenticate(server: Server, username: String, password: String) = ClientResult.Success(account)
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(false)
            override fun quickConnect(server: Server) = emptyFlow<QuickConnectState>()
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        val catalog = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun views(account: Account) = ClientResult.Success(ItemPage(emptyList()))
        }
        startKoin {
            modules(
                module {
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { catalog }
                    single<ServerDiscovery> {
                        object : ServerDiscovery {
                            override suspend fun discover() = emptyList<app.pictureshow.core.DiscoveredServer>()
                        }
                    }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account, session: CatalogSession) -> CatalogHomeViewModel(account, session) }
                }
            )
        }
        koinStarted = true
        compose.activity.setContent {
            host = LocalView.current
            MaterialTheme { Pictureshow() }
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Viewer A · Fixture server").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Who’s watching?").assertExists()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun click(label: String) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun hidden(label: String, expected: Boolean) {
        compose.onNodeWithText(label).assert(
            if (expected) {
                SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit)
            } else {
                SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility)
            }
        )
    }

    private fun assertOtherActionsHidden(target: String) {
        val intended = compose.onNodeWithText(target).fetchSemanticsNode().id
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.id != intended }.forEach {
            assertTrue(it.config.contains(SemanticsProperties.HideFromAccessibility), "Every other clickable target must be hidden")
        }
    }

    private fun acknowledge(label: String) {
        val node = compose.onNodeWithText(label).assertIsFocused().fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(host.accessibilityNodeProvider.performAction(node.id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
            val focused = requireNotNull(host.accessibilityNodeProvider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            assertTrue(focused.isVisibleToUser && focused.isClickable && focused.isFocused, "Both cursors must own the same visible action")
            @Suppress("DEPRECATION")
            focused.recycle()
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun openRemoval(serverRemoval: Boolean) {
        click(if (serverRemoval) "Remove server Fixture server" else "Remove Viewer B on Fixture server")
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Cancel").assertIsFocused()
        val question = if (serverRemoval) "Remove server?" else "Remove remembered account?"
        val description = if (serverRemoval) {
            "This removes Fixture server and 2 remembered accounts from this TV."
        } else {
            "This removes Viewer B on Fixture server from this TV."
        }
        compose.onNodeWithText("Cancel").assertContentDescriptionEquals(
            "$question $description Other accounts stay signed in. You can add it again later."
        )
        assertOtherActionsHidden("Cancel")
        hidden(question, true)
        hidden(if (serverRemoval) "Remove server" else "Remove account", true)
    }

    /** Real D-pad delivery during the acknowledgement window must not land on an invisible action. */
    private fun protectedArrival(serverRemoval: Boolean, timeout: Boolean) {
        chooser()
        openRemoval(serverRemoval)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Cancel").assertIsFocused()
        val destructive = if (serverRemoval) "Remove server" else "Remove account"
        // Reject direct requests too, rather than merely cancelling one navigation key direction.
        compose.onNodeWithText(destructive).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(destructive).assertIsNotFocused()
        compose.onNodeWithText("Cancel").assertIsFocused()
        if (timeout) compose.mainClock.advanceTimeBy(1_600) else acknowledge("Cancel")
        hidden(destructive, false)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(destructive).assertIsFocused()
        assertEquals(2, accounts.snapshots.value.accounts.size)
    }

    private fun protectedReturn(serverRemoval: Boolean, back: Boolean, accessible: Boolean) {
        chooser(accessible)
        click(if (serverRemoval) "Remove server Fixture server" else "Remove Viewer B on Fixture server")
        compose.mainClock.advanceTimeBy(300)
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Cancel").assertIsFocused()
        if (accessible) acknowledge("Cancel")
        // In ordinary mode navigation is immediately available, with no hand-off window.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(if (serverRemoval) "Remove server" else "Remove account").assertIsFocused()
        if (back) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() } else click("Cancel")
        compose.mainClock.advanceTimeBy(300)
        val opener = if (serverRemoval) "Remove server Fixture server" else "Remove Viewer B on Fixture server"
        compose.onNodeWithText(opener).assertIsFocused()
        if (accessible) {
            assertOtherActionsHidden(opener)
            // Try every clickable action directly as well as all four directions.
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
                it.id != compose.onNodeWithText(opener).fetchSemanticsNode().id
            }.forEach { node ->
                compose.onNode(SemanticsMatcher("node id") { it.id == node.id })
                    .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.onNodeWithText(opener).assertIsFocused()
            }
            compose.onRoot().performKeyInput {
                pressKey(Key.DirectionUp)
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionLeft)
                pressKey(Key.DirectionRight)
            }
            compose.onNodeWithText(opener).assertIsFocused()
            acknowledge(opener)
        }
        // Every action can regain physical focus after acknowledgement (or immediately with TalkBack off).
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            compose.onNode(SemanticsMatcher("node id") { it.id == node.id })
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .assertIsFocused()
        }
        assertEquals(2, accounts.snapshots.value.accounts.size)
    }

    @Test fun aFailedRemovalStatusCannotStealTheReturnHandoff() {
        chooser()
        openRemoval(false)
        acknowledge("Cancel")
        compose.runOnIdle { failWrites = true }
        click("Remove account")
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Could not save accounts on this device. Please try again.").assertExists()
        click("Cancel")
        compose.mainClock.advanceTimeBy(300)
        val opener = "Remove Viewer B on Fixture server"
        compose.onNodeWithText(opener).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(opener).assertIsFocused()
        val status = compose.onNodeWithText("Could not save accounts on this device. Please try again.", useUnmergedTree = true).onParent()
        // Clearing semantics deliberately removes RequestFocus as well as hiding the child text.
        // The physical Down check above proves the underlying input target is blocked too.
        status.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
        status.assert(SemanticsMatcher.expectValue(SemanticsProperties.HideFromAccessibility, Unit))
        compose.onNodeWithText(opener).assertIsFocused()
        acknowledge(opener)
        compose.onNodeWithText(
            "Could not save accounts on this device. Please try again."
        ).onParent().performSemanticsAction(SemanticsActions.RequestFocus) {
            it()
        }.assertIsFocused()
        assertEquals(2, accounts.snapshots.value.accounts.size)
    }

    @Test fun accountArrivalRejectsInputUntilAcknowledged() = protectedArrival(false, false)

    @Test fun serverArrivalRejectsInputUntilAcknowledged() = protectedArrival(true, false)

    @Test fun accountArrivalReleasesInputAtTimeout() = protectedArrival(false, true)

    @Test fun serverArrivalReleasesInputAtTimeout() = protectedArrival(true, true)

    @Test fun accountCancelProtectsExactOpenerUntilAcknowledged() = protectedReturn(false, false, true)

    @Test fun accountBackProtectsExactOpenerUntilAcknowledged() = protectedReturn(false, true, true)

    @Test fun serverCancelProtectsExactOpenerUntilAcknowledged() = protectedReturn(true, false, true)

    @Test fun serverBackProtectsExactOpenerUntilAcknowledged() = protectedReturn(true, true, true)

    @Test fun accountCancelWithoutTalkBackRestoresAndReleasesInputImmediately() = protectedReturn(false, false, false)

    @Test fun accountBackWithoutTalkBackRestoresAndReleasesInputImmediately() = protectedReturn(false, true, false)

    @Test fun serverCancelWithoutTalkBackRestoresAndReleasesInputImmediately() = protectedReturn(true, false, false)

    @Test fun serverBackWithoutTalkBackRestoresAndReleasesInputImmediately() = protectedReturn(true, true, false)
}
