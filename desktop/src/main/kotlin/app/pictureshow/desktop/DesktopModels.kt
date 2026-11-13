/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*

enum class DesktopRoute { Loading, Servers, Accounts, SignIn, QuickConnect, Home, Library, Detail, Player, Settings }

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
    val nextEpisode: MediaItem? = null
)

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
    val seasons: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val selectedSeason: MediaItem? = null,
    val settings: DesktopSettings = DesktopSettings(),
    val playback: DesktopPlaybackState? = null
)
