/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAccessibilityManager

/**
 * Most tests only need "a reader is on" and were written with Compose's own accessibility dispatch off, which an
 * entry in the service list would turn on. Touch exploration alone keeps them as they were.
 */
internal fun ShadowAccessibilityManager.setScreenReader(on: Boolean) {
    setTouchExplorationEnabled(on)
    setEnabled(on)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ScreenReaderDetectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val manager get() = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val shadow get() = shadowOf(manager)
    private val generic = AccessibilityServiceInfo.FEEDBACK_GENERIC
    private val spoken = AccessibilityServiceInfo.FEEDBACK_SPOKEN
    private val braille = AccessibilityServiceInfo.FEEDBACK_BRAILLE
    private val haptic = AccessibilityServiceInfo.FEEDBACK_HAPTIC

    private fun services(vararg feedback: Int) = feedback.map { AccessibilityServiceInfo().apply { feedbackType = it } }

    private fun composed(): () -> Boolean {
        var seen: Boolean? = null
        compose.setContent { seen = rememberScreenReaderInUse() }
        compose.waitForIdle()
        return {
            compose.waitForIdle()
            seen!!
        }
    }

    private fun idleFor(millis: Long) {
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
    }

    @Test fun decisionCountsSpokenBrailleAndTouchExplorationOnly() {
        assertFalse(screenReaderInUse(true, false, emptyList()))
        assertFalse(screenReaderInUse(true, false, listOf(generic)))
        assertFalse(screenReaderInUse(true, false, listOf(generic, haptic, AccessibilityServiceInfo.FEEDBACK_VISUAL)))
        assertTrue(screenReaderInUse(true, false, listOf(spoken)))
        assertTrue(screenReaderInUse(true, false, listOf(braille)))
        assertTrue(screenReaderInUse(true, false, listOf(generic, spoken or haptic or AccessibilityServiceInfo.FEEDBACK_AUDIBLE)))
        assertTrue(screenReaderInUse(true, true, emptyList()))
    }

    @Test fun decisionIsFalseWhileAccessibilityIsOff() {
        assertFalse(screenReaderInUse(false, true, listOf(spoken)))
    }

    @Test fun aPasswordManagerAloneIsNotAReader() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        assertTrue(manager.isEnabled, "This is the state the old check called accessible")
        assertFalse(composed()())
    }

    @Test fun aSpokenServiceIsAReaderWithoutTouchExplorationAsOnTv() {
        shadow.setEnabledAccessibilityServiceList(services(generic, spoken))
        shadow.setEnabled(true)
        assertFalse(manager.isTouchExplorationEnabled)
        assertTrue(composed()())
    }

    @Test fun aBrailleServiceIsAReader() {
        shadow.setEnabledAccessibilityServiceList(services(braille))
        shadow.setEnabled(true)
        assertTrue(composed()())
    }

    @Test fun touchExplorationIsAReaderAndItsChangeIsLive() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        val inUse = composed()
        assertFalse(inUse())
        compose.runOnUiThread { shadow.setTouchExplorationEnabled(true) }
        assertTrue(inUse())
        compose.runOnUiThread { shadow.setTouchExplorationEnabled(false) }
        assertFalse(inUse())
    }

    @Test fun turningTheOnlyReaderOffIsLive() {
        shadow.setEnabledAccessibilityServiceList(services(spoken))
        shadow.setEnabled(true)
        val inUse = composed()
        assertTrue(inUse())
        // The shadow keeps its list: the enabled flag alone must end the answer, as it does when the last service stops.
        compose.runOnUiThread { shadow.setEnabled(false) }
        assertFalse(inUse())
    }

    /** Android 11 TV: isEnabled turns true while TalkBack is still binding, and nothing else fires once it has bound. */
    @Test fun belowApi33AReaderStillBindingWhenEnabledTurnsTrueIsPickedUp() {
        val inUse = composed()
        assertFalse(inUse())
        compose.runOnUiThread { shadow.setEnabled(true) }
        assertFalse(inUse())
        compose.runOnUiThread { shadow.setEnabledAccessibilityServiceList(services(spoken)) }
        assertFalse(inUse(), "Nothing has signalled the list yet")
        idleFor(600)
        assertTrue(inUse())
    }

    /** isEnabled stays true when TalkBack joins a password manager, so only the setting changes. */
    @Test fun belowApi33AReaderJoiningAnotherServiceIsPickedUpFromTheSetting() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        val inUse = composed()
        assertFalse(inUse())
        val setting = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        compose.runOnUiThread {
            shadow.setEnabledAccessibilityServiceList(services(generic, spoken))
            compose.activity.contentResolver.notifyChange(setting, null)
        }
        idleFor(0)
        assertTrue(inUse(), "Read as soon as the setting changes")
        compose.runOnUiThread {
            shadow.setEnabledAccessibilityServiceList(services(generic))
            compose.activity.contentResolver.notifyChange(setting, null)
        }
        idleFor(0)
        assertFalse(inUse())
    }

    /** A slow bind: the setting changes well before the service is in the list. */
    @Test fun belowApi33TheLastSettleReadCatchesASlowBind() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        val inUse = composed()
        compose.runOnUiThread {
            compose.activity.contentResolver.notifyChange(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), null)
        }
        idleFor(2_100)
        assertFalse(inUse())
        compose.runOnUiThread { shadow.setEnabledAccessibilityServiceList(services(generic, spoken)) }
        idleFor(3_000)
        assertTrue(inUse())
    }

    @Test fun returningFromSettingsReadsAgain() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        val inUse = composed()
        assertFalse(inUse())
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.runOnUiThread { shadow.setEnabledAccessibilityServiceList(services(spoken)) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue(inUse())
    }

    @Test
    @Config(sdk = [34])
    fun fromApi33TheServicesListenerAloneCarriesTheChange() {
        shadow.setEnabledAccessibilityServiceList(services(generic))
        shadow.setEnabled(true)
        val inUse = composed()
        assertFalse(inUse())
        compose.runOnUiThread { shadow.setEnabledAccessibilityServiceList(services(generic, spoken)) }
        assertTrue(inUse())
        compose.runOnUiThread { shadow.setEnabledAccessibilityServiceList(services(generic)) }
        assertEquals(false, inUse())
    }
}
