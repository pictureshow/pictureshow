# Language-neutral reference vectors

All `.json` files here are executable test inputs, not schemas or documentation snapshots. Discover them recursively.
The current runners accept schema version 1 and the suites `reference`, `application`, `recovery` and `playback-information`. There is no update mode.

```json
{
  "schemaVersion": 1,
  "suite": "reference",
  "sources": ["core/src/commonTest/kotlin/app/pictureshow/core/playback/PlayDecisionTest.kt"],
  "context": { "server": {}, "identity": {}, "account": {} },
  "cases": [
    { "id": "stable-case-id", "operation": "operation-name", "input": {}, "expected": {} }
  ]
}
```

The schematic context above is abbreviated. Reference files contain a full invented `server` (`id`, `name`, `url`,
`version`), client `identity` (`deviceName`, `deviceId`, `version`) and account (`userId`, `token`, nullable `deviceId`).
Account device identity overrides installation identity for authenticated requests and decisions. The
`authorization` operation deliberately uses installation identity unless explicitly overridden. Application fixtures
have per-case source provenance and their own explicit mocked context; see [APPLICATION.md](APPLICATION.md).

Compare JSON structurally, without subset matching or fuzzy numeric comparison. Object member order is immaterial.
Array order, query value multiplicity, null versus absent keys, integers and exact strings matter. Expected wire JSON
uses Jellyfin PascalCase and enums; normalized fixture inputs use the documented lower-camel-case names. Fixture
numbers fit the exact JavaScript integer range. Compute ticks with integer arithmetic: one millisecond = 10,000 ticks,
one tick = 100 ns. Division truncates toward zero; do not round saved positions to seconds.

## Coverage and source boundaries

| File | Production behavior / reference tests |
| --- | --- |
| `network.json` | Server address candidate order/validation; percent-encoded quoted authorization; same-origin checks; base-path resolution; token stripping; image/chapter/trickplay URLs. |
| `profiles.json` | Complete serialized device profiles and PlaybackInfo bodies, bitrate defaults/caps/validation, explicit/null track choices, recovery copy flags. |
| `compatibility.json` | HDR/Dolby Vision base layers, bit depth/profile/level, size/rate, hardware/software evidence, E-AC-3 7.1/TS, containers and subtitle delivery, evaluated against built profiles. |
| `negotiation.json` | Complete prepare request → supplied server response → direct/direct-stream/transcode decision, including account identity, bitrate, track selections and safe stream URL. |
| `playback.json` | Source/method selection, refusal/recovery errors, source order, authenticated headers, URL safety, start/progress/stop payloads, exact tick conversion. |
| `catalog.json` | Home rows' endpoints, browse sort/filter/pagination/bounds, IDs freshness query, detail's batch user-data projection, watched/favorite mutations, five search categories, person items, encoding cleanup and report endpoints. |
| `recovery.json` | Reduced-rate server-seek eligibility/clamping/URL rewrite, copied versus encoded audio, forced video-copy recovery and burn-in subtitle refusal; [RECOVERY.md](RECOVERY.md). |
| `application.json` | Local settings decoding/validation/effective values, server configuration preservation, server-priority track selection, caption accessibility overrides and aliases, resume/final positions, segment safety/boundaries and next-episode adjacency/timing. |
| `player-quality.json` | Explicit viewing quality choices, Auto/manual labels, Home/Remote/Metered automatic ceilings and truthful adaptive quality notices. |
| `playback-settings-layout.json` | Native UI acceptance at the measured 360dp landscape/1.3x text boundary: first setting, current value and Done remain fully visible. Android executes this through real Compose layout; ports owe their native UI runner. The Android preview integration also checks the actual subtitle renderer against system caption scaling and an explicit size override. |
| `settings-presentation.json` | Viewer-facing playback settings labels and capability consequences. |
| `playback-settings.json` | SET-13..21: network caps and IPv6 unique-local classification, adaptation guards and progressive active-read windows (excluding inter-read idle), subtitle style normalization and burn policy, audio/HDR profiles and request channels, credits/resume preferences, tolerant persistence and truthful conversion information. |
| `frame-rate-match.json` | Same-resolution integer cadence selection, fractional timing tolerance, preserving known HDR support and refusing unknown frame rates. A selected mode is a request, not proof the display switched. |
| `playback-start.json` | Rewind only initial saved resume, preserving exact explicit handoff positions and clamping at zero. |

`ProfileEvaluator` is the existing Kotlin test model of the relevant Jellyfin direct-play conditions; it is not a
production negotiation engine. `compatibility` runs the real `DeviceProfileBuilder`, then that reduced independent
model. It returns sorted direct-play failure names. It excludes server policy, actual bitrate/network limits,
permissions, encoder availability and server remux/transcode ranking. **The server chooses compatibility and produces
PlaybackInfo.** `decision` consumes that response; it does not decide from raw file metadata whether a server will
encode. `negotiation` makes that boundary explicit by supplying the response separately from capability inputs.

The full-profile fixtures and compatibility fixtures are Android Media3 reference cases. A port passes them when
translating the shared profile logic for equivalent inputs, while supplying honest native capabilities in its app.
A web or Apple player must not advertise Media3's container/subtitle support if its actual playback stack lacks it.
A genuinely platform-specific profile difference needs a documented exception and native capability tests; changing
the expected Android fixture silently would destroy the shared reference.

## Reference operations

