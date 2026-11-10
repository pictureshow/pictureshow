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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Linux GLX presentation experiment. Xvfb runs prove no physical display or HDR capability. */
fun canvasMain(args: Array<String>) {
    require(com.sun.jna.Platform.isLinux()) {"The GLX canvas probe requires Linux"}
    require(args.size >= 2) { "Usage: canvas MEDIA EVIDENCE [--auto] [--sustain=125]" }
    val media=File(args[0]).absolutePath
    val out=File(args[1]).apply { mkdirs() }
    val sustain=args.firstOrNull {it.startsWith("--sustain=")}?.substringAfter('=')?.toInt() ?: 0
    val auto=args.contains("--auto")
    require(sustain == 0 || sustain >= 125)
    require(sustain == 0 || auto) {"A sustained probe requires --auto"}
    val mpv=Mpv(out)
    mpv.option("loop-file", "no")
    val failure=AtomicReference<Throwable?>(null)
    val stopping=AtomicBoolean(false)
    val worker=AtomicReference<Thread?>(null)
    var video:GlCanvasSurface?=null
    Thread { Thread.sleep((sustain+120L)*1000); Runtime.getRuntime().halt(3) }.apply {isDaemon=true}.start()
    application {
        val state=rememberWindowState(width=960.dp,height=640.dp)
        var clicks by remember { mutableStateOf(0) }
        var fullscreen by remember { mutableStateOf(false) }
        var floating:Rectangle?=null
        fun stop(code:Int=0) {
            if (!stopping.compareAndSet(false,true)) return
            Thread {
                var rc=code
                try {
                    worker.get()?.let {it.interrupt();it.join(30000);check(!it.isAlive){"Probe worker did not stop"}}
                    video?.close()
                    mpv.close()
                    failure.get()?.let {throw it}
                    File(out,"teardown.txt").writeText("probe worker joined; GL renderer freed before mpv destroy\n")
                    if(rc==0) File(out,"sequence.txt").writeText("overlayClicks=$clicks\n")
                }catch(e:Throwable){File(out,"failure.txt").writeText(e.stackTraceToString());rc=2}
                kotlin.system.exitProcess(rc)
            }.start()
        }
        Window(onCloseRequest={stop()},state=state,title="Pictureshow Linux GLCanvas probe",undecorated=true) {
            fun toggleFullscreen() {
                if (!fullscreen) {
                    floating=Rectangle(window.bounds)
                    window.graphicsConfiguration.device.fullScreenWindow=window
                    window.bounds=window.graphicsConfiguration.bounds
                } else {
                    window.graphicsConfiguration.device.fullScreenWindow=null
                    floating?.let {window.bounds=it}
                }
                fullscreen=!fullscreen
            }
            MaterialTheme(colors=darkColors()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SwingPanel(factory={GlCanvasSurface(mpv,media,{failure.compareAndSet(null,it)}).also{video=it}.panel},modifier=Modifier.fillMaxSize())
                    Popup(alignment=Alignment.TopEnd,offset=IntOffset(-16,16),properties=PopupProperties(focusable=false)) {
                        Button(onClick={if(!stopping.get()){clicks++;mpv.command("cycle","pause")}}) {Text("COMPOSE OVER VIDEO · $clicks")}
                    }
                    Popup(alignment=Alignment.BottomCenter,offset=IntOffset(0,-16),properties=PopupProperties(focusable=false)) {
                        Row(Modifier.background(Color(0xDD141820)).padding(8.dp)) {
                            Button(onClick={if(!stopping.get()) mpv.command("cycle","pause")}) {Text("Pause / play")}
                            Spacer(Modifier.width(8.dp))
                            Button(onClick={toggleFullscreen()}) {Text("Fullscreen")}
                            Spacer(Modifier.width(8.dp))
                            Button(onClick={stop()}) {Text("Close")}
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                if(!auto) return@LaunchedEffect
                val thread=Thread {
                    try {
                        fun alive(){check(!stopping.get()){ "Probe is stopping" };failure.get()?.let {throw it}}
                        fun ready() {
                            val deadline=System.nanoTime()+20_000_000_000L
                            while (mpv.get("vo-configured")!="yes" || mpv.get("time-pos")==null) {
                                alive();check(System.nanoTime()<deadline){"Video did not become ready"};Thread.sleep(100)
                            }
                        }
                        fun stage(name:String) {
                            Thread.sleep(1200);alive()
                            SwingUtilities.invokeAndWait {
                                val b=window.bounds; val p=window.locationOnScreen; val screen=window.graphicsConfiguration.bounds
                                jpeg(Robot().createScreenCapture(Rectangle(p.x,p.y,b.width,b.height)),File(out,"$name.jpg"))
                                File(out,"$name-geometry.txt").writeText("window=$b\nscreen=$screen\nundecorated=${window.isUndecorated}\nfullscreen=$fullscreen\noverlayClicks=$clicks\n")
                            }
                            mpv.dump(name,"Linux GLCanvas; fullscreen=$fullscreen; overlayClicks=$clicks")
                            File(out,"$name-render.txt").writeText("renderCalls=${video?.renderCalls?.get()}\nqueuedNewFrameObservations=${video?.queuedNewFrameObservations?.get()}\nperFrameCPUReadbacks=0\n")
                        }
                        ready();stage("01-window")
                        SwingUtilities.invokeAndWait {
                            val candidates=java.awt.Window.getWindows().filter {it!==window && it.isVisible && it.width>50 && it.width<window.width}
                            check(candidates.size==2){"Expected two Compose control popups; found ${candidates.size}"}
                            val p=candidates.minBy {it.locationOnScreen.y}
                            val origin=p.locationOnScreen
                            Robot().apply {mouseMove(origin.x+p.width/2,origin.y+p.height/2);mousePress(InputEvent.BUTTON1_DOWN_MASK);mouseRelease(InputEvent.BUTTON1_DOWN_MASK)}
                        }
                        stage("02-popup-click");check(clicks>0){"Compose popup did not receive real pointer input"}
                        mpv.set("pause","no")
                        SwingUtilities.invokeAndWait {state.size=androidx.compose.ui.unit.DpSize(740.dp,500.dp)}
                        stage("03-resize")
                        SwingUtilities.invokeAndWait {toggleFullscreen()}
                        stage("04-fullscreen")
                        SwingUtilities.invokeAndWait {check(window.isUndecorated && window.bounds==window.graphicsConfiguration.bounds){"Native fullscreen bounds do not match screen"};toggleFullscreen()}
                        stage("05-restored")
                        SwingUtilities.invokeAndWait {check(window.bounds==floating){"Floating bounds were not restored"}}
                        if (sustain==0) {
                            check(mpv.get("video-params/w")=="640"){"Expected 640-pixel fixture"}
                            val count=mpv.get("track-list/count")!!.toInt()
                            val subs=(0 until count).filter{mpv.get("track-list/$it/type")=="sub"}
                            check(subs.size==3){"Expected SRT, ASS and PGS tracks, got $subs"}
                            val codecs=subs.map {mpv.get("track-list/$it/codec")}.toSet()
                            check(codecs==setOf("subrip","ass","hdmv_pgs_subtitle")){"Unexpected subtitle codecs: $codecs"}
                            for((n,track) in subs.withIndex()) {
                                val sid=mpv.get("track-list/$track/id")!!
                                mpv.command("loadfile",media);Thread.sleep(500);ready()
                                mpv.set("sid",sid);mpv.set("pause","no");stage("06-sub-${n+1}")
                                check(mpv.get("sid")==sid && mpv.get("track-list/$track/selected")=="yes"){"Subtitle not selected: $sid"}
                            }
                        } else {
                            SwingUtilities.invokeAndWait {toggleFullscreen()}
                            Thread.sleep(1000)
                            mpv.set("pause","yes");mpv.command("seek","0","absolute+exact");Thread.sleep(500)
                            check(mpv.get("video-params/w")=="1920" && mpv.get("video-params/h")=="1080"){"Expected 1920x1080 fixture"}
                            check(kotlin.math.abs(mpv.get("container-fps")!!.toDouble()-60.0)<0.01){"Expected 60fps fixture"}
                            val startFrames=video!!.queuedNewFrameObservations.get();val start=System.nanoTime()
                            val first=mpv.get("time-pos")!!.toDouble()
                            check(first<1.0){"Seek to soak origin did not complete: $first"}
                            mpv.set("pause","no")
                            File(out,"progress.tsv").bufferedWriter().use {log ->
                                log.write("elapsed\ttime-pos\tqueuedNewFrameObservations\trenderCalls\tvoDrops\tdecoderDrops\n")
                                repeat(sustain) {
                                    Thread.sleep(1000);alive();check(mpv.get("eof-reached")!="yes"){"EOF during soak"}
                                    log.write("${(System.nanoTime()-start)/1e9}\t${mpv.get("time-pos")}\t${video!!.queuedNewFrameObservations.get()-startFrames}\t${video!!.renderCalls.get()}\t${mpv.get("frame-drop-count")}\t${mpv.get("decoder-frame-drop-count")}\n");log.flush()
                                }
                            }
                            val elapsed=(System.nanoTime()-start)/1e9;val advanced=mpv.get("time-pos")!!.toDouble()-first
                            check(elapsed>=125 && advanced>=elapsed-3){"Playback stalled: elapsed=$elapsed advanced=$advanced"}
                            File(out,"sustain.txt").writeText("seconds=$elapsed\nmediaAdvance=$advanced\nqueuedNewFrameObservations=${video!!.queuedNewFrameObservations.get()-startFrames}\n")
                            stage("07-sustain-end")
                        }
                        // Run shutdown off the worker so it can join this thread before freeing native objects.
                        Thread {stop()}.start()
                    } catch(e:Throwable) {
                        if(!stopping.get()){failure.compareAndSet(null,e);Thread {stop(2)}.start()}
                    }
                }
                worker.set(thread);thread.start()
            }
        }
    }
}
