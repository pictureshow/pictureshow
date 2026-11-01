/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.ui.SubtitleView
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCaptioningManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = Application::class,
    qualifiers = "w960dp-h540dp-land-xhdpi",
    shadows = [PlayerCaptionListenerTest.RecordingCaptioningManager::class]
)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerCaptionListenerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overlaysAndAppearanceKeepOneListenerUntilTheSubtitlesLeaveComposition() {
        val overlayTop = mutableStateOf<Int?>(null)
        val appearance = mutableStateOf(SubtitleAppearance())
        val shown = mutableStateOf(true)
        compose.setContent { if (shown.value) PlayerSubtitles(null, appearance.value, overlayTop.value) }
        compose.waitForIdle()
        val manager = captioningManager()
        val recording = Shadow.extract<RecordingCaptioningManager>(manager)
        val listener = recording.listeners.single()
        val view = subtitleView(compose.activity.findViewById(android.R.id.content))!!
        val fullHeight = view.height
        assertTrue(fullHeight > 0)
        for (top in listOf(fullHeight * 3 / 4, null, fullHeight / 2, null, fullHeight / 3)) {
            compose.runOnIdle { overlayTop.value = top }
            compose.waitForIdle()
        }
        compose.runOnIdle { appearance.value = SubtitleAppearance(size = SubtitleSize.Large) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, recording.addCalls, "Overlay and appearance changes must not register settings observers again")
            assertEquals(0, recording.removeCalls)
            assertSame(listener, recording.listeners.single())
            assertTrue(view.height < fullHeight / 3)
            shown.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, recording.removeCalls, "Leaving composition must unregister the listener")
            assertTrue(recording.listeners.isEmpty())
        }
    }

    @Test
    fun captionCallbacksAfterOverlayAndAppearanceChangesUseTheCurrentPresentation() {
        val overlayTop = mutableStateOf<Int?>(null)
        val appearance = mutableStateOf(SubtitleAppearance())
        compose.setContent { PlayerSubtitles(null, appearance.value, overlayTop.value) }
        compose.waitForIdle()
        val manager = captioningManager()
        val recording = Shadow.extract<RecordingCaptioningManager>(manager)
        val view = subtitleView(compose.activity.findViewById(android.R.id.content))!!
        val fullHeight = view.height
        for (top in listOf(fullHeight / 2, fullHeight * 3 / 4, null)) {
            compose.runOnIdle { overlayTop.value = top }
            compose.waitForIdle()
            compose.runOnIdle {
                Settings.Secure.putInt(compose.activity.contentResolver, "accessibility_captioning_enabled", 1)
                Settings.Secure.putFloat(compose.activity.contentResolver, "accessibility_captioning_font_scale", 1.6f)
                val listener = recording.listeners.single()
                // Reset the view so each callback must apply the new setting itself, without recomposition.
                for (callback in listOf<() -> Unit>(
                    { listener.onEnabledChanged(true) },
                    { listener.onFontScaleChanged(1.6f) },
                    { listener.onUserStyleChanged(manager.userStyle) }
                )) {
                    view.setFractionalTextSize(0f)
                    callback()
                    assertTextFraction(view, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.6f * fullHeight / view.height)
                }
            }
        }
        compose.runOnIdle {
            overlayTop.value = fullHeight / 3
            appearance.value = SubtitleAppearance(size = SubtitleSize.Large, background = SubtitleBackground.Box)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            view.setFractionalTextSize(0f)
            recording.listeners.single().onUserStyleChanged(manager.userStyle)
            assertTextFraction(view, SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.25f * fullHeight / view.height)
        }
    }

    private fun captioningManager() = compose.activity.getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager

    private fun assertTextFraction(view: SubtitleView, expected: Float) {
        assertEquals(expected, ReflectionHelpers.getField<Float>(view, "defaultTextSize"), 0.00001f)
    }

    private fun subtitleView(view: View): SubtitleView? {
        if (view is SubtitleView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) subtitleView(view.getChildAt(index))?.let { return it }
        return null
    }

    /** Robolectric's caption shadow has no listener counters; record calls while retaining framework registration. */
    @Implements(CaptioningManager::class)
    class RecordingCaptioningManager : ShadowCaptioningManager() {
        @RealObject private lateinit var manager: CaptioningManager
        var addCalls = 0
        var removeCalls = 0
        val listeners = mutableListOf<CaptioningManager.CaptioningChangeListener>()

        @Implementation
        protected fun addCaptioningChangeListener(listener: CaptioningManager.CaptioningChangeListener) {
            addCalls++
            listeners += listener
            Shadow.directlyOn<Unit, CaptioningManager>(
                manager,
                CaptioningManager::class.java,
                "addCaptioningChangeListener",
                ClassParameter.from(CaptioningManager.CaptioningChangeListener::class.java, listener)
            )
        }

        @Implementation
        protected fun removeCaptioningChangeListener(listener: CaptioningManager.CaptioningChangeListener) {
            removeCalls++
            listeners -= listener
            Shadow.directlyOn<Unit, CaptioningManager>(
                manager,
                CaptioningManager::class.java,
                "removeCaptioningChangeListener",
                ClassParameter.from(CaptioningManager.CaptioningChangeListener::class.java, listener)
            )
        }
    }
}
