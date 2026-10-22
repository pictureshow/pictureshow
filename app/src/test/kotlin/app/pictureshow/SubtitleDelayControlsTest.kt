package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleDelayControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun leftRightAdjustsFromEveryDelayActionAndCenterResets() {
        var value by mutableIntStateOf(0)
        compose.setContent { MaterialTheme { SubtitleDelayControls(value, true) { value = it } } }
        compose.onNodeWithTag("delay:earlier").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("delay:earlier").assertIsFocused()
        fun key(key: Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        key(Key.DirectionLeft)
        assertEquals(-100, value)
        key(Key.DirectionDown)
        compose.onNodeWithTag("delay:later").assertIsFocused()
        key(Key.DirectionRight)
        assertEquals(0, value)
        key(Key.DirectionDown)
        compose.onNodeWithTag("delay:reset").assertIsFocused()
        key(Key.DirectionRight)
        assertEquals(100, value)
        key(Key.DirectionCenter)
        assertEquals(0, value)
    }
}
