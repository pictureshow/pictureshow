/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.sun.jna.*
import java.io.File
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import org.jetbrains.skia.EncodedImageFormat

private interface WaylandNative:Library {
 fun ps_wayland_create(mpv:Pointer,receipt:String,hdr:Int):Pointer
 fun ps_wayland_pump(ctx:Pointer):Int
 fun ps_wayland_width(ctx:Pointer):Int
 fun ps_wayland_height(ctx:Pointer):Int
 fun ps_wayland_fullscreen(ctx:Pointer):Int
 fun ps_wayland_fullscreen_set(ctx:Pointer,value:Int)
 fun ps_wayland_resize(ctx:Pointer,width:Int,height:Int)
 fun ps_wayland_ui(ctx:Pointer,data:ByteArray,width:Int,height:Int)
 fun ps_wayland_input(ctx:Pointer,event:IntArray):Int
 fun ps_wayland_receipt(ctx:Pointer,path:String)
 fun ps_wayland_test_click(ctx:Pointer,x:Int,y:Int):Int
 fun ps_wayland_capture(ctx:Pointer,path:String):Int
 fun ps_wayland_close(ctx:Pointer)
}

/** Wayland owns video presentation and input; Compose owns controls and hit testing.
 * Only changing SDR UI pixels cross shared memory; decoded video remains on EGL/GL.
 */
