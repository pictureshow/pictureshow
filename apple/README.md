# Pictureshow for Apple

Native SwiftUI clients for iPhone, iPad and Apple TV. Both targets use the same UI sources,
the Foundation-only `PictureshowCore` package and AVFoundation/AVKit playback. Requires
Xcode 16 or newer; deployment targets are iOS/iPadOS 17 and tvOS 17. Bundle ID: `app.pictureshow`.

Open `Pictureshow.xcodeproj` and select `Pictureshow-iOS` or `Pictureshow-tvOS`. The checked-in
project is generated from `project.yml` with XcodeGen; regenerate after changing targets or files:

```sh
xcodegen generate --spec apple/project.yml
xcodebuild -project apple/Pictureshow.xcodeproj -scheme Pictureshow-iOS \
  -destination 'generic/platform=iOS Simulator' build
xcodebuild -project apple/Pictureshow.xcodeproj -scheme Pictureshow-tvOS \
  -destination 'generic/platform=tvOS Simulator' build
swift test --package-path apple/PictureshowCore
swift test --package-path apple/PictureshowTransport
```

Pass `--scratch-path` or `-derivedDataPath` to place build products on an appropriate development
volume. Builds are unsigned by default. No provisioning profile, Apple team or store configuration
is included. Unsigned simulators store the test account in their app sandbox because they have no
Keychain access-group entitlement. Physical-device builds use Keychain exclusively; that path
requires verification when device signing is configured in a later task.

## Slice behavior

Enter a Jellyfin address, then use a password or approve the displayed Quick Connect code in an
already signed-in Jellyfin client. Home loads Continue watching, Next up, libraries and the latest
movies/series from each relevant library. Failed shelves show a warning while healthy shelves remain
available. Detail views refresh the server's list-based UserData projection so their resume position
matches Continue watching, including Jellyfin 12. Series expose seasons and episode details.

The app negotiates a conservative Apple device profile: H.264 SDR in MP4/M4V/MOV, and HEVC SDR when
VideoToolbox reports hardware decoding. Unsupported containers, video/audio codecs, or selected
subtitle formats use Jellyfin's HLS H.264/AAC route. A direct decoder failure permits one HLS retry.
The request binds the media-source ID so Jellyfin applies explicit track choices, including Off.
The app's audio and subtitle chooser renegotiates at the current position; subtitles use
server burn-in. iPhone and iPad use native AVKit seeking and pause/play. Apple TV uses a passive
AVPlayerLayer with focusable SwiftUI Back, track, pause/play and ten-second seek controls, plus
the remote's play/pause command. TV video fits between the persistent header and transport so
those controls cannot cover burned-in captions. Native embedded-track changes are observed and reported only
when stream metadata identifies a unique server index; uncertain indices are omitted rather
than guessed from track order. Use the explicit chooser to select such tracks. The app reports start,
progress, stop and exact-session encoder cleanup, and returns to the detail action with focus.

Playback URLs never contain the server token. A per-playback HTTP relay binds only to loopback,
uses a random capability path, and adds credentials only after validating the upstream origin.
It streams byte ranges and rewrites HLS variants, segments, keys, maps and subtitle URIs through
the same origin gate. Cross-origin redirects are refused. Manifest buffers are capped at 2 MB.
Completed responses drain before an explicit TCP write-close; peer closure or a finite cleanup
timeout disposes the transfer.
Finite Jellyfin HLS uses the full source timeline; resume seeks and reports absolute player time.

## Design and parity

`App/DesignTokens.swift` is generated from `spec/design/tokens.json`:

```sh
python3 apple/generate-design-tokens.py
```

The palette, type sizes, poster ratios and card widths follow the Android reference. System sans-serif
rendering, Dynamic Type, navigation, media controls, VoiceOver and tvOS focus stay native.

`PictureshowCore` ports the needed Kotlin address, authorization, catalog, playback-decision and
lifecycle wire tests. Set `PICTURESHOW_SPEC_DIR` to the directory containing `vectors/` to run shared
vectors before the spec branch has been integrated. Unsupported vector families are reported as
outside this slice; they are not silently counted as passed. This package does not port Android
Media3/FFmpeg decoder logic. Settings, segment skipping, next-episode prompts, chapter/trickplay
previews, live media and downloads are subsequent features.

## Simulator flow tests

The `*-UITests` targets exercise the real server/sign-in/detail/direct/HLS/resume flow. They require
an explicitly supplied private pilot account via test-runner `PICTURESHOW_PILOT_JSON`, a JSON object
with string values `server`, `username`, `password`, `libraryID`, `directID` and `hlsID`.
For nested libraries, include `samplesFolderID` and `directContainers`/`hlsContainers`
(pipe-separated intermediate folder IDs). Resolve these from the signed-in catalog rather than
assuming a sample folder is itself playable. The series test also takes `seriesID`, `seasonID` and
`episodeID` (and optionally `showsLibraryID` to browse from the library). For actual track selection,
provide `directAudioIndex` and `directSubtitleIndex`. Set `directResumeEligible` or
`hlsResumeEligible` to `"false"` for short samples excluded by the server's resume policy.
An optional `resumeID`/`resumeContainers` title with `resumeResumeSeconds` exercises a longer
eligible video; the test waits for that position, stops, then verifies playback past the saved
position after reopening. Pass the configuration through
a local `.xctestrun` environment; keep it outside the repository. DEBUG-only app environment values
prefill the sign-in fields without bypassing server discovery, authentication or the UI actions.
`testServerHLSAndResume` exercises the unsupported-codec and long-title resume paths independently
of the strict direct-play gate. `testDirectPlaybackStartup` is a diagnostic that opens a configured
detail using an existing valid account, then requires advancing direct video. It does not replace
the full server/sign-in/catalog flow. The TV series test also checks remote play/pause, ten-second
seek, Menu cancellation of track selection, and Menu return with Play focus restored.

The Quick Connect test checks cancellation by default. With `approveQuickConnect: "true"`, a private
harness must approve the displayed code, and the test asserts arrival at home. Test sample videos
must be long enough to establish resume, and the direct sample must be in an Apple-supported
container as well as use supported codecs.

Simulator evidence cannot establish Apple TV hardware codec support, HDR/Dolby Vision, audio output
routing/passthrough, real Siri Remote behavior, AirPlay, background playback or Keychain entitlements.
These need a separately authorized hardware pass. This slice does not access any household device.

During iOS simulator acceptance (2026-10-31), a progressive H.264/AAC MP4 with embedded timed text
and metadata tracks intermittently failed with AVFoundation -11800 / underlying -12780. The same
failure reproduced over plain local HTTP outside the relay; an identical local-file control passed.
The cause remains unestablished. Successful direct video and the server-HLS recovery path are
separate observations; these runs do not establish reliable progressive playback on real devices.
