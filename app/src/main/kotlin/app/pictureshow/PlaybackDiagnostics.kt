/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.util.Log
import app.pictureshow.core.playback.PlayDecision

/** Callers supply ids, numeric fields and fixed reasons; never exception messages or request queries. */
internal object PlaybackDiagnostics {
    fun line(decision: PlayDecision?, event: String, fields: String): String =
        "$event item=${decision?.itemId ?: "-"} session=${decision?.playSessionId ?: "-"} $fields"

    fun write(line: String) {
        Log.i("PictureshowPlayback", line)
    }

    fun write(decision: PlayDecision, event: String, fields: String) = write(line(decision, event, fields))
}
