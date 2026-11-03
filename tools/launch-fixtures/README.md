# Launch transport fixtures

These tools generate original CC0 media and run real HTTP, WebSocket, HLS and DVR checks against disposable Jellyfin servers. Captures are fixture facts for the later control/queue vectors, not product or device acceptance. Code is MPL-2.0; generated signals, guide and media are dedicated to CC0-1.0 by Legitimate LLC. No external media or household library is used.

Infrastructure addresses, SSH ownership and the actual root directory belong in the private leg handoff. The commands below use environment variables so that this public directory contains no private infrastructure. Keep credentials in environment memory or the vault, never in arguments, a checked-in `.env`, output files or diagnostics.

## Prerequisites and ownership

The fixture host needs Docker/Compose and Python 3. The controller needs Python 3.10+, FFmpeg/ffprobe and `websockets` 13.1 (the tested version). Install dependencies in your usual isolated environment. `secret` and `accounts-ledger` are the operator's local vault tools. `api.py` uses Python's HTTP client and header authentication; WebSocket authentication uses a query token retained only in memory. Jellyfin's own logs and reverse-proxy logs may retain URL tokens; do not publish container logs.

Use a dedicated root and this Compose project only. It creates `ps84a-<version>`, `ps84a-origin`, `ps84a-channels` and the `pictureshow84a` Docker network. There is no restart policy, host scheduler, cron or launch agent. Do not point setup or checks at an acceptance server. Fixture servers intentionally run one version at a time on the small host. `up`, `init` and `live-up` refuse the reserved 06:00–10:00Z load window; callers must finish and stop active work before that window too.

## Generate and provision

On the controller:

```sh
python3 generate.py "$GENERATED_MEDIA"
```

Copy this tools directory and the generated media to the fixture host. Set `FIXTURE_ROOT` to a dedicated absolute directory on that host, and `FIXTURE_TOOLS` to this tools directory. Pull the three version tags, inspect their digests, then initialize:

```sh
docker pull jellyfin/jellyfin:10.10.7
docker pull jellyfin/jellyfin:10.11.11
docker pull jellyfin/jellyfin:12.4
docker pull busybox:1.37
python3 "$FIXTURE_TOOLS/fixture.py" init --root "$FIXTURE_ROOT"
```

`init` records `images.json` and writes a private `compose.json` pinned to each inspected Jellyfin digest. It refuses to overwrite an existing configuration. Copy the generated tree into each version's separate writable `media/` directory. Each version has its own `config/`, database, cache and `recordings/`; no shared database or household volume is mounted. The recorded tested pins are in [captures/images.json](captures/images.json). Original-asset and live/guide license manifests are in [captures/media-license.json](captures/media-license.json) and [captures/live-license.json](captures/live-license.json). Rebuild from those digest references if tags move.

Generate the current guide and start the finite origin when a test needs live media:

```sh
python3 "$FIXTURE_TOOLS/fixture.py" guide --root "$FIXTURE_ROOT"
python3 "$FIXTURE_TOOLS/fixture.py" live-up --root "$FIXTURE_ROOT"
python3 "$FIXTURE_TOOLS/fixture.py" up --root "$FIXTURE_ROOT" --version 10.10.7
```

The guide contains two mapped channels, a current airing, an imminent simultaneous two-minute pair, repeated series episodes, deliberate gaps/overlaps and 48 hours of relative UTC data. It expires; regenerate and run `python3 refresh.py guide` with the chosen JF_URL/JF_VERSION before new guide tests. `refresh.py library` performs a bounded catalog refresh when needed. DST policy belongs in deterministic vectors, independently of today's timezone. Two blue/red H.264/AAC channels have tones at 440/880 Hz, burned elapsed time/frame counters, two-second HLS segments and six-segment sliding windows. Live producers are real-time and CPU capped. `live-down` stops both producers and origin.

On the controller, forward your selected server port. All host bindings are loopback only:

| Version | Host/controller port | Container port | Maximum CPU / memory |
|---|---:|---:|---|
| 10.10.7 | 18107 | 8096 | 1 CPU / 1200 MiB |
| 10.11.11 | 18117 | 8096 | 1 CPU / 1200 MiB |
| 12.4 | 18124 | 8096 | 1 CPU / 1200 MiB |
| Live origin | 18140 | 8080 | 0.15 CPU / 64 MiB |
| Live generators | internal | — | 0.8 CPU / 384 MiB |

Use an explicitly owned SSH process, without modifying anyone else's forward:

```sh
ssh -N -o ExitOnForwardFailure=yes -L 18107:127.0.0.1:18107 "$FIXTURE_HOST"
```

