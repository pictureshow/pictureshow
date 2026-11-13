/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.pictureshow.core.Account
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import kotlin.math.abs

/** Already-visible programmes keep their position; reveal a clipped edge by the shortest distance. */
internal fun liveGuideScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val end = offset + size
    if (offset >= 0f && end <= containerSize || offset < 0f && end > containerSize) return 0f
    return if (abs(offset) < abs(end - containerSize)) offset else end - containerSize
}

@OptIn(ExperimentalFoundationApi::class)
private object GuideBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        liveGuideScrollDistance(offset, size, containerSize)
}

/** Grid navigation must not inherit a catalog's focus pivot when its saved selection returns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveGuide(
    channels: List<LiveChannel>,
    programs: List<LiveProgram>,
    account: Account,
    tv: Boolean,
    start: Long,
    now: Long,
    scroll: ScrollState,
    list: LazyListState,
    returnFocus: String?,
    restore: Boolean,
    onFocus: (String) -> Unit,
    guideUnavailable: Boolean = false,
    channelHeader: @Composable () -> Unit = { LiveText("Channel", tv, small = true) },
    onOpen: (LiveProgram) -> Unit
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides GuideBringIntoView) {
        LiveGuideContent(
            channels, programs, account, tv, start, now, scroll, list, returnFocus, restore, onFocus,
            guideUnavailable, channelHeader, onOpen
        )
    }
}
