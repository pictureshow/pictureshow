# Pictureshow for Apple

One SwiftUI app for iPhone, iPad and Apple TV (bundle `app.pictureshow`, iOS/iPadOS/tvOS 18 and
later). The project builds unsigned simulator apps; no signing, provisioning or store submission is
configured here.

## Layout

- `App/` — the app. `PictureshowApp.swift` (root, tabs, navigation), `Model/` (accounts, preferences,
  device identity), `Design/` (theme, artwork, cards; tokens generated from `spec/design/` by
  `generate-design-tokens.py`), `Screens/` (sign-in, Home, libraries, detail pages, search, settings),
  `Player/` (playback negotiation and reporting, player UI per platform).
- `PictureshowCore/` — the Swift translation of the Kotlin core: Jellyfin API client, playback
  negotiation, reporting and resume policy, segment and next-episode policy, captions, conversion
  notice copy, library queries. It runs the shared `spec/vectors/`.
- `PictureshowTransport/` — HTTP transport with origin-pinned redirects.
- `UnitTests/` — app-level tests: account storage and the HEVC hardware qualification.

## Build and test

```sh
xcodegen generate --spec apple/project.yml
xcodebuild -project apple/Pictureshow.xcodeproj -scheme Pictureshow-iOS \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apple/Pictureshow.xcodeproj -scheme Pictureshow-tvOS \
  -destination 'generic/platform=tvOS Simulator' CODE_SIGNING_ALLOWED=NO build
PICTURESHOW_SPEC_DIR=$PWD/spec swift test --package-path apple/PictureshowCore
swift test --package-path apple/PictureshowTransport
xcodebuild -project apple/Pictureshow.xcodeproj -scheme Pictureshow-iOS \
  -destination 'platform=iOS Simulator,name=<an iPhone>' -parallel-testing-enabled NO test
```

The core runner reads every vector file in `spec/vectors/`. Cases this port hasn't implemented yet
are listed by ID in `PendingSpecCaseManifest.swift`; they are validated for identity but not run, so a
new case on main fails here until it is either ported or listed. `track-labels` vectors live only on
the old `apple` branch and are skipped when absent.

## What the app does

- **Sign in.** Server address (with or without scheme, port or reverse-proxy path), the server's
  public user list, password, Quick Connect, and several accounts on several servers with a switcher.
  Each account has its own device ID, so two viewers on one device keep separate sessions.
- **Browse.** Home: a hero for what to watch next, Continue watching, Next up, library tiles and the
  latest items per library. Library grids page through the server with sort (name, date added,
  release date, rating, runtime, random) and filters (unwatched, watched, in progress, favorites,
  genre), remembered per library.
  Movie, series, season, episode, collection and person pages; seasons switch in place on a series.
  Search runs per kind (movies, shows, episodes, collections, people) as you type.
- **Play.** Direct play when AVFoundation can play the file as it is (H.264 or qualified HEVC, SDR,
  in MP4/MOV with AAC, AC-3, E-AC-3, ALAC or MP3); otherwise the server makes an HLS stream that copies
  whatever it can, including surround AAC/AC-3/E-AC-3 up to 5.1. When the server converts video, a
  notice says why and what file would play directly (it can be turned off in Settings). Text
  subtitles (SRT, ASS, WebVTT, mov_text) are fetched as WebVTT and drawn by the app; image subtitles
  (PGS, VobSub) are burned in by the server. Intro, credits and recap segments follow the per-type
  setting (ask, skip automatically, or ignore). The next episode counts down at the end and plays,
  unless turned off.
- **Report.** Start, progress every 10 seconds and on pause/seek, and stop with the final position, so
  resume, Continue watching and Next up stay right on every client. The server's transcode is
  stopped when the player closes or moves to the next episode.
- **Platforms.** Apple TV uses the system player (`AVPlayerViewController`) with the server's audio
  and subtitle tracks in the transport bar's menus and skip/next-episode as contextual actions;
  shelves, grids and buttons use the system focus engine and card styles. iPhone and iPad use custom
  controls over the video with a track menu, AirPlay route picker, ±10 s and a scrubber; iPad gets a
  sidebar-adaptable tab bar. The theme follows the system on iPhone/iPad (Settings can force light or
  dark); Apple TV is dark.

## Account storage

Accounts (server, user, access token, device ID) are stored in the Keychain. If a Keychain write fails,
the app falls back to a local copy only where the Keychain can't work at all: a build without the
Keychain entitlement (`errSecMissingEntitlement`) or the simulator. Any other failure is surfaced as
"Sign-in not saved" with a retry, and nothing is written in the clear. Leftover fallback copies are
deleted on sign-out, on account removal and after every successful Keychain save.
`UnitTests/AccountBookTests.swift` covers this through an injected Keychain.

## Media URLs and the token

AVPlayer fetches media itself, so the stream URL carries the viewer's token as an `ApiKey` query
parameter, built for the configured server's origin only (`ServerPlaybackURL`). That token can appear
in server or reverse-proxy access logs. API calls and artwork use the Authorization header.

## Not in this pass

Downloads, SyncPlay, Live TV/DVR, music, photo libraries and casting beyond AirPlay are later passes.
HDR and Dolby Vision are not advertised yet (the profile asks for SDR, so the server tone-maps).
Real-device checks are still needed for Keychain entitlements, Siri Remote gestures, AirPlay to a
receiver, audio routes and hardware HEVC.
