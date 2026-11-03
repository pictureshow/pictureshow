# Launch subsystem API reference (draft)

Inspected 2026-11-03. **OBSERVED** means schema or pinned source presence, not authenticated runtime success. Minimum supported Jellyfin is **10.10**. Leg 84A tests isolated **10.10.7, 10.11.11 and 12.4** (schema version 12.4.0, current stable on this date). Household **12.0.0 remains the unchanged acceptance server**, separate from these disposable fixtures. A supported range below means checked release-line snapshots, not proof of identical runtime behavior on every patch.

Sources: [official API documentation](https://api.jellyfin.org/), [10.10.7](https://lon1.mirror.jellyfin.org/main/openapi/stable/jellyfin-openapi-10.10.7.json), [10.11.11](https://lon1.mirror.jellyfin.org/main/openapi/stable/jellyfin-openapi-10.11.11.json), [12.4](https://lon1.mirror.jellyfin.org/main/openapi/stable/jellyfin-openapi-12.4.json). Version-12 mirror filenames omit the patch digit, for example `jellyfin-openapi-12.4.json`. The source-backed hidden routes are explained below.

| Snapshot | SHA-256 of inspected bytes |
|---|---|
| 10.10.7 | `ac915696049d5c5466444b1c00c1906f7d02a54f90e6a7ba117fa7bc99332664` |
| 10.11.11 | `e29fc369ecae54676caeb50cbbc006b5c4ee959906c00f1e02f6f83db9c227fb` |
| 12.4.0 | `02a6064916b7e08f1f1d8d7ffc1fb1c7623df0e378729fcd7fd353766868036e` |
| Household 12.0.0 served schema (acceptance only) | `099ba77403761127a3f466a554568946c0918384194851a07da45ce56acb52ec` |

All paths are relative to the configured server base, including reverse-proxy prefix. All media, user data, control and SyncPlay operations use the signed-in viewer’s authorization, never an administrative API key in a shipped client. Public discovery/Quick Connect initiation/time have their own documented authentication rules. Query names use the spelling below; JSON DTO members use PascalCase. HTTP success of a control request proves dispatch, not receiver execution.

The v12.4 [session manager](https://github.com/jellyfin/jellyfin/blob/v12.4/Emby.Server.Implementations/Session/SessionManager.cs) deletes the token-backed device record on logout. Integration still verifies repeated-cycle cleanup on every supported version. Server-side logout logging can include the token; this reinforces CTRL-10's external-log disclosure. Temporary receiver/download-set sessions use their dedicated device identity and `POST /Sessions/Logout` authenticated by that token to revoke it. A device identity is not a media-only permission scope; the session retains the viewer's server permissions. Provision through supported user authentication/Quick Connect, never invent an endpoint to mint scoped tokens. Query tokens needed by browser WebSocket, transcode, AirPlay, Cast progressive and Apple package paths are disclosed under CTRL-10; own request hooks prefer headers and redact diagnostics.

Only the methods needed by these subsystems are listed; HEAD variants and unrelated mutation methods are omitted. Body means `application/json`; names before `?` below are path parameters.

## Account, media and offline

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /System/Info/Public` | `GetPublicSystemInfo` | — | 10.10+ |
| `GET /Users/Me` | `GetCurrentUser` | — | 10.10+ |
| `GET /UserViews` | `GetUserViews` | — | 10.10+ |
| `GET /Items` | `GetItems` | — | 10.10+ |
| `GET /Items/{itemId}` | `GetItem` | — | 10.10+ |
| `GET /UserItems/{itemId}/UserData` | `GetItemUserData` | — | 10.10+ |
| `POST /UserItems/{itemId}/UserData` | `UpdateItemUserData` | JSON `UpdateUserItemDataDto` | 10.10+ |
| `GET /Items/{itemId}/Download` | `GetDownload` | — | 10.10+ |
| `GET /Items/{itemId}/File` | `GetFile` | — | 10.10+ |
| `GET /Items/{itemId}/PlaybackInfo` | `GetPlaybackInfo` | — | 10.10+ |
| `POST /Items/{itemId}/PlaybackInfo` | `GetPostedPlaybackInfo` | JSON `PlaybackInfoDto` | 10.10+ |
| `GET /Videos/{itemId}/stream` | `GetVideoStream` | — | 10.10+ |
| `GET /Videos/{itemId}/stream.{container}` | `GetVideoStreamByContainer` | — | 10.10+ |
| `GET /Videos/{itemId}/master.m3u8` | `GetMasterHlsVideoPlaylist` | query `mediaSourceId`; — | 10.10+; hidden from 12 OpenAPI by `IgnoreApi` |
| `GET /Audio/{itemId}/universal` | `GetUniversalAudioStream` | — | 10.10+ |
| `GET /Audio/{itemId}/stream` | `GetAudioStream` | — | 10.10+ |
| `GET /Audio/{itemId}/stream.{container}` | `GetAudioStreamByContainer` | — | 10.10+ |
| `DELETE /Videos/ActiveEncodings` | `StopEncodingProcess` | query `deviceId, playSessionId`; — | 10.10+; hidden from 12 OpenAPI by `IgnoreApi` |
| `POST /Sessions/Playing` | `ReportPlaybackStart` | JSON `PlaybackStartInfo` | 10.10+ |
| `POST /Sessions/Playing/Progress` | `ReportPlaybackProgress` | JSON `PlaybackProgressInfo` | 10.10+ |
| `POST /Sessions/Playing/Stopped` | `ReportPlaybackStopped` | JSON `PlaybackStopInfo` | 10.10+ |

## Music and persistent playlists

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /Artists` | `GetArtists` | — | 10.10+ |
| `GET /Artists/AlbumArtists` | `GetAlbumArtists` | — | 10.10+ |
| `GET /Genres` | `GetGenres` | — | 10.10+ |
| `GET /Items/{itemId}/Images/{imageType}` | `GetItemImage` | — | 10.10+ |
| `GET /Items/{itemId}/InstantMix` | `GetInstantMixFromItem` | — | 10.10+ |
| `POST /Playlists` | `CreatePlaylist` | JSON `CreatePlaylistDto` | 10.10+ |
| `POST /Playlists/{playlistId}` | `UpdatePlaylist` | JSON `UpdatePlaylistDto` | 10.10+ |
| `GET /Playlists/{playlistId}` | `GetPlaylist` | — | 10.10+ |
| `POST /Playlists/{playlistId}/Items` | `AddItemToPlaylist` | — | 10.10+ |
| `DELETE /Playlists/{playlistId}/Items` | `RemoveItemFromPlaylist` | — | 10.10+ |
| `GET /Playlists/{playlistId}/Items` | `GetPlaylistItems` | — | 10.10+ |
| `POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}` | `MoveItem` | — | 10.10+ |

## Target sessions and authorization

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /Sessions` | `GetSessions` | — | 10.10+ |
| `POST /Sessions/Capabilities/Full` | `PostFullCapabilities` | JSON `ClientCapabilitiesDto` | 10.10+ |
| `POST /Sessions/{sessionId}/Playing` | `Play` | query `playCommand, itemIds`; — | 10.10+ |
| `POST /Sessions/{sessionId}/Playing/{command}` | `SendPlaystateCommand` | — | 10.10+ |
| `POST /Sessions/{sessionId}/Command` | `SendFullGeneralCommand` | JSON `GeneralCommand` | 10.10+ |
| `POST /Sessions/{sessionId}/Viewing` | `DisplayContent` | query `itemType, itemId, itemName`; — | 10.10+ |
| `GET /QuickConnect/Enabled` | `GetQuickConnectEnabled` | — | 10.10+ |
| `POST /QuickConnect/Initiate` | `InitiateQuickConnect` | — | 10.10+ |
| `GET /QuickConnect/Connect` | `GetQuickConnectState` | query `secret`; — | 10.10+ |
| `POST /QuickConnect/Authorize` | `AuthorizeQuickConnect` | query `code`; — | 10.10+ |
| `POST /Users/AuthenticateWithQuickConnect` | `AuthenticateWithQuickConnect` | JSON `QuickConnectDto` | 10.10+ |
| `POST /Sessions/Logout` | `ReportSessionEnded` | — | 10.10+ |

## Live TV and DVR

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /LiveTv/Info` | `GetLiveTvInfo` | — | 10.10+ |
| `GET /LiveTv/Channels` | `GetLiveTvChannels` | — | 10.10+ |
| `GET /LiveTv/Channels/{channelId}` | `GetChannel` | — | 10.10+ |
| `GET /LiveTv/GuideInfo` | `GetGuideInfo` | — | 10.10+ |
| `GET /LiveTv/Programs` | `GetLiveTvPrograms` | — | 10.10+ |
| `POST /LiveTv/Programs` | `GetPrograms` | JSON `GetProgramsDto` | 10.10+ |
| `GET /LiveTv/Programs/{programId}` | `GetProgram` | — | 10.10+ |
| `GET /LiveTv/Recordings` | `GetRecordings` | — | 10.10+ |
| `GET /LiveTv/Recordings/{recordingId}` | `GetRecording` | — | 10.10+ |
| `DELETE /LiveTv/Recordings/{recordingId}` | `DeleteRecording` | — | 10.10+ |
| `GET /LiveTv/Timers/Defaults` | `GetDefaultTimer` | — | 10.10+ |
| `GET /LiveTv/Timers` | `GetTimers` | — | 10.10+ |
| `POST /LiveTv/Timers` | `CreateTimer` | JSON `TimerInfoDto` | 10.10+ |
| `GET /LiveTv/Timers/{timerId}` | `GetTimer` | — | 10.10+ |
| `DELETE /LiveTv/Timers/{timerId}` | `CancelTimer` | — | 10.10+ |
| `POST /LiveTv/Timers/{timerId}` | `UpdateTimer` | JSON `TimerInfoDto` | 10.10+ |
| `GET /LiveTv/SeriesTimers` | `GetSeriesTimers` | — | 10.10+ |
| `POST /LiveTv/SeriesTimers` | `CreateSeriesTimer` | JSON `SeriesTimerInfoDto` | 10.10+ |
| `GET /LiveTv/SeriesTimers/{timerId}` | `GetSeriesTimer` | — | 10.10+ |
| `DELETE /LiveTv/SeriesTimers/{timerId}` | `CancelSeriesTimer` | — | 10.10+ |
| `POST /LiveTv/SeriesTimers/{timerId}` | `UpdateSeriesTimer` | JSON `SeriesTimerInfoDto` | 10.10+ |
| `POST /LiveStreams/Open` | `OpenLiveStream` | JSON `OpenLiveStreamDto` | 10.10+ |
| `POST /LiveStreams/Close` | `CloseLiveStream` | query `liveStreamId`; — | 10.10+ |
| `POST /LiveTv/TunerHosts` | `AddTunerHost` | JSON `TunerHostInfo` | 10.10+ |
| `DELETE /LiveTv/TunerHosts` | `DeleteTunerHost` | — | 10.10+ |
| `POST /LiveTv/ListingProviders` | `AddListingProvider` | JSON `ListingsProviderInfo` | 10.10+ |
| `DELETE /LiveTv/ListingProviders` | `DeleteListingProvider` | — | 10.10+ |
| `GET /LiveTv/ChannelMappingOptions` | `GetChannelMappingOptions` | — | 10.10+ |
| `POST /LiveTv/ChannelMappings` | `SetChannelMapping` | JSON `SetChannelMappingDto` | 10.10+ |

## SyncPlay

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /GetUtcTime` | `GetUtcTime` | — | 10.10+ |
| `GET /SyncPlay/List` | `SyncPlayGetGroups` | — | 10.10+ |
| `GET /SyncPlay/{id}` | `SyncPlayGetGroup` | — | 10.11+ |
| `POST /SyncPlay/New` | `SyncPlayCreateGroup` | JSON `NewGroupRequestDto` | 10.10+ |
| `POST /SyncPlay/Join` | `SyncPlayJoinGroup` | JSON `JoinGroupRequestDto` | 10.10+ |
| `POST /SyncPlay/Leave` | `SyncPlayLeaveGroup` | — | 10.10+ |
| `POST /SyncPlay/SetNewQueue` | `SyncPlaySetNewQueue` | JSON `PlayRequestDto` | 10.10+ |
| `POST /SyncPlay/Queue` | `SyncPlayQueue` | JSON `QueueRequestDto` | 10.10+ |
| `POST /SyncPlay/MovePlaylistItem` | `SyncPlayMovePlaylistItem` | JSON `MovePlaylistItemRequestDto` | 10.10+ |
| `POST /SyncPlay/RemoveFromPlaylist` | `SyncPlayRemoveFromPlaylist` | JSON `RemoveFromPlaylistRequestDto` | 10.10+ |
| `POST /SyncPlay/SetPlaylistItem` | `SyncPlaySetPlaylistItem` | JSON `SetPlaylistItemRequestDto` | 10.10+ |
| `POST /SyncPlay/NextItem` | `SyncPlayNextItem` | JSON `NextItemRequestDto` | 10.10+ |
| `POST /SyncPlay/PreviousItem` | `SyncPlayPreviousItem` | JSON `PreviousItemRequestDto` | 10.10+ |
| `POST /SyncPlay/SetRepeatMode` | `SyncPlaySetRepeatMode` | JSON `SetRepeatModeRequestDto` | 10.10+ |
| `POST /SyncPlay/SetShuffleMode` | `SyncPlaySetShuffleMode` | JSON `SetShuffleModeRequestDto` | 10.10+ |
| `POST /SyncPlay/Pause` | `SyncPlayPause` | — | 10.10+ |
| `POST /SyncPlay/Unpause` | `SyncPlayUnpause` | — | 10.10+ |
| `POST /SyncPlay/Seek` | `SyncPlaySeek` | JSON `SeekRequestDto` | 10.10+ |
| `POST /SyncPlay/Stop` | `SyncPlayStop` | — | 10.10+ |
| `POST /SyncPlay/Buffering` | `SyncPlayBuffering` | JSON `BufferRequestDto` | 10.10+ |
| `POST /SyncPlay/Ready` | `SyncPlayReady` | JSON `ReadyRequestDto` | 10.10+ |
| `POST /SyncPlay/Ping` | `SyncPlayPing` | JSON `PingRequestDto` | 10.10+ |
| `POST /SyncPlay/SetIgnoreWait` | `SyncPlaySetIgnoreWait` | JSON `IgnoreWaitRequestDto` | 10.10+ |

## Wire details implementations must retain

- `GET /Sessions?controllableByUserId={viewerId}&activeWithinSeconds=60` discovers authorized live sessions. Check `SupportsRemoteControl`, media types and `Capabilities.SupportedCommands`; absence is not permission. Register target support with `POST /Sessions/Capabilities/Full` (`SupportsMediaControl`, `PlayableMediaTypes`, `SupportedCommands`, actual `DeviceProfile`). Subscribe over the authenticated Jellyfin `/socket` WebSocket; `Play`, `Playstate`, `GeneralCommand`, `Sessions`, `SyncPlayCommand` and `SyncPlayGroupUpdate` are distinct messages. The WebSocket is not enumerated as an HTTP operation by OpenAPI. Session subscriptions use the server’s `SessionsStart` / `SessionsStop` message protocol; verify envelopes against all three isolated versions.
- `POST /Sessions/{sessionId}/Playing` uses **query parameters**, including required `playCommand` and `itemIds`, optional `startPositionTicks`, `startIndex`, `mediaSourceId`, audio/subtitle indices. A JSON `PlayRequest` body is not the contract for this HTTP operation. `Playing/{command}` accepts `Pause`, `Unpause`, `Stop`, `Seek`, `NextTrack`, `PreviousTrack`, `Rewind`, `FastForward`, `PlayPause`; use desired-state commands for retry-safe UI. Seek uses query `seekPositionTicks`. General commands use JSON `Name`, `Arguments` and optional `ControllingUserId`; validate names/arguments against the target’s advertised list.
- `PlaybackInfoDto`: `UserId`, `DeviceProfile`, `MediaSourceId`, `StartTimeTicks`, bitrate/channel caps, indices, direct/transcode enable flags and `AutoOpenLiveStream`. Use `MediaSources[].TranscodingUrl`, supported direct paths, `RequiresOpening`, `OpenToken`, `LiveStreamId`, `RequiresClosing`, `IsInfiniteStream` and actual streams. Never turn `MediaSourceInfo.Path` (a server filesystem location) into a client URL. Close only a live stream and encoding owned by that viewing; `DELETE /Videos/ActiveEncodings` requires query `deviceId` **and** `playSessionId` in 10.10. The route remains in 12.4 source, hidden from OpenAPI by IgnoreApi. Keep the same path/required IDs; 84A checks HLS playback and owned cleanup as ordinary integration assertions, never as an unresolved route-existence gate.
- Downloads: `GET /Items/{itemId}/Download` has the `Download` authorization policy. It serves an original file; it has **no quality, mediaSourceId, scheduling, DRM, entitlement-expiry or offline-sync job parameters**. Enforce `EnableContentDownloading` and item access before any download, including a converted one. Do not bypass a denied download via `/File` or a stream route. Range/validator support must be established per response; media source/version selection uses a proven authorized media stream or reports unsupported selection.
- `GET /Items?userId=…&parentId=…&recursive=true&includeItemTypes=Audio,MusicAlbum,MusicArtist,Playlist` is paged with `startIndex` / `limit`. Artist and album-artist views apply `artistIds` and `albumArtistIds` filters respectively before paging. Albums sort by `ParentIndexNumber,IndexNumber,SortName` for tracks; server album/artist filtering precedes paging. `/Artists/AlbumArtists`, `/Artists` and `/Genres` supply browse facets. `/Audio/{itemId}/universal` negotiates containers/codecs/bitrate via query for audio; capture redirects privately, never persist signed URLs as catalog identity.
- Playlist queue entry identities differ from media identities: `/Playlists/{playlistId}/Items` returns `PlaylistItemId`; removal query `entryIds` and Move path `{itemId}` identify **playlist entries**, including repeats. Adding uses query `ids` and optional `userId`; create/update playlist JSON retains name/media type/user access and existing unknown fields read from the fetched playlist. Read back accepted order; an ad hoc playback queue is not automatically a persisted server playlist.
- `GET /UserItems/{itemId}/UserData?userId=…` and POST with `UpdateUserItemDataDto` exist across all three inspected release lines. Update only desired dirty fields (`PlaybackPositionTicks`, `Played`, optional `LastPlayedDate`); do not overwrite `PlayCount` or `IsFavorite` incidentally. There is no documented compare-and-swap revision or idempotency key. Report endpoints and UserData mutation are different tools; do not fabricate a late online viewing by replaying offline Start/Stop.
- Live guide: query `/LiveTv/Channels` with `userId`, paging and optional favorites; `/LiveTv/Programs` with `channelIds`, `minEndDate`, `maxStartDate` to select overlapping windows (GET query or POST `GetProgramsDto`), and retrieve `/LiveTv/GuideInfo` for guide bounds. UTC instants are authoritative. `/LiveTv/Timers/Defaults?programId=…` supplies `SeriesTimerInfoDto` defaults: project the common timer fields when creating a one-off timer.
- DVR: POST `/LiveTv/Timers` JSON `TimerInfoDto` (`ProgramId`, `ChannelId`, UTC `StartDate` / `EndDate`, padding, priority); POST `/LiveTv/Timers/{timerId}` updates. Series uses corresponding `SeriesTimerInfoDto`, including `RecordAnyTime`, `RecordAnyChannel`, `RecordNewOnly`, `Days`, `KeepUpTo`, `SkipEpisodesInLibrary`, padding/priority. Preserve provider defaults/unsupported fields and refetch after writes. DELETE timer cancels scheduling; DELETE recording deletes media and needs separate confirmation/policy. Read server `Status`, including `ConflictedOk`, `ConflictedNotOk`, `Error`; no universal atomic conflict-preflight/reservation API exists here.
- All three release lines support the main DVR list/detail routes. 10.10/10.11 additionally expose `/LiveTv/Recordings/Groups`, `/LiveTv/Recordings/Groups/{groupId}` and `/LiveTv/Recordings/Series`; those are absent from the inspected 12 schema. Use `/LiveTv/Recordings` plus client grouping for the common path.
- Live stream: POST `/LiveStreams/Open` supports query fields (`openToken`, `itemId`, `userId`, `playSessionId` and caps) and JSON `OpenLiveStreamDto` with corresponding fields plus `DeviceProfile`. Client convention is to populate JSON consistently and omit conflicting query duplicates; 84A records which returned source/session fields were honored on each version. Open only when `RequiresOpening`; `AutoOpenLiveStream` can remove this step. A Stopped report carrying `LiveStreamId` can close the stream automatically. Explicit owned POST `/LiveStreams/Close?liveStreamId=…` treats 404 as already closed, while other errors remain visible cleanup failures. Prefer returned source URLs and metadata over hardcoded `/LiveTv/LiveStreamFiles/...` or filesystem URLs. In-progress recordings must negotiate their growing source and real seekable ranges.

## SyncPlay request and event compatibility

All POSTs below are relative to `/SyncPlay/`. `New`, `Join`, `SetNewQueue` and queue mutations require JSON; no-body `Pause`, `Unpause`, `Stop`, `Leave` still require authorization. Group membership is bound to the caller’s server session. `SyncPlayAccess` user policy is `CreateAndJoinGroups`, `JoinGroups` or `None`.

| POST suffix | Body |
|---|---|
| `New` | `NewGroupRequestDto`: `GroupName` |
| `Join` | `JoinGroupRequestDto`: `GroupId` |
| `Leave` | No JSON body |
| `SetNewQueue` | `PlayRequestDto`: `PlayingQueue`, `PlayingItemPosition`, `StartPositionTicks` |
| `Queue` | `QueueRequestDto`: `ItemIds`, `Mode` |
| `MovePlaylistItem` | `MovePlaylistItemRequestDto`: `PlaylistItemId`, `NewIndex` |
| `RemoveFromPlaylist` | `RemoveFromPlaylistRequestDto`: `PlaylistItemIds`, `ClearPlaylist`, `ClearPlayingItem` |
| `SetPlaylistItem` | `SetPlaylistItemRequestDto`: `PlaylistItemId` |
| `NextItem` | `NextItemRequestDto`: `PlaylistItemId` |
| `PreviousItem` | `PreviousItemRequestDto`: `PlaylistItemId` |
| `SetRepeatMode` | `SetRepeatModeRequestDto`: `Mode` |
| `SetShuffleMode` | `SetShuffleModeRequestDto`: `Mode` |
| `Pause` | No JSON body |
| `Unpause` | No JSON body |
| `Seek` | `SeekRequestDto`: `PositionTicks` |
| `Stop` | No JSON body |
| `Buffering` | `BufferRequestDto`: `When`, `PositionTicks`, `IsPlaying`, `PlaylistItemId` |
| `Ready` | `ReadyRequestDto`: `When`, `PositionTicks`, `IsPlaying`, `PlaylistItemId` |
| `Ping` | `PingRequestDto`: `Ping` |
| `SetIgnoreWait` | `IgnoreWaitRequestDto`: `IgnoreWait` |

`GET /GetUtcTime` returns `RequestReceptionTime` / `ResponseTransmissionTime`. `/SyncPlay/Ping` sends measured ping in milliseconds; it does not return the clock offset. `SendCommand` carries `GroupId`, `PlaylistItemId`, `When`, `PositionTicks`, `Command`, `EmittedAt`. `PlayQueueUpdate` carries server queue entries (`ItemId`, `PlaylistItemId`), current index, start ticks, repeat/shuffle, `LastUpdate`, `IsPlaying`.

In 10.10, `SyncPlayGroupUpdateCommandMessage` wraps generic `GroupUpdate` variants. In 10.11/12, `SyncPlayGroupUpdateMessage.Data` is discriminated by `Type` with named variants. The observable `GroupId` / `Type` / `Data` semantics remain the adapter boundary. 10.10 also enumerates `CreateGroupDenied` / `JoinGroupDenied`; 10.11/12 no longer enumerate those variants, so handle HTTP denial and known errors without relying on these messages. Do not reject valid events because generated SDK class names changed. `GET /SyncPlay/{id}` exists in inspected **10.11.11 and 12.4** (10.11+), returning `GroupInfoDto`, **not a full playback queue**. 10.10 recovery uses List plus Join and fresh events; 10.11/12 also require fresh queue events. Never synthesize the missing snapshot from stale local state.

## Additional version boundaries

The 12.4 controllers [DynamicHlsController](https://github.com/jellyfin/jellyfin/blob/v12.4/Jellyfin.Api/Controllers/DynamicHlsController.cs) and [HlsSegmentController](https://github.com/jellyfin/jellyfin/blob/v12.4/Jellyfin.Api/Controllers/HlsSegmentController.cs) carry `ApiExplorerSettings(IgnoreApi = true)`. They retain `GET /Videos/{itemId}/master.m3u8` and `DELETE /Videos/ActiveEncodings`; the latter still requires `deviceId` and `playSessionId`. The matrix records these as source-backed 10.10+ operations, not removed routes. Master HLS across these versions requires `mediaSourceId`; returned source URLs are authoritative. HLS and owned cleanup are ordinary 84A integration assertions. Schema absence alone establishes no removal: check a version-pinned server controller before reporting a missing capability.

Other query differences: 12 adds `audioLanguages` / `subtitleLanguages` to GET `/Items` and `position` to POST `/Playlists/{playlistId}/Items`; the inspected 12 audio/video streaming queries omit 10.10’s `breakOnNonKeyFrames`. The common implementation appends playlist items and uses the explicit Move operation rather than depending on newer-version insertion; it omits unsupported query flags. These are observed schema differences, not a statement that undocumented queries are accepted or rejected at runtime.

## Server boundaries

Jellyfin supplies media, policies, metadata, DVR jobs, session command relay and SyncPlay coordination. It does not supply Chromecast/AirPlay device discovery, a generic Cast receiver registration, a Pictureshow cloud relay, local secure storage, offline DRM/revocation, atomic offline conflict resolution, sample-accurate output synchronization or tuner/guide subscriptions. OpenAPI presence does not prove a configured tuner, guide availability, write permission, finite offline HLS, a playable codec or reachable receiver. Each implementation records the runtime capability and failure reason.
