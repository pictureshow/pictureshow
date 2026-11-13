/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.MediaStream

internal fun trackLanguage(language: String?): String? = app.pictureshow.core.playback.trackLanguage(language)

// Keep the platform probe seam: Android ICU and a desktop JDK intentionally retain their own Unicode folding.
private fun String.names(value: String): Boolean = app.pictureshow.core.playback.trackMetadataNames(this, value)

internal fun trackLabel(stream: MediaStream, trackNumber: Int? = null, showDefault: Boolean = true): String =
    app.pictureshow.core.playback.trackLabel(stream, trackNumber, showDefault)

internal fun trackLabels(streams: List<MediaStream>): List<String> = app.pictureshow.core.playback.trackLabels(streams)
