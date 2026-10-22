/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SearchImeTest {
    @Test
    fun searchInputRetainsDelegateConnectionAndActionAndRequestsNoFullscreenOrExtractUi() {
        val connection = BaseInputConnection(View(ApplicationProvider.getApplicationContext()), true)
        val info = EditorInfo()
        val delegate = PlatformTextInputMethodRequest {
            it.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            it.inputType = android.text.InputType.TYPE_CLASS_TEXT
            connection
        }
        assertSame(connection, searchInputRequest(delegate).createInputConnection(info))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertEquals(android.text.InputType.TYPE_CLASS_TEXT, info.inputType)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
    }
}
