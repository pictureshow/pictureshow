/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)


package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

/** D-15: "Original" means the file plays as it is unless this computer can't decode it. */
internal const val VIDEO_QUALITY_ORIGINAL = "Original (no conversion unless needed)"
internal const val VIDEO_QUALITY_CAPTION = "Lower it if playback stutters. A cap below the file's bitrate makes the server convert it."

@Composable
internal fun SettingsPage(state:DesktopState,c:DesktopController,onSignOut:()->Unit) {
    val s=state.settings
    FormPage("Settings","Playback and viewing preferences for this computer.") {
        SettingsSection("Account") {
            SettingRow(state.account?.user?.name ?: "Signed out", state.account?.server?.name ?: "No account is connected") {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.controlFocus().heightIn(min = 40.dp).probe("sign-out")) { Text("Sign out") }
            }
        }
        SettingsSection("Playback") {
            SettingRow("Video quality", VIDEO_QUALITY_CAPTION) {
                Choice("Video quality",listOf(0 to VIDEO_QUALITY_ORIGINAL,4 to "4 Mbps",10 to "10 Mbps",20 to "20 Mbps",40 to "40 Mbps",80 to "80 Mbps"),s.bitrateMbps?:0,"quality",buttonValue = if ((s.bitrateMbps ?: 0) == 0) "Original" else "${s.bitrateMbps} Mbps", buttonWidth = 180.dp, trailingIcon = DesktopGlyph.ChevronDown, fixedWidth = true, menuMinWidth = 340.dp) { c.saveSettings(s.copy(bitrateMbps=it.takeIf{it>0})) }
            }
            SettingSwitch("Explain video conversion","Tell me when this computer needs a different video format.",s.conversionNotice,"conversion") { c.saveSettings(s.copy(conversionNotice=it)) }
            SettingSwitch("Play next episode","Continue to the next episode when this one ends.",s.autoplayNext,"autoplay") { c.saveSettings(s.copy(autoplayNext=it)) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("${s.volume}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(s.volume.toFloat(),{c.saveSettings(s.copy(volume=it.toInt()))},valueRange=0f..100f,modifier=Modifier.controlFocus(RoundedCornerShape(12.dp)).probe("volume").semantics { contentDescription="Volume" })
            }
        }
        SettingsSection("Interface") {
            SettingSwitch("Living-room size","Larger text and controls for watching from a distance.",s.largeInterface,"large-interface") { c.saveSettings(s.copy(largeInterface=it)) }
        }
        SettingsSection("About") {
            Text("Pictureshow · Free, open source Jellyfin client\nMPL-2.0 · Legitimate LLC\nVideo: dynamically loaded LGPL libmpv",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CatalogHeading(title, section = true)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
@Composable
internal fun SettingRow(title: String, caption: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("caption:$title"))
        }
        trailing()
    }
}
@Composable
internal fun SettingSwitch(title:String,caption:String,value:Boolean,tag:String,onChange:(Boolean)->Unit) {
    SettingRow(title, caption) { Switch(value,onChange,Modifier.controlFocus().probe(tag).semantics { contentDescription=title }) }
}
@Composable
internal fun <T> Choice(label:String,items:List<Pair<T,String>>,selected:T,tag:String,buttonValue:String?=null,buttonWidth:Dp=560.dp,icon:ImageVector?=null,trailingIcon:ImageVector?=DesktopGlyph.ChevronDown,fixedWidth:Boolean=false,menuMinWidth:Dp=Dp.Unspecified,onChoose:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val initialFocus = remember { FocusRequester() }
    val initialKey = items.firstOrNull { it.first == selected }?.first ?: items.firstOrNull()?.first
    Box {
        OutlinedButton(onClick={expanded=true},enabled=items.isNotEmpty(),contentPadding = if (icon != null) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,modifier = Modifier.focusRequester(opener).controlFocus(enabled = !expanded).then(if (fixedWidth) Modifier.width(buttonWidth) else Modifier.widthIn(max=buttonWidth)).probe(tag).semantics { contentDescription = "$label: ${items.firstOrNull{it.first==selected}?.second ?: "Default"}" }) {
            Row(if (fixedWidth) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically) {
                if(icon!=null) { Icon(icon,contentDescription=null,modifier=Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(buttonValue ?: "$label · ${items.firstOrNull{it.first==selected}?.second ?: "Default"}",modifier=Modifier.weight(1f, fill=fixedWidth),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=MaterialTheme.typography.labelLarge.fontWeight)
                if (trailingIcon != null) { Spacer(Modifier.width(8.dp)); Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(16.dp)) }
            }
        }
        DesktopDropdownMenu(expanded,onDismissRequest={expanded=false},opener=opener,initialFocus=initialFocus,modifier=Modifier.widthIn(min=menuMinWidth).probe("$tag-menu")) { items.forEach { (key,text) -> DropdownMenuItem(text={Column { Text(text); if (tag == "quality") qualitySupport((key as? Int) ?: 0)?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) } }},onClick={expanded=false;onChoose(key)},leadingIcon={RadioButton(selected==key,null)},modifier=Modifier.then(if (key == initialKey) Modifier.focusRequester(initialFocus) else Modifier).menuItemFocus().probe("$tag:$key")) } }
    }
}

internal fun qualitySupport(mbps: Int): String? = when (mbps) {
    0 -> "Keep original quality when supported"
    4 -> "For slow connections; a softer picture"
    10 -> "A smaller cap for everyday streaming"
    20 -> "More detail with a steady connection"
    40 -> "For higher-bitrate video on fast connections"
    80 -> "For demanding video on very fast connections"
    else -> null
}
