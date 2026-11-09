/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerTrackMenuPreparationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val subtitle = MediaStream(type = MediaStreamType.Subtitle, index = 3, language = "en", codec = "srt")
    private val audio = MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", codec = "aac")

    /** A blocked metadata traversal stands in for slow label/ICU work, without a timing benchmark. */
    private class WorkerStreams(private val values: List<MediaStream>) : List<MediaStream> by values {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val traversals = AtomicInteger()

        override fun iterator(): Iterator<MediaStream> {
            check(Thread.currentThread() !== Looper.getMainLooper().thread) { "Track metadata was traversed on the UI thread" }
            traversals.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release the metadata worker" }
            return values.iterator()
        }

        // Compose compares keys on the UI thread; that is not label generation or traversal.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    @Test fun bothMenusPrepareBeforeAnyOpenWithoutBlockingTheUiAndReuseTheirResults() {
        val streams = WorkerStreams((0..43).map { subtitle.copy(index = it + 3) } + audio)
        var source by mutableStateOf("source-a")
        var preparation: PlayerTrackMenuPreparation? = null
        compose.setContent { preparation = rememberPlayerTrackMenus(source, streams) }
        try {
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                streams.entered.count == 0L
            }
            val first = requireNotNull(preparation)
            assertNull(first.menus)
            // The UI must be free to process a new source while metadata work is still blocked.
            compose.runOnIdle { source = "source-b" }
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                preparation !== first
            }
            val replacement = requireNotNull(preparation)
            assertNotSame(first, replacement)
            streams.release.countDown()
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                preparation?.menus != null
            }
            val menus = requireNotNull(preparation?.menus)
            assertEquals(45, menus.subtitles.choices.size)
            assertEquals(listOf(-1 to "Off", 1 to "English · AAC"), menus.audio.choices)
            assertSame(menus.audio, menus[MediaStreamType.Audio])
            assertSame(menus.subtitles, menus[MediaStreamType.Subtitle])
            compose.waitForIdle()
            assertSame(replacement, preparation)
            assertSame(menus, preparation?.menus)
            assertTrue(streams.traversals.get() >= 2)
        } finally {
            streams.release.countDown()
        }
    }

    @Test fun anObsoleteSlowSourceCannotReplaceNewlyPreparedMetadata() {
        val oldStreams = WorkerStreams(listOf(subtitle, audio))
        var streams by mutableStateOf<List<MediaStream>>(oldStreams)
        var source by mutableStateOf("old-source")
        var preparation: PlayerTrackMenuPreparation? = null
        compose.setContent { preparation = rememberPlayerTrackMenus(source, streams) }
        try {
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                oldStreams.entered.count == 0L
            }
            val old = requireNotNull(preparation)
            compose.runOnIdle {
                source = "new-source"
                streams = listOf(subtitle.copy(index = 9, title = "Commentary"), audio)
            }
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                preparation?.menus != null
            }
            val current = requireNotNull(preparation)
            val ready = requireNotNull(current.menus)
            assertEquals("new-source", current.sourceId)
            assertEquals(listOf(-1 to "Off", 9 to "English · SRT · Commentary"), ready.subtitles.choices)
            oldStreams.release.countDown()
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                oldStreams.traversals.get() >= 2
            }
            compose.waitForIdle()
            assertNotSame(old, current)
            assertSame(current, preparation)
            assertSame(ready, preparation?.menus)
        } finally {
            oldStreams.release.countDown()
        }
    }
}
