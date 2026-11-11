/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs
import kotlin.math.max

/** Measured section geometry belongs to vertical scrolling; each shelf retains the platform's horizontal policy. */
@OptIn(ExperimentalFoundationApi::class)
internal class LiveRecordingFocus(private val platform: BringIntoViewSpec, private val margin: Float, private val contentPadding: Float) {
    private val shelves = mutableMapOf<String, LayoutCoordinates>()
    private val headings = mutableMapOf<String, Int>()
    private val cards = mutableMapOf<String, LayoutCoordinates>()
    private val shelfHeights = mutableStateMapOf<String, Int>()
    private var viewport: LayoutCoordinates? = null
    private var viewportHeight by mutableIntStateOf(0)
    private var group: String? = null
    private var cardId: String? = null

    fun onShelfPositioned(group: String, coordinates: LayoutCoordinates) {
        shelves[group] = coordinates
        shelfHeights[group] = coordinates.size.height
    }

    fun onHeadingSize(group: String, height: Int) {
        headings[group] = height
    }

    fun onCardPositioned(id: String, coordinates: LayoutCoordinates) {
        cards[id] = coordinates
    }

    fun onCardFocused(group: String, id: String) {
        this.group = group
        cardId = id
    }

    fun onViewportPositioned(coordinates: LayoutCoordinates) {
        viewport = coordinates
        viewportHeight = coordinates.size.height
    }

    /** Enough scroll range to put the last measured section at the same pivot as every earlier section. */
    fun trailingSpace(lastGroup: String?): Float {
        if (lastGroup == null) return contentPadding
        // Until the last section is measured, reserve the viewport rather than briefly clamping its first focus request.
        val height = shelfHeights[lastGroup] ?: 0
        return max(contentPadding, viewportHeight - height - safeTopInset())
    }

    val verticalSpec: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val fallback = platform.calculateScrollDistance(offset, size, containerSize)
            val id = cardId ?: return fallback
            val shelfGroup = group ?: return fallback
            val card = cards[id]?.takeIf { it.isAttached && abs(it.size.height - size) < 1f } ?: return fallback
            val shelf = shelves[shelfGroup]?.takeIf { it.isAttached } ?: return fallback
            val heading = headings[shelfGroup] ?: return fallback
            val above = shelf.localPositionOf(card, Offset.Zero).y
            val topInset = safeTopInset()
            val available = (containerSize - topInset - safeBottomInset()).coerceAtLeast(0f)
            val safeOffset = offset - topInset
            val distance = focusedCardScrollDistance(safeOffset, size, available, margin, safeOffset - above)
            return sectionTitleScrollDistance(
                distance,
                safeOffset,
                size,
                available,
                margin,
                above,
                (above - heading).coerceAtLeast(0f)
            )
        }
    }

    @Composable
    fun HorizontalContent(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides platform, content = content)
    }

    private fun safeTopInset(): Float {
        val viewport = viewport?.takeIf { it.isAttached } ?: return contentPadding
        val rootHeight = viewport.findRootCoordinates().size.height
        return max(contentPadding, rootHeight * .05f - viewport.positionInRoot().y)
    }

    private fun safeBottomInset(): Float {
        val viewport = viewport?.takeIf { it.isAttached } ?: return 0f
        val rootHeight = viewport.findRootCoordinates().size.height
        return (viewport.positionInRoot().y + viewport.size.height - rootHeight * .95f).coerceAtLeast(0f)
    }
}
