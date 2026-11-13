/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSubtitleSampleTest {
    @Test fun previewImageHasTransparentSceneAndOutlinedReadableGlyphs() {
        val image = imageSubtitleSample()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue(pixels.count { Color.alpha(it) == 0 } > pixels.size / 2, "The scene must remain visible around the text")
        assertTrue(pixels.count { Color.alpha(it) > 200 && Color.red(it) > 230 } > 100, "Readable light glyphs")
        assertTrue(pixels.count { Color.alpha(it) > 200 && Color.red(it) < 30 } > 100, "Dark outline for bright scenes")
    }
}
