/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** PRIVATE SOURCE-ONLY DRAFT: no staging/compile/run evidence yet. No fake IME insets. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayPhoneWidthAndEmptyQueueRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun touchMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp-port-xhdpi")
    fun narrowCompactWindowAtFont13KeepsReadableNameLabelAndReachableCreate() {
        verifyCompactCreatePath(widthDp = 320, requireInitiallyVisibleCreate = false)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun true640By360DpWindowAtFont13KeepsNameAndCreateInitiallyVisible() {
        verifyCompactCreatePath(widthDp = 640, requireInitiallyVisibleCreate = true)
    }

    // These are configuration ASSERTIONS alongside real LocalWindowInfo pixels, never layout sizing.
    @android.annotation.SuppressLint("ConfigurationScreenWidthHeight")
    private fun verifyCompactCreatePath(widthDp: Int, requireInitiallyVisibleCreate: Boolean) {
        // Existing PlayerQualityMenuTest uses this real Robolectric resource/font setting.
        // Window size itself comes from @Config, not an outer configuration/density override.
        RuntimeEnvironment.setFontScale(1.3f)
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            assertEquals(IntSize(widthDp * 2, 720), LocalWindowInfo.current.containerSize,
                "A320dpi window must really be ${widthDp * 2}x720native, not mislabeled native pixels")
            assertEquals(widthDp, LocalConfiguration.current.screenWidthDp)
            assertEquals(360, LocalConfiguration.current.screenHeightDp)
            assertEquals(2f, LocalDensity.current.density, .001f)
            app.pictureshow.ui.PictureshowTheme {
                SyncPlayPanel(
                    SyncPlayPresentation(server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups"),
                    { op, value -> actions += op to value }, {}
                )
            }
        }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        val onePixel = .5.dp
        assertTrue(abs((panel.right - panel.left - widthDp.dp).value) <= onePixel.value,
            "Production Dialog must occupy the actual configured width: $panel")
        assertTrue(abs((panel.bottom - panel.top - 360.dp).value) <= onePixel.value,
            "Production Dialog must really be360dp tall, not180dp: $panel")
        val name = compose.onNodeWithTag("sync:name")
        name.assertIsDisplayed()
        val field = name.getUnclippedBoundsInRoot()
        assertTrue(field.right - field.left >= 200.dp - onePixel,
            "Readable name field needs200dp even beside/above Create: $field")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Group name", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val label = layouts.single()
        assertEquals(1.3f, label.layoutInput.density.fontScale, .01f,
            "The actual Dialog label must render at font1.3; an outer-only font override is insufficient")
        assertEquals(1, label.lineCount, "Field label must not become a vertical word ladder")
        assertEquals(label.layoutInput.text.length, label.getLineEnd(0, visibleEnd = true),
            "Complete field label must remain visible on its one line")
        assertTrue(!label.isLineEllipsized(0), "Readable field must fit the complete short label")
        assertTrue(label.getLineLeft(0) >= -1f && label.getLineRight(0) <= label.size.width + 1f,
            "Actual label line must fit its measured box: size=${label.size} " +
                "lineLeft=${label.getLineLeft(0)} lineRight=${label.getLineRight(0)}")
        if (requireInitiallyVisibleCreate) assertCreateInsideWindow()
        name.performClick().performTextInput("Friday films")
        // A narrow fallback may scroll. Reachability requires the whole target, not a sliver.
        compose.onNodeWithTag("sync:create").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertCreateInsideWindow()
        compose.onNodeWithTag("sync:name").assertTextContains("Friday films", substring = true)
        compose.onNodeWithTag("sync:create").performClick()
        assertEquals(listOf<Pair<String, String?>>("create" to "Friday films"), actions,
            "The reachable action must create exactly once with the full draft")
    }

    private fun assertCreateInsideWindow() {
        val create = compose.onNodeWithTag("sync:create").assertIsDisplayed().getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        val close = compose.onNodeWithTag("sync:close").getUnclippedBoundsInRoot()
        assertTrue(create.left >= panel.left && create.right <= panel.right &&
            create.top >= close.bottom && create.bottom <= panel.bottom,
            "The entire Create target must fit in the actual body window: create=$create panel=$panel close=$close")
    }

    @Test fun touchEmptyQueueOmitsLocalFactAndRealCurrentItemRestoresUnavailableAndErrorFacts() {
        verifyLocalFactLifecycle(tv = false)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun tvEmptyQueueOmitsLocalFactAndRealCurrentItemRestoresUnavailableAndErrorFacts() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        verifyLocalFactLifecycle(tv = true)
    }

    private fun verifyLocalFactLifecycle(tv: Boolean) {
        // Actual no-media projection is UNAVAILABLE, unlike the old READY fixture.
        var state by mutableStateOf(SyncPlayPresentation(
            server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
            groupId = "g", groupName = "Friday films", phase = "Idle",
            localPlayback = SyncLocalPlayback.UNAVAILABLE, queue = emptyList()
        ))
        compose.setContent {
            val actualConfiguration = LocalConfiguration.current
            // Change only UI type for the TV environment; preserve actual window dimensions/insets.
            val mode = Configuration(actualConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                    if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
            }
            CompositionLocalProvider(LocalConfiguration provides mode) {
                PictureshowTheme { SyncPlayPanel(state, { _, _ -> }, {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:now-playing").assertTextContains("Nothing playing yet", substring = true)
        compose.onNodeWithTag("sync:local").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(phase = "Paused", queue = listOf(SyncPlayQueueRow("entry-a", "A real film", true)))
        }
        compose.waitForIdle()
        assertUnavailableFactStillVisible()
        compose.runOnIdle {
            state = state.copy(phase = "Error", notice = SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:status").assertExists()
        assertUnavailableFactStillVisible()
        compose.runOnIdle { state = state.copy(phase = "Idle", notice = null, queue = emptyList()) }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:local").assertDoesNotExist()
    }

    private fun assertUnavailableFactStillVisible() {
        compose.onNodeWithTag("sync:local").performScrollTo().assertIsDisplayed()
            .assertTextContains("This device: Playback status unavailable", substring = true)
    }

    @Test fun joinedEmptyDebugFixtureUsesTheRealNoMediaProjection() {
        // Exercise the existing debug fixture producer, not a duplicate or new presentation helper.
        val fixture = assertNotNull(syncPlayFixture("joined-empty"))
        assertNotNull(fixture.groupId, "The fixture must remain a joined group")
        assertTrue(fixture.queue.isEmpty(), "The joined-empty fixture must really have no media")
        assertEquals(SyncLocalPlayback.UNAVAILABLE, fixture.localPlayback,
            "Fixture must match the actual empty player's unavailable projection, not invent READY")
    }
}
