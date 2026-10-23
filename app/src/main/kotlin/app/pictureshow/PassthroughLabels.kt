/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.PassthroughEncoding

/** Name the accepted encoding, including its extensions, rather than collapsing it to a decoder codec. */
internal fun passthroughEncodingLabels(encodings: Set<PassthroughEncoding>, emptyLabel: String = "PCM only"): String =
    encodings.sortedBy { it.ordinal }.joinToString(", ") {
        when (it) {
            PassthroughEncoding.AC3 -> "AC3"
            PassthroughEncoding.EAC3 -> "EAC3"
            PassthroughEncoding.EAC3_JOC -> "EAC3 JOC"
            PassthroughEncoding.DTS -> "DTS"
            PassthroughEncoding.DTS_HD -> "DTS-HD"
            PassthroughEncoding.TRUEHD -> "TrueHD"
        }
    }.ifEmpty { emptyLabel }
