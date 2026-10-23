/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
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
internal fun preferenceLanguageLabel(value: String): String = if (value.isBlank()) "No preference" else trackLanguage(value)

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
    var restoreEntry by rememberSaveable { mutableStateOf("") }
    val local = state.local
    val configuration = state.configuration
    Column(
        Modifier.fillMaxSize().background(Color(0xFF10131C)).verticalScroll(rememberScrollState()).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Button(
            onClick = {
                restoreEntry = "this-tv"
                onThisTv()
            },
            modifier = Modifier.focusRequester(thisTvFocus).testTag("settings:this-tv")
        ) { Text("This TV") }
        Button(
            onClick = {
                restoreEntry = "supporter"
                onSupport()
            },
            modifier = Modifier.focusRequester(supportFocus).testTag("settings:supporter")
        ) { Text(stringResource(R.string.supporter_title)) }
        Button(
            onClick = {
                restoreEntry = "about"
                onAbout()
            },
            modifier = Modifier.focusRequester(aboutFocus).testTag("settings:about")
        ) { Text("About Pictureshow") }
        Text("Press center to change a value. Changes are saved immediately.")
        Button(
            onClick = { onLocal { it.copy(bitrateMbps = nextSetting(PlaybackPreferences.BITRATES, it.bitrateMbps)) } },
            modifier = Modifier.focusRequester(first).testTag("settings:bitrate")
        ) { Text("Max streaming bitrate: ${local.bitrateMbps?.let { "$it Mbps" } ?: "Auto (200 Mbps)"}") }
        Button(onClick = { onLocal { it.copy(passthrough = !it.passthrough) } }, modifier = Modifier.testTag("settings:passthrough")) {
            Text("Audio passthrough: ${if (local.passthrough) "On" else "Off"}")
        }
        Text("This TV's audio output accepts: $acceptedAudio")
        Button(onClick = {
            onLocal { it.copy(subtitleSize = nextSetting(SubtitleSize.entries, it.subtitleSize)) }
        }, modifier = Modifier.testTag("settings:size")) {
            Text("Subtitle size: ${settingLabel(local.subtitleSize.name)}")
        }
        Button(onClick = {
            onLocal { it.copy(subtitleBackground = nextSetting(SubtitleBackground.entries, it.subtitleBackground)) }
        }, modifier = Modifier.testTag("settings:background")) {
            Text("Subtitle background: ${settingLabel(local.subtitleBackground.name)}")
        }
        Text(
            "System follows Android caption settings. Bitmap subtitles keep their authored appearance. These settings apply the next time you open the player."
        )
        Text("Skip segments", style = MaterialTheme.typography.headlineSmall)
        SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
            val mode = local.segmentModes[type] ?: SegmentMode.Ask
            Button(
                onClick = {
                    onLocal { it.copy(segmentModes = it.segmentModes + (type to nextSetting(SegmentMode.entries, mode))) }
                },
                modifier = Modifier.testTag("settings:segment:$type")
            ) {
                Text(
                    "${segmentLabel(type).replaceFirstChar {
                        it.uppercase()
                    }}: ${if (mode == SegmentMode.Automatic) "Skip automatically" else mode.name}"
                )
            }
        }
        Text("Ask shows a skip button. These settings apply the next time you open the player.")
        Text("Your server preferences", style = MaterialTheme.typography.headlineSmall)
        Text("These follow your account to other clients.")
        val enabled = !state.loading && !state.saving && configuration != null
        Button(
            onClick = { onServer("AudioLanguagePreference", nextSetting(preferenceLanguages, configuration!!.audioLanguage)) },
            enabled = enabled,
            modifier = Modifier.testTag("settings:audio-language")
        ) { Text("Audio language: ${configuration?.audioLanguage?.let(::preferenceLanguageLabel) ?: "Loading…"}") }
        Button(
            onClick = { onServer("SubtitleLanguagePreference", nextSetting(preferenceLanguages, configuration!!.subtitleLanguage)) },
            enabled = enabled,
            modifier = Modifier.testTag("settings:subtitle-language")
        ) { Text("Subtitle language: ${configuration?.subtitleLanguage?.let(::preferenceLanguageLabel) ?: "Loading…"}") }
        Button(
            onClick = { onServer("SubtitleMode", nextSetting(subtitleModes, configuration!!.subtitleMode)) },
            enabled = enabled,
            modifier = Modifier.testTag("settings:subtitle-mode")
        ) { Text("Subtitle mode: ${configuration?.subtitleMode ?: "Loading…"}") }
        if (state.saving) Text("Saving server preferences…")
        state.error?.let {
            Text(it)
            Button(onClick = onRetry) { Text("Retry") }
        }
        Button(onClick = onBack, modifier = Modifier.testTag("settings:back")) { Text("Back") }
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
    "System" -> "System (default)"
    "ExtraLarge" -> "Extra large"
    else -> value
}
