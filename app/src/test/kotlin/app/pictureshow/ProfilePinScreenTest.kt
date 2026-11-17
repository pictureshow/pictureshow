/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.pictureshow.core.JvmProfilePinHasher
import app.pictureshow.core.ProfileLockStore
import app.pictureshow.core.TextPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AUTH-16: the lock screen's pad checks the PIN through the store and opens only on the right one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class ProfilePinScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Memory(var text: String? = null) : TextPersistence {
        override suspend fun read() = text
        override suspend fun write(value: String) {
            text = value
        }
    }

    private fun type(pin: String) {
        pin.forEach { compose.onNodeWithTag("pin:key:$it").performClick() }
        compose.onNodeWithTag("pin:ok").performClick()
    }

    /**
     * A remote: no touch mode (performClick would inject touches, and in touch mode TV buttons can't take focus), focus
     * resting on "0", digits from the remote's number keys and Enter to submit. Without PIN focus reset, focus would
     * stay on "0" after each try.
     */
    private fun remote() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)

    private val digitKeys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

    private fun press(pin: String) {
        compose.onNodeWithTag("pin:key:0").requestFocus()
        compose.onNodeWithTag("pin:key:0").assertIsFocused()
        compose.onNodeWithTag("pin:key:0").performKeyInput {
            pin.forEach { pressKey(digitKeys[it - '0']) }
            pressKey(Key.NumPadEnter)
        }
    }

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) { condition() }

    @Test
    fun aWrongPinSaysSoAndTheRightOneOpens() {
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking {
            store.set("parent", "2468", askOnStart = true)
            store.markUnlocked(null)
        }
        var opened = 0
        var switched = 0
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                ProfileLockScreen(store, "parent", "Parent", tv = false, onUnlocked = {
                    opened++
                }, onSwitch = { switched++ }, onPassword = {})
            }
        }
        type("1111")
        waitUntil { runCatching { compose.onNodeWithTag("pin:note").assertTextContains("didn't match", substring = true) }.isSuccess }
        assertEquals(0, opened)
        type("2468")
        waitUntil { opened == 1 }
        assertEquals("parent", store.unlocked.value)
        compose.onNodeWithTag("pin:switch").performClick()
        compose.runOnIdle { assertEquals(1, switched) }
    }

    /** On a remote, focus goes back to "1" after a wrong PIN and on "Type the PIN again" (rc38 finding 1). */
    @Test
    fun focusReturnsToTheFirstKeyAfterEachTry() {
        remote()
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking {
            store.set("parent", "2468", askOnStart = true)
            store.markUnlocked(null)
        }
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                ProfileLockScreen(store, "parent", "Parent", tv = true, onUnlocked = {}, onSwitch = {}, onPassword = {})
            }
        }
        press("1111")
        waitUntil { runCatching { compose.onNodeWithTag("pin:note").assertTextContains("didn't match", substring = true) }.isSuccess }
        waitUntil { runCatching { compose.onNodeWithTag("pin:key:1").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("pin:key:1").assertIsFocused()
    }

    @Test
    fun theConfirmStepStartsOnTheFirstKey() {
        var chosen: String? = null
        remote()
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                ProfilePinSetup(tv = true, name = "Parent", onDone = { chosen = it }, onCancel = {})
            }
        }
        press("2580")
        waitUntil { runCatching { compose.onNodeWithTag("pin:key:1").assertIsFocused() }.isSuccess }
        press("2580")
        compose.runOnIdle { assertEquals("2580", chosen) }
    }

    private fun lockScreen(onUnlocked: () -> Unit): ProfileLockStore {
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking {
            store.set("parent", "2468", askOnStart = true)
            store.markUnlocked(null)
        }
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                ProfileLockScreen(store, "parent", "Parent", tv = true, onUnlocked = onUnlocked, onSwitch = {}, onPassword = {})
            }
        }
        return store
    }

    /**
     * rc39: number keys, then the remote's OK (Enter or D-pad center) typed another "1", because focus rested on the
     * "1" key. A number-key digit moves focus to OK, so OK submits whichever code the remote sends.
     */
    private fun numberKeysThen(enter: Key) {
        remote()
        var opened = 0
        lockScreen { opened++ }
        compose.onNodeWithTag("pin:key:1").requestFocus()
        compose.onNodeWithTag("pin:key:1").assertIsFocused()
        compose.onNodeWithTag("pin:key:1").performKeyInput { "2468".forEach { pressKey(digitKeys[it - '0']) } }
        waitUntil { runCatching { compose.onNodeWithTag("pin:ok").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("pin:ok").performKeyInput { pressKey(enter) }
        waitUntil { opened == 1 }
    }

    @Test
    fun numberKeysThenEnterSubmits() = numberKeysThen(Key.Enter)

    @Test
    fun numberKeysThenDPadCenterSubmits() = numberKeysThen(Key.DirectionCenter)

    /** A D-pad-only remote (the projector) types by pressing the focused key: focus stays on it until the viewer moves. */
    @Test
    fun dPadCenterOnAKeyTypesItAndFocusStays() {
        remote()
        var opened = 0
        lockScreen { opened++ }
        "2468".forEach { digit ->
            val key = compose.onNodeWithTag("pin:key:$digit")
            key.requestFocus()
            key.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            key.assertIsFocused()
        }
        compose.onNodeWithTag("pin:dots").assertContentDescriptionContains("4 digits", substring = true)
        compose.onNodeWithTag("pin:ok").requestFocus()
        compose.onNodeWithTag("pin:ok").performKeyInput { pressKey(Key.DirectionCenter) }
        waitUntil { opened == 1 }
    }

    @Test
    fun afterTenWrongTriesOnlyThePasswordRouteIsLeft() {
        val hasher = JvmProfilePinHasher(rounds = 1_000)
        val locks = mapOf("parent" to app.pictureshow.core.ProfileLock(hasher.create("2468"), failures = 10, failedAt = 5))
        val store = ProfileLockStore(Memory(kotlinx.serialization.json.Json.encodeToString(locks)), hasher)
        runBlocking { store.load() }
        var password = 0
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                ProfileLockScreen(store, "parent", "Parent", tv = false, onUnlocked = {}, onSwitch = {}, onPassword = { password++ })
            }
        }
        waitUntil { compose.onAllNodesWithTag("pin:key:1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("pin:note").assertTextContains("the PIN is off", substring = true)
        compose.onNodeWithTag("pin:password").performClick()
        compose.runOnIdle { assertEquals(1, password) }
    }
}
