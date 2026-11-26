/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";
import {
    addressCandidates,
    authorization,
    sameOrigin,
    resolve,
    stripApiKey,
    resumeStartMs,
    ticksFromNanoseconds,
    progressReport,
    stopReport,
    assembleHome,
} from "./index";
import {
    decodeSettings,
    validateBitrate,
    validateMaxStreamingBitrate,
    defaultStream,
    audioTrackIndex,
    captionLanguage,
    captionIndex,
    userConfiguration,
    endPosition,
    activeSegment,
    nextTrigger,
    gridVerticalMove,
    nextCountdown,
    nextAdjacency,
    imageUrl,
    chapterImageUrl,
    trickplaySheetUrl,
    protocolDecision,
    decisionProjection,
    seekRequired,
    seekStart,
    seekUrl,
    audioEncodes,
    preserveVideo,
    urlProjection,
    ParityCatalogClient,
    playbackRequest,
    serverLabel,
    searchDuplicateLabels,
} from "./parity";
import { referenceProfile, referenceCompatibility } from "./reference-profile";
import * as rc26 from "./rc26";
import * as rc28 from "./rc28";
import * as playbackInformation from "./playback-information";
import * as playbackFormats from "./playback-formats";
import * as catalogReturn from "./catalog-return";
import { controlProjection, controlSuites, runControlCase } from "./control";
import { playerTrackLabels } from "../../src/player-track-labels";
import * as browse from "../../src/library-browse";
import { runSyncStayVector } from "./sync-stay";
const spec =
    process.env.PICTURESHOW_SPEC ??
    fileURLToPath(new URL("../../../spec", import.meta.url));
