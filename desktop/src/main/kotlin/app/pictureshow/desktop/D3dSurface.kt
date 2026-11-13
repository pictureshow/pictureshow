/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.*
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface VideoSurface : AutoCloseable { var width: Int; var height: Int }
interface D3dBridge : Library {
    fun pictureshow_d3d_create(mpv: Pointer, software: Int): Pointer?
    fun pictureshow_d3d_frame(surface: Pointer, width: Int, height: Int, pixels: Pointer): Int
    fun pictureshow_d3d_close(surface: Pointer)
}
/** Same SDR readback probe as GL, using current libmpv's D3D11 API on Windows. */
class D3dSurface(mpv: Mpv, publish: (ImageBitmap) -> Unit, onFailure: (Throwable) -> Unit) : VideoSurface {
    @Volatile override var width = 640
    @Volatile override var height = 360
    private val api = Native.load(requireNotNull(System.getenv("SPIKE_D3D_BRIDGE")), D3dBridge::class.java)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mpv-d3d").apply { isDaemon = true } }
    private val handle = executor.submit<Pointer> {
        requireNotNull(api.pictureshow_d3d_create(mpv.handle, if (System.getenv("SPIKE_SOFTWARE_GPU") == "1") 1 else 0)) { "D3D11 renderer creation failed" }
    }.get(30, TimeUnit.SECONDS)
    @Volatile private var broken = false
    private val animator = executor.scheduleWithFixedDelay({
        if (!broken) try {
            val w = width.coerceIn(64, 1280); val h = height.coerceIn(64, 720)
            Memory((w * h * 4).toLong()).use { pixels ->
                mpv.check(api.pictureshow_d3d_frame(handle, w, h, pixels))
                val bytes = pixels.getByteArray(0, w * h * 4)
                val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val row = IntArray(w)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = (y * w + x) * 4
                        row[x] = (255 shl 24) or ((bytes[i].toInt() and 255) shl 16) or
                            ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i + 2].toInt() and 255)
                    }
                    image.setRGB(0, y, w, 1, row, 0, w)
                }
                publish(image.toComposeImageBitmap())
            }
        } catch (e: Throwable) { broken = true; onFailure(e) }
    }, 0, 33, TimeUnit.MILLISECONDS)
    override fun close() {
        animator.cancel(false)
        executor.execute { api.pictureshow_d3d_close(handle) }
        executor.shutdown()
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "D3D11 teardown did not finish" }
    }
}
