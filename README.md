# Pictureshow

Pictureshow is a free, open-source Jellyfin client for Android TV from Legitimate LLC, licensed under MPL-2.0. Copyright © 2026 Legitimate LLC.

Status: early development. Server discovery, password sign-in, Quick Connect, remembered accounts, home rows, paged library browsing, item details, video-library search and Media3 playback are implemented. Playback still needs acceptance on a range of Android TV hardware.

Pictureshow is developed with AI assistance and tested on real Android TV hardware.

## Build

Install JDK 21 and the Android SDK with platform 37 and build-tools 36.0.0. Set `ANDROID_HOME` or point `sdk.dir` in an untracked `local.properties` file at the SDK, then run:

```sh
./gradlew check assembleDebug
```

For performance testing on TV hardware, use `./gradlew assembleDeviceTest`. This build is minified and non-debuggable, signed with the development key, and uses the same application id. Its APK is written to `app/build/outputs/apk/deviceTest/`.

The debug APK is written to `app/build/outputs/apk/debug/`. `check` runs the shared core tests, app ViewModel and Robolectric remote-focus tests, Android lint, and ktlint. The wrapper pins Gradle and its distribution checksum. CI uses Linux and JDK 21.

## Structure

- `core/`: platform-free Kotlin Multiplatform authentication, catalog protocol, presentation helpers, and account logic, with UDP sockets isolated in `jvmMain`.
- `app/`: Compose for TV screens, Navigation Compose, Koin wiring, and app-private DataStore persistence.
- `media3-ffmpeg/`: Media3's FFmpeg audio decoder extension with prebuilt LGPL FFmpeg libraries (`tools/ffmpeg/`, `third_party/ffmpeg/`).

Use a TV remote's arrows to move focus, center to select or open the on-screen keyboard, and Back to leave a screen. Enter a server address or choose a discovered server. Hidden Jellyfin users can always type their username. Quick Connect is offered only when enabled by the server.

Saved access tokens are stored in app-private DataStore; passwords are not saved, and Android backup is disabled. HTTP is supported for local servers; use HTTPS when available.

Home shows Continue watching, Next up, Libraries, and recent additions for movie and TV libraries, hiding empty sections. Library grids support name, date-added, and release-date sorting in either direction. Item details offer watched and favorite controls, Play, Resume and Play from start. Revoked sessions return to that server's sign-in screen without removing other remembered accounts.

Navigation and focus use the TV remote throughout. Home and library grids retain their selected card and scroll position when returning from details. The current home order is fixed; server-side home-section preferences are planned for a later version.


## Playback

Pictureshow builds a server device profile from platform decoders, display capabilities and the current audio route. Media3 1.11.1 plays the server decision, with optional FFmpeg audio renderers discovered at runtime when installed. The initial defaults explicitly allow up to 200 Mbps for LAN playback and enable passthrough for encodings supported by the current route. The request and both profile bitrate limits always carry a positive ceiling; the server still enforces its remote-client limits. Software AV1 is advertised only when the device reports a real-time performance envelope.

During playback, center toggles play/pause and shows controls. Left/right seek 10 seconds; a held direction increases the step to 30 seconds. Down shows controls; Up/Down moves between the playback, audio and subtitle actions. Back closes a track chooser, then hides controls, then returns to the item. Audio and subtitles include an Off choice. Tracks that need a different server stream are negotiated again at the current position.

Playback reports save progress every ten seconds and on pause, seek, track change and exit. A failed direct source gets one server-transcode attempt at the same position. Access tokens travel only in the Authorization header to the configured server's HTTP(S) origin (same scheme, host and port). This applies to manifests, segments, streams, subtitles and every redirect hop. External origins receive no server credentials; off-origin transcoding URLs are rejected. Legacy token query parameters are removed at the media-request boundary.


A Media3 MediaSession publishes the active player's title, position and playback state to Android. System play/pause, rewind and fast-forward commands control that player; the session is released when playback closes.

External playback entry accepts a 32-character hexadecimal item ID for the signed-in account:

```sh
adb shell am start -a app.pictureshow.action.PLAY --es itemId 0123456789abcdef0123456789abcdef
adb shell am start -a app.pictureshow.action.PLAY --es itemId 0123456789abcdef0123456789abcdef --el positionMs 0
adb shell am start -a android.intent.action.VIEW -d 'pictureshow://play/0123456789abcdef0123456789abcdef?positionMs=120000'
```

