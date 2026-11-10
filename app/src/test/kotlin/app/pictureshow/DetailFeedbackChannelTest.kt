/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@Suppress("DEPRECATION")
class DetailFeedbackChannelTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun aDepartedPageCannotSpeakOnTheNextPagesMatchingAction() {
        val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setScreenReader(true)
        val host = View(compose.activity)
        fun node() = AccessibilityNodeInfo.obtain().apply {
            setSource(host, 1)
            viewIdResourceName = "detail:play"
            className = "android.widget.Button"
            isFocused = true
            isAccessibilityFocused = true
            isVisibleToUser = true
            isEnabled = true
        }
        val native = object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int) = node()
            override fun findFocus(focus: Int) = node()
        }
        ViewCompat.setAccessibilityDelegate(
            host,
            object : AccessibilityDelegateCompat() {
                override fun getAccessibilityNodeProvider(host: View) = AccessibilityNodeProviderCompat(native)
            }
        )
        val sent = mutableListOf<String>()
        val receiver = object : FrameLayout(compose.activity) {
            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                sent += event.text.joinToString()
                event.recycle()
                return true
            }
        }
        receiver.addView(host)
        val first = mutableStateOf(true)
        val second = mutableStateOf(false)
        lateinit var oldChannel: DetailFeedbackChannel
        lateinit var newChannel: DetailFeedbackChannel
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                if (first.value) oldChannel = rememberDetailFeedbackChannel(false) {}
                if (second.value) newChannel = rememberDetailFeedbackChannel(false) {}
            }
        }
        compose.runOnIdle { second.value = true }
        compose.runOnIdle {
            host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(1)!!.recycle()
            assertFalse(oldChannel.send("primary", "Ready"))
            assertTrue(newChannel.send("primary", "Ready"))
            assertEquals(listOf("Ready"), sent)
        }
        compose.runOnIdle { first.value = false }
        compose.runOnIdle {
            assertFalse(oldChannel.send("primary", "Ready"))
            assertEquals(listOf("Ready"), sent)
        }
        compose.runOnIdle { second.value = false }
        compose.runOnIdle {
            assertFalse(newChannel.send("primary", "Ready"))
            assertTrue(host.accessibilityNodeProvider === native)
            assertEquals(listOf("Ready"), sent)
        }
    }
}
