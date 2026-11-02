/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class DetailReaderLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private class RetainedFocusProvider(private val host: View) : AccessibilityNodeProvider() {
        val labels = mutableMapOf(7 to "detail:play", 22 to "player:play-pause")
        var readerId = 7
        var readerFocused = true
        var inputFocused = true
        var inputFocusActions = 0
        var newReaderFocusEvents = 0
        val clears = mutableListOf<Int>()

        override fun createAccessibilityNodeInfo(id: Int) = AccessibilityNodeInfo.obtain().apply {
            setSource(host, id)
            viewIdResourceName = labels[id]
            className = "android.widget.Button"
            isFocused = inputFocused
            isAccessibilityFocused = readerFocused && id == readerId
            isVisibleToUser = true
            isClickable = true
            isEnabled = true
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? = if (readerFocused) createAccessibilityNodeInfo(readerId) else null

        override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (readerFocused && id == readerId) {
                    clears += id
                    readerFocused = false
                    true
                } else {
                    false
                }
            }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (readerFocused && id == readerId) {
                    false
                } else {
                    readerId = id
                    readerFocused = true
                    newReaderFocusEvents++
                    true
                }
            }
            AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> {
                inputFocusActions++
                true
            }
            else -> true
        }
    }

    private fun host(): Pair<View, RetainedFocusProvider> {
        val manager = compose.activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        org.robolectric.Shadows.shadowOf(manager).setEnabledAccessibilityServiceList(
            listOf(
                android.accessibilityservice.AccessibilityServiceInfo().apply {
                    feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
                }
            )
        )
        val host = View(compose.activity)
        val original = RetainedFocusProvider(host)
        ViewCompat.setAccessibilityDelegate(
            host,
            object : AccessibilityDelegateCompat() {
                override fun getAccessibilityNodeProvider(host: View) = AccessibilityNodeProviderCompat(original)
            }
        )
        return host to original
    }

    @Test fun pauseReleasesOnlyTheOwnedReaderFlagSoResumeCanAnnounceItsInputTarget() {
        val (host, native) = host()
        val owner = Owner()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host, LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(CatalogDetailState(account, film, loading = false), {}, {}, {}, {}, {}) }
            }
        }
        compose.runOnIdle {
            // The public node query supplies the exact native virtual ID of this page's action.
            host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(7)!!.recycle()
            assertTrue(native.readerFocused)
            assertTrue(native.inputFocused)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        compose.runOnIdle {
            assertEquals(listOf(7), native.clears, "A paused page must release its retained owned accessibility-focus flag")
            assertFalse(native.readerFocused)
            assertTrue(native.inputFocused, "Reader cleanup must not move the independent input cursor")
            assertEquals(0, native.inputFocusActions)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            // Models TalkBack's normal follow-input action: the old stale flag made this a no-op.
            assertTrue(host.accessibilityNodeProvider!!.performAction(7, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
            assertEquals(1, native.newReaderFocusEvents)
        }
    }

    @Test fun pauseDoesNotClearAPlayerNodesReaderFocusOnTheSharedHost() {
        val (host, native) = host()
        val owner = Owner()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host, LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(CatalogDetailState(account, film, loading = false), {}, {}, {}, {}, {}) }
            }
        }
        compose.runOnIdle {
            host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(7)!!.recycle()
            native.readerId = 22
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        compose.runOnIdle {
            assertTrue(native.clears.isEmpty())
            assertTrue(native.readerFocused)
            assertEquals(22, native.readerId)
            assertEquals(0, native.inputFocusActions)
        }
    }

    @Test fun anOutgoingOwnersPauseCannotClearTheNewOwnersMatchingLogicalAction() {
        val (host, native) = host()
        native.labels[22] = "detail:play"
        val oldOwner = Owner()
        val newOwner = Owner()
        val next = mutableStateOf(false)
        compose.runOnUiThread {
            oldOwner.registry.currentState = Lifecycle.State.RESUMED
            newOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                CompositionLocalProvider(LocalLifecycleOwner provides oldOwner) {
                    PictureshowTheme { CatalogDetail(CatalogDetailState(account, film, loading = false), {}, {}, {}, {}, {}) }
                }
                if (next.value) {
                    CompositionLocalProvider(LocalLifecycleOwner provides newOwner) {
                        rememberDetailFeedbackChannel(false) {}
                    }
                }
            }
        }
        compose.runOnIdle {
            host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(7)!!.recycle()
            next.value = true
        }
        compose.runOnIdle {
            native.readerId = 22
            host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(22)!!.recycle()
            oldOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        compose.runOnIdle {
            assertTrue(native.clears.isEmpty(), "Only the current page owner may release its own reader cursor")
            assertTrue(native.readerFocused)
            assertEquals(22, native.readerId)
            assertEquals(0, native.inputFocusActions)
        }
    }
}
