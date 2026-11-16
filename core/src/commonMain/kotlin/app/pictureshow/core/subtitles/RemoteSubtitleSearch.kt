/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.subtitles

import app.pictureshow.core.Account
import app.pictureshow.core.playback.MediaStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RemoteSubtitlePhase { Checking, Blocked, Searching, Results, Downloading, Added, NotListed }

data class RemoteSubtitleState(
    val phase: RemoteSubtitlePhase = RemoteSubtitlePhase.Checking,
    val access: RemoteSubtitleAccess = RemoteSubtitleAccess.Allowed,
    val language: String = "eng",
    val languages: List<SubtitleCulture> = emptyList(),
    val results: List<RemoteSubtitle> = emptyList(),
    /** Why [results] is empty once a search finished, or why a download failed. */
    val empty: RemoteSubtitleEmpty = RemoteSubtitleEmpty.None,
    val chosen: RemoteSubtitle? = null,
    val adopted: AdoptedSubtitle? = null
) {
    val languageName: String get() = languages.firstOrNull { it.threeLetter?.lowercase() == language }?.displayName ?: language
}

/** Where the search runs: the item, and in the player the source and streams it's playing now. */
data class RemoteSubtitleTarget(
    val itemId: String,
    val itemType: String?,
    val playable: Boolean,
    val mediaSourceId: String? = null,
    val streams: List<MediaStream> = emptyList(),
    val selectedAudio: Int = -1
)

/**
 * SUB-DL: one search panel's lifetime, shared by the player and the title page. It checks access first (SUB-DL-01),
 * searches in the viewer's language (SUB-DL-02), and after a download waits for the server to list the file so the
 * caller can switch to it (SUB-DL-04).
 */
class RemoteSubtitleSearch(
    private val client: RemoteSubtitleClient,
    private val account: Account,
    private val scope: CoroutineScope,
    /** The device's language, the last fallback for the first search. */
    private val deviceLanguage: String?
) {
    private val mutable = MutableStateFlow(RemoteSubtitleState())
    val state: StateFlow<RemoteSubtitleState> = mutable.asStateFlow()
    private var target: RemoteSubtitleTarget? = null
    private var job: Job? = null

    fun open(target: RemoteSubtitleTarget) {
        this.target = target
        job?.cancel()
        mutable.value = RemoteSubtitleState()
        job = scope.launch {
            val viewer = client.access(account, target.itemType, target.playable)
            if (viewer.access != RemoteSubtitleAccess.Allowed) {
                mutable.value = mutable.value.copy(phase = RemoteSubtitlePhase.Blocked, access = viewer.access)
                return@launch
            }
            val cultures = client.cultures(account)
            val preferences = listOf(viewer.subtitleLanguage, viewer.audioLanguage, deviceLanguage)
            val language = RemoteSubtitlePolicy.initialLanguage(preferences[0], preferences[1], preferences[2], cultures)
            val first = preferences.mapNotNull { RemoteSubtitlePolicy.searchCode(it, cultures) } + language
            mutable.value = mutable.value.copy(
                language = language,
                languages = RemoteSubtitlePolicy.languageChoices(listOf(language) + first, cultures)
            )
            search(target, language)
        }
    }

    fun language(code: String) {
        val target = target ?: return
        if (mutable.value.phase == RemoteSubtitlePhase.Downloading) return
        job?.cancel()
        job = scope.launch { search(target, code.lowercase()) }
    }

    fun retry() {
        val target = target ?: return
        if (mutable.value.phase == RemoteSubtitlePhase.Blocked || mutable.value.phase == RemoteSubtitlePhase.Checking) {
            open(target)
        } else {
            language(mutable.value.language)
        }
    }

    /**
     * Download [result]. In the player, [onAdopted] gets the refreshed indices to reopen with; on a title page there is
     * no stream to switch, and the panel just confirms.
     */
    fun download(result: RemoteSubtitle, onAdopted: (AdoptedSubtitle) -> Unit = {}) {
        val target = target ?: return
        if (mutable.value.phase == RemoteSubtitlePhase.Downloading) return
        job?.cancel()
        mutable.value = mutable.value.copy(phase = RemoteSubtitlePhase.Downloading, chosen = result, empty = RemoteSubtitleEmpty.None)
        job = scope.launch {
            // Before the download: the streams the server listed, which the refreshed list is compared against.
            val before = target.streams.ifEmpty { client.streams(account, target.itemId, target.mediaSourceId).orEmpty() }
            when (val call = client.download(account, target.itemId, result.id)) {
                is SubtitleCall.Failed -> {
                    mutable.value = mutable.value.copy(
                        phase = RemoteSubtitlePhase.Results,
                        empty = RemoteSubtitlePolicy.failure(call.status)
                    )
                    return@launch
                }
                is SubtitleCall.Ok -> Unit
            }
            val adopted = client.awaitAdded(account, target.itemId, target.mediaSourceId, before, target.selectedAudio, result)
            if (adopted == null) {
                mutable.value = mutable.value.copy(phase = RemoteSubtitlePhase.NotListed)
            } else {
                mutable.value = mutable.value.copy(phase = RemoteSubtitlePhase.Added, adopted = adopted)
                onAdopted(adopted)
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    private suspend fun search(target: RemoteSubtitleTarget, language: String) {
        mutable.value = mutable.value.copy(
            phase = RemoteSubtitlePhase.Searching,
            language = language,
            results = emptyList(),
            empty = RemoteSubtitleEmpty.None,
            chosen = null
        )
        mutable.value = when (val call = client.search(account, target.itemId, language)) {
            is SubtitleCall.Ok -> mutable.value.copy(
                phase = RemoteSubtitlePhase.Results,
                results = call.value,
                empty = if (call.value.isEmpty()) RemoteSubtitleEmpty.NoMatches else RemoteSubtitleEmpty.None
            )
            is SubtitleCall.Failed -> mutable.value.copy(
                phase = RemoteSubtitlePhase.Results,
                empty = RemoteSubtitlePolicy.failure(call.status)
            )
        }
    }
}
