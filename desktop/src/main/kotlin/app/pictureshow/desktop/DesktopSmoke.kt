/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.unit.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.*
import app.pictureshow.core.*
import kotlinx.serialization.json.*
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
    var nativeCloseRequested=false
    fun waitFor(label:String,seconds:Long=45,predicate:()->Boolean) {
        val until=System.nanoTime()+seconds*1_000_000_000L
        while(!predicate()) { check(System.nanoTime()<until) { "SMOKE timed out: $label; route=${c.state.value.route}; busy=${c.state.value.busy}; error=${c.state.value.error != null}; window=${window.bounds}; stateSize=${windowState.size}; display=${window.graphicsConfiguration.bounds}" };Thread.sleep(60) }
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
    fun screenshot(name:String, sizes:List<Pair<Int,Int>> = listOf(1280 to 800,1920 to 1080), hoverTag:String? = null) {
        for((w,h) in sizes) {
            SwingUtilities.invokeAndWait {
                if(com.sun.jna.Platform.isMac()) java.awt.Desktop.getDesktop().requestForeground(true)
                val wantedX=System.getenv("PICTURESHOW_SMOKE_WINDOW_X")?.toInt() ?: window.x
                val wantedY=System.getenv("PICTURESHOW_SMOKE_WINDOW_Y")?.toInt() ?: window.y
                val display=java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.firstOrNull { it.contains(wantedX,wantedY) } ?: window.graphicsConfiguration.bounds
                check(display.width>=w && display.height>=h) { "Smoke needs a display at least ${w}x${h}; got ${display.width}x${display.height}" }
                val targetX=display.x+minOf(100,display.width-w)
                val targetY=display.y+minOf(60,display.height-h)
                windowState.position=WindowPosition.Absolute(targetX.dp,targetY.dp)
                windowState.size=DpSize(w.dp,h.dp)
                // Drive the native resize on the EDT as a real window-manager
                // resize does; do not rely only on declarative state propagation.
                window.setBounds(targetX,targetY,w,h)
                window.toFront()
                window.focusTraversalPolicy.getDefaultComponent(window)?.requestFocusInWindow()
            }
            robot.mouseMove(window.x+window.width/2,window.y+window.height/2)
            waitFor("requested ${w}x${h} window contained on its display",5) {
                val observed=window.bounds
                observed.width==w && observed.height==h && window.graphicsConfiguration.bounds.contains(observed)
            }
            Thread.sleep(400) // Allow the final native resize and Compose layout to paint.
            hoverTag?.let { tag ->
                val target=DesktopUiProbe.bounds.getValue(tag)
                val origin=window.locationOnScreen;val scale=window.graphicsConfiguration.defaultTransform.scaleX
                robot.mouseMove(origin.x+(target.center.x/scale).roundToInt(),origin.y+(target.center.y/scale).roundToInt())
                Thread.sleep(1100)
            }
            val bounds=window.bounds
            check(bounds.width==w && bounds.height==h && window.graphicsConfiguration.bounds.contains(bounds)) { "Requested ${w}x${h} window not visible; actual=$bounds display=${window.graphicsConfiguration.bounds}" }
            val original=robot.createScreenCapture(bounds)
            if(name=="02b-sign-in-error" && w==1920) {
                val username=DesktopUiProbe.bounds.getValue("username")
                val passwordBounds=DesktopUiProbe.bounds.getValue("password")
                val transform=window.graphicsConfiguration.defaultTransform
                val scaleX=transform.scaleX
                val scaleY=transform.scaleY
                val pad=24
                val left=((minOf(username.left,passwordBounds.left).toDouble()/scaleX).roundToInt()-pad).coerceAtLeast(0)
                val top=((minOf(username.top,passwordBounds.top).toDouble()/scaleY).roundToInt()-pad).coerceAtLeast(0)
                val right=((maxOf(username.right,passwordBounds.right).toDouble()/scaleX).roundToInt()+pad).coerceAtMost(original.width)
                val bottom=((maxOf(username.bottom,passwordBounds.bottom).toDouble()/scaleY).roundToInt()+pad).coerceAtMost(original.height)
                check(right>left && bottom>top) { "Authentication crop is outside Robot image: rect=$left,$top,$right,$bottom image=${original.width}x${original.height} transform=${scaleX}x$scaleY" }
                val crop=original.getSubimage(left,top,right-left,bottom-top)
                val cropScale=(900.0/crop.width.coerceAtLeast(crop.height)).coerceAtMost(1.0)
                val cropSmall=BufferedImage((crop.width*cropScale).roundToInt().coerceAtLeast(1),(crop.height*cropScale).roundToInt().coerceAtLeast(1),BufferedImage.TYPE_INT_RGB)
                cropSmall.createGraphics().let { g->g.drawImage(crop,0,0,cropSmall.width,cropSmall.height,null);g.dispose() }
                val cropFile=File(screens,"$name-${w}x${h}-crop-auth-fields.jpg")
                ImageIO.getImageWritersByFormatName("jpeg").next().let { writer -> ImageIO.createImageOutputStream(cropFile).use { output ->writer.output=output;val p=writer.defaultWriteParam;p.compressionMode=ImageWriteParam.MODE_EXPLICIT;p.compressionQuality=.55f;writer.write(null,IIOImage(cropSmall,null,null),p)};writer.dispose() }
                File(screens,"$name-${w}x${h}-crop-auth-fields.geometry.json").writeText(buildJsonObject {
                    put("source", "Robot BufferedImage before full-window resize")
                    put("originalWidth", original.width); put("originalHeight", original.height)
                    put("defaultTransformScaleX", scaleX); put("defaultTransformScaleY", scaleY)
                    put("usernameBounds", buildJsonObject { put("left",username.left);put("top",username.top);put("right",username.right);put("bottom",username.bottom) })
                    put("passwordBounds", buildJsonObject { put("left",passwordBounds.left);put("top",passwordBounds.top);put("right",passwordBounds.right);put("bottom",passwordBounds.bottom) })
                    put("crop", buildJsonObject { put("coordinateSpace", "Robot BufferedImage logical pixels");put("left",left);put("top",top);put("right",right);put("bottom",bottom);put("width",right-left);put("height",bottom-top) })
                    put("outputWidth",cropSmall.width);put("outputHeight",cropSmall.height);put("scale",cropScale)
                }.toString()+"\n")
            }
            val scale=900.0/original.width.coerceAtLeast(original.height)
            val small=BufferedImage((original.width*scale).roundToInt(),(original.height*scale).roundToInt(),BufferedImage.TYPE_INT_RGB)
            small.createGraphics().let { g->g.drawImage(original,0,0,small.width,small.height,null);g.dispose() }
            val target=File(screens,"$name-${w}x${h}.jpg")
            ImageIO.getImageWritersByFormatName("jpeg").next().let { writer -> ImageIO.createImageOutputStream(target).use { output ->writer.output=output;val p=writer.defaultWriteParam;p.compressionMode=ImageWriteParam.MODE_EXPLICIT;p.compressionQuality=.55f;writer.write(null,IIOImage(small,null,null),p)};writer.dispose() }
            if(c.state.value.route==DesktopRoute.Player && c.state.value.playback?.ready==true && !com.sun.jna.Platform.isLinux()) {
                var receipt:String?=null
                waitFor("whole video clears header and controls") {
                    receipt=c.videoViewportReceipt()
                    receipt?.let {
                        val data=Json.parseToJsonElement(it).jsonObject
                        val top=data.getValue("topRatio").jsonPrimitive.double
                        val bottom=data.getValue("bottomRatio").jsonPrimitive.double
                        val dimensions=data.getValue("dimensions").jsonObject
                        val height=dimensions.getValue("h").jsonPrimitive.double
                        top>0 && bottom>0 && height>0 && dimensions.getValue("mt").jsonPrimitive.double>=top*height-2 && dimensions.getValue("mb").jsonPrimitive.double>=bottom*height-2
                    } ?: false
                }
                File(screens,"$name-${w}x${h}.viewport.json").writeText(requireNotNull(receipt)+"\n")
            }
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
        SwingUtilities.invokeAndWait {windowState.size=DpSize(1280.dp,800.dp);window.setSize(1280,800)}
        waitFor("restore standard window after capture",5) { window.width==1280 && window.height==800 }
    }
    fun firstLibrary()=c.state.value.homeRows.first{it.library}.items.first{it.collectionType=="movies" || it.name.contains("Movie",true)}
    try {
        if(com.sun.jna.Platform.isMac()) check(File(out,"window-chrome.json").readText().contains("\"nativeMacChrome\":true")) { "Mac smoke requires visible standard window controls" }
        ready(DesktopRoute.Servers)
        if(System.getenv("PICTURESHOW_SMOKE_DISCOVERY_PORT")!=null) {
            waitFor("owned loopback Jellyfin discovery") { c.state.value.discovered.any { it.address==server } }
            File(out,"discovery-result.json").writeText("{\"found\":true}\n")
        }
        screenshot("01-server");screenshot("01b-server-minimum",listOf(800 to 600))
        click("server-address");type(server);click("connect");ready(DesktopRoute.SignIn);checks["server"]=true;screenshot("02-sign-in");screenshot("02c-sign-in-minimum",listOf(800 to 600))
        click("username");waitFor("username has actual keyboard focus") { DesktopUiProbe.focus["username"]==true };type(user);click("password");waitFor("password has actual keyboard focus") { DesktopUiProbe.focus["password"]==true };type("wrong-password");click("sign-in")
        waitFor("recoverable password rejection") { c.state.value.route==DesktopRoute.SignIn && !c.state.value.busy && c.state.value.errorKind==ErrorKind.INVALID_CREDENTIALS }
        waitFor("rejected password is focused") { DesktopUiProbe.focus["password"]==true }
        key(KeyEvent.VK_RIGHT);Thread.sleep(250)
        check(c.state.value.errorKind==ErrorKind.INVALID_CREDENTIALS) { "Cursor movement cleared the credential rejection" };screenshot("02b-sign-in-error");screenshot("02b-sign-in-error-minimum",listOf(800 to 600))
        click("password");repeat(32) { key(KeyEvent.VK_RIGHT) };repeat(32) { key(KeyEvent.VK_BACK_SPACE) };type(password)
        click("sign-in");ready(DesktopRoute.Home);checks["passwordSignIn"]=true
        waitFor("home rows") { c.state.value.homeRows.any { it.items.isNotEmpty() } };checks["home"]=true;screenshot("03-home");screenshot("03b-home-minimum",listOf(800 to 600));if(System.getenv("PICTURESHOW_SMOKE_4K")=="1") screenshot("03c-home-4k",listOf(3840 to 2160))
        val library=firstLibrary();click("nav:library:${library.id}");ready(DesktopRoute.Library);waitFor("library items"){c.state.value.items.isNotEmpty()};checks["library"]=true;screenshot("04-library");screenshot("04b-library-minimum",listOf(800 to 600))
        click("sort");click("sort:DateCreated");waitFor("sort applied"){c.state.value.sortBy=="DateCreated"&&!c.state.value.busy};checks["sort"]=true
        val movie=c.state.value.items.first{it.name=="Signal One"};click("grid:${movie.id}");ready(DesktopRoute.Detail);checks["detail"]=true;screenshot("05-movie-detail");screenshot("05b-detail-minimum",listOf(800 to 600));if(System.getenv("PICTURESHOW_SMOKE_4K")=="1") screenshot("05c-detail-4k",listOf(3840 to 2160))
        key(KeyEvent.VK_ESCAPE);ready(DesktopRoute.Library)
        waitFor("keyboard Back restores opener") { DesktopUiProbe.focus["grid:${movie.id}"]==true }
        key(KeyEvent.VK_ENTER);ready(DesktopRoute.Detail)
        waitFor("primary action keyboard focus") { DesktopUiProbe.focus["play"]==true }
        screenshot("05d-primary-keyboard-focus",listOf(1280 to 800))
        key(KeyEvent.VK_TAB);Thread.sleep(350);screenshot("05e-tab-keyboard-focus",listOf(1280 to 800))
        click("play");ready(DesktopRoute.Player)
        waitFor("decoded playback",60){c.state.value.playback?.ready==true};checks["playback"]=true
        check(c.state.value.playback!!.positionSeconds>=10){"Resume did not use fixture server progress"};checks["resume"]=true
        val beforeFull=java.awt.Rectangle(window.bounds)
        robot.keyPress(KeyEvent.VK_ALT);key(KeyEvent.VK_ENTER);robot.keyRelease(KeyEvent.VK_ALT);waitFor("keyboard fullscreen"){window.bounds==window.graphicsConfiguration.bounds}
        robot.keyPress(KeyEvent.VK_ALT);key(KeyEvent.VK_ENTER);robot.keyRelease(KeyEvent.VK_ALT);waitFor("fullscreen restore"){window.bounds==beforeFull}
        val first=c.state.value.playback!!.positionSeconds
        waitFor("advancing playback",30){(c.state.value.playback?.positionSeconds?:0.0)>first+3};checks["progress"]=true
        screenshot("06-player");screenshot("06b-player-minimum",listOf(800 to 600));screenshot("06c-back-seek-tooltip",hoverTag="seek-back")
        fun trackReceipt(name:String): DesktopSelectedTracks? = c.nativeTrackSelection().also { selected ->
            selected?.let { File(out,"$name.json").writeText(buildJsonObject { put("audioId",it.audioId?.let(::JsonPrimitive) ?: JsonNull);put("subtitleId",it.subtitleId?.let(::JsonPrimitive) ?: JsonNull);put("subtitleText",it.subtitleText);put("subtitleVisible",it.subtitleVisible)
                put("nativeVersion",it.nativeVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("ffmpegVersion",it.ffmpegVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("subtitleCodec",it.subtitleCodec?.let(::JsonPrimitive) ?: JsonNull)
                put("subtitleStartSeconds",it.subtitleStartSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("subtitleEndSeconds",it.subtitleEndSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("positionSeconds",it.positionSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("initialSubtitleIndex",it.initialSubtitleIndex?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()+"\n") }
        }
        val audio=c.state.value.playback!!.audioTracks.last();click("audio");click("audio:${audio.index}");waitFor("audio choice"){c.state.value.playback?.audioIndex==audio.index};waitFor("native audio selection") { trackReceipt("audio-selection")?.audioId==c.state.value.playback!!.audioTracks.filter { !it.isExternal }.indexOfFirst { it.index==audio.index }+1 };checks["audio"]=true;screenshot("07-audio-selected")
        val sub=c.state.value.playback!!.subtitleTracks.first();click("subtitles");screenshot("08-subtitle-menu");screenshot("08b-subtitle-menu-minimum",listOf(800 to 600));click("subtitles:${sub.index}");waitFor("subtitle choice"){c.state.value.playback?.subtitleIndex==sub.index}
        waitFor("native decoded subtitle cue",20) { trackReceipt("subtitle-selection")?.let { it.subtitleId!=null && it.subtitleVisible && it.subtitleText.contains("Pictureshow original CC0 subtitle") &&
                it.subtitleCodec in setOf("srt", "subrip") &&
                it.subtitleStartSeconds==0.0 && it.subtitleEndSeconds==180.0 &&
                it.positionSeconds?.let { position -> position>=0.0 && position<180.0 }==true &&
                it.initialSubtitleIndex in setOf(-1, sub.index) }==true }
        screenshot("08c-subtitles-on");screenshot("08d-subtitles-on-minimum",listOf(800 to 600));click("subtitles");click("subtitles:-1");waitFor("subtitle Off"){c.state.value.playback?.subtitleIndex== -1};waitFor("native subtitle Off") { trackReceipt("subtitle-off")?.let { it.subtitleId==null }==true };checks["subtitles"]=true
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
        screenshot(if(qualityConversion) "08b-quality-conversion" else "08b-conversion-notice")
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
        waitFor("next episode"){c.state.value.playback?.nextEpisode!=null};screenshot("10c-next-episode-minimum",listOf(800 to 600));click("next-episode");ready(DesktopRoute.Player);waitFor("next episode playing",60){c.state.value.playback?.let{it.ready&&it.item.id!=episode.id}==true};checks["nextEpisode"]=true
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
        click("stop");ready(DesktopRoute.Detail);click("nav:settings");ready(DesktopRoute.Settings);screenshot("11-settings");screenshot("11b-settings-minimum",listOf(800 to 600));if(System.getenv("PICTURESHOW_SMOKE_4K")=="1") screenshot("11c-settings-4k",listOf(3840 to 2160))
        click("conversion");ready(DesktopRoute.Settings);check(!c.state.value.settings.conversionNotice);click("conversion");ready(DesktopRoute.Settings);checks["settings"]=true
        click("nav:accounts");ready(DesktopRoute.Accounts);screenshot("12-accounts")
        val removeKey=c.state.value.accounts.first().key
        click("remove:$removeKey");waitFor("removal dialog initially focuses Cancel") { DesktopUiProbe.focus["remove-cancel"]==true };screenshot("12b-remove-confirmation");waitFor("Cancel remains focused after capture") { DesktopUiProbe.focus["remove-cancel"]==true }
        key(KeyEvent.VK_LEFT);key(KeyEvent.VK_DOWN)
        waitFor("arrows keep removal focus in modal") { DesktopUiProbe.focus["remove-cancel"]==true && DesktopUiProbe.bounds.containsKey("remove-cancel") && c.state.value.route==DesktopRoute.Accounts }
        key(KeyEvent.VK_TAB);waitFor("Tab focuses remove confirmation") { DesktopUiProbe.focus["remove-confirm"]==true }
        key(KeyEvent.VK_RIGHT);key(KeyEvent.VK_UP)
        waitFor("arrows keep confirmation focus in modal") { DesktopUiProbe.focus["remove-confirm"]==true && DesktopUiProbe.bounds.containsKey("remove-confirm") && c.state.value.route==DesktopRoute.Accounts }
        screenshot("12c-remove-confirm-focused");key(KeyEvent.VK_ESCAPE)
        waitFor("Escape dismisses removal without changing accounts") { !DesktopUiProbe.bounds.containsKey("remove-cancel") && c.state.value.route==DesktopRoute.Accounts && c.state.value.accounts.any{it.key==removeKey} }
        waitFor("removal dialog returns focus to opener") { DesktopUiProbe.focus["remove:$removeKey"]==true }
        // Voluntary sign-out is tested before the second authentication method.
        click("account:${c.state.value.accounts.first().key}");ready(DesktopRoute.Home);click("nav:settings");ready(DesktopRoute.Settings);click("sign-out")
        waitFor("signed out"){c.state.value.account==null&&!c.state.value.busy}
        if(c.state.value.route==DesktopRoute.SignIn) {
            check(c.state.value.server?.url?.trimEnd('/')==server.trimEnd('/')) { "Sign-out did not retain the selected server" }
        } else {
            if(c.state.value.route==DesktopRoute.Accounts) click("add-server")
            ready(DesktopRoute.Servers);click("server-address");type(server);click("connect");ready(DesktopRoute.SignIn)
        }
        click("quick-connect")
        waitFor("Quick Connect code"){c.state.value.route==DesktopRoute.QuickConnect&&c.state.value.quickCode!=null}
        val code=c.state.value.quickCode!!
        val temp=File(out,"quick-connect-request.tmp");temp.writeText("{\"code\":\"$code\"}\n");java.nio.file.Files.move(temp.toPath(),File(out,"quick-connect-request.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        screenshot("13-quick-connect");ready(DesktopRoute.Home);checks["quickConnect"]=true
        click("shortcuts");waitFor("keyboard help focuses Close") { DesktopUiProbe.focus["help-close"]==true }
        key(KeyEvent.VK_LEFT);key(KeyEvent.VK_UP);key(KeyEvent.VK_RIGHT);key(KeyEvent.VK_DOWN)
        waitFor("arrows keep keyboard help focus in modal") { DesktopUiProbe.focus["help-close"]==true && DesktopUiProbe.bounds.containsKey("help-close") && c.state.value.route==DesktopRoute.Home }
        screenshot("14-keyboard-shortcuts");key(KeyEvent.VK_ESCAPE)
        waitFor("Escape dismisses keyboard help without navigating") { !DesktopUiProbe.bounds.containsKey("help-close") && c.state.value.route==DesktopRoute.Home }
        // Traverse real controls after the dialog restores its opener. A key
        // injection without observing focus is not keyboard-navigation evidence.
        waitFor("help opener focus restored") { DesktopUiProbe.focus["shortcuts"]==true }
        key(KeyEvent.VK_TAB)
        waitFor("Tab reaches fullscreen") { DesktopUiProbe.focus["fullscreen"]==true }
        robot.keyPress(KeyEvent.VK_SHIFT);key(KeyEvent.VK_TAB);robot.keyRelease(KeyEvent.VK_SHIFT)
        waitFor("Shift Tab restores shortcuts") { DesktopUiProbe.focus["shortcuts"]==true }
        key(KeyEvent.VK_ENTER)
        waitFor("Enter opens keyboard help") { DesktopUiProbe.bounds.containsKey("help-close") }
        key(KeyEvent.VK_ESCAPE)
        waitFor("keyboard help closes with Escape") { !DesktopUiProbe.bounds.containsKey("help-close") && c.state.value.route==DesktopRoute.Home }
        click("nav:library:${library.id}");ready(DesktopRoute.Library)
        click("grid:${movie.id}");ready(DesktopRoute.Detail)
        if(c.state.value.detail?.isWatched!=true) { click("watched");waitFor("watched server state") { c.state.value.detail?.isWatched==true } }
        if(c.state.value.detail?.userData?.isFavorite!=true) { click("favorite");waitFor("favorite server state") { c.state.value.detail?.userData?.isFavorite==true } }
        screenshot("15-detail-selected-states")
        DesktopUiProbe.requestFocus["watched"]?.requestFocus();waitFor("watched toggle keyboard focus"){DesktopUiProbe.focus["watched"]==true};screenshot("15c-watched-keyboard-focus",listOf(1280 to 800))
        DesktopUiProbe.requestFocus["favorite"]?.requestFocus();waitFor("favorite toggle keyboard focus"){DesktopUiProbe.focus["favorite"]==true};screenshot("15d-favorite-keyboard-focus",listOf(1280 to 800))
        screenshot("15b-favorite-tooltip",hoverTag="favorite")
        click("watched");waitFor("unwatched server state") { c.state.value.detail?.isWatched==false }
        click("favorite");waitFor("unfavorite server state") { c.state.value.detail?.userData?.isFavorite==false }
        click("nav:home");ready(DesktopRoute.Home)
        successful=checks.size==18&&checks.values.all{it}
        File(out,"app-smoke-result.json").writeText("{\"pass\":$successful,\"checks\":{${checks.entries.joinToString(","){(k,v)->"\"$k\":$v"}}}}\n")
        check(successful)
        println("SMOKE all 18 viewer flows passed")
        if(com.sun.jna.Platform.isMac() && System.getenv("PICTURESHOW_SMOKE_NATIVE_CLOSE")=="1") {
            click("nav:library:${library.id}");ready(DesktopRoute.Library)
            click("grid:${movie.id}");ready(DesktopRoute.Detail);click("play");ready(DesktopRoute.Player)
            waitFor("native close source playing",60) { c.state.value.playback?.ready==true }
            Thread.sleep(1500)
            File(out,"native-close-request.json").writeText("{\"ready\":true}\n")
            recordDesktopMacChrome(File(out,"native-close-chrome.json"),window)
            nativeCloseRequested=true
            // The standard AppKit close button is anchored at (14,19), 14×14.
            robot.mouseMove(window.x+21,window.y+26)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            waitFor("native window close",15) { !window.isDisplayable }
        }
    } catch(error:Throwable) {
        successful=false
        File(out,"app-smoke-result.json").writeText("{\"pass\":false,\"checks\":{${checks.entries.joinToString(","){(k,v)->"\"$k\":$v"}}}}\n")
        runCatching { screenshot("99-failure") }
        System.err.println("SMOKE ${error.message}")
    } finally {
        if(!nativeCloseRequested || !successful) SwingUtilities.invokeLater{close()}
        if(!successful) Thread {Thread.sleep(2500);kotlin.system.exitProcess(2)}.apply{isDaemon=true;start()}
    }
}
