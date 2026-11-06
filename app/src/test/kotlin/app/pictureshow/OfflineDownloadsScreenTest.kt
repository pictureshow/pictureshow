/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.offlineEncodeManifest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        File(context.noBackupFilesDir, "offline").deleteRecursively()
        val manifest = OfflineManifest(
            entries = listOf(
                entry("failed", OfflinePhase.failed, OfflineJob(OfflinePhase.failed, failure = "not-found")),
                entry("waiting", OfflinePhase.waitingStorage),
                entry("paused", OfflinePhase.paused)
            )
        )
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
}
