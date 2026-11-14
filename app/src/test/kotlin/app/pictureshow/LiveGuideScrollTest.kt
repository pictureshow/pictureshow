/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class)
class LiveGuideScrollTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backRestoresBothOffsetsWithoutInheritingTheParentFocusPivot() {
        val account = Account(Server("s", "Demo", "http://example.test", "12"), User("u", "Viewer"), "token")
        val channels = (1..12).map { LiveChannel("c$it", "Channel $it", "$it") }
        val programs = channels.mapIndexed { i, c ->
            LiveProgram(
                "p${i + 1}",
                "Program ${i + 1}",
                c.id,
                startDate = Instant.ofEpochMilli(1_800_000).toString(),
                endDate = Instant.ofEpochMilli(2_400_000).toString()
            )
        }
        val detail = mutableStateOf(false)
        val pivot = mutableStateOf(false)
        lateinit var horizontal: ScrollState
        lateinit var vertical: LazyListState
        lateinit var scope: CoroutineScope
        val parent = object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = if (pivot.value) offset - 20f else 0f
        }
        compose.setContent {
            horizontal = rememberScrollState()
            vertical = rememberLazyListState()
            scope = rememberCoroutineScope()
            PictureshowTheme {
                CompositionLocalProvider(LocalBringIntoViewSpec provides parent) {
                    Column(Modifier.height(400.dp)) {
                        if (detail.value) {
                            Button(onClick = { detail.value = false }) { Text("Return to guide") }
                        } else {
                            LiveGuide(channels, programs, account, true, 0, 2_000_000, horizontal, vertical, "p:p4", true, {}) {
                                detail.value = true
                                pivot.value = true
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("live:p:p4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle {
            scope.launch {
                vertical.scrollToItem(1, 10)
                horizontal.scrollTo(80)
            }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle { Triple(vertical.firstVisibleItemIndex, vertical.firstVisibleItemScrollOffset, horizontal.value) }
        assertEquals(Triple(1, 10, 80), before)
        compose.onNodeWithTag("live:p:p4").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Return to guide").performClick()
        compose.onNodeWithTag("live:p:p4").assertIsFocused()
        val after = compose.runOnIdle { Triple(vertical.firstVisibleItemIndex, vertical.firstVisibleItemScrollOffset, horizontal.value) }
        assertEquals(before, after, "Back keeps channel and time offsets, not only the programme key")
    }
}
