package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.UserConfiguration
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun remoteOpensMovesChangesEveryOptionAndBackReturns() {
        var state by mutableStateOf(
            PlaybackSettingsState(
                configuration = UserConfiguration(
                    Json.parseToJsonElement(
                        """{"AudioLanguagePreference":"eng","SubtitleLanguagePreference":"eng","SubtitleMode":"Always","RememberAudioSelections":true}"""
                    ).jsonObject
                ),
                loading = false
            )
        )
        var back = false
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    state,
                    "AC-3, E-AC-3",
                    onLocal = { state = state.copy(local = it(state.local)) },
                    onServer = { name, value -> state = state.copy(configuration = state.configuration!!.withPreference(name, value)) },
                    onRetry = {},
                    onBack = { back = true }
                )
            }
        }
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        fun center() {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
        }
        fun down(tag: String) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        center()
        assertEquals(120, state.local.bitrateMbps)
        down("settings:passthrough")
        center()
        assertEquals(false, state.local.passthrough)
        down("settings:size")
        center()
        assertEquals(SubtitleSize.Small, state.local.subtitleSize)
        down("settings:background")
        center()
        assertEquals(SubtitleBackground.None, state.local.subtitleBackground)
        app.pictureshow.core.SegmentType.entries.filter { it != app.pictureshow.core.SegmentType.Unknown }.forEach { type ->
            down("settings:segment:$type")
            center()
            assertEquals(app.pictureshow.core.SegmentMode.Automatic, state.local.segmentModes[type])
        }
        down("settings:audio-language")
        center()
        assertEquals("spa", state.configuration!!.audioLanguage)
        down("settings:subtitle-language")
        center()
        assertEquals("spa", state.configuration!!.subtitleLanguage)
        down("settings:subtitle-mode")
        center()
        assertEquals("OnlyForced", state.configuration!!.subtitleMode)
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(back) }
    }

    @Test
    fun localPreferencesSurviveReopenAndEveryBitrateMapsToExplicitProtocolLimit() = runTest {
        val directory = Files.createTempDirectory("playback-preferences").toFile()
        var scope = CoroutineScope(coroutineContext + SupervisorJob())
        var preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { directory.resolve("settings.preferences_pb") })
        try {
            val store = PlaybackPreferencesStore(preferences)
            assertEquals(200_000_000, store.read().deviceSettings().maxStreamingBitrate)
            for (value in PlaybackPreferences.BITRATES) {
                store.update { it.copy(bitrateMbps = value) }
                assertEquals(
                    value?.times(1_000_000) ?: 200_000_000,
                    PlaybackPreferencesStore(preferences).read().deviceSettings().maxStreamingBitrate
                )
            }
            store.update {
                it.copy(
                    passthrough = false,
                    subtitleSize = SubtitleSize.ExtraLarge,
                    subtitleBackground = SubtitleBackground.Box,
                    segmentModes = mapOf(app.pictureshow.core.SegmentType.Intro to app.pictureshow.core.SegmentMode.Off)
                )
            }
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            scope = CoroutineScope(coroutineContext + SupervisorJob())
            preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { directory.resolve("settings.preferences_pb") })
            val restored = PlaybackPreferencesStore(preferences).read()
            assertEquals(app.pictureshow.core.SegmentMode.Off, restored.segmentModes[app.pictureshow.core.SegmentType.Intro])
            assertEquals(app.pictureshow.core.SegmentMode.Ask, restored.segmentModes[app.pictureshow.core.SegmentType.Outro])
            assertEquals(false, restored.deviceSettings().passthroughEnabled)
            assertEquals(SubtitleAppearance(SubtitleSize.ExtraLarge, SubtitleBackground.Box), restored.appearance)
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
}
