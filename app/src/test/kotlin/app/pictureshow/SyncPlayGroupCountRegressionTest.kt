/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import java.text.NumberFormat
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
}
