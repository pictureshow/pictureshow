/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

interface NativeLayer : Library {
    fun ps_find_window(title: String): Pointer?
    fun ps_create(view: Pointer, mpv: Pointer): Pointer?
    fun ps_frame(surface: Pointer, width: Int, height: Int): Int
    fun ps_receipt(surface: Pointer): String
    fun ps_sync(surface: Pointer)
    fun ps_fullscreen(surface: Pointer, enabled: Int)
    fun ps_click(surface: Pointer, x: Int, y: Int)
    fun ps_destroy(surface: Pointer)
}

/** Separate SDR Compose controls and native HDR video presentation, joined by the OS compositor.
 * No decoded frame traverses BufferedImage, Compose ImageBitmap or JVM memory.
 */
fun layerMain(args: Array<String>) {
    require(args.size >= 2) { "Usage: layer MEDIA EVIDENCE [--sustain=125]" }
    val media=File(args[0]).absolutePath
    val out=File(args[1]).apply { mkdirs() }
    val sustain=args.firstOrNull { it.startsWith("--sustain=") }?.substringAfter('=')?.toInt() ?: 0
    val bridge=Native.load(requireNotNull(System.getenv("SPIKE_LAYER_BRIDGE") ?: System.getProperty("spike.layer.bridge")),NativeLayer::class.java)
    val mpv=Mpv(out)
    val executor=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"native-video") }
    val failure=AtomicReference<Throwable?>(null)
    val stopping=AtomicBoolean(false)
    var surface:Pointer?=null
    var tick:java.util.concurrent.ScheduledFuture<*>?=null
    val pixels=AtomicReference(java.awt.Dimension(960,640))
    val worker=AtomicReference<Thread?>(null)
    var geometryListener:java.awt.event.ComponentListener?=null
    val overlayBounds=AtomicReference<androidx.compose.ui.geometry.Rect?>(null)
    val controlBounds=AtomicReference<androidx.compose.ui.geometry.Rect?>(null)
    Thread { Thread.sleep((sustain+120L)*1000); Runtime.getRuntime().halt(3) }.apply {isDaemon=true}.start()
    application {
        val state=rememberWindowState(width=960.dp,height=640.dp)
        var clicks by remember { mutableStateOf(0) }
        var full by remember { mutableStateOf(false) }
        var restoreBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        fun stop(code:Int=0) {
            if(!stopping.compareAndSet(false,true)) return
            val workerInitiated=Thread.currentThread()===worker.get()
            Thread {
                var rc=code
                try {
                    worker.get()?.let { if(!workerInitiated) it.interrupt(); it.join(35000); check(!it.isAlive) { "Probe worker failed to stop" } }
                    tick?.cancel(false)
                    executor.submit { surface?.let(bridge::ps_destroy); surface=null }.get(30,TimeUnit.SECONDS)
                    executor.shutdown(); mpv.close()
                    failure.get()?.let { throw it }
                    File(out,"teardown.txt").writeText("native render freed before mpv destroy; executor stopped\n")
                    if(rc==0) File(out,"sequence.txt").writeText("overlayClicks=$clicks\n")
                } catch(e:Throwable) { File(out,"failure.txt").writeText(e.stackTraceToString()); rc=2 }
                kotlin.system.exitProcess(rc)
            }.start()
        }
        Window(onCloseRequest={stop()},state=state,title="Pictureshow native HDR surface",undecorated=true,transparent=true) {
            fun toggleFullscreen() {
                val enabled=!full
                run {
                    // Borderless desktop fullscreen. Native window/screen bounds prove it;
                    // it deliberately does not depend on the failing AppKit Space request.
                    val bounds=if(enabled) { restoreBounds=java.awt.Rectangle(window.bounds); window.graphicsConfiguration.bounds } else requireNotNull(restoreBounds)
                    state.placement=WindowPlacement.Floating
                    state.position=WindowPosition.Absolute(bounds.x.dp,bounds.y.dp)
                    state.size=androidx.compose.ui.unit.DpSize(bounds.width.dp,bounds.height.dp)
                }
                full=enabled
                surface?.let { bridge.ps_fullscreen(it,if(enabled) 1 else 0) }
            }
            MaterialTheme(colors=darkColors()) {
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.align(Alignment.TopEnd).padding(18.dp).background(Color(0xE0174E72)).padding(8.dp)) {
                        Button(modifier=Modifier.onGloballyPositioned { overlayBounds.set(it.boundsInWindow()) },onClick={ clicks++; mpv.command("cycle","pause") }) { Text("COMPOSE OVER VIDEO · $clicks") }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onGloballyPositioned { controlBounds.set(it.boundsInWindow()) }.background(Color(0xDD141820)).padding(8.dp)) {
                        Button(onClick={mpv.command("cycle","pause")}) {Text("Pause / play")}
                        Spacer(Modifier.width(8.dp))
                        Button(onClick={toggleFullscreen()}) {Text("Fullscreen")}
                        Spacer(Modifier.width(8.dp))
                        Button(onClick={stop()}) {Text("Close")}
                    }
                }
            }
            LaunchedEffect(Unit) {
                Thread {
                    try {
                        Thread.sleep(1800)
                        SwingUtilities.invokeAndWait { if (Platform.isMac()) java.awt.Desktop.getDesktop().requestForeground(true); window.toFront(); window.requestFocus() }
                        mpv.option("vo","libmpv")
                        // Render linear BT.2020 into a float target. 1.0 is the SDR reference white.
                        mpv.option("target-prim",if(Platform.isMac()) "bt.2020" else "bt.709"); mpv.option("target-trc","linear"); mpv.option("target-peak","1000")
                        mpv.option("fbo-format",if(Platform.isMac()) "rgba16f" else "rgba16hf")
                        mpv.option("dither-depth","no") // Float output must not be quantized as an 8-bit window framebuffer.
                        if(Platform.isWindows()) {
                            val shader=File(out,"scrgb-reference.glsl")
                            shader.writeText("//!HOOK OUTPUT\n//!BIND HOOKED\nvec4 hook() { vec4 c = HOOKED_tex(HOOKED_pos); return vec4(c.rgb * (203.0 / 80.0), c.a); }\n")
                            mpv.option("glsl-shaders",shader.absolutePath)
                        }
                        mpv.initialize()
                        val view=if(Platform.isMac()) requireNotNull(bridge.ps_find_window("Pictureshow native HDR surface")) else Native.getWindowPointer(window)
                        executor.submit { surface=requireNotNull(bridge.ps_create(view,mpv.handle)) {"Native layer creation failed"} }.get(40,TimeUnit.SECONDS)
                        fun sync() {
                            val update=Runnable {
                            if(stopping.get()) return@Runnable
                            val transform=window.graphicsConfiguration.defaultTransform
                            pixels.set(java.awt.Dimension((window.width*transform.scaleX).toInt(),(window.height*transform.scaleY).toInt()))
                            bridge.ps_sync(surface!!)
                            }
                            if(SwingUtilities.isEventDispatchThread()) update.run() else SwingUtilities.invokeAndWait(update)
                        }
                        SwingUtilities.invokeAndWait {
                            geometryListener=object:java.awt.event.ComponentAdapter() {
                                override fun componentMoved(e:java.awt.event.ComponentEvent) {sync()}
                                override fun componentResized(e:java.awt.event.ComponentEvent) {sync()}
                            }
                            window.addComponentListener(geometryListener)
                        }
                        sync()
                        tick=executor.scheduleWithFixedDelay({try {
                            val size=pixels.get(); val rc=bridge.ps_frame(surface!!,size.width,size.height); check(rc>=0) {"Native frame failed: $rc"}
                        }catch(e:Throwable){failure.compareAndSet(null,e); tick?.cancel(false)}},0,1,TimeUnit.MILLISECONDS)
                        mpv.command("loadfile",media)
                        Thread.sleep(4500)
                        fun stage(name:String) {
                            sync(); Thread.sleep(1000); failure.get()?.let {throw it}
                            val deadline=System.nanoTime()+15_000_000_000L
                            fun layoutReady():Boolean {
                                val size=pixels.get(); val top=overlayBounds.get() ?: return false
                                val bar=controlBounds.get() ?: return false
                                return top.right<=size.width+2 && kotlin.math.abs(bar.width-size.width)<3 && kotlin.math.abs(bar.bottom-size.height)<3
                            }
                            while(!layoutReady() && System.nanoTime()<deadline) Thread.sleep(100)
                            File(out,"$name-layout.txt").writeText("ready=${layoutReady()}; pixels=${pixels.get()}; overlay=${overlayBounds.get()}; controls=${controlBounds.get()}\n")
                            File(out,"$name-native.json").writeText(bridge.ps_receipt(surface!!))
                            SwingUtilities.invokeAndWait {
                                val p=window.locationOnScreen; val b=window.bounds; val screen=window.graphicsConfiguration.bounds
                                jpeg(Robot().createScreenCapture(java.awt.Rectangle(p.x,p.y,b.width,b.height)),File(out,"$name.jpg"))
                                mpv.dump(name,"window=$b; screen=$screen; undecorated=${window.isUndecorated}; placement=${state.placement}; overlayClicks=$clicks")
                            }
                        }
                        stage("01-window")
                        SwingUtilities.invokeAndWait {overlayBounds.get()?.let { b ->
                            val origin=window.contentPane.locationOnScreen; val d=window.graphicsConfiguration.defaultTransform
                            Robot().apply { mouseMove(origin.x+(b.center.x/d.scaleX).toInt(),origin.y+(b.center.y/d.scaleY).toInt()); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }}
                        stage("02-global-click")
                        if(clicks==0 && Platform.isMac()) {
                            overlayBounds.get()?.let {b -> val d=window.graphicsConfiguration.defaultTransform
                                bridge.ps_click(surface!!,(b.center.x/d.scaleX).toInt(),(b.center.y/d.scaleY).toInt()) }
                            stage("02-appkit-click")
                        }
                        if(clicks==0) {
                            SwingUtilities.invokeAndWait {overlayBounds.get()?.let { b ->
                                val d=window.graphicsConfiguration.defaultTransform; val x=(b.center.x/d.scaleX).toInt(); val y=(b.center.y/d.scaleY).toInt()
                                val target=SwingUtilities.getDeepestComponentAt(window.contentPane,x,y)!!
                                val p=SwingUtilities.convertPoint(window.contentPane,x,y,target)
                                for(id in listOf(MouseEvent.MOUSE_PRESSED,MouseEvent.MOUSE_RELEASED)) target.dispatchEvent(MouseEvent(target,id,System.currentTimeMillis(),0,p.x,p.y,1,false,MouseEvent.BUTTON1))
                            }}
                            stage("02-local-click")
                        }
                        check(clicks>0) {"Compose overlay did not receive input"}
                        mpv.set("pause","no")
                        SwingUtilities.invokeAndWait {state.size=androidx.compose.ui.unit.DpSize(740.dp,500.dp)}
                        stage("03-resize")
                        SwingUtilities.invokeAndWait {toggleFullscreen()}
                        Thread.sleep(2500); stage("04-fullscreen")
                        SwingUtilities.invokeAndWait { check(window.isUndecorated && window.bounds==window.graphicsConfiguration.bounds) {"Actual fullscreen bounds differ from screen"} }
                        SwingUtilities.invokeAndWait {toggleFullscreen()}
                        Thread.sleep(2000); stage("05-restored")
                        val tracks=mpv.get("track-list/count")!!.toInt()
                        val subtitles=(0 until tracks).filter {mpv.get("track-list/$it/type")=="sub"}
                        if(sustain==0) check(subtitles.size==3) {"Expected SRT, ASS and PGS tracks"}
                        for((n,track) in subtitles.withIndex()) {
                            mpv.command("loadfile",media); Thread.sleep(500)
                            mpv.set("sid",mpv.get("track-list/$track/id")!!); mpv.set("pause","no")
                            stage("06-sub-${n+1}")
                            check(mpv.get("track-list/$track/selected")=="yes") {"Subtitle did not select"}
                        }
                        if(sustain>0) {
                            mpv.set("loop-file","no"); mpv.command("loadfile",media); Thread.sleep(1000)
                            mpv.command("seek","0","absolute+exact"); mpv.set("pause","no"); stage("07-sustain-start")
                            val startPos=mpv.get("time-pos")!!.toDouble()
                            val progress=File(out,"sustain-progress.tsv").apply {writeText("elapsed\ttime-pos\tframe-drops\tdecoder-drops\n")}
                            val started=System.nanoTime()
                            while((System.nanoTime()-started)/1e9 < sustain) { Thread.sleep(1000); failure.get()?.let{throw it}
                                progress.appendText("${(System.nanoTime()-started)/1e9}\t${mpv.get("time-pos")}\t${mpv.get("frame-drop-count")}\t${mpv.get("decoder-frame-drop-count")}\n")
                            }
                            check(mpv.get("time-pos")!!.toDouble()-startPos>sustain-4) {"Playback did not advance in real time"}
                            check(mpv.get("eof-reached")!="yes") {"Fixture ended during sustained playback"}
                            File(out,"sustained-seconds.txt").writeText(((System.nanoTime()-started)/1e9).toString())
                            stage("08-sustain-end")
                        }
                        stop()
                    } catch(e:Throwable) {if(!stopping.get()) {failure.compareAndSet(null,e); e.printStackTrace(); stop(2)}}
                    finally { SwingUtilities.invokeLater {geometryListener?.let(window::removeComponentListener)} }
                }.also {worker.set(it);it.start()}
            }
        }
    }
}
