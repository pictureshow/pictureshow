/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.playback.MediaSourceInfo

/** A failed configuration read leaves the server in charge, including an explicit None mode. */
internal fun captionSubtitleLanguage(enabled: Boolean, configuration: UserConfiguration?, tvLanguage: String): String? =
    app.pictureshow.core.playback.captionSubtitleLanguage(enabled, configuration, tvLanguage)

/** Prefer full captions, then hearing-impaired, then text for styling and seamless switching.
 * Delivery is negotiated by the server.
 */
internal fun captionSubtitleIndex(source: MediaSourceInfo, preferredLanguage: String): Int? =
    app.pictureshow.core.playback.captionSubtitleIndex(source, preferredLanguage)
