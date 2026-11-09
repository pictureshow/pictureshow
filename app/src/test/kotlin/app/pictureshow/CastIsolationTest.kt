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
import app.pictureshow.core.control.CastStoredSessions
import app.pictureshow.core.control.CastViewingRecord
import app.pictureshow.core.control.TemporaryState
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    /** Robolectric's mode shadow ignores the television qualifier, so the application starts again as a TV. */
    @Test
    fun tvStartupBuildsNoSenderAndNoLink() {
        val application = ApplicationProvider.getApplicationContext<PictureshowApplication>()
        shadowOf(application.getSystemService(UiModeManager::class.java)).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        stopKoin()
        CastRuntime.hostBuilds = 0
        CastRuntime.linkBuilds = 0
        application.onCreate()
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

    /** The TV build shares this manifest. It gets nothing for a reconnection service or media notification that's turned off. */
    @Test
    fun theMergedManifestHasNoneOfTheSdksUnusedComponents() {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        assertEquals(emptyList(), info.services.orEmpty().map { it.name }.filter { "gms.cast" in it })
        assertEquals(emptyList(), info.receivers.orEmpty().map { it.name }.filter { "gms.cast" in it })
        // Foreground service rights exist for downloads and music alone; Cast still gets no service to use them with.
        assertEquals(
            setOf(
                OfflineTransferService::class.java.name to android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                MusicPlaybackService::class.java.name to android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            ),
            info.services.orEmpty().filter { it.foregroundServiceType != 0 }.map { it.name to it.foregroundServiceType }.toSet()
        )
        assertEquals(false, info.services.orEmpty().single { it.name == MusicPlaybackService::class.java.name }.exported)
        assertEquals(false, info.services.orEmpty().single { it.name == OfflineTransferService::class.java.name }.exported)
        assertEquals(
            setOf("android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
            info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.FOREGROUND_SERVICE_") }.toSet()
        )
        assertEquals(1, info.requestedPermissions.orEmpty().count { it == "android.permission.FOREGROUND_SERVICE" })
    }

    @Test
    fun whatTheManifestRemovesIsTurnedOffInTheOptions() {
        val options = CastOptionsProvider().getCastOptions(context)
        assertFalse(options.enableReconnectionService)
        assertNull(options.castMediaOptions!!.notificationOptions)
        assertFalse(options.castMediaOptions!!.mediaSessionEnabled)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CastEnvironmentTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** A dispatcher that runs nothing until asked, so a test can see which thread of work a write was on. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val held = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held += block
        }

        fun drain() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    private val session = CastStoredSessions(TemporaryState(emptyList()), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun noWriteHappensOnTheCallersThread() = runTest(UnconfinedTestDispatcher()) {
        val storage = HeldDispatcher()
        val environment = AndroidCastEnvironment(context, storage)
        val marked = CastStoredSessions(TemporaryState(effect = "keep"), emptyMap())
        val save = launch { environment.saveSessions(marked) }
        assertEquals(session, environment.sessions, "held back: nothing has written yet")
        assertFalse(save.isCompleted)
        storage.drain()
        assertTrue(save.isCompleted)
        assertEquals(marked, AndroidCastEnvironment(context).sessions)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aViewingReadsBackAtOnceAndWritesInOrder() = runTest(UnconfinedTestDispatcher()) {
        val storage = HeldDispatcher()
        val environment = AndroidCastEnvironment(context, storage)
        val record =
            CastViewingRecord(
                nonce = "n",
                server = "s",
                viewer = "v",
                route = "r",
                device = "d",
                session = "c",
                generation = 2,
                items = emptyList()
            )
        val save = launch { environment.saveViewing(record) }
        assertEquals(record, environment.viewing)
        assertNull(AndroidCastEnvironment(context).viewing, "the disk hasn't got it yet")
        environment.clearViewing()
        assertNull(environment.viewing)
        storage.drain()
        assertTrue(save.isCompleted)
        assertNull(AndroidCastEnvironment(context).viewing, "the later clear is the one that stays")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSavedViewingSurvivesANewEnvironment() = runTest(UnconfinedTestDispatcher()) {
        val record =
            CastViewingRecord(
                nonce = "n",
                server = "s",
                viewer = "v",
                route = "r",
                device = "d",
                session = "c",
                generation = 2,
                items = emptyList()
            )
        val storage = HeldDispatcher()
        val save = launch { AndroidCastEnvironment(context, storage).saveViewing(record) }
        storage.drain()
        assertTrue(save.isCompleted)
        assertEquals(record, AndroidCastEnvironment(context).viewing)
    }

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
