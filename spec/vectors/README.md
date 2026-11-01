# Language-neutral reference vectors

All `.json` files here are executable test inputs, not schemas or documentation snapshots. Discover them recursively.
The current runners accept schema version 1 and the suites `reference`, `application` and `recovery`. There is no update mode.

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
