/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

internal typealias LiveGuideTextWindow = app.pictureshow.core.livetv.LiveGuideTextWindow
internal fun liveGuideTextWindow(cellX: Float, cellWidth: Float, viewportStart: Float, viewportWidth: Float): LiveGuideTextWindow =
    app.pictureshow.core.livetv.liveGuideTextWindow(cellX, cellWidth, viewportStart, viewportWidth)
