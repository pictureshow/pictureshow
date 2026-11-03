# Launch subsystem API reference (draft)

Inspected 2026-11-03. **OBSERVED** means operation/schema presence, not a successful authenticated integration. Official 10.10.0 and 10.10.7 OpenAPI were compared with the demo server’s 12.0.0 `/api-docs/openapi.json`. Both 10.10 snapshots have the same relevant operation paths. Do not infer every patch behaves identically.

Sources: [official API documentation](https://api.jellyfin.org/), [10.10.0 schema](https://lon1.mirror.jellyfin.org/main/openapi/stable/jellyfin-openapi-10.10.0.json), [10.10.7 schema](https://lon1.mirror.jellyfin.org/main/openapi/stable/jellyfin-openapi-10.10.7.json). For 12, retrieve the connected server’s own schema; the exact inspected snapshot hashes are below. No private origin is needed in this contract.

| Snapshot | SHA-256 of inspected bytes |
|---|---|
| 10.10.0 | `73d290bf44c91691a49b359a523873b17f703ba80236091c565277d0b6fddfa0` |
| 10.10.7 | `ac915696049d5c5466444b1c00c1906f7d02a54f90e6a7ba117fa7bc99332664` |
| 12.0.0 | `099ba77403761127a3f466a554568946c0918384194851a07da45ce56acb52ec` |

All paths are relative to the configured server base, including reverse-proxy prefix. All media, user data, control and SyncPlay operations use the signed-in viewer’s authorization, never an administrative API key in a shipped client. Public discovery/Quick Connect initiation/time have their own documented authentication rules. Query names use the spelling below; JSON DTO members use PascalCase. HTTP success of a control request proves dispatch, not receiver execution.

Only the methods needed by these subsystems are listed; HEAD variants and unrelated mutation methods are omitted. Body means `application/json`; names before `?` below are path parameters.

## Account, media and offline

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /System/Info/Public` | `GetPublicSystemInfo` | — | Both (10.10 / 12) |
| `GET /Users/Me` | `GetCurrentUser` | — | Both (10.10 / 12) |
| `GET /UserViews` | `GetUserViews` | — | Both (10.10 / 12) |
| `GET /Items` | `GetItems` | — | Both (10.10 / 12) |
| `GET /Items/{itemId}` | `GetItem` | — | Both (10.10 / 12) |
| `GET /UserItems/{itemId}/UserData` | `GetItemUserData` | — | Both (10.10 / 12) |
| `POST /UserItems/{itemId}/UserData` | `UpdateItemUserData` | JSON `UpdateUserItemDataDto` | Both (10.10 / 12) |
| `GET /Items/{itemId}/Download` | `GetDownload` | — | Both (10.10 / 12) |
| `GET /Items/{itemId}/File` | `GetFile` | — | Both (10.10 / 12) |
| `GET /Items/{itemId}/PlaybackInfo` | `GetPlaybackInfo` | — | Both (10.10 / 12) |
| `POST /Items/{itemId}/PlaybackInfo` | `GetPostedPlaybackInfo` | JSON `PlaybackInfoDto` | Both (10.10 / 12) |
| `GET /Videos/{itemId}/stream` | `GetVideoStream` | — | Both (10.10 / 12) |
| `GET /Videos/{itemId}/stream.{container}` | `GetVideoStreamByContainer` | — | Both (10.10 / 12) |
| `GET /Videos/{itemId}/master.m3u8` | `GetMasterHlsVideoPlaylist` | query `mediaSourceId`; — | 10.10.0, 10.10.7 |
| `GET /Audio/{itemId}/universal` | `GetUniversalAudioStream` | — | Both (10.10 / 12) |
| `GET /Audio/{itemId}/stream` | `GetAudioStream` | — | Both (10.10 / 12) |
| `GET /Audio/{itemId}/stream.{container}` | `GetAudioStreamByContainer` | — | Both (10.10 / 12) |
| `DELETE /Videos/ActiveEncodings` | `StopEncodingProcess` | query `deviceId, playSessionId`; — | 10.10.0, 10.10.7 |
| `POST /Sessions/Playing` | `ReportPlaybackStart` | JSON `PlaybackStartInfo` | Both (10.10 / 12) |
| `POST /Sessions/Playing/Progress` | `ReportPlaybackProgress` | JSON `PlaybackProgressInfo` | Both (10.10 / 12) |
| `POST /Sessions/Playing/Stopped` | `ReportPlaybackStopped` | JSON `PlaybackStopInfo` | Both (10.10 / 12) |

## Music and persistent playlists

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /Artists` | `GetArtists` | — | Both (10.10 / 12) |
| `GET /Artists/AlbumArtists` | `GetAlbumArtists` | — | Both (10.10 / 12) |
| `GET /Genres` | `GetGenres` | — | Both (10.10 / 12) |
| `GET /Items/{itemId}/Images/{imageType}` | `GetItemImage` | — | Both (10.10 / 12) |
| `GET /Items/{itemId}/InstantMix` | `GetInstantMixFromItem` | — | Both (10.10 / 12) |
| `POST /Playlists` | `CreatePlaylist` | JSON `CreatePlaylistDto` | Both (10.10 / 12) |
| `POST /Playlists/{playlistId}` | `UpdatePlaylist` | JSON `UpdatePlaylistDto` | Both (10.10 / 12) |
| `GET /Playlists/{playlistId}` | `GetPlaylist` | — | Both (10.10 / 12) |
| `POST /Playlists/{playlistId}/Items` | `AddItemToPlaylist` | — | Both (10.10 / 12) |
| `DELETE /Playlists/{playlistId}/Items` | `RemoveItemFromPlaylist` | — | Both (10.10 / 12) |
| `GET /Playlists/{playlistId}/Items` | `GetPlaylistItems` | — | Both (10.10 / 12) |
| `POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}` | `MoveItem` | — | Both (10.10 / 12) |

## Target sessions and authorization

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /Sessions` | `GetSessions` | — | Both (10.10 / 12) |
| `POST /Sessions/Capabilities/Full` | `PostFullCapabilities` | JSON `ClientCapabilitiesDto` | Both (10.10 / 12) |
| `POST /Sessions/{sessionId}/Playing` | `Play` | query `playCommand, itemIds`; — | Both (10.10 / 12) |
| `POST /Sessions/{sessionId}/Playing/{command}` | `SendPlaystateCommand` | — | Both (10.10 / 12) |
| `POST /Sessions/{sessionId}/Command` | `SendFullGeneralCommand` | JSON `GeneralCommand` | Both (10.10 / 12) |
| `POST /Sessions/{sessionId}/Viewing` | `DisplayContent` | query `itemType, itemId, itemName`; — | Both (10.10 / 12) |
| `GET /QuickConnect/Enabled` | `GetQuickConnectEnabled` | — | Both (10.10 / 12) |
| `POST /QuickConnect/Initiate` | `InitiateQuickConnect` | — | Both (10.10 / 12) |
| `GET /QuickConnect/Connect` | `GetQuickConnectState` | query `secret`; — | Both (10.10 / 12) |
| `POST /QuickConnect/Authorize` | `AuthorizeQuickConnect` | query `code`; — | Both (10.10 / 12) |
| `POST /Users/AuthenticateWithQuickConnect` | `AuthenticateWithQuickConnect` | JSON `QuickConnectDto` | Both (10.10 / 12) |
| `POST /Sessions/Logout` | `ReportSessionEnded` | — | Both (10.10 / 12) |

## Live TV and DVR

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /LiveTv/Info` | `GetLiveTvInfo` | — | Both (10.10 / 12) |
| `GET /LiveTv/Channels` | `GetLiveTvChannels` | — | Both (10.10 / 12) |
| `GET /LiveTv/Channels/{channelId}` | `GetChannel` | — | Both (10.10 / 12) |
| `GET /LiveTv/GuideInfo` | `GetGuideInfo` | — | Both (10.10 / 12) |
| `GET /LiveTv/Programs` | `GetLiveTvPrograms` | — | Both (10.10 / 12) |
| `POST /LiveTv/Programs` | `GetPrograms` | JSON `GetProgramsDto` | Both (10.10 / 12) |
| `GET /LiveTv/Programs/{programId}` | `GetProgram` | — | Both (10.10 / 12) |
| `GET /LiveTv/Recordings` | `GetRecordings` | — | Both (10.10 / 12) |
| `GET /LiveTv/Recordings/{recordingId}` | `GetRecording` | — | Both (10.10 / 12) |
| `DELETE /LiveTv/Recordings/{recordingId}` | `DeleteRecording` | — | Both (10.10 / 12) |
| `GET /LiveTv/Timers/Defaults` | `GetDefaultTimer` | — | Both (10.10 / 12) |
| `GET /LiveTv/Timers` | `GetTimers` | — | Both (10.10 / 12) |
| `POST /LiveTv/Timers` | `CreateTimer` | JSON `TimerInfoDto` | Both (10.10 / 12) |
| `GET /LiveTv/Timers/{timerId}` | `GetTimer` | — | Both (10.10 / 12) |
| `DELETE /LiveTv/Timers/{timerId}` | `CancelTimer` | — | Both (10.10 / 12) |
| `POST /LiveTv/Timers/{timerId}` | `UpdateTimer` | JSON `TimerInfoDto` | Both (10.10 / 12) |
| `GET /LiveTv/SeriesTimers` | `GetSeriesTimers` | — | Both (10.10 / 12) |
| `POST /LiveTv/SeriesTimers` | `CreateSeriesTimer` | JSON `SeriesTimerInfoDto` | Both (10.10 / 12) |
| `GET /LiveTv/SeriesTimers/{timerId}` | `GetSeriesTimer` | — | Both (10.10 / 12) |
| `DELETE /LiveTv/SeriesTimers/{timerId}` | `CancelSeriesTimer` | — | Both (10.10 / 12) |
| `POST /LiveTv/SeriesTimers/{timerId}` | `UpdateSeriesTimer` | JSON `SeriesTimerInfoDto` | Both (10.10 / 12) |
| `POST /LiveStreams/Open` | `OpenLiveStream` | JSON `OpenLiveStreamDto` | Both (10.10 / 12) |
| `POST /LiveStreams/Close` | `CloseLiveStream` | query `liveStreamId`; — | Both (10.10 / 12) |
| `POST /LiveTv/TunerHosts` | `AddTunerHost` | JSON `TunerHostInfo` | Both (10.10 / 12) |
| `DELETE /LiveTv/TunerHosts` | `DeleteTunerHost` | — | Both (10.10 / 12) |
| `POST /LiveTv/ListingProviders` | `AddListingProvider` | JSON `ListingsProviderInfo` | Both (10.10 / 12) |
| `DELETE /LiveTv/ListingProviders` | `DeleteListingProvider` | — | Both (10.10 / 12) |
| `GET /LiveTv/ChannelMappingOptions` | `GetChannelMappingOptions` | — | Both (10.10 / 12) |
| `POST /LiveTv/ChannelMappings` | `SetChannelMapping` | JSON `SetChannelMappingDto` | Both (10.10 / 12) |

## SyncPlay

| Method and path | Operation | Required query / JSON body type | Versions |
|---|---|---|---|
| `GET /GetUtcTime` | `GetUtcTime` | — | Both (10.10 / 12) |
| `GET /SyncPlay/List` | `SyncPlayGetGroups` | — | Both (10.10 / 12) |
| `GET /SyncPlay/{id}` | `SyncPlayGetGroup` | — | 12.0.0 |
| `POST /SyncPlay/New` | `SyncPlayCreateGroup` | JSON `NewGroupRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Join` | `SyncPlayJoinGroup` | JSON `JoinGroupRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Leave` | `SyncPlayLeaveGroup` | — | Both (10.10 / 12) |
| `POST /SyncPlay/SetNewQueue` | `SyncPlaySetNewQueue` | JSON `PlayRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Queue` | `SyncPlayQueue` | JSON `QueueRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/MovePlaylistItem` | `SyncPlayMovePlaylistItem` | JSON `MovePlaylistItemRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/RemoveFromPlaylist` | `SyncPlayRemoveFromPlaylist` | JSON `RemoveFromPlaylistRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/SetPlaylistItem` | `SyncPlaySetPlaylistItem` | JSON `SetPlaylistItemRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/NextItem` | `SyncPlayNextItem` | JSON `NextItemRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/PreviousItem` | `SyncPlayPreviousItem` | JSON `PreviousItemRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/SetRepeatMode` | `SyncPlaySetRepeatMode` | JSON `SetRepeatModeRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/SetShuffleMode` | `SyncPlaySetShuffleMode` | JSON `SetShuffleModeRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Pause` | `SyncPlayPause` | — | Both (10.10 / 12) |
| `POST /SyncPlay/Unpause` | `SyncPlayUnpause` | — | Both (10.10 / 12) |
| `POST /SyncPlay/Seek` | `SyncPlaySeek` | JSON `SeekRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Stop` | `SyncPlayStop` | — | Both (10.10 / 12) |
| `POST /SyncPlay/Buffering` | `SyncPlayBuffering` | JSON `BufferRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Ready` | `SyncPlayReady` | JSON `ReadyRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/Ping` | `SyncPlayPing` | JSON `PingRequestDto` | Both (10.10 / 12) |
| `POST /SyncPlay/SetIgnoreWait` | `SyncPlaySetIgnoreWait` | JSON `IgnoreWaitRequestDto` | Both (10.10 / 12) |

## Wire details implementations must retain

- `GET /Sessions?controllableByUserId={viewerId}&activeWithinSeconds=60` discovers authorized live sessions. Check `SupportsRemoteControl`, media types and `Capabilities.SupportedCommands`; absence is not permission. Register target support with `POST /Sessions/Capabilities/Full` (`SupportsMediaControl`, `PlayableMediaTypes`, `SupportedCommands`, actual `DeviceProfile`). Subscribe over the authenticated Jellyfin `/socket` WebSocket; `Play`, `Playstate`, `GeneralCommand`, `Sessions`, `SyncPlayCommand` and `SyncPlayGroupUpdate` are distinct messages. The WebSocket is not enumerated as an HTTP operation by OpenAPI. Session subscriptions use the server’s `SessionsStart` / `SessionsStop` message protocol; verify envelopes against both running versions.
- `POST /Sessions/{sessionId}/Playing` uses **query parameters**, including required `playCommand` and `itemIds`, optional `startPositionTicks`, `startIndex`, `mediaSourceId`, audio/subtitle indices. A JSON `PlayRequest` body is not the contract for this HTTP operation. `Playing/{command}` accepts `Pause`, `Unpause`, `Stop`, `Seek`, `NextTrack`, `PreviousTrack`, `Rewind`, `FastForward`, `PlayPause`; use desired-state commands for retry-safe UI. Seek uses query `seekPositionTicks`. General commands use JSON `Name`, `Arguments` and optional `ControllingUserId`; validate names/arguments against the target’s advertised list.
- `PlaybackInfoDto`: `UserId`, `DeviceProfile`, `MediaSourceId`, `StartTimeTicks`, bitrate/channel caps, indices, direct/transcode enable flags and `AutoOpenLiveStream`. Use `MediaSources[].TranscodingUrl`, supported direct paths, `RequiresOpening`, `OpenToken`, `LiveStreamId`, `RequiresClosing`, `IsInfiniteStream` and actual streams. Never turn `MediaSourceInfo.Path` (a server filesystem location) into a client URL. Close only a live stream and encoding owned by that viewing; `DELETE /Videos/ActiveEncodings` requires query `deviceId` **and** `playSessionId` in 10.10. It is absent from inspected 12 OpenAPI: do not infer removal or success. Existing Pictureshow code still invokes that exact path with both IDs; qualify the 12 runtime cleanup route without touching another session.
- Downloads: `GET /Items/{itemId}/Download` has the `Download` authorization policy. It serves an original file; it has **no quality, mediaSourceId, scheduling, DRM, entitlement-expiry or offline-sync job parameters**. Enforce `EnableContentDownloading` and item access before any download, including a converted one. Do not bypass a denied download via `/File` or a stream route. Range/validator support must be established per response; media source/version selection uses a proven authorized media stream or reports unsupported selection.
- `GET /Items?userId=…&parentId=…&recursive=true&includeItemTypes=Audio,MusicAlbum,MusicArtist,Playlist` is paged with `startIndex` / `limit`. Use `artistIds` / `albumArtistIds` where needed. Albums sort by `ParentIndexNumber,IndexNumber,SortName` for tracks; server album/artist filtering precedes paging. `/Artists/AlbumArtists`, `/Artists` and `/Genres` supply browse facets. `/Audio/{itemId}/universal` negotiates containers/codecs/bitrate via query for audio; capture redirects privately, never persist signed URLs as catalog identity.
- Playlist queue entry identities differ from media identities: `/Playlists/{playlistId}/Items` returns `PlaylistItemId`; removal query `entryIds` and Move path `{itemId}` identify **playlist entries**, including repeats. Adding uses query `ids` and optional `userId`; create/update playlist JSON retains name/media type/user access and existing unknown fields as appropriate. Read back accepted order; an ad hoc playback queue is not automatically a persisted server playlist.
- `GET /UserItems/{itemId}/UserData?userId=…` and POST with `UpdateUserItemDataDto` exist in **both** inspected versions. Update only desired dirty fields (`PlaybackPositionTicks`, `Played`, optional `LastPlayedDate`); do not overwrite `PlayCount` or `IsFavorite` incidentally. There is no documented compare-and-swap revision or idempotency key. Report endpoints and UserData mutation are different tools; do not fabricate a late online viewing by replaying offline Start/Stop.
- Live guide: query `/LiveTv/Channels` with `userId`, paging and optional favorites; `/LiveTv/Programs` with `channelIds`, `minEndDate`, `maxStartDate` to select overlapping windows (GET query or POST `GetProgramsDto`), and retrieve `/LiveTv/GuideInfo` for guide bounds. UTC instants are authoritative. `/LiveTv/Timers/Defaults?programId=…` supplies `SeriesTimerInfoDto` defaults: project the common timer fields when creating a one-off timer.
- DVR: POST `/LiveTv/Timers` JSON `TimerInfoDto` (`ProgramId`, `ChannelId`, UTC `StartDate` / `EndDate`, padding, priority); POST `/LiveTv/Timers/{timerId}` updates. Series uses corresponding `SeriesTimerInfoDto`, including `RecordAnyTime`, `RecordAnyChannel`, `RecordNewOnly`, `Days`, `KeepUpTo`, `SkipEpisodesInLibrary`, padding/priority. Preserve provider defaults/unsupported fields and refetch after writes. DELETE timer cancels scheduling; DELETE recording deletes media and needs separate confirmation/policy. Read server `Status`, including `ConflictedOk`, `ConflictedNotOk`, `Error`; no universal atomic conflict-preflight/reservation API exists here.
- Both versions support the main DVR list/detail routes. 10.10 additionally exposes `/LiveTv/Recordings/Groups`, `/LiveTv/Recordings/Groups/{groupId}` and `/LiveTv/Recordings/Series`; those are absent from the inspected 12 schema. Use `/LiveTv/Recordings` plus client grouping for the common path.
- Live stream: POST `/LiveStreams/Open` JSON `OpenLiveStreamDto` with returned `OpenToken`, `ItemId`, viewer, `PlaySessionId`, profile and caps when `RequiresOpening`; `AutoOpenLiveStream` can remove this extra open. POST `/LiveStreams/Close?liveStreamId=…` releases the opened resource. Prefer returned source URLs and metadata over hardcoded `/LiveTv/LiveStreamFiles/...` or filesystem URLs. In-progress recordings must negotiate their growing source and real seekable ranges.

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

In 10.10, `SyncPlayGroupUpdateCommandMessage` wraps generic `GroupUpdate` variants. In 12, `SyncPlayGroupUpdateMessage.Data` is discriminated by `Type` with named variants. The observable `GroupId` / `Type` / `Data` semantics remain the adapter boundary. 10.10 also enumerates `CreateGroupDenied` / `JoinGroupDenied`; 12 no longer enumerates those variants, so handle HTTP denial and known errors without relying on these messages. Do not reject valid events because generated SDK class names changed. `GET /SyncPlay/{id}` exists only in inspected 12 and returns `GroupInfoDto`, **not a full playback queue**. 10.10 recovery uses List plus Join and fresh server events; 12 needs fresh queue events too. Never synthesize the missing snapshot from stale local state.

## Additional version boundaries

The inspected 12 schema omits the 10.10 dynamic HLS master/variant/segment operations and `DELETE /Videos/ActiveEncodings`. This is a schema fact, **not proof the running server removed the routes**. Follow returned `TranscodingUrl` for HLS, and separately probe authenticated playback/owned-session cleanup during fixture qualification. No untested replacement cleanup endpoint is invented. 10.10 master HLS requires query `mediaSourceId`; encoding cleanup requires both `deviceId` and `playSessionId`. Media/encoding resource cleanup on 12 remains an explicit runtime acceptance gate.

Other query differences: 12 adds `audioLanguages` / `subtitleLanguages` to GET `/Items` and `position` to POST `/Playlists/{playlistId}/Items`; the inspected 12 audio/video streaming queries omit 10.10’s `breakOnNonKeyFrames`. The common implementation appends playlist items and uses the explicit Move operation rather than depending on 12-only insertion; it omits unsupported query flags. These are observed schema differences, not a statement that undocumented queries are accepted or rejected at runtime.

## Server boundaries

Jellyfin supplies media, policies, metadata, DVR jobs, session command relay and SyncPlay coordination. It does not supply Chromecast/AirPlay device discovery, a generic Cast receiver registration, a Pictureshow cloud relay, local secure storage, offline DRM/revocation, atomic offline conflict resolution, sample-accurate output synchronization or tuner/guide subscriptions. OpenAPI presence does not prove a configured tuner, guide availability, write permission, finite offline HLS, a playable codec or reachable receiver. Each implementation records the runtime capability and failure reason.