| Operation | Input | Exact output |
| --- | --- | --- |
| `address` | `value`: user server address. | `{ "urls": [candidate URLs in order] }` or `{ "error": "IllegalArgumentException" }`. |
| `authorization` | Optional `deviceName`, `deviceId`, `version`, `token`. | Full `MediaBrowser` header string; null/absent token is omitted. Percent-encode UTF-8 delimiter bytes, including literal plus/percent/quote. |
| `sameOrigin` | Absolute `url`. | Boolean: equal HTTP(S) scheme, case-insensitive host, effective port; reject ambiguous/credential-bearing/malformed URLs. Relative references are not origins. |
| `resolve` | `url`: relative, protocol-relative or absolute reference. | Exact resolved URL. Root-relative Jellyfin paths retain the configured server base path; protocol-relative URLs retain their own authority. Validate origin after resolution, before forwarding auth. |
| `stripApiKey` | `url`. | URL with all recognized token query names removed, including encoded/case variants; preserve other parameters and fragment. |
| `image` | Jellyfin `item`, `width`, optional `height`, `wide` (default false). | Exact public image URL or null. Pixel bounds clamp to at least one; quality is 85. No token. |
| `chapterImage` | `itemId`, `index`, `tag`. | Exact chapter URL with maxWidth 320. |
| `trickplaySheet` | `itemId`, `mediaSourceId`, `width`, `sheet`. | Exact sheet URL with mediaSourceId; no token. |
| `decision` | `itemId`, Jellyfin `response`, optional `mediaSourceId`. | `{method,mediaSourceId,url,headers,playSessionId,transcodeReasons}` or `{unplayable: REASON}`. Source/session fields are explicit null when absent. |
| `reports` | `method`, integer `positionMs`, `paused`, nullable `audioStreamIndex` / `subtitleStreamIndex`, `canSeek`, `failed`. | `{progress,stopped}` containing complete serialized payloads for fixture film/source/session/live. The protocol layer preserves a supplied negative position; app call sites bound their actual positions first. |
| `ticks` | Signed integer `nanoseconds`. | Signed integer tick count; truncate sub-100ns remainder toward zero. |
| `profile` | `capabilities` as below. | Complete serialized Jellyfin DeviceProfile, including defaults and array ordering. |
| `request` | `capabilities`, optional `startMs` (0), nullable source/track indices, and optional `enableDirectPlay`, `enableDirectStream`, `allowVideoStreamCopy`, `allowAudioStreamCopy` (all true). | Complete serialized PlaybackInfo request. Null optionals are omitted; subtitle `-1` remains explicit off. |
| `validateBitrate` | `value`: lower-level bits/sec ceiling. | Positive value or `{error: IllegalArgumentException}`. UI choices use application `validate-bitrate` instead. |
| `compatibility` | `capabilities` and reduced `combo`. | Sorted array of failure reason names; empty means the reduced model finds no direct-play violation. |
| `http` | `action`, action parameters, explicit ordered `responses` (`status`, JSON `body`). | `{requests:[{method,path,query,authorization,body}],result}` from the actual production clients on a mock transport. |

`decision` takes the first playable source in the server's order, or the explicitly requested source; it does not
reorder candidates to prefer a later direct source. `SupportsDirectPlay` wins over `SupportsDirectStream`; either uses
the static original-file URL in today's Android implementation. A nonblank TranscodingUrl is sufficient for transcode
eligibility even when SupportsTranscoding is false. The method name `DirectStream` is the report enum, not proof that
the client has requested a remux. A cross-origin URL yields UNSAFE_STREAM_URL. Required credential header variants
cannot replace account authorization; noncredential required headers remain. These constraints apply to subsequent
media, caption, manifest, segment and redirect requests too; the vectors exercise the core decisions, while native
transport integration must enforce them on every outgoing request.

### Capability input

`capabilities` has required `name`, `videoDecoders`, `display`, `audioDecoders`, `audioOutput`, and optional `settings`.
A video decoder has `codec` (Kotlin enum spelling H264/HEVC/VP8/VP9/AV1/MPEG2/MPEG4/VC1), ordered `profiles`, optional
`maxLevel:{major,minor}`, required `maxWidth`, `maxHeight`, `maxFrameRate`, optional `maxFrameRateAtMaxSize` (defaults to
maxFrameRate), `maxBitDepth` (8), `hdrFormats` ([]), `dolbyVisionProfiles` ([]), `hardware` (true),
`sustainablePerformance:{width,height,frameRate}`, and `profileCapabilities:[{profile,maxLevel,maxBitDepth,hdrFormats}]`.
A missing profile list's *contents* (empty array) means unknown and gets conservative fallback profiles, not permission
to play every profile. The arrays are treated in their supplied order where the reference's sets preserve insertion.

Display has `maxWidth`, `maxHeight`, `maxRefreshRate`, `hdrFormats` ([]). Audio decoder has `codec` (AAC/AC3/EAC3/TRUEHD/
DTS/FLAC/ALAC/OPUS/VORBIS/MP3/MP2/PCM), `maxChannels`, `hardware` (true). Audio output has `pcmChannels`, `passthrough`
([]; AC3/EAC3/EAC3_JOC/DTS/DTS_HD/TRUEHD). HDR enums are HDR10/HDR10_PLUS/HLG/DOLBY_VISION. Settings has
`maxStreamingBitrate` (200000000) and `passthroughEnabled` (false in the low-level capability model; the app preference
is true). Each decoder's HDR/profile/depth/level/performance tuple belongs to it and cannot be combined with a
complementary decoder to invent a capability.

