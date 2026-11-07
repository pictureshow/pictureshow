/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@Suppress("DEPRECATION")
class DetailFeedbackInteropTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun interleavedPlayerAndDetailLifetimesReuseOnePresentationBridge() {
        val host = View(compose.activity)
        var creates = 0
        var focusQueries = 0
        fun node(id: Int) = AccessibilityNodeInfo.obtain().apply {
            setSource(host, id)
            viewIdResourceName = when (id) {
                2 -> "detail:play-pending-label"
                99 -> "player:play-pause"
                else -> "detail:play-pending"
            }
            if (id == 2) text = "Play when ready"
            isFocused = id == 1
            isAccessibilityFocused = id == 1
            isVisibleToUser = true
            isEnabled = true
        }
        val native = object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo {
                creates++
                return node(id)
            }
            override fun findFocus(focus: Int): AccessibilityNodeInfo {
                focusQueries++
                return node(1)
            }
            override fun performAction(id: Int, action: Int, args: android.os.Bundle?) = true
        }
        val original = object : AccessibilityDelegateCompat() {
            override fun getAccessibilityNodeProvider(host: View) = AccessibilityNodeProviderCompat(native)
        }
        ViewCompat.setAccessibilityDelegate(host, original)
        val first = mutableStateOf(false)
        val player = mutableStateOf(false)
        val returning = mutableStateOf(false)
        var stable: AccessibilityDelegateCompat? = null
        var oldCallbacks = 0
        var currentCallbacks = 0
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                if (first.value) rememberDetailFeedbackChannel(true) { oldCallbacks++ }
                if (player.value) CurrentPositionAccessibility(setOf("player:play-pause")) { "1:00 of 2:00" }
                if (returning.value) rememberDetailFeedbackChannel(true) { currentCallbacks++ }
            }
        }
        repeat(3) { cycle ->
            compose.runOnIdle { first.value = true }
            compose.runOnIdle {
                if (stable == null) stable = ViewCompat.getAccessibilityDelegate(host)
                assertTrue(ViewCompat.getAccessibilityDelegate(host) === stable)
            }
            compose.runOnIdle { player.value = true }
            compose.runOnIdle { first.value = false }
            compose.runOnIdle { returning.value = true }
            compose.runOnIdle {
                val node = host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(99)!!
                assertEquals("1:00 of 2:00", AccessibilityNodeInfoCompat.wrap(node).stateDescription.toString())
                node.recycle()
            }
            compose.runOnIdle { player.value = false }
            compose.runOnIdle {
                val provider = host.accessibilityNodeProvider!!
                assertTrue(provider.performAction(1, 64, null))
                creates = 0
                focusQueries = 0
                provider.createAccessibilityNodeInfo(2)!!.recycle()
                assertEquals(1, creates, "One underlying native node query")
                assertEquals(1, focusQueries, "Disposed detail layers must not query focus again")
                assertEquals(0, oldCallbacks, "An old page cannot receive the returning page's label")
                assertEquals(cycle + 1, currentCallbacks)
            }
            compose.runOnIdle { returning.value = false }
            compose.runOnIdle {
                assertTrue(ViewCompat.getAccessibilityDelegate(host) === stable, "Player restores the same host bridge")
                assertTrue(host.accessibilityNodeProvider === native, "An inactive bridge forwards the original native provider")
            }
        }
    }
}
