# Pictureshow

Pictureshow is a free, open-source Jellyfin client for Android TV from Legitimate LLC, licensed under MPL-2.0. Copyright © 2026 Legitimate LLC.

Status: early development, not usable yet. Server discovery, password sign-in, Quick Connect, remembered accounts, home rows, paged library browsing, and item details are implemented. Playback is planned for a later version.

Pictureshow is developed with AI assistance and tested on real devices.

## Build

Install JDK 21 and the Android SDK with platform 37 and build-tools 36.0.0. Set `ANDROID_HOME` or point `sdk.dir` in an untracked `local.properties` file at the SDK, then run:

```sh
./gradlew check assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. `check` runs the shared core tests, app ViewModel and Robolectric remote-focus tests, Android lint, and ktlint. The wrapper pins Gradle and its distribution checksum. CI uses Linux and JDK 21.

## Structure

- `core/`: platform-free Kotlin Multiplatform authentication, catalog protocol, presentation helpers, and account logic, with UDP sockets isolated in `jvmMain`.
- `app/`: Compose for TV screens, Navigation Compose, Koin wiring, and app-private DataStore persistence.
- `media3-ffmpeg/`: Media3's FFmpeg audio decoder extension with prebuilt LGPL FFmpeg libraries (`tools/ffmpeg/`, `third_party/ffmpeg/`).

Use a TV remote's arrows to move focus, center to select or open the on-screen keyboard, and Back to leave a screen. Enter a server address or choose a discovered server. Hidden Jellyfin users can always type their username. Quick Connect is offered only when enabled by the server.

Saved access tokens are stored in app-private DataStore; passwords are not saved, and Android backup is disabled. HTTP is supported for local servers; use HTTPS when available.

Home shows Continue watching, Next up, Libraries, and recent additions for movie and TV libraries, hiding empty sections. Library grids support name, date-added, and release-date sorting in either direction. Item details offer watched and favorite controls; Play currently displays a message about the upcoming playback feature. Revoked sessions return to that server's sign-in screen without removing other remembered accounts.

Navigation and focus use the TV remote throughout. Home and library grids retain their selected card and scroll position when returning from details. The current home order is fixed; server-side home-section preferences are planned for a later version.
