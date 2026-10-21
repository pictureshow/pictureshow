# Pictureshow

Pictureshow is a free, open-source Jellyfin client for Android TV from Legitimate LLC, licensed under MPL-2.0.

Status: early development, not usable yet. The first slice covers server discovery, password sign-in, Quick Connect, and remembered accounts. The home screen is a placeholder; browsing and playback are not implemented.

Pictureshow is developed with AI assistance and tested on real devices.

## Build

Install JDK 21 and the Android SDK with platform 37 and build-tools 36.0.0. Set `ANDROID_HOME` or point `sdk.dir` in an untracked `local.properties` file at the SDK, then run:

```sh
./gradlew check assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. `check` runs the shared core tests, app ViewModel tests, Android lint, and ktlint. The wrapper pins Gradle and its distribution checksum. CI uses Linux and JDK 21.

## Structure

- `core/`: platform-free Kotlin Multiplatform protocol and account logic, with UDP sockets isolated in `jvmMain`.
- `app/`: Compose for TV screens, Navigation Compose, Koin wiring, and app-private DataStore persistence.

Use a TV remote's arrows to move focus, center to select or open the on-screen keyboard, and Back to leave a screen. Enter a server address or choose a discovered server. Hidden Jellyfin users can always type their username. Quick Connect is offered only when enabled by the server.

Saved access tokens are stored in app-private DataStore; passwords are not saved, and Android backup is disabled. HTTP is supported for local servers; use HTTPS when available.
