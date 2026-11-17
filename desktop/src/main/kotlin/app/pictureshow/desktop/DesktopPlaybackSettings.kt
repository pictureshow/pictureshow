/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Desktop's playback defaults. Audio passthrough starts Off here (SET-02's default is On): a desktop OS doesn't report
 * whether a receiver is attached, and most computers play through speakers or headphones.
 */
internal fun desktopDefaultPlayback(): PlaybackPreferences = PlaybackPreferences.decode(passthrough = false)

private val preferenceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun segmentKey(type: SegmentType) = "segment.${type.name.lowercase()}"

/** Invalid stored values fall back one field at a time, as on Android (core PlaybackPreferences/PlaybackPreferenceJson). */
internal fun readDesktopPlayback(p: Properties): PlaybackPreferences = PlaybackPreferences.decode(
    passthrough = p.getProperty("passthrough")?.toBooleanStrictOrNull() ?: false,
    subtitleSize = p.getProperty("subtitleSize"),
    subtitleBackground = p.getProperty("subtitleBackground"),
    segmentModes = SegmentType.entries.filter { it != SegmentType.Unknown }.associateWith { p.getProperty(segmentKey(it)) }
).copy(
    tuning = PlaybackPreferenceJson.tuning(p.getProperty("tuning")),
    subtitleStyle = PlaybackPreferenceJson.style(p.getProperty("subtitleStyle"))
)

internal fun writeDesktopPlayback(p: Properties, prefs: PlaybackPreferences) {
    p.setProperty("passthrough", prefs.passthrough.toString())
    p.setProperty("subtitleSize", prefs.subtitleSize.name)
    p.setProperty("subtitleBackground", prefs.subtitleBackground.name)
    SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
        p.setProperty(segmentKey(type), (prefs.segmentModes[type] ?: SegmentMode.Ask).name)
    }
    p.setProperty("tuning", preferenceJson.encodeToString(prefs.tuning.normalized()))
    p.setProperty("subtitleStyle", preferenceJson.encodeToString(prefs.subtitleStyle.normalized()))
}

/** SET-06/07/08: the viewer's server preferences. Saving sends the whole configuration back, unknown fields intact. */
internal data class DesktopServerPreferencesState(
    val loading: Boolean = false,
    val configuration: UserConfiguration? = null,
    val saving: Boolean = false,
    val error: String? = null
)

internal class DesktopServerPreferences(private val service: UserConfigurationService, private val scope: CoroutineScope, private val account: () -> Account?) {
    private val mutable = MutableStateFlow(DesktopServerPreferencesState())
    val state: StateFlow<DesktopServerPreferencesState> = mutable.asStateFlow()
    private var loadedFor: String? = null

    fun load(force: Boolean = false) {
        val a = account() ?: return
        if (!force && loadedFor == a.key && mutable.value.configuration != null) return
        loadedFor = a.key
        mutable.value = DesktopServerPreferencesState(loading = true)
        scope.launch {
            mutable.value = when (val r = service.configuration(a)) {
                is ClientResult.Success -> DesktopServerPreferencesState(configuration = r.value)
                is ClientResult.Failure -> DesktopServerPreferencesState(error = "Your server preferences couldn't be loaded. ${r.error.message}")
            }
        }
    }

    fun save(name: String, value: String) {
        val a = account() ?: return
        val current = mutable.value.configuration ?: return
        val next = current.withPreference(name, value)
        mutable.value = mutable.value.copy(configuration = next, saving = true, error = null)
        scope.launch {
            mutable.value = when (val r = service.saveConfiguration(a, next)) {
                is ClientResult.Success -> mutable.value.copy(saving = false)
                is ClientResult.Failure -> mutable.value.copy(configuration = current, saving = false, error = "That change wasn't saved. ${r.error.message}")
            }
        }
    }
}