Omit the position to resume from the server's saved position; zero starts over. The item is checked against the current account before playback. Without an account, normal startup opens. An unavailable item shows a short message and returns home. Back from externally opened playback returns to the item's details.

## Search

Press Up from the first home row to reach Search, or use the remote’s search key anywhere in the catalog.
Search opens the system keyboard; its microphone provides voice typing. On later visits to the field, press center
to open the keyboard. Results update after a short pause once the trimmed query has at least two characters.
Movies, shows, episodes, collections and people appear in separate rows. A person opens their movies and shows.
Back from results focuses the field; Back from the field with the keyboard closed returns to the previous screen.
Returning from a detail preserves the query, rows and selected result.

Recent searches are stored per account when a result is opened or a query is submitted. The last eight appear when
the field is empty. Clear removes them, and signing out removes that account’s history.

The app accepts `android.intent.action.SEARCH` and `com.google.android.gms.actions.SEARCH_ACTION` with a `query`
string extra for the current signed-in account. Those entries show results with the keyboard closed. Music,
playlists and Android TV launcher search-provider integration are planned for later work.


### TV diagnostics and launcher integration

Settings → **This TV** shows the runtime decoder/display/audio reports, the effective bitrate ceiling and the exact
`DeviceProfileBuilder` profile JSON used for playback negotiation. Refresh after changing the audio connection.
The report and Android share sheet contain capability/version information only: no account, server, token, media
title or device identifier. Audio lists connected output types and the Media3 default-route capabilities; it does
not claim an active AudioTrack route while no player exists. Reported limits are not measured playback performance.

Only the `deviceTest` variant includes this read-only automation endpoint. With a non-rooted TV connected to adb
(API 23–36), collect and validate the exact UTF-8 profile JSON on your computer:

```sh
adb exec-out content read --uri content://app.pictureshow.deviceprofile/profile.json > device-profile.json
python3 -m json.tool device-profile.json >/dev/null
```

Add `-s SERIAL` after `adb` if multiple devices are attached. Validation catches the Android `content` command's
text errors, which can appear on stdout. The endpoint streams the same profile JSON as This TV using the current
playback preferences. It is absent from debug and release; no root, debuggable APK or storage permission is required.
Opening **View device profile JSON** replaces the capability summary with the profile; use D-pad Down/Up to scroll
its focusable blocks, and **Hide device profile JSON** to return to the summary.

On API 26+ TVs with a TV provider, Pictureshow publishes Continue watching and Next up to the launcher's Play Next /
Watch Next row. Refreshes follow app startup/return, backgrounding, account changes, catalog watch-state changes and
final playback reports. No recurring job or periodic wakeup is scheduled. A failed catalog fetch preserves the
current account's rows; switching accounts clears old rows before fetching the new ones. Launcher dismissals are
remembered per account/item until app data is cleared, including across sign-out and sign-in. Artwork URLs contain
no access token. Launcher presentation and availability vary; Google TV need not display the Android TV row.

## About and open-source licenses

Settings → **About Pictureshow** shows the installed version, source and privacy addresses. **Open-source licenses**
lists the release runtime libraries, including transitive dependencies, with full license texts and notices.
Use D-pad Down/Up to browse and scroll, center to open a license, and Back to return one level. Focus returns to the
library or settings entry you left.

The build uses the Apache-2.0 [AboutLibraries Gradle plugin](https://github.com/mikepenz/AboutLibraries) for release
metadata and checks it against resolved runtime artifacts. `app/licenses.gradle` retains embedded license and
NOTICE files, adds the native FFmpeg/Media3 extension and core-library desugaring notices, and fails for missing
license metadata or text. No phone UI or runtime AboutLibraries library is included. All variants display this
release catalog; test/debug-only libraries are excluded. After changing dependencies, build normally and review the
generated `app/build/generated/licenseAssets/licenses/catalog.json`.

FFmpeg 6.0.1 is LGPL-2.1-or-later. Its full LGPL text is readable in the app; source, configuration and replacement
instructions are in [third_party/ffmpeg/README.md](third_party/ffmpeg/README.md), and
[tools/ffmpeg/build.sh](tools/ffmpeg/build.sh) reproduces the shared libraries.
