/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType
import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.UserConfigurationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PlaybackSettingsState(
    val local: PlaybackPreferences = PlaybackPreferences(),
    val configuration: UserConfiguration? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null
)

internal class PlaybackSettingsViewModel(
    private val account: Account,
    private val preferences: PlaybackPreferencesStore,
    private val service: UserConfigurationService,
    private val accounts: AccountStore
) : ViewModel() {
    private val mutable = MutableStateFlow(PlaybackSettingsState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                preferences.values.collect { value -> mutable.update { it.copy(local = value) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not read playback settings. Try again.") }
            }
        }
        reload()
    }

    fun reload() {
        if (mutable.value.saving) return
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = service.configuration(account)) {
                is ClientResult.Success -> mutable.update { it.copy(configuration = result.value, loading = false) }

                is ClientResult.Failure -> {
                    if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) accounts.expire(account.key, account.accessToken)
                    mutable.update { it.copy(loading = false, error = result.error.message) }
                }
            }
        }
    }

    fun local(change: (PlaybackPreferences) -> PlaybackPreferences) {
        viewModelScope.launch {
            try {
                preferences.update(change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not save playback settings. Try again.") }
            }
        }
    }

    fun server(name: String, value: String) {
        val current = mutable.value
        if (current.saving || current.loading) return
        val next = current.configuration?.withPreference(name, value) ?: return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = service.saveConfiguration(account, next)) {
                is ClientResult.Success -> mutable.update { it.copy(configuration = next, saving = false) }

                is ClientResult.Failure -> {
                    if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) accounts.expire(account.key, account.accessToken)
                    mutable.update { it.copy(saving = false, error = result.error.message) }
                }
            }
        }
    }
}

internal val preferenceLanguages = listOf("", "eng", "spa", "fra", "deu", "jpn", "zho", "kor", "por", "ita", "rus", "ara", "hin")
internal val subtitleModes = listOf("Default", "Always", "OnlyForced", "None", "Smart")
internal fun <T> nextSetting(values: List<T>, current: T): T = values[(values.indexOf(current) + 1).mod(values.size)]
internal fun preferenceLanguageLabel(value: String): String = if (value.isBlank()) "No preference" else trackLanguage(value) ?: value

