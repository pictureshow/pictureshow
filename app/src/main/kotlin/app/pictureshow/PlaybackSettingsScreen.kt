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
import app.pictureshow.core.playback.PassthroughEncoding
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

/** Who is signed in on this TV, for the Account group at the top of Settings. */
internal data class SettingsAccount(val user: String, val server: String)

/** The TV Settings groups in page order (T-9). Each group has a visible heading; nothing appears twice on the page. */
internal val tvSettingsGroups = listOf(
    "Account",
    "Playback & quality",
    "Subtitles",
    "Audio & video output",
    "Skip segments",
    "Your server preferences",
    "Pictureshow"
)

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
    onSyncPlay: () -> Unit = {},
    supportedPassthrough: Set<PassthroughEncoding> = emptySet(),
    account: SettingsAccount? = null,
    onSwitchUser: () -> Unit = {},
    onSignOut: () -> Unit = {},
    signingOut: Boolean = false,
    /** HOME-12: null hides the switch until the Home layout is read. */
    separateNextUp: Boolean? = null,
    onSeparateNextUp: (Boolean) -> Unit = {},
    /** Seerr requests: null hides the row (no account yet); otherwise the connected name, or "" when not connected. */
    seerrUser: String? = null,
    onSeerr: () -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    val switchUserFocus = remember { FocusRequester() }
    val signOutFocus = remember { FocusRequester() }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val thisTvFocus = remember { FocusRequester() }
    val aboutFocus = remember { FocusRequester() }
    val supportFocus = remember { FocusRequester() }
    val seerrFocus = remember { FocusRequester() }
    val advancedFocus = remember { mutableMapOf<String, FocusRequester>() }
    var restoreEntry by rememberSaveable { mutableStateOf("") }
    var restoreAdvanced by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf<String?>(null) }
    val local = state.local
    val configuration = state.configuration

    @Composable
    fun group(name: String, modifier: Modifier = Modifier) {
        val requester = remember(name) { FocusRequester() }
        advancedFocus[name] = requester
        TvSettingsActionRow(
            name,
            modifier.focusRequester(requester).testTag("settings:advanced:$name"),
            navigation = true,
            summary = when (name) {
                "Network quality" ->
                    "Home ${bitrateLabel(local.bitrateMbps)} · Away ${bitrateLabel(local.tuning.remoteBitrateMbps)} · " +
                        "Metered ${bitrateLabel(local.tuning.meteredBitrateMbps)}"
                "Subtitle appearance" ->
                    "Size ${subtitleSizeLabel(local.subtitleSize)} · Background ${subtitleBackgroundLabel(local.subtitleBackground)}, " +
                        "with a live preview"
                "Subtitle conversion" -> "When the server draws subtitles into the picture"
                "Playback" -> "After credits, next episode and resume"
                "Audio output" ->
                    "Passthrough: " + app.pictureshow.core.playback.PlaybackSettingsPresentation.passthroughSummary(
                        local.passthrough,
                        supportedPassthrough,
                        checking = acceptedAudio.startsWith("Checking"),
                        downmix = local.tuning.downmix
                    )
                else -> "Refresh rate and HDR compatibility"
            }
        ) { advanced = name }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 36.dp, vertical = 24.dp)) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = 28.dp).semantics {
                heading()
            }
        )
        TvSettingsList(Modifier.weight(1f)) {
            if (account != null) {
                TvSettingsSectionHeader("Account", supportingText = "${account.user} on ${account.server}")
                TvSettingsActionRow(
                    "Switch user",
                    Modifier.focusRequester(first).focusRequester(switchUserFocus).testTag("settings:switch-user"),
                    supportingText = "Sign in as someone else, or pick another saved account.",
                    enabled = !signingOut,
                    navigation = true
                ) {
                    restoreEntry = "switch-user"
                    onSwitchUser()
                }
                ProfilePinSettings(
                    tv = true,
                    row = { title, supporting, tag, onClick ->
                        TvSettingsActionRow(
                            title,
                            Modifier.testTag(tag),
                            supportingText = supporting,
                            enabled = !signingOut,
                            onClick = onClick
                        )
                    },
                    toggle = { title, supporting, checked, tag, onChange ->
                        TvSettingsToggleRow(
                            title,
                            checked,
                            Modifier.testTag(tag),
                            supportingText = supporting,
                            enabled = !signingOut,
                            onToggle = onChange
                        )
                    }
                )
                TvSettingsActionRow(
                    if (signingOut) "Signing out…" else "Sign out",
                    Modifier.focusRequester(signOutFocus).testTag("settings:sign-out"),
                    supportingText = "Signs ${account.user} out of this TV. The account stays saved here.",
                    enabled = !signingOut
                ) { confirmSignOut = true }
                if (confirmSignOut) {
                    SignOutConfirmation(
                        account.user,
                        onCancel = {
                            confirmSignOut = false
                            signOutFocus.requestFocus()
                        },
                        onConfirm = {
                            confirmSignOut = false
                            onSignOut()
                        }
                    )
                }
            }
            if (separateNextUp != null) {
                TvSettingsSectionHeader("Home")
                TvSettingsToggleRow(
                    "Separate Next up row",
                    separateNextUp,
                    Modifier.testTag("settings:separate-next-up"),
                    supportingText = separateNextUpSummary(separateNextUp)
                ) { value -> onSeparateNextUp(value) }
            }
            TvSettingsSectionHeader("Playback & quality")
            group("Network quality", if (account == null) Modifier.focusRequester(first) else Modifier)
            group("Playback")
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
                { if (it == "auto") "Auto" else musicQualityLabel(it) },
                Modifier.testTag("settings:music-quality"),
                optionSupportingText = { if (it == "auto") "256 kbps on metered networks" else null }
            ) { value -> onLocal { it.copy(musicQuality = value) } }
            TvSettingsChoiceRow(
                "Phone remote",
                local.remoteReceiver,
                listOf("same-viewer", "server-allowed", "off"),
                label = {
                    when (it) {
                        "off" -> "Off"
                        "server-allowed" -> "Server access"
                        else -> "Only you"
                    }
                },
                modifier = Modifier.testTag("settings:phone-remote"),
                supportingText = "Who can play and control things on this TV from their phone while Pictureshow is open.",
                optionSupportingText = { if (it == "server-allowed") "Anyone this server allows" else null }
            ) { value -> onLocal { it.copy(remoteReceiver = value) } }
            TvSettingsSectionHeader("Subtitles")
            group("Subtitle appearance")
            group("Subtitle conversion")
            TvSettingsSectionHeader("Audio & video output")
            group("Audio output")
            group("Video output")
            TvSettingsActionRow(
                "This TV",
                Modifier.focusRequester(thisTvFocus).testTag("settings:this-tv"),
                supportingText = "A technical report of the video and audio formats this TV can play.",
                navigation = true
            ) {
                restoreEntry = "this-tv"
                onThisTv()
            }
            TvSettingsSectionHeader("Skip segments", supportingText = "Changes apply the next time you open the player.")
            SegmentType.entries.filter { it != SegmentType.Unknown }.forEach { type ->
                TvSettingsChoiceRow(
                    segmentLabel(type).replaceFirstChar { it.uppercase() },
                    local.segmentModes[type] ?: SegmentMode.Ask,
                    SegmentMode.entries,
                    { if (it == SegmentMode.Automatic) "Skip automatically" else it.name },
                    Modifier.testTag("settings:segment:$type")
                ) { value -> onLocal { it.copy(segmentModes = it.segmentModes + (type to value)) } }
            }
            TvScreensaverSettingsRows()
            TvSettingsSectionHeader("Your server preferences")
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
            TvSettingsSectionHeader("Pictureshow")
            TvSettingsActionRow(
                androidx.compose.ui.res.stringResource(R.string.sync_title),
                Modifier.testTag("settings:syncplay"),
                navigation = true,
                onClick = onSyncPlay
            )
            if (seerrUser != null) {
                TvSettingsActionRow(
                    stringResource(R.string.seerr_settings_title),
                    Modifier.focusRequester(seerrFocus).testTag("settings:seerr"),
                    supportingText = if (seerrUser.isEmpty()) {
                        stringResource(R.string.seerr_settings_off)
                    } else {
                        stringResource(R.string.seerr_settings_on, seerrUser)
                    },
                    navigation = true
                ) {
                    restoreEntry = "seerr"
                    onSeerr()
                }
            }
            TvSettingsActionRow(
                stringResource(R.string.supporter_title),
                Modifier.focusRequester(supportFocus).testTag("settings:supporter"),
                navigation = true
            ) {
                restoreEntry = "supporter"
                onSupport()
            }
            TvSettingsActionRow("About Pictureshow", Modifier.focusRequester(aboutFocus).testTag("settings:about"), navigation = true) {
                restoreEntry = "about"
                onAbout()
            }
            TvSettingsActionRow("Back", Modifier.testTag("settings:back"), onClick = onBack)
        }
    }
    advanced?.let { group ->
        AdvancedPlaybackSettings(group, local, false, onLocal, onClose = {
            advanced = null
            restoreAdvanced = group
        }, supportedPassthrough = supportedPassthrough)
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
            "seerr" -> seerrFocus.requestFocus()
            "switch-user" -> if (account != null) switchUserFocus.requestFocus() else first.requestFocus()
            else -> first.requestFocus()
        }
    }
}
