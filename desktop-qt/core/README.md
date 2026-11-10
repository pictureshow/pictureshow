# C++ parity sample

This is a working QtCore C++ translation of four Kotlin core areas, with no JVM or language bridge. The
`pictureshow_core_sample` library contains the behavior; `pictureshow_vectors` is a separate adapter that reads the
existing JSON inputs and expectations. Effects returned by the reducers are values for the desktop platform adapter
to execute. These functions do not open a player, send network requests, or read the expected outputs.

```sh
cmake -S desktop-qt/core -B build/core -DCMAKE_PREFIX_PATH=/path/to/qt
cmake --build build/core --parallel 2
ctest --test-dir build/core --output-on-failure
```

The sample uses Qt Core under LGPL-3.0-only (the LGPL alternative in Qt's multi-license distribution). QtCore is
dynamically linked; this sample's own static library is Pictureshow MPL-2.0 code. All source here is independently
translated from this repository's MPL Kotlin core, copyrighted Legitimate LLC. No external frontend source is used.

Implemented suites:

| Suite | Cases | Checks | Reference production source |
| --- | ---: | ---: | --- |
| `playback-mode` | 13 | 24 trace steps | `core/.../control/PlaybackMode.kt` |
| `music-session` | 16 | 38 trace steps | `core/.../music/MusicSession.kt` |
| `music-reports` | 9 | 29 trace steps | `core/.../music/MusicReports.kt` |
| `music-quality` | 20 | 20 stateless outputs | `core/.../music/MusicQuality.kt` |

`core/...` above means `core/src/commonMain/kotlin/app/pictureshow/core/`. The vectors run unchanged from
`spec/vectors/`; they are not copied or regenerated. Stateful expected objects are explicit projections, following
the Kotlin `ControlVectorsTest`/`MusicVectorsTest` adapters; arrays, scalar values, and null are exact. The stateless
outputs compare as whole JSON values. The profile adapter projects a real generated wire profile the same way the
Kotlin test adapter does. Tick counts and serials remain decimal int64 strings and are never parsed as doubles.

The runner takes explicit files, rejects unsupported schemas/suites/operations, duplicate IDs/suites, empty suites,
missing expectations, and mismatches with a nonzero exit. This is a **partial port**: passing these four suites
does not imply that every recursively discovered spec file is supported. Supplying any other suite fails. A full
desktop port must add dispatch for all suites and switch the top-level gate to recursive discovery, as the contract
requires. This sample reports effect decisions; it does not prove the OS actually delivers background audio, media
keys, routes, reports, Cast, or SyncPlay.

The music profile translates the Android reference's pure factory for equivalent supplied codec inputs. An actual
mpv desktop profile needs measured native capability inputs and an audited container/codec matrix before it is
sent to Jellyfin. The two profile fixtures assert their documented projection, not every wire-profile field.

On macOS the Qt 6.12 binary SDK's imported `-idirafter` compiler option did not quote a prefix containing a space.
Configuring through a symlink whose path has no spaces worked; this is an SDK path workaround, independent of the
C++ port.
