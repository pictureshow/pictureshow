/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The next episode's seconds of loading have no player on screen, and the rotation has to hold through them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class OfflineRouteWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var nav: NavHostController
    private var musicEnded = 0

    /** An engine that never finishes its first load, so its routes stay in the gap before a player exists. */
    private fun inTheGap(tv: Boolean = false) {
        if (GlobalContext.getOrNull() != null) stopKoin()
        val accounts = AccountStore(
            object : TextPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) = Unit
            }
        )
        val engine = OfflineEngine(
            compose.activity,
            accounts,
            { ClientIdentity("Test", "install-id", "1") },
            object : OfflineForeground {
                override fun update(activeTitles: List<String>) = Unit
            },
            scope = CoroutineScope(StandardTestDispatcher())
        )
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        startKoin {
            modules(
                module {
                    single { engine }
                    single { DeviceIdentityStore(store) }
                }
            )
        }
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") {}
                if (tv) {
                    offlineTvScreens(nav, { account }, { error("no player in the gap") }, { musicEnded++ })
                } else {
                    offlineTouchScreens(nav, { account }, { error("no player in the gap") }, { musicEnded++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun holdsThroughTheGap(route: String) {
        inTheGap()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, compose.activity.requestedOrientation)
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    @After fun tearDown() {
        stopKoin()
    }

    /** Video and music never share the speakers (MUS-11), and the gap before the player is already video's. */
    private fun endsMusicBeforeThePlayerExists(route: String, tv: Boolean = false) {
        inTheGap(tv)
        assertEquals(0, musicEnded)
        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()
        assertEquals(1, musicEnded)
    }

    @Test fun theDownloadedPlayersRouteEndsMusic() = endsMusicBeforeThePlayerExists("offline-player?entry=${Uri.encode("e2:original")}")

    @Test fun theStreamRouteEndsMusic() = endsMusicBeforeThePlayerExists("online-player?entry=${Uri.encode("e1:original")}&start=0")

    @Test fun theTvDownloadedPlayersRouteEndsMusic() =
        endsMusicBeforeThePlayerExists("offline-player?entry=${Uri.encode("e2:original")}", tv = true)

    @Test fun theTvStreamRouteEndsMusic() = endsMusicBeforeThePlayerExists(
        "online-player?entry=${Uri.encode("e1:original")}&start=0",
        tv = true
    )

    @Test fun theDownloadedPlayersRouteHoldsTheRotationBeforeItsPlayerExists() =
        holdsThroughTheGap("offline-player?entry=${Uri.encode("e2:original")}")

    @Test fun theStreamRouteHoldsTheRotationBeforeItsPlayerExists() = holdsThroughTheGap(
        "online-player?entry=${Uri.encode("e1:original")}&start=0"
    )
}
