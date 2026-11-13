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

/** The same remote path exercised by the page-order and all entry-restoration regressions. */
internal val settingsViewerTags = listOf("settings:bitrate", "settings:passthrough", "settings:conversion-notice") +
    listOf("settings:music-quality", "settings:phone-remote", "settings:size", "settings:background") +
    playbackGroups.map { "settings:advanced:$it" } +
    SegmentType.entries.filter { it != SegmentType.Unknown }.map { "settings:segment:$it" } +
    listOf("settings:audio-language", "settings:subtitle-language", "settings:subtitle-mode")

internal fun ComposeContentTestRule.walkSettingsFromHomeTo(tag: String) {
    val path = settingsViewerTags + listOf("settings:supporter", "settings:about", "settings:this-tv", "settings:back")
    val target = path.indexOf(tag)
    require(target >= 0)
    path.drop(1).take(target).forEach { next ->
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithTag(next).assertIsFocused()
    }
}
