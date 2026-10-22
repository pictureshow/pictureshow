# Pictureshow

Pictureshow is a free, open-source Jellyfin client for Android TV from Legitimate LLC, licensed under MPL-2.0. Copyright © 2026 Legitimate LLC.

Status: early development. Server discovery, password sign-in, Quick Connect, remembered accounts, home rows, paged library browsing, item details and Media3 playback are implemented. Playback still needs acceptance on a range of Android TV hardware.

Pictureshow is developed with AI assistance and tested on real devices.

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

Use a TV remote's arrows to move focus, center to select or open the on-screen keyboard, and Back to leave a screen. Enter a server address or choose a discovered server. Hidden Jellyfin users can always type their username. Quick Connect is offered only when enabled by the server.

Saved access tokens are stored in app-private DataStore; passwords are not saved, and Android backup is disabled. HTTP is supported for local servers; use HTTPS when available.

Home shows Continue watching, Next up, Libraries, and recent additions for movie and TV libraries, hiding empty sections. Library grids support name, date-added, and release-date sorting in either direction. Item details offer watched and favorite controls, Play, Resume and Play from start. Revoked sessions return to that server's sign-in screen without removing other remembered accounts.

Navigation and focus use the TV remote throughout. Home and library grids retain their selected card and scroll position when returning from details. The current home order is fixed; server-side home-section preferences are planned for a later version.


## Playback

Pictureshow builds a server device profile from platform decoders, display capabilities and the current audio route. Media3 1.11.1 plays the server decision, with optional FFmpeg audio renderers discovered at runtime when installed. The initial defaults leave streaming bitrate uncapped and enable passthrough for encodings supported by the current route. Software AV1 is advertised only when the device reports a real-time performance envelope.

During playback, center toggles play/pause and shows controls. Left/right seek 10 seconds; a held direction increases the step to 30 seconds. Down shows controls; Up/Down moves between the playback, audio and subtitle actions. Back closes a track chooser, then hides controls, then returns to the item. Audio and subtitles include an Off choice. Tracks that need a different server stream are negotiated again at the current position.

Playback reports save progress every ten seconds and on pause, seek, track change and exit. A failed direct source gets one server-transcode attempt at the same position. Access tokens travel in the Authorization header for manifests, segments, streams and external subtitles; legacy key query parameters are removed at the media-request boundary.
