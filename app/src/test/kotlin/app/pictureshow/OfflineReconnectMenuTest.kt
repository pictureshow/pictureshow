/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class OfflineReconnectMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val entry = OfflineEntry(
        "i:original",
        "i",
        OfflineMediaKind.movie,
        preset = OfflinePreset.original,
        labels = OfflineLabels("Film"),
        resources = listOf(
            OfflineResource("media", OfflineResourceRole.media, "media.mkv", complete = true),
            OfflineResource("sub3", OfflineResourceRole.subtitle, "s3.vtt", complete = true),
            OfflineResource("sub5", OfflineResourceRole.subtitle, "s5.vtt", complete = false)
        ),
        tracks = listOf(
            OfflineTrack(1, "audio", "English", "eng", codec = "aac"),
            OfflineTrack(6, "audio", "Japanese", "jpn", codec = "aac", available = false),
            OfflineTrack(3, "subtitle", "English", "eng", codec = "subrip", resourceId = "sub3"),
            OfflineTrack(4, "subtitle", "Spanish", "spa", codec = "subrip", available = false),
            OfflineTrack(5, "subtitle", "German", "ger", codec = "subrip", resourceId = "sub5")
        )
    )

    private fun menus() =
        preparePlayerTrackMenus(offlineSourceInfo(entry, 1) { File("/x/$it") }.mediaStreams, offlineUndownloadedStreams(entry))

    @Test fun `undownloaded rows say so plainly and follow the rows that play`() {
        assertEquals(listOf(-1 to "Off", 1 to "English · AAC", 6 to "Japanese · AAC · needs a connection"), menus().audio.choices)
        assertEquals(setOf(6), menus().audio.needsConnection)
    }

    @Test fun `a caption whose file never finished is one row, the honest one`() {
        val subtitles = menus().subtitles
        val expected = listOf(
            -1 to "Off",
            3 to "English · SRT · External",
            4 to "Spanish · SRT · needs a connection",
            5 to "German · SRT · External · needs a connection"
        )
        assertEquals(expected, subtitles.choices)
        assertEquals(setOf(4, 5), subtitles.needsConnection)
    }

    @Test fun `undownloaded rows never reach what the decoder is told it can play`() {
        val playable = offlineSourceInfo(entry, 1) { File("/x/$it") }.mediaStreams.map { it.index }
        assertFalse(6 in playable)
        assertFalse(4 in playable)
        assertEquals(listOf(6, 4, 5), offlineUndownloadedStreams(entry).map { it.index })
        val types = offlineUndownloadedStreams(entry).map { it.type }
        assertEquals(listOf(MediaStreamType.Audio, MediaStreamType.Subtitle, MediaStreamType.Subtitle), types)
    }

    @Test fun `an online player's menus are exactly what they were`() {
        val streams = listOf(MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", language = "eng"))
        val menu = preparePlayerTrackMenus(streams).audio
        assertEquals(listOf(-1 to "Off", 1 to "English · AAC"), menu.choices)
        assertTrue(menu.needsConnection.isEmpty())
    }

    @Test fun `every playing route owns the whole touch screen and nothing else does`() {
        val playing = listOf("player?item={item}&start={start}", "offline-player?entry={entry}", OFFLINE_STREAM_ROUTE)
        playing.forEach {
            assertTrue(isPlayerRoute(it), it)
            assertFalse(touchShowsNavigation(it, signedIn = true), it)
        }
        assertTrue(touchShowsNavigation("downloads", signedIn = true))
        listOf("downloads", "home", null).forEach { assertFalse(isPlayerRoute(it)) }
    }

    @Test fun `TV's music bar stays off every playing route, downloaded and streamed too`() {
        listOf("player?item={item}&start={start}", "offline-player?entry={entry}", OFFLINE_STREAM_ROUTE, MUSIC_NOW_ROUTE).forEach {
            assertFalse(tvShowsMusicBar(musicVisible = true, it), it)
        }
        listOf(OFFLINE_DOWNLOADS_ROUTE, "home", null).forEach { assertTrue(tvShowsMusicBar(musicVisible = true, it), it.toString()) }
        assertFalse(tvShowsMusicBar(musicVisible = false, "home"))
    }

    private lateinit var nav: NavHostController
    private var arguments: Triple<Long, String?, String?>? = null
    private var entryId: String? = null

    @Composable private fun Graph() {
        nav = rememberNavController()
        NavHost(nav, "downloads") {
            composable("downloads") {}
            composable("offline-player?entry={entry}") {}
            composable(OFFLINE_STREAM_ROUTE, offlineStreamArguments) { route ->
                entryId = route.arguments?.getString("entry")
                val given = route.arguments
                arguments = Triple(given!!.getLong("start"), given.getString("audio"), given.getString("subtitle"))
            }
        }
    }

    private fun leave(start: OfflineStreamStart) {
        compose.setContent { Graph() }
        compose.runOnIdle {
            nav.navigate(offlinePlayerRoute(entry))
            nav.leaveLocalPlayer(nav.currentBackStackEntry!!, offlineStreamRoute(entry, start))
        }
        compose.waitForIdle()
    }

    @Test fun `the move replaces the local player, so Back lands on Downloads`() {
        leave(OfflineStreamStart(123_000, 6, -1))
        compose.runOnIdle {
            assertEquals("i:original", entryId)
            assertEquals(Triple(123_000L, "6", "-1"), arguments)
            assertEquals(OFFLINE_STREAM_ROUTE, nav.currentDestination?.route)
            assertTrue(nav.popBackStack())
            assertEquals("downloads", nav.currentDestination?.route)
        }
    }

    @Test fun `a track left to the server travels as nothing`() {
        leave(OfflineStreamStart(0, null, 4))
        compose.runOnIdle { assertEquals(Triple(0L, "", "4"), arguments) }
    }
}
