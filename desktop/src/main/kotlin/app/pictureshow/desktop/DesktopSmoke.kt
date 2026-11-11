/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.unit.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.*
import app.pictureshow.core.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Real window/control smoke. No network fixture, catalog or playback result is synthesized here. */
fun runDesktopSmoke(c:DesktopController,window:ComposeWindow,windowState:WindowState,args:List<String>,close:()->Unit) {
    val (server,user,password,evidence)=args
    val out=File(evidence).apply{mkdirs()}
    val screens=File(out,"screens").apply{mkdirs()}
    val checks=linkedMapOf<String,Boolean>()
    val robot=Robot().apply { autoDelay=45 }
    var successful=false
    fun waitFor(label:String,seconds:Long=45,predicate:()->Boolean) {
        val until=System.nanoTime()+seconds*1_000_000_000L
        while(!predicate()) { check(System.nanoTime()<until) { "SMOKE timed out: $label; route=${c.state.value.route}; busy=${c.state.value.busy}; error=${c.state.value.error != null}" };Thread.sleep(60) }
        Thread.sleep(250)
    }
    fun ready(route:DesktopRoute) = waitFor(route.name) { c.state.value.route==route && !c.state.value.busy }
    fun click(tag:String) {
        waitFor("composed control $tag") { DesktopUiProbe.reveal.containsKey(tag) }
        DesktopUiProbe.reveal[tag]?.invoke()
        Thread.sleep(350)
        waitFor("control $tag") { DesktopUiProbe.bounds[tag]?.let{it.width>0&&it.height>0}==true }
        val b=DesktopUiProbe.bounds.getValue(tag)
        val origin=window.locationOnScreen
        val scale=window.graphicsConfiguration.defaultTransform.scaleX
        // Compose bounds are physical pixels; AWT Robot uses logical desktop coordinates.
        val x=origin.x+(b.center.x/scale).roundToInt();val y=origin.y+(b.center.y/scale).roundToInt()
        robot.mouseMove(x,y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(220)
    }
    fun key(code:Int) {
        if(code==KeyEvent.VK_ESCAPE || code==KeyEvent.VK_F11) {
            val manager=java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            println("SMOKE key=$code activeViewer=${manager.activeWindow===window} focusedViewer=${manager.focusedWindow===window} focusOwner=${manager.focusOwner?.javaClass?.simpleName} defaultInput=${window.focusTraversalPolicy.getDefaultComponent(window)?.javaClass?.simpleName} composeFocus=${DesktopUiProbe.focus.filterValues{it}.keys}")
        }
        robot.keyPress(code);robot.keyRelease(code)
    }
    fun type(value:String) {
        value.forEach { ch ->
            val shifted = ch.isUpperCase() || ch in ":_?+!@#\"<>"
            val base=when(ch) { ':'->';';'_'->'-';'?'->'/';'+'->'=';'!'->'1';'@'->'2';'#'->'3';'"'->'\'';'<'->',';'>'->'.';else->ch.uppercaseChar() }
            val code=KeyEvent.getExtendedKeyCodeForChar(base.code)
            require(code!=KeyEvent.VK_UNDEFINED) { "Unsupported smoke input character" }
            if(shifted) robot.keyPress(KeyEvent.VK_SHIFT)
            key(code)
            if(shifted) robot.keyRelease(KeyEvent.VK_SHIFT)
        }
    }
    fun screenshot(name:String) {
        for((w,h) in listOf(1280 to 800,1920 to 1080)) {
            SwingUtilities.invokeAndWait {
                if(com.sun.jna.Platform.isMac()) java.awt.Desktop.getDesktop().requestForeground(true)
                val wantedX=System.getenv("PICTURESHOW_SMOKE_WINDOW_X")?.toInt() ?: window.x
                val wantedY=System.getenv("PICTURESHOW_SMOKE_WINDOW_Y")?.toInt() ?: window.y
                val display=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.firstOrNull { it.contains(wantedX,wantedY) } ?: window.graphicsConfiguration.bounds
                check(display.width>=w && display.height>=h) { "Smoke needs a display at least ${w}x${h}; got ${display.width}x${display.height}" }
                windowState.position=WindowPosition.Absolute((display.x+minOf(100,display.width-w)).dp,(display.y+minOf(60,display.height-h)).dp)
                windowState.size=DpSize(w.dp,h.dp);window.toFront()
                window.focusTraversalPolicy.getDefaultComponent(window)?.requestFocusInWindow()
            }
            robot.mouseMove(window.x+window.width/2,window.y+window.height/2)
            Thread.sleep(900)
            val bounds=window.bounds
            check(bounds.width==w && bounds.height==h && window.graphicsConfiguration.bounds.contains(bounds)) { "Requested window is not fully visible on its display" }
            val original=robot.createScreenCapture(bounds)
            val scale=900.0/original.width.coerceAtLeast(original.height)
            val small=BufferedImage((original.width*scale).roundToInt(),(original.height*scale).roundToInt(),BufferedImage.TYPE_INT_RGB)
            small.createGraphics().let { g->g.drawImage(original,0,0,small.width,small.height,null);g.dispose() }
            val target=File(screens,"$name-${w}x${h}.jpg")
            ImageIO.getImageWritersByFormatName("jpeg").next().let { writer -> ImageIO.createImageOutputStream(target).use { output ->writer.output=output;val p=writer.defaultWriteParam;p.compressionMode=ImageWriteParam.MODE_EXPLICIT;p.compressionQuality=.55f;writer.write(null,IIOImage(small,null,null),p)};writer.dispose() }
            val accessibleRows=mutableListOf<String>()
            SwingUtilities.invokeAndWait {
                fun visit(a: javax.accessibility.Accessible?, depth:Int) {
                    if(a==null || depth>40) return
                    val ctx=a.accessibleContext ?: return
                    val role=ctx.accessibleRole.toDisplayString()
                    val name=if(ctx.accessibleRole==javax.accessibility.AccessibleRole.PASSWORD_TEXT) "<hidden>" else (ctx.accessibleName ?: "").replace(password,"<hidden>").replace(server,"<test-server>")
                    accessibleRows += "  ".repeat(depth)+role+" | "+name
                    if(ctx.accessibleRole!=javax.accessibility.AccessibleRole.PASSWORD_TEXT) for(i in 0 until ctx.accessibleChildrenCount) visit(ctx.getAccessibleChild(i),depth+1)
                }
                visit(window,0)
            }
            File(screens,"$name-${w}x${h}.accessibility.txt").writeText(accessibleRows.joinToString("\n")+"\n")
            val geometry=DesktopUiProbe.bounds.entries.sortedBy{it.key}.joinToString("\n"){(tag,b)->"$tag ${b.left},${b.top},${b.right},${b.bottom}"}
            File(screens,"$name-${w}x${h}.geometry.txt").writeText("requested=${w}x${h}\nactual=${bounds.width}x${bounds.height}\n$geometry\n")
            File(screens,"index.md").appendText("- [$name · ${w}×${h}](${target.name}) — actual window ${bounds.width}×${bounds.height}, reader off.\n")
        }
        SwingUtilities.invokeAndWait {windowState.size=DpSize(1280.dp,800.dp)}
        Thread.sleep(250)
    }
    fun firstLibrary()=c.state.value.homeRows.first{it.library}.items.first{it.collectionType=="movies" || it.name.contains("Movie",true)}
    try {
        ready(DesktopRoute.Servers);screenshot("01-server")
        click("server-address");type(server);click("connect");ready(DesktopRoute.SignIn);checks["server"]=true;screenshot("02-sign-in")
        click("username");type(user);click("password");type("wrong-password");click("sign-in");ready(DesktopRoute.SignIn)
        check(c.state.value.error!=null) { "Wrong password did not show a recoverable error" };screenshot("02b-sign-in-error")
        click("password");repeat(32) { key(KeyEvent.VK_RIGHT) };repeat(32) { key(KeyEvent.VK_BACK_SPACE) };type(password)
        click("sign-in");ready(DesktopRoute.Home);checks["passwordSignIn"]=true
        waitFor("home rows") { c.state.value.homeRows.any { it.items.isNotEmpty() } };checks["home"]=true;screenshot("03-home")
        val library=firstLibrary();click("nav:library:${library.id}");ready(DesktopRoute.Library);waitFor("library items"){c.state.value.items.isNotEmpty()};checks["library"]=true;screenshot("04-library")
        click("sort");click("sort:DateCreated");waitFor("sort applied"){c.state.value.sortBy=="DateCreated"&&!c.state.value.busy};checks["sort"]=true
        val movie=c.state.value.items.first{it.name=="Signal One"};click("grid:${movie.id}");ready(DesktopRoute.Detail);checks["detail"]=true;screenshot("05-movie-detail")
        key(KeyEvent.VK_ESCAPE);ready(DesktopRoute.Library)
        waitFor("keyboard Back restores opener") { DesktopUiProbe.focus["grid:${movie.id}"]==true }
        key(KeyEvent.VK_ENTER);ready(DesktopRoute.Detail)
        click("play");ready(DesktopRoute.Player)
        waitFor("decoded playback",60){c.state.value.playback?.ready==true};checks["playback"]=true
        check(c.state.value.playback!!.positionSeconds>=10){"Resume did not use fixture server progress"};checks["resume"]=true
        val beforeFull=java.awt.Rectangle(window.bounds)
        key(KeyEvent.VK_F11);waitFor("keyboard fullscreen"){window.bounds==window.graphicsConfiguration.bounds}
        key(KeyEvent.VK_F11);waitFor("fullscreen restore"){window.bounds==beforeFull}
        val first=c.state.value.playback!!.positionSeconds
        waitFor("advancing playback",30){(c.state.value.playback?.positionSeconds?:0.0)>first+3};checks["progress"]=true
        screenshot("06-player")
        val audio=c.state.value.playback!!.audioTracks.last();click("audio");click("audio:${audio.index}");waitFor("audio choice"){c.state.value.playback?.audioIndex==audio.index};checks["audio"]=true;screenshot("07-audio-selected")
        val sub=c.state.value.playback!!.subtitleTracks.first();click("subtitles");screenshot("08-subtitle-menu");click("subtitles:${sub.index}");waitFor("subtitle choice"){c.state.value.playback?.subtitleIndex==sub.index};click("subtitles");click("subtitles:-1");waitFor("subtitle Off"){c.state.value.playback?.subtitleIndex== -1};checks["subtitles"]=true
        click("pause");waitFor("paused"){c.state.value.playback?.paused==true};click("pause");waitFor("unpaused"){c.state.value.playback?.paused==false}
        click("stop");ready(DesktopRoute.Detail)
        click("nav:library:${library.id}");ready(DesktopRoute.Library)
        val archive=c.state.value.items.first{it.name=="Archive Signal"}
        val qualityConversion=com.sun.jna.Platform.isWindows() || System.getenv("PICTURESHOW_SMOKE_FORCE_QUALITY")=="1"
        if(qualityConversion) {
            click("nav:settings");ready(DesktopRoute.Settings);click("quality");click("quality:4");ready(DesktopRoute.Settings)
            click("nav:library:${library.id}");ready(DesktopRoute.Library)
        }
        click("grid:${archive.id}");ready(DesktopRoute.Detail);click("play");ready(DesktopRoute.Player)
        waitFor("converted video decoded",60){c.state.value.playback?.ready==true}
        check(c.state.value.playback!!.decision.method==app.pictureshow.core.playback.PlayMethod.Transcode) { "Unsupported codec was not converted" }
        check((c.state.value.playback!!.notice!=null) != qualityConversion) { "Conversion notice must explain a device limit, never a viewer quality cap" }
        screenshot("08b-conversion-notice")
        waitFor("converted progress"){(c.state.value.playback?.positionSeconds?:0.0)>=3}
        click("seek-forward")
        waitFor("converted seek full timeline"){(c.state.value.playback?.positionSeconds?:0.0)>=13}
        Thread.sleep(2000)
        checks["conversion"]=true
        click("stop");ready(DesktopRoute.Detail)
        if(qualityConversion) { click("nav:settings");ready(DesktopRoute.Settings);click("quality");click("quality:0");ready(DesktopRoute.Settings) }
        click("nav:home");ready(DesktopRoute.Home)
        val shows=c.state.value.homeRows.first{it.library}.items.first{it.collectionType=="tvshows"||it.name.contains("Show",true)}
        click("nav:library:${shows.id}");ready(DesktopRoute.Library);waitFor("series"){c.state.value.items.any{it.type=="Series"}}
        val series=c.state.value.items.first{it.type=="Series"};click("grid:${series.id}");ready(DesktopRoute.Detail)
        waitFor("seasons"){c.state.value.seasons.isNotEmpty()};checks["seasons"]=true
        val season=c.state.value.seasons.first();click("season:${season.id}");ready(DesktopRoute.Detail);waitFor("episodes"){c.state.value.episodes.size>=2};checks["episodes"]=true;screenshot("09-series-detail")
        val episode=c.state.value.episodes.first();click("episode:${episode.id}");ready(DesktopRoute.Detail);screenshot("10-episode-detail");click("play");ready(DesktopRoute.Player);waitFor("episode ready",60){c.state.value.playback?.ready==true}
        waitFor("next episode"){c.state.value.playback?.nextEpisode!=null};click("next-episode");ready(DesktopRoute.Player);waitFor("next episode playing",60){c.state.value.playback?.let{it.ready&&it.item.id!=episode.id}==true};checks["nextEpisode"]=true
        click("stop");ready(DesktopRoute.Detail)
        click("nav:home");ready(DesktopRoute.Home);click("nav:library:${shows.id}");ready(DesktopRoute.Library)
        click("grid:${series.id}");ready(DesktopRoute.Detail);click("season:${season.id}");ready(DesktopRoute.Detail)
        click("episode:${episode.id}");ready(DesktopRoute.Detail)
        if(DesktopUiProbe.bounds["play-start"]!=null) click("play-start") else click("play")
        ready(DesktopRoute.Player);waitFor("autoplay source ready",60){c.state.value.playback?.ready==true}
        waitFor("autoplay successor available"){c.state.value.playback?.nextEpisode!=null}
        val slider=DesktopUiProbe.bounds.getValue("seek");val origin=window.locationOnScreen;val pixelScale=window.graphicsConfiguration.defaultTransform.scaleX
        val duration=c.state.value.playback!!.durationSeconds
        val ratio=((duration-12)/duration).coerceIn(0.0,1.0)
        robot.mouseMove(origin.x+((slider.left+slider.width*ratio)/pixelScale).roundToInt(),origin.y+(slider.center.y/pixelScale).roundToInt());robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        waitFor("natural EOF autoplay",35){c.state.value.playback?.let{it.ready&&it.item.id!=episode.id}==true};checks["autoplayNext"]=true
        click("stop");ready(DesktopRoute.Detail);click("nav:settings");ready(DesktopRoute.Settings);screenshot("11-settings")
        click("conversion");ready(DesktopRoute.Settings);check(!c.state.value.settings.conversionNotice);click("conversion");ready(DesktopRoute.Settings);checks["settings"]=true
        click("nav:accounts");ready(DesktopRoute.Accounts);screenshot("12-accounts")
        val removeKey=c.state.value.accounts.first().key
        click("remove:$removeKey");screenshot("12b-remove-confirmation");key(KeyEvent.VK_ESCAPE)
        waitFor("Escape dismisses removal without changing accounts") { !DesktopUiProbe.bounds.containsKey("remove-cancel") && c.state.value.route==DesktopRoute.Accounts && c.state.value.accounts.any{it.key==removeKey} }
        // Voluntary sign-out is tested before the second authentication method.
        click("account:${c.state.value.accounts.first().key}");ready(DesktopRoute.Home);click("nav:settings");ready(DesktopRoute.Settings);click("sign-out")
        waitFor("signed out"){c.state.value.account==null&&!c.state.value.busy}
        if(c.state.value.route==DesktopRoute.Accounts) click("add-server")
        ready(DesktopRoute.Servers);click("server-address");type(server);click("connect");ready(DesktopRoute.SignIn);click("quick-connect")
        waitFor("Quick Connect code"){c.state.value.route==DesktopRoute.QuickConnect&&c.state.value.quickCode!=null}
        val code=c.state.value.quickCode!!
        val temp=File(out,"quick-connect-request.tmp");temp.writeText("{\"code\":\"$code\"}\n");java.nio.file.Files.move(temp.toPath(),File(out,"quick-connect-request.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        screenshot("13-quick-connect");ready(DesktopRoute.Home);checks["quickConnect"]=true
        click("shortcuts");screenshot("14-keyboard-shortcuts");key(KeyEvent.VK_ESCAPE)
        waitFor("Escape dismisses keyboard help without navigating") { !DesktopUiProbe.bounds.containsKey("help-close") && c.state.value.route==DesktopRoute.Home }
        // Native Tab and Escape must act on the same viewer window after authentication.
        key(KeyEvent.VK_TAB);key(KeyEvent.VK_ESCAPE)
        successful=checks.size==18&&checks.values.all{it}
        File(out,"app-smoke-result.json").writeText("{\"pass\":$successful,\"checks\":{${checks.entries.joinToString(","){(k,v)->"\"$k\":$v"}}}}\n")
        check(successful)
        println("SMOKE all 18 viewer flows passed")
    } catch(error:Throwable) {
        File(out,"app-smoke-result.json").writeText("{\"pass\":false,\"checks\":{${checks.entries.joinToString(","){(k,v)->"\"$k\":$v"}}}}\n")
        runCatching { screenshot("99-failure") }
        System.err.println("SMOKE ${error.message}")
    } finally {
        SwingUtilities.invokeLater{close()}
        if(!successful) Thread {Thread.sleep(2500);kotlin.system.exitProcess(2)}.apply{isDaemon=true;start()}
    }
}
