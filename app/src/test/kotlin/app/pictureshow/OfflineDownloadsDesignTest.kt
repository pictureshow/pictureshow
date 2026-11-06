/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflinePresetChoice
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.offlineEncodeManifest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** What the design review asked of the Downloads screens, each pinned where the viewer would see it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineDownloadsDesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val account =
        Account(Server("server", "Fixture server", "https://media.example.test", "12.0.0"), User("viewer-a", "Viewer A"), "fixture-token")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: OfflineEngine
    private var played: String? = null
    private var storageOpened = 0

    private fun entry(id: String, phase: OfflinePhase, failure: String? = null, tracks: List<OfflineTrack> = emptyList()) = OfflineEntry(
        id = id,
        itemId = id,
        kind = OfflineMediaKind.movie,
        preset = OfflinePreset.video720,
        labels = OfflineLabels("Title $id"),
        job = OfflineJob(phase, failure = failure),
        tracks = tracks
    )

    private fun start(manifest: OfflineManifest? = null, corrupt: Boolean = false) {
        OfflineFiles(context.noBackupFilesDir).manifest(account.key).apply {
            parentFile!!.mkdirs()
            if (corrupt) writeText("not a manifest") else manifest?.let { writeText(offlineEncodeManifest(it)) }
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
            http = OfflineHttp { _, _, _ -> throw java.io.IOException("no network in this test") },
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

    private fun start(vararg entries: OfflineEntry) = start(OfflineManifest(entries = entries.toList()))

    @Before
    fun clear() {
        File(context.noBackupFilesDir, "offline").deleteRecursively()
    }

    @After
    fun tearDown() {
        stopKoin()
        scope.cancel()
        File(context.noBackupFilesDir, "offline").deleteRecursively()
    }

    private fun tv() = compose.setContent {
        OfflineDownloadsTvScreen(account, true, { played = it.id }, { storageOpened++ }, {}, {})
    }

    private fun touch() = compose.setContent {
        OfflineDownloadsTouchScreen(account, true, { played = it.id }, { storageOpened++ }, {}, {})
    }

    private fun settle() {
        repeat(8) {
            compose.waitForIdle()
            Thread.sleep(25)
        }
    }

    private fun tag(tag: String) = compose.onNodeWithTag(tag)

    private fun bounds(tag: String): Rect = rect(tag(tag))

    private fun rect(node: SemanticsNodeInteraction): Rect = node.getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun SemanticsNodeInteraction.textColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first().layoutInput.style.color
    }

    // Blockers 1 and 2: the form has a field the theme doesn't blank out, and it's there whatever cleared the flag.

    @Test
    fun `TV password form has a focused readable field even when only the job remembers`() {
        start(entry("a", OfflinePhase.failed, "needs-password"), entry("b", OfflinePhase.paused))
        assertEquals(false, engine.libraries.value[account.key]!!.needsPassword)
        tv()
        settle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Password"))
        field.assertIsFocused()
        tag("downloads:password-continue").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for your password").assertExists()
        tag("downloads:retry:a").assertDoesNotExist()
        tag("downloads:delete:a").assertExists()
        // The field is read-only until the remote's centre button, so the keyboard doesn't take the D-pad on focus.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        field.performTextReplacement("secret")
        settle()
        val typed = compose.onNode(hasSetTextAction() and hasContentDescription("Password"), useUnmergedTree = true)
        assertEquals(Color.White, typed.textColor())
    }

    @Test
    fun `touch password form shows from the job too, with no Retry beside it`() {
        start(entry("a", OfflinePhase.failed, "needs-password"))
        touch()
        settle()
        compose.onNode(hasSetTextAction()).assertExists()
        tag("downloads:retry:a").assertDoesNotExist()
        compose.onNodeWithText("Waiting for your password").assertExists()
    }

    // Blocker 4 on screen: the summary, not the raw labels.

    @Test
    fun `TV summarises what wasn't downloaded`() {
        val tracks = listOf(OfflineTrack(2, "audio", "Spanish - Dolby Digital - Mono", "spa", available = false))
        start(entry("a", OfflinePhase.ready, tracks = tracks))
        tv()
        settle()
        compose.onNodeWithText("Not downloaded: Spanish audio").assertExists()
        compose.onNodeWithText("Dolby Digital", substring = true).assertDoesNotExist()
    }

    // Follow-up 1: first focus.

    @Test
    fun `TV OK from a ready list plays`() {
        start(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready))
        tv()
        settle()
        tag("downloads:play:a").assertIsFocused()
        tag("downloads:play:a").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals("a", played)
    }

    @Test
    fun `TV empty list lands on Storage`() {
        start()
        tv()
        settle()
        tag("downloads:storage").assertIsFocused()
    }

    @Test
    fun `TV list changing after the viewer moved doesn't take the remote back`() {
        start(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready))
        tv()
        settle()
        tag("downloads:play:a").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        settle()
        val header = (hasTestTag("downloads:storage") or hasTestTag("downloads:online")) and isFocused()
        compose.onNode(header).assertExists()
        runBlocking { engine.delete(account.key, "a") }
        settle()
        tag("downloads:play:b").assertIsNotFocused()
        compose.onNode(header).assertExists()
    }

    @Test
    fun `TV Cancel returns to Delete and a confirmed delete lands on what's left`() {
        start(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.ready))
        tv()
        settle()
        tag("downloads:delete:a").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        tag("downloads:cancel").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        tag("downloads:delete:a").assertIsFocused()
        tag("downloads:delete:a").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val confirmLabel = compose.onNode(hasText("Delete") and hasAnyAncestor(hasTestTag("downloads:confirm")), useUnmergedTree = true)
        assertEquals(OfflineErrorTone, confirmLabel.textColor())
        tag("downloads:confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { engine.libraries.value[account.key]!!.manifest.entries.none { it.id == "a" } }
        settle()
        tag("downloads:play:b").assertIsFocused()
    }

    // Follow-ups 4 to 8.

    @Test
    fun `TV hides usage when the list can't be read`() {
        start(corrupt = true)
        tv()
        settle()
        compose.onNodeWithText(OFFLINE_RECOVERABLE_TEXT).assertExists()
        compose.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun `touch hides usage when the list can't be read`() {
        start(corrupt = true)
        touch()
        settle()
        compose.onNodeWithText(OFFLINE_RECOVERABLE_TEXT).assertExists()
        compose.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    private val attentionFirst = arrayOf(
        entry("c", OfflinePhase.ready),
        entry("b", OfflinePhase.paused),
        entry("a", OfflinePhase.failed, "not-found")
    )

    private fun assertAttentionFirst() {
        val attention = rect(compose.onNodeWithText("Needs attention"))
        val progress = rect(compose.onNodeWithText("In progress"))
        assertTrue("$attention above $progress", attention.bottom <= progress.top)
    }

    @Test
    fun `TV puts what needs attention above what's in progress`() {
        start(*attentionFirst)
        tv()
        settle()
        assertAttentionFirst()
        assertEquals(OfflineErrorTone, compose.onNodeWithTag("downloads:status:a", useUnmergedTree = true).textColor())
    }

    @Test
    fun `touch puts what needs attention above what's in progress`() {
        start(*attentionFirst)
        touch()
        settle()
        assertAttentionFirst()
    }

    private fun meteredRow(phase: OfflinePhase): OfflineRow {
        val library = OfflineLibrary(OfflineManifest(entries = listOf(entry("a", phase))))
        return offlineSections(library, OfflineNetwork.metered, tv = false).active.single()
    }

    private fun rowContent(phase: OfflinePhase, tv: Boolean) {
        val row = meteredRow(phase)
        compose.setContent {
            val controller = OfflineDownloadsController(account, engine, rememberCoroutineScope(), {})
            if (tv) {
                OfflineRowTv(account, row, controller, onStorage = { storageOpened++ })
            } else {
                OfflineRowTouch(account, row, controller, onStorage = { storageOpened++ })
            }
        }
    }

    @Test
    fun `TV row waiting for storage goes straight to the storage screen`() {
        start()
        rowContent(OfflinePhase.waitingStorage, tv = true)
        settle()
        tag("downloads:open-storage:a").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, storageOpened)
    }

    @Test
    fun `touch row waiting for storage goes straight to the storage screen`() {
        start()
        rowContent(OfflinePhase.waitingStorage, tv = false)
        settle()
        tag("downloads:open-storage:a").performClick()
        assertEquals(1, storageOpened)
    }

    // Blocker 7: the row keeps its shape on a phone.

    @Test
    @Config(qualifiers = "w360dp-h780dp-port-xhdpi")
    fun `phone row waiting for Wi-Fi puts the override alone with Pause and Delete together below`() {
        start()
        rowContent(OfflinePhase.waitingNetwork, tv = false)
        settle()
        val override = bounds("downloads:allowcellular:a")
        val pause = bounds("downloads:pause:a")
        val delete = bounds("downloads:delete:a")
        assertTrue("override above", override.bottom <= pause.top)
        assertEquals("same line", pause.top, delete.top, 1f)
        assertTrue("delete fits", delete.right <= 360f)
    }

    @Test
    fun `TV row keeps moving while it can't show a total`() {
        start()
        compose.mainClock.autoAdvance = false
        rowContent(OfflinePhase.preparing, tv = true)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        tag("downloads:working:a").assertExists()
    }

    @Test
    fun `touch row keeps moving while it can't show a total`() {
        start()
        compose.mainClock.autoAdvance = false
        rowContent(OfflinePhase.preparing, tv = false)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        tag("downloads:working:a").assertExists()
    }

    // Blocker 6: the OK sits beside the sentence it answers.

    @Test
    fun `TV conflict notice keeps OK next to its sentence`() {
        val words = offlineConflictText("Title")
        compose.setContent { OfflineConflictTv(words) {} }
        val text = bounds("downloads:conflict-text")
        val ok = bounds("downloads:conflict-ok")
        assertTrue("OK is ${ok.left - text.right} from the text", ok.left - text.right < 40f)
        assertTrue("same line", ok.top < text.bottom && text.top < ok.bottom)
    }

    // Follow-up 3: Storage.

    @Test
    fun `TV Storage selects and focuses the current limit even off the steps, and deleting everything is apart`() {
        val limit = 1_288_490_189L
        start(OfflineManifest(budget = OfflineBudget(capBytes = limit), entries = listOf(entry("a", OfflinePhase.paused))))
        compose.setContent { OfflineStorageTvScreen(account) {} }
        settle()
        tag("storage:cap:$limit").assertIsFocused()
        compose.onNodeWithText("✓", substring = true).assertExists()
        compose.onNodeWithText("All downloads").performScrollTo()
        compose.onNodeWithText("Delete all downloads").assertExists()
    }

    @Test
    fun `touch Storage offers the current limit even off the steps`() {
        val limit = 1_288_490_189L
        start(OfflineManifest(budget = OfflineBudget(capBytes = limit), entries = listOf(entry("a", OfflinePhase.paused))))
        compose.setContent { OfflineStorageTouchScreen(account) {} }
        settle()
        tag("storage:cap:$limit").performScrollTo()
        compose.onNodeWithText("All downloads").performScrollTo()
    }

    // Follow-up 9: the preview's first focus and words.

    private fun preview(available: Boolean): OfflinePreviewController {
        val item = OfflineApiItem("m", "Movie", "Movie", sources = listOf(OfflineApiSource(size = 1000)))
        return OfflinePreviewController(account, engine, { error("not loaded in this test") }, scope).apply {
            state = OfflinePreviewState.Ready(
                OfflinePreview(
                    "Movie",
                    listOf(item),
                    listOf(OfflinePresetChoice(OfflinePreset.original, available, estimateBytes = 1000, exactSize = true)),
                    OfflinePolicy(enableDownloads = true, enablePlayback = true, videoTranscoding = true, audioTranscoding = true)
                )
            )
            episodes = setOf("m")
            preset = OfflinePreset.original
        }
    }

    @Test
    fun `TV download preview starts on Download and speaks of its own connections`() {
        start()
        val controller = preview(available = true)
        compose.setContent { OfflineDownloadTvContent(controller, "Movie", {}, {}) }
        settle()
        tag("download:start").assertIsFocused()
        compose.onNodeWithText("Wi-Fi or Ethernet only: On").assertExists()
        compose.onNodeWithText("cellular", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("mobile", substring = true, ignoreCase = true).assertDoesNotExist()
        val start = bounds("download:start")
        val size = bounds("download:size")
        assertTrue("size beside Download", size.left >= start.right && size.top < start.bottom)
    }

    @Test
    fun `TV download preview with nothing to start still has focus`() {
        start()
        val controller = preview(available = false)
        compose.setContent { OfflineDownloadTvContent(controller, "Movie", {}, {}) }
        settle()
        tag("download:cellular").assertIsFocused()
    }

    // Follow-up 11: confirmation at the foot of a long list isn't below the fold.

    @Test
    @Config(qualifiers = "w360dp-h480dp-port-xhdpi")
    fun `touch delete confirmation comes into view`() {
        start(*Array(6) { entry("e$it", OfflinePhase.ready) })
        touch()
        settle()
        tag("downloads:delete:e5").performScrollTo().performClick()
        settle()
        tag("downloads:confirm").assertIsDisplayed()
    }
}
