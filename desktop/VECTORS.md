# Desktop JVM parity gate

`:desktop:vectorTest` executes core's compiled JVM vector test classes and desktop's HDR vector bridge. Compiled core tests keep Kotlin friend-module access; rules and their tests are shared with Android. `:desktop:test` depends on this gate and separately executes desktop app tests.

The corpus inventory guard rejects a JSON file added without a registered executor, missing or stale packaged resources, empty case suites, and duplicate IDs. The ordinary runners reject unknown operations. No contract suite is exempt. The HDR contract has a different schema from the case suites, so its actual Python analyzer (including wrong-scale, wrong-gamut, clipping, malformed capture and negative-channel controls) runs as a bounded subprocess owned by a JVM test. Python 3 is required on each CI runner. This checks mathematical contracts; real compositor HDR output still needs the separate per-OS capture gates.

Snapshot 2026-11-10: 40 JSON contracts, 2149 cases in the case-based contracts, plus the numeric HDR contract. A green logic gate establishes shared policy behavior, not completed desktop UI parity.

| Contract | Cases | Executor |
| --- | ---: | --- |
| `application.json` | 432 | `ApplicationParityVectorsTest` |
| `cast-capability.json` | 41 | `ControlVectorsTest` |
| `cast-handoff.json` | 95 | `ControlVectorsTest` |
| `cast-session.json` | 53 | `ControlVectorsTest` |
| `catalog.json` | 28 | `ReferenceParityVectorsTest` |
| `compatibility.json` | 53 | `ReferenceParityVectorsTest` |
| `desktop-hdr-output.json` | PQ anchors, scRGB and analyzer negative controls | `DesktopHdrVectorsTest → Python test_analyze (10 checks)` |
| `grid-navigation.json` | 40 | `GridNavigationVectorsTest` |
| `music-catalog.json` | 30 | `MusicVectorsTest` |
| `music-playlists.json` | 14 | `MusicVectorsTest` |
| `music-quality.json` | 20 | `MusicVectorsTest` |
| `music-queue.json` | 26 | `MusicVectorsTest` |
| `music-reports.json` | 9 | `MusicVectorsTest` |
| `music-session.json` | 16 | `MusicVectorsTest` |
| `native-regressions.json` | 14 | `ReferenceParityVectorsTest` |
| `negotiation.json` | 3 | `ReferenceParityVectorsTest` |
| `network.json` | 36 | `ReferenceParityVectorsTest` |
| `offline-budget.json` | 40 | `OfflineVectorsTest` |
| `offline-fmp4.json` | 43 | `OfflineVectorsTest` |
| `offline-job.json` | 283 | `OfflineVectorsTest` |
| `offline-manifest.json` | 98 | `OfflineVectorsTest` |
| `offline-next.json` | 14 | `OfflineVectorsTest` |
| `offline-outbox.json` | 83 | `OfflineVectorsTest` |
| `offline-plan.json` | 45 | `OfflineVectorsTest` |
| `offline-reconnect.json` | 16 | `OfflineVectorsTest` |
| `offline-resume.json` | 78 | `OfflineVectorsTest` |
| `playback-information.json` | 96 | `PlaybackInformationVectorsTest` |
| `playback-mode.json` | 13 | `ControlVectorsTest` |
| `playback.json` | 72 | `ReferenceParityVectorsTest` |
| `profiles.json` | 18 | `ReferenceParityVectorsTest` |
| `queue-edit.json` | 40 | `ControlVectorsTest` |
| `queue-order.json` | 27 | `ControlVectorsTest` |
| `recovery.json` | 25 | `RecoveryParityVectorsTest` |
| `remote-queue.json` | 16 | `RemoteVectorsTest` |
| `remote-receive.json` | 51 | `RemoteVectorsTest` |
| `remote-reconnect.json` | 35 | `RemoteVectorsTest` |
| `remote-targets.json` | 17 | `RemoteVectorsTest` |
| `remote-wire.json` | 77 | `RemoteVectorsTest` |
| `session-command.json` | 35 | `ControlVectorsTest` |
| `session-owner.json` | 17 | `ControlVectorsTest` |
