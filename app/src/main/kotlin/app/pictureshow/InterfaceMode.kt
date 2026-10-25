/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** A touch-capable television is still a television. Input hardware does not choose the UI. */
internal fun isTvInterface(uiModeType: Int): Boolean = uiModeType == Configuration.UI_MODE_TYPE_TELEVISION

internal fun usesTvInterface(context: Context): Boolean = isTvInterface(
    (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
)
