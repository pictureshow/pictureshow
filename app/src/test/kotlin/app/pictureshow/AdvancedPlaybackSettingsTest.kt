/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.ui.SubtitleView
import app.pictureshow.core.playback.PassthroughEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AdvancedPlaybackSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun livePreviewUsesSystemCaptionScaleUntilAnExplicitSizeIsChosen() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", 1)
        Settings.Secure.putFloat(context.contentResolver, "accessibility_captioning_font_scale", 1.6f)
        var appearance by mutableStateOf(SubtitleAppearance())
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            androidx.tv.material3.MaterialTheme { SubtitlePreview(appearance) }
        }
        fun subtitle(view: View): SubtitleView? = when (view) {
            is SubtitleView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { subtitle(view.getChildAt(it)) }
            else -> null
        }
        compose.runOnIdle {
            val preview = requireNotNull(subtitle(root))
            assertEquals(14f / 108f * 1.6f, ReflectionHelpers.getField<Float>(preview, "defaultTextSize"), 0.00001f)
            appearance = appearance.copy(size = SubtitleSize.Large)
        }
        compose.runOnIdle {
            val preview = requireNotNull(subtitle(root))
            assertEquals(14f / 108f * 1.25f, ReflectionHelpers.getField<Float>(preview, "defaultTextSize"), 0.00001f)
        }
    }

    @Test fun tvSubtitleControlsChangeActualAppearanceAndExposeConsequences() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                AdvancedPlaybackSettings("Subtitle appearance", prefs, false, { prefs = it(prefs) }, {})
            }
        }
        compose.onNodeWithText("Text color").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText("White").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals("White", prefs.subtitleStyle.foreground) }
        compose.onNodeWithText("Image subtitle opacity").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {
            check(it())
        }
        compose.onNode(
            hasText("80%") and hasTestTag("tv-settings:option:1")
        ).performSemanticsAction(SemanticsActions.OnClick) {
            check(it())
        }
        compose.runOnIdle { assertEquals(80, prefs.subtitleStyle.imageOpacity) }
        compose.onNodeWithText("Use my style for styled subtitles").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {
            check(it())
        }
        compose.runOnIdle { assertEquals(true, prefs.subtitleStyle.overrideEmbedded) }
    }

    @Test fun touchBurnChoiceShowsTruthfulAlwaysBurnConsequence() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Subtitle conversion", prefs, true, { prefs = it(prefs) }, {})
            }
        }
        compose.onNodeWithText("Burn in subtitles").performClick()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText(
            "The server draws every subtitle into the video. This converts the video, " +
                "and your subtitle style and timing settings won't apply."
        ).assertExists()
    }

    @Test fun touchAudioChoiceUsesViewerFormatsAndActuallyLimitsChannels() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Audio output", prefs, true, { prefs = it(prefs) }, {}, PassthroughEncoding.entries.toSet())
            }
        }
        compose.onNodeWithText("Audio channel limit").performClick()
        compose.onNodeWithText("5.1").performClick()
        compose.runOnIdle { assertEquals(6, prefs.tuning.maxAudioChannels) }
        compose.onNodeWithText("Dolby Digital").performScrollTo().assertExists()
        compose.onNodeWithText("Dolby Atmos (Dolby Digital Plus)").performScrollTo().assertExists()
    }

    @Test fun passthroughControlsReflectReceiverSupportAndDownmixWithoutLosingAllowedFormats() {
        var prefs by mutableStateOf(PlaybackPreferences(passthrough = true))
        var supported by mutableStateOf(setOf(PassthroughEncoding.AC3))
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Audio output", prefs, true, { prefs = it(prefs) }, {}, supported)
            }
        }
        compose.onNodeWithTag("settings:Dolby Digital").performScrollTo().assertIsEnabled().assertIsOn()
        compose.onNodeWithTag("settings:DTS").assertDoesNotExist()
        compose.onNodeWithTag("settings:Downmix to stereo").performScrollTo().performClick()
        compose.onNodeWithTag("settings:Dolby Digital").performScrollTo().assertIsNotEnabled().assertIsOn()
        compose.onNodeWithTag("settings:Downmix to stereo").performScrollTo().performClick()
        compose.onNodeWithTag("settings:Dolby Digital").performScrollTo().assertIsEnabled().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(setOf(PassthroughEncoding.AC3), prefs.tuning.disabledPassthrough) }
        compose.onNodeWithTag("settings:Audio passthrough").performScrollTo().performClick()
        compose.onNodeWithTag("settings:Dolby Digital").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.runOnIdle {
            supported = emptySet()
            prefs = prefs.copy(passthrough = true)
        }
        compose.onNodeWithTag("settings:Dolby Digital").assertDoesNotExist()
        compose.onNodeWithText("On when a compatible receiver is connected. None detected now.").performScrollTo().assertExists()
        compose.onNodeWithText("Format choices appear when a receiver is connected.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(setOf(PassthroughEncoding.AC3), prefs.tuning.disabledPassthrough) }
    }

    @Test fun shortAutoValueKeepsItsCeilingInTheActualNetworkPicker() {
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Network quality", PlaybackPreferences(), true, {}, {})
            }
        }
        compose.onNode(hasText("Home limit") and hasText("Auto")).assertExists()
        compose.onNodeWithText("Home limit").performClick()
        compose.onNodeWithText("Up to 200 Mbps").assertExists()
    }

    @Test fun videoOverrideExplainsFailureRiskBesideItsChoice() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Video output", prefs, true, { prefs = it(prefs) }, {})
            }
        }
        compose.onNodeWithText("HDR").performClick()
        compose.onNodeWithText("Always on").performClick()
        compose.runOnIdle { assertEquals(app.pictureshow.core.playback.CapabilityOverride.Force, prefs.tuning.hdr) }
        compose.onNodeWithText("Only if your device supports it but Automatic gets it wrong. Video may fail to play.").assertExists()
    }
}
