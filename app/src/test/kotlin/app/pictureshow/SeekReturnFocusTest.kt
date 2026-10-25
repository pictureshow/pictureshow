/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.InterruptedIOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SeekReturnFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private var exits = 0
    private lateinit var host: android.view.View

    @After fun cleanup() {
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun visibleControlsDoNotOfferTheSurfaceAsAFocusRecoveryTargetAfterSeekDone() {
        launch(true)
        val surface = compose.onNodeWithTag("player:surface")
        surface.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        surface.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        timeline.assert(hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true)))
        val surfaceId = surface.fetchSemanticsNode().id
        val timelineId = timeline.fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            val background = provider.createAccessibilityNodeInfo(surfaceId)
            assertTrue(background == null || !background.isVisibleToUser)
            assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(timelineId)).isVisibleToUser)
        }
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText("Done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        timeline.assertIsFocused()
        surface.assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun aFocusedSkipActionReturnsToTheTimelineWhenItDisappearsWithControlsVisible() = accessibleSkipReturn(false)

    @Test fun skipReturnUsesAccessibilityEnabledAfterThePlayerOpened() = accessibleSkipReturn(true)

    private fun accessibleSkipReturn(enableAfterLaunch: Boolean) {
        launch(!enableAfterLaunch)
        if (enableAfterLaunch) {
            compose.runOnIdle {
                val manager = host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                assertTrue(!manager.isEnabled, "Accessibility must start disabled for this transition")
                shadowOf(manager).setEnabled(true)
                assertTrue(manager.isEnabled)
            }
            compose.waitForIdle()
        }
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        timeline.assertIsFocused()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        val skip = compose.onNodeWithTag("skip:Intro")
        val titleId = compose.onNodeWithText("An episode").fetchSemanticsNode().id
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        skip.assertIsFocused()
        compose.mainClock.autoAdvance = false
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.waitForIdle()
        // Deliver SideEffect snapshot writes between frames before querying the native tree.
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue(compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isEmpty())
        skip.assertDoesNotExist()
        compose.runOnIdle {
            val title = host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(titleId)
            assertTrue(title == null || !title.isVisibleToUser, "Only the position is offered during accessible action recovery")
        }
        compose.onNodeWithContentDescription("Playback position").assertIsFocused()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("player:surface").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun ordinarySkipRemovalKeepsItsSurfaceInputTargetWhenTheTimelineIsNotFocusable() {
        launch(false)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        val skip = compose.onNodeWithTag("skip:Intro")
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        skip.assertDoesNotExist()
        compose.onNodeWithTag("player:surface").assertIsFocused()
        compose.onNodeWithContentDescription("Playback position").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    private fun launch(accessible: Boolean) {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        val item = MediaItem("episode", "An episode", "Episode", seriesId = "series")
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val json = when {
                        request.url.encodedPath.endsWith(
                            "/PlaybackInfo"
                        ) ->
                            """{"PlaySessionId":"viewing","MediaSources":[{"Id":"source","RunTimeTicks":600000000,
                                "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                                "TranscodingUrl":"/Videos/episode/master.m3u8","MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"}]}]}"""
                        request.url.encodedPath.endsWith(
                            "/Episodes"
                        ) ->
                            """{"Items":[{"Id":"episode","Type":"Episode"},
                                {"Id":"next","Name":"Next story","Type":"Episode"}]}"""
                        request.url.encodedPath == "/Items/episode" ->
                            """{"Id":"episode","Name":"An episode",
                                "Type":"Episode","SeriesId":"series"}"""
                        request.url.encodedPath.startsWith(
                            "/MediaSegments"
                        ) -> """{"Items":[{"Id":"intro","Type":"Intro","StartTicks":0,"EndTicks":200000000}]}"""
                        else -> return@addHandler respond("", HttpStatusCode.NoContent)
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                }
            )
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(accessible)
        compose.setContent {
            host = androidx.compose.ui.platform.LocalView.current
            PictureshowTheme { PlayerItemScreen(account, item, 0, catalog, {}, { exits++ }, providedOutput = output) }
        }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            output.player.value != null
        }
        compose.waitForIdle()
    }

    /** The decoder is deliberately held; renderer timing is supplied at the real output callback boundary. */
    private class WaitingSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            manifest = if (dataSpec.uri.path?.endsWith(".m3u8") == true) {
                (
                    "#EXTM3U\n#EXT-X-TARGETDURATION:61\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:60.008,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    ).byteInputStream()
            } else {
                null
            }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            manifest?.let { return it.read(buffer, offset, length) }
            return try {
                Thread.sleep(Long.MAX_VALUE)
                C.RESULT_END_OF_INPUT
            } catch (_: InterruptedException) {
                throw InterruptedIOException()
            }
        }
        override fun close() = Unit
    }
}
