/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.java.KoinJavaComponent.getKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV interface must never pay for Cast: not a class load, not a search, nothing at startup. */
class CastSourceIsolationTest {
    private val sources = File("src/main/kotlin/app/pictureshow").listFiles()!!.filter { it.extension == "kt" }

    @Test fun onlyTheAdapterImportsTheSdk() {
        val importers = sources.filter { file ->
            val imports = file.readLines().any { line ->
                line.startsWith("import com.google.android.gms.") || line.startsWith("import androidx.mediarouter")
            }
            imports || ("com.google.android.gms.cast" in file.readText() && file.name != "GmsCastLink.kt")
        }.map { it.name }
        assertEquals(listOf("GmsCastLink.kt"), importers)
    }

    /** The files a TV walks through at startup. If one of these names the sender, this fails. */
    @Test fun theTvPathDoesNotNameTheSender() {
        val tvPath =
            listOf("MainActivity.kt", "PlayerScreen.kt", "PlayerPlaybackHost.kt", "InterfaceMode.kt", "WatchNext.kt", "WatchNextRuntime.kt")
        val offending = sources.filter { it.name in tvPath && ("CastSenderHost" in it.readText() || "GmsCastLink" in it.readText()) }
        assertEquals(emptyList(), offending.map { it.name })
    }

    @Test fun theApplicationGraphOnlyDeclaresTheSender() {
        val application = sources.single { it.name == "PictureshowApplication.kt" }.readText()
        assertFalse("get<CastSenderHost>" in application)
        assertFalse("GmsCastLink" in application)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = PictureshowApplication::class)
class CastStartupTest {
    @AfterTest fun tearDown() {
        stopKoin()
        CastRuntime.hostBuilds = 0
        CastRuntime.linkBuilds = 0
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(qualifiers = "television")
    fun tvStartupBuildsNoSenderAndNoLink() {
        assertTrue(usesTvInterface(context), "this test proves nothing unless the app is in its TV interface")
        assertEquals(0, CastRuntime.hostBuilds)
        assertEquals(0, CastRuntime.linkBuilds)
    }

    @Test
    fun resolvingTheHostBuildsNoLink() {
        val builds = CastRuntime.linkBuilds
        val host = getKoin().get<CastSenderHost>()
        host.setForeground(true)
        host.openChooser(null)
        assertEquals(builds, CastRuntime.linkBuilds)
    }

    @Test
    fun theMergedManifestStartsNothingOfCastsAtProcessStart() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        val providers = info.providers.orEmpty().map { it.name }
        println("merged providers: $providers")
        assertFalse(providers.any { "cast" in it.lowercase() }, providers.toString())
        val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        assertEquals(
            "app.pictureshow.CastOptionsProvider",
            metadata.getString("com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME")
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CastEnvironmentTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun tvModeIsReportedSoTheHostCanRefuse() {
        val mode = context.getSystemService(UiModeManager::class.java)
        shadowOf(mode).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        assertTrue(AndroidCastEnvironment(context).tvInterface)
    }

    @Test fun belowTheNewTargetTheLocalNetworkNeedsNoAsk() {
        assertTrue(AndroidCastEnvironment(context).localNetworkAllowed())
    }

    @Test fun preferencesSurviveANewEnvironment() {
        val first = AndroidCastEnvironment(context)
        assertTrue(first.enabled)
        assertFalse(first.mayBeLive)
        first.enabled = false
        first.mayBeLive = true
        val second = AndroidCastEnvironment(context)
        assertFalse(second.enabled)
        assertTrue(second.mayBeLive)
    }
}
