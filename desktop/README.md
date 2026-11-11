# Pictureshow for desktop

A native Compose Multiplatform viewer for Jellyfin on Windows, macOS and Linux,
using the shared Pictureshow Kotlin core and dynamically loaded LGPL libmpv.

With JDK 21, an architecture-matched LGPL libmpv and the matching native surface adapter:

```
./gradlew :desktop:test :desktop:spikeRuntime
MPV_LIBRARY=/path/to/libmpv SPIKE_LAYER_BRIDGE=/path/to/native-adapter ./gradlew :desktop:run
```

The default entry point is the viewer: connect to a server or discover one on the
LAN; sign in with a password or Quick Connect; browse home shelves and library
grids; sort; open a movie, series, season or episode; resume or start over; choose
audio and subtitles; continue to the next episode; and set basic preferences.
Saved tokens and preferences are committed atomically in the OS user data folder.
Unreadable storage offers Retry. Native decoder capabilities are measured before
negotiating playback; the server remains the authority for remembered progress
and track choices. Video conversion explains its reason when a device limit
requires it. A viewer-selected bitrate conversion does not show a device warning.
Text subtitles use a full-timeline, authenticated same-origin delivery URL, so
resuming after a cue starts does not depend on finding an earlier embedded packet.
Selected bitmap tracks negotiate their own embedding profile; mixing bitmap
embedding into the text profile can change Jellyfin's subtitle delivery choice.

Use Tab/Shift+Tab or arrows to navigate, Enter to activate, Escape to close a menu,
exit fullscreen and go back, F11/Alt+Enter for fullscreen, and Space/Left/Right
for playback pause and ten-second seeks. Menus, cards, forms, selected states and
settings use Compose semantics. The normal window is resizable; the Living-room
size setting enlarges the same controls for distant use.

macOS and Windows compose controls over the qualified native HDR surface. Linux
requires a Wayland compositor and uses the qualified native EGL video window;
all track, seek, continuation and stop controls also remain in the accessible
Compose application window. The native Linux video overlay has no independent
AT-SPI bridge: use the application control window with a screen reader. X11-only
video output and a single-window Linux accessibility integration remain owed.
This development slice is not a parity or release qualification claim.

`:desktop:vectorTest` runs **every** repository vector contract through JVM
runners, including the numeric HDR analyzer, and rejects unregistered or stale
suites. See [VECTORS.md](VECTORS.md). The hosted `Desktop app` workflow builds and
runs the vectors and a scripted viewer smoke against a disposable Jellyfin on
all three OSes, plus the Android full gate. See
[smoke setup](tools/app-smoke/README.md).

The diagnostic entry points remain available in the app build:

```
MainKt layer MEDIA EVIDENCE --sustain=125
MainKt wayland MEDIA EVIDENCE --sustain=125
```

They preserve the qualified surface, HDR and pacing gates. See
[NATIVE-SURFACES.md](NATIVE-SURFACES.md) and [numeric HDR](tools/hdr/README.md).
Normal playback has no per-frame video CPU readback. Smoke runs mute audio;
normal viewer playback enables its saved volume. Muted tests do not qualify
speaker mapping or audible surround output.

## Packaging is a later slice

Development dependencies are not automatically redistributable packages.
Installers need an audited architecture-specific LGPL dependency closure,
matching source/build recipes and notices, dynamic library replacement, runtime
codec receipts, Windows signing and installer, macOS signing/notarization and
Intel/Apple-silicon distributions, Linux packages/Flatpak integration, upgrade
and data-migration tests, native accessibility qualification and actual GPU/HDR
hardware beta evidence. Never ship a development GPL libmpv in an MPL-only build.