// Frozen by the Android reference and owed by this runner: visible as todos, never as passes.
const pendingPortSuites = new Set([
    // HOME-20..23 TV hero art, facts and BlurHash (Android leg 188): web port owed.
    "home-hero",
    "cast-queue",
    // SYN-01 create-with-item queues on GroupJoined (Apple/Android); web SyncPlay create owed.
    "sync-create-queue",
    // AUTH-10 retained-card sign-out: desktop/core implementation; web runtime port owed.
    "account-sign-out",
    // SEERR: Seerr requests are on Android first; web port owed.
    "seerr",
    // Search presentation (CAT-14..16 desktop contract); web search runs its own CAT-14a cases.
    "search-presentation",
    // CAT-30..32, PHOTO-01/02, PLAY-30, SAVER-01 detail discovery (Android leg 160): web port owed.
    "discovery",
    "frame-rate-match",
    // PLAY-60 player engine choice (Android leg 164): web has one engine; owed only if it gains a second.
    "playback-engine",
    "playback-start",
    "playback-settings",
    "player-quality",
    "quality-auto-floor",
    "playback-info-reasons",
    "settings-presentation",
    "playback-settings-layout",
    "music-catalog",
    "music-queue",
    "music-session",
    "music-reports",
    "music-quality",
    "music-playlists",
    // MUS-10 offline music (Android leg 169): web has no in-app offline library at launch (OFF-PD04).
    "music-offline",
    "remote-targets",
    "remote-wire",
    "remote-receive",
    "remote-reconnect",
    "remote-queue",
    "offline-job",
    "offline-budget",
    "offline-resume",
    "offline-manifest",
    "offline-outbox",
    "offline-plan",
    "offline-reconnect",
    "offline-next",
    // Web has no in-app downloads at launch (OFF-PD04); owed with OFF-14.
    "offline-detail-source",
    "offline-fmp4",
    "cast-handoff",
    "cast-capability",
    "cast-session",
    "grid-navigation",
    "sync-clock",
    "sync-presentation",
    "sync-application",
    "sync-drift",
    "sync-state",
    "sync-queue",
    "sync-completion",
    "sync-completion-reporting",
    "sync-source",
    // New Android reference adapters and native UI remain explicit Web port obligations.
    "sync-chip-name",
    "sync-departure",
    "sync-local-display",
    "sync-membership-registration",
    "sync-phone-player-chrome",
    "sync-phone-quality-cue",
    // Native remote identity/chrome and structural scroll-position UI remain owed.
    "sync-phone-remote-chrome",
    // Native search layout and deliberate keyboard-submit UI remain owed.
    "sync-phone-remote-search",
    "sync-phone-surfaces",
    "sync-player-intent-presentation",
    "sync-rate-presentation",
    "sync-route-consent",
    "sync-socket-readiness",
    "sync-speed-intent-origin",
    "sync-tv-surfaces",
    // Native TV geometry/typography profiles remain an explicit Web UI port obligation.
    "sync-tv-rhythm",
    "sync-tv-player-coherence",
    "sync-tv-attention",
    "sync-tv-structure-discovery",
    "sync-group-social-summary-and-picker-role",

    "live-guide",
    "live-dvr",
    "live-source",
    "live-presentation",
    "player-overlay-layout",
    // Native player paint/geometry and clearance adapter remain owed.
    "touch-player-director-clearance-paint-edges",
    // Native seek/orientation ink and TV overflow paint remain owed UI adapters.
    "player-control-affordances",
    // SUB-DL-01..04 remote subtitle search and stream adoption (Android leg 163): web port owed.
    "subtitle-download",
    // PLAY-11 subtitle timing wording and steps (core SubtitleTiming): web's player-subtitles.ts port of the runner owed.
    "subtitle-timing",
]);
// Desktop slice 1 added these shared application policies. Their web adapters/UI
// are still owed; list exact case IDs rather than accepting unknown operations.
// These remain visible todos, never execution passes.
// Android leg 160 catalog reads (CAT-30..32, PHOTO-01, SAVER-01): web client port owed.
// Exact case ID -> HTTP action, so a changed vector cannot hide behind a todo.
const pendingReferenceHttpCases = new Map([
    ["catalog-local-trailers", "catalog.localTrailers"],
    ["catalog-special-features", "catalog.specialFeatures"],
    ["catalog-similar", "catalog.similar"],
    ["catalog-screensaver-items", "catalog.screensaverItems"],
    ["catalog-parental-ratings", "catalog.parentalRatings"],
    ["catalog-photos", "catalog.photos"],
    // BROWSE-05 letter rail sort-name read (desktop leg 172): web client port owed.
    ["catalog-sort-names-letter-rail", "catalog.sortNames"],
    // MUS-01 music search groups (Android and Apple): web search port owed.
    ["search-albums", "search"],
    ["search-artists", "search"],
    ["search-songs", "search"],
    ["search-playlists", "search"],
]);
const pendingApplicationCases = new Map([
    // PLAY-17 finished line wording (Android leg android-live-rules): web shows the line; its core rule is owed.
    ["detail-finished-line-title", "detail-finished-line"],
    ["detail-finished-line-trimmed", "detail-finished-line"],
    ["detail-finished-line-blank", "detail-finished-line"],
    ["detail-finished-line-missing", "detail-finished-line"],
    ["detail-primary-next", "detail-play-item"],
    ["detail-primary-unplayed", "detail-play-item"],
    ["detail-primary-all-played", "detail-play-item"],
    ["detail-primary-empty", "detail-play-item"],
    ["detail-primary-container", "detail-play-item"],
    ["catalog-announcement-episode", "catalog-announcement"],
    ["catalog-announcement-season-count", "catalog-announcement"],
    ["catalog-announcement-empty", "catalog-announcement"],
    ["catalog-announcement-resume", "catalog-announcement"],
    // Desktop real-user round (D-15, P-7): sub-minute runtimes and library placeholder kinds.
    ["runtime-label-under-minute", "runtime-label"],
    ["runtime-label-tiny", "runtime-label"],
    ["runtime-label-just-under-minute", "runtime-label"],
    ["runtime-label-one-minute", "runtime-label"],
    ["runtime-label-short-clip-keeps-seconds", "runtime-label"],
    ["runtime-label-hours", "runtime-label"],
    ["runtime-label-implausible", "runtime-label"],
    ["placeholder-kind-movies", "catalog-placeholder-kind"],
    ["placeholder-kind-shows", "catalog-placeholder-kind"],
    ["placeholder-kind-livetv", "catalog-placeholder-kind"],
    ["placeholder-kind-unknown-library", "catalog-placeholder-kind"],
    ["placeholder-kind-boxset", "catalog-placeholder-kind"],
    ["placeholder-kind-title", "catalog-placeholder-kind"],
    // PLAY-03 last focused action on D-pad re-show (Android TV review T-3): web TV-mode port owed.
    ["controls-last-action-soon", "controls-restore-last-action"],
    ["controls-last-action-at-window", "controls-restore-last-action"],
    ["controls-last-action-too-late", "controls-restore-last-action"],
    ["controls-last-action-never-hidden", "controls-restore-last-action"],
    ["controls-last-action-clock-backwards", "controls-restore-last-action"],
    // TOUCH-11 Back and TOUCH-15 picture-in-picture (Android phone leg 162): web touch port owed.
    ["touch-back-sheet-first", "touch-player-back"],
    ["touch-back-menu-next", "touch-player-back"],
    ["touch-back-controls-showing-exits", "touch-player-back"],
    ["touch-pip-playing-video", "touch-pip-allowed"],
    ["touch-pip-never-on-tv", "touch-pip-allowed"],
    ["touch-pip-device-without-feature", "touch-pip-allowed"],
    ["touch-pip-no-video-yet", "touch-pip-allowed"],
    ["touch-pip-error-card", "touch-pip-allowed"],
    ["touch-pip-loading-next", "touch-pip-allowed"],
    ["touch-pip-leaving", "touch-pip-allowed"],
    ["touch-pip-finished", "touch-pip-allowed"],
    ["touch-pip-handed-off", "touch-pip-allowed"],
    ["touch-pip-actions-playing", "touch-pip-actions"],
    ["touch-pip-actions-paused", "touch-pip-actions"],
    ["touch-pip-actions-live-no-timeshift", "touch-pip-actions"],
    ["touch-pip-actions-live-at-edge", "touch-pip-actions"],
    ["touch-pip-aspect-1080p", "touch-pip-aspect"],
    ["touch-pip-aspect-unknown", "touch-pip-aspect"],
    ["touch-pip-aspect-scope-clamped", "touch-pip-aspect"],
    ["touch-pip-aspect-tall-clamped", "touch-pip-aspect"],
    ["touch-pip-aspect-anamorphic-dvd", "touch-pip-aspect"],
    ["touch-pip-aspect-4x3", "touch-pip-aspect"],
  ["quick-connect-code-pasted-with-spaces", "quick-connect-code"],
  ["quick-connect-code-too-short", "quick-connect-code"],
  ["quick-connect-code-too-long", "quick-connect-code"],
  ["quick-connect-code-punctuation-not-guessed", "quick-connect-code"],
  ["quick-connect-code-non-ascii-letter", "quick-connect-code"],
  ["quick-connect-approval-approved", "quick-connect-approval-result"],
  ["quick-connect-approval-server-said-no", "quick-connect-approval-result"],
  ["quick-connect-approval-unknown-code", "quick-connect-approval-result"],
  ["quick-connect-approval-token-refused", "quick-connect-approval-result"],
  ["quick-connect-approval-server-error", "quick-connect-approval-result"],
  ["quick-connect-approval-unreadable-body", "quick-connect-approval-result"],
    // PLAY-08 empty track menus (Android TV review T-10): web port owed.
    ["track-menu-empty-no-subtitles", "track-menu-empty"],
    ["track-menu-empty-no-audio", "track-menu-empty"],
    ["track-menu-empty-subtitles-present", "track-menu-empty"],
    ["track-menu-empty-audio-present", "track-menu-empty"],
    // Detail media badges and the series Play label (Android TV review T-11/T-13): web port owed.
    ["media-badges-uhd-hdr10-51", "media-badges"],
    ["media-badges-sdr-stereo-empty", "media-badges"],
    ["media-badges-scope-4k-dolby-vision-atmos", "media-badges"],
    ["media-badges-hdr10plus", "media-badges"],
    ["media-badges-hlg", "media-badges"],
    ["media-badges-invalid-dolby-vision-claims-nothing", "media-badges"],
    ["media-badges-default-audio-track-wins", "media-badges"],
    ["media-badges-older-server-profile-dtsx", "media-badges"],
    ["media-badges-spatial-format-none-overrides-profile", "media-badges"],
    ["media-badges-no-source", "media-badges"],
    ["detail-play-label-series-next", "detail-play-label"],
    ["detail-play-label-series-resume", "detail-play-label"],
    ["detail-play-label-season-untitled", "detail-play-label"],
    ["detail-play-label-movie", "detail-play-label"],
    ["detail-play-label-movie-resume", "detail-play-label"],
    ["detail-play-label-series-nothing-to-play", "detail-play-label"],
    ["detail-play-label-movie-finished", "detail-play-label"],
    ["detail-play-label-episode-finished", "detail-play-label"],
    ["detail-play-label-series-finished-names-episode", "detail-play-label"],
    ["profile-pin-valid-four-digits", "profile-pin-valid"],
    ["profile-pin-valid-eight-digits", "profile-pin-valid"],
    ["profile-pin-valid-too-short", "profile-pin-valid"],
    ["profile-pin-valid-too-long", "profile-pin-valid"],
    ["profile-pin-valid-letter", "profile-pin-valid"],
    ["profile-pin-valid-space", "profile-pin-valid"],
    ["profile-pin-valid-non-ascii-digits", "profile-pin-valid"],
    ["profile-pin-valid-empty", "profile-pin-valid"],
    ["profile-pin-needed-no-pin-switch", "profile-pin-needed"],
    ["profile-pin-needed-no-pin-start", "profile-pin-needed"],
    ["profile-pin-needed-switch-always-asks", "profile-pin-needed"],
    ["profile-pin-needed-switch-asks-even-if-unlocked", "profile-pin-needed"],
    ["profile-pin-needed-start-asks", "profile-pin-needed"],
    ["profile-pin-needed-start-off", "profile-pin-needed"],
    ["profile-pin-needed-start-already-unlocked", "profile-pin-needed"],
    ["profile-pin-attempt-first-wrong", "profile-pin-attempt"],
    ["profile-pin-attempt-fourth-wrong-no-wait", "profile-pin-attempt"],
    ["profile-pin-attempt-fifth-wrong-waits-30s", "profile-pin-attempt"],
    ["profile-pin-attempt-wait-ending-keeps-the-count", "profile-pin-attempt"],
    ["profile-pin-attempt-seventh-wrong-waits-5min", "profile-pin-attempt"],
    ["profile-pin-attempt-eighth-wrong-waits-15min", "profile-pin-attempt"],
    ["profile-pin-attempt-ninth-wrong-waits-1h", "profile-pin-attempt"],
    ["profile-pin-attempt-tenth-wrong-locks-out", "profile-pin-attempt"],
    ["profile-pin-attempt-locked-out-right-pin-refused", "profile-pin-attempt"],
    ["profile-pin-attempt-right-clears", "profile-pin-attempt"],
    ["profile-pin-attempt-during-wait-not-counted", "profile-pin-attempt"],
    ["profile-pin-attempt-restart-begins-the-wait-again", "profile-pin-attempt"],
    ["profile-pin-attempt-restart-wait-served-since-boot", "profile-pin-attempt"],
    ["profile-pin-relock-never-left", "profile-pin-relock"],
    ["profile-pin-relock-four-minutes-away-stays-open", "profile-pin-relock"],
    ["profile-pin-relock-just-under-five-minutes-stays-open", "profile-pin-relock"],
    ["profile-pin-relock-five-minutes-away-locks", "profile-pin-relock"],
    ["profile-pin-relock-an-hour-away-locks", "profile-pin-relock"],
    ["profile-pin-relock-restart-while-away-locks", "profile-pin-relock"],
    ["profile-pin-change-first-pin-is-free", "profile-pin-change"],
    ["profile-pin-change-open-viewer-is-not-enough", "profile-pin-change"],
    ["profile-pin-change-current-pin-typed", "profile-pin-change"],
    ["profile-pin-change-password-sign-in", "profile-pin-change"],
    ["profile-pin-away-visible", "profile-pin-away"],
    ["profile-pin-away-background", "profile-pin-away"],
    ["profile-pin-away-pip-playing", "profile-pin-away"],
    ["profile-pin-away-pip-paused", "profile-pin-away"],
    ["profile-pin-away-visible-pip-paused", "profile-pin-away"],
    ["profile-pin-proof-change-made", "profile-pin-proof"],
    ["profile-pin-proof-viewer-changed", "profile-pin-proof"],
    ["profile-pin-proof-relocked", "profile-pin-proof"],
    ["profile-pin-proof-settings-left", "profile-pin-proof"],
    ["profile-pin-proof-sign-in-landed", "profile-pin-proof"],
    ["profile-pin-proof-setup-cancelled", "profile-pin-proof"],
    ["access-refusal-sign-in", "access-refusal"],
    ["access-refusal-in-session", "access-refusal"],
    ["sync-group-list-loading", "sync-group-list"],
    ["sync-group-list-failed-is-not-empty", "sync-group-list"],
    ["sync-group-list-empty", "sync-group-list"],
    ["sync-group-list-groups", "sync-group-list"],
    ["sync-group-list-join-only", "sync-group-list"],
    ["sync-group-list-no-access", "sync-group-list"],
]);
const homeBrowse: Record<string, (i: any) => unknown> = {
    "library-filter-parameters": (i) => browse.filterParameters(i),
    "library-filter-remove": (i) => browse.withoutConstraint(i.filters, i.key),
    "filter-presentation": (i) => ({
        keys: browse.constraintKeys(i.filters),
        active: browse.constraintKeys(i.filters).length > 0,
        empty: browse.filteredEmptyLabel(i.collectionType),
        ...(i.remove
            ? {
                  remaining: browse.constraintKeys(
                      browse.withoutConstraint(i.filters, i.remove),
                  ),
              }
            : {}),
    }),
    "filter-constraint-label": (i) => browse.constraintLabel(i.filters, i.key),
    "resolution-summary": (i) => browse.resolutionSummary(i.resolution),
    "title-count-label": (i) => browse.titleCountLabel(i.count, i.exact ?? true),
    "sort-direction-label": (i) => browse.sortDirectionLabel(i.descending),
    "library-browse-scope": (i) => browse.libraryBrowseScope(i.collectionType),
    "letter-jump": (i) => browse.letterJump(i.sortNames, i.letter),
    "collection-play-scope": (i) =>
        browse.collectionPlayScope(i.type, i.collectionType),
    "queue-limit": () => browse.QUEUE_LIMIT,
};
const files = readdirSync(spec + "/vectors", { recursive: true })
    .filter((p) => String(p).endsWith(".json"))
    .map((p) => String(p));