internal val DESKTOP_PREFERENCE_LANGUAGES = listOf("", "eng", "spa", "fra", "deu", "jpn", "zho", "kor", "por", "ita", "rus", "ara", "hin")
internal val DESKTOP_SUBTITLE_MODES = listOf("Default", "Always", "OnlyForced", "None", "Smart")
internal fun desktopLanguageLabel(code: String) = if (code.isBlank()) "No preference" else trackLanguage(code) ?: code
internal fun desktopSubtitleModeLabel(mode: String) = when (mode) { "OnlyForced" -> "Only forced"; else -> mode }
internal fun desktopSecondsLabel(seconds: Int) = if (seconds == 0) "Off" else "$seconds seconds"
internal fun desktopStillWatchingLabel(value: StillWatching) = if (value == StillWatching.Off) "Never" else "After ${value.episodes} episodes"
internal fun desktopStillWatchingCaption(value: StillWatching): String {
    if (value == StillWatching.Off) return "Episodes keep playing until you stop them."
    val hours = value.minutes / 60
    val time = when { value.minutes % 60 == 30 -> "$hours½ hours"; hours == 1 -> "1 hour"; else -> "$hours hours" }
    return "Also asks after $time without a key press or click, before the next episode starts."
}
private fun bitrateLabel(mbps: Int?) = if (mbps == null) "Original" else "$mbps Mbps"

/** SET-23: the long Settings page opens with its sections; choosing one scrolls to it and focuses its heading. */
internal val DESKTOP_SETTINGS_SECTIONS = listOf("Account", "Playback", "Subtitles", "Skip segments", "Languages", "Audio", "Interface", "About")

internal class DesktopSettingsAnchors {
    val views = DESKTOP_SETTINGS_SECTIONS.associateWith { BringIntoViewRequester() }
    val headings = DESKTOP_SETTINGS_SECTIONS.associateWith { FocusRequester() }
}

internal val LocalSettingsAnchors = staticCompositionLocalOf<DesktopSettingsAnchors?> { null }

@Composable
internal fun SettingsJumpList() {
    val anchors = LocalSettingsAnchors.current ?: return
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On this page", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DESKTOP_SETTINGS_SECTIONS.forEach { section ->
                OutlinedButton(onClick = {
                    scope.launch {
                        anchors.views.getValue(section).bringIntoView()
                        runCatching { anchors.headings.getValue(section).requestFocus() }
                    }
                }, shape = RoundedCornerShape(50), modifier = Modifier.heightIn(min = 36.dp).controlFocus().probe("jump:$section")) { Text(section) }
            }
        }
    }
}


@Composable
private fun <T> ChoiceRow(title: String, caption: String, items: List<Pair<T, String>>, selected: T, tag: String, onChoose: (T) -> Unit) {
    SettingRow(title, caption) {
        Choice(title, items, selected, tag, buttonValue = items.firstOrNull { it.first == selected }?.second ?: "Default", buttonWidth = 200.dp, fixedWidth = true, menuMinWidth = 240.dp, onChoose = onChoose)
    }
}

/** Playback rows added to the existing Playback section (SET-13, SET-17, SET-18, PLAY-30). */
@Composable
internal fun DesktopPlaybackRows(s: DesktopSettings, c: DesktopController) {
    val prefs = s.playback
    val tuning = prefs.tuning
    fun tune(change: (PlaybackTuning) -> PlaybackTuning) = c.saveSettings(s.copy(playback = prefs.copy(tuning = change(tuning).normalized())))
    val rungs = PlaybackPreferences.BITRATES.map { it to bitrateLabel(it) }
    ChoiceRow("Quality away from home", "For a server outside your home network. Video quality above applies at home.", rungs, tuning.remoteBitrateMbps, "remote-quality") { v -> tune { it.copy(remoteBitrateMbps = v) } }
    ChoiceRow("Rewind on resume", "Replays a few seconds when you return to a saved position.", listOf(0, 5, 10, 15, 30).map { it to desktopSecondsLabel(it) }, tuning.rewindSeconds, "rewind") { v -> tune { it.copy(rewindSeconds = v) } }
    ChoiceRow("Next episode countdown",
        if (tuning.nextEpisodeCountdownSeconds == 0) "The next episode waits for you to choose Play next." else "How long the next episode card counts down before it plays.",
        PlaybackTuning.NEXT_EPISODE_COUNTDOWNS.map { it to desktopSecondsLabel(it) }, tuning.nextEpisodeCountdownSeconds, "countdown") { v -> tune { it.copy(nextEpisodeCountdownSeconds = v) } }
    ChoiceRow("After credits",
        if (tuning.postCredits == PostCredits.KeepScenes) "Plays any scenes after the credits before the next episode starts."
        else "Starts the next episode during the credits. You can cancel the countdown to keep watching.",
        PostCredits.entries.map { it to PlaybackSettingsPresentation.postCreditsLabel(it) }, tuning.postCredits, "post-credits") { v -> tune { it.copy(postCredits = v) } }
    ChoiceRow("Ask if I'm still watching", desktopStillWatchingCaption(tuning.stillWatching),
        StillWatching.entries.map { it to desktopStillWatchingLabel(it) }, tuning.stillWatching, "still-watching") { v -> tune { it.copy(stillWatching = v) } }
}

