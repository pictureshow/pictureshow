/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AboutLicensesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun open(start: String = "settings") {
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = start) {
                    composable("settings") {
                        PlaybackSettingsScreen(
                            PlaybackSettingsState(),
                            "PCM",
                            {},
                            { _, _ -> },
                            {},
                            onAbout = { nav.navigate("about") },
                            onBack = {}
                        )
                    }
                    aboutScreens(nav)
                }
            }
        }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    @Test
    fun remoteTraversesSettingsAboutListReaderAndRestoresFocusAtEveryBack() {
        open()
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("settings:about").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("about:licenses").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("licenses:0").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("licenses:1").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("license:chunk:0").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("license:chunk:1").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionUp)
        compose.onNodeWithTag("license:chunk:0").assertIsFocused().assertIsDisplayed()
        back()
        compose.onNodeWithTag("licenses:1").assertIsFocused()
        // Traverse enough rows to exercise off-screen list focus/scroll restoration too.
        repeat(15) { key(Key.DirectionDown) }
        compose.onNodeWithTag("licenses:16").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("license:chunk:0").assertIsFocused()
        back()
        compose.onNodeWithTag("licenses:16").assertIsFocused().assertIsDisplayed()
        back()
        compose.onNodeWithTag("about:licenses").assertIsFocused()
        back()
        compose.onNodeWithTag("settings:about").assertIsFocused()
    }

    @Test
    fun aboutShowsThisBuildVersionAndPublicProjectAddresses() {
        open("about")
        compose.onNodeWithText("Pictureshow").assertIsDisplayed()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertIsDisplayed()
        compose.onNodeWithText("Free and open-source under the Mozilla Public License 2.0").assertIsDisplayed()
        compose.onNodeWithText("Made by Legitimate LLC").assertIsDisplayed()
        compose.onNodeWithText("https://github.com/pictureshow/pictureshow").assertExists()
        compose.onNodeWithText("https://pictureshow.app/privacy/").assertExists()
    }

    private fun catalog(): List<JSONObject> {
        val context = RuntimeEnvironment.getApplication()
        val root = JSONObject(context.assets.open("licenses/catalog.json").bufferedReader().use { it.readText() })
        val rows = root.getJSONArray("libraries")
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    @Test
    fun generatedReleaseCatalogContainsTransitiveRuntimeLibrariesAndNativeLgplText() {
        val rows = catalog()
        listOf(
            "androidx.media3:media3-exoplayer",
            "com.android.billingclient:billing",
            "com.squareup.okhttp3:okhttp-android",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "com.squareup.okio:okio-jvm"
        ).forEach { id ->
            assertTrue(rows.any { it.getString("id") == id }, "Missing runtime library $id")
        }
        val billing = rows.single { it.getString("id") == "com.android.billingclient:billing" }
        assertEquals("9.1.0", billing.getString("version"))
        assertEquals("ASDKL", billing.getString("license"))
        assertTrue(billing.getString("text").contains("14.7"))
        assertTrue(billing.getString("text").contains("April 28, 2026"))
        val desugar = rows.single { it.getString("id") == "com.android.tools:desugar_jdk_libs" }
        assertEquals("2.1.5", desugar.getString("version"))
        assertEquals("GPL-2.0-only WITH Classpath-exception-2.0 AND Apache-2.0", desugar.getString("license"))
        assertTrue(desugar.getString("text").contains("\"CLASSPATH\" EXCEPTION TO THE GPL"))
        assertTrue(desugar.getString("text").contains("Apache License"))
        assertTrue(desugar.getString("text").contains("Version 2, June 1991"))
        val ffmpeg = rows.single { it.getString("id") == "ffmpeg" }
        assertEquals("6.0.1", ffmpeg.getString("version"))
        assertEquals("LGPL-2.1-or-later", ffmpeg.getString("license"))
        val text = ffmpeg.getString("text")
        assertTrue(text.contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue(text.contains("Version 2.1, February 1999"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz"))
        assertTrue(text.contains("third_party/ffmpeg/README.md"))
        assertTrue(text.contains("tools/ffmpeg/build.sh"))
        assertTrue(rows.any { it.getString("id") == "media3-ffmpeg" && it.getString("license") == "Apache-2.0" })
        assertTrue(rows.none { it.getString("id").contains("robolectric") || it.getString("id").contains("mockwebserver") })
    }

    @Test
    fun everyGeneratedEntryHasVersionFullLicenseAndPackagedNotices() {
        val rows = catalog()
        assertTrue(rows.size > 50, "Expected transitive dependencies")
        rows.forEach { row ->
            val id = row.getString("id")
            assertTrue(row.getString("version").isNotBlank(), "$id has no version")
            assertTrue(row.getString("license").isNotBlank(), "$id has no license")
            assertTrue(row.getString("text").length > 500, "$id has no full license text")
            val notices = row.getJSONArray("notices")
            for (index in 0 until notices.length()) {
                assertTrue(row.getString("text").contains(notices.getString(index)), "$id dropped a packaged notice")
            }
        }
        // Kotlin's binary distribution includes its own copyright notice, not just SPDX boilerplate.
        val kotlin = rows.single { it.getString("id") == "org.jetbrains.kotlin:kotlin-stdlib" }
        assertTrue(kotlin.getString("text").contains("JetBrains"))
        assertTrue(kotlin.getJSONArray("notices").length() > 0)
    }
}