fun waylandMain(args:Array<String>) {
 require(Platform.isLinux())
 require(args.size>=2){"Usage: wayland MEDIA EVIDENCE [--sustain=125] [--sdr]"}
 val media=File(args[0]).absolutePath
 val out=File(args[1]).apply{mkdirs()}
 val seconds=args.firstOrNull{it.startsWith("--sustain=")}?.substringAfter('=')?.toInt()?:0
 val hdr=!args.contains("--sdr")
 val native=Native.load(System.getenv("SPIKE_WAYLAND_BRIDGE")?:"pictureshow_wayland",WaylandNative::class.java)
 val mpv=Mpv(out)
 mpv.option("vo","libmpv");mpv.option("loop-file","no");mpv.option("hwdec","auto-safe")
 mpv.option("video-timing-offset","0")
 if(hdr){mpv.option("target-trc","pq");mpv.option("target-prim","bt.2020");mpv.option("target-peak","1000");mpv.option("fbo-format","rgba16f");mpv.option("dither-depth","10")}
 mpv.initialize()
 val context=native.ps_wayland_create(mpv.handle,File(out,"native.txt").absolutePath,if(hdr)1 else 0)
 var clicks by mutableStateOf(0)
 var wantExit=false
 var scene:ImageComposeScene?=null
 var width=0;var height=0
 var uiDirty=true
 fun updateUi(){
  val w=native.ps_wayland_width(context);val h=native.ps_wayland_height(context)
  if(w!=width||h!=height){scene?.close();width=w;height=h;scene=ImageComposeScene(width=w,height=h){
   MaterialTheme(colors=darkColors()){
    Box(Modifier.fillMaxSize()){
     Button(modifier=Modifier.align(Alignment.TopEnd).padding(16.dp),onClick={clicks++;uiDirty=true}){Text("COMPOSE OVER VIDEO · $clicks")}
     Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color(0xDD141820)).padding(8.dp)){
      Button(onClick={mpv.command("cycle","pause");uiDirty=true}){Text("Pause / play")};Spacer(Modifier.width(8.dp))
      Button(onClick={native.ps_wayland_fullscreen_set(context,1-native.ps_wayland_fullscreen(context));uiDirty=true}){Text("Fullscreen")};Spacer(Modifier.width(8.dp))
      Button(onClick={wantExit=true}){Text("Close")}
     }
    }
   }
  };uiDirty=true}
  val sc=scene!!
  if(uiDirty||sc.hasInvalidations()){
   // Read only the CPU-rendered UI image. This is never a video frame/readback.
   sc.render(System.nanoTime()).use{image->
    val bytes=image.encodeToData(EncodedImageFormat.PNG)!!.use{it.bytes}
    val bitmap=ImageIO.read(ByteArrayInputStream(bytes))
    val ints=bitmap.getRGB(0,0,w,h,null,0,w);val pixels=ByteArray(w*h*4)
    for(n in ints.indices){val argb=ints[n];val a=(argb ushr 24) and 255;val k=n*4;pixels[k]=((argb and 255)*a/255).toByte();pixels[k+1]=(((argb ushr 8)and 255)*a/255).toByte();pixels[k+2]=(((argb ushr 16)and 255)*a/255).toByte();pixels[k+3]=a.toByte()}
    native.ps_wayland_ui(context,pixels,w,h)
   }
   uiDirty=false
  }
 }
 fun pump(){
  check(native.ps_wayland_pump(context)>=0){"Native Wayland loop failed"}
  val event=IntArray(3)
  while(native.ps_wayland_input(context,event)>0){scene!!.sendPointerEvent(if(event[0]==1)PointerEventType.Press else PointerEventType.Release,Offset(event[1].toFloat(),event[2].toFloat()));uiDirty=true}
  updateUi();Thread.sleep(1)
 }
 fun waitFor(ms:Long,condition:()->Boolean={false}){val end=System.nanoTime()+ms*1_000_000;while(!condition()&&System.nanoTime()<end&&!wantExit)pump()}
 fun stage(name:String){waitFor(1000);native.ps_wayland_receipt(context,File(out,"$name-native.txt").absolutePath);check(native.ps_wayland_capture(context,File(out,"$name.ppm").absolutePath)==0){"Compositor screenshot failed"};mpv.dump(name,"Wayland actual=${width}x$height; fullscreen=${native.ps_wayland_fullscreen(context)}; overlayClicks=$clicks")}
 try {
  updateUi();mpv.command("loadfile",media)
  waitFor(20000){mpv.get("vo-configured")=="yes"&&mpv.get("time-pos")!=null};check(mpv.get("vo-configured")=="yes")
  if(args.contains("--hdr-numeric")){
   // Dedicated generated fixture gate: no seeking, subtitle assumptions or UI injection.
   // Keep the decoded static frame available after the two-frame file reaches EOF.
   mpv.set("pause","yes")
   native.ps_wayland_fullscreen_set(context,1)
   waitFor(5000){native.ps_wayland_fullscreen(context)==1}
   check(native.ps_wayland_fullscreen(context)==1)
   stage("numeric")
   check(mpv.get("video-params/primaries") == if(media.endsWith("pq.mkv")) "bt.2020" else "bt.709")
   File(out,"sequence.txt").writeText("numeric=true\nactualCompositorFramebuffer=true\n")
   return
  }
  stage("01-window")
  mpv.set("pause","yes");native.ps_wayland_resize(context,740,500);stage("03-resize");check(mpv.get("pause")=="yes");mpv.set("pause","no")
  native.ps_wayland_fullscreen_set(context,1);waitFor(5000){native.ps_wayland_fullscreen(context)==1};stage("04-fullscreen")
  check(native.ps_wayland_fullscreen(context)==1)
  check(native.ps_wayland_test_click(context,width-180,36)==0){"Weston input test seat required"};waitFor(1200);stage("04b-fullscreen-click");check(clicks==1){"Real Wayland pointer did not reach Compose"};check(native.ps_wayland_fullscreen(context)==1){"Pointer reverted fullscreen"}
  native.ps_wayland_fullscreen_set(context,0);waitFor(5000){native.ps_wayland_fullscreen(context)==0}
  native.ps_wayland_resize(context,740,500);stage("05-restored");check(width==740&&height==500)
  if(seconds>0){
   require(seconds>=125)
   native.ps_wayland_fullscreen_set(context,1);waitFor(5000){native.ps_wayland_fullscreen(context)==1}
   mpv.set("pause","yes");mpv.command("seek","0","absolute+exact");waitFor(1500)
   check(mpv.get("video-params/w")=="1920"&&mpv.get("video-params/h")=="1080")
   check(kotlin.math.abs(mpv.get("container-fps")!!.toDouble()-60)<0.01)
   fun counter(name:String)=checkNotNull(mpv.get(name)?.toLongOrNull())
   val vo=counter("frame-drop-count");val dec=counter("decoder-frame-drop-count");val first=mpv.get("time-pos")!!.toDouble();check(first<1)
   native.ps_wayland_receipt(context,File(out,"soak-start-native.txt").absolutePath)
   mpv.dump("soak-start","Counters baselined before unpause")
   mpv.set("pause","no");val start=System.nanoTime();val end=start+seconds*1_000_000_000L;var next=start
   File(out,"progress.tsv").bufferedWriter().use{log->
    log.write("elapsed\tmedia\tvoDrops\tdecoderDrops\n")
    while(System.nanoTime()<end){pump();if(System.nanoTime()>=next){check(mpv.get("eof-reached")!="yes");log.write("${(System.nanoTime()-start)/1e9}\t${mpv.get("time-pos")}\t${counter("frame-drop-count")-vo}\t${counter("decoder-frame-drop-count")-dec}\n");log.flush();next+=1_000_000_000L}}
   }
   native.ps_wayland_receipt(context,File(out,"soak-end-native.txt").absolutePath)
   mpv.dump("soak-end","End counters before settling/screenshot")
   val elapsed=(System.nanoTime()-start)/1e9;val advanced=mpv.get("time-pos")!!.toDouble()-first
   File(out,"sustain.txt").writeText("seconds=$elapsed\nmediaAdvance=$advanced\nvoDrops=${counter("frame-drop-count")-vo}\ndecoderDrops=${counter("decoder-frame-drop-count")-dec}\n")
   check(advanced>=elapsed-3){"Media stalled"};stage("07-sustain-end")
  }else{
   val tracks=(0 until mpv.get("track-list/count")!!.toInt()).filter{mpv.get("track-list/$it/type")=="sub"};check(tracks.size==3)
   check(tracks.map{mpv.get("track-list/$it/codec")}.toSet()==setOf("subrip","ass","hdmv_pgs_subtitle"))
   for((n,t)in tracks.withIndex()){
    mpv.command("loadfile",media);waitFor(1000)
    val sid=mpv.get("track-list/$t/id")!!;mpv.set("sid",sid);stage("06-sub-${n+1}")
    check(mpv.get("sid")==sid&&mpv.get("track-list/$t/selected")=="yes"){"Subtitle track not selected: $sid"}
   }
  }
  File(out,"sequence.txt").writeText("overlayClicks=$clicks\n")
 }catch(e:Throwable){File(out,"failure.txt").writeText(e.stackTraceToString());throw e}
 finally{scene?.close();native.ps_wayland_close(context);mpv.close();File(out,"teardown.txt").writeText("Compose scene closed; Wayland EGL render context freed before mpv destroy\n")}
}
