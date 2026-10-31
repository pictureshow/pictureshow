# PictureshowCore

Foundation-only Swift translation of the Kotlin core used by the Apple vertical slice. Supports iOS 17, tvOS 17, and macOS 14 (for command-line XCTest). It has no third-party dependencies.

The package includes server address discovery, Jellyfin password and Quick Connect authentication, catalog shelves and detail projection, season/episode browsing, a conservative AVFoundation device profile, playback negotiation, same-origin URL/authentication rules, and playback lifecycle reports. An account retains the device ID that acquired its token. Diagnostics redact account and Quick Connect credentials.

The platform app owns credential storage, AVPlayer, focus, and the authenticated streaming transport. Artwork uses `imageData(account:url:)`, which rejects another origin before authenticating, uses the origin-pinned session, strips legacy URL credentials, and checks the final response origin and status. Scope image caches to the account as well as the URL. `PlayDecision.url` never contains an account token. Core validates the initial stream origin and authenticates API/artwork requests with origin-pinned redirects. The renderer constructs the final media URL and owns media authentication. AVKit and external AirPlay receivers fetch their own nested manifests, segments and redirects; these are not intercepted by the core API session. See the app README for the direct-server viewer-ApiKey URL decision and its exposure/trust boundary.

Jellyfin VOD HLS advertises the full source timeline. Seek to `initialSeekSeconds` after AVPlayer becomes ready and report `serverPositionTicks(playerSeconds:)`; adding the resume position again would corrupt progress. Track changes renegotiate playback at the current absolute position. Embedded MP4/MOV timed text (`mov_text`/`tx3g`) is advertised as a native embedded stream. Explicit subtitle selections use server burn-in; audio changes use HLS unless the app maps native media selections itself.

Jellyfin 12 applies explicit audio/subtitle indices only when the playback request binds a `MediaSourceId`. Pass a known detail source ID to `prepare` for one request; otherwise, when explicit tracks are supplied, the client discovers the preferred source and negotiates again with its ID. This includes subtitles Off (`-1`), which otherwise can silently become the server default external subtitle and force HLS.

`homeContent(account:)` returns healthy rows and section warnings. An expired sign-in still throws. Detail requests refresh UserData through the same list projection used by Continue watching, matching Jellyfin 12's behavior. A failed projection fails the complete detail read, including Movie/Episode/Video, so the app can retain its freshness/Retry barrier instead of publishing ambiguous primary resume/watched state. A successful batch without the requested item still retains the pre-existing primary-detail fallback; it remains an explicit freshness limitation.

Run the XCTest ports from the repository root:

```sh
swift test --package-path apple/PictureshowCore
PICTURESHOW_SPEC_DIR="$PWD/spec" swift test --package-path apple/PictureshowCore
```

The vector runner discovers the repository's `spec/vectors/` recursively by default; `PICTURESHOW_SPEC_DIR` overrides
that directory's parent for standalone package checks. Missing fixtures, unknown schema/suite/operation, duplicate
IDs and empty suites fail. There is no snapshot-update mode or skipped vector family. The adapter structurally
compares complete outputs with the shared committed expectations, including HTTP method/path/query/header/body and
returned error/outcome. The suite ports caption language/index ranking, final position policy, settings validation,
configuration preservation, segments/continuation, reports, account identity and encoder cleanup, plus network,
negotiation and recovery.

`ReferenceDeviceProfile` translates the shared Android demux/capability policy for equivalent capability inputs,
including its conservative decoder envelopes and HDR/profile constraints. It is separate from `AppleProfile`, which
is the app's conservative AVFoundation protocol profile. Passing Android compatibility fixtures is not a claim that
AVFoundation decodes Matroska, PGS, FFmpeg audio or Android passthrough formats. The reduced `ProfileEvaluator` port
lives in tests and models only the documented server conditions, not real playback.

`PlaybackPreferences` decodes corrupt stored choices to Android defaults; `UserConfiguration` preserves unknown
JSON values. `Captions` supplies the shared accessibility language/track policy. `PlaybackExtras` supplies chapter,
segment, trickplay and chronological adjacency helpers; the app owns the actual renderer, skip/next interaction and
native caption appearance/timing capabilities. `PlaybackEndPosition.completed` is for confirmed physical EOS only,
not an ordinary pause/seek/Stop.

The XCTest ports also cover request construction through a mock URLProtocol: account-bound identity, password and
Quick Connect, detail resume projection, partial home failures, track-bound negotiation, progress/stop DTOs, expired
sessions, and session-scoped cleanup. Additional shared regression fixtures exercise source discovery and bound
Off/burn-in negotiation, absolute HLS position through a track replacement, partial home row assembly, and lossless
configuration read/save. Decoder/focus/spoken-output/runtime checks remain separate acceptance evidence.