A reduced `combo` has `container`, nullable `videoCodec`, `videoProfile`, `bitDepth`, `rangeType`, `audioCodec`,
`audioChannels`, optional `subtitles` ([]), nullable `width`, `height`, `frameRate`, `level`. Spellings are ffprobe/
Jellyfin strings such as `hevc`, `Main 10`, `DOVIWithHDR10`. It intentionally does not contain bitrate because the
reduced evaluator does not implement server bitrate policy.

### Mock HTTP actions

`catalog.views`, `.resume`, `.nextUp` (optional seriesId), `.latest` (parentId), `.items` (BrowseQuery parameters),
`.item` (itemId), `.seasons` (seriesId), `.episodes` (seriesId, seasonId), `.played` / `.favorite` (itemId, value) call the
real CatalogClient. Browse defaults: parentId library, recursive false, sortBy SortName, descending false,
startIndex 0, limit 60, ids empty. Null includeItemTypes and seriesId are omitted. Indices clamp to >=0; limit to 1–200.
An IDs freshness query uses its dedicated smaller field projection instead of browse pagination/sort parameters.

`search` takes kind MOVIES/SHOWS/EPISODES/COLLECTIONS/PEOPLE and query; `personItems` takes personId/startIndex/limit.
The five searches use separate requests. Fewer than two trimmed characters yields success without a request.
`prepare` takes capabilities, itemId, optional startMs/source/audio/subtitle indices; it calls PlaybackClient.prepare.
`stopEncoding` takes playSessionId and issues device-and-session-scoped DELETE. `reportStart`, `reportProgress`,
`reportStopped` take a full wire `body`, decode it to the production DTO and send it to its actual endpoint.

Request `query` is the complete decoded map of names to ordered arrays of values, so semantically equivalent encoding
of spaces is allowed for API queries while missing or duplicated values fail. Request `path` is the encoded path,
`body` is complete JSON or explicit null for no body; Authorization is exact. `result` is `{kind:success}` or
`{kind:failure,errorKind}`; detail success additionally returns `resumeTicks`, prepare success additionally returns
`outcome`. Response lists are explicit and ordered; an unexpected extra request fails. Catalog 401 maps to EXPIRED,
while playback 401 maps to INVALID_CREDENTIALS in the reference.

`native-regressions.json` adds source binding (including explicit Off and encoded bitmap captions), full-source HLS
positions through an internal track replacement, healthy home-row preservation when another shelf is absent, and
configuration HTTP preservation. `homeRows` invokes production row assembly with unsuccessful sections represented
as empty arrays; runtime error presentation is a separate UI check. `timelineThroughTrackSwitch` consumes two actual
PlaybackInfo responses and reports observed absolute positions, retaining the new session identity without adding
resume time a second time. These fixtures do not establish decoder output or native lifecycle delivery.

The source-binding fixtures initially failed Android's reference on 2026-10-31: its unbound explicit track request
could return a different default on Jellyfin 12. The production prepare path now discovers the preferred source and
reissues explicit selections bound to that source. It does not open the first response's stream URL, so the discovery
step starts no encoder. The final decision retains the bound response's session; ordinary cleanup uses that account
and session. The committed expectations were kept unchanged through the red-to-green fix.

The `native.catalog.projection-503.*` cases require a failed UserData batch projection to fail the complete detail
read for Movie, Episode and Video, as it already does for containers. Android and the initial Apple port returned
primary detail on a non-401 playable projection failure, contradicting CAT-08/11/13 freshness/Retry requirements:
Jellyfin 12's primary projection may carry ambiguous resume/watched state. Both implementations now propagate the
failure. The primary metadata remains available to the UI's retained presentation, but the API does not label its
user state authoritative. The `native.catalog.projection-missing.*` cases also require INVALID_RESPONSE when a
successful batch contains no matching item (empty array or only another ID). That is missing authoritative UserData,
not a usable fallback. Both implementations require a matching item before completing the detail read.

## Playback information and speed (PLAY-25/26)

`playback-information.json` uses suite `playback-information`, schema 1, executed by
`PlaybackInformationVectorsTest`. Cases use exact string expectations. Operations: `reason` takes a server
`reason` code and optional `qualityMbps`; `method` takes a report-enum `method` and invented playback `url`;
`subtitle` takes a Jellyfin `source` and selected `index`; `bitrate` takes nullable bits per second; `speed`
takes a numeric rate and boolean `encoded` and returns the string `true` or `false`. Reasons use device-neutral
wording so TV, phone, Apple and web can share it. Unknown reason strings are never displayed verbatim.
A quality cap is a possible remedy, not proof it is the only conversion cause. The server may rewrite its media
metadata: label that metadata separately from the actual received formats, and never turn a list of candidate
codecs into a claim about the output. An explicit all-copy audio/video URL is remux; otherwise retain the
server's negotiated method. These vectors do not prove real decoder speed, pitch or encoded output behavior.

