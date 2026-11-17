/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*

enum class DesktopRoute { Loading, Servers, Accounts, SignIn, QuickConnect, Home, Search, Person, Library, Detail, Player, Settings, LiveTv,
    /** Stream C2: SEERR-03..06 and INFO-01..08. */
    Requests, SeerrTitle, About, Licenses, License, Supporter, ThisComputer }

data class DesktopSettings(
    val bitrateMbps: Int? = null,
    val conversionNotice: Boolean = true,
    val autoplayNext: Boolean = true,
    val volume: Int = 80,
    val largeInterface: Boolean = false
) {
    fun preferences() = PlaybackPreferences.decode(bitrateMbps = bitrateMbps, conversionNotice = conversionNotice)
}

data class DesktopPlaybackState(
    val item: MediaItem,
    val decision: PlayDecision,
    val notice: ConversionNotice? = null,
    val audioTracks: List<MediaStream> = emptyList(),
    val subtitleTracks: List<MediaStream> = emptyList(),
    val audioIndex: Int = -1,
    val subtitleIndex: Int = -1,
    val paused: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val ready: Boolean = false,
    val nextEpisode: MediaItem? = null,
    val muted: Boolean = false,
    /** Set once the viewing reached its physical end (PLAY-17): the end card replaces the transport. */
    val end: DesktopEndCard? = null,
    val liveChannel: app.pictureshow.core.livetv.LiveChannel? = null,
    val liveWindow: ObservedLiveWindow? = null,
    val bufferExpired: Boolean = false,
    val received: DesktopReceivedFormats = DesktopReceivedFormats()
)

/**
 * The card shown after a genuine end. [next] null means "Finished" (a film or the last episode).
 * [secondsLeft] counts down to the next episode; null means no countdown (autoplay off or cancelled).
 */
data class DesktopEndCard(val next: MediaItem?, val secondsLeft: Int? = null)

/** PLAY-17: the return to a title after it played to its end, for that title's page only. */
data class DesktopFinished(val itemId: String, val line: String)

/**
 * The Finished card's Back to title carries the line; Stop mid-viewing, an Up next card and live TV don't. Desktop has no SyncPlay,
 * so a group ending can't reach here.
 */
internal fun desktopFinished(p: DesktopPlaybackState?): DesktopFinished? = p
    ?.takeIf { it.end != null && it.end.next == null && it.liveChannel == null && !it.decision.isLive }
    ?.let { DesktopFinished(it.item.id, app.pictureshow.core.playbackFinishedLine(it.item.name)) }

/** The title page's primary action: "Play again" from the start on the return from its end; otherwise Resume or Play. */
internal fun desktopDetailPlay(item: MediaItem, finished: DesktopFinished?): Pair<String, Boolean> = when {
    finished?.itemId == item.id -> "Play again" to true
    item.userData.playbackPositionTicks > 0 -> resumeLabel(item.userData.playbackPositionTicks) to false
    else -> "Play" to false
}

data class DesktopState(
    val route: DesktopRoute = DesktopRoute.Loading,
    val account: Account? = null,
    val accounts: List<Account> = emptyList(),
    val server: Server? = null,
    val serverAddress: String? = null,
    val rememberedServers: List<Server> = emptyList(),
    val discovered: List<DiscoveredServer> = emptyList(),
    val discovering: Boolean = false,
    val publicUsers: List<User> = emptyList(),
    /** Saved-card reauthentication supplies only the username; passwords stay local to SignInForm. */
    val signInUsername: String? = null,
    val quickCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val errorKind: ErrorKind? = null,
    val homeRows: List<HomeRow> = emptyList(),
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val library: MediaItem? = null,
    val sortBy: String = "SortName",
    val descending: Boolean = false,
    val detail: MediaItem? = null,
    val detailLibrary: String? = null,
    val seasons: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val selectedSeason: MediaItem? = null,
    val settings: DesktopSettings = DesktopSettings(),
    val playback: DesktopPlaybackState? = null,
    /** TOUCH-02: the sidebar root this page was opened under, stored when it opens; Detail keeps its opener's root. */
    val root: String? = null,
    /** A control tag the next page focuses once, e.g. "play" on the title after a film finishes (PLAY-17). */
    val returnFocus: String? = null,
    /** PLAY-17: set by Back to title after a genuine end; any other page change clears it. */
    val finished: DesktopFinished? = null,
    val search: DesktopSearchState = DesktopSearchState(),
    val person: MediaItem? = null
)

internal const val DESKTOP_ROOT_HOME = "home"
internal const val DESKTOP_ROOT_SEARCH = "search"
internal const val DESKTOP_ROOT_SETTINGS = "settings"
internal fun desktopLibraryRoot(id: String) = "library:$id"
/** A root page owns itself; any other page keeps the root it was opened from. */
internal fun desktopRootFor(route: DesktopRoute, previous: String?): String? = when (route) {
    DesktopRoute.Home -> DESKTOP_ROOT_HOME
    DesktopRoute.Search -> DESKTOP_ROOT_SEARCH
    DesktopRoute.Settings -> DESKTOP_ROOT_SETTINGS
    DesktopRoute.Requests -> DESKTOP_ROOT_REQUESTS
    else -> previous
}
