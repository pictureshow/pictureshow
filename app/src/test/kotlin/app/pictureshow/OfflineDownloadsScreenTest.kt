/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineConflict
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflineOutboxEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.offlineEncodeManifest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineDownloadsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val account =
        Account(Server("server", "Fixture server", "https://media.example.test", "12.0.0"), User("viewer-a", "Viewer A"), "fixture-token")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: OfflineEngine
    private var played: String? = null

    private fun entry(id: String, phase: OfflinePhase, job: OfflineJob = OfflineJob(phase)) = OfflineEntry(
        id = id,
        itemId = id,
        kind = OfflineMediaKind.movie,
        preset = OfflinePreset.video720,
        labels = OfflineLabels("Title $id"),
        job = job
    )

    @Before
    fun setUp() {
        install(
            entry("failed", OfflinePhase.failed, OfflineJob(OfflinePhase.failed, failure = "not-found")),
            entry("waiting", OfflinePhase.waitingStorage),
            entry("paused", OfflinePhase.paused)
        )
    }

    /** A test that needs another list starts over with it: the engine reads the manifest once. */
    private fun install(vararg entries: OfflineEntry) {
        if (GlobalContext.getOrNull() != null) stopKoin()
        File(context.noBackupFilesDir, "offline").deleteRecursively()
        val manifest = OfflineManifest(entries = entries.toList())
        OfflineFiles(context.noBackupFilesDir).manifest(account.key).apply {
            parentFile!!.mkdirs()
            writeText(offlineEncodeManifest(manifest))
        }
        val accounts = AccountStore(
            object : TextPersistence {
                private var text: String? = null
                override suspend fun read() = text
                override suspend fun write(value: String) {
                    text = value
                }
            }
        )
        runBlocking { accounts.remember(account) }
        engine = OfflineEngine(
            context,
            accounts,
            { ClientIdentity("Test", "install-id", "1") },
            object : OfflineForeground {
                override fun update(activeTitles: List<String>) = Unit
            },
            http = OfflineHttp { _, _, _, _ -> throw java.io.IOException("no network in this test") },
            apiClient = okhttp3.OkHttpClient(),
            networkState = { OfflineNetwork.none },
            freeBytes = { 1L shl 40 },
            quotaBytes = { 1L shl 40 },
            retryDelayMs = { 1 },
            scope = scope
        )
        runBlocking { engine.start().join() }
        startKoin { modules(module { single { engine } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
        scope.cancel()
        File(context.noBackupFilesDir, "offline").deleteRecursively()
    }

    private fun tv() = compose.setContent { OfflineDownloadsTvScreen(account, true, { played = it.id }, {}, {}, {}) }

    private fun touch() = compose.setContent { OfflineDownloadsTouchScreen(account, true, { played = it.id }, {}, {}, {}) }

    private fun settle() {
        repeat(8) {
            compose.waitForIdle()
            Thread.sleep(25)
        }
    }

    @Test
    fun `TV delete asks first and starts on Cancel`() {
        tv()
        settle()
        compose.onNodeWithTag("downloads:delete:paused").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithTag("downloads:cancel").assertIsFocused()
        compose.onNodeWithText("Stop and delete", substring = true).assertExists()
        compose.onNodeWithTag("downloads:cancel").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithTag("downloads:row:paused").assertExists()
    }

    // The remote stays where the viewer was looking: a control that goes away used to drop focus on the top of the page.

    private fun ask(id: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("downloads:delete:$id"))
        compose.onNodeWithTag("downloads:delete:$id").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithTag("downloads:cancel").assertIsFocused()
    }

    private fun confirmDelete() {
        compose.onNodeWithTag("downloads:confirm").performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun `TV Cancel returns to the row's own Delete, on a failed row and a waiting one`() {
        tv()
        settle()
        listOf("failed", "waiting").forEach { id ->
            ask(id)
            compose.onNodeWithTag("downloads:cancel").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            compose.onNodeWithTag("downloads:delete:$id").assertIsFocused()
        }
    }

    @Test
    fun `TV Back from the question returns to the row's own Delete`() {
        tv()
        settle()
        ask("waiting")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onNodeWithTag("downloads:delete:waiting").assertIsFocused()
    }

    @Test
    fun `TV Cancel returns to a ready row's own Delete`() {
        install(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready))
        tv()
        settle()
        ask("b")
        compose.onNodeWithTag("downloads:cancel").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithTag("downloads:delete:b").assertIsFocused()
    }

    @Test
    fun `TV Delete moves to the row that takes its place, then the one above, then the top`() {
        install(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready), entry("c", OfflinePhase.ready))
        tv()
        settle()
        ask("b")
        confirmDelete()
        compose.onNodeWithTag("downloads:row:b").assertDoesNotExist()
        compose.onNodeWithTag("downloads:play:c").assertIsFocused()
        ask("c")
        confirmDelete()
        compose.onNodeWithTag("downloads:play:a").assertIsFocused()
        ask("a")
        confirmDelete()
        compose.onNodeWithTag("downloads:storage").assertIsFocused()
    }

    /** A window this short holds one row, so the next one isn't composed until the list scrolls to it. */
    @Test
    @Config(qualifiers = "w960dp-h200dp")
    fun `TV Delete finds the next row when it isn't on screen yet`() {
        install(*Array(12) { entry("r$it", OfflinePhase.ready) })
        tv()
        settle()
        ask("r5")
        confirmDelete()
        compose.onNodeWithTag("downloads:play:r6").assertIsFocused()
    }

    private fun conflictOn(id: String) {
        val file = OfflineFiles(context.noBackupFilesDir).progress(account.key)
        file.parentFile!!.mkdirs()
        val outbox = OfflineOutboxEntry(
            account.server.id,
            account.user.id,
            id,
            OfflineUserData(10, false, 0),
            phase = "conflict",
            conflict = OfflineConflict(20, false, 10, false, emptyList())
        )
        file.writeText(Json.encodeToString(OfflineProgressFile.serializer(), OfflineProgressFile(outbox = mapOf(id to outbox))))
        runBlocking { engine.progressStore(account).watched(listOf(id)) }
    }

    @Test
    fun `TV opens on the conflict notice, whole, and OK moves to the first row`() {
        install(*Array(4) { entry("r$it", OfflinePhase.ready) })
        conflictOn("r0")
        tv()
        settle()
        compose.onNodeWithTag("downloads:conflict-ok").assertIsFocused()
        val card = compose.onNodeWithTag("downloads:conflict-text").getBoundsInRoot()
        assertTrue("the sentence isn't under the top edge: ${card.top}", card.top >= 0.dp)
        // A real key press: from here on, focus only moves by itself because the control under it went away.
        compose.onNodeWithTag("downloads:conflict-ok").performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        compose.onNodeWithTag("downloads:conflict-ok").assertDoesNotExist()
        compose.onNodeWithTag("downloads:play:r0").assertIsFocused()
    }

    /** Back from the player: a viewing's sync is the usual way a notice appears, and the row would push it off the top. */
    @Test
    @Config(qualifiers = "w960dp-h200dp")
    fun `TV back from the player goes to a notice, whole, and OK returns to the row`() {
        install(*Array(4) { entry("r$it", OfflinePhase.ready) })
        val back = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        back.setContent { OfflineDownloadsTvScreen(account, true, { played = it.id }, {}, {}, {}) }
        settle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("downloads:play:r2"))
        compose.onNodeWithTag("downloads:play:r2").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertTrue("played", played != null)
        conflictOn("r2")
        back.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithTag("downloads:conflict-ok").assertIsFocused()
        val card = compose.onNodeWithTag("downloads:conflict-text").getBoundsInRoot()
        assertTrue("the sentence isn't under the top edge: ${card.top}", card.top >= 0.dp)
        compose.onNodeWithTag("downloads:conflict-ok").performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        compose.onNodeWithTag("downloads:play:r2").assertIsFocused()
    }

    /** Only one question is open, and the remote stays in it. Sending focus back to the first row's Delete made the two rows bounce. */
    @Test
    fun `TV asking about a second row moves the question and focus there, once`() {
        install(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready))
        tv()
        settle()
        ask("a")
        ask("b")
        settle()
        compose.onNodeWithTag("downloads:cancel").assertIsFocused()
        compose.onNodeWithTag("downloads:delete:a").assertIsNotFocused()
        compose.onNodeWithTag("downloads:cancel").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithTag("downloads:delete:b").assertIsFocused()
    }

    /** The first sync after reconnecting often finishes with the list already open and scrolled to its first row. */
    @Test
    @Config(qualifiers = "w960dp-h200dp")
    fun `TV a notice that arrives while the list is open and untouched shows whole and takes focus`() {
        install(*Array(4) { entry("r$it", OfflinePhase.ready) })
        tv()
        settle()
        compose.onNodeWithTag("downloads:play:r0").assertIsFocused()
        runBlocking {
            val store = engine.progressStore(account)
            store.remember("r0", OfflineUserData(positionTicks = 100))
            store.viewing("r0", 1, 500)
            store.sync(
                object : OfflineProgressRemote {
                    override suspend fun sameViewer(account: Account) = true
                    override suspend fun read(account: Account, itemId: String) = OfflineUserData(positionTicks = 900)
                    override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? = 204
                }
            )
        }
        settle()
        compose.onNodeWithTag("downloads:conflict-ok").assertIsFocused()
        val card = compose.onNodeWithTag("downloads:conflict-text").getBoundsInRoot()
        assertTrue("the sentence isn't under the top edge: ${card.top}", card.top >= 0.dp)
    }

    private fun network(transport: Int?) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (transport == null) {
            shadowOf(manager).setDefaultNetworkActive(false)
            return
        }
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
    }

    @Test
    fun `TV says there's no connection when there's none, the same as touch`() {
        network(null)
        tv()
        settle()
        compose.onNodeWithText("No connection. Everything saved below still plays.").assertExists()
        compose.onNodeWithText("Open the library").assertExists()
    }

    @Test
    fun `touch says there's no connection when there's none`() {
        network(null)
        touch()
        settle()
        compose.onNodeWithText("No connection. Everything saved below still plays.").assertExists()
        compose.onNodeWithText("Open the library").assertExists()
    }

    @Test
    fun `TV on a wired network with the server gone doesn't claim there's no connection`() {
        network(NetworkCapabilities.TRANSPORT_ETHERNET)
        tv()
        settle()
        compose.onNodeWithText("No connection. Everything saved below still plays.").assertDoesNotExist()
    }

    @Test
    fun `TV shows why a download failed, what waits and that it's offline`() {
        tv()
        settle()
        compose.onNodeWithTag("downloads:online").assertExists()
        compose.onNodeWithTag("downloads:retry:failed").performScrollTo()
        compose.onNodeWithTag("downloads:status:waiting").performScrollTo()
        compose.onNodeWithTag("downloads:resume:paused").performScrollTo()
        assertEquals(null, played)
    }

    @Test
    fun `touch delete asks first and confirming removes the row`() {
        touch()
        settle()
        compose.onNodeWithTag("downloads:delete:failed").performScrollTo().performClick()
        settle()
        compose.onNodeWithTag("downloads:confirm").performClick()
        compose.waitUntil(10_000) {
            engine.libraries.value[account.key]!!.manifest.entries.none { it.id == "failed" }
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h300dp-land-xhdpi")
    fun `touch landscape delete keeps the named question and both answers visible`() {
        touch()
        settle()
        compose.onNodeWithTag("downloads:delete:paused").performScrollTo().performClick()
        settle()
        compose.onNodeWithTag("downloads:question").assertIsDisplayed()
        compose.onNodeWithTag("downloads:question").assertTextContains("Title paused", substring = true)
        compose.onNodeWithTag("downloads:cancel").assertIsDisplayed()
        compose.onNodeWithTag("downloads:confirm").assertIsDisplayed()
    }

    @Test
    fun `shrinking a touch delete question to landscape reveals its whole question with the answers`() {
        // Keep rows below the question: at the end of a list, the scroll limit can accidentally keep the question
        // visible even when only the answer buttons are requested.
        install(
            entry("paused", OfflinePhase.paused),
            entry("below-one", OfflinePhase.paused),
            entry("below-two", OfflinePhase.paused),
            entry("below-three", OfflinePhase.paused),
            entry("below-four", OfflinePhase.paused)
        )
        var height by mutableStateOf(500.dp)
        compose.setContent {
            val configuration = android.content.res.Configuration(LocalConfiguration.current).apply {
                orientation = if (height > 150.dp) {
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                } else {
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Box(Modifier.requiredSize(700.dp, height).testTag("viewport")) {
                    OfflineDownloadsTouchScreen(account, true, { played = it.id }, {}, {}, {})
                }
            }
        }
        settle()
        compose.onNodeWithTag("downloads:delete:paused").performScrollTo().performClick()
        settle()
        // The viewer has scrolled the answers near the top before rotating. The answer buttons remain wholly in
        // the short window, while the question is partly above it. A buttons-only request would do nothing.
        val beforeViewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val beforeQuestion = compose.onNodeWithText("Stop and delete", substring = true).getUnclippedBoundsInRoot()
        val scrollPx = (beforeQuestion.bottom - beforeViewport.top - 4.dp).value * context.resources.displayMetrics.density
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollPx) }
        settle()
        val scrolledQuestion = compose.onNodeWithText("Stop and delete", substring = true).getUnclippedBoundsInRoot()
        assertTrue("prep must place the question above the viewport", scrolledQuestion.top < beforeViewport.top)
        compose.runOnIdle { height = 150.dp }
        settle()
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val question = compose.onNodeWithText("Stop and delete", substring = true).getUnclippedBoundsInRoot()
        val answer = compose.onNodeWithTag("downloads:confirm").getUnclippedBoundsInRoot()
        assertTrue("question above visible window: $question / $viewport", question.top >= viewport.top)
        assertTrue("answer below visible window: $answer / $viewport", answer.bottom <= viewport.bottom)
    }
}
