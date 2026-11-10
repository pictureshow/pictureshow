/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jogamp.opengl.*
import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface GlProc : Callback { fun invoke(context: Pointer?, name: String): Pointer? }
@Structure.FieldOrder("getProc", "context")
class GlInit : Structure() {
    @JvmField var getProc: GlProc? = null
    @JvmField var context: Pointer? = null
}
@Structure.FieldOrder("fbo", "width", "height", "format")
class GlFbo : Structure() {
    @JvmField var fbo = 0
    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var format = 0
}

/** Deliberately conservative prototype: private GL context -> RGBA readback -> Compose/Skia image.
 * This proves composition/controls, NOT zero-copy performance or HDR presentation.
 * All render API calls stay on one GL thread; client API calls stay off it.
 */
class GlSurface(private val mpv: Mpv, private val publish: (ImageBitmap) -> Unit) : AutoCloseable {
    @Volatile var width = 640
    @Volatile var height = 360
    private var render: Pointer? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mpv-gl").apply { isDaemon = true } }
    private val drawable: GLOffscreenAutoDrawable
    // Keep callbacks and backing memory alive until render context teardown.
    private val glLib = NativeLibrary.getInstance(if (Platform.isMac()) "/System/Library/Frameworks/OpenGL.framework/OpenGL" else if (Platform.isWindows()) "opengl32" else "GL")
    private val proc = GlProc { _, name ->
        val dynamic = if (Platform.isWindows()) "wglGetProcAddress" else if (Platform.isLinux()) "glXGetProcAddressARB" else null
        val p = dynamic?.let { glLib.getFunction(it).invokePointer(arrayOf(name)) }
        p ?: runCatching { glLib.getFunction(name) as Pointer }.getOrNull()
    }
    private val init = GlInit().apply { getProc = proc; write() }
    private val apiName = cString("opengl")
    init {
        GLProfile.initSingleton()
        val profile = GLProfile.get(GLProfile.GL3)
        drawable = GLDrawableFactory.getFactory(profile).createOffscreenAutoDrawable(null, GLCapabilities(profile), null, width, height)
        drawable.addGLEventListener(object : GLEventListener {
            override fun init(d: GLAutoDrawable) {
                val params = renderParams(1 to apiName, 2 to init.pointer)
                val ref = PointerByReference()
                mpv.check(mpv.api.mpv_render_context_create(ref, mpv.handle, params[0].pointer))
                render = ref.value
            }
            override fun display(d: GLAutoDrawable) {
                val ctx = render ?: return
                val gl = d.gl.gL2GL3
                val binding = IntArray(1)
                gl.glGetIntegerv(0x8CA6, binding, 0)
                val fbo = GlFbo().apply { fbo = binding[0]; width = d.surfaceWidth; height = d.surfaceHeight; write() }
                val flip = intMemory(1)
                val params = renderParams(3 to fbo.pointer, 4 to flip)
                mpv.check(mpv.api.mpv_render_context_render(ctx, params[0].pointer))
                val w = d.surfaceWidth; val h = d.surfaceHeight
                val pixels = ByteBuffer.allocateDirect(w * h * 4)
                gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
                val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val row = IntArray(w)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = (y * w + x) * 4
                        row[x] = (255 shl 24) or ((pixels.get(i).toInt() and 255) shl 16) or
                            ((pixels.get(i + 1).toInt() and 255) shl 8) or (pixels.get(i + 2).toInt() and 255)
                    }
                    image.setRGB(0, h - 1 - y, w, 1, row, 0, w)
                }
                publish(image.toComposeImageBitmap())
            }
            override fun reshape(d: GLAutoDrawable, x: Int, y: Int, w: Int, h: Int) {}
            override fun dispose(d: GLAutoDrawable) { render?.let(mpv.api::mpv_render_context_free); render = null }
        })
        // Create renderer before loadfile, per render API contract.
        executor.submit { drawable.display() }.get(30, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({
            try {
                val w = width.coerceIn(64, 1280); val h = height.coerceIn(64, 720)
                if (drawable.surfaceWidth != w || drawable.surfaceHeight != h) drawable.setSurfaceSize(w, h)
                drawable.display()
            } catch (e: Throwable) { e.printStackTrace(); executor.shutdown() }
        }, 0, 33, TimeUnit.MILLISECONDS)
    }
    override fun close() {
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        // JOGL makes the same context current for dispose.
        drawable.destroy()
    }
}
