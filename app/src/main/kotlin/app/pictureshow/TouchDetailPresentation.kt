/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.DETAIL_PENDING_LABEL_GRACE_MILLIS
import app.pictureshow.core.DETAIL_READY_AFTER_PENDING_CUE_MILLIS
import app.pictureshow.core.DetailFailurePresentation
import app.pictureshow.core.DetailPendingFeedback
import app.pictureshow.core.DetailReadyFeedback
import app.pictureshow.core.MediaItem
import app.pictureshow.core.detailActionDescription
import app.pictureshow.core.detailFailureKey
import app.pictureshow.core.detailHeldActionLabel
import app.pictureshow.core.detailPendingPlainLabel
import app.pictureshow.core.detailPendingPresentationLabel
import app.pictureshow.core.detailPendingPresentationVisible
import app.pictureshow.core.detailPlayedRequestNotice
import app.pictureshow.core.detailReadHasFailure
import app.pictureshow.core.detailReadyCue
import app.pictureshow.core.detailRequestedActionLabel
import app.pictureshow.core.detailUnavailableActionLabel
import app.pictureshow.core.isEmptyContainer
import app.pictureshow.core.isWatched
import app.pictureshow.core.presentDetailFailure
import app.pictureshow.core.resumeLabel
import app.pictureshow.core.runtimeLabel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val NOTICE_MILLIS = 6_000L
private const val REQUEST_EXPIRED = "The request expired. Try again when the item loads."
private const val COULD_NOT_SAVE = "The request could not be saved. Try again."
private const val COULD_NOT_SAVE_RELOAD = "The request could not be saved. Reload the item and try again."
private const val NOT_LOADED = "The server has not finished loading. Retry to reload the item."
private const val NO_EPISODES = "No playable episodes are available."
private const val CHANGES_LATER = "Changes will be available when loading finishes."
private const val RELOAD_FIRST = "Reload the item before changing it."

/** One slot of the action grid. [reserve] holds every wording it can take, so a label change never moves a neighbor. */
internal data class TouchDetailAction(
    val target: String,
    val label: String,
    val reserve: List<String>,
    val enabled: Boolean,
    val state: String?,
    val onClick: () -> Unit,
    /** Accepted while the page waits: it shows a check beside its requested wording. */
    val requested: Boolean = false,
    val visible: Boolean = true,
    val icon: String? = null
)

internal data class TouchDetailPresentation(
    val header: MediaItem?,
    val recovery: Boolean,
    val retryWorking: Boolean,
    val failure: String?,
    val writeFailure: String?,
    val status: String?,
    val cue: String,
    val additions: List<String>,
    val actions: List<TouchDetailAction>,
    val onRetry: () -> Unit,
    val abandon: () -> Unit
)

private data class TouchDetailCommand(val generation: Int, val mutation: Boolean, val action: () -> Unit)

/**
 * CAT-08..13 in touch form. Touch has no focus or key queue, so a tap during the wait captures its request
 * here, with the desired value, and an ordered consumer runs it once the page is fresh. The composition owns
 * the consumer, so leaving the destination drops anything not yet started.
 */
