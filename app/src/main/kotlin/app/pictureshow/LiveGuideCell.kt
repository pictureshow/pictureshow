/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveProgram

internal typealias LiveGuideCell = app.pictureshow.core.livetv.LiveGuideCell
internal fun liveGuideCells(programs: List<LiveProgram>, start: Long, end: Long): List<LiveGuideCell> =
    app.pictureshow.core.livetv.liveGuideCells(programs, start, end)
internal fun liveGuideInitialFocus(cells: List<LiveGuideCell>, now: Long): String? =
    app.pictureshow.core.livetv.liveGuideInitialFocus(cells, now)
