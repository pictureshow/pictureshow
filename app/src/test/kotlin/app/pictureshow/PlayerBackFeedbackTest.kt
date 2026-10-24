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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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
class PlayerBackFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private var exits = 0

    @After fun cleanup() {
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun accessibleFirstBackNamesTheHiddenControlsAndSecondBackStillExits() {
        launch(true)
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithContentDescription("Controls hidden")
            .assertIsFocused()
        compose.runOnIdle {
            assertEquals(0, exits)
            assertTrue(output.player.value!!.playWhenReady)
        }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            exits == 1
        }
    }

    @Test fun ordinaryFirstBackKeepsItsExistingSilentHideAndPlaybackIntent() {
        launch(false)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithContentDescription("Video player")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle {
            assertEquals(0, exits)
            assertTrue(output.player.value!!.playWhenReady)
        }
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
                        request.url.encodedPath.startsWith("/MediaSegments") -> """{"Items":[]}"""
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
        compose.setContent { PictureshowTheme { PlayerItemScreen(account, item, 0, catalog, {}, { exits++ }, providedOutput = output) } }
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
