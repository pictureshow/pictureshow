/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream

// Chooser copy is shared with Apple and web through spec/vectors/track-labels.json.
internal fun trackLanguage(language: String?): String? = app.pictureshow.core.playback.trackLanguage(language)

internal fun trackLabel(stream: MediaStream, trackNumber: Int? = null, showDefault: Boolean = true): String =
    app.pictureshow.core.playback.trackLabel(stream, trackNumber, showDefault)

internal fun trackLabels(streams: List<MediaStream>): List<String> = app.pictureshow.core.playback.trackLabels(streams)
