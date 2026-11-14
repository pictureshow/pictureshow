# SyncPlay / watch together (launch draft)

**Status: Android reference implementation in progress; timing qualification is still required. Apple, web and desktop owe native adapters, UI, and independent rendered-output qualification.** Apply [shared session/queue](session-control.md), [API version contract](launch-api.md) and APP/PLAY rules. Evidence: [Jellyfin Web SyncPlay behavior](https://github.com/jellyfin/jellyfin-web/tree/master/src/plugins/syncPlay) and server SyncPlay API; [Plex Watch Together](https://support.plex.tv/articles/watch-together/) describes the shared viewing intent but notes retirement from newer native experiences and continuing Web availability. The Plex statement uses its official indexed excerpt and the prior intent inventory; direct article retrieval was blocked on this research date. No live Plex UI test is claimed. No GPL code or Plex service protocol is copied.

| ID | Behavior | Acceptance criteria |
|---|---|---|
| SYN-01 | Watch together lists server groups and allows Create, Join and Leave according to SyncPlayAccess policy. | Server/viewer scope is explicit; no Pictureshow cloud account, anonymous invite or cross-server room. Group name/participant visibility explains that other authorized users can see it. Creation/join denial differs from no groups. On TV every operation is reachable by remote and reader. |
| SYN-02 | Join resolves the group's authoritative queue/current entry and confirms media access for each participant. | Never start from a stale local queue; repeated media IDs use server PlaylistItemId. Inaccessible/missing content produces LibraryAccessDenied/recovery, not a share link or permissions bypass. CreateAndJoinGroups/JoinGroups/None are separately tested. Group state and queue events may arrive in either order. |
| SYN-03 | Pause, play, seek, next/previous and queue/repeat/shuffle changes are group requests. | A local UI/hardware command never changes only one participating player first and leaves the group unaware. Server commands own scheduled action; no “host-only” permission is invented. Show who is waiting using only server-provided facts; all permitted participant controls remain explicit. Each rendering participant reports its own viewing once. |
| SYN-04 | Waiting/buffering participants send readiness with actual queue entry/position/state. | Buffering and Ready messages carry When, PositionTicks, IsPlaying and PlaylistItemId. Do not Ready before renderer can start; no zero-position placeholder. Default waits for participants; Ignore wait for this device is deliberate and described as excluding it from the wait barrier, not kicking someone else. Permanent failure allows Leave; no infinite unannounced waiting. |
| SYN-05 | Server clock and drift correction use deterministic measurable tolerances. | Calibrate via GetUtcTime; send measured RTT to SyncPlay/Ping. At 1x absolute drift <=80 ms needs no correction; >80..250 ms uses 0.97x ahead / 1.03x behind only if the engine advertises that exact rate and source permits rate changes, for at most 5 s, then restores 1x/rechecks. Otherwise or >250 ms seek with 2 s cooldown. Paused commands seek and stay paused. Vectorize equality and future/late scheduling; never echo applied group commands. |
| SYN-06 | Sync quality is visible and bounded by real renderer capability. | On controlled LAN VOD, each supported platform pair converges to ≤250 ms drift within 5 s after start/seek/rebuffer, stays there for ≥95% of a 10-minute measured run and has no unexplained >1 s excursion. Record measurement uncertainty; if uncertainty exceeds 50 ms or route lacks timely position/seek, report Not qualified. Buffer waits and correction seeks are separately counted; do not hide poor runs with an average. |
| SYN-07 | Group events are scoped, ordered and stale-safe. | Deduplicate MessageId when present, reject another group/old membership/queue entry, compare EmittedAt and queue LastUpdate and preserve server order for equal timestamps. Missing/gapped/out-of-order state triggers paused resnapshot/rejoin rather than applying stale commands. Unknown future event type is recoverable, never silent success or app crash. |
| SYN-08 | Reconnect, background and leave have explicit ownership. | Network loss pauses participant; reacquire membership, queue and clock before output. Deliberate Leave retains local paused resume and never Stops group. Video background pauses and sends Leave once; foreground requires deliberate rejoin. Music may continue while active OS audio and calibrated clock meet SYN-06; loss of either pauses and leaves once. Late membership events cannot undo Leave. |
| SYN-09 | Track/quality preferences are local; shared timeline remains common. | Participants may use different supported audio/subtitle/bitrate paths for the same item, with full-media ticks. Local subtitle delay and styling do not seek the group. Skip/next actions that change timeline become group seeks/queue requests; disable independent auto segment-skip/credits/episode-next while in group. Group repeat/queue owns continuation. |
| SYN-10 | Native Jellyfin TV and remote control support groups; external casting requires leaving at launch. | Phone controlling a group TV sends to that TV, which makes group requests. Selecting Cast Connect, Web Receiver or AirPlay requires explicit Leave group before casting; cancel keeps current membership/output. SyncPlay over these external routes is later improvement work under SYN-PD03, not a launch gate. A TV joining directly as a native Jellyfin participant remains supported. |
| SYN-11 | Live/infinite streams and offline copies do not pretend to be common VOD. | Group VOD and music are launch paths. Live TV lacks a shared guaranteed seek origin; display unavailable Watch together for live/infinite sources unless an independently qualified common recording timeline exists. Offline cannot join a server group; reconnect and validate the same media source/timeline first. |

## Clock policy and executable vectors

Use exact signed 64-bit 100 ns ticks with overflow checks. If local request send/receive times are t0/t3 and server reception/transmission t1/t2, offset is `((t1−t0)+(t2−t3))/2`, network RTT is `(t3−t0)−(t2−t1)`. Keep five valid samples, initially sample five times, select the lowest-RTT sample, refresh every 30 s while active and immediately after foreground/reconnect. These are finite session tasks, not standing polling. Reject negative RTT and samples >1 s; if none valid, stay paused and show unavailable synchronization. Map server UTC onto a monotonic local anchor; a wall-clock jump >100 ms invalidates calibration. Target ticks at local execution time are commanded PositionTicks plus elapsed server time since When only for a playing command, clamped to the actual media bounds.

`sync-clock`: positive/negative offset, asymmetric delays (uncertainty), processing time, invalid/slow samples, wall-clock jump, future/late commands, 64-bit tick conversion. `sync-drift`: ±80/±250 ms equality, rate limits/5 s cap/2 s cooldown, paused correction, disabled rate, seek bounds, restore rate on Stop. `sync-state`: Idle/Joining/Waiting/Playing/Paused/Buffering/Reconnecting/Leaving/Error, Ready with entry ID, stale/duplicate events, 10.10 versus 10.11/12 event shapes, denied policies, missing snapshot. `sync-queue`: repeat/shuffle server authority, duplicate item identities, simultaneous edits and local auto-next suppression.

## Platform-dependent rows and proof

| ID | Platform-dependent limit | Acceptance and fallback |
|---|---|---|
| SYN-PD01 | Android Media3, Apple AVPlayer and browser time/rate/seek precision differ. | Run the same reducer vectors, then independently measure SYN-06 on each renderer pair. Unsupported temporary rate uses seek correction; no inferred pass from HTTP success. |
| SYN-PD02 | Mobile/browser suspension and autoplay gating can prevent scheduled output. | Deliberate Join/Play unlocks playback; if suspended, pause/reconnect and reacquire queue/clock before resuming. A closed tab cannot participate. |
| SYN-PD03 | External-output timing differs: Cast/AirPlay SyncPlay is later improvement scope; local Bluetooth output adds hardware buffering. | At launch enforce SYN-10 Leave group before casting for Cast/AirPlay. Later external-route synchronization needs SYN-05/06 measurement and wait/clock control before enabling it. For local Bluetooth measure actual rendered output in SYN-06; a failed timing gate is an explicit unqualified route, never silent free-running. |
| SYN-PD04 | Live/infinite streams have no guaranteed shared seek origin; offline has no server membership. | Show unavailable for those paths; a completed recording with qualified VOD timeline can join. This is an explicit source limitation, not an omitted SyncPlay control. |


Android uses Media3 position/rate/seek and native WebSocket; Apple AVPlayer time/rate observers and monotonic timing; web uses media time/rate, WebSocket and performance timing. Browser autoplay may require explicit Join and Play and hidden tabs can lose timing precision; rejoin after suspension. Audio route buffering (AirPlay/Bluetooth), Cast position cadence and TV decoder behavior are measured limits, never inferred from a successful API call.

Prove two independent viewers, mixed platform pairs, same and differently transcoded paths, late join, seek/pause, rebuffer, one slow client, denied library, host departure, network loss and reconnect against isolated 10.10.7, 10.11.11 and 12.4 (household 12.0.0 unchanged for acceptance). Use a generated visual/audio timecode fixture and a shared capture clock; server/request timestamps alone do not prove simultaneous displayed frames. SyncPlay is watch-together synchronization, not sample-accurate multiroom speaker audio or voice/chat.


## Android reference adapter and executable contract (2026-11-10)

The Android reference uses the signed-in viewer's existing Jellyfin socket. Disabling remote UI receiving does not disable this group channel. Incoming server transport is applied through an explicit decoder bypass so it cannot echo a second group request. Ordinary UI, hardware and remote transport is intercepted before the decoder moves. Each renderer keeps its existing single report owner.

The executable `sync-clock`, `sync-drift`, `sync-state`, `sync-queue` and `sync-source` suites exercise production Kotlin policies. Source cases cover positive signed-64-bit duration boundaries and every combination of seekable, live, infinite and offline flags. Tick values are decimal strings at JSON adapter boundaries where a number would exceed JavaScript's exact integer range. A vector pass proves the policy, not native timer or renderer precision.

An acknowledged new empty Idle group can emit Stop with an all-zero PlaylistItemId and no PlayQueue. Retain Idle membership without inventing a queue or entering a resnapshot loop. After a populated queue, Stop refers to its current PlaylistItemId. Ready can schedule Unpause before a subsequent StateUpdate; consume the scheduled command when received, preserving it independently of the transient reducer field.

Observed Jellyfin 12.0.0 queue/readiness behavior: creating an empty Idle group and setting its first queue can emit PlayQueue without StateUpdate, although the server already considers it Waiting. The participant must report actual renderer readiness for the new PlaylistItemId even if its cached group phase is Idle. Direct Ready may then produce a scheduled Unpause followed by Playing/Ready; a prior synthetic Buffering is unnecessary. Stop likewise can arrive without StateUpdate: retain its entry identity and suppress readiness that would restart that stopped entry, until a different authoritative entry or a non-Idle server phase permits it. The shared readiness cases distinguish these transitions; a protocol probe is not rendered-output proof.

Android uses seek correction unless its actual source/output explicitly qualifies the exact temporary rates. Media3's generic speed capability alone does not establish encoded-output support for 0.97/1.03. The visible status states clock uncertainty and renderer-position drift, with “not qualified” until SYN-06 has independent frame/output evidence. Unqualified background music pauses and leaves, just as video does; it does not claim timing after suspension.

The group chooser is shared by TV and touch. Cast device selection requires a deliberate Leave group and successful server Leave before dispatch; cancellation retains membership and output. Native Jellyfin remote targets remain group participants and convert received transport/queue edits into group requests.

Observed protocol reference, 2026-11-10: demo Jellyfin 12.0.0 with two isolated viewers, plus isolated 10.10.7, 10.11.11 and 12.4.0. Group operations, access policies, buffering/readiness, timing messages and server queue mutations were exercised through real HTTP/WebSocket clients: 39 targeted checks on the demo and 44 on each isolated version. These protocol checks do not prove the Kotlin adapter, native UI or SYN-06 rendered-output timing. Native timing qualification remains required. No GPL client code or text was copied.

Source eligibility is a shared rule: positive full-media duration, an actually seekable renderer, and no live, infinite or offline source. A finite-looking duration does not override IsInfiniteStream. Android pauses an unsupported prepared source and visibly offers Leave; it never reports Ready for that source. The executable `sync-source` suite covers the boundaries. Android TV and touch propagate immutable local-copy provenance from the actual offline playback binding; reconnect offers and restored connectivity do not authorize that local renderer. A completed, validated handoff opens a new online renderer, whose actual server source must independently satisfy the same eligibility checks. Cached download metadata and server file paths do not make an online renderer offline. The restriction also applies before source negotiation, to downloaded audio, and to each local next-episode renderer; an identical item or source identifier across the handoff does not transfer eligibility.

When a participant selects a different local video catalog title, Android stages the selection until metadata and the paused renderer prove source eligibility. It then requests SetNewQueue once, without starting local output. Unknown metadata cannot replace the shared queue. Membership changes or renderer disposal discard the staged selection; a server selection of the same item wins with its own entry and position.

A ready decoder for media X under playlist entry A cannot release readiness or execute the scheduled command for a later entry B merely because B names the same media X. Gate on the replacement renderer binding or its actual playlist slot identity, including persistent music engines. Catalogue lookup success is not decoder readiness. Reusing a deliberately staged source is allowed only for its matching server acknowledgement; later duplicate entries still prepare independently.

Video navigation carries the exact accepted opening generation, PlaylistItemId and media ID into the eventual renderer binding. A late same-media renderer for an earlier entry or membership cannot bind or acknowledge the current entry, even after a newer renderer has already become ready. A rejected route retains a durable paused output intent. Native navigation persistence preserves the stamp as primitive fields; a resumed route does not invent current membership.

Android debug evidence separately records accepted phase transitions, readiness/buffering changes, scheduled timeline application and drift-correction seeks. These fixed numeric/status fields exclude viewer names, media/group identities and server locations; they support the separate SYN-06 counts and do not qualify rendered timing.

An Unpause response to Ready can carry a newer emission/message identity while retaining the exact group, queue-entry identity, When and authoritative base position of an already applied Unpause. Accept its ordering and state effects, and restore the playing mode when readiness and the group phase permit, without treating that equivalent timeline as a new positional seek or bypassing the autonomous correction cooldown. This exception requires a successful fully playing application on the current renderer, membership and entry; pending, aborted or Waiting-only applications cannot establish it. It survives a temporary Waiting/mode retry and autonomous correction, but a changed explicit timeline, source/renderer/queue identity, membership or invalidated clock clears it. Ordinary valid clock refresh alone does not create a new playback timeline. `sync-clock` vectors compare timeline values independently of emission identity; Android host regressions cover the ownership and application fences.

Automatic group alignment and drift correction update output without opening ordinary user seek controls or a trickplay preview. Preserve an existing deliberate preview rather than replacing or clearing it, including when the seek must wait for native seeking commands. A subsequent deliberate seek retains normal feedback. The `sync-clock` seek-feedback cases describe this presentation rule; Android exercises immediate and deferred native seek paths.

An autonomous seek decision must be checked again against the actual native position at execution. Advance the sampled playing target by the elapsed monotonic time, clamp it to duration without overflow, and express it at the renderer's actual position resolution (Media3 uses milliseconds). Within the inclusive80ms playing band, omit the positional seek; paused alignment omits an already representable target. A skipped or deferred application does not create a fresh actual-seek cooldown or readiness obligation. Explicit Pause/Seek/Unpause application keeps its separate authoritative-command rules. The `sync-clock` correction-position cases cover late execution, the exact80ms boundary, position resolution, paused fractional targets, invalid clocks and overflow-safe duration clamping.

A scheduled server transport action that changes renderer position starts the same two-second autonomous correction cooldown as a drift seek, restoring 1x. A buffering/Ready/Unpause cycle for the same entry cannot clear that deadline and immediately seek again. Explicit future group Pause/Seek/Unpause commands remain scheduled normally; this cooldown gates autonomous correction, not viewer intent. `sync-drift` cases include the instant before and exact deadline, paused output and an aligned renderer. An aligned scheduled command that issues no renderer seek preserves the old deadline rather than extending or clearing it; the adapter reports whether it actually issued a seek. Stop/Leave discard the old viewing's deadlines.

At scheduled Unpause execution, compare the renderer against the recomputed execution-time target. Within ±80 ms inclusive, apply playing and 1x without a positional seek; retain the original server timeline and When. This avoids decoder buffering for an already aligned resume. Outside that band retain alignment. Explicit Pause and Seek remain positional even inside this band. Existing entry/readiness/clock fences and actual-seek cooldown still apply; this optimization is not a rendered timing qualification.

SYN-01 access presentation distinguishes an unknown/in-flight or failed policy check from a confirmed SyncPlayAccess=None denial. Unknown/failed access keeps Create and Join fail-closed, clears stale chooser groups, and offers Refresh once idle; even a confirmed denied viewer can Refresh to discover a later administrator policy change. Only a successful current-viewer policy response can enable group actions or display a permission denial.

An accepted future command becomes the pending timeline immediately, although renderer application still waits for When. A Playing state or accepted Unpause must not extrapolate an earlier paused Seek through its waiting interval or trigger autonomous output before the new scheduled start. Existing entry, membership, clock and readiness checks still apply at execution.

A Seek timeline retains its actually applied paused/playing mode. A later StateUpdate(Playing) alone cannot authorize extrapolation or playback between that event and the next scheduled transport command. A newly accepted pending command replaces the old timeline immediately; execution records the mode actually applied.

Pending and applied timeline modes are distinct. If a command reaches When before its renderer is ready, retain it and retry scheduled application once ready with a valid clock and matching entry/generation. Apply its play/pause mode even when its recomputed position is already aligned; a drift-only retry cannot replace that transport application. Only an applied Seek freezes its applied mode across later phase notifications.

A pending Seek accepted in a confirmed Playing phase retains that playing intent through unready output and a temporary Waiting barrier. While Waiting its output remains paused; lifting the barrier allows the already accepted command to apply its retained mode. A Seek accepted while Waiting has no inferred resume authority: apply it paused and await explicit Unpause, even if a later phase notification says Playing. The wire has no Seek play-mode subtype; a past When alone does not imply resume.

While a retained playing command is blocked by Waiting or Paused, readiness/drift retries hold output and preserve that intent; they do not repeatedly dispatch paused transport on every tick. After confirmed Playing and readiness, pending application still applies its play mode even at an already aligned position.

An actual renderer positional seek invalidates that renderer's previous readiness acknowledgement for the current membership and playlist entry. The next eligible observation reports the new actual position/state once, even if BUFFERING→READY completes between sampling ticks. A future scheduled command does not invalidate readiness until its position is actually applied. An aligned/no-op application, or a Waiting/Ready phase notification alone, does not re-arm readiness; unchanged subsequent observations remain deduplicated. If the renderer is still buffering, report its actual unready state and then fresh Ready after recovery. Existing clock/source/entry/generation fences apply to both reports; membership or renderer replacement discards the previous acknowledgement. The `sync-state` readiness-cache sequences cover short/long buffering, no-op applications and scope replacement.

Actual native seek completion, including a seek delayed until the decoder advertises its seek command, invalidates the old readiness acknowledgement and starts the correction cooldown at completion. A short BUFFERING→READY cycle between samples still requires fresh Ready at the new position. Callback ownership must match the bound renderer token and current decoder; callbacks from retired sources cannot affect its readiness. Decoder replacement also resets the readiness observation, including a completed server-side seek reopen.

A deferred server-position application retains its transport-bypass intent until the native seek is issued; it cannot turn into an outgoing group Seek when command availability changes. The bypass ends with that application and does not exempt later viewer transport. Native completion callbacks carry the membership generation and PlaylistItemId captured by their binding; even a reused decoder playing identical media cannot borrow an earlier entry's callback.

The `sync-application.json` adapter vectors cover delayed server seeks at ordinary and zero positions, delayed viewer-origin seeks, and the following viewer command. Each platform must exercise its real transport wrapper and native application path: a queued server application produces exactly one native seek and no group echo; a viewer-origin application remains a group request. Core readiness and renderer-bind vectors separately cover delayed completion, membership and same-media playlist-entry scope.

A SyncPlay queue open carries an explicit server timeline position. Local saved-resume rewind preferences never subtract from that position; preserve the membership/entry stamp independently of playback-start provenance. Ordinary saved resumes continue to honor local rewind settings. Portable case: `playback-start.syncplay-server-position`; Android navigation restoration verifies both provenances.

Readiness `IsPlaying` means the native playback is **unpaused**, as defined by the [Jellyfin BufferRequestDto contract](https://typescript-sdk.jellyfin.org/interfaces/generated-client.BufferRequestDto.html). It is distinct from currently advancing/rendering: buffering or audio suppression can stop rendered progress while native play intent remains true. Capture the actual position, When and native play intent together before a local wait hold. Ready after a real local pause reports false; never infer true from a future scheduled Unpause or group phase. Both Android adapters provide this fact independently of debug instrumentation. The five `sync-application` readiness cases exercise actual native state and the wire mapping; every platform owes its equivalent native adapter test.

Automatic server or readiness-barrier pauses update native pause/buffering facts without revealing full playback controls. A deliberate viewer Pause, including a media-session, hardware or Jellyfin remote-controller request, reveals controls immediately even while awaiting the authoritative group response. Ordinary solo pauses retain their existing feedback. Re-evaluate this rule when membership changes without replacing the decoder; use current callbacks. The seven `sync-application` pause-feedback cases cover solo, viewer group, barrier and server origins. The persistent group-status chip communicates waiting without replacing the video with controls.

An older-emitted command that exactly repeats the most recently accepted command's group, entry, command, When and position is a no-op, including across intervening StateUpdate events. Preserve the authoritative snapshot and latest observed phase; never replay the older command or lower the emission watermark. A changed older command still requires paused recovery. Observed Jellyfin 12 readiness responses can interleave these identical timeline replies with later emissions.

Snapshot loss is never a local source selection. A renderer rebind during Reconnecting or Joining without a queue cannot stage or send SetNewQueue from its local start/resume position. Discard staged source replacements when recovery invalidates the snapshot. An acknowledged empty Idle group may still accept an explicit eligible local selection. Portable cases cover identical older Pause/Unpause/Seek and the invalid-snapshot source fence; Android exercises actual binding reinstallation.

Command identity retains the wire When timestamp at 100 ns precision independently of native millisecond scheduling. A one-tick change within one millisecond is a different timeline, both for older-command filtering and already-applied Unpause equivalence. Portable cases exercise both identity paths.

For Android video, a confirmed PCM output from Pictureshow's own renderer permits exact0.97/1.03 temporary correction through its software Sonic processor (float output and platform playback-parameter processing are disabled). A generic speed command is insufficient: an unobserved output, encoded passthrough/offload, an injected renderer without this contract, or unavailable speed command retains bounded seek fallback. Reset output evidence when the decoder is replaced or released, and reject late output callbacks from the old decoder generation. Five `sync-clock` PCM-capability cases define the admission rule. This capability is distinct from independent SYN-06 rendered-output qualification; music retains seek fallback until its own output contract is implemented.

Temporary group rate corrections belong to synchronization, not the viewer's selected playback speed. Keep the selected speed and its explanatory messages unchanged through temporary0.97/1.03 rates and restoration to1x, including delayed native parameter notifications. On detaching group rate control, restore the viewer's selected speed without creating a false audio-output-adjustment message. Ignore obsolete parameter notifications that no longer match the live renderer parameters. Genuine encoded-output restrictions and later ordinary playback-speed adjustments retain their existing feedback. The four `sync-rate-presentation` cases define these presentation and ownership rules.

A membership Create/Join waits for server session registration on the current socket, not merely its transport-open callback. On Jellyfin, a valid ForceKeepAlive message establishes this boundary: the server attaches the connection to its session controller before sending it. Invalid heartbeats and callbacks from a retired connection cannot authorize a request; disconnect and socket replacement discard registration. Coordinator reattachment uses the registered state as well. Keep the existing bounded connection timeout and paused recovery if registration never arrives. The five `sync-socket-readiness` adapter cases exercise these transitions through the real socket owner; platforms must test their own connection boundary.

A permitted early Create/Join intent is retained during registration, including when transport readiness is still unknown. Keep local output paused, issue the request once the current registration arrives, and cancel it on explicit Leave/account replacement. An absent registration expires after the existing bounded wait into paused recovery without an HTTP membership request. Android host regressions cover early Create and Join, cancellation and timeout alongside the connection adapter vectors.

Cancelling a membership intent before its HTTP Create/Join was sent performs no server Leave. Local Joining alone is not proof of server membership. Retain possible server membership through recovery and rejoin: cancellation must still Leave an earlier confirmed group or an uncertain request already sent. Explicit external-route consent can proceed after cancelling a purely local unsent intent, with local output paused and no network dependency. The twelve `sync-membership-registration` cases assert the entire request list, including prior membership, definite denial versus an uncertain sent request, an acknowledged server departure and both external-route cancellations.

Native rate-application evidence must distinguish the requested rate, the exposed player wrapper and the actual renderer parameters. A wrapper's optimistic state is insufficient proof of application. Exercise the temporary-rate and restoration sequences through the real native adapter and assert its underlying renderer parameter, preserving the viewer's speed choice. Debug traces label the exposed and native rate separately and record native parameter callbacks; rendered-output qualification remains independent.

A fresh emission of the same already-applied Unpause restores its permitted play mode without interrupting or restarting a current temporary rate correction. Preserve the correction state and its original five-second deadline when no position changes. Apply a temporary rate only while that deadline is still valid, playback is permitted and the current source still supports it; otherwise use1x. A changed timeline or actual positional application retains ordinary transport reset and cooldown behavior. The twelve `sync-clock` replay-rate cases cover active, expired, held, unsupported and invalid-clock boundaries; Android also exercises the real host response and subsequent deadline fallback.

A future Unpause accepted for a paused, READY source already aligned within80ms of its base records that preparation separately from an already-applied timeline. At When, timer lateness alone must not introduce a positional seek into that prepared source: apply its permitted play mode and retain the original advancing timeline for subsequent ordinary drift correction. Revalidate the same account/membership/entry/snapshot, renderer/token/native source, current clock generation, actual readiness/paused state and absence of a pending positional application. Clock invalidation, native positional completion, replacement or changed command/base/When discards the preparation. A command already late on receipt, or a genuinely misaligned source, retains catch-up positioning. Waiting still holds output; an unready source cannot borrow preparation. This rule does not widen drift thresholds or establish rendered convergence. The13 `sync-clock` preparation cases and Android late-wake/source-replacement regressions define the distinction.
## Android presentation and portable projections

`sync-presentation` supplies 41 cases for semantic group phase, local sync estimates, title-response ownership and the names-only roster. Every title row retains both ItemId and PlaylistItemId; duplicate entries remain separate. Authenticated metadata lookups are cached within an account and membership generation. Replies are accepted only for the requesting account/token, membership generation, queue revision and still-present requested playlist entries. Loading and unavailable titles have separate localized fallbacks; synthetic Item N titles are not shown.

The player retains a compact group chip outside the ordinary controls overlay. Its semantic phase/quality changes are polite announcements; numerical timing diagnostics never become UI state or live-region text. In sync is explicitly an estimate of this device, available only with a current usable bounded-uncertainty clock, matching ready renderer, applied current timeline and bounded error. Stale clocks, future/held commands, pending position application, missing readiness and disconnected memberships cannot assert it. This estimate is independent of SYN-06 native renderer/physical output qualification.

Jellyfin GroupInfoDto Participants is a names-only list. Display actual names as separate rows, capped at four with a localized remaining count, and display group-wide waiting. Do not infer which name is buffering, individual readiness or “you” from a name match. Local renderer facts appear beside group phase with an explicit This device label. A Waiting/Ready state is still waiting; it never means all people are ready. State-update wait reasons clear on membership replacement/reconnect rather than leaking to another group.

TV uses native list, switch, selected repeat chip and icon transport roles with content/data-ready initial focus, a scrolling body with fades/position cue and pinned error-colour Leave. Touch uses a full-screen Scaffold with close icon, scroll body, system/IME insets, a fixed transport row, full-width segmented repeat and pinned footer. Wait for me is the inverse of server-confirmed IgnoreWait. Requests do not optimistically change it, repeat, shuffle or queue. Errors carry typed recovery actions and survive transient information; all viewer copy and counts use string/plural resources and locale number formatting.

Android debug builds alone register `SyncPlayFixtureActivity`, protected by `android.permission.DUMP`. Named fixtures render the actual panel without a host/gateway/account and carry a visible UI fixture banner. They establish layout/focus only and cannot qualify protocol, timing or playback. The registration and presets are absent from release builds.

A queue-title lookup from another queue revision is stale even when every requested PlaylistItemId still appears in the current queue. Reject that response independently of the account, membership and entry-presence checks; the retained-entry revision case in `sync-presentation` isolates this fence.


### Persistent group chip presentation

Keep group status visible when the ordinary player controls hide. The primary line
states the group playback phase and local sync estimate; group name and localized
people count occupy a separate secondary row. Preserve explicit icon spacing and
keep separators attached to their text when a status wraps. Long group names may
ellipsize while the people count remains visible. “Looks in sync” is a local estimate,
not measured peer-output qualification. Android TV keeps this interactive chip inside
the title-safe area (at least48dp horizontally and27dp vertically); touch respects
safe drawing insets. The opener remains focusable and announces state politely.

Android TV safe-area source, checked2026-11-13:
https://developer.android.com/training/tv/playback/compose/layouts .


### Native UI recovery, layout and capture contract

Transport is available only when the current group controls are usable and its
**authoritative queue has a positive entry count**. With an empty queue the TV and
touch group panels omit Previous, Play/Pause and Next, explain how to start the queue,
and start TV focus on the useful Wait control. A nonempty queue retains the existing
transport roles, availability and request behavior. The native player screen’s
separate empty-group primary action remains disabled. The pure Kotlin
`syncTransportAvailable(usable, queueEntries)` projection implements the six portable
`op=transport` boundary cases. This does not change group transport authorization,
readiness, playback clocks or source policy.

TV keeps local playback facts next to group phase in the first viewport. The
names-only limitation is stated once; up to four actual names (including duplicate
names) and localized +N are separate, remote-focusable **read-only facts**, with no
button/click/disabled/selected/switch semantics. The local fact does not identify a
remote bufferer or infer “you”. On TV with a nonempty queue, a short group-wide
playback/queue fact follows status, above transport and Wait, with12dp of block
separation rather than reading as the Wait row’s caption. An empty TV group instead
keeps its concise library-play next step and the explicit personal/shared section
scopes, omitting the redundant free context paragraph so the shared section can
continue visibly at large fonts. TV additionally places a native Your settings
section header immediately above Wait explaining the choice of whether the group
waits for this TV, and a Group playback header above Repeat/Shuffle stating that
changes apply to everyone.
Repeat is a setting within that shared section, using the native toggle-row title
typography and normal setting-title foreground, distinct from the quieter section
heading. Keep its native selected chips and8dp horizontal spacing. The visible chip
outlines have at least12dp separation before the next setting plate, greater than
their label-to-control gap. Whole-control fade protection checks the actual painted
content against the cut-content mask and opaque guard. Modifier order of an
`onGloballyPositioned` observer alone does not change the coordinates it reports;
spacing and paint remain independently checked by the layout and native-pixel cases.
When a minimal-focus-scroll TV panel has a pinned preview directly adjoining the
scrolling viewport, with no intervening pinned editor or notice, leave12dp below
the preview before the viewport and its top continuation rule. Transfer the existing
12dp list top inset into that fixed header, preserving initial body positions.
Panels with an intervening editor/notice, default shared panels and direct-list
callers retain their existing layout. The full40dp cut-content mask,8dp opaque
guard and whole-visible enabled-paint rules remain unchanged.
These passive labels do not
change the Play→Wait→Repeat→Shuffle remote order or initial Wait focus for an empty
queue. Touch retains its existing explanation beside Wait.
Recovery actions take initial focus when offered; Leave appears only in the pinned
footer. A completed asynchronous populated group load preserves an attached Refresh
focus, while a newly opened loaded chooser starts on its first Join. One bounded
exception promotes a loading EMPTY chooser from Refresh to the attached Group name
field when its first settled access/list result permits creation, only before actual
D-pad/Enter or pointer navigation. Programmatic focus does not claim user ownership;
input received before the delayed native request prevents that request. Later
refreshes, group publications and populated results do not repeat the promotion.
Focusing the field does not open its keyboard. Actual mode changes
and removed focus targets recover useful focus. Removing a queue entry focuses the
entry now at its old index, else the previous entry, else enabled Wait. Queue rows
share one title column and quiet remove actions; the selected current entry remains
visible independently of focus. The SyncPlay TV panel opts into minimal scrolling
for already visible targets,12dp footer surface edges and scale1 full-width footer
buttons; other shared settings callers retain their prior defaults.

Touch compact portrait remains full-screen. Wide windows keep the Scaffold, app bar and footer backing full-bleed; only their
readable body/footer content is centered at a600dp maximum. Compact height uses a48dp collapsing app bar, compact
status so useful transport is initially visible at640×360dp. At every touch height,
the Leave caption and action share the existing compact footer row; the caption may
wrap naturally beside the native48dp action. While the actual body can scroll
forward, a1dp outlineVariant divider spans the pinned footer’s upper edge. The body
and footer read the same native scroll state; fade/reveal rules remain unchanged.
A compact chooser never pins Create to a footer: its field and inline Create remain
together before and during IME display. Empty touch choosers put this form first at
every height. IME Go submits a nonblank trimmed name. Compactness and IME insets
are read inside the Dialog window; its caller’s inset state is not evidence of
keyboard occlusion. While a compact IME is visible the footer is absent. Regular
portrait retains pinned Create above the IME. Native system/cutout/IME insets remain
in force; bottom fades do not cover a field when only terminal padding remains.
Plain text, fields and footer use the same16dp column as native list items. Empty
footer surfaces are omitted. A long unbroken group token ellipsizes on one line;
ordinary multiword names balance across at most two lines.

Typed notices are projected once for display and focus. Rounded banners keep their
explicit outlined recovery action in the message column with theme content colors.
The pinned footer owns Leave; a failure banner suppresses a duplicate error phase.
A local sync estimate is not peer timing qualification. The persistent chip retains
its independent status/context rows, count visibility, native safe insets, natural
width cap and non-orphaning status separators, including under font stress.

Debug-only immutable fixtures additionally expose all four local states, empty and
unavailable/long-title queues, warning/information notices, and a separate labeled
chip stress area. These use actual UI components without a host/gateway/account;
local fixture transitions prove layout and focus only. They never establish actual
protocol, membership, casting, timing, physical output or playback behavior.

A source/test build is not the visual acceptance gate. Handoff requires new exact
source/APK/installed-hash receipts, native XML/geometry and full-size deliverables
plus reduced model images, all accepted TV/phone/stress findings, real versus fixture
labels, compact IME/landscape initial transport, large font/tablet and chip stress,
read-only roster/neighbor/recovery focus, intermediate motion and honest missing
states. A whole-screen design-director recapture verdict remains required. Web,
Apple and desktop owe their native equivalents, UI/accessibility evidence and the
new portable presentation cases; Kotlin projection success does not implement those app cases.
The existing Web pending-app-case report is a debt ledger, not implementation proof.


### Native follow-up presentation rules

The joined panel names the actual current queue title directly under the group
headline and above the phase/local facts, on both TV and touch. Only the entry
marked current supplies this line; do not substitute the first future entry.
Unavailable metadata takes precedence over a stale title and reads “Title
unavailable”; pending metadata reads “Loading…”. Empty queues say “Nothing playing
yet” once. Item IDs, URLs and debug identifiers never substitute for a title.
Current metadata does not supply episode or series facts, so none are inferred.
The touch current-row selection is a full-width rectangle with native16dp text
padding; all queue titles retain one shared column and independent48dp remove
controls. Unavailable library copy describes leaving to watch on one’s own rather
than promising control of the shared queue.

The panel phase does not repeat a quality recovery hint already owned by its
banner. `syncPanelQuality(quality, recoveryOffered)` returns UNKNOWN precisely
when a non-Leave recovery action is displayed. The warning sentence is “This
device is out of sync with the group. Rejoin to catch up.” Error reconnection
retains “Lost sync with the group. Rejoin to catch up.” The banner owns the one
Rejoin action and its initial focus; only the pinned footer owns Leave. Generic
playback failure offers an actionable Leave-and-join-again instruction, without
inventing a different recovery protocol. TV notice surfaces use16dp horizontal
and12dp vertical inner padding and12dp actual separation from adjacent content.
Their action shares the message column and uses the correct theme foreground.

The chip independently applies `syncChipShowsQuality`: UNKNOWN has no quality
suffix, while known IN_SYNC, CATCHING_UP and OUT_OF_SYNC estimates remain visible
with Playing/Paused. Waiting and reconnecting keep their own phase. A balanced,
at-most-two-line status, a centered decorative20dp icon, natural measured width,
non-orphaning separators, actual group name and localized count all remain part of
the contract. Unknown omission does not assert that playback is synchronized.
The41 portable vectors include six recovery-banner cases and four chip-quality
cases; app behavior and each other platform’s native equivalent still need their
own implementation and evidence.

The TV chooser separates remote focus from text editing. Initial name focus is
allowed with no automatic IME. A paired Select/Enter explicitly starts editing;
Go submits once, and Back dismisses the keyboard before dismissing the panel.
The saved, single-line,200-character editor stays mounted under the fixed title
and server/viewer context rather than moving when the keyboard opens. Only the
scrolling content below it consumes IME space; a floating keyboard must not pan
the context away. The TV chooser keeps its pinned Create action and footer divider
mounted during editing and after the platform dismisses its keyboard. A zero-height
floating IME can still be visible: keyboard visibility and bottom overlap are separate
facts. Once the platform reports the keyboard hidden, the next Back dismisses the
panel once; a stale request-to-edit flag must not consume that Back. Preserve the
draft, the name field's focus and its bounds through keyboard dismissal. Initial
focus still does not request a keyboard, and Go still submits only once.

An enabled TV Create action uses a quiet tonal filled resting surface
(secondaryContainer/onSecondaryContainer on Android TV), then the same unmistakable
onSurface/inverseOnSurface focused fill as the native rows, without enlarging its
painted body or a dark-on-dark focus ring. Rest/focus paint contrast is at least3:1.
The pinned TV create section has the existing localized create heading and its
privacy supporting text above the stable name field, with an additional8dp caller
gap so the privacy glyphs and native floating field label have at least12dp of
separation. This spacing preserves the pinned editor, IME and keyboard action roles.
The empty joined status gives a localized library-play next step without an empty
transport row. These requirements need native font1.0/1.3, floating-IME and D-pad evidence; source alone
does not establish qualification. The six `sync-tv-attention` cases run through actual
Compose paint, focus and section geometry in Android; other platforms owe their
corresponding native adapters. Repeat chips also keep
their resting painted bounds on focus, whether selected or unselected; focus alone
must not change shared Repeat. The chooser's empty-group status uses quiet supporting
hierarchy (bodyMedium/onSurfaceVariant on Android TV), separated from Refresh by at
least12dp.
Busy placeholders are neutral, noninteractive surfaces matching the actual native
two-line group row:64dp minimum,12dp vertical padding and measured headline/support
styles. They grow with large fonts and share zero row spacing with loaded rows. Join-only permission copy precedes its group list.

SyncPlay opts into shared TV panel inset, pinned-editor and focus-scroll options;
other settings callers retain their defaults. The effective focus viewport excludes
active32dp top fade and the actual active bottom fade, plus8dp breathing room.
The bottom mask is at most40dp with a final8dp opaque band for cut or unknown content.
Use unclipped item geometry: a row crossing the viewport edge must not be classified
as whole merely because its reported visible bounds were clipped. For a wholly
visible known last row, shorten or suppress the mask as necessary to preserve every
solid label stroke and its trailing content at full enabled contrast. Derive painting
and reveal clearance from the same current mask extent; absent masks reserve no
invisible clearance. Empty, incomplete or invalid geometry retains the conservative
40dp/8dp cut-content treatment. A later known cut row must not dim earlier wholly
visible known content above the final opaque band: keep the mask extent and reveal
clearance, but hold its ramp transparent through that content plus one physical
pixel before reaching the unchanged opaque band. Unknown or incomplete geometry
retains the original conservative ramp. These opt-in rules cover every visual block
in this SyncPlay surface and preserve other shared-component callers' default behavior.
Fully visible targets do not move, violated edges move minimally, oversized targets
remain stable and terminal48dp padding lets the last read-only roster fact clear the
bottom fade.
Scrolling Android TV settings surfaces use a4dp position indicator in the
full-opacity onSurfaceVariant theme color with at least3:1 contrast over their
surface. The initial empty-group panel must show the Your settings header and Wait,
then a whole legible Group playback header above the active bottom mask, or the
Repeat chips if they also fit. The removed empty transport and concise TV intro
make room for that continuation; the40dp/8dp cut-content mask and whole-ink rules
remain unchanged. No scroll hint is added to the Leave explanation. The footer
explanation has8dp clearance from Leave. Native font1.0/1.3 captures must establish
that actual header/chip continuation and visible indicator paint; source is not
native qualification.
The Play→Wait→Repeat→Shuffle remote order remains unchanged. Transport controls
have12dp gaps and scale1 on focus; the pinned footer keeps12dp body surface edges,
scale1 and at least24dp bottom title-safe clearance (28dp content padding).

Touch orders shared queue content immediately after transport, before Repeat,
Shuffle, People and Your settings. This removes the personal-settings false
bottom without changing TV traversal. Wait remains server-confirmed IgnoreWait’s
inverse; when disconnected or playback is unusable the entire row is visibly
disabled with “Available when connected and playback is ready.” No optimistic
local queue/replay is implied. Its passive switch and whole-row action expose one
accessible switch. Portrait keeps a pinned app bar; compact height may collapse it
and must still show all transport above Leave on arrival at640×360dp/font1.3.

These source contracts require fresh native keyboard, fade, safe-area, wide/compact,
large-font and whole-screen director evidence. They do not diagnose the unrelated
fixture ANR, alter a clock or establish peer output timing. Web, Apple and desktop
native UI and app-case parity remain pending even when Kotlin vectors pass.


### Retained departure acknowledgement

A local departure retains a typed cause and the actual known former group name,
separately from transient informational notices. Stop ends this local viewing and
intentionally leaves; ended playback and closed viewing have their own causes.
Actual manual Leave retains “You left the group. This device is paused.” and can
show the former name as separate context. Confirmed Cast has its own cause; opening
or cancelling Cast consent has no departure effect. Unexpected accepted server
membership loss states only “You’re no longer in {group}.” No explanation invents
a kick, group dissolution, everyone leaving, a raw group ID, or a paused Stop.

The pure departure reducer scopes records to the account/token epoch, distinct
from changing membership generations and request sequences. Accepted new membership
or a changed account/token clears the old fact; a failed join intent does not.
Refreshing access/groups, time spent with the panel closed, and four-second INFO
notice expiry do not clear the record. The next chooser must actually lay out the
selected departure banner in a nonempty visible viewport before it can acknowledge
presentation. Higher-priority actionable/error banners retain the hidden record.
Only deliberate dismissal of that same chooser session, with matching scope and
record identity, consumes it. Composition disposal/configuration changes are not
dismissal. Old layout/dismiss callbacks cannot consume a newer record or account.
These acknowledgement callbacks send no connection mutation. Leave still invalidates
old events immediately, sends at most its existing required request, and waits for
confirmation before an external Cast route; cancellation and failure remain safe.

The separate sync-departure vector traces exercise the actual core reducer. Native
binding/Host tests additionally exercise refresh, hidden time, selected severity,
actual placement, Close/Back and disposal. Kotlin coverage does not implement the
Web, Apple or desktop reducer/acknowledgement: those app cases remain owed.

### Native viewport, chooser and chip layout

All measurements here are logical dp unless explicitly labelled native pixels.
For example1280×720native at density2 is640×360dp;640×360native at that density is
320×180dp stress evidence. A156dpi TV has.975native px/dp. Capture receipts record
native dimensions, density, actual window dp and Configuration.fontScale/system
font scale. A600dp image alone cannot prove the full-bleed/centred600dp body rule
in a wider window. Large-font and real letter-IME evidence are separate requirements.

Touch creation keeps one mounted single-line editor/draft across width and IME
changes. At compact height, field and Create share a row only if the native action
leaves at least200dp for the field after normal side insets/gap; otherwise they
stack with a full-width field and action. Name label stays one line. Normal portrait
keeps its pinned Create. Empty choosers lead with creation. True640×360dp/font1.3
still owes visible initial content and actionable readable field/Create with IME.
The actual Dialog insets control its usable viewport. Focus reveal excludes the
painted16dp top/20dp bottom fades plus8dp breathing room (24/28dp), using the same
live fade predicates as drawing, and reruns when the IME resizes the focused field.
Visible targets do not move, oversized targets stay stable, and terminal padding
allows the focused outline to clear the fade. No empty pinned footer is drawn.

TV opts into opaque8dp bands at BOTH viewport clip edges inside the existing32/40dp
fades; other shared settings callers retain their defaults. A1dp native divider
separates the scrolled header only while backward scroll is possible, and separates
an existing joined OR chooser footer without changing safe inset geometry. Effective
reveal remains40/48dp. Transport has8dp vertical internal padding beside the4dp
body spacing, yielding12dp actual gaps above/below; horizontal gaps remain12dp.
Returning focus to the first useful body control restores scroll0 and the complete
group/title/status context. Other targets move minimally. Queue removal retains the
same next-at-index/previous-last neighbour (sole removal uses Wait); programmatic
focus/reveal waits for the authoritative next layout, not merely a frame timer.
Every focused row must clear the same effective fade boundaries after settle.

TV Repeat is entered on the current checked enabled chip from both Up and Down;
Left/Right within it remains free and focus alone sends no shared-setting action.
A LEFT/departure chooser with create permission initially focuses NAME even when
cached groups are already populated, without automatic IME. A valid NAME/Refresh
or deliberately moved focus survives asynchronous results. Fresh normal loaded
chooser still starts on first Join. Newly offered recovery/removed targets trigger
useful repair; unrelated flags do not. The single acknowledgement banner is pinned
above the stable editor outside scroll fades, with12dp context/banner/editor gaps.
Native Select→IME→typed→Go, Back-keeps-draft and Down→enabled Create remain required.

Both native surfaces use the authoritative queue count for local-fact visibility:
empty queues have no “This device” playback fact. The empty debug fixture reports
UNAVAILABLE independently of this visibility rule. Nonempty unavailable/preparing/
buffering/ready facts remain honest and do not infer other participants’ readiness.
Nothing-playing/title-unavailable placeholders are muted; actual titles remain
primary, and no raw IDs substitute for missing titles.

Chip phase and known quality are WHOLE phrases: share one baseline only when both
fit, otherwise put phase first and quality second with no orphan/leading dot. A
translated phrase that alone exceeds the measure may ellipsize on its own line;
it must not consume the other fact’s line. Phase-only Waiting/Reconnecting may use
two lines. UNKNOWN remains omitted, and no chip estimate is remote-output proof.
The natural chip width, native role, count/plural and original participant count
remain intact. Context reserves the full localized count first. Show a fitting
whole short group name; otherwise display an ORIGINAL prefix of at least SIX
COMPLETE Unicode graphemes plus ellipsis, or omit name AND separator. No invented
abbreviation or1–3letter stub. Measure the actual rendered prefix so equal8dp gaps
follow its visible box. On API23, unproven non-ASCII segmentation falls back to
count-only unless the whole name fits; no unsafe UTF-16/code-point slicing. API24+
uses extended grapheme boundaries. Native font1.3/280dp and long-locale evidence
must prove these rules; declarative vectors cannot prove actual text fit.

These selective shared UI changes do not alter offline, source/renderer eligibility,
clock, speed, readiness, transport protocol or connection policy. The same semantic
presentation and departure rules apply to all platforms; Web/Apple/desktop still owe
their native measurement/focus/IME/acknowledgement implementations and app evidence.


Cast consent is bound to BOTH the account/token epoch and a monotonically distinct
consent identity. A changed account, token or logout clears pending consent; an
identical account/token rebind preserves it. The rendered dialog captures that
identity for Confirm and Cancel. An old dialog callback cannot confirm or cancel
a newer consent, even within the same account. Accepted confirmation consumes the
pending identity before the existing Leave path, so repeat presses do nothing.
The eventual route callback checks the same account epoch and latest consent ID
again, in addition to the existing request/account/HTTP-success/nonparticipation
fences. A stale or cancelled consent sends no extra Leave, records no departure
and launches no route. These guards do not change valid Cast confirmation,
failed-Leave recovery, cancellation, offline behavior or group transport policy.
Native implementations and their portable consent traces must preserve this scope
and individual-identity contract; a prior consent never authorizes a later viewer.

The15 `sync-tv-surfaces` cases are native TV rendering/input profiles, separate from
network/timing vectors. Android executes their actual theme, font, draft, visibility
and paint/geometry expectations through SyncPlayTvPrimaryPaintRegressionTest and
SyncPlayTvRepeatPaintAndBackRegressionTest. Equivalent TV surfaces on other platforms
need native focus, keyboard and measurement adapters; corpus inventory alone does
not execute those UI contracts. Real floating-keyboard Back, font1.3, full-screen
paint and focus traversal still require the indexed native device/director evidence.

## Player intent, maintenance and compact touch presentation

Retain standalone Media3 idle/end/prepare and deferred-next behavior. While participating in an usable group with a nonempty authoritative queue, the primary player action follows the shared phase: Playing offers Pause; Paused and Waiting offer Play, matching the existing group panel. A locally held/buffering decoder does not change that shared action. Unusable membership or an empty group queue disables it. Resolve the same projection again at the event boundary and dispatch through shared transport interception; Pause retains immediate local user-intent feedback while waiting for a server command. Local output state and group phase remain distinct facts. JSON sync-player-intent-presentation has12 actual core cases and Android real-player adapter coverage. The TV screen, focused remote activation and hardware Play/Pause toggle use this same current group projection; explicit media Play/Pause retain their explicit intent. SyncTvPlayerIntentRegressionTest exercises four existing primary case objects through the actual TV screen, installed Sync binding and media session callback.

Audio-output format maintenance is not viewer speed selection. A callback may reapply native parameters only for its exact current decision reference, native generation and active native player; stale callbacks have no effect. Maintenance does not emit a group speed request or viewer notice. It preserves an active temporary PCM correction and encoded audio still forces native1×. Explicit viewer speed requests retain the existing grouped-policy handling; standalone selection and source-replacement restoration remain intact. JSON sync-speed-intent-origin has4 native adapter profiles plus lifetime/rate guards. The Speed context explains the shared1× restriction and disables selection while participating. An untriggered informational Speed card must not precede group identity and transport. Actionable errors/recovery remain prominent.

On compact touch group entry, the complete primary transport row is revealed within the actual usable viewport, including active fade and clearance, after its first placement. This initial reveal does not require keyboard/accessibility focus, does not repeat on clock/quality publications, and re-evaluates for membership or window/font geometry changes. An actionable recovery notice takes priority. JSON sync-phone-surfaces has7 actual native render/input/lifecycle profiles at real font1.0/1.3.

Phone player chrome uses a native gradient over its measured extent, exposing the image at its upper edge and darkening toward actions. On Android touch, Watch together is a48dp native icon action immediately before Quality in the scrollable title header. The shared centered native transport and role-specific options/overflow retain the current player layout; Watch together remains a secondary action. Elapsed/total time shares the seek row without reducing the slider or any action below its48dp touch target; scrolling keeps controls reachable when font/window constraints require it. Persistent adaptive-quality detail belongs to the existing Quality context. Keep its truthful fact reachable there. A newly changed reduction may show a finite visible, polite native message only in actual free space, yielding to conversion/recovery/subtitles and open sheets. Do not repeat an unchanged fact on every controls reveal, consume a pending fact while blocked, or replace a visible message with an invisible accessibility-only node. The final sync-phone-quality-cue lifecycle profiles execute the real native context and cue. Conversion/recovery notices retain their existing higher priority. For live/infinite streams this secondary action remains visible but disabled with a localized unavailable reason; Quality retains the content column’s right edge. JSON sync-phone-player-chrome has10 native paint/geometry/reachability and group-overlay priority profiles.

The5 sync-phone-quality-cue cases execute real Quality detail, changed-fact lifetime/no replay, null reset, conversion priority and mounted-sheet priority. Start the five-second cue lifetime on actual visible placement; an insufficient gap leaves the fact pending. Android source and renderer tests remain distinct from native device, screen-reader speech, other-platform and SYN-06 qualification.

The shared centered phone transport treats the live group status chip as a measured blocker. A changed-quality cue preserves at least8dp clearance from that transport, the status chip and its existing panel roles; available space above the transport may be used, otherwise the fact remains pending until it can actually be drawn. The cue lifetime begins after visible placement. `sync-phone-player-chrome` profile `native-grouped-overlay-geometry` (case `grouped-centered-transport-blockers-font-1-3`) is exercised by the Android real-host native geometry tests; other platforms owe the same priority and bounds checks.

The ten `sync-tv-structure-discovery` native profiles exercise personal/shared
section reading order, actual TV scroll-indicator paint and first-viewport
continuation at font scales1.0/1.3, privacy-to-floating-label glyph clearance,
empty/nonempty group-panel transport and request/focus transitions, and compact
touch footer geometry with a divider coupled to the actual body scroll state.
Touch-target checks measure the native input region independently of button paint.
Android executes the production Compose surfaces; other platforms owe equivalent
native adapters. An inventory entry or TODO is not a UI qualification.

On the touch joined-group panel, show a passive localized participant-count summary
next to the initial group phase, including when the queue is empty. Use the actual
participant-list length, preserve zero and plural forms, and update with the next
snapshot; do not invent the viewer, readiness or per-person state. The detailed
People section retains its existing order and names-only limitation. The count
is ordinary text, with no focus, action or announcement role.

The shared native destination chooser names the task from the actual captured
media request: a null request used to attach a remote or select a queue target
has the localized title “Choose a TV”; a real media request has “Play on”. Existing
direct destination-chooser callers retain “Play on”. Changing this presentation
does not select a receiver, start playback, alter consent, or change any request
identity, permission, cancellation or route callback. Other platforms owe the same
truthful task and participant-count presentation through their native adapters.


The phone group body exposes an explicit passive scroll-position cue whenever its
actual content overflows, including a padding-only tail where the bottom fade is
absent. The cue follows the measured scroll offset, reaches the viewport end and
disappears when content fits. It must not take focus, add an action, move transport,
change fade/reveal clearances or depend on which row happens to meet the fold.
`sync-phone-remote-chrome` has4 actual native phone profiles for this cue, structured
remote identity, status-first contained controller chrome and real scroll reachability
of the existing exit actions. `sync-tv-rhythm` has3 native TV profiles for pinned
preview clearance, painted setting separation and title hierarchy. Android executes
them in SyncPhoneRemoteChromeRegressionTest and SyncPlayTvRhythmRegressionTest;
other platforms owe corresponding native executors and captures.


### Touch player and remote search follow-up

When phone player controls are visible, reserve at least16dp between their whole
transport row and the group chip, notices and action cards wherever a sufficiently
large free band exists. In an adequate band between chip and bottom panel, keep at
least16dp clear of both and balance the free vertical space instead of attaching
the transport to a blocker edge. Preserve the64dp primary action,48dp secondary
targets,24dp gaps and safe-area constraints. If the available geometry cannot fit
those clearances, retain the existing constrained-space fallback and transport
availability; do not claim the impossible clearance. The separate
three-profile native layout/paint suite includes the640×360 joined-player case.

Phone overlay controls outside the bottom panel need picture backing over bright
video. While the controls are visible, a30–40% black whole-picture scrim sits after
the decoded picture and before subtitles and chrome. Hiding the controls removes
this scrim, while the group chip remains visible. The existing bottom gradient
continues to deepen the panel and preserve subtitle placement. Right-edge Quality
text aligns optically with the total clock within2dp; its target remains at least
48dp. Native white-picture paint and actual text-ink profiles check these outcomes,
without asserting device decoder output or physical contrast qualification.

Phone remote search keeps its current explanation or local validation message in
the native outlined field's supporting-text slot. Reserve the larger localized
wrap of those two messages so validation, sending and readiness changes do not
move retained controls. The full48dp Send action belongs in the field and remains
wholly discoverable in the initial360×640 phone viewport with the native system
insets; explanation and submission have no invisible-message gap larger than12dp.
Typing alone sends nothing. The explicit keyboard Send action and the visible Send
action share one guarded submission: require a current ready target and nonblank
text, retain the existing256-character single-line protocol validation, clear only
on accepted submission, and announce local rejection politely. Detaching removes
the search controls; a stale callback cannot send to the detached target. The
three `sync-phone-remote-search` profiles cover native layout, retained-control
stability and exactly-once guarded keyboard dispatch. These are UI/input rules;
they do not change REM-04 freshness or the remote wire protocol.


## TV group facts and player action row

The joined TV panel exposes the same passive localized authoritative participant count as touch, including an empty queue. Use the actual participant-list length, retain zero/singular/plural, update on server snapshots, and do not invent identity or readiness. Count publication sends no group request and does not move deliberate focus.

TV player actions form one non-wrapping horizontal row. All existing actions remain available through native D-pad navigation; focusing an action brings its complete native target into the viewport, and returning to the first action restores its visibility. Keep the current primary group intent and explicit Stop behavior, use the shared player action component for Watch together, and preserve panel opener/return ownership. Long labels, conditional Chapters and font1.3 do not create an orphan second row.

Use the shared48dp horizontal TV player safe edge for conversion notice, persistent group chip, title/timeline/action content and bottom-end segment/next actions. The picture and controls backing remain full-bleed; independent existing vertical spacing remains appropriate to each role. Actual native geometry, full targets, focus and paint require native tests and device evidence, rather than a vector inventory pass. Other TV-capable platforms owe equivalent source facts, action navigation and safe-area handling through their native APIs.

The shared player-control-affordances rules in player.md also apply during group playback: preserve readable ten-second seek intent and orientation marks, expose TV overflow without a playback request, and reveal complete direct-action labels through native focus.

Natural completion is distinct from an explicit Next request. With a current authoritative queue and matching unpaused renderer, an earlier Repeat Off entry requests NextItem; Repeat All also requests NextItem, including its last entry. Repeat One requests SetPlaylistItem for the current playlist entry to restart through the server. Never seek, rewind, or advance the local queue optimistically. At the final Repeat Off entry, finish local video/audio reporting and Leave once with the retained playback-ended departure; send neither NextItem nor group Stop. Video must validate the captured playback decision and queue its normal Stopped-before-Played completion before leaving or navigating away; rejected or already-completed decisions retain the current viewing without departure. A valid completion reports Played once even when navigation immediately closes the screen. Missing/recovering queue authority, a mismatched renderer/entry, or a paused seek-to-end cannot authorize continuation or terminal departure. Capture renderer token, native source, membership generation and playlist entry with the completion callback; a retired callback cannot finish a replacement viewing, even for identical media. Explicit Next retains its separate server-authoritative request and may be a no-op at the final entry. The 25 `sync-completion` vectors execute the shared natural-completion policy; Android host tests separately verify wire identity, departure, native intent and obsolete-callback fences. The two `sync-completion-reporting` adapter cases exercise current and obsolete playback decisions through the actual video binding/session, including ordered completion reports and local navigation; other platforms must run them through their native adapters.
