/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * TOUCH-11 (phone review P-4): Back closes an open sheet or menu first; otherwise it leaves the player, whether or not
 * the controls are showing. Hiding the controls is a tap on the video, never a Back.
 */
fun touchPlayerBack(sheetOpen: Boolean, menuOpen: Boolean): String = when {
    sheetOpen -> "close-sheet"
    menuOpen -> "close-menu"
    else -> "exit"
}

/** What a touch player knows when the viewer leaves the app or asks for picture-in-picture (TOUCH-15). */
data class PictureInPictureState(
    val tvInterface: Boolean,
    val deviceSupports: Boolean,
    val hasVideo: Boolean,
    val error: Boolean,
    val switching: Boolean,
    val exiting: Boolean,
    val atEnd: Boolean,
    val handedOff: Boolean
)

/**
 * TOUCH-15: a touch player shrinks into picture-in-picture when the viewer leaves for Home (button or swipe) while a
 * video is on screen. Not on a TV interface, a device without the feature, an error or loading-next card, a player
 * already leaving, a finished title, or a viewing handed to another screen (Cast, a remote TV): there is nothing to
 * keep watching in a small window.
 */
fun pictureInPictureAllowed(state: PictureInPictureState): Boolean = with(state) {
    !tvInterface && deviceSupports && hasVideo && !error && !switching && !exiting && !atEnd && !handedOff
}

/**
 * TOUCH-15's window actions, left to right: Back 10 seconds, Play or Pause, Forward 10 seconds. Each keeps its slot
 * when it can't act (a live channel with no time-shift), so a thumb aimed at Pause never lands on a seek.
 * [primary] is the player's own primary action: "play", "pause" or null when neither is possible.
 */
fun pictureInPictureActions(primary: String?, canSeekBack: Boolean, canSeekForward: Boolean): List<PictureInPictureAction> = listOf(
    PictureInPictureAction("back10", canSeekBack),
    PictureInPictureAction(if (primary == "pause") "pause" else "play", primary != null),
    PictureInPictureAction("forward10", canSeekForward)
)

data class PictureInPictureAction(val action: String, val enabled: Boolean)

/** Android refuses a picture-in-picture window outside 1:2.39 to 2.39:1. */
const val PIP_MAX_RATIO_NUMERATOR = 239
const val PIP_MAX_RATIO_DENOMINATOR = 100

/**
 * TOUCH-15: the window takes the video's display shape, clamped to what the system allows; no known size yet is 16:9.
 * [pixelRatio] is the video's pixel aspect ratio (anamorphic sources); non-positive or unknown counts as square.
 */
fun pictureInPictureAspect(width: Int, height: Int, pixelRatio: Double = 1.0): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 16 to 9
    val shownWidth = (width * (if (pixelRatio > 0) pixelRatio else 1.0)).toLong().coerceAtLeast(1)
    val ratio = shownWidth.toDouble() / height
    val max = PIP_MAX_RATIO_NUMERATOR.toDouble() / PIP_MAX_RATIO_DENOMINATOR
    return when {
        ratio > max -> PIP_MAX_RATIO_NUMERATOR to PIP_MAX_RATIO_DENOMINATOR
        ratio < 1 / max -> PIP_MAX_RATIO_DENOMINATOR to PIP_MAX_RATIO_NUMERATOR
        else -> {
            val divisor = gcd(shownWidth, height.toLong())
            (shownWidth / divisor).toInt() to (height / divisor).toInt()
        }
    }
}

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
