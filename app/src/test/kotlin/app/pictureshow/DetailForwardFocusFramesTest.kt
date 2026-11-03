/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * A reader's forward open must put input focus on the guarded primary while the authoritative
 * read is still loading, a fixed small number of frames after the destination resumes. Frames,
 * not milliseconds: a slow device stretches each frame, but the handoff must not add frames.
 * The primary must also be focused before the read settles (the fixture never settles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailForwardFocusFramesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val movie = MediaItem("movie", "Invented movie", "Movie", overview = "Arrival overview")

    @Test fun aReaderForwardOpenFocusesPrimaryWithinFourFramesOfResumedWhileLoading() {
        val (resumed, focused) = forwardOpen()
        println("forward-open frames resumed=$resumed focused=$focused")
        assertTrue(
            focused - resumed <= MAX_FRAMES_AFTER_RESUMED,
            "primary focused ${focused - resumed} frames after RESUMED (navigate+$resumed, focus+$focused)"
        )
    }

    private fun forwardOpen(): Pair<Int, Int> {
        compose.mainClock.autoAdvance = false
        lateinit var nav: NavHostController
        val args = listOf(
            navArgument("item") {
                type = NavType.StringType
                defaultValue = "fixture"
            }
        )
        compose.setContent {
            PictureshowTheme {
                nav = rememberNavController()
                NavHost(
                    nav,
                    startDestination = "grid?item={item}",
                    enterTransition = { catalogEnterTransition(true, initialState.destination.route, targetState.destination.route) },
                    exitTransition = { catalogExitTransition(true, initialState.destination.route, targetState.destination.route) }
                ) {
                    composable("grid?item={item}", args) { BasicText("Grid") }
                    composable("item?item={item}", args) {
                        CatalogDetail(
                            CatalogDetailState(account, null, loading = true),
                            {},
                            {},
                            {},
                            {},
                            {},
                            origin = movie,
                            onPendingAction = { true }
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { nav.navigate("item?item=fixture") }
        var resumedFrame: Int? = null
        var focusedFrame: Int? = null
        for (frame in 1..40) {
            compose.mainClock.advanceTimeByFrame()
            val entry = nav.currentBackStackEntry
            if (resumedFrame == null &&
                entry?.destination?.route?.startsWith("item?") == true &&
                entry.lifecycle.currentState == Lifecycle.State.RESUMED
            ) {
                resumedFrame = frame
            }
            val primary = compose.onAllNodesWithTag("detail:play-pending").fetchSemanticsNodes()
            if (primary.singleOrNull()?.config?.getOrElse(SemanticsProperties.Focused) { false } == true) {
                focusedFrame = frame
                break
            }
        }
        assertNotNull(resumedFrame, "the detail destination never resumed")
        assertNotNull(focusedFrame, "the guarded primary never received input focus while loading")
        return resumedFrame to focusedFrame
    }

    private companion object {
        // rc26/rc27 and rc29 all measure 4 here (navigate+4 RESUMED, navigate+8 focused): one frame
        // before publication, two between publication and the request, one for the semantics update.
        const val MAX_FRAMES_AFTER_RESUMED = 4
    }
}
