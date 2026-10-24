/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.Renderer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class EndDeliveryTest {
    @Test
    fun physicalEndRejectedDuringSeekBufferingIsDeliveredAfterReadyAndOnlyOnce() {
        var state = Player.STATE_BUFFERING
        var ends = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayerError" -> null
                else -> null
            }
        } as Player
        val signal = PlaybackEndSignal({ player }) { ends++ }
        val uri = Uri.parse("https://example.test/video")
        val end = PhysicalPlaybackEnd(uri, canEnd = signal::canEnd) { signal.ended("av-drained") }
        val renderer = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
            when (method.name) {
                "getTrackType" -> C.TRACK_TYPE_AUDIO
                "getState" -> Renderer.STATE_STARTED
                "isEnded", "isCurrentStreamFinal" -> true
                else -> null
            }
        } as Renderer
        val wrapped = end.wrap(arrayOf(renderer), Handler(Looper.getMainLooper())).single()
        val source = end.sourceFactory(DataSource.Factory { Body() }).createDataSource()
        source.open(DataSpec(uri))
        source.read(ByteArray(1), 0, 1)
        wrapped.render(60_000_000, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ends, "Pending seeks still suppress physical EOF")
        state = Player.STATE_READY
        wrapped.render(60_000_000, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ends, "Rejected delivery must not permanently consume this source's EOF")
        wrapped.render(60_000_000, 0)
        shadowOf(Looper.getMainLooper()).idle()
        signal.ended()
        assertEquals(1, ends)
        source.close()
    }

    private class Body : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri? = null
        override fun open(dataSpec: DataSpec) = 1L
        override fun read(buffer: ByteArray, offset: Int, length: Int) = 1
        override fun close() = Unit
    }
}