`conversion-notice` (PLAY-27) takes a report-enum `method`, invented playback `url`, `reasons` (array of
TranscodeReason strings as the server sent them), the platform's `device` noun (`this TV`, `this phone`, `this
browser`), optional Jellyfin `source` (default empty) and optional `subtitleIndex` (default -1, Off). It returns
`title` + newline + `reason` + newline + `fix`, or the string `none` when no notice applies. Each platform shows
`reason fix` as one line and appends its own path to Playback info after it. The cases model observed reason sets: a browser without an HEVC path
(video+audio codec, notice), a browser that copies video and converts audio (no notice), and a Safari remux
(container+codec tag, no notice), plus caps, burn-in, unknown reasons and the all-copy URL. Boundary cases carry a
`description` with the reasoning: a `VideoCodec=copy` URL means remux whatever reasons are listed, except that a
burn-in report always means the video is re-encoded. A missing field, unknown method or misspelled operation must fail
the runner, never pass.

## Shared control trace format (schema 1)

The five control suites below execute `core/control` production reducers through `ControlVectorsTest`.
Each case has `id`, `operation`, `state`, and nonempty `steps`. A step has an `event` and a nonempty
`expected` projection of the returned state. Objects in `expected` recursively select fields; arrays,
strings, booleans and null compare exactly, including JSON type and array order. Omitted expected fields
are deliberately unasserted. Each step feeds its complete actual state into the next step; `queue`
uses the returned `queue` member. Defaults below apply only to input state/events, never to expectations.
Unknown suite, case operation, event operation, or event field is a failure. These traces are Kotlin
reference contracts ready for translation; Apple/web execution is still unproved.

**Exact encoding:** every signed 64-bit control value is a canonical decimal **JSON string**, even zero:
`ticks`, `positionTicks`, `durationTicks`, `generation`, `sequence`, `revision`, `completion`, `ticket`,
`sentAtMs`, `nowMs`, report sequence and command ID. Range is −9223372036854775808 through
9223372036854775807. No leading plus/zero, exponent, fraction, whitespace or negative zero. Kotlin uses
Long; Swift uses Int64; TypeScript must use BigInt/string arithmetic and must not pass these through
Number or JSON numeric decoding. Track indices, move indices and schemaVersion are ordinary small JSON
integers. A millisecond is 10,000 ticks. Signed relative deltas may be negative; positions are nonnegative.
Malformed seek strings are ordinary rejected events, not decoder crashes. Overflowing relative addition
saturates before clamping to [0,durationTicks]. This extends the earlier suites' safe-number encoding;
those existing suites and their immutable expectations retain their original schema.

Gradle packages these five named files in `control-vectors`, and legacy files in `parity-vectors`.
This retains the existing fail-on-unknown legacy runner byte-for-byte. The new runner inventories all
packaged control suites, rejects unknown operations, enforces unique case IDs and runs all trace steps.
There is no snapshot recording/update mode.

### session-owner

Operations: `owner` and `temporary`. `owner` state requires `owner:{server,viewer,device,session,generation,kind}`;
`kind` is local/jellyfin/cast. `local` defaults to owner, candidate is null, phase active, intent empty/paused,
route native, generation owner.generation, reportSequence −1. `ControlIntent` contains entry (nullable), ticks
(0), paused (true), ordered queue entry IDs ([]), repeat (off), stored shuffle ([]), nullable audio/subtitle
indices. IDs are opaque, never credentials or media URLs. Handoff intent is an immutable snapshot.

Owner events: `replace`/`viewer-switch` require a newer identity and reset reporting/route/intent;
`report` requires exact active owner plus a strictly newer sequence; `airplay`/`native-route` retain the
local identity. `select` validates same server/viewer and newer nonlocal target. Then `connect`, `prepare`,
`local-paused`, `ready` use that exact candidate. Prepare emits pause-local; only the local pause acknowledgment
allows request-remote. Readiness must observe exact preserved ticks before ownership commits. `fail`,
`timeout`, `cancel` invalidate the candidate and expose paused Resume here. `disconnect` only requests refresh.
`return` emits stop-remote; `remote-stopped` uses confirmed to choose return-ready versus return-uncertain.
`resume-here` needs a new local generation, and confirmed=true when remote Stop is uncertain. No failure or
network event emits play-local. Effects are instructions to adapters, not evidence an output acted.

`temporary` state has records ([]), effect none, reason null. Each record binds stable server/viewer/device,
requesting sender and generation; phase starts creating, secretRef null. `create` permits one live record per
stable server/viewer/device. `issued` accepts only the bound sender/generation and checks returnedViewer;
a wrong viewer enters cleanup. `end` creates pending-persist; `persisted` permits authenticated logout;
`logout-failed` retains pending-revoke; `reconnect` retries logout before new provisioning. `logout-ok` removes
the record and emits erase-secret. `use` is allowed only while active. secretRef is an opaque private-storage
handle; actual bearer credentials are adapter-owned. This is lifecycle policy, not proof of persistent storage,
HTTP logout, Quick Connect sender addressing, or server device-record cleanup.

### session-command

Operation `command`. Required state owner; defaults: play/pause/seek/stop capabilities, authorized/fresh true,
empty paused observed ControlIntent, maximum Int64 durationTicks, sequence 0, seekTarget/pending null. Each
accepted intent requires matching owner, fresh authorization, advertised capability and increasing positive ID.
Events always carry owner. `intent.value` names play/pause/stop/seek-absolute/seek-relative/play-now. Queue edits use their
separate revision reducer. Track, volume and navigation commands are rejected as unsupported until their
respective observation adapters are implemented; a transport observation cannot confirm them. Seek arguments are `entry` decimal strings; for nonseek intents entry is an optional target
entry ID. Repeated Pause expresses paused=true; there is no toggle operation. A seek's dispatch contains an
absolute bounded ticks target even when the original operation was relative; adapters must apply that absolute
value. Subsequent relative intents accumulate from seekTarget, including while the earlier dispatch is pending.

State pending contains id, operation, nullable entry/ticks/paused, sentAtMs and result sent/confirmed/rejected/
unknown. `http` never confirms playback. `observe` supplies id, nowMs, entry, value (observed ticks) and paused;
only the current sent ID and matching desired fields before 5,000ms confirm it. Stop additionally
requires stopped=true in the observation; paused alone never confirms Stop. Play-now requires an entry ID. `timeout` at 5,000ms makes
it unknown and requests refresh. Late acknowledgments cannot confirm another command. `denied` removes rights;
`disconnect` expires pending intent. `refresh` supplies ticks/entry/paused, authorization and current capabilities;
it enables only new deliberate commands, never dispatches an old one. No event automatically retries a mutation.
Native inputs that still use the accepted player path are explicitly outside this new transport adapter boundary.

### queue-edit

Operations `queue` and `store`. Queue state requires server/viewer. Defaults: entries/order/history/operations/
failed empty, current null, positionTicks/revision/generation/completion zero, paused true, repeat off, shuffled
false. Entries are `{id,media,downloaded:false}`; duplicate media IDs are intentional, entry IDs must be unique.
Order is the stored playback permutation; entries is canonical order. History records navigation history.
Visited defaults to distinct history for initial states and records only this shuffle traversal; Repeat All wrap
starts a new visited set while retaining full Previous history. Previous rebuilds traversal visits before its
selected entry. Shuffle/unshuffle use visited/current, so old traversal history cannot hide upcoming entries.
Every `queue` event includes expected revision/generation (default zero); mismatch refreshes or rejects. A
nonempty operationId deduplicates one successful local operation within this queue generation. Replays are
recognized before revision checking. Reducer output is `{queue,outcome,removed:[]}`. A successful change
increments revision once; a rejected/duplicate operation retains the queue. All returned queues are proposals.

Edits: play-now replaces context/starts first entry; play-next inserts its block after current; append adds
at end. With no current, insertion is at zero and remains stopped. remove uses entry ID and selects the
successor at the removed current index, else final previous; move removes an ID and inserts at final index,
preserving current/clock. During shuffle move changes canonical order while retaining the audible permutation.
clear-upcoming keeps current/history; clear-all stops. Invalid IDs/blocks/indices are rejected.

Queue state also has automatic (default []): entry IDs that only continue what was asked for. play-now, play-next and
append events may carry automatic entries besides entries. play-now needs a named entry and orders named then automatic.
play-next inserts after current, and in canonical order never behind the first upcoming automatic entry; append inserts before the first upcoming automatic entry, else at the end. A named entry
removes any upcoming automatic entry with the same media. Automatic event entries go last, and only for media no
entry in the queue holds, including media already played. An edit with only automatic entries is valid; one with neither is invalid.
Shuffle keeps the permutation's order within non-automatic then automatic entries. Remove, clear and restore keep automatic consistent.

`store` state has committed queue, pending/retry null, ticket 0, effect none, reason null, removed []. `edit` contains
a queue event; a changed proposal becomes pending and emits persist. No second mutation is accepted while
pending. `persisted` with the exact ticket publishes; `persist-failed` rolls back to committed, retains the exact proposal in retry and offers retry.
`retry-persist` moves that exact proposal back to pending with a new ticket; a new edit discards the failed proposal.
Tickets increase across failures; delayed acknowledgments cannot publish another edit. `restore` takes saved,
accessible (validated entry IDs), offline. It checks server/viewer, retains only accessible entries and, offline,
only downloaded entries; removed IDs are disclosed, current is preserved if available, otherwise first retained
at zero. Restores increment generation to invalidate pre-restoration commands, are paused and persist before publish. The adapter supplies accessibility from local storage
when offline: this reducer has no network effect. `partition` switches to an empty paused server/viewer queue
and invalidates old acknowledgments. Storage loading/validation/retry UI remain adapter work.

### queue-order

Operations `queue`, `store` and `transfer`. Same queue schema as queue-edit. repeat accepts off/one/all;
shuffle requires an exact explicit permutation of unvisited upcoming IDs, preserving history/current;
unshuffle restores canonical upcoming order excluding visited IDs. No PRNG is involved.

Replacing/restarting current through play-now/remove-current/previous/play/retry/clear-all also increments the completion token.
end/next/error require the current completion token (default zero), consume it once and increment it even on
stop/error. Natural end honors One; explicit next ignores One; only All wraps. Previous restarts current only
above 30,000,000 ticks; at/below it selects history/previous, first Off/One restarts and All wraps. position
sets nonnegative ticks; play/pause express intent; retry is deliberate and clears failure traversal. Automatic
music error marks one failed entry per traversal, skips only unfailed upcoming entries, and stops when exhausted
even with All. Video error pauses the same entry. Each skip returns skipped-unavailable for adapter disclosure.

`transfer` state has queue, intent, effect none, unavailable []. prepare compares queue/repeat/shuffle/audio/
subtitle support; matchingAudio/matchingSubtitles list the exact supported selected indices (including
explicit −1 Off). Generic track-selection capability alone does not establish a match. An inconsistent
queue/current intent snapshot requests refresh. Missing selections require explicit replacement confirmation before transfer. It retains
position/current/pause and supported tracks, strips unsupported selections only after confirmation. attach
requires a valid authoritative same-server/viewer queue and replaces the phone snapshot; missing snapshot requests
refresh, wrong partition rejects. Adapters must explain unavailable before confirmation and perform transport I/O.

### playback-mode

Operation `mode`. Defaults: ownerKind local, media video, explicitAudio false, group/requestedRoute null, leaving false, effect
none. background emits pause-video for local video or nonexplicit audio, keep-audio for deliberate audio, and
keep-receiver for remote controllers. transport emits group-request when attached to SyncPlay, otherwise
owner-command. route accepts cast/airplay: with a group it emits require-leave, without one select-route. cancel
retains group. leave requests leave-group; only a subsequent confirmed left acknowledgment selects the route.
No external-route SyncPlay clock is started. OS delivery, browser suspension and actual platform playback
continuation require platform qualification; these vectors decide routing only.

### cast-handoff

Operation `cast`, with `reduceCast` as the reference. It takes a viewing from this device to a Cast receiver, and back. State requires `owner`, an `owner` suite state whose `local` is the sender and whose `owner` kind becomes `cast` once the receiver has proved it started. Defaults: `link` idle, `stop` none, `cleanup` none, `mediaSession`, `observed`, `commands`, `stopPurpose`, `returnPaused`, `returnTicks`, `recovery`, `notice` null or absent, `emptyStatus` false. Each returned state resets `effect` to none and `reason` to null unless the event sets them, and the vectors compare `effect` and `owner.effect`, which are instructions to the adapter.

The start of a handoff, in order:

| `op` | Fields | Meaning |
| --- | --- | --- |
| `pick` | `identity`, `intent` | The viewer chose a device. Answers with the `effect` to take or a refusal in `reason`, without starting anything by itself when the device already holds this viewing. |
| `select` | `identity`, `intent` | Begin: `identity` becomes the candidate, phase `selecting`. |
| `link-connecting` | none | The device was reached: `link` connecting, phase `connecting`. |
| `link-connected` | none | Phase `preparing`, effect `pause-local`: the adapter stops the local player, reads it, and answers with `local-paused`. |
| `local-paused` | `ticks`, `paused` (both optional) | The moment the local player stopped. Phase `awaiting-ready`, effect `request-remote`. |
| `observe` | `observation`, `nowMs` | What the receiver reports. Ownership commits only on the candidate's generation, playing or paused, within `CAST_READY_TOLERANCE_TICKS` (150000000 ticks, 15 s) of the requested position. |
| `load-failed`, `load-timeout`, `cancel` | `recovery` (optional) | The candidate is invalidated, and the viewer gets Resume here. |
| `link-lost`, `link-failed` | none | The link went or never came up. A candidate fails. A detached viewing stays detached, and `link-failed` sets `notice` to `connect-failed`. |

**`local-paused`.** `ticks` and `paused` describe one moment, and each replaces its value in `owner.intent`. An absent field keeps the value already there: `ticks` from the pick, and `paused` from the pick. Absent is neither false nor an error: it is what an adapter sends when no local player is involved, such as a Cast request from a details page, so `paused` then keeps whatever the viewing asked for. Where a player exists, `paused` means the viewer's own pause, measured before the adapter stops the player. A pause the app made itself (going to the background, a playback failure, this handoff's own stop) doesn't count. The receiver is then loaded with autoplay equal to `!owner.intent.paused`.

Two consequences to keep, so the ports don't choose differently. A pause the app made stays the app's after the viewer returns: if the app paused for the background and the viewer comes back and picks a TV without pressing Play, the local player shows paused and the TV loads playing. Of the ways a player stops itself, another app taking the audio for good isn't the viewer's pause, so the TV loads playing, and an unplugged headset is, so the TV loads paused. On Android both set `playWhenReady` false, and `onPlayWhenReadyChanged`'s reason tells them apart. A short loss of the audio doesn't change `playWhenReady`: playback holds and carries on by itself, and it reads as not paused.

**`pick` answers.** The adapter switches on these. With a candidate, or a phase other than `active`, `failed` or `disconnected`: reason `handoff-busy`. A local owner or a `failed` phase: the same as `select`. Otherwise, comparing `identity.device` with the owner's device and `intent.entry` with the observed entry (the owner's intent entry when nothing is observed):

| State | `effect` | `reason` | Adapter |
| --- | --- | --- | --- |
| Detached, other device | none | `detached-elsewhere` | Refuses; the viewer ends or forgets the old one first. |
| Detached, same device, same entry | `reconnect` | `already-casting` | Rejoins. No load. |
| Detached, same device, other entry | `reconnect` | `rejoin-to-replace` | Rejoins, keeps the pick, and picks again once the receiver has answered and the viewing is `active`. |
| Attached, `link` not connected | none | `link-not-ready` | Refuses. |
| Same device, same entry | `show-controls` | `already-casting` | Shows the controls. No load. |
| Same device, other entry | `replace-on-link` | null | Ends the old viewing's report and session, then the new one starts on this link: `link` stays connected. |
| Other device | `replace-elsewhere` | null | The same, then ends the session, joins the new device, and `link` starts idle. |

**`observation`.** Field and default: `mediaSession` -1 (negative means the receiver holds no media), `generation` -1 (what our load carried; negative when the media isn't ours), `playerState` `idle` (`idle`, `playing`, `paused`, or a state such as `buffering` that isn't an answer yet), `idleReason` null (`finished`, `error`, `cancelled`, ...), `ticks` 0, `durationTicks` null (unknown or live length, not zero), `entry` null, `activeTracks` empty, `queue` empty, `commands` `play`, `pause`, `seek`, `stop`. Longs are decimal strings. A reading is rejected with `stale-observation` when the target isn't a Cast identity, `link` isn't connected, the phase isn't `awaiting-ready`, `returning`, `return-uncertain`, `disconnected` or `active`, the generation is older than the target's, or `mediaSession` is lower than the bound one. Exceptions: with a local owner, an idle reading settles a `stop` of `unknown` (`stop` confirmed, effect `cleanup`); a newer `mediaSession` on the same generation is the same viewing, restarted. A newer generation, or a negative one with media, is someone else's media: it is `stale-observation` while `awaiting-ready`, confirms a pending Stop, and otherwise ends the viewing with notice `receiver-taken` (or the idle reason's notice). A reading with no media is `stale-observation` while `awaiting-ready` and confirms a pending Stop. Otherwise, with no `idleReason`, it is answered once with effect `request-status`, reason `unconfirmed-empty`; a second one in a row ends the viewing. `idleReason` maps to a notice: `finished` to `receiver-finished`, `error` to `receiver-error`, anything else to `receiver-stopped`.

The remaining events:

| `op` | Fields | Behaviour |
| --- | --- | --- |
| `link-suspended` | none | `link` suspended; forgets the observation, keeps its position in `owner.intent`, marks commands stale. |
| `progress` | `ticks` | Moves `observed.ticks` between statuses, capped at the duration, only while active, connected and `playing`. Otherwise `stale-observation`, or `position-held` when not playing, `ticks` is zero or less, or a seek is on its way. |
| `command` | `id`, `value`, `entry`, `nowMs` | Runs `reduceCommand`'s intent; effect `send-command` when it should be sent, its rejection in `reason`. `not-attached` unless active and connected. |
| `command-timeout` | `id`, `nowMs` | Times out the pending command; effect `request-status` if the receiver should be asked. |
| `command-rejected` | `id` | Marks that sent command rejected and drops its seek target; any other id is `stale-command`. |
| `disconnect` | none | Detaches: phase `disconnected`, `link` idle, notice `receiver-continues`, effect `detach`, position kept. The receiver plays on. |
| `stop` | none | Phase `returning`, `stop` sent, `stopPurpose` `end`, cleanup pending, effect `stop-remote`. Records `returnPaused` and `returnTicks` once. |
| `play-here` | none | The same with `stopPurpose` `here`, and the local player starts once it is confirmed (effect `resume-here`). |
| `retry-stop` | none | Only with `stop` `unknown`: `stop` again, keeping the recorded return values. Otherwise `stale-stop`. |
| `stop-timeout` | none | Only with `stop` `sent`: phase `return-uncertain`, `stop` `unknown`, effect `stop-unknown`, or `require-choice` for `here`. Reason `remote-may-continue`. |
| `cleanup-done` | none | `cleanup` done, from pending; otherwise `stale-cleanup`. |
| `resume-here` | `identity`, `confirmed` | From `failed` or `return-ready`, or `return-uncertain` with `confirmed`, otherwise `explicit-choice-required`. A new local owner, effect `play-local`, at `returnTicks` and `returnPaused` when recorded. |
| `report` | `sequence`, `identity` (optional) | Effect `report` for a higher sequence on the current owner. `stale-report` otherwise; `not-attached` for a Cast owner that isn't connected and observed. |
| `viewer-switch`, `replace` | `identity`, `intent` | A new local owner on a higher generation (otherwise `stale-generation`), `link` idle, effect `detach`. |

Vocabulary. A port needs these as written, because the vectors assert them.

- `owner.phase`: `active`, `selecting`, `connecting`, `preparing`, `awaiting-ready`, `failed`, `disconnected`, `returning`, `return-ready`, `return-uncertain`.
- `link`: `idle`, `connecting`, `connected`, `suspended`, `lost`. `stop`: `none`, `sent`, `confirmed`, `unknown`. `stopPurpose`: `end`, `here`, `ended`. `cleanup`: `none`, `pending`, `done`.
- `effect`, an instruction to the adapter:
  - Starting: `pause-local` (stop the player, read it, answer `local-paused`), `request-remote` (send the load), `release-local` (the receiver has it, so let the player go).
  - Picks: `reconnect`, `show-controls`, `replace-on-link`, `replace-elsewhere`.
  - Attached: `request-status`, `send-command`, `report`, `detach` (end the session, leaving the receiver).
  - Ending: `stop-remote`, `stop-unknown` and `require-choice` (ask the viewer), `cleanup` (last report, encoder and session), `resume-here` (offer it, or start it for `here`), `play-local`.
- `reason`:
  - Picks: `already-casting`, `rejoin-to-replace`, `detached-elsewhere`, `link-not-ready`, `handoff-busy`, `invalid-target`.
  - Readings: `stale-observation`, `invalid-observation`, `not-ready`, `position-outside-tolerance`, `position-held`, `unconfirmed-empty`.
  - Late or refused events: `stale-owner`, `stale-target`, `stale-report`, `stale-stop`, `stale-cleanup`, `stale-link`, `stale-command`, `stale-generation`, `not-attached`, `explicit-choice-required`, `remote-may-continue`.
  - From `reduceCommand`: `refresh-required`, `unsupported`.
- `recovery`, why a handoff failed: `cancelled`, `connect-failed`, `link-lost`, `receiver-interrupted`, `receiver-load-failed`, `receiver-timeout`, and whatever the adapter passes with `load-failed`, such as `unsupported-media` or `route-gone`.
- `notice`, what the viewer is told: `receiver-continues`, `connect-failed`, `link-lost`, `receiver-finished`, `receiver-error`, `receiver-stopped`, `receiver-taken`.

A seek confirms within `CAST_SEEK_TOLERANCE_TICKS` (50000000 ticks, 5 s) of its target. Field encoding follows the rules above: `ticks` and `generation` are decimal strings. These vectors are the reference for the reducer only; the hold on the next step, the drop at the end of a file, and what the adapter does with the receiver session live in the host and are specified in CAST-15 and CAST-23.

## grid-navigation

A vertical D-pad move's outcome, the end of a held Down, the pinned strip's words and where a fixed head goes, in
`APPLICATION.md`. Runs through `GridNavigationVectorsTest` (Gradle packages it in `grid-vectors`). Web lists it as
`it.todo` through `pendingPortSuites` until it ports it.

## Remote (REM) trace format

`remote-targets`, `remote-wire`, `remote-receive`, `remote-reconnect` and `remote-queue` use the shared trace format and encoding
above and run through `RemoteVectorsTest` (Gradle packages them in `remote-vectors`). Web lists them as `it.todo`
through `pendingPortSuites` until it ports them; delete a name there when the port lands.

Operations: `targets` (state `sessions`, `viewer`, `selfDevice`, the server's PascalCase JSON as received;
expected `targets`), `request` (state `target`, event a command; expected `request` with exact `method`, `path`, `query`,
`body`, or `rejection`), `parse` (event `text`; expected the validated message), `capabilities`, `advertised`,
`relay-rights` (`version`; expected `enforces`), `socket-url` (`server`; expected `url`), `targets-path` (`viewer`;
expected `path`), `receive` (the target's gate: `message`, `viewing`, `resolved`, `field`, `policy`, `tick`, `disconnect`,
`reconnect`, `account`), `queue` (the target's queue, state `{queue}`; events `open`, `add-next`, `add-last`, `observed`, `previous`, `clear`; expected `current` and `upNext`, media IDs) and `controller` (the controller's states: `attach`, `detach`, `account`, `refresh`, `resume`,
`intent`, `sent`, `tick`).

Fixed behaviour worth reading before porting: a 2xx from a send never confirms anything; only a later `refresh` can.
Seek confirms when the observed position lands in [target - 1 s, target + 6 s]; pause, stop and play-now confirm by
state, and next, previous, track and volume by their own observation. Nothing is retried, held input dies at 5 s or on
reconnect, and PlayPause resolves against the viewing's observed state.

## Offline (OFF) trace format

`offline-job`, `offline-budget`, `offline-resume`, `offline-manifest`, `offline-outbox`, `offline-plan`, `offline-reconnect` and `offline-fmp4` use
the shared trace format and run through `OfflineVectorsTest` (Gradle packages them in `offline-vectors`). Web lists them as
`it.todo` through `pendingPortSuites` until it ports them. Each step's output feeds the next step's state. An expected object
is a projection of the output, except that `{}` and any `reservations` object compare exactly. Outbox ticks and sequences are
decimal strings; byte counts in these fixtures fit the safe integer range and are plain numbers.

Operations: `job`, `admit`, `budget`, `default-cap`, `range-header`, `decision`, `body-end`, `verify`, `blockers`, `commit`,
`reconcile`, `decode`, `roundtrip`, `track-switch`, `entry-id`, `entry-dir`, `safe-path`, `preview`, `outbox`, `download-set`
(`reduceTemporary` under a download-set identity), `sha256`, `device`, `presets`, `select`, the `*-request` builders,
`box-header`, `parse-moof`, `parse-moov`, `index-boxes`, `reconnect-tracks`, `reconnect-decision`, `reconnect-track-on-server` and
`handoff-selection`. `{"error": true}` means the call refuses its input.

Fixed behaviour worth reading before porting: Ready is reachable only through `committed`, and `commit` checks blockers first.
Reconcile lists only safe paths under no entry's directory, never touches a file directly in the root, and recomputes used bytes
from what it kept. A cap below used bytes evicts nothing and answers `used-over-cap`. The outbox checks `current == desired`
before `current == base`, so a replay never writes, and a read always precedes a retry. An index placeholder is `free` then
`sidx`, because `first_offset` counts from the end of the sidx and 0 must land on the first moof.
### music-catalog, music-queue, music-session, music-reports, music-quality, music-playlists

Frozen by the Android reference for MUS-01 to MUS-09, run on Kotlin by `MusicVectorsTest`, and owed by the web and Apple
runners as visible todos. `music-catalog` freezes each browse request's route and scope, disc-then-track order with its
fallbacks, page merging, a genre's tracks loaded only on Play, and the empty, inaccessible and failed states. `music-queue` adds `entry` and `permutation` to
`play-now`, so a selected track starts inside its context and Shuffle plays a whole order, and freezes the output window,
the smallest edit to it, the replace-queue decision and the seeded shuffle. `music-session` keeps the listener's intent
apart from what the OS did. `music-reports` gives one viewing per time a track is heard. `music-quality` freezes the
ceilings, the audio profile and the lossless claim. `music-playlists` refuses to send an edit again after an ambiguous
answer until the list has been read back, and pins the create body: `IsPublic` is always sent and false unless the viewer chose
otherwise, since the server's own default is public.


Android settings UI checks distinguish the merged accessibility row from its rendered text. A row that replaces
its accessibility semantics can still have visible text children in Compose's unmerged test tree. Geometry checks
select that visual text, while separate native AccessibilityNodeInfo checks verify the exported checked state.