@Composable
internal fun rememberTouchDetailPresentation(
    state: CatalogDetailState,
    origin: MediaItem?,
    onPlayed: () -> Unit,
    onFavorite: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (MediaItem, Boolean) -> Unit,
    onPlayOnTv: (MediaItem, Boolean) -> Unit,
    onPlayNext: (MediaItem) -> Unit,
    onAddToQueue: (MediaItem) -> Unit,
    onPlayedRequest: () -> Unit
): TouchDetailPresentation {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle == Lifecycle.State.RESUMED
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }

    // Every loading frame guards commands: taps are captured, so nothing is lost, and nothing acts on stale data.
    val waiting = state.playbackPending || (state.loading && state.error == null)
    var mutationDataReady by remember {
        mutableStateOf(!state.loading && !state.playbackPending && state.error == null && state.item != null)
    }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var pendingExpired by remember { mutableStateOf(false) }
    var expiryReported by remember { mutableStateOf(false) }
    var retryWorking by remember { mutableStateOf(false) }
    var retryBudget by remember { mutableIntStateOf(0) }
    var commandNotice by remember { mutableStateOf<String?>(null) }
    var waitHadRequest by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(state.playbackPending) }
    var frozenHeader by remember { mutableStateOf<MediaItem?>(null) }
    var cue by remember { mutableStateOf("") }

    val readFailure = detailReadHasFailure(state.readFailed, state.error, mutationDataReady)
    val readSettled = !state.loading && state.error == null && !state.readFailed && (!state.playbackPending || state.readSucceeded)
    val recoveryActive = !readSettled && (recoveryMessage != null || readFailure || (pendingExpired && waiting))
    SideEffect {
        if (readFailure && state.error != null) recoveryMessage = state.error
        if (readSettled) {
            if (pendingExpired && recoveryMessage != null) commandNotice = null
            recoveryMessage = null
            pendingExpired = false
        }
        if (state.loading || waiting || state.readFailed) {
            mutationDataReady = false
        } else if (state.error == null && state.item != null) {
            mutationDataReady = true
            if (commandNotice == CHANGES_LATER) commandNotice = null
        }
        if (state.playbackPending && state.error == null) returning = true
    }

    val item = state.item ?: origin.takeIf { waiting || recoveryActive }
    SideEffect { if (returning && waiting && frozenHeader == null) frozenHeader = state.item ?: origin }
    val header = frozenHeader ?: item
    val fresh = !waiting && !recoveryActive
    val playItem = touchDetailPlayItem(state).takeIf { fresh }
    // A new returning model deliberately clears stale data; the route still names what this page can do.
    val pendingItem = state.item ?: origin
    val pendingCanPlay = when (pendingItem?.type) {
        "Movie", "Episode", "Video" -> true
        "Series" -> state.nextUp.isNotEmpty() || state.episodes.isNotEmpty() || !pendingItem.isEmptyContainer
        "Season" -> state.episodes.isNotEmpty() || !pendingItem.isEmptyContainer
        else -> false
    }

    var commandGeneration by remember { mutableIntStateOf(0) }
    val commands = remember { Channel<TouchDetailCommand>(Channel.BUFFERED) }
    var readyQueued by remember { mutableStateOf(emptySet<String>()) }
    var playQueued by remember { mutableStateOf<String?>(null) }
    var pendingQueued by remember { mutableStateOf(emptySet<String>()) }

    val latestError by rememberUpdatedState(state.error)
    val latestBusy by rememberUpdatedState(state.busy)
    val latestItem by rememberUpdatedState(state.item)
    val latestResumed by rememberUpdatedState(resumed)
    val latestPlayItem by rememberUpdatedState(playItem)
    val latestPlaybackReady by rememberUpdatedState(fresh && !state.loading && state.error == null)
    val latestMutationReady by rememberUpdatedState(fresh && !state.readFailed && mutationDataReady && !state.loading && state.item != null)
    val latestOnPlayed by rememberUpdatedState(onPlayed)
    val latestOnFavorite by rememberUpdatedState(onFavorite)
    val latestOnPlay by rememberUpdatedState(onPlay)
    val latestOnPlayOnTv by rememberUpdatedState(onPlayOnTv)
    val latestOnPlayNext by rememberUpdatedState(onPlayNext)
    val latestOnAddToQueue by rememberUpdatedState(onAddToQueue)

    LaunchedEffect(Unit) {
        for ((generation, mutation, command) in commands) {
            snapshotFlow {
                generation != commandGeneration ||
                    (latestError != null && (!mutation || !latestMutationReady)) ||
                    ((if (mutation) latestMutationReady else latestPlaybackReady) && !latestBusy && latestResumed)
            }.first { it }
            if (generation == commandGeneration) {
                if (latestError == null || (mutation && latestMutationReady)) command() else commandNotice = COULD_NOT_SAVE_RELOAD
            }
            // Give optimistic busy/loading state a composition before taking another command.
            repeat(2) { withFrameNanos { } }
        }
    }

    fun abandon() {
        commandGeneration++
        readyQueued = emptySet()
        playQueued = null
        pendingQueued = emptySet()
    }

    fun expirePending() {
        val hadRequest = waitHadRequest
        abandon()
        expiryReported = true
        pendingExpired = true
        recoveryMessage = NOT_LOADED
        commandNotice = if (hadRequest) REQUEST_EXPIRED else "Actions will be available when the item loads."
    }

    // A new playback exit starts over. A plain reload does not: a change queued behind a busy write still runs after it.
    LaunchedEffect(state.playbackPending) {
        if (state.playbackPending) {
            commandGeneration++
            readyQueued = emptySet()
        }
    }
    LaunchedEffect(waiting) {
        if (waiting) {
            waitHadRequest = false
            commandNotice = null
            pendingExpired = false
            expiryReported = false
        } else {
            playQueued = null
            pendingQueued = emptySet()
        }
    }
    // Retry owns a new budget; the model's own deadline and this one end in the same recovery state.
    LaunchedEffect(waiting, retryBudget) {
        if (waiting) {
            delay(ApiTimeouts.REQUEST_MILLIS)
            if (latestError == null && !expiryReported) expirePending()
        }
    }
    LaunchedEffect(state.loading, state.error, state.readAttempt) {
        if (!state.loading || state.error != null) retryWorking = false
    }
    LaunchedEffect(commandNotice) {
        val notice = commandNotice
        if (notice != null) {
            cue = notice
            delay(NOTICE_MILLIS)
            commandNotice = null
        }
    }
    LaunchedEffect(state.error) {
        if (state.error == null && commandNotice in setOf(COULD_NOT_SAVE_RELOAD, RELOAD_FIRST)) commandNotice = null
    }

    fun capture(requested: String, command: () -> Unit): Boolean {
        val generation = commandGeneration
        val accepted = !pendingExpired &&
            !expiryReported &&
            commands.trySend(TouchDetailCommand(generation, false, command)).isSuccess
        if (accepted) {
            waitHadRequest = true
            cue = requested
        } else {
            commandNotice = REQUEST_EXPIRED
            pendingExpired = true
        }
        return accepted
    }

    /** Ready controls stay live while a write is busy: a tap joins the same ordered consumer. */
    fun queueReadyMutation(watched: Boolean) {
        if (!mutationDataReady) {
            commandNotice = if (state.error != null) RELOAD_FIRST else CHANGES_LATER
            return
        }
        val offered = state.item ?: return
        val target = if (watched) "played" else "favorite"
        if (target in readyQueued) return
        val desired = if (watched) !offered.isWatched else !offered.userData.isFavorite
        val queued = commands.trySend(
            TouchDetailCommand(commandGeneration, true) {
                readyQueued = readyQueued - target
                val current = latestItem
                if (current != null) {
                    if (watched && !current.isEmptyContainer) {
                        val notice = detailPlayedRequestNotice(desired, current.isWatched)
                        if (notice == null) latestOnPlayed() else commandNotice = notice
                    }
                    if (!watched && current.userData.isFavorite != desired) latestOnFavorite()
                }
            }
        )
        if (queued.isFailure) {
            commandNotice = COULD_NOT_SAVE
        } else {
            readyQueued = readyQueued + target
            if (watched) onPlayedRequest()
            commandNotice = null
        }
    }

    fun runOnFresh(action: (MediaItem) -> Unit) {
        val target = latestPlayItem
        if (latestPlaybackReady) {
            if (target != null) action(target) else commandNotice = NO_EPISODES
        }
    }

    fun capturePlay(target: String, requested: String, action: (MediaItem) -> Unit) {
        if (playQueued == null && capture(requested) { runOnFresh(action) }) playQueued = target
    }

    fun captureQueue(target: String, requested: String, action: (MediaItem) -> Unit) {
        if (target !in pendingQueued && capture(requested) { runOnFresh(action) }) pendingQueued = pendingQueued + target
    }

    fun captureMutation(watched: Boolean) {
        val target = if (watched) "played" else "favorite"
        if (target in pendingQueued) return
        val wanted = if (watched) pendingItem?.isWatched != true else pendingItem?.userData?.isFavorite != true
        val accepted = capture(detailRequestedActionLabel(target, true)) {
            val current = latestItem
            if (latestPlaybackReady && !latestBusy && current != null) {
                if (watched && !current.isEmptyContainer) {
                    val notice = detailPlayedRequestNotice(wanted, current.isWatched)
                    if (notice == null) latestOnPlayed() else commandNotice = notice
                }
                if (!watched && current.userData.isFavorite != wanted) latestOnFavorite()
            }
        }
        if (accepted) {
            pendingQueued = pendingQueued + target
            if (watched) onPlayedRequest()
        }
    }

    // A short read keeps its plain wording; only a wait the viewer can notice says "when ready".
    val presentationVisible = detailPendingPresentationVisible(waiting, resumed, published = true)
    var heldLabels by remember(presentationVisible) { mutableStateOf(false) }
    LaunchedEffect(presentationVisible) {
        if (presentationVisible) {
            delay(DETAIL_PENDING_LABEL_GRACE_MILLIS)
            heldLabels = true
        }
    }

    val pendingEnabled = !recoveryActive && waiting && state.error == null && pendingCanPlay
    val captureOpen = pendingEnabled && !pendingExpired && !expiryReported
    val known = state.item != null
    val watchedNow = pendingItem?.isWatched == true
    val favoriteNow = pendingItem?.userData?.isFavorite == true

    fun plain(target: String) = detailPendingPlainLabel(target, known, watchedNow, favoriteNow)

    fun label(target: String, requested: Boolean, pending: String) =
        detailPendingPresentationLabel(heldLabels, requested, captureOpen, plain(target), pending)

    val position = playItem?.userData?.playbackPositionTicks ?: 0L
    val primaryFresh = if (position > 0) resumeLabel(position) else detailHeldActionLabel("primary")
    val primaryPending = when {
        !captureOpen -> detailUnavailableActionLabel("primary")
        playQueued == "primary" -> detailRequestedActionLabel("primary", true)
        else -> "Play when ready"
    }

    fun played(): String {
        val wording = if (state.item?.isWatched == true) "Mark unwatched" else "Mark watched"
        return when {
            recoveryActive -> detailUnavailableActionLabel("played")
            !pendingEnabled && waiting -> if (heldLabels) detailUnavailableActionLabel("played") else plain("played")
            pendingEnabled -> label(
                "played",
                "played" in pendingQueued,
                when {
                    !captureOpen -> detailUnavailableActionLabel("played")
                    "played" in pendingQueued -> detailRequestedActionLabel("played", true)
                    else -> if (watchedNow) "Mark unwatched when ready" else "Mark watched when ready"
                }
            )
            "played" in readyQueued -> detailRequestedActionLabel("played", true)
            else -> wording
        }
    }

    fun favorite(): String {
        val wording = if (state.item?.userData?.isFavorite == true) "Unfavorite" else "Favorite"
        return when {
            recoveryActive -> detailUnavailableActionLabel("favorite")
            !pendingEnabled && waiting -> if (heldLabels) detailUnavailableActionLabel("favorite") else plain("favorite")
            pendingEnabled -> label(
                "favorite",
                "favorite" in pendingQueued,
                when {
                    !captureOpen -> detailUnavailableActionLabel("favorite")
                    "favorite" in pendingQueued -> detailRequestedActionLabel("favorite", true)
                    else -> if (favoriteNow) "Unfavorite when ready" else "Favorite when ready"
                }
            )
            "favorite" in readyQueued -> detailRequestedActionLabel("favorite", true)
            else -> wording
        }
    }

    /** A remote action has no core wording: it says "when ready" and "requested" in the same form. */
    fun held(name: String, requested: Boolean, otherPlayQueued: Boolean) = when {
        !pendingEnabled -> name
        requested -> "$name requested"
        !captureOpen || otherPlayQueued || !heldLabels -> name
        else -> "$name when ready"
    }

    fun remote(
        target: String,
        name: String,
        requested: Boolean,
        inPlaySlot: Boolean,
        fromStart: Boolean,
        freshAction: (MediaItem) -> Unit
    ) = TouchDetailAction(
        target = target,
        label = held(name, requested, inPlaySlot && playQueued != null),
        reserve = listOf(name, "$name when ready", "$name requested"),
        enabled = if (pendingEnabled) captureOpen && !(inPlaySlot && playQueued != null && !requested) else fresh && playItem != null,
        state = null,
        requested = requested,
        onClick = {
            when {
                pendingEnabled && inPlaySlot -> capturePlay(target, "$name requested") { latestOnPlayOnTv(it, fromStart) }
                pendingEnabled -> captureQueue(target, "$name requested", freshAction)
                else -> playItem?.let(freshAction)
            }
        }
    )

    val canFreshPlay = fresh && playItem != null

    // One rule for the play slot: once a Play is accepted, every other Play is disabled under its plain name.
    fun playOpen(target: String) = captureOpen && (playQueued == null || playQueued == target)
    val pendingPosition = pendingItem?.userData?.playbackPositionTicks ?: 0L
    val currentItem = state.item
    val playedEnabled = when {
        pendingEnabled -> captureOpen
        else -> fresh && currentItem != null && !currentItem.isEmptyContainer
    }
    val favoriteEnabled = if (pendingEnabled) captureOpen else fresh && currentItem != null
    val stateKnown = fresh && currentItem != null
    val actions = buildList {
        add(
            TouchDetailAction(
                target = "primary",
                label = when {
                    !pendingEnabled -> primaryFresh
                    playQueued != null && playQueued != "primary" -> plain("primary")
                    else -> label("primary", playQueued == "primary", primaryPending)
                },
                // The longest resume wording stands in for any position, so fresh data arriving moves nothing.
                reserve = listOf("Play", "Resume from 0:00:00", primaryFresh, "Play when ready", "Play requested", "Play unavailable"),
                enabled = if (pendingEnabled) playOpen("primary") else canFreshPlay,
                state = null,
                requested = pendingEnabled && playQueued == "primary",
                onClick = {
                    if (pendingEnabled) {
                        capturePlay("primary", detailRequestedActionLabel("primary", true)) {
                            latestOnPlay(it, it.userData.playbackPositionTicks > 0)
                        }
                    } else {
                        playItem?.let { onPlay(it, it.userData.playbackPositionTicks > 0) }
                    }
                }
            )
        )
        add(
            TouchDetailAction(
                target = "played",
                label = played(),
                reserve = listOf(
                    "Mark watched",
                    "Mark unwatched",
                    "Mark unwatched when ready",
                    "Mark watched when ready",
                    "Watched change requested",
                    "Watched unavailable"
                ),
                enabled = playedEnabled,
                state = detailActionDescription(if (currentItem?.isWatched == true) "Watched" else "Unwatched", stateKnown),
                icon = "check",
                onClick = { if (pendingEnabled) captureMutation(true) else queueReadyMutation(true) }
            )
        )
        add(
            TouchDetailAction(
                target = "favorite",
                label = favorite(),
                reserve = listOf("Favorite", "Unfavorite", "Unfavorite when ready", "Favorite change requested", "Favorite unavailable"),
                enabled = favoriteEnabled,
                state = detailActionDescription(if (currentItem?.userData?.isFavorite == true) "Favorite" else "Not favorite", stateKnown),
                icon = if (favoriteNow) "heart" else "heart-outline",
                onClick = { if (pendingEnabled) captureMutation(false) else queueReadyMutation(false) }
            )
        )
        add(
            TouchDetailAction(
                target = "from-start",
                label = when {
                    !pendingEnabled -> "Play from start"
                    playQueued != null && playQueued != "from-start" -> "Play from start"
                    else -> label(
                        "from-start",
                        playQueued == "from-start",
                        when {
                            !captureOpen -> detailUnavailableActionLabel("from-start")
                            playQueued == "from-start" -> detailRequestedActionLabel("from-start", true)
                            else -> "Play from start when ready"
                        }
                    )
                },
                reserve = listOf(
                    "Play from start",
                    "Play from start when ready",
                    "Start requested",
                    "Start unavailable"
                ),
                enabled = if (pendingEnabled) playOpen("from-start") else canFreshPlay,
                state = null,
                requested = pendingEnabled && playQueued == "from-start",
                // CAT: a resume-only action; the returning layout keeps its place until the fresh position is known.
                visible = (returning && waiting) || recoveryActive || position > 0 || pendingPosition > 0 || playQueued == "from-start",
                onClick = {
                    if (pendingEnabled) {
                        capturePlay("from-start", detailRequestedActionLabel("from-start", true)) { latestOnPlay(it, false) }
                    } else {
                        playItem?.let { onPlay(it, false) }
                    }
                }
            )
        )
        add(remote("on-tv", "Play on TV", playQueued == "on-tv", inPlaySlot = true, fromStart = false) { onPlayOnTv(it, false) })
        add(
            remote("play-next", "Play next on TV", "play-next" in pendingQueued, inPlaySlot = false, fromStart = false) {
                latestOnPlayNext(it)
            }
        )
        add(
            remote("add-to-queue", "Add to TV queue", "add-to-queue" in pendingQueued, inPlaySlot = false, fromStart = false) {
                latestOnAddToQueue(it)
            }
        )
        // Last in the grid: its arrival with fresh resume data moves no other action.
        if (position > 0 || playQueued == "on-tv-start") {
            add(
                remote("on-tv-start", "Play on TV from start", playQueued == "on-tv-start", inPlaySlot = true, fromStart = true) {
                    onPlayOnTv(it, true)
                }
            )
        }
    }

    // Failure text keeps one truthful reason for an episode; a transport refinement cannot contradict it.
    val rawReason = (state.error ?: recoveryMessage ?: NOT_LOADED) +
        if (waitHadRequest && pendingExpired) " The previous request expired and will not run." else ""
    var previousFailure by remember { mutableStateOf<DetailFailurePresentation?>(null) }
    val failureKey = detailFailureKey(
        readRecovery = recoveryActive,
        retrying = retryWorking,
        writeAttempt = state.writeAttempt.takeIf { !recoveryActive && state.error != null }
    )
    val presented = presentDetailFailure(previousFailure, failureKey, if (recoveryActive) rawReason else state.error)
    SideEffect { previousFailure = presented }
    val failure = if (recoveryActive) presented?.message ?: rawReason else null
    val writeFailure = presented?.message?.takeIf { failureKey?.startsWith("write:") == true }

    val status = when {
        recoveryActive -> null
        commandNotice != null -> commandNotice
        !waiting || !(state.playbackPending || heldLabels || header == null) -> null
        state.readSucceeded && !state.loading && state.playbackPending && !state.playbackReportFinished -> "Item loaded. Saving progress…"
        state.playbackPending && !state.playbackReportFinished -> "Saving progress…"
        header?.type in listOf("Series", "Season") -> "Loading episodes…"
        else -> "Loading item…"
    }

    // The cue node is one polite live region: pending, request, failure and Ready take turns on it.
    val active = resumed && accessibility.isEnabled
    val pendingFeedback = remember { DetailPendingFeedback() }
    val readiness = remember { DetailReadyFeedback() }
    var pendingCueRevision by remember { mutableIntStateOf(0) }
    var pendingCueQueued by remember { mutableStateOf(false) }
    var readyDelivery by remember { mutableStateOf(false) }
    val primaryPendingLabel = actions.first().label
    SideEffect {
        val cancelled = recoveryActive || state.error != null || waitHadRequest
        if (pendingEnabled && pendingFeedback.update(waiting, heldLabels, "primary", active, cancelled) != null) {
            pendingFeedback.submitted()
            readiness.presented("primary")
            cue = primaryPendingLabel
            pendingCueQueued = true
            pendingCueRevision++
        }
        val ready = readiness.update(
            held = waiting,
            focused = "primary",
            resumed = active,
            usable = playItem != null,
            cancelled = cancelled || readyQueued.isNotEmpty() || state.busy
        )
        if (ready != null) {
            readiness.submitted()
            readyDelivery = true
        }
    }
    LaunchedEffect(pendingCueRevision) {
        if (pendingCueRevision > 0) {
            // TalkBack keeps one waiting utterance per node, so a newer one discards it: let the pending cue finish.
            delay(DETAIL_READY_AFTER_PENDING_CUE_MILLIS)
            pendingCueQueued = false
        }
    }
    LaunchedEffect(cue) {
        if (cue.isNotEmpty()) {
            // The band and labels stay readable; an old cue should not linger as a stop in the swipe order.
            delay(NOTICE_MILLIS)
            cue = ""
        }
    }
    val latestPrimary by rememberUpdatedState(primaryFresh)
    val latestWaitHadRequest by rememberUpdatedState(waitHadRequest)
    LaunchedEffect(readyDelivery) {
        if (readyDelivery) {
            snapshotFlow { pendingCueQueued }.first { !it }
            if (latestError == null && !latestWaitHadRequest) cue = detailReadyCue(latestPrimary)
            readyDelivery = false
        }
    }
    LaunchedEffect(recoveryActive, retryWorking, failure) {
        if (recoveryActive && failure != null) {
            cue = if (retryWorking) "Loading item. Please wait." else "Loading failed. ${failure.trim().trimEnd('.')}. Retry is available."
        }
    }
    LaunchedEffect(writeFailure) {
        if (writeFailure != null) cue = "Change failed. $writeFailure Retry is available."
    }

    val additions = if (!waiting && frozenHeader != null && item != null) {
        val before = frozenHeader!!
        buildList {
            if (item.name != before.name) add(item.name)
            if (item.productionYear != before.productionYear) item.productionYear?.let { add(it.toString()) }
            if (item.officialRating != before.officialRating) item.officialRating?.let { add(it) }
            if (item.communityRating != before.communityRating) item.communityRating?.let { add(communityRatingLabel(it)) }
            if (item.runTimeTicks != before.runTimeTicks) {
                item.runTimeTicks?.let(::runtimeLabel)?.takeIf(String::isNotBlank)?.let { add(it) }
            }
            if (item.overview != before.overview) item.overview?.takeIf(String::isNotBlank)?.let { add(it) }
            if (item.genres != before.genres && item.genres.isNotEmpty()) add(item.genres.joinToString(" · "))
        }
    } else {
        emptyList()
    }

    return TouchDetailPresentation(
        header = header,
        recovery = recoveryActive,
        retryWorking = retryWorking,
        failure = failure,
        writeFailure = writeFailure,
        status = status,
        cue = cue,
        additions = additions,
        actions = actions,
        onRetry = {
            if (recoveryActive) {
                if (!retryWorking) {
                    previousFailure = null
                    retryWorking = true
                    retryBudget++
                    onRetry()
                }
            } else {
                onRetry()
            }
        },
        abandon = ::abandon
    )
}
