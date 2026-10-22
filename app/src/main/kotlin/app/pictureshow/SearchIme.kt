/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Keep the search results on screen while preserving Compose's input connection and editor action. */
internal fun searchInputRequest(delegate: PlatformTextInputMethodRequest) = PlatformTextInputMethodRequest { info ->
    delegate.createInputConnection(info).also {
        info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    }
}
