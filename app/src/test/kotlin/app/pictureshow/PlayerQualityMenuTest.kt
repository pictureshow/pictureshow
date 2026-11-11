/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerQualityMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneOffersEveryChoiceAndOpeningDoesNotChangeQualityThenManualCanReturnToAuto() {
        var value by mutableStateOf<Int?>(null)
        var open by mutableStateOf(true)
        var selections = 0
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                if (open) {
                    PlayerQualityMenu(value, "Automatic: Remote limit 20 Mbps", null, true, {
                        value = it
                        selections++
                    }, { open = false })
                } else {
                    Button(onClick = { open = true }) { Text("Open quality") }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithTag("player:quality-choice:auto").assertIsSelected()
        listOf("Auto", "120 Mbps", "80 Mbps", "40 Mbps", "20 Mbps", "10 Mbps", "4 Mbps").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("player:quality-choice:40").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(40, value)
            assertEquals(1, selections)
        }
        compose.onNodeWithText("Open quality").performClick()
        compose.onNodeWithTag("player:quality-choice:40").assertIsSelected()
        compose.onNodeWithTag("player:quality-choice:80").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(80, value)
            assertEquals(2, selections)
        }
        compose.onNodeWithText("Open quality").performClick()
        compose.onNodeWithTag("player:quality-choice:80").assertIsSelected()
        compose.onNodeWithTag("player:quality-choice:auto").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(null, value)
            assertEquals(3, selections)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun shortLandscapeQualityMenuRevealsOverflowAndSelectsLowerCapOnce() {
        val file = listOf(File("../spec/vectors/playback-settings-layout.json"), File("spec/vectors/playback-settings-layout.json"))
            .single { it.isFile }
        val case = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("operation").jsonPrimitive.content == "player-quality-short-window-overflow" }
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        var value by mutableStateOf<Int?>(null)
        var open by mutableStateOf(true)
        var selections = 0
        var closes = 0
        compose.setContent {
            assertEquals(
                IntSize(input.getValue("widthDp").jsonPrimitive.int, input.getValue("heightDp").jsonPrimitive.int),
                LocalWindowInfo.current.containerSize,
                "The real native window must match the portable short landscape viewport"
            )
            CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                TouchTheme {
                    if (open) {
                        PlayerQualityMenu(
                            null,
                            input.getValue("automaticContext").jsonPrimitive.content,
                            input.getValue("reductionNotice").jsonPrimitive.content,
                            true,
                            {
                                value = it
                                selections++
                            },
                            {
                                closes++
                                open = false
                            }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(expected.getValue("selectionCountOnOpen").jsonPrimitive.int, selections)
            assertEquals(null, value)
            assertEquals(0, closes)
        }
        compose.onNodeWithTag("player:quality-choice:auto").assertIsSelected()
        val hint = expected.getValue("overflowHint").jsonPrimitive.content
        // Count first: omitting the cue must fail a content assertion, not a node lookup.
        assertEquals(1, compose.onAllNodesWithText(hint).fetchSemanticsNodes().size, "A short Quality menu must reveal its lower choices")
        compose.onNodeWithText(hint).assertIsDisplayed()
        val viewport = compose.onNodeWithTag("player:quality-menu").getBoundsInRoot()
        val supporting = input.getValue("automaticContext").jsonPrimitive.content + "\n" +
            input.getValue("reductionNotice").jsonPrimitive.content
        val supportBounds = compose.onNodeWithText(supporting, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val supportFullyVisible = supportBounds.top >= viewport.top &&
            supportBounds.bottom <= viewport.bottom &&
            supportBounds.left >= viewport.left &&
            supportBounds.right <= viewport.right
        assertEquals(
            true,
            supportFullyVisible,
            "The full Auto limit and reduction explanation must be visible before scrolling: $supportBounds / $viewport"
        )
        val requested = input.getValue("selectMbps").jsonPrimitive.int
        compose.onNodeWithTag("player:quality-choice:$requested").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(requested, value)
            assertEquals(expected.getValue("selectionCountAfterChoice").jsonPrimitive.int, selections)
            assertEquals(expected.getValue("closeCountAfterChoice").jsonPrimitive.int, closes)
        }
        assertEquals(0, compose.onAllNodesWithTag("player:quality-menu").fetchSemanticsNodes().size, "Selecting a cap closes the menu")
    }

    @Test fun tvOpensFocusedOnCurrentRadioAndSelectingAutoIsExplicit() {
        var selected: Int? = 40
        var closed = 0
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlayerQualityMenu(40, "Automatic: Metered limit 4 Mbps", null, false, { selected = it }, { closed++ })
            }
        }
        compose.onNodeWithText("40 Mbps").assertIsFocused().assertIsSelected()
        listOf("Auto", "120 Mbps", "80 Mbps", "40 Mbps", "20 Mbps", "10 Mbps", "4 Mbps").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Auto").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals(null, selected)
            assertEquals(1, closed)
        }
    }

    @Test fun automaticRadioAlignsWithItsLabelAtLargeFontScale() {
        val file = listOf(File("../spec/vectors/playback-settings-layout.json"), File("spec/vectors/playback-settings-layout.json"))
            .single { it.isFile }
        val case = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("operation").jsonPrimitive.content == "settings-radio-first-line" }
        val input = case.getValue("input").jsonObject
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                TouchTheme {
                    PlayerQualityMenu(null, "Automatic: Home limit 200 Mbps", "Quality limit reduced to 4 Mbps to avoid buffering.", true, {
                    }, {})
                }
            }
        }
        val label = compose.onNodeWithTag("player:quality-label:auto", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val radio = compose.onNode(
            hasTestTag("touch:radio-indicator") and hasAnyAncestor(hasTestTag("player:quality-choice:auto")),
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val offset = abs((label.top.value + label.bottom.value - radio.top.value - radio.bottom.value) / 2)
        assertEquals(
            true,
            offset <= case.getValue("expected").jsonObject.getValue("maximumCenterOffsetDp").jsonPrimitive.float,
            "$label / $radio"
        )
    }

    @Test fun automaticRowExplainsConfiguredNetworkLimitAndCurrentReduction() {
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                PlayerQualityMenu(null, "Automatic: Home limit 200 Mbps", "Quality limit reduced to 4 Mbps to avoid buffering.", true, {
                }, {})
            }
        }
        compose.onNodeWithText("Automatic: Home limit 200 Mbps\nQuality limit reduced to 4 Mbps to avoid buffering.").assertExists()
    }
}
