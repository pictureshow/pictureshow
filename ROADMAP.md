# Roadmap

Pictureshow aims to be a complete Jellyfin client on every screen people watch on, matching what the official
Jellyfin and Plex clients do and adding to it. Each platform is built natively: Kotlin and Compose for Android, SwiftUI
for Apple devices, Compose Desktop with libmpv for Windows, macOS and Linux, and TypeScript and React for the web. They
share one behaviour contract in [`spec/`](spec/): feature rules and JSON test vectors that every platform runs, so
parity is checked by tests rather than by hand.

Android TV and Android phones and tablets go to real users first. Apple, desktop and web follow as each one is ready.
Nothing ships to a public store until it is fully tested on real hardware.

## Now: Android TV, phones and tablets

The Android app is the reference implementation. It has sign-in (password, Quick Connect, saved accounts, profile
PINs), Home, libraries, search, playback with Media3 and an optional MPV engine, downloads and offline playback, music
(including offline albums and playlists), Live TV and recordings, Watch together (SyncPlay), casting, phone-as-remote,
subtitles search and download, and Seerr requests. Before it goes out:

- [ ] Finish the music polish: grouped music in Downloads, the "Play again" state after a title ends, offline artwork.
- [ ] Android Auto for music, and synced lyrics.
- [ ] Run the playback format sweep (video codecs, HDR, audio passthrough, subtitles) on real TVs and on emulators.
- [ ] Translation pass: move the remaining hard-coded strings into resources and add the first languages beyond English.
- [ ] Fire OS: test on Fire TV hardware and prepare the Amazon Appstore listing.
- [ ] Complete the Google Play store requirements and internal testing, then open testing.

## Next: iPhone, iPad and Apple TV

The SwiftUI app covers the same features from the Android reference and runs the shared test vectors. Remaining:

- [ ] Port music downloads and offline music.
- [ ] Finish focus and layout details found in review (Apple TV focus after disconnecting Seerr, iPhone checks of the
      finished-title and subtitle-timing screens, iPad side-by-side multitasking).
- [ ] Real-user and design review rounds once feature-complete, then TestFlight.

## Then: desktop (Windows, macOS, Linux)

Compose Desktop on the shared Kotlin core, with libmpv for native decoding and HDR output on all three systems.

- [ ] Downloads and offline playback, including music.
- [ ] A second round of real-user fixes (menus, keyboard paths, account management).
- [ ] HDR acceptance on real GPUs and displays for each system.
- [ ] Signed installers for each system.

## Then: web

The TypeScript and React client in [`web/`](web/) runs the same test vectors; suites it hasn't ported yet are listed as
owed in `web/core/src/vectors.test.ts`.

- [ ] Port the owed suites: music, casting queue, Seerr, SyncPlay group creation, playback settings and quality,
      remote control, discovery rows.
- [ ] Music search groups and the live TV schedule rules.
- [ ] Real-user and design review rounds once feature-complete.

## Across every platform

- When the server has to convert a file because the device can't play it, tell the viewer why and how to avoid it.
- Keep the spec and test vectors as the single source of truth: a behaviour change lands with a vector, and every
  platform that hasn't ported it shows it as owed.
- Accessibility: screen readers, remote and keyboard-only navigation, text scaling.

## Later

- Servers other than Jellyfin.
- A supporter subscription that funds development. Supporters' feature requests go first.

Suggestions and bug reports are welcome as GitHub issues.
