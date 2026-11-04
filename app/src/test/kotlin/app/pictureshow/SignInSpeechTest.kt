/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SignInSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fieldCaptionsSupplyNoTextForTalkBackToPromoteToAnEmptyGroupStop() {
        compose.setContent {
            PictureshowTheme {
                Column {
                    RemoteField("Server address", "", {})
                    RemoteField("Username", "", {})
                    RemoteField("Password", "", {}, password = true)
                }
            }
        }
        listOf("Server address", "Username", "Password").forEach { label ->
            compose.onNodeWithContentDescription(label).assertExists()
            // hideFromAccessibility leaves text on the node. TalkBack's group heuristic
            // still counts it even though that text cannot produce an utterance.
            compose.onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun codeArrivalAndEachChangeAreAnnouncedOnceWithoutMovingTheViewer() {
        val code = mutableStateOf("123456")
        val announcements = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                Column {
                    QuickConnectCode(code.value, announcements::add)
                    Button(onClick = {}) { Text("Back") }
                }
            }
        }
        compose.runOnIdle { assertEquals(listOf("Quick Connect code 1 2 3 4 5 6"), announcements) }
        compose.onNodeWithText("Back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { code.value = "654321" }
        compose.onNodeWithText("Back").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("Quick Connect code 1 2 3 4 5 6", "Quick Connect code 6 5 4 3 2 1"), announcements)
            code.value = "123456"
        }
        compose.onNodeWithText("Back").assertIsFocused()
        compose.runOnIdle { assertEquals(3, announcements.size) }
    }

    @Test fun aChangedCodeIsAnnouncedWhileTheCodeItselfKeepsFocus() {
        val code = mutableStateOf("123456")
        val announcements = mutableListOf<String>()
        compose.setContent { PictureshowTheme { QuickConnectCode(code.value, announcements::add) } }
        compose.onNodeWithText("123456").assertIsFocused()
        compose.runOnIdle {
            announcements.clear()
            code.value = "654321"
        }
        compose.onNodeWithText("654321").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("Quick Connect code 6 5 4 3 2 1"), announcements) }
    }

    @Test fun aScreenReaderCodeArrivalDoesNotStealInputFromTheExistingAction() {
        val manager = compose.activity.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        val code = mutableStateOf<String?>(null)
        val announcements = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                Column {
                    code.value?.let { QuickConnectCode(it, announcements::add) }
                    Button(onClick = {}) { Text("Back") }
                }
            }
        }
        compose.onNodeWithText("Back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { code.value = "123456" }
        compose.onNodeWithContentDescription("Quick Connect code 1 2 3 4 5 6").assertExists()
        compose.onNodeWithText("Back").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("Quick Connect code 1 2 3 4 5 6"), announcements) }
    }

    @Test fun theCodeLabelItselfOwnsArrivalFocus() {
        compose.setContent { PictureshowTheme { QuickConnectCode("123456") } }
        compose.onNodeWithContentDescription("Quick Connect code 1 2 3 4 5 6").assertIsFocused()
    }

    @Test fun aRejectedArrivalFocusStillAnnouncesTheCode() {
        val announcements = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()) {
                    QuickConnectCode("123456", announcements::add)
                }
            }
        }
        compose.runOnIdle { assertEquals(listOf("Quick Connect code 1 2 3 4 5 6"), announcements) }
    }

    @Test fun initialCodeIsPublishedOnlyOnTheResumedPage() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    QuickConnectCode("123456")
                }
            }
        }
        compose.onNodeWithText("123456").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        var firstVisibleFocused: Boolean? = null
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val nodes = compose.onAllNodesWithText("123456").fetchSemanticsNodes()
            if (firstVisibleFocused == null && nodes.isNotEmpty()) {
                firstVisibleFocused = nodes.single().config.getOrNull(SemanticsProperties.Focused)
            }
        }
        assertEquals(true, firstVisibleFocused)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("123456").assertIsFocused()
    }

    @Test fun initialCodeWaitsForThePageTransitionBeforeRequestingFocus() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        val code = mutableStateOf<String?>(null)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                PictureshowTheme {
                    Column {
                        code.value?.let { QuickConnectCode(it) }
                        Button(onClick = {}) { Text("Back") }
                    }
                }
            }
        }
        compose.onNodeWithText("Back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { code.value = "123456" }
        compose.onNodeWithText("Back").assertIsFocused()
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.onNodeWithText("123456").assertIsFocused()
    }

    @Test fun renewedCodeIsAnnouncedWithoutMovingTheViewersFocus() {
        val code = mutableStateOf("123456")
        val announcements = mutableListOf<String>()
        compose.setContent {
            PictureshowTheme {
                Column {
                    QuickConnectCode(code.value, announcements::add)
                    Button(onClick = {}) { Text("Back") }
                }
            }
        }
        compose.onNodeWithText("Back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { code.value = "654321" }
        compose.onNodeWithText("Back").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf("Quick Connect code 1 2 3 4 5 6", "Quick Connect code 6 5 4 3 2 1"), announcements) }
    }

    @Test fun aRenewalThatReusesTheArrivalDigitsStillAnnouncesItsUpdate() {
        val code = mutableStateOf("123456")
        val announcements = mutableListOf<String>()
        compose.setContent { PictureshowTheme { QuickConnectCode(code.value, announcements::add) } }
        compose.runOnIdle { code.value = "654321" }
        compose.runOnIdle { code.value = "123456" }
        compose.runOnIdle {
            assertEquals(
                listOf("Quick Connect code 1 2 3 4 5 6", "Quick Connect code 6 5 4 3 2 1", "Quick Connect code 1 2 3 4 5 6"),
                announcements
            )
        }
    }
}
