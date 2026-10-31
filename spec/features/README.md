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

`phone` diverged before several fixes in `main`. Its touch screens are additive UI requirements, **not** permission to regress main's reporting, detail freshness, Retry deadlines, HLS recovery or release ordering. A phone behavior that differs is identified explicitly in the touch manifest. Missing Android behavior is recorded as a gap, rather than silently promoted into an existing feature.

The manifests describe existing scope: Jellyfin client, video catalog/playback, saved accounts, diagnostics and voluntary support. General music/photo players, casting, downloads, external players, profile editing and configurable home layouts are not implemented at this snapshot. Their settings inventory entries remain gaps.
