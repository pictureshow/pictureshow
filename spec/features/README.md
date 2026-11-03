# Android feature manifest

Reference snapshot: Android `main` **fce5d6dfa63c6b41c0ea1c75919d4a981d836c5e** (rc21), and additive touch UI from `phone` **c46bc87c8e3c8a5ff5e432a565a4144ef1bb1e70**, inspected 2026-10-31. Paths below are repository-relative. Kotlin filenames without a directory are under `app/src/main/kotlin/app/pictureshow/`; core filenames are under `core/src/commonMain/kotlin/app/pictureshow/core/`.

This is a manifest of observable behavior, with stable feature IDs for platform checklists. Each table's acceptance column is a testable requirement; apply the shared input/accessibility/recovery contract to every row. A port records implemented, verified (with platform/device/input), gap, or platform-dependent against each ID. Passing logic vectors alone does not establish UI parity.

| Screen family | Manifest |
|---|---|
| Application shell, universal navigation/accessibility, evidence limits | [Shared contract](shared.md) |
| Server discovery, sign-in, Quick Connect, saved accounts | [Accounts](accounts.md) |
| Home, libraries, details, search, people | [Catalog](catalog.md) |
| Playback, tracks, captions, chapters, segments, continuation | [Player](player.md) |
| Settings and server-side preference honor matrix | [Settings](settings.md) |
| Device report, About, licenses, Supporter | [Information and support](information.md) |
| Phone/tablet adaptation and additions | [Touch](touch.md) |
| Historical acceptance corpus and unresolved findings | [Evidence and deviations](evidence.md) |
| Shared launch playback ownership and queue | [Session control and queue (draft)](session-control.md) |
| Downloads and offline | [Offline (launch draft)](offline.md) |
| Chromecast, native Cast Connect, AirPlay and browser output | [Casting (launch draft)](casting.md) |
| Phone/tablet/web as remote for a TV | [Remote (launch draft)](remote.md) |
| Music library and background audio | [Music (launch draft)](music.md) |
| Guide, live tune, recordings and schedules | [Live TV and DVR (launch draft)](live-tv.md) |
| Watch together | [SyncPlay (launch draft)](syncplay.md) |
| Exact server operations and version limits for launch drafts | [Launch API reference](launch-api.md) |

`phone` diverged before several fixes in `main`. Its touch screens are additive UI requirements, **not** permission to regress main's reporting, detail freshness, Retry deadlines, HLS recovery or release ordering. A phone behavior that differs is identified explicitly in the touch manifest. Missing Android behavior is recorded as a gap, rather than silently promoted into an existing feature.

The manifests describe existing scope: Jellyfin client, video catalog/playback, saved accounts, diagnostics and voluntary support. Music, casting, downloads, remote control, Live TV/DVR and SyncPlay remain unimplemented at this snapshot but are required launch scope in the draft manifests above (decision 2026-11-03). Their settings inventory entries remain historical gaps until implementation and evidence land. Photos, external players, profile editing and configurable home layouts are separate inventory work; this draft does not decide their launch status. Drafts and planned vectors are not implementation or qualification claims.

Launch draft revision (2026-11-03): minimum server 10.10; isolated tests on 10.10.7, 10.11.11 and 12.4, unchanged household 12.0.0 acceptance. Web downloads save originals through the browser (OFF-13); the web in-app offline library is explicitly Later (OFF-14). Cast/AirPlay SyncPlay is Later; launch requires Leave group before casting. Web gapless remains required. Numbered subcriteria have individual status; their parent row passes only when all pass.