private val COLOR_CHOICES = SubtitleStyle.COLORS.map { it to if (it == "System") "Default" else it }
private val OPACITY_CHOICES = listOf(0, 25, 50, 75, 80, 100).map { it to "$it%" }

/** SET-03/04/15 and SET-05, SET-06..08, SET-02/19: the sections after Playback. */
@Composable
internal fun DesktopPlaybackSections(state: DesktopState, c: DesktopController) {
    val s = state.settings
    val prefs = s.playback
    val style = prefs.subtitleStyle
    fun save(next: PlaybackPreferences) = c.saveSettings(s.copy(playback = next))
    fun styled(change: (SubtitleStyle) -> SubtitleStyle) = save(prefs.copy(subtitleStyle = change(style).normalized()))
    SettingsSection("Subtitles") {
        ChoiceRow("Size", "Text subtitles only. Image subtitles keep their size.", SubtitleSize.entries.map { it to PlaybackSettingsPresentation.subtitleSizeLabel(it) }, prefs.subtitleSize, "subtitle-size") { save(prefs.copy(subtitleSize = it)) }
        ChoiceRow("Background", "Behind text subtitles.", SubtitleBackground.entries.map { it to PlaybackSettingsPresentation.subtitleBackgroundLabel(it) }, prefs.subtitleBackground, "subtitle-background") { save(prefs.copy(subtitleBackground = it)) }
        ChoiceRow("Text color", "Default keeps the file's colors.", COLOR_CHOICES, style.foreground, "subtitle-color") { v -> styled { it.copy(foreground = v) } }
        ChoiceRow("Text opacity", "How solid the letters are.", OPACITY_CHOICES, style.textOpacity, "subtitle-text-opacity") { v -> styled { it.copy(textOpacity = v) } }
        ChoiceRow("Edge", "An outline or shadow keeps text readable on bright scenes.", SubtitleStyle.EDGES.map { it to if (it == "System") "Default" else it }, style.edge, "subtitle-edge") { v -> styled { it.copy(edge = v) } }
        if (style.edge == "Outline" || style.edge == "Shadow") ChoiceRow("Edge color", "The outline or shadow color.", COLOR_CHOICES.drop(1), style.edgeColor, "subtitle-edge-color") { v -> styled { it.copy(edgeColor = v) } }
        if (prefs.subtitleBackground == SubtitleBackground.Box) {
            ChoiceRow("Box color", "The box behind the text.", COLOR_CHOICES.drop(1), style.backgroundColor, "subtitle-box-color") { v -> styled { it.copy(backgroundColor = v) } }
            ChoiceRow("Box opacity", "How solid the box is.", OPACITY_CHOICES, style.backgroundOpacity, "subtitle-box-opacity") { v -> styled { it.copy(backgroundOpacity = v) } }
        }
        ChoiceRow("Position", "Where text subtitles sit on the picture.", SubtitleStyle.POSITIONS.map { it to if (it == "Authored") "As in the file" else it }, style.position, "subtitle-position") { v -> styled { it.copy(position = v) } }
        SettingSwitch("Bold", "Heavier letters.", style.bold, "subtitle-bold") { v -> styled { it.copy(bold = v) } }
        SettingSwitch("Italic", "Slanted letters.", style.italic, "subtitle-italic") { v -> styled { it.copy(italic = v) } }
        SettingSwitch("Override styled subtitles", "Use these choices even for subtitles with their own styling (ASS/SSA).", style.overrideEmbedded, "subtitle-override") { v -> styled { it.copy(overrideEmbedded = v) } }
        Text("Changes apply the next time a video opens. Subtitles the server draws into the video can't be restyled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsSection("Skip segments") {
        listOf(SegmentType.Intro to "Intros", SegmentType.Recap to "Recaps", SegmentType.Preview to "Previews", SegmentType.Commercial to "Commercials", SegmentType.Outro to "Credits").forEachIndexed { i, (type, title) ->
            ChoiceRow(title, "When your server has marked them.",
                SegmentMode.entries.map { it to if (it == SegmentMode.Automatic) "Skip automatically" else it.name },
                prefs.segmentModes[type] ?: SegmentMode.Ask, "segment-${type.name.lowercase()}") { v -> save(prefs.copy(segmentModes = prefs.segmentModes + (type to v))) }
        }
    }
    SettingsSection("Languages") {
        val server by c.serverPreferences.state.collectAsState()
        LaunchedEffect(state.account?.key) { c.serverPreferences.load() }
        Text("Saved to your account on the server, so every app you sign in to uses them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val config = server.configuration
        when {
            config != null -> {
                val languages = DESKTOP_PREFERENCE_LANGUAGES.map { it to desktopLanguageLabel(it) }
                ChoiceRow("Audio language", "Preferred when a title has several.", languages, config.audioLanguage.takeIf { it in DESKTOP_PREFERENCE_LANGUAGES } ?: "", "audio-language") { c.serverPreferences.save("AudioLanguagePreference", it) }
                ChoiceRow("Subtitle language", "Preferred when a title has several.", languages, config.subtitleLanguage.takeIf { it in DESKTOP_PREFERENCE_LANGUAGES } ?: "", "subtitle-language") { c.serverPreferences.save("SubtitleLanguagePreference", it) }
                ChoiceRow("Subtitle mode", "When the server turns subtitles on by itself.", DESKTOP_SUBTITLE_MODES.map { it to desktopSubtitleModeLabel(it) }, config.subtitleMode.takeIf { it in DESKTOP_SUBTITLE_MODES } ?: "Default", "subtitle-mode") { c.serverPreferences.save("SubtitleMode", it) }
            }
            server.loading -> Text("Loading your server preferences…")
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(server.error ?: "Sign in to change these.", Modifier.weight(1f))
                if (server.error != null) OutlinedButton(onClick = { c.serverPreferences.load(force = true) }, modifier = Modifier.controlFocus().probe("languages-retry")) { Text("Try again") }
            }
        }
        server.error?.takeIf { config != null }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    SettingsSection("Audio") {
        val tuning = prefs.tuning
        fun tune(change: (PlaybackTuning) -> PlaybackTuning) = save(prefs.copy(tuning = change(tuning).normalized()))
        SettingSwitch("Audio passthrough",
            "Sends Dolby and DTS audio undecoded to a receiver over HDMI or S/PDIF. Leave it off for speakers and headphones. Speed stays at 1× while it's in use.",
            prefs.passthrough, "passthrough") { save(prefs.copy(passthrough = it)) }
        if (prefs.passthrough && !tuning.downmix) {
            PassthroughEncoding.entries.filter { it != PassthroughEncoding.EAC3_JOC }.forEach { format ->
                SettingSwitch(PlaybackSettingsPresentation.passthroughLabel(format), "Allow this format to pass through.", format !in tuning.disabledPassthrough, "passthrough-${format.name.lowercase()}") { on ->
                    tune { t ->
                        val formats = if (format == PassthroughEncoding.EAC3) setOf(PassthroughEncoding.EAC3, PassthroughEncoding.EAC3_JOC) else setOf(format)
                        t.copy(disabledPassthrough = if (on) t.disabledPassthrough - formats else t.disabledPassthrough + formats)
                    }
                }
            }
        }
        SettingSwitch("Downmix to stereo", "Mixes surround sound down to two channels. Turns passthrough off.", tuning.downmix, "downmix") { v -> tune { it.copy(downmix = v) } }
        if (!tuning.downmix) ChoiceRow("Channel limit", "The most channels this computer sends to its speakers.", listOf(null, 2, 6, 8).map { it to PlaybackSettingsPresentation.audioChannelLabel(it) }, tuning.maxAudioChannels, "channels") { v -> tune { it.copy(maxAudioChannels = v) } }
    }
}