@Composable
internal fun PlaybackSettingsScreen(
    state: PlaybackSettingsState,
    acceptedAudio: String,
    onLocal: ((PlaybackPreferences) -> PlaybackPreferences) -> Unit,
    onServer: (String, String) -> Unit,
    onRetry: () -> Unit,
    onThisTv: () -> Unit = {},
    onAbout: () -> Unit = {},
    onSupport: () -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    val thisTvFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    val supportFocus = remember { FocusRequester() }
    val advancedFocus = remember { mutableMapOf<String, FocusRequester>() }
    var restoreEntry by rememberSaveable { mutableStateOf("") }
    var restoreAdvanced by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf<String?>(null) }
    val local = state.local
    val configuration = state.configuration
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 36.dp, vertical = 24.dp)) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 12.dp).semantics {
                heading()
            }
        )
        TvSettingsList(Modifier.weight(1f)) {
            TvSettingsActionRow(
                stringResource(R.string.supporter_title),
                Modifier.focusRequester(supportFocus).testTag("settings:supporter")
            ) {
                restoreEntry = "supporter"
                onSupport()
            }
            TvSettingsActionRow("About Pictureshow", Modifier.focusRequester(aboutFocus).testTag("settings:about")) {
                restoreEntry = "about"
                onAbout()
            }
            TvSettingsChoiceRow(
                "Home quality limit",
                local.bitrateMbps,
                PlaybackPreferences.BITRATES,
                label = { it?.let { value -> "$value Mbps" } ?: "Auto (200 Mbps)" },
                modifier = Modifier.focusRequester(first).testTag("settings:bitrate"),
                supportingText = "For playback on your home network. Changes are saved automatically."
            ) { value -> onLocal { it.copy(bitrateMbps = value) } }
            TvSettingsToggleRow(
                "Audio passthrough",
                local.passthrough,
                Modifier.testTag("settings:passthrough"),
                supportingText = "Send supported audio to your receiver. This TV accepts: $acceptedAudio."
            ) { value -> onLocal { it.copy(passthrough = value) } }
            TvSettingsToggleRow(
                "Conversion notice",
                local.conversionNotice,
                Modifier.testTag("settings:conversion-notice"),
                supportingText = "Shows why a video or song needs conversion for this TV."
            ) { value -> onLocal { it.copy(conversionNotice = value) } }
            TvSettingsChoiceRow(
                "Music quality",
                local.musicQuality,
                musicQualityOptions.map { it.first },
                ::musicQualityLabel,
                Modifier.testTag("settings:music-quality")
            ) { value -> onLocal { it.copy(musicQuality = value) } }
            TvSettingsChoiceRow(
                "Phone remote",
                local.remoteReceiver,
                listOf("same-viewer", "server-allowed", "off"),
                label = {
                    when (it) {
                        "off" -> "Off"
                        "server-allowed" -> "Anyone this server allows"
                        else -> "Only you"
                    }
                },
                modifier = Modifier.testTag("settings:phone-remote"),
                supportingText = "Who can play and control things on this TV from their phone while Pictureshow is open."
            ) { value -> onLocal { it.copy(remoteReceiver = value) } }
            TvSettingsChoiceRow(
                "Subtitle size",
                local.subtitleSize,
                SubtitleSize.entries,
                { settingLabel(it.name) },
                Modifier.testTag("settings:size"),
                supportingText = "Default follows Android captions."
            ) { value -> onLocal { it.copy(subtitleSize = value) } }
            TvSettingsChoiceRow(
                "Subtitle background",
                local.subtitleBackground,
                SubtitleBackground.entries,
                { settingLabel(it.name) },
                Modifier.testTag("settings:background"),
                supportingText = "Open Subtitle appearance for a live preview, including image subtitles."
            ) { value -> onLocal { it.copy(subtitleBackground = value) } }
            playbackGroups.forEach { group ->
                val requester = remember(group) { FocusRequester() }
                advancedFocus[group] = requester
                TvSettingsActionRow(group, Modifier.focusRequester(requester).testTag("settings:advanced:$group")) { advanced = group }
            }
            Text("Skip segments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp).semantics { heading() })
            SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
                TvSettingsChoiceRow(
                    segmentLabel(type).replaceFirstChar { it.uppercase() },
                    local.segmentModes[type] ?: SegmentMode.Ask,
                    SegmentMode.entries,
                    { if (it == SegmentMode.Automatic) "Skip automatically" else it.name },
                    Modifier.testTag("settings:segment:$type"),
                    supportingText = "Ask shows a skip button. Changes apply next time you open the player."
                ) { value -> onLocal { it.copy(segmentModes = it.segmentModes + (type to value)) } }
            }
            Text(
                "Your server preferences",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(12.dp).semantics {
                    heading()
                }
            )
            val enabled = !state.loading && !state.saving && configuration != null
            TvSettingsChoiceRow(
                "Audio language",
                configuration?.audioLanguage ?: "",
                preferenceLanguages,
                label = { if (configuration == null) "Loading…" else preferenceLanguageLabel(it) },
                modifier = Modifier.testTag("settings:audio-language"),
                enabled = enabled,
                supportingText = "These preferences follow your account to other clients."
            ) { value -> onServer("AudioLanguagePreference", value) }
            TvSettingsChoiceRow(
                "Subtitle language",
                configuration?.subtitleLanguage ?: "",
                preferenceLanguages,
                label = { if (configuration == null) "Loading…" else preferenceLanguageLabel(it) },
                modifier = Modifier.testTag("settings:subtitle-language"),
                enabled = enabled
            ) { value -> onServer("SubtitleLanguagePreference", value) }
            TvSettingsChoiceRow(
                "Subtitle mode",
                configuration?.subtitleMode ?: "Default",
                subtitleModes,
                label = {
                    if (configuration == null) {
                        "Loading…"
                    } else {
                        when (it) {
                            "OnlyForced" -> "Only forced"
                            "None" -> "Off"
                            else -> it
                        }
                    }
                },
                modifier = Modifier.testTag("settings:subtitle-mode"),
                enabled = enabled
            ) { value -> onServer("SubtitleMode", value) }
            if (state.saving) Text("Saving server preferences…", Modifier.padding(12.dp))
            state.error?.let { error ->
                TvSettingsActionRow("Retry", supportingText = error, onClick = onRetry)
            }
            Text("Troubleshooting", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp).semantics { heading() })
            TvSettingsActionRow(
                "This TV",
                Modifier.focusRequester(thisTvFocus).testTag("settings:this-tv"),
                supportingText = "A technical report of the video and audio formats this TV can play."
            ) {
                restoreEntry = "this-tv"
                onThisTv()
            }
            TvSettingsActionRow("Back", Modifier.testTag("settings:back"), onClick = onBack)
        }
    }
    advanced?.let { group ->
        AdvancedPlaybackSettings(group, local, false, onLocal, onClose = {
            advanced = null
            restoreAdvanced = group
        })
    }
    LaunchedEffect(restoreAdvanced) {
        restoreAdvanced?.let { group ->
            androidx.compose.runtime.withFrameNanos { }
            advancedFocus[group]?.requestFocus()
            restoreAdvanced = null
        }
    }
    LaunchedEffect(Unit) {
        when (restoreEntry) {
            "this-tv" -> thisTvFocus.requestFocus()
            "about" -> aboutFocus.requestFocus()
            "supporter" -> supportFocus.requestFocus()
            else -> first.requestFocus()
        }
    }
}

private fun settingLabel(value: String): String = when (value) {
    "System" -> "Default"
    "ExtraLarge" -> "Extra large"
    "Normal" -> "Medium"
    "None" -> "Off"
    else -> value
}