Select a specific emulator with `ANDROID_SERIAL`, then `adb -s "$ANDROID_SERIAL" reverse tcp:18107 tcp:18107` (substitute the other version's port as needed). The emulator uses the controller's loopback URL. Remove only your own reverse mapping when finished. No emulator or simulator must be booted just to provision a server. Real Cast/AirPlay receivers cannot use this loopback route; independent receiver reachability remains a later device test.

Initialize each server once from the controller; the URL and version are arguments without credentials:

```sh
python3 setup.py --version 10.10.7 --url http://127.0.0.1:18107
```

`setup.py` stores random credentials directly in the vault before provisioning. It creates Scope Music, Scope Video, Scope Restricted libraries; Jellyfin creates its recording library when a timer first records; metadata fetchers are disabled for generated assets. Viewers A/B have playback, download, Live TV management and SyncPlay creation/join rights. B cannot see Scope Restricted. The restricted viewer can watch permitted content/TV but cannot download, manage DVR, control other users or join/create SyncPlay groups. The contract check briefly tests B with JoinGroups and restores its original policy even on failure. Both HTTP and WebSocket source/profile differences are captured; HTTP requests explicitly negotiate the PascalCase response profile. It creates an M3U tuner with `SimultaneousStreamLimit=1`, XMLTV provider and recording paths, then discovers/runs the guide refresh task. `--resume` is for a partially initialized fixture only; existing credentials and libraries are retained.

Vault entries use these exact templates for each of `10.10.7`, `10.11.11`, `12.4`:

- `Pictureshow fixture Jellyfin <version> (scope-admin)`
- `Pictureshow fixture Jellyfin <version> (scope-viewer-a)`
- `Pictureshow fixture Jellyfin <version> (scope-viewer-b)`
- `Pictureshow fixture Jellyfin <version> (scope-restricted)`

Run `accounts-ledger` once after a provisioning batch to index the new accounts. No token is committed. Tests log in as dedicated device IDs and log out at completion; passwords remain in the vault for downstream tests.

## Run the executable receipts

```sh
export JF_URL=http://127.0.0.1:18107 JF_VERSION=10.10.7
python3 contracts.py --out "$PRIVATE_EVIDENCE/transport-10.10.7.json"
# On the host, regenerate guide immediately before the recording receipt:
ssh "$FIXTURE_HOST" python3 "$FIXTURE_TOOLS/fixture.py" guide --root "$FIXTURE_ROOT"
python3 refresh.py guide
python3 contracts.py --mode recordings --out "$PRIVATE_EVIDENCE/recordings-10.10.7.json"
python3 ws-auth.py --out "$PRIVATE_EVIDENCE/ws-auth-10.10.7.json"
python3 audio-contract.py --out "$PRIVATE_EVIDENCE/audio-10.10.7.json"
python3 check-local.py
```

Set `JF_CONTAINER=ps84a-10.10.7` and optionally `JF_DOCKER_HOST="$FIXTURE_HOST"` to add host process evidence to owned-encoding cleanup. Without these, captures explicitly state HTTP-only cleanup evidence. The tested runs use host process evidence: two different PlaySessionIds, real HLS segment decoding, deletion of the first encoding while the second PID survives, then deletion of the final owned PID. No arbitrary encoding or another device's resources are stopped.

Alternative to vault access: `JF_CREDENTIALS` can contain a JSON object keyed by the four fixture usernames, each with `username`/`password`. Inject it into process environment without printing it. URL can contain a reverse-proxy prefix. Credentials, returned media URLs, OpenToken, filesystem paths, endpoints and server names are removed from captures; IDs receive consistent capture-local aliases. `websocketEvents` preserves MessageType / Data / GroupId / Type / queue and command nesting and order. Do not use these aliases as server IDs. Output captures on failure too; a nonzero exit cannot count as a passing run.

Transport mode downloads and decodes original media, verifies Range bytes, records persistent-playlist repeat behavior, checks policy denials, opens two actual authenticated HLS encodings, checks isolated cleanup, fetches guide/mapping, exercises JSON-only and query-only Live Open, reports Stop/Close and repeated Close statuses, receives remote command envelopes, tests Sessions subscription policy, SyncPlay policy variants, duplicate queue entries, readiness, a scheduled group command, two-viewer events and UTC clock fields. HTTP dispatch does not prove a renderer obeyed a command.

Recording mode requires the freshly generated guide pair starting45s after generation. It schedules the two explicit ProgramIds, asserts returned start/end match the two-minute window with no padding, observes timer transitions and real output, creates/cancels a series rule, downloads/decodes completed recordings and reads/writes/restores resume state. Recording category subfolders are disabled so manual timer outputs remain catalog-visible. The generated XMLTV series has a recorder SeriesId verified in the host receipt; public Program DTOs do not expose that internal identifier. Timer defaults preserve `ServiceName`: omitting it yields HTTP 500 on the older server. Completed generated recordings are retained for downstream legs. Timers/rules created by the run are canceled on exit; do not interrupt the process without its cleanup. Run without another check using these same device IDs.

Reset only one fixture viewer's watched/position state:

```sh
python3 reset.py --user scope-viewer-a
```

This preserves play counts/favorites and touches only items visible to that authenticated fixture viewer. Other users' state is not reset.

## Stop and teardown

On the fixture host:

```sh
python3 "$FIXTURE_TOOLS/fixture.py" down --root "$FIXTURE_ROOT" --version 10.10.7
python3 "$FIXTURE_TOOLS/fixture.py" live-down --root "$FIXTURE_ROOT"
python3 "$FIXTURE_TOOLS/fixture.py" status --root "$FIXTURE_ROOT"
```

Stop your SSH process by its recorded PID and remove only your emulator reverse mapping. For container/network teardown, use `fixture.py teardown --root "$FIXTURE_ROOT" --confirm-root "$FIXTURE_ROOT"`. Teardown preserves config, generated media and recordings for inspection; deleting this dedicated root is a separate explicit operation. Never share these roots with another server. No periodic refresh/watch job survives a test session.

## Observed version boundaries

See the [capture index](captures/README.md) and the private leg result for exact outcomes. These are runtime facts from 2026-11-03, not guarantees for every patch. In particular, the design's assumptions about viewer Sessions subscriptions, persistent duplicate entry IDs, SeriesId exposure and repeated Close status must be checked against these receipts before freezing reducer vectors. The server cannot establish audible playback, exact drift, native OS background behavior, Cast receiver access or finite offline-package behavior; those remain downstream qualification work.

Recording receipts reserve one fixture version while they run. They first create/cancel a future time-specific series rule, then schedule the two one-offs. A broad same-series rule can associate, rename and reschedule existing matching timers; do not mix that mutation into a one-off proof. Timer ownership uses IDs discovered across each write, not mutable display names. Recording ownership uses the run's new catalog IDs after its series rule has been canceled. The first recording library can need a full scan before list APIs see it; the receipt performs bounded scans during and after recording. The read-only viewer receives access to this generated recording library when it appears, without gaining management rights.

The exact default response casing and available subscriptions differ from a generated SDK's type names. Query tokens in server-returned URLs and WebSocket handshakes remain private. Use the explicitly negotiated HTTP profile, handle both the versioned schema and actual wire envelopes, and distinguish a dispatched command from renderer confirmation. The fixture emits no playback output on a user's speakers.

The common tested WebSocket query parameter is **`ApiKey`**. On 12.4, `api_key` returned HTTP403 while `ApiKey`/`apiKey` and native Authorization returned101; do not infer a browser limitation from the legacy spelling. `ws-auth.py` reads handshake status directly so an HTTP403 with a chunked body is reported as authorization failure rather than a WebSocket parser error. The transport harness uses the common spelling on every version.

An active recording can appear in the catalog before a compatible source is ready. One 12.4 receipt returned `NoCompatibleStream` with an empty source at18.1s, then negotiated a source at21.3s. Recording mode requests actual playback/transcoding and retains bounded negotiation attempts while the timer is active. A negotiated source is recorded separately from completed-file decoding; it does not prove growing playback on a native renderer.

`refresh.py` requires a new successful task execution result after its POST; stale Idle or a failed result cannot pass. Cleanup checks each owned timer/rule deletion and continues attempting the rest if one fails; any failed cleanup makes the receipt fail. Returned media URLs can be relative to the configured proxy base or absolute on the same origin. Other origins are rejected before sending fixture Authorization.

A recording DTO's missing `IsInProgress` is not completion evidence. Recording mode always queries the growing list, tracks the generated recording IDs while active, and waits for both to leave that list with no owned active timers. The decoded completed-file duration must be110–150s for the nominal120s fixture (HLS segment/window tolerances). Server-retimed windows fail immediately. An earlier12.4 diagnostic changed a manual timer to a future28-minute guide episode and exposed only8s while still growing; that receipt was rejected, not committed as completed proof.

Guide task completion and source freshness are separate. `refresh.py guide` requires exactly one fixture XMLTV provider at the internal origin's `/guide.xml`, preserves its Id/options and varies a query cache key so Jellyfin reloads regenerated XMLTV instead of its previous source cache. It then requires a new successful task execution; recording mode also verifies the actual API programme/timer times. Run the live origin before refreshing. Partial files from failed/canceled diagnostic jobs can remain in these disposable recording volumes; use the completed receipt's hashes/durations when selecting a full recording.

New fixture roots install a `logging.json` override at Fatal (including framework overrides) before first login. Jellyfin's normal server logs can include logout tokens, OpenToken or request-query tokens; the fixture uses sanitized contract receipts for diagnostics instead. Historical diagnostic logs/containers from provisioning are cleared at handoff. Do not enable verbose server/proxy logs or publish raw output containing credentials. If a later investigation needs server logs, arrange redaction before collecting them. This setting applies only to these disposable fixtures.
