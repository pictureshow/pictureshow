/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 25, 26, 30], application = Application::class)
class LauncherBrandingTest {
    @Test
    fun manifestResolvesMarqueeIconsAndBannerAtEverySupportedResourceBoundary() {
        val app = RuntimeEnvironment.getApplication()
        val resources = app.resources
        val info = app.packageManager.getApplicationInfo(app.packageName, 0)
        fun resource(type: String, name: String): Int =
            resources.getIdentifier(name, type, app.packageName).also { assertNotEquals(0, it, "$type/$name is missing") }

        val icon = resource("mipmap", "ic_launcher")
        val roundIcon = resource("mipmap", "ic_launcher_round")
        assertEquals(icon, info.icon)
        assertEquals(resource("drawable", "tv_banner"), info.banner)
        app.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.next() != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                    assertEquals(
                        roundIcon,
                        manifest.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "roundIcon", 0)
                    )
                    break
                }
            }
        }
        val normal = app.packageManager.getApplicationIcon(info)
        val round = resources.getDrawable(roundIcon, app.theme)
        if (Build.VERSION.SDK_INT >= 26) {
            assertIs<AdaptiveIconDrawable>(normal)
            assertIs<AdaptiveIconDrawable>(round)
        } else {
            assertIs<BitmapDrawable>(normal)
            assertIs<BitmapDrawable>(round)
        }
        val banner = assertIs<BitmapDrawable>(app.packageManager.getApplicationBanner(info)).bitmap
        assertEquals(0xff10131c.toInt(), banner.getPixel(0, 0))
        val pixels = IntArray(banner.width * banner.height)
        banner.getPixels(pixels, 0, banner.width, 0, 0, banner.width, banner.height)
        assertTrue(pixels.any { it == 0xffffb547.toInt() }, "Marquee amber bulbs must be present")
        assertTrue(pixels.any { it == 0xffb8c4ff.toInt() }, "Marquee periwinkle screen and wordmark must be present")
    }
}
