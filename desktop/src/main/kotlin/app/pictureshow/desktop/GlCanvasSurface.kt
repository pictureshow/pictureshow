/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.jogamp.opengl.util.FPSAnimator
import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

@Structure.FieldOrder("type", "data")
class CanvasFrameParam : Structure(), Structure.ByValue {
    @JvmField var type=11
    @JvmField var data:Pointer?=null
}
@Structure.FieldOrder("flags", "targetTime")
class CanvasFrameInfo : Structure() {
    @JvmField var flags=0L
    @JvmField var targetTime=0L
}
private interface CanvasRenderApi : Library {
    fun mpv_render_context_get_info(ctx:Pointer,param:CanvasFrameParam):Int
    fun mpv_render_context_report_swap(ctx:Pointer)
}

/** Application-owned GLX window. LGPL mpv does not own or implement the X11 VO.
 * Controls use a Compose popup above this heavyweight; pixels never leave the GPU.
 */
class GlCanvasSurface(private val mpv:Mpv, media:String, private val failed:(Throwable)->Unit) : VideoSurface {
    @Volatile override var width=640
    @Volatile override var height=360
    private var context:Pointer?=null
    private val closed=AtomicBoolean(false)
    private var loader:Thread?=null
    val renderCalls=AtomicLong()
    val newVideoFrames=AtomicLong()
    private val frameApi=Native.load(System.getenv("MPV_LIBRARY") ?: "mpv",CanvasRenderApi::class.java)
    private val lib=NativeLibrary.getInstance("GL")
    private val proc=GlProc {_,name->lib.getFunction("glXGetProcAddressARB").invokePointer(arrayOf(name))}
    private val init=GlInit().apply{getProc=proc;write()}
    private val api=cString("opengl")
    val panel=JPanel(BorderLayout())
    private val canvas:GLCanvas
    private val animator:FPSAnimator
    init {
        mpv.option("vo","libmpv"); mpv.initialize()
        GLProfile.initSingleton()
        canvas=GLCanvas(GLCapabilities(GLProfile.get(GLProfile.GL3)))
        canvas.addGLEventListener(object:GLEventListener {
            override fun init(d:GLAutoDrawable) {
                try {
                    val p=renderParams(1 to api,2 to init.pointer);val out=PointerByReference()
                    mpv.check(mpv.api.mpv_render_context_create(out,mpv.handle,p[0].pointer));context=out.value
                    java.io.File(mpv.out,"gl-device.txt").writeText("vendor=${d.gl.glGetString(GL.GL_VENDOR)}\nrenderer=${d.gl.glGetString(GL.GL_RENDERER)}\nversion=${d.gl.glGetString(GL.GL_VERSION)}\nperFrameCPUReadbacks=0\n")
                    loader=Thread {try {if(!closed.get()) mpv.command("loadfile",media)}catch(e:Throwable){failed(e)}}.also {it.start()}
                } catch(e:Throwable){failed(e)}
            }
            override fun display(d:GLAutoDrawable) {
                try {context?.let {ctx->
                    val info=CanvasFrameInfo()
                    val infoParam=CanvasFrameParam().apply {data=info.pointer;write()}
                    mpv.check(frameApi.mpv_render_context_get_info(ctx,infoParam));info.read()
                    val fbo=GlFbo().apply{fbo=0;width=d.surfaceWidth;height=d.surfaceHeight;write()}
                    val p=renderParams(3 to fbo.pointer,4 to intMemory(1))
                    mpv.check(mpv.api.mpv_render_context_render(ctx,p[0].pointer))
                    renderCalls.incrementAndGet()
                    // PRESENT without REDRAW/REPEAT is a new queued video frame, not a present-call count.
                    if(info.flags and 1L != 0L && info.flags and 6L == 0L) newVideoFrames.incrementAndGet()
                }}catch(e:Throwable){failed(e)}
            }
            override fun reshape(d:GLAutoDrawable,x:Int,y:Int,w:Int,h:Int) {}
            override fun dispose(d:GLAutoDrawable) {context?.let(mpv.api::mpv_render_context_free);context=null}
        })
        panel.add(canvas,BorderLayout.CENTER)
        animator=FPSAnimator(canvas,60,true);animator.start()
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        animator.stop()
        loader?.let {it.join(30000);check(!it.isAlive){"Media load thread did not stop"}}
        canvas.destroy()
        check(context==null){"GL render context was not freed"}
    }
}