const ids = new Set<string>(),
    foundControlSuites = new Set<string>();
// Full Media3 profile fixtures are evaluated as pure reference-policy translations.
// Runtime browser playback uses its own actual MIME/MSE/native capability probes.
// Passing these inputs is not a claim that browsers render Android formats.
for (const file of files) {
    const suite = JSON.parse(readFileSync(spec + "/vectors/" + file, "utf8"));
    if (
        suite.schemaVersion !== 1 ||
        ![
            "reference",
            "application",
            "recovery",
            "playback-information",
            "home-browse",
            "playback-formats",
            "catalog-return",
            ...controlSuites,
            ...pendingPortSuites,
            "sync-stay",
        ].includes(suite.suite) ||
        !Array.isArray(suite.cases)
    )
        throw new Error("Unsupported vector envelope: " + file);
    for (const c of suite.cases) {
        if (ids.has(c.id)) throw new Error("Duplicate vector ID: " + c.id);
        ids.add(c.id);
    }
    if (suite.suite === "sync-stay") {
        // SYN-08 stay-in-group policy: executed here, not owed.
        describe("sync-stay " + file, () => {
            for (const c of suite.cases)
                it(c.id, () =>
                    expect(runSyncStayVector(c)).toEqual(c.expected),
                );
        });
        continue;
    }
    if (pendingPortSuites.has(suite.suite)) {
        describe("owed " + file, () => {
            for (const c of suite.cases) it.todo(c.id);
        });
        continue;
    }
    if (suite.suite === "home-browse") {
        // Web runs the library browse rules it implements (BROWSE-01 to 06);
        // the home editor and pin rules stay visible todos until ported.
        describe("shared " + file, () => {
            for (const c of suite.cases) {
                const run = homeBrowse[c.operation];
                if (!run) it.todo(c.id + " (web home editor port owed)");
                else it(c.id, () => expect(run(c.input)).toEqual(c.expected));
            }
        });
        continue;
    }
    if (controlSuites.has(suite.suite)) {
        if (foundControlSuites.has(suite.suite))
            throw new Error("Duplicate control vector suite: " + suite.suite);
        foundControlSuites.add(suite.suite);
        describe("control " + file, () => {
            for (const c of suite.cases)
                it(c.id, () => {
                    const steps = runControlCase(suite.suite, c);
                    for (const step of steps)
                        expect(
                            controlProjection(step.actual, step.expected),
                        ).toEqual(step.expected);
                });
        });
        continue;
    }
    const browserVisibilityExpected: Record<string, boolean> = {
        "visibility-native-foreground": true,
        "visibility-native-stopped": true,
        "visibility-returned-latched": false,
        "visibility-paused-request": false,
        "visibility-web-background": true,
        "visibility-web-paused": false,
    };
    // Preserve every shared case verbatim. Also feed all six visibility inputs
    // our actual background policy, with explicit browser-policy expectations.
    for (const c of suite.cases.filter(
        (c: any) => c.operation === "visibility-play",
    ))
        it("browser background policy: " + c.id, () => {
            expect(browserVisibilityExpected).toHaveProperty(c.id);
            expect(
                rc28.visibilityPlay(
                    c.input.requested,
                    rc28.WEB_FOREGROUND_REQUIRED,
                    c.input.foreground,
                    c.input.pauseLatched,
                ),
            ).toBe(browserVisibilityExpected[c.id]);
        });
    const runnable = suite.cases;
    describe("shared " + file, () => {
        for (const c of runnable) {
            if (
                suite.suite === "application" &&
                pendingApplicationCases.has(c.id)
            ) {
                if (pendingApplicationCases.get(c.id) !== c.operation)
                    throw new Error(
                        "Changed owed application operation: " + c.id,
                    );
                it.todo(c.id + " (web application policy port owed)");
                continue;
            }
            if (
                suite.suite === "reference" &&
                pendingReferenceHttpCases.has(c.id)
            ) {
                if (pendingReferenceHttpCases.get(c.id) !== c.input?.action)
                    throw new Error("Changed owed HTTP action: " + c.id);
                it.todo(c.id + " (web catalog client port owed)");
                continue;
            }
            it(c.id, async () => {
                const i = c.input,
                    ctx = suite.context;
                let value: unknown;
                if (suite.suite === "catalog-return") {
                    if (c.operation === "refresh") expect(catalogReturn.catalogReturnRefresh(i.page)).toEqual(c.expected);
                    else if (c.operation === "progress") expect(catalogReturn.playbackReturnData(i.previous, i.positionTicks, i.ended)).toEqual(c.expected);
                    else throw new Error("Unknown catalog return operation");
                    return;
                }
                if (suite.suite === "playback-formats") {
                    switch (c.operation) {
                        case "range": value = playbackFormats.sourceRange(i.range, i.profile); break;
                        case "profile": value = playbackFormats.profile(i.profile); break;
                        case "decoder": value = playbackFormats.decoder(i.hwdec); break;
                        case "display": value = playbackFormats.display(i.transfer, i.output); break;
                        default: throw new Error("Unknown playback format operation");
                    }
                    expect(value).toEqual(c.expected);
                    return;
                }
                if (suite.suite === "playback-information") {
                    // One strict runner: unknown operations and malformed inputs throw.
                    expect(playbackInformation.runVector(c)).toEqual(
                        c.expected,
                    );
                    return;
                }
                switch (c.operation) {
                    case "audio-track-index":
                        value = audioTrackIndex(
                            i.streams,
                            i.selected,
                            i.serverDefault,
                        );
                        break;
                    case "track-labels":
                        value = playerTrackLabels(i.streams);
                        break;
                    case "visibility-play":
                        value = rc28.visibilityPlay(
                            i.requested,
                            i.foregroundRequired,
                            i.foreground,
                            i.pauseLatched,
                        );
                        break;
                    case "detail-retry-description":
                        value = rc28.detailRetryDescription(i.reason);
                        break;
                    case "detail-quiet-departing-change":
                        value = rc28.detailQuietDepartingChange(
                            i.handoff,
                            i.action,
                            i.onlyLabelChanged,
                        );
                        break;
                    case "detail-quiet-request-rediscovery":
                        value = rc28.detailQuietRequestRediscovery(
                            i.action,
                            i.onlyLabelChanged,
                            i.rediscovery,
                        );
                        break;
                    case "detail-request-once-feedback": {
                        const feedback = new rc28.DetailRequestOnceFeedback();
                        value = i.steps.map((step: any) =>
                            feedback.update(
                                step.request ?? null,
                                step.eligible,
                                step.submitted,
                            ),
                        );
                        break;
                    }
                    case "detail-held-label-presentation":
                        value = rc28.detailHeldLabelPresentation(
                            i.selectedWhileHeld,
                            i.heldLabelRequested,
                        );
                        break;
                    case "detail-feedback-source":
                        value = rc28.detailFeedbackSource(
                            i.currentPage,
                            i.selectedAction,
                        );
                        break;
                    case "detail-pending-presentation-label":
                        value = rc28.detailPendingPresentationLabel(
                            i.held,
                            i.requested,
                            i.available,
                            i.plain,
                            i.pending,
                        );
                        break;
                    case "detail-completion-presented-played":
                        value = rc28.detailCompletionPresentedPlayed(
                            i.played,
                            i.completionIntent,
                        );
                        break;
                    case "detail-played-request-notice":
                        value = rc28.detailPlayedRequestNotice(
                            i.wanted,
                            i.current,
                        );
                        break;
                    case "detail-reader-focus-clear":
                        value = rc28.detailReaderFocusClear(
                            i.currentPage,
                            i.ownedHeader,
                            i.sameNativeNode,
                        );
                        break;
                    case "detail-retry-failure-cue":
                        value = rc28.detailRetryFailureCue(
                            i.baseline ?? null,
                            i.attempt,
                            i.loading,
                            i.failed,
                            i.resumed,
                        );
                        break;
                    case "detail-retry-attempt-succeeded":
                        value = rc28.detailRetryAttemptSucceeded(
                            i.baseline ?? null,
                            i.attempt,
                            i.loading,
                            i.failed,
                        );
                        break;
                    case "detail-raw-read-acknowledgement":
                        value = rc28.detailRawReadAcknowledgement(
                            i.raw,
                            i.acknowledged,
                        );
                        break;
                    case "detail-pending-presentation-visible":
                        value = rc28.detailPendingPresentationVisible(
                            i.waiting,
                            i.resumed,
                            i.published,
                        );
                        break;
                    case "detail-completion-intent-retained":
                        value = rc28.detailCompletionIntentRetained(
                            i.reportFailed,
                            i.reportPending,
                            i.rawReadSucceeded,
                        );
                        break;
                    case "detail-retry-cue-can-wait":
                        value = rc28.detailRetryCueCanWait(
                            i.accessibilityEnabled,
                            i.elapsedMillis,
                        );
                        break;
                    case "detail-completion-may-apply":
                        value = rc28.detailCompletionMayApply(
                            i.reportChoiceRevision ?? null,
                            i.currentChoiceRevision,
                        );
                        break;
                    case "detail-pending-feedback":
                        value = rc28.detailPendingFeedback(i.steps);
                        break;
                    case "detail-pending-plain-label":
                        value = rc28.detailPendingPlainLabel(
                            i.target,
                            i.known,
                            i.played,
                            i.favorite,
                        );
                        break;
                    case "external-subtitle-loading":
                        value = {
                            retryDelayMs: rc26.externalSubtitleRetryDelay(
                                i.errorCount,
                            ),
                            sourceOffsetMs: rc26.externalSubtitleOffset(
                                i.fullTimeline,
                                i.serverStartMs,
                            ),
                        };
                        break;
                    case "subtitle-selection-local":
                        value = rc26.canSelectSubtitleLocally(
                            i.transcoding,
                            i.subtitle,
                            i.currentLocal,
                            i.nextLocal,
                        );
                        break;
                    case "report-track-index":
                        value = rc26.reportTrackIndex(
                            i.selectedIndex,
                            i.chosenByViewer,
                        );
                        break;
                    case "track-selection-unchanged":
                        value = rc26.unchangedTrack(
                            i.currentIndex,
                            i.pickedIndex,
                        );
                        break;
                    case "playback-report-fields":
                        value = rc26.playbackReportFields(
                            i.kind,
                            i.positionTicks,
                            i.audio,
                            i.subtitle,
                            i.audioViewer,
                            i.subtitleViewer,
                        );
                        break;
                    case "seek-step":
                        value = rc26.seekStepTarget(
                            i.positionMs,
                            i.pendingMs,
                            i.deltaMs,
                            i.durationMs,
                        );
                        break;
                    case "detail-empty":
                        value = rc26.showDetailEmptyState(
                            i.itemType,
                            i.loading,
                            i.readSucceeded,
                            i.readFailed,
                            i.episodeCount,
                            i.seasonCount,
                        );
                        break;
                    case "detail-action-state":
                        value = rc26.detailActionDescription(
                            i.currentState ?? null,
                            i.available,
                        );
                        break;
                    case "detail-held-label":
                        value = rc26.detailHeldActionLabel(i.target);
                        break;
                    case "detail-unavailable-label":
                        value = rc26.detailUnavailableActionLabel(i.target);
                        break;
                    case "detail-failure-key":
                        value = rc26.detailFailureKey(
                            i.readRecovery,
                            i.retrying,
                            i.writeAttempt ?? null,
                        );
                        break;
                    case "detail-failure-presentation": {
                        let previous: rc26.DetailFailurePresentation | null =
                            null;
                        value = i.steps.map((step: any) => {
                            previous = rc26.presentDetailFailure(
                                previous,
                                step.key ?? null,
                                step.message ?? null,
                            );
                            return previous?.message ?? null;
                        });
                        break;
                    }
                    case "detail-ready-cue":
                        value = rc26.detailReadyCue(i.label);
                        break;
                    case "detail-ready-feedback": {
                        const f = new rc26.DetailReadyFeedback();
                        value = i.steps.map((s: any) =>
                            f.update(
                                s.held,
                                s.focused ?? null,
                                s.resumed,
                                s.usable ?? true,
                                {
                                    presented: s.presented ?? false,
                                    cancelled: s.cancelled ?? false,
                                    submitted: s.submitted ?? true,
                                    nativePresented: s.nativePresented ?? null,
                                },
                            ),
                        );
                        break;
                    }
                    case "detail-read-lifecycle": {
                        const lifecycle = new rc26.DetailReadLifecycle();
                        value = i.resumes.map(() => lifecycle.resumed());
                        break;
                    }
                    case "detail-arrival-pending": {
                        let pending = true;
                        value = i.restored.map(
                            (restored: boolean) =>
                                (pending = rc26.detailArrivalStillPending(
                                    pending,
                                    restored,
                                )),
                        );
                        break;
                    }
                    case "detail-request-priority":
                        value = rc26.detailRequestFeedbackIsUrgent(
                            i.newlyAccepted,
                            i.requested,
                            i.available,
                        );
                        break;
                    case "catalog-arrival-animation":
                        value = rc26.catalogArrivalUsesAnimation(
                            i.accessibilityEnabled,
                            i.sourceIsPlayback,
                            i.destinationIsCatalog,
                            i.returning ?? false,
                        );
                        break;
                    case "detail-arrival-publication":
                        value = rc26.detailArrivalAwaitsAction(
                            i.loading,
                            i.contentRevealed,
                            i.returningFromPlayback,
                        );
                        break;
                    case "detail-metadata-handoff":
                        value = rc26.detailMetadataAwaitsHandoff(
                            i.hasActions,
                            i.handoffFinished,
                            i.returningFromPlayback,
                            i.failed,
                        );
                        break;
                    case "detail-retain-season-focus":
                        value = rc26.detailRetainsSeasonFocus(
                            i.selectedSection,
                            i.restored,
                            i.semanticsRevealed,
                            i.selectedId,
                            i.seasonIds,
                        );
                        break;
                    case "detail-host-focus-handoff":
                        value = rc26.detailSuppressesEmptyHostFocus(
                            i.ordinaryArrivalWithActions,
                            i.handoffFinished,
                            i.returningFromPlayback,
                            i.recoveryHandoff,
                            i.failed,
                        );
                        break;
                    case "detail-completion-read":
                        value = rc26.detailReadCanReplaceCompletion(
                            i.completionItem,
                            i.completionRead,
                            i.item,
                            i.read,
                            i.succeeded,
                        );
                        break;
                    case "detail-cold-pending":
                        value = rc26.detailNeedsPendingControls(
                            i.loading,
                            i.firstArrival,
                            i.canQueue,
                            i.returningFromPlayback ?? false,
                        );
                        break;
                    case "detail-requested-label":
                        value = rc26.detailRequestedActionLabel(
                            i.target,
                            i.newlyAccepted,
                        );
                        break;
                    case "catalog-refresh-revision":
                        value = rc26.catalogRevisionNeedsRefresh(
                            i.previous ?? null,
                            i.current,
                        );
                        break;
                    case "detail-read-band":
                        value = rc26.detailReadBandText(i.retrying, i.reason);
                        break;
                    case "detail-session-read":
                        value = rc26.detailSessionNeedsRead(
                            i.newRevision,
                            i.refreshed,
                            i.container,
                            i.alreadyConfirmed,
                        );
                        break;
                    case "detail-read-failure":
                        value = rc26.detailReadHasFailure(
                            i.readFailed,
                            i.reason ?? null,
                            i.mutationReady,
                        );
                        break;
                    case "detail-request-cue-active":
                        value = rc26.detailRequestCueRemainsActive(
                            i.active,
                            i.readerLeftAction,
                            i.requested ?? true,
                            i.presented ?? true,
                        );
                        break;
                    case "detail-confirmed-leaf":
                        value = rc26.detailHasConfirmedLeafRead(
                            i.readSucceeded,
                            i.sameUserData,
                            i.container,
                        );
                        break;
                    case "detail-read-dependencies":
                        value = rc26.detailReadRequiresDependents(i.type);
                        break;
                    case "homeRows":
                        value = assembleHome(
                            i.views,
                            i.resume,
                            i.nextUp,
                            i.latest,
                        ).map((r) => ({
                            key: r.key,
                            title: r.title,
                            items: r.items.map((x) => x.Id),
                            library: r.key === "libraries",
                            wide: ["resume", "nextup"].includes(r.key),
                        }));
                        break;
                    case "timelineThroughTrackSwitch": {
                        const decisions = i.responses.map((r: any) =>
                            protocolDecision(ctx, i.itemId, r),
                        );
                        value = {
                            initialSeekMs: [i.startMs, i.positionsMs[1]],
                            positionTicks: i.positionsMs.map(
                                (p: number, n: number) => {
                                    const d = decisions[n < 2 ? 0 : 1];
                                    if ("unplayable" in d)
                                        throw new Error(
                                            "Unplayable timeline fixture",
                                        );
                                    return progressReport(
                                        d as any,
                                        p / 1000,
                                        false,
                                    ).PositionTicks;
                                },
                            ),
                            sessions: decisions.map(
                                (d: any) => d.playSessionId,
                            ),
                        };
                        break;
                    }
                    case "profile":
                        value = referenceProfile(i.capabilities);
                        break;
                    case "compatibility":
                        value = referenceCompatibility(
                            referenceProfile(i.capabilities),
                            i.combo,
                        );
                        break;
                    case "request":
                        value = playbackRequest(
                            ctx.account.userId,
                            referenceProfile(i.capabilities),
                            i,
                            i.capabilities.settings?.maxStreamingBitrate ??
                                200000000,
                        );
                        break;
                    case "address":
                        try {
                            value = { urls: addressCandidates(i.value) };
                        } catch {
                            value = { error: "IllegalArgumentException" };
                        }
                        break;
                    case "authorization":
                        value = authorization(
                            i.deviceId ?? ctx.identity.deviceId,
                            i.token,
                            i.deviceName ?? ctx.identity.deviceName,
                            i.version ?? ctx.identity.version,
                        );
                        break;
                    case "sameOrigin":
                        value = sameOrigin(ctx.server.url, i.url);
                        break;
                    case "resolve":
                        value = resolve(ctx.server.url, i.url);
                        break;
                    case "stripApiKey":
                        value = stripApiKey(i.url);
                        break;
                    case "image":
                        value = imageUrl(
                            ctx.server.url,
                            i.item,
                            i.width,
                            i.height,
                            i.wide,
                        );
                        break;
                    case "chapterImage":
                        value = chapterImageUrl(
                            ctx.server.url,
                            i.itemId,
                            i.index,
                            i.tag,
                        );
                        break;
                    case "trickplaySheet":
                        value = trickplaySheetUrl(
                            ctx.server.url,
                            i.itemId,
                            i.mediaSourceId,
                            i.width,
                            i.sheet,
                        );
                        break;
                    case "decision":
                        value = decisionProjection(
                            protocolDecision(
                                ctx,
                                i.itemId,
                                i.response,
                                i.mediaSourceId,
                            ),
                        );
                        break;
                    case "ticks":
                        value = ticksFromNanoseconds(i.nanoseconds);
                        break;
                    case "reports": {
                        const d = {
                            itemId: "film",
                            source: { Id: "source", LiveStreamId: "live" },
                            method: i.method,
                            url: "",
                            playSessionId: "session",
                        };
                        value = {
                            progress: progressReport(
                                d,
                                i.positionMs / 1000,
                                i.paused,
                                i.audioStreamIndex,
                                i.subtitleStreamIndex,
                                i.canSeek,
                            ),
                            stopped: stopReport(
                                d,
                                i.positionMs / 1000,
                                i.failed,
                            ),
                        };
                        break;
                    }
                    case "validateBitrate":
                        try {
                            value = validateMaxStreamingBitrate(i.value);
                        } catch {
                            value = { error: "IllegalArgumentException" };
                        }
                        break;
                    case "server-label":
                        value = serverLabel(i.server, i.all);
                        break;
                    case "search-duplicate-labels":
                        value = searchDuplicateLabels(i.items);
                        break;
                    case "settings":
                        value = decodeSettings(i);
                        break;
                    case "validate-bitrate":
                        value = { valid: validateBitrate(i.bitrateMbps) };
                        break;
                    case "default-stream":
                        value = defaultStream(
                            i.streams,
                            i.type,
                            i.serverIndex,
                            i.preferredLanguage,
                        );
                        break;
                    case "caption-language":
                        value = captionLanguage(
                            i.enabled,
                            i.configuration,
                            i.tvLanguage,
                        );
                        break;
                    case "caption-index":
                        value = captionIndex(i.source, i.preferredLanguage);
                        break;
                    case "resume-start":
                        value = resumeStartMs(
                            i.ticks,
                            i.resume,
                            i.explicitPositionMs,
                        );
                        break;
                    case "user-configuration":
                        value = userConfiguration(i.fields, i.change);
                        break;
                    case "end-position":
                        value = endPosition(i.positionMs, i.durationMs);
                        break;
                    case "active-segment":
                        value =
                            activeSegment(i.segments, i.positionMs, i.modes)
                                ?.Id ?? null;
                        break;
                    case "next-trigger":
                        value = nextTrigger(i.durationMs, i.segments);
                        break;
                    case "grid-vertical-move":
                        value = gridVerticalMove(
                            i.index,
                            i.columns,
                            i.count,
                            i.down,
                            i.loadingMore ?? false,
                        );
                        break;
                    case "next-countdown":
                        value = nextCountdown(
                            i.wallSeconds,
                            i.creditsCountdown,
                            i.mediaLeftMs,
                            i.speed,
                            i.autoPlay,
                            i.ended,
                            i.suspended,
                        );
                        break;
                    case "next-adjacency":
                        value = nextAdjacency(i.item, i.items)?.Id ?? null;
                        break;
                    case "seek-required":
                        value = seekRequired(i.decision);
                        break;
                    case "seek-start":
                        value = seekStart(i.decision, i.targetMs);
                        break;
                    case "seek-url":
                        value = urlProjection(seekUrl(i.decision, i.startMs));
                        break;
                    case "audio-encodes":
                        value = audioEncodes(i.decision);
                        break;
                    case "preserve-video": {
                        const d = preserveVideo(
                            i.decision,
                            i.subtitleStreamIndex,
                        );
                        value =
                            "unplayable" in d
                                ? d
                                : {
                                      ...decisionProjection(d),
                                      url: urlProjection(d.url),
                                  };
                        break;
                    }
                    case "prepare-preserve-video": {
                        let sent: any;
                        const client = new ParityCatalogClient(
                            ctx,
                            async (url, init) => {
                                expect(String(url)).toBe(
                                    resolve(
                                        ctx.server.url,
                                        "Items/film/PlaybackInfo",
                                    ),
                                );
                                expect(init?.method).toBe("POST");
                                if (sent)
                                    throw new Error(
                                        "Unexpected duplicate prepare",
                                    );
                                sent = JSON.parse(String(init?.body));
                                return new Response(
                                    JSON.stringify(i.response),
                                    { status: 200 },
                                );
                            },
                        );
                        // The operation's documented projection excludes platform DeviceProfile.
                        // Use a native-adapter slot, never Android decoder declarations.
                        const outcome = await client.prepare(
                            "film",
                            {},
                            i.request,
                            ctx.capabilities.settings.maxStreamingBitrate,
                        );
                        for (const key of [
                            "DeviceProfile",
                            "EnableTranscoding",
                            "AutoOpenLiveStream",
                            "AlwaysBurnInSubtitleWhenTranscoding",
                        ])
                            delete sent[key];
                        value = {
                            request: sent,
                            outcome:
                                "unplayable" in outcome
                                    ? outcome
                                    : {
                                          ...decisionProjection(outcome),
                                          url: urlProjection(outcome.url),
                                      },
                        };
                        break;
                    }
                    case "http": {
                        const requests: any[] = [],
                            responses = [...i.responses];
                        const client = new ParityCatalogClient(
                            ctx,
                            async (url, init) => {
                                const u = new URL(String(url));
                                requests.push({
                                    method: init?.method ?? "GET",
                                    path: u.pathname,
                                    query: urlProjection(u.href).query,
                                    authorization: new Headers(
                                        init?.headers,
                                    ).get("Authorization"),
                                    body: init?.body
                                        ? JSON.parse(String(init.body))
                                        : null,
                                });
                                const response = responses.shift();
                                if (!response)
                                    throw new Error("Unexpected extra request");
                                return new Response(
                                    response.status === 204
                                        ? null
                                        : JSON.stringify(response.body),
                                    { status: response.status },
                                );
                            },
                        );
                        let result: any = { kind: "success" };
                        try {
                            let detail: any;
                            switch (i.action) {
                                case "prepare": {
                                    const outcome = await client.prepare(
                                        i.itemId,
                                        referenceProfile(i.capabilities),
                                        i,
                                        i.capabilities.settings
                                            ?.maxStreamingBitrate ?? 200000000,
                                    );
                                    result.outcome =
                                        decisionProjection(outcome);
                                    break;
                                }
                                case "catalog.views":
                                    await client.views();
                                    break;
                                case "catalog.resume":
                                    await client.resume();
                                    break;
                                case "catalog.nextUp":
                                    await client.nextUp(i.seriesId);
                                    break;
                                case "catalog.latest":
                                    await client.latest(i.parentId);
                                    break;
                                case "catalog.items":
                                    await client.items(i);
                                    break;
                                case "catalog.item":
                                    detail = await client.item(i.itemId);
                                    result.resumeTicks =
                                        detail.UserData
                                            ?.PlaybackPositionTicks ?? 0;
                                    break;
                                case "catalog.seasons":
                                    await client.seasons(i.seriesId);
                                    break;
                                case "catalog.episodes":
                                    await client.episodes(
                                        i.seriesId,
                                        i.seasonId,
                                    );
                                    break;
                                case "catalog.played":
                                    await client.played(i.itemId, i.value);
                                    break;
                                case "catalog.favorite":
                                    await client.favorite(i.itemId, i.value);
                                    break;
                                case "search":
                                    await client.search(i.kind, i.query);
                                    break;
                                case "personItems":
                                    await client.personItems(
                                        i.personId,
                                        i.startIndex,
                                        i.limit,
                                    );
                                    break;
                                case "configuration":
                                    result.configuration =
                                        await client.configuration();
                                    break;
                                case "saveConfiguration":
                                    await client.saveConfiguration(i.fields);
                                    break;
                                case "stopEncoding":
                                    await client.stopEncoding(i.playSessionId);
                                    break;
                                case "reportStart":
                                    await client.report("Start", i.body);
                                    break;
                                case "reportProgress":
                                    await client.report("Progress", i.body);
                                    break;
                                case "reportStopped":
                                    await client.report("Stopped", i.body);
                                    break;
                                default:
                                    throw new Error(
                                        "Unknown HTTP action " + i.action,
                                    );
                            }
                        } catch (e: any) {
                            if (!e.kind) throw e;
                            result = { kind: "failure", errorKind: e.kind };
                        }
                        value = { requests, result };
                        break;
                    }
                    default:
                        throw new Error(
                            "Unimplemented portable operation: " + c.operation,
                        );
                }
                expect(value).toEqual(c.expected);
            });
        }
    });
}
for (const suite of controlSuites)
    if (!foundControlSuites.has(suite))
        throw new Error("Missing control vector suite: " + suite);
