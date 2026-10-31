# PictureshowCore

Foundation-only Swift translation of the Kotlin core used by the Apple vertical slice. Supports iOS 17, tvOS 17, and macOS 14 (for command-line XCTest). It has no third-party dependencies.

The package includes server address discovery, Jellyfin password and Quick Connect authentication, catalog shelves and detail projection, season/episode browsing, a conservative AVFoundation device profile, playback negotiation, same-origin URL/authentication rules, and playback lifecycle reports. An account retains the device ID that acquired its token. Diagnostics redact account and Quick Connect credentials.

The platform app owns credential storage, AVPlayer, focus, and the authenticated streaming transport. Artwork uses `imageData(account:url:)`, which rejects another origin before authenticating, uses the origin-pinned session, strips legacy URL credentials, and checks the final response origin and status. Scope image caches to the account as well as the URL. `PlayDecision.url` never contains an account token. Every actual media request, including manifests, child playlists, segments and redirects, must pass the same-origin gate before credentials are attached.

Jellyfin VOD HLS advertises the full source timeline. Seek to `initialSeekSeconds` after AVPlayer becomes ready and report `serverPositionTicks(playerSeconds:)`; adding the resume position again would corrupt progress. Track changes renegotiate playback at the current absolute position. Embedded MP4/MOV timed text (`mov_text`/`tx3g`) is advertised as a native embedded stream. Explicit subtitle selections use server burn-in; audio changes use HLS unless the app maps native media selections itself.

Jellyfin 12 applies explicit audio/subtitle indices only when the playback request binds a `MediaSourceId`. Pass a known detail source ID to `prepare` for one request; otherwise, when explicit tracks are supplied, the client discovers the preferred source and negotiates again with its ID. This includes subtitles Off (`-1`), which otherwise can silently become the server default external subtitle and force HLS.

`homeContent(account:)` returns healthy rows and section warnings. An expired sign-in still throws. Detail requests refresh UserData through the same list projection used by Continue watching, matching Jellyfin 12's behavior.

Run the XCTest ports from the repository root:

```sh
swift test --package-path apple/PictureshowCore
PICTURESHOW_SPEC_DIR="$PWD/spec" swift test --package-path apple/PictureshowCore
```

The vector runner discovers `spec/` in the repository by default. A standalone Apple checkout without shared fixtures records an explicit skipped vector test; provide `PICTURESHOW_SPEC_DIR` to require fixture execution. The runner currently covers address candidates, authorization encoding, URL origin/resolution/token stripping, image URLs, playback decisions, tick conversion, resume entry, and default stream choice. It prints the executed count and operations outside this slice. Android decoder/profile fixtures cannot describe AVFoundation's capabilities and are not asserted as Apple profiles.

Application `end-position` vectors describe final-report completion and duration bounding, which belong to the platform playback lifecycle and have no matching helper in this package. `caption-language` describes system-caption enablement and server user-configuration precedence; `language(_:)` only normalizes a tag. `caption-index` describes accessibility caption ranking by hearing impairment, forced status and language, while `defaultStream` preserves the server choice. These three families are explicitly excluded with reasons, rather than claiming that a related helper implements their policy.

The XCTest ports also cover live HTTP request construction through a mock URLProtocol: account-bound device identity, password and Quick Connect flows, detail resume projection, partial home failures, negotiation/track selection, start/progress/stop reports, expired sessions, and session-scoped encoder cleanup. These request-level behaviors and the full-timeline HLS resume regression, and the source-bound Off/selected-track negotiation are candidates for additional shared vectors.
