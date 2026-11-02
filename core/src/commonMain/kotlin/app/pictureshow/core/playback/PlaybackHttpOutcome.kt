/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Only fixed outcomes cross into release playback diagnostics; never an exception message or URL. */
enum class PlaybackReportFailure { Http, Timeout, Network, InvalidResponse, Cancelled }

data class PlaybackHttpOutcome(val status: Int?, val failure: PlaybackReportFailure? = null)
