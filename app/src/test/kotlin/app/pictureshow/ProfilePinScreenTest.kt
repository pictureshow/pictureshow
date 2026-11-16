/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
