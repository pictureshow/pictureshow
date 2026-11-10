/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every way off the search screen withdraws the phone's text, and only coming back restores it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RemoteTextDestinationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var sink: ((String) -> Unit)? = null
    private val typed = mutableListOf<String>()
    private lateinit var nav: NavHostController
    private var shown by mutableStateOf(true)
    private val register: (((String) -> Unit)?) -> Unit = { sink = it }

    private fun go(move: NavHostController.() -> Unit) {
        compose.runOnIdle { nav.move() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun forwardBackReplacedAndRemovedAllWithdrawTheSink() {
        compose.setContent {
            if (shown) {
                nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {}
                    composable("credentials") {}
                    composable("player") {}
                    composable("search") { RemoteTextDestination(register) { typed += it } }
                }
            }
        }
        go { navigate("search") }
        requireNotNull(sink)("alien")
        assertEquals(listOf("alien"), typed)
        go { navigate("credentials") }
        assertNull(sink, "forward to a credential screen")
        go { popBackStack() }
        assertNotNull(sink, "back to search")
        go { navigate("player") }
        assertNull(sink, "forward to the player")
        go { popBackStack() }
        go { popBackStack() }
        assertNull(sink, "back off search")
        go { navigate("search") }
        go {
            navigate("home") {
                popUpTo(graph.id) { inclusive = true }
            }
        }
        assertNull(sink, "go home clears the stack")
        go { navigate("search") }
        assertNotNull(sink)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertNull(sink, "the whole graph leaves on sign-out or activity teardown")
    }
}
