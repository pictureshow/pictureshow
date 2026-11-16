/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.SegmentType

/**
 * The TV Settings rows in page order, as the remote walks them (T-9): Playback & quality, Subtitles, Audio & video output
 * (This TV ends it), Skip segments, Your server preferences, then the Pictureshow rows. Each setting appears once.
 * Screens opened with a signed-in account put [settingsAccountTags] first.
 */
internal val settingsViewerTags =
    listOf("settings:advanced:Network quality", "settings:advanced:Playback", "settings:conversion-notice") +
        listOf("settings:music-quality", "settings:phone-remote") +
        listOf("settings:advanced:Subtitle appearance", "settings:advanced:Subtitle conversion") +
        listOf("settings:advanced:Audio output", "settings:advanced:Video output", "settings:this-tv") +
        SegmentType.entries.filter { it != SegmentType.Unknown }.map { "settings:segment:$it" } +
        listOf("settings:audio-language", "settings:subtitle-language", "settings:subtitle-mode", "settings:syncplay")

/** The Account group's rows, first on the page whenever someone is signed in. */
internal val settingsAccountTags = listOf("settings:switch-user", "settings:sign-out")

/** Every row after the viewer settings, through Back. */
internal val settingsTrailingTags = listOf("settings:supporter", "settings:about", "settings:back")

internal fun ComposeContentTestRule.walkSettingsFromHomeTo(tag: String) {
    val path = settingsViewerTags + settingsTrailingTags
    val target = path.indexOf(tag)
    require(target >= 0)
    path.drop(1).take(target).forEach { next ->
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(next).assertIsFocused()
    }
}
