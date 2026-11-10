/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
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
