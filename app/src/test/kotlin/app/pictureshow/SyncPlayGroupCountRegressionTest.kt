/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

internal fun syncSocialRoleCase(id: String): JsonObject {
    val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    val file = roots.map { File(it, "spec/vectors/sync-group-social-summary-and-picker-role.json") }.first { it.isFile }
    val suite = Json.parseToJsonElement(file.readText()).jsonObject
    assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
    assertEquals("sync-group-social-summary-and-picker-role", suite.getValue("suite").jsonPrimitive.content)
    return suite.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
}

/** Real first-arrival phone layout: social context must not depend on a lucky fold. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayGroupCountRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun touchMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }

    @After fun restoreFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun onePersonIsVisibleOnFirstEmptyGroupArrival() = verify("phone-empty-one-person")

    @Test fun twoPeopleAreVisibleOnFirstEmptyGroupArrivalAtLargeFont() = verify("phone-empty-two-people-large-font")

    @Test fun zeroPeopleAndLaterSnapshotsUpdateTheVisibleLocalizedCountWithoutCommands() {
        val case = syncSocialRoleCase("phone-empty-count-snapshot-lifecycle")
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val snapshots = input.getValue("participantSnapshots").jsonArray.map { snapshot ->
            snapshot.jsonArray.map { it.jsonPrimitive.content }
        }
        val steps = expected.getValue("countSnapshots").jsonArray.map { it.jsonObject }
        assertEquals("touch", input.getValue("platform").jsonPrimitive.content)
        assertEquals(360, input.getValue("widthDp").jsonPrimitive.int)
        assertEquals(640, input.getValue("heightDp").jsonPrimitive.int)
        assertEquals(0, input.getValue("queueEntries").jsonPrimitive.int)
        assertEquals(4, snapshots.size, "Exercise initial zero and the later1,2,0 snapshots")
        assertEquals(snapshots.size, steps.size)
        assertEquals(emptyList(), snapshots.first())
        RuntimeEnvironment.setFontScale(input.getValue("fontScale").jsonPrimitive.float)
        val sent = mutableListOf<Pair<String, String?>>()
        val state = mutableStateOf(
            SyncPlayPresentation(
                server = "Cinema", viewer = "Viewer", policyKnown = true,
                policy = "CreateAndJoinGroups", groupId = "g", groupName = "Friday films",
                phase = input.getValue("phase").jsonPrimitive.content,
                participants = snapshots.first(), localPlayback = SyncLocalPlayback.UNAVAILABLE
            )
        )
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                SyncPlayPanel(state.value, { op, value -> sent += op to value }, {})
            }
        }
        var previousText: String? = null
        snapshots.zip(steps).forEachIndexed { index, (people, step) ->
            if (index > 0) compose.runOnIdle { state.value = state.value.copy(participants = people) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val count = step.getValue("count").jsonPrimitive.int
            val countText = step.getValue("countText").jsonPrimitive.content
            assertEquals(count, people.size, "Actual fixture snapshot must match its independently expected count")
            val locale = compose.activity.resources.configuration.locales[0]
            val localized = compose.activity.resources.getQuantityString(
                R.plurals.sync_people_watching,
                count,
                NumberFormat.getIntegerInstance(locale).format(count)
            )
            assertEquals(countText, localized, "The profile must use the native localized quantity text")
            val summary = compose.onNodeWithText(countText).assertIsDisplayed()
            val bounds = summary.getUnclippedBoundsInRoot()
            val owners = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            ).fetchSemanticsNodes().filter { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f }
            assertEquals(1, owners.size, "The same real group body must still have below-fold content")
            val owner = owners.single()
            val density = owner.layoutInfo.density.density
            assertEquals(2f, density, .001f, "Actual native phone profile must remain320dpi")
            assertEquals(false, expected.getValue("rescueScroll").jsonPrimitive.boolean)
            assertEquals(true, expected.getValue("wholeCountAboveFooterOnArrival").jsonPrimitive.boolean)
            assertEquals(0f, owner.config[SemanticsProperties.VerticalScrollAxisRange].value(), .5f, "No rescue scroll")
            assertTrue(bounds.top.value * density >= owner.boundsInRoot.top - 1f, "Whole count must be below the app bar: $bounds")
            assertTrue(bounds.bottom.value * density <= owner.boundsInRoot.bottom - 1f, "Whole count must be above the footer: $bounds")
            if (expected.getValue("previousDistinctCountAbsent").jsonPrimitive.boolean) {
                previousText?.takeIf { it != countText }?.let { compose.onNodeWithText(it).assertDoesNotExist() }
            }
            assertEquals(expected.getValue("groupCommands").jsonPrimitive.int, sent.size, "Count updates must send no group command")
            previousText = countText
        }
    }

    private fun verify(id: String) {
        val case = syncSocialRoleCase(id)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val people = input.getValue("participants").jsonArray.map { it.jsonPrimitive.content }
        assertEquals("touch", input.getValue("platform").jsonPrimitive.content)
        assertEquals(360, input.getValue("widthDp").jsonPrimitive.int)
        assertEquals(640, input.getValue("heightDp").jsonPrimitive.int)
        assertEquals(0, input.getValue("queueEntries").jsonPrimitive.int)
        RuntimeEnvironment.setFontScale(input.getValue("fontScale").jsonPrimitive.float)
        val sent = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                SyncPlayPanel(
                    SyncPlayPresentation(
                        server = "Cinema", viewer = "Viewer", policyKnown = true,
                        policy = "CreateAndJoinGroups", groupId = "g", groupName = "Friday films",
                        phase = input.getValue(
                            "phase"
                        ).jsonPrimitive.content,
                        participants = people, localPlayback = SyncLocalPlayback.UNAVAILABLE
                    ),
                    { op, value -> sent += op to value },
                    {}
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val summary = compose.onNodeWithText(expected.getValue("countText").jsonPrimitive.content)
        summary.assertIsDisplayed()
        val bounds = summary.getUnclippedBoundsInRoot()
        val owners = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        ).fetchSemanticsNodes().filter { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f }
        assertEquals(1, owners.size, "The real group body must still have below-fold content")
        val owner = owners.single()
        val density = owner.layoutInfo.density.density
        assertEquals(2f, density, .001f, "Actual native phone profile must remain320dpi")
        assertEquals(false, expected.getValue("rescueScroll").jsonPrimitive.boolean)
        assertEquals(true, expected.getValue("wholeCountAboveFooterOnArrival").jsonPrimitive.boolean)
        assertEquals(0f, owner.config[SemanticsProperties.VerticalScrollAxisRange].value(), .5f, "No rescue scroll")
        assertTrue(bounds.top.value * density >= owner.boundsInRoot.top - 1f, "Whole count must be below the app bar: $bounds")
        assertTrue(bounds.bottom.value * density <= owner.boundsInRoot.bottom - 1f, "Whole count must be above the footer: $bounds")
        assertEquals(expected.getValue("groupCommands").jsonPrimitive.int, sent.size, "A passive summary must send no group command")
    }

    /** Same real TV title/count row; compare native first baselines rather than the two layout-box centres. */
    @Test
    @Config(qualifiers = "w985dp-h554dp-land-television-156dpi")
    fun tvGroupNameAndCountShareTheirActualFirstBaselineAtFont10() = joinedTvBaseline("tv-joined-name-count-first-baseline-font10")

    @Test
    @Config(qualifiers = "w985dp-h554dp-land-television-156dpi")
    fun tvGroupNameAndCountShareTheirActualFirstBaselineAtFont13() = joinedTvBaseline("tv-joined-name-count-first-baseline-font13")

    @Test fun phoneSharedPlaybackScopePrecedesRepeatAndShuffleAtFont10() = joinedPhoneScope("phone-joined-shared-playback-scope-font10")

    @Test fun phoneSharedPlaybackScopePrecedesRepeatAndShuffleAtFont13() = joinedPhoneScope("phone-joined-shared-playback-scope-font13")

    private var joinedDensity = 0f
    private var joinedWindow = IntSize.Zero
    private val joinedCommands = mutableListOf<String>()

    private fun launchJoinedGeometry(tv: Boolean, input: JsonObject): MutableState<SyncPlayPresentation> {
        assertEquals(if (tv) "tv" else "touch", input.getValue("platform").jsonPrimitive.content)
        val fontScale = input.getValue("fontScale").jsonPrimitive.float
        val initialQueue = emptyList<SyncPlayQueueRow>()
        assertEquals(input.getValue("queueEntries").jsonPrimitive.int, initialQueue.size, "Bind the actual initial empty queue")
        RuntimeEnvironment.setFontScale(fontScale)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        val state = mutableStateOf(
            SyncPlayPresentation(
                server = "Cinema", viewer = "Viewer", policyKnown = true,
                policy = "CreateAndJoinGroups", groupId = "g", groupName = input.getValue("groupName").jsonPrimitive.content,
                phase = input.getValue("phase").jsonPrimitive.content,
                participants = input.getValue("participants").jsonArray.map { it.jsonPrimitive.content },
                localPlayback = SyncLocalPlayback.UNAVAILABLE, queue = initialQueue
            )
        )
        compose.setContent {
            val scale = LocalDensity.current.density
            val actualWindow = LocalWindowInfo.current.containerSize
            SideEffect {
                joinedDensity = scale
                joinedWindow = actualWindow
            }
            if (tv) {
                PictureshowTheme { SyncPlayPanel(state.value, { op, _ -> joinedCommands += op }, {}) }
            } else {
                app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(state.value, { op, _ -> joinedCommands += op }, {}) }
            }
        }
        settleJoinedGeometry()
        assertEquals(if (tv) 156 else 320, input.getValue("densityDpi").jsonPrimitive.int)
        assertEquals(input.getValue("densityDpi").jsonPrimitive.int, compose.activity.resources.displayMetrics.densityDpi)
        val profileWindow = IntSize(input.getValue("widthPx").jsonPrimitive.int, input.getValue("heightPx").jsonPrimitive.int)
        assertEquals(if (tv) IntSize(960, 540) else IntSize(720, 1280), profileWindow)
        assertEquals(profileWindow, joinedWindow, "Use the real physical window")
        assertEquals(if (tv) .975f else 2f, joinedDensity, .001f, "No synthetic density override")
        return state
    }

    private fun settleJoinedGeometry() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun joinedLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val values = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
        assertEquals(1, values.size, "Measure the actual production Text")
        return values.single()
    }

    private fun joinedNativeBounds(node: SemanticsNodeInteraction): Rect {
        val b = node.getUnclippedBoundsInRoot()
        return Rect(
            b.left.value * joinedDensity,
            b.top.value * joinedDensity,
            b.right.value * joinedDensity,
            b.bottom.value * joinedDensity
        )
    }

    private fun joinedTvBaseline(id: String) {
        val case = syncSocialRoleCase(id)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val fontScale = input.getValue("fontScale").jsonPrimitive.float
        val tolerance = expected.getValue("baselineToleranceNativePx").jsonPrimitive.float
        assertEquals(1f, tolerance, "Retain the strict one-native-pixel contract")
        launchJoinedGeometry(true, input)
        val group = compose.onNodeWithTag("sync:group", useUnmergedTree = true).assertIsDisplayed()
        val people = compose.onNodeWithTag("sync:people-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sync:${expected.getValue("firstUsefulFocus").jsonPrimitive.content}").assertIsFocused()
        val baselines = listOf(group, people).map { node ->
            val layout = joinedLayout(node)
            assertEquals(fontScale, layout.layoutInput.density.fontScale, .01f)
            assertTrue(layout.firstBaseline > 0f && layout.firstBaseline < layout.size.height)
            val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
            coordinates.localToRoot(Offset(0f, layout.firstBaseline)).y
        }
        val count = people.fetchSemanticsNode()
        assertEquals(
            expected.getValue("passive").jsonPrimitive.boolean,
            !count.config.contains(SemanticsActions.OnClick) && !count.config.contains(SemanticsActions.RequestFocus)
        )
        assertTrue(
            abs(baselines[0] - baselines[1]) <= tolerance,
            "Actual group-name and people-count first baselines must align within one native pixel: title=${baselines[0]} count=${baselines[1]} font=$fontScale"
        )
        assertEquals(
            expected.getValue("groupCommands").jsonPrimitive.int,
            joinedCommands.size,
            "Passive header geometry sends no group command"
        )
    }

    private fun joinedText(resource: Int) = compose.onNodeWithText(compose.activity.getString(resource), useUnmergedTree = true)

    private fun joinedPhoneFooter(expected: JsonObject): Rect {
        val tolerance = expected.getValue("geometryToleranceNativePx").jsonPrimitive.float
        val minimumHit = expected.getValue("minimumHitDp").jsonPrimitive.float
        val gap = expected.getValue("footerGapDp").jsonPrimitive.float
        assertEquals(1f, tolerance)
        assertEquals(48f, minimumHit)
        assertEquals(8f, gap)
        val footer = joinedNativeBounds(compose.onNodeWithTag("sync:touch-footer").assertIsDisplayed())
        val caption = joinedNativeBounds(joinedText(R.string.sync_leave_explanation).assertIsDisplayed())
        val leave = compose.onNodeWithTag("sync:leave").assertIsDisplayed()
        val leaveBox = joinedNativeBounds(leave)
        val hit = leave.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(hit.width >= minimumHit * joinedDensity - tolerance && hit.height >= minimumHit * joinedDensity - tolerance)
        assertTrue(caption.right + gap * joinedDensity <= leaveBox.left + tolerance, "Caption and Leave retain their native one-row gap")
        assertTrue(minOf(caption.bottom, leaveBox.bottom) > maxOf(caption.top, leaveBox.top))
        assertTrue(
            footer.left >= -tolerance && footer.right <= joinedWindow.width + tolerance && footer.bottom <= joinedWindow.height + tolerance
        )
        return footer
    }

    private fun joinedPhoneScope(id: String) {
        val case = syncSocialRoleCase(id)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val fontScale = input.getValue("fontScale").jsonPrimitive.float
        val tolerance = expected.getValue("geometryToleranceNativePx").jsonPrimitive.float
        val minimumHit = expected.getValue("minimumHitDp").jsonPrimitive.float
        val state = launchJoinedGeometry(false, input)
        val footer = joinedPhoneFooter(expected)
        listOf("sync:previous", "sync:play", "sync:pause", "sync:next").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        assertEquals(expected.getValue("scopeHeading").jsonPrimitive.content, compose.activity.getString(R.string.sync_group_playback))
        assertEquals(
            expected.getValue("scopeExplanation").jsonPrimitive.content,
            compose.activity.getString(R.string.sync_group_playback_scope)
        )
        val heading = joinedText(R.string.sync_group_playback).assertExists()
        val explanation = joinedText(R.string.sync_group_playback_scope).assertExists()
        heading.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        listOf(heading, explanation).forEach { node ->
            val config = node.fetchSemanticsNode().config
            assertEquals(
                expected.getValue("passive").jsonPrimitive.boolean,
                !config.contains(SemanticsActions.OnClick) && !config.contains(SemanticsActions.RequestFocus),
                "Scope is passive context"
            )
        }
        val repeat = joinedText(R.string.sync_repeat)
        val shuffle = compose.onNodeWithTag("sync:shuffle")
        val sections = mapOf(
            "queue" to joinedText(R.string.sync_queue),
            "scope-heading" to heading,
            "scope-helper" to explanation,
            "repeat-title" to repeat,
            "repeat-control" to compose.onNodeWithTag("sync:repeat:RepeatNone"),
            "shuffle" to shuffle,
            "people" to joinedText(R.string.sync_people)
        )
        val readingOrder = expected.getValue("readingOrder").jsonArray.map { sections.getValue(it.jsonPrimitive.content) }
        readingOrder.zipWithNext().forEach { (before, after) ->
            assertTrue(
                joinedNativeBounds(before).bottom <= joinedNativeBounds(after).top + tolerance,
                "Queue, shared scope and shared settings retain their reading order"
            )
        }
        repeat.performScrollTo()
        settleJoinedGeometry()
        val owner = generateSequence(repeat.fetchSemanticsNode().parent) { it.parent }.first {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        val viewport = owner.boundsInRoot
        val clearTop = compose.onAllNodesWithTag("sync:fade-top").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.bottom ?: viewport.top
        val clearBottom =
            compose.onAllNodesWithTag("sync:fade-bottom").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.top ?: viewport.bottom
        listOf(heading, explanation, repeat).forEach { node ->
            node.assertIsDisplayed()
            val layout = joinedLayout(node)
            assertEquals(fontScale, layout.layoutInput.density.fontScale, .01f)
            val rounding = .5f
            val metrics = "text=${layout.layoutInput.text} size=${layout.size} " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                "constraints=${layout.layoutInput.constraints} lines=${layout.lineCount} " +
                "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                "maxLinesExceeded=${layout.multiParagraph.didExceedMaxLines} " +
                "lineBoxes=${(0 until layout.lineCount).map {
                    listOf(layout.getLineLeft(it), layout.getLineRight(it), layout.getLineTop(it), layout.getLineBottom(it))
                }}"
            assertTrue(
                !layout.didOverflowHeight && !layout.multiParagraph.didExceedMaxLines,
                "The real scope text must fit vertically and retain all lines: $metrics"
            )
            for (line in 0 until layout.lineCount) {
                assertTrue(!layout.isLineEllipsized(line), "The real scope text must be readable without ellipsis: $metrics")
                assertTrue(
                    layout.getLineLeft(line) >= -rounding &&
                        layout.getLineRight(line) <= layout.size.width + rounding &&
                        layout.getLineTop(line) >= -rounding &&
                        layout.getLineBottom(line) <= layout.size.height + rounding,
                    "Actual scope glyph line must fit the Text box: line=$line $metrics"
                )
            }
            val b = joinedNativeBounds(node)
            assertTrue(
                b.top >= clearTop - tolerance && b.bottom <= clearBottom + tolerance,
                "Scope and its Repeat setting must be visible outside real fades: $b clear=$clearTop..$clearBottom"
            )
        }
        shuffle.performScrollTo()
        settleJoinedGeometry()
        shuffle.assertIsDisplayed()
        val afterScroll = joinedPhoneFooter(expected)
        assertTrue(
            abs(footer.top - afterScroll.top) <= tolerance && abs(footer.bottom - afterScroll.bottom) <= tolerance,
            "Body context cannot move the pinned footer"
        )
        val media = input.getValue("nextPresentation").jsonObject
        val queue = media.getValue("queue").jsonArray.map { item ->
            val row = item.jsonObject
            SyncPlayQueueRow(
                row.getValue("id").jsonPrimitive.content,
                row.getValue("title").jsonPrimitive.content,
                row.getValue("current").jsonPrimitive.boolean
            )
        }
        compose.runOnIdle {
            state.value = state.value.copy(
                phase = media.getValue("phase").jsonPrimitive.content,
                localPlayback = SyncLocalPlayback.valueOf(media.getValue("localPlayback").jsonPrimitive.content),
                queue = queue
            )
        }
        settleJoinedGeometry()
        val controls = listOf("sync:previous", "sync:play", "sync:next").map {
            compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()
        }
        val boxes = controls.map { node ->
            val hit = node.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(hit.width >= minimumHit * joinedDensity - tolerance && hit.height >= minimumHit * joinedDensity - tolerance)
            assertTrue(hit.left >= -tolerance && hit.right <= joinedWindow.width + tolerance && hit.bottom <= footer.top + tolerance)
            joinedNativeBounds(node)
        }
        boxes.zipWithNext().forEach { (before, after) ->
            assertTrue(before.right <= after.left + tolerance, "Transport targets cannot overlap")
            assertTrue(abs(before.center.y - after.center.y) <= tolerance, "Transport remains one centred native row")
        }
        val afterMedia = joinedPhoneFooter(expected)
        assertTrue(abs(footer.top - afterMedia.top) <= tolerance && abs(footer.bottom - afterMedia.bottom) <= tolerance)
        assertEquals(
            expected.getValue("groupCommands").jsonPrimitive.int,
            joinedCommands.size,
            "Reading scope, scrolling and queue publication send no group command"
        )
    }
}
