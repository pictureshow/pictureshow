/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.*
import java.awt.Robot
import java.io.File
import javax.swing.SwingUtilities
interface WindowNative:Library {fun ps_window_find(title:String):Pointer?;fun ps_window_activate(ptr:Pointer);fun ps_window_info(ptr:Pointer):String;fun ps_window_toggle(ptr:Pointer);fun ps_window_desktop_chrome(ptr:Pointer):Int}
/** Isolate Compose placement from mpv, JOGL and the prototype's direct JDK extension calls. */
fun windowMain(args:Array<String>) {
 val method=args[0];val out=File(args[1]).apply{mkdirs()}
 val native=Native.load(System.getenv("SPIKE_WINDOW_BRIDGE") ?: System.getProperty("spike.window.bridge"),WindowNative::class.java)
 application {
 val state=rememberWindowState(width=740.dp,height=500.dp)
 Window(onCloseRequest={File(out,"close-request.txt").writeText("AWT window closing delivered\n");exitApplication()},title="Pictureshow fullscreen isolation",state=state,undecorated=method in setOf("borderless","chrome"),transparent=method=="chrome") {
  MaterialTheme(colors=darkColors()) {Box(Modifier.fillMaxSize().background(Color(0xFF174E72))){Text("Pictureshow fullscreen isolation · $method",Modifier.padding(24.dp))}}
  LaunchedEffect(Unit){Thread {Thread.sleep(90000);Runtime.getRuntime().halt(3)}.apply{isDaemon=true}.start();Thread {
   try {
    Thread.sleep(1800)
    fun stage(name:String){Thread.sleep(1800);val ptr=requireNotNull(native.ps_window_find("Pictureshow fullscreen isolation"))
     File(out,"$name-native.json").writeText(native.ps_window_info(ptr))
     SwingUtilities.invokeAndWait{val p=window.locationOnScreen;val b=window.bounds
      File(out,"$name-awt.txt").writeText("bounds=$b;screen=${window.graphicsConfiguration.bounds};placement=${state.placement};undecorated=${window.isUndecorated}\n")
      jpeg(Robot().createScreenCapture(java.awt.Rectangle(p.x,p.y,b.width,b.height)),File(out,"$name.jpg"))
     }
    }
    if(method=="chrome") {
     val ptr=requireNotNull(native.ps_window_find("Pictureshow fullscreen isolation"))
     check(native.ps_window_desktop_chrome(ptr)==1)
     SwingUtilities.invokeAndWait { state.position=WindowPosition.Absolute(40.dp,40.dp) }
     for((w,h) in listOf(1280 to 800,1920 to 1080,1280 to 800)) {
      SwingUtilities.invokeAndWait { state.size=androidx.compose.ui.unit.DpSize(w.dp,h.dp) }
      stage("chrome-${w}x${h}-${System.nanoTime()}")
     }
     File(out,"complete.txt").writeText("native controls resize sequence complete\n")
     val bounds=window.bounds
     Robot().apply { mouseMove(bounds.x+21,bounds.y+26);mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
     Thread.sleep(4000)
     check(File(out,"close-request.txt").exists()) { "Native button did not deliver AWT close request" }
    }
    if(method.contains("active")) native.ps_window_activate(requireNotNull(native.ps_window_find("Pictureshow fullscreen isolation")))
    stage("01-before")
    fun toggle(){
     if(method.startsWith("native")){native.ps_window_toggle(requireNotNull(native.ps_window_find("Pictureshow fullscreen isolation")));return}
     SwingUtilities.invokeAndWait {
     when(method){
      "jdk" -> {val u=Class.forName("com.apple.eawt.FullScreenUtilities");u.getMethod("setWindowCanFullScreen",java.awt.Window::class.java,java.lang.Boolean.TYPE).invoke(null,window,true)
       val c=Class.forName("com.apple.eawt.Application");c.getMethod("requestToggleFullScreen",java.awt.Window::class.java).invoke(c.getMethod("getApplication").invoke(null),window)}
      "native" -> native.ps_window_toggle(requireNotNull(native.ps_window_find("Pictureshow fullscreen isolation")))
      else -> state.placement=if(state.placement==WindowPlacement.Fullscreen)WindowPlacement.Floating else WindowPlacement.Fullscreen
     }
    }}
    toggle();Thread.sleep(2500);stage("02-fullscreen");toggle();Thread.sleep(2500);stage("03-restored")
    File(out,"complete.txt").writeText("sequence complete; geometry requires inspection\n");kotlin.system.exitProcess(0)
   }catch(e:Throwable){File(out,"failure.txt").writeText(e.stackTraceToString());kotlin.system.exitProcess(2)}
  }.start()}
 }
 }
}
