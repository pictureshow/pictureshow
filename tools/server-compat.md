# Live server compatibility gate

This opt-in JVM tool calls the production `JellyfinClient`, `PlaybackExtrasClient`,
`UserConfigurationClient` and `PlaybackClient` using Ktor's OkHttp engine. Normal
`check` runs remain offline. Run this matrix before a release, against the latest
patch image in each supported Jellyfin minor line, plus the first unsupported line.
Record exact Docker tags, digests and reported versions with the reports.

Use **disposable servers and a dedicated administrator test user**. This check
changes played/favorite state, reports playback, authorizes Quick Connect, and
writes then restores subtitle preferences. Do not run it against a real library
account. The media can be mounted read-only. Server setup/bootstrap is separate
from the check; it may use the startup-wizard API, but API assertions here use
real client code.

Requirements for a full check:

- JDK 17+ to run an exported runtime; JDK 21 and the project's Android SDK to build.
- Two visible libraries (movies and TV), populated with local metadata, two episodes
  in the first series/season, and an H264/AAC stereo direct-play file.
- Quick Connect enabled; the test user can authorize its own code.
- Chapter Segments Provider built for that server's ABI, named intro/outro chapters,
  and extracted trickplay images. Missing optional data reports `DEGRADED`.

Set `PS_COMPAT_URL` to the server's base URL, including any reverse-proxy prefix.
Set `PS_COMPAT_VERSION` to its exact runtime version (this is checked, not guessed
from the image tag). Set `PS_COMPAT_CONFIG` to a private JSON file with `username`
and `password` string fields. Never commit this file. Set `PS_COMPAT_REPORT` to the
output JSON path, outside the repository or under an ignored `build/` directory.
The unsupported-version check needs no credentials.

Run directly in the checkout:

```sh
./gradlew :core:serverCompat
```

Or build on a separate machine and copy the exported `runtime/` directory:

```sh
./gradlew :core:serverCompatRuntime
PS_COMPAT_RUNTIME=core/build/reports/server-compat/runtime tools/server-compat.sh
```

The exported runtime includes the core JVM classes and dependencies, allowing the
same code to run next to a loopback-only server without forwarding credentials.
The launcher reads all server details from environment variables. Reports contain
operation names, HTTP statuses and semantic differences; they omit addresses,
credentials, user identities, media identifiers and titles. Review reports before
sharing. `FAIL` exits nonzero; `DEGRADED` exits zero and requires release review of
the reported optional capability. A supported-server probe or sign-in failure
makes the run fail, even when later calls cannot execute.

A successful JVM run establishes API and negotiation behavior. It does not establish
Compose rendering, focus, Media3 decoder behavior or visible subtitle playback.
Verify those separately on the target device with the minified device-test build.

The fixture server should allow resume for short videos (set `MinResumeDurationSeconds`
to zero and use a resume percentage window that includes one third of the video).
The checker reads the reported position back through the production resume client.
For 10.9.x, the only expected operation is the public-info probe: it must return
`UNSUPPORTED_VERSION` with the minimum-version message before authentication occurs.
