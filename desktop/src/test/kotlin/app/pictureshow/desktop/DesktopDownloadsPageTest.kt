/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package app.pictureshow.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf

class DesktopDownloadsPageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = ClientIdentity("Test", "install-id", "1")
    private val viewer = app.pictureshow.core.Account(app.pictureshow.core.Server("server", "Demo", "http://127.0.0.1:1", "12.4.0"), app.pictureshow.core.User("viewer", "Viewer"), "token")
    private val owner = DesktopOffline(
        DesktopOfflineEngine(kotlin.io.path.createTempDirectory("downloads-page").toFile(), AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        }), identity, scope = scope),
        DesktopOfflineApi(identity), scope, flowOf(viewer), { viewer }
    )

    private fun entry(id: String, title: String, phase: OfflinePhase, failure: String? = null) = OfflineEntry(
        id, "item-$id", OfflineMediaKind.movie, preset = OfflinePreset.original, labels = OfflineLabels(title, year = 2024),
        resources = listOf(OfflineResource("media", OfflineResourceRole.media, "media.mkv", bytes = 1_000_000, complete = phase == OfflinePhase.ready)),
        job = OfflineJob(phase, failure = failure)
    )

    private val state = DesktopOfflineState(
        accountKey = viewer.key,
        library = OfflineLibrary(OfflineManifest(
            entries = listOf(entry("ready", "A Film Saved For The Plane", OfflinePhase.ready), entry("failed", "One That Stopped", OfflinePhase.failed, "http")),
            budget = OfflineBudget(capBytes = 20L * 1024 * 1024 * 1024, usedBytes = 1_000_000)
        ))
    )

    @Test fun downloadsAreGroupedByWhatNeedsTheViewerAndFitTheSmallestWindow() = runDesktopComposeUiTest(800, 600) {
        val played = mutableListOf<OfflineEntry>()
        try {
            setContent { MaterialTheme { DownloadsPage(state, owner, play = { played += it }, retryServer = {}) } }
            onNodeWithTag("downloads:section:Needs attention").assertExists()
            onNodeWithTag("downloads:section:Ready to play").assertExists()
            val attention = onNodeWithTag("downloads:section:Needs attention").fetchSemanticsNode().boundsInRoot
            val ready = onNodeWithTag("downloads:section:Ready to play").fetchSemanticsNode().boundsInRoot
            assertTrue(attention.top < ready.top, "what needs the viewer comes first")
            onNodeWithTag("downloads:retry:failed").assertExists()
            val play = onNodeWithTag("downloads:play:ready").fetchSemanticsNode().boundsInRoot
            assertTrue(play.right <= 800f, "Play stays inside an 800-wide window: $play")
            onNodeWithTag("downloads:play:ready").performClick()
            waitForIdle()
            assertEquals(listOf("ready"), played.map { it.id })
            onNodeWithTag("downloads:cap").assertExists()
        } finally { scope.cancel() }
    }

    @Test fun anUnreachableServerSaysDownloadsStillPlayAndOffersToTryAgain() = runDesktopComposeUiTest(1280, 800) {
        var retried = 0
        try {
            setContent { MaterialTheme { DownloadsPage(state.copy(serverUnreachable = true), owner, play = {}, retryServer = { retried++ }) } }
            onNodeWithText(OFFLINE_UNREACHABLE_TEXT).assertExists()
            onNodeWithTag("downloads:retry-server").performClick()
            waitForIdle()
            assertEquals(1, retried)
        } finally { scope.cancel() }
    }

    @Test fun anEmptyListSaysHowToStart() = runDesktopComposeUiTest(1280, 800) {
        try {
            setContent { MaterialTheme { DownloadsPage(DesktopOfflineState(), owner, play = {}, retryServer = {}) } }
            onNodeWithTag("downloads:empty").assertExists()
            onNodeWithTag("downloads:delete-all").assertDoesNotExist()
        } finally { scope.cancel() }
    }
}
