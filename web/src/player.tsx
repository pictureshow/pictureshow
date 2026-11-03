/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import {
    Jellyfin,
    ClientError,
    authenticatedMediaRequest,
    fetchMedia,
    localAddressSpace,
    encode,
    ticks,
    type Item,
    type Decision,
    type Stream,
    canDecodeVideo,
} from "@pictureshow/core";
import {
    BROWSER_DEVICE,
    BROWSER_NOTICE_ACTIONS,
    CHROMIUM_DECODE_STEPS,
    browserFamily,
    conversionKind,
    conversionNotice,
    conversionRemedies,
    conversionSummary,
    cueNearTop,
    formatBitrate,
    playbackMethod,
    speedSupported,
    subtitleDelivery,
    transcodeReason,
    transcodeReasons,
    type ConversionNotice,
    type NoticeInput,
} from "@pictureshow/core/playback-information";
import {
    visibilityPlay,
    WEB_FOREGROUND_REQUIRED,
} from "@pictureshow/core/rc28";
import "./player.css";
import { readCompletionDetail, invalidateCatalogAccount } from "./detail-reads";
import {
    beginCompletion,
    completionMayApply,
    finishCompletion,
} from "./completion-intents";
import {
    playbackReportFields,
    externalSubtitleRetryDelay,
} from "@pictureshow/core/rc26";
export { playerTrackLabels } from "./player-track-labels";
import { validSubtitleText } from "./player-subtitles";
import type { TrackMenus } from "./player-track-menus";

/** Dispose this player's MSE ownership without mutating live SourceBuffers. */
export function disposeHlsDecoder(
    hls: Pick<Hls, "stopLoad" | "transferMedia" | "destroy">,
    media: HTMLMediaElement,
    resetMedia = true,
) {
    const sourceChildren = Array.from(media.querySelectorAll("source"));
    const objectUrls = new Set(
        [media.getAttribute("src"), ...sourceChildren.map((s) => s.src)].filter(
            (url): url is string => !!url?.startsWith("blob:"),
        ),
    );
    hls.stopLoad();
    // Transfer detaches Hls listeners/append queues without abort/remove/EOS.
    // At unmount, DOM removal releases the complete native MSE graph without
    // resetting a never-played final frame inside React's removal transaction.
    hls.transferMedia();
    try {
        if (resetMedia) {
            // A replacement reuses this element, so release its old resource
            // before the next decoder attaches. Unmount needs no native reset.
            media.removeAttribute("src");
            for (const source of sourceChildren) source.remove();
            media.load();
        }
    } finally {
        try {
            hls.destroy();
        } finally {
            for (const url of objectUrls) URL.revokeObjectURL(url);
        }
    }
}

type SegmentType = "Intro" | "Recap" | "Preview" | "Commercial" | "Outro";
export type PlayerPreferences = {
    bitrateMbps?: number | null;
    subtitleSize?: "System" | "Small" | "Normal" | "Large" | "ExtraLarge";
    subtitleBackground?: "System" | "None" | "Shadow" | "Box";
    segments?: Partial<
        Record<SegmentType, "Ask" | "Auto" | "Automatic" | "Off">
    >;
    screenReader?: boolean;
    /** SET-11; missing means On. Read once when the player opens. */
    conversionNotice?: boolean;
};
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2] as const;
const rateLabel = (rate: number) => `${rate}×`;
/** Friendly name for a codec string the decoder actually received. */
function codecName(codec: string | undefined) {
    if (!codec) return "Not reported";
    const id = codec.toLowerCase();
    const name = id.startsWith("avc")
        ? "H.264"
        : id.startsWith("hvc") || id.startsWith("hev")
          ? "HEVC"
          : id.startsWith("av01")
            ? "AV1"
            : id.startsWith("vp09") || id === "vp9"
              ? "VP9"
              : id.startsWith("mp4a.40.34") || id === "mp3"
                ? "MP3"
                : id.startsWith("mp4a")
                  ? "AAC"
                  : id === "ac-3"
                    ? "AC-3"
                    : id === "ec-3"
                      ? "E-AC-3"
                      : id === "opus"
                        ? "Opus"
                        : id === "flac" || id === "fLaC".toLowerCase()
                          ? "FLAC"
                          : undefined;
    return name ? `${name} (${codec})` : codec;
}
const chromium = () =>
    typeof navigator !== "undefined" &&
    /(Chrome|Chromium|Edg)\//.test(navigator.userAgent) &&
    !/\bOPR\//.test(navigator.userAgent);
type Chapter = {
    StartPositionTicks?: number;
    Name?: string;
    ImageTag?: string;
};
type Segment = {
    Id?: string;
    Type: string;
    StartTicks: number;
    EndTicks: number;
};
type Trickplay = {
    Width: number;
    Height: number;
    TileWidth: number;
    TileHeight: number;
    ThumbnailCount: number;
    Interval: number;
};
type Extras = {
    Chapters?: Chapter[];
    Trickplay?: Record<string, Record<string, Trickplay>>;
};
type Request = {
    position: number;
    audio?: number;
    subtitle?: number;
    hls: boolean;
    revision: number;
    sourceId?: string;
};
type Viewing = {
    decision?: Decision;
    position: number;
    audio?: number;
    subtitle?: number;
    started: boolean;
    stopped: boolean;
    queue: Promise<unknown>;
};
type Panel = "Audio" | "Subtitles" | "Chapters" | "Seek" | "Speed" | "Info";
const panelTitle = (panel: Panel) =>
    panel === "Info" ? "Playback info" : panel;
const clock = (s: number) =>
    `${Math.floor(Math.max(0, s) / 60)}:${String(Math.floor(Math.max(0, s) % 60)).padStart(2, "0")}`;
const plausibleDuration = (n: number) =>
    Number.isFinite(n) && n > 0 && n <= 86400;
function usableSegment(s: Segment) {
    const max = ["Intro", "Recap", "Preview"].includes(s.Type)
        ? 600
        : ["Outro", "Commercial"].includes(s.Type)
          ? 1800
          : 0;
    return (
        s.StartTicks >= 0 &&
        s.EndTicks > s.StartTicks &&
        s.EndTicks <= 86400e7 &&
        (s.EndTicks - s.StartTicks) / 1e7 <= max
    );
}
function MediaImage({
    client,
    path,
    ...props
}: {
    client: Jellyfin;
    path: string;
    className?: string;
    style?: React.CSSProperties;
}) {
    const [url, setUrl] = useState("");
    useEffect(() => {
        let live = true,
            object = "";
        setUrl("");
        void fetchMedia(client.account!, path)
            .then((r) => r.blob())
            .then((blob) => {
                if (live) {
                    object = URL.createObjectURL(blob);
                    setUrl(object);
                }
            })
            .catch(() => {});
        return () => {
            live = false;
            if (object) URL.revokeObjectURL(object);
        };
    }, [path, client]);
    return url ? <img {...props} src={url} alt="" /> : null;
}
export function Player({
    client,
    item,
    start,
    onBack,
    onExpired,
    onNext,
    onLeaving,
    preferences = {},
}: {
    client: Jellyfin;
    item: Item;
    start: number;
    onBack: (reportError?: string) => void;
    onExpired: (e: unknown) => void;
    onNext?: (next: Item) => void;
    onLeaving?: (
        barrier: Promise<void>,
        retryReport?: () => Promise<void>,
    ) => void;
    preferences?: PlayerPreferences;
}) {
    const itemDuration = (item.RunTimeTicks ?? 0) / 1e7;
    const initialPosition = plausibleDuration(itemDuration)
        ? Math.min(Math.max(0, start), itemDuration)
        : Math.max(0, start);
    const video = useRef<HTMLVideoElement>(null),
        root = useRef<HTMLElement>(null),
        playButton = useRef<HTMLButtonElement>(null),
        positionButton = useRef<HTMLButtonElement>(null),
        panelElement = useRef<HTMLDialogElement>(null);
    const alive = useRef(true),
        leaving = useRef(false),
        replacing = useRef(true),
        // Background tabs may play. A real lifecycle stop is retained before any decoder exists.
        foreground = useRef(!document.hidden),
        stopGeneration = useRef(0),
        stopLatched = useRef(false),
        playAttempt = useRef(0),
        ownPauses = useRef(new WeakMap<HTMLMediaElement, number>()),
        // latchStop has already sent this stop's paused Progress; the decoder's
        // queued pause event must not report it again.
        latchPauseReported = useRef<HTMLMediaElement | undefined>(undefined),
        // When the decoder's periodic Progress last ran; a stop's own report resets it.
        periodicProgressAt = useRef(0),
        pauseIntent = useRef(false),
        completionDeferred = useRef(false),
        completionSaved = useRef(false),
        completionProjection = useRef<number | undefined>(undefined),
        completionHandedOff = useRef(false),
        ended = useRef(false),
        pausedAtPhysicalEnd = useRef(false),
        completing = useRef(false),
        retries = useRef(0),
        lastActivity = useRef(Date.now()),
        panelOpener = useRef<HTMLElement | null>(null),
        pendingSeek = useRef<number | undefined>(undefined),
        trackOrigins = useRef({ audio: false, subtitle: false }),
        subtitleSelection = useRef(0),
        reportSequence = useRef(0),
        trackCommands = useRef<Promise<void>>(Promise.resolve()),
        trackReady = useRef(new Set<() => void>()),
        playbackFailed = useRef(false),
        pendingPanel = useRef<
            { value: Panel; source: string | null } | undefined
        >(undefined),
        panelSource = useRef<string | undefined>(undefined),
        menuGeneration = useRef(0),
        menuSource = useRef<string | undefined>(undefined),
        [menus, setMenus] = useState<TrackMenus>(),
        previewTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
            undefined,
        ),
        cleanup = useRef<Promise<unknown>>(Promise.resolve());
    const viewing = useRef<Viewing>({
        position: initialPosition,
        started: false,
        stopped: false,
        queue: Promise.resolve(),
    });
    const initial: Request = { position: start, hls: false, revision: 0 };
    const desired = useRef<Request>(initial);
    const [request, setRequest] = useState(initial),
        [decision, setDecision] = useState<Decision>(),
        [error, setError] = useState(""),
        [reportError, setReportError] = useState(""),
        [completionError, setCompletionError] = useState(false),
        [notice, setNotice] = useState("Preparing playback…"),
        [paused, setPaused] = useState(false),
        [buffering, setBuffering] = useState(true),
        [position, setPosition] = useState(initialPosition),
        [duration, setDuration] = useState(
            plausibleDuration((item.RunTimeTicks ?? 0) / 1e7)
                ? item.RunTimeTicks! / 1e7
                : 0,
        ),
        [volume, setVolume] = useState(1),
        [controls, setControls] = useState(true),
        [panel, setPanel] = useState<Panel>(),
        [seekPreview, setSeekPreview] = useState<number>(),
        [subtitleDelay, setSubtitleDelay] = useState(0),
        [extras, setExtras] = useState<Extras>({}),
        [segments, setSegments] = useState<Segment[]>([]),
        [next, setNext] = useState<Item | null>(),
        [nextState, setNextState] = useState<"pending" | "ready" | "failed">(
            "pending",
        ),
        [cancelNext, setCancelNext] = useState(false),
        [countdown, setCountdown] = useState(10),
        [reader, setReader] = useState(!!preferences.screenReader),
        [orientation, setOrientation] = useState(
            window.innerWidth > window.innerHeight,
        );
    const delayRef = useRef(0),
        trackCueSets = useRef(new WeakMap<TextTrack, Set<TextTrackCue>>()),
        cues = useRef(
            new WeakMap<
                TextTrackCue,
                {
                    start: number;
                    end: number;
                    line?: number | "auto";
                    snap?: boolean;
                }
            >(),
        ),
        skipUsed = useRef(new Set<string>()),
        nextPromise = useRef<Promise<Item | null> | undefined>(undefined),
        nextRetry = useRef(false),
        stopError = useRef(""),
        stopStages = useRef({ reported: false, encodingStopped: false }),
        safeCaptionLine = useRef<number | null>(null),
        completionNext = useRef<Item | null | undefined>(undefined),
        orientationOwned = useRef(false),
        stopSnapshot = useRef<
            | {
                  decision: Decision;
                  position: number;
                  audio?: number;
                  subtitle?: number;
                  failed: boolean;
                  muted: boolean;
                  origins: { audio: boolean; subtitle: boolean };
              }
            | undefined
        >(undefined);
    const activeSource = decision?.source ?? viewing.current.decision?.source;
    const streams = activeSource?.MediaStreams ?? [];
    const audioIndex =
        viewing.current.audio ??
        request.audio ??
        activeSource?.DefaultAudioStreamIndex ??
        streams.find((s) => s.Type === "Audio")?.Index ??
        -1;
    const subtitleIndex =
        viewing.current.subtitle ??
        request.subtitle ??
        activeSource?.DefaultSubtitleStreamIndex ??
        -1;
    const selectedSubtitle = streams.find(
        (s) => s.Type === "Subtitle" && s.Index === subtitleIndex,
    );
    const isText = (s?: Stream) =>
        !!s &&
        (s.IsTextSubtitleStream ||
            [
                "srt",
                "vtt",
                "subrip",
                "mov_text",
                "webvtt",
                "ass",
                "ssa",
                "ttml",
                "tx3g",
                "text",
                "smi",
                "sami",
            ].includes(s.Codec?.toLowerCase() ?? ""));
    const localText = (s?: Stream) =>
        isText(s) &&
        !["Encode", "Embed", "Hls", "Drop"].includes(
            (s as Stream & { DeliveryMethod?: string })?.DeliveryMethod ?? "",
        );
    const textSubtitle = localText(selectedSubtitle);
    const burned = subtitleIndex >= 0 && !textSubtitle;
    const [selectionRevision, setSelectionRevision] = useState(0);
    // PLAY-25/26/27 state belongs to this viewing (the player is keyed by item).
    const noticeEnabled = useRef(
        preferences.conversionNotice !== false,
    ).current;
    const [speed, setSpeed] = useState(1),
        [speedMessage, setSpeedMessage] = useState(""),
        // A speed reset the viewer did not ask for, shown in the status line too.
        [speedNote, setSpeedNote] = useState(""),
        [playbackReady, setPlaybackReady] = useState(false),
        [received, setReceived] = useState<{ video?: string; audio?: string }>(
            {},
        ),
        [videoSize, setVideoSize] = useState(""),
        [noticePhase, setNoticePhase] = useState<"waiting" | "active" | "done">(
            "waiting",
        ),
        [shownNotice, setShownNotice] = useState<ConversionNotice>(),
        [announcement, setAnnouncement] = useState(""),
        // The notice's action line follows the input in use: coarse pointers start on touch.
        [noticeInput, setNoticeInput] = useState<NoticeInput>(() =>
            typeof matchMedia === "function" &&
            matchMedia("(pointer: coarse)").matches
                ? "touch"
                : "mouse",
        ),
        [topCue, setTopCue] = useState(false),
        [noticeTop, setNoticeTop] = useState<number>();
    const speedRef = useRef(1),
        speedNoteTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
            undefined,
        ),
        noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
            undefined,
        );
    /** Ask the element for a rate and report whether it is really active. */
    function applyRate(el: HTMLVideoElement, rate: number) {
        try {
            el.preservesPitch = true;
            el.defaultPlaybackRate = rate;
            el.playbackRate = rate;
        } catch {}
        return el.playbackRate === rate;
    }
    function chooseSpeed(rate: number) {
        const el = video.current;
        if (!el || !speedSupported(rate, false)) {
            setSpeedMessage(
                `${rateLabel(rate)} is not available for this video. Speed stays at ${rateLabel(speedRef.current)}.`,
            );
            return;
        }
        if (applyRate(el, rate)) {
            speedRef.current = rate;
            setSpeed(rate);
            setSpeedMessage("");
            setSpeedNote("");
            closePanel();
        } else {
            applyRate(el, speedRef.current);
            setSpeedMessage(
                `This browser could not play this video at ${rateLabel(rate)}. Speed stays at ${rateLabel(speedRef.current)}.`,
            );
        }
    }
    /** A new or replaced decoder keeps the viewing's speed, or says why it cannot. */
    function decoderReady(el: HTMLVideoElement) {
        if (!applyRate(el, speedRef.current)) {
            const previous = speedRef.current;
            speedRef.current = 1;
            applyRate(el, 1);
            setSpeed(1);
            if (previous !== 1) {
                // Shown in the player's status line as well as the Speed panel, so a
                // viewer who never reopens Speed still learns why the rate changed.
                const message = `Speed returned to 1× because this stream could not keep ${rateLabel(previous)}. Choose Speed to try again.`;
                setSpeedMessage(message);
                setSpeedNote(message);
                clearTimeout(speedNoteTimer.current);
                speedNoteTimer.current = setTimeout(() => {
                    if (alive.current) setSpeedNote("");
                }, 8000);
            }
        }
        setVideoSize(el.videoWidth ? `${el.videoWidth}×${el.videoHeight}` : "");
        setPlaybackReady(true);
    }
    async function sendReport(
        d: Decision,
        event: "start" | "progress" | "stop",
        at: number,
        pausedState: boolean,
        audio: number | undefined,
        subtitle: number | undefined,
        origins: { audio: boolean; subtitle: boolean },
        failed = false,
        keepalive = false,
        muted = false,
    ) {
        const sequence = ++reportSequence.current;
        const fields = playbackReportFields(
            event,
            ticks(at),
            audio ?? null,
            subtitle ?? null,
            origins.audio,
            origins.subtitle,
        );
        // The outcome comes from the actual attempt; never serialize URLs, titles, tokens or exceptions.
        await client.report(
            d,
            event,
            at,
            pausedState,
            audio,
            subtitle,
            failed,
            keepalive,
            muted,
            (outcome) => {
                console.info(
                    `PictureshowPlayback report item=${d.itemId} session=${d.playSessionId ?? "none"} seq=${sequence} ${fields} status=${outcome.status ?? "none"} failure=${outcome.failure?.toLowerCase() ?? "none"} elapsedMs=${Math.max(0, Math.floor(outcome.elapsedMs))} paused=${event === "stop" ? "omitted" : pausedState}`,
                );
            },
        );
    }
    const report = (event: "start" | "progress") => {
        const s = viewing.current;
        if (!s.decision || s.stopped || (event === "progress" && !s.started))
            return;
        s.queue = s.queue
            .catch(() => {})
            .then(async () => {
                const d = s.decision!;
                const origins = { ...trackOrigins.current };
                await sendReport(
                    d,
                    event,
                    s.position,
                    stopLatched.current || pauseIntent.current,
                    origins.audio ? s.audio : undefined,
                    origins.subtitle ? s.subtitle : undefined,
                    origins,
                    false,
                    false,
                    video.current?.muted ?? false,
                );
            })
            .then(() => {
                if (alive.current) setReportError("");
            })
            .catch((e) => {
                if (alive.current)
                    setReportError(
                        "Playback reports are not reaching the server.",
                    );
                if (e instanceof ClientError && e.kind === "EXPIRED")
                    onExpired(e);
            });
    };
    const sample = () => {
        const el = video.current,
            s = viewing.current,
            d = s.decision;
        if (
            !el ||
            !d ||
            replacing.current ||
            pendingSeek.current !== undefined ||
            (pausedAtPhysicalEnd.current && pauseIntent.current)
        )
            return pendingSeek.current ?? s.position;
        // Browser HLS masters and direct media both expose the full item clock.
        const p = Number.isFinite(el.currentTime) ? el.currentTime : 0;
        s.position = Math.max(0, p);
        if (alive.current) setPosition(s.position);
        return s.position;
    };
    async function stop(failed = false, keepalive = false) {
        pendingPanel.current = undefined;
        if (alive.current) setPanel(undefined);
        releaseTrackCommands();
        const s = viewing.current;
        if (s.stopped) return s.queue;
        sample();
        s.stopped = true;
        const d = s.decision;
        stopStages.current = { reported: !s.started, encodingStopped: !d };
        if (d)
            stopSnapshot.current = {
                decision: d,
                position: s.position,
                audio: s.audio,
                subtitle: s.subtitle,
                failed,
                muted: video.current?.muted ?? false,
                origins: { ...trackOrigins.current },
            };
        const snapshot = stopSnapshot.current;
        s.queue = s.queue
            .catch(() => {})
            .then(async () => {
                try {
                    if (s.started && snapshot) {
                        await sendReport(
                            snapshot.decision,
                            "stop",
                            snapshot.position,
                            true,
                            snapshot.audio,
                            snapshot.subtitle,
                            snapshot.origins,
                            snapshot.failed,
                            keepalive,
                            snapshot.muted,
                        );
                        stopStages.current.reported = true;
                        invalidateCatalogAccount(client);
                    }
                } finally {
                    if (d && !keepalive) {
                        await client.stopEncoding(d);
                        stopStages.current.encodingStopped = true;
                    }
                }
            })
            .catch(() => {
                stopError.current = stopStages.current.reported
                    ? "Playback stopped, but server cleanup did not finish. Retry cleanup before another viewing."
                    : "Playback progress could not be saved. Retry saving progress to continue.";
                if (alive.current) setReportError(stopError.current);
            });
        return s.queue;
    }
    async function retryFinalReport() {
        const snapshot = stopSnapshot.current;
        if (!snapshot || !stopError.current) return;
        // An acknowledged Stop is never resent merely because ActiveEncodings cleanup failed.
        if (!stopStages.current.reported) {
            await sendReport(
                snapshot.decision,
                "stop",
                snapshot.position,
                true,
                snapshot.audio,
                snapshot.subtitle,
                snapshot.origins,
                snapshot.failed,
                false,
                snapshot.muted,
            );
            stopStages.current.reported = true;
            invalidateCatalogAccount(client);
        }
        if (!stopStages.current.encodingStopped) {
            await client.stopEncoding(snapshot.decision);
            stopStages.current.encodingStopped = true;
        }
        stopError.current = "";
    }
    async function leave(failed = false) {
        if (leaving.current) return;
        leaving.current = true;
        if (completionProjection.current !== undefined) {
            finishCompletion(client, item.Id, completionProjection.current);
            completionProjection.current = undefined;
        }
        releaseTrackCommands();
        pendingPanel.current = undefined;
        setPanel(undefined);
        pauseIntent.current = true;
        sample();
        pauseDecoder();
        const barrier = stop(failed).then(() => {
            if (stopError.current) throw new Error(stopError.current);
        });
        if (onLeaving) {
            onLeaving(barrier, retryFinalReport);
            return;
        }
        await barrier.catch(() => {});
        onBack(stopError.current || undefined);
    }
    function reveal(focus = true) {
        setControls(true);
        lastActivity.current = Date.now();
        if (focus) requestAnimationFrame(() => playButton.current?.focus());
    }
    function hide() {
        pendingPanel.current = undefined;
        setPanel(undefined);
        setControls(false);
        setNotice("Controls hidden");
        setTimeout(() => {
            if (alive.current)
                setNotice((value) =>
                    value === "Controls hidden" ? "" : value,
                );
        }, 1800);
        video.current?.focus();
    }
    function back() {
        if (pendingPanel.current) {
            pendingPanel.current = undefined;
            setNotice("");
            return;
        }
        if (completing.current) {
            void leave();
            return;
        }
        if (panel) {
            closePanel();
            return;
        }
        if (controls && !error) {
            hide();
            return;
        }
        void leave(!!error);
    }
    function openPanel(value: Panel, opener?: HTMLElement) {
        panelOpener.current = opener ?? (document.activeElement as HTMLElement);
        if (value === "Audio" || value === "Subtitles") {
            pendingPanel.current = {
                value,
                source: menuSource.current ?? null,
            };
            if (!decision || !menus || menus.source !== menuSource.current) {
                setNotice(`Preparing ${value.toLowerCase()} choices…`);
                return;
            }
            pendingPanel.current = undefined;
            panelSource.current = menus.source;
        }
        setPanel(value);
        setControls(true);
        lastActivity.current = Date.now();
        if (value === "Seek") setSeekPreview(viewing.current.position);
    }
    function closePanel() {
        pendingPanel.current = undefined;
        const opener = panelOpener.current;
        panelElement.current?.close();
        setPanel(undefined);
        setSeekPreview(undefined);
        opener?.focus();
        requestAnimationFrame(() => {
            // Restoration must not overwrite a deliberate move made after dismissing the sheet.
            if (
                !panelElement.current?.open &&
                (document.activeElement === document.body ||
                    panelElement.current?.contains(document.activeElement))
            )
                opener?.focus();
            if (
                document.activeElement === opener &&
                opener === positionButton.current
            )
                setNotice(`Position ${clock(viewing.current.position)}`);
        });
    }
    function change(
        audio: number | undefined,
        subtitle: number | undefined,
        at = viewing.current.position,
        hls = desired.current.hls,
    ) {
        if (leaving.current) return;
        desired.current = {
            position: Math.max(0, at),
            audio,
            subtitle,
            hls,
            revision: desired.current.revision + 1,
            sourceId: viewing.current.decision?.source.Id,
        };
        setPosition(desired.current.position);
        if (replacing.current) return;
        replacing.current = true;
        sample();
        pauseDecoder();
        setRequest({ ...desired.current });
    }
    function seek(to: number) {
        if (completing.current || leaving.current) return;
        const at = Math.max(0, duration ? Math.min(duration, to) : to);
        pendingSeek.current = at;
        viewing.current.position = at;
        setPosition(at);
        pausedAtPhysicalEnd.current = false;
        if (at !== to) setNotice(at === 0 ? "At the beginning" : "At the end");
        setSeekPreview(at);
        clearTimeout(previewTimer.current);
        previewTimer.current = setTimeout(
            () => setSeekPreview(undefined),
            2000,
        );
        if (replacing.current) {
            pendingSeek.current = at;
            desired.current = {
                ...desired.current,
                position: at,
                revision: desired.current.revision + 1,
            };
            setPosition(at);
            return;
        }
        if (viewing.current.decision?.method === "Transcode")
            change(desired.current.audio, desired.current.subtitle, at);
        else if (video.current) {
            video.current.currentTime = at;
            viewing.current.position = at;
            setPosition(at);
            report("progress");
        }
    }
    function seekStep(delta: number) {
        seek((pendingSeek.current ?? viewing.current.position) + delta);
    }
    function releaseTrackCommands() {
        for (const resolve of trackReady.current) resolve();
        trackReady.current.clear();
    }
    function pickTrack(type: "Audio" | "Subtitles", index: number) {
        const source = panelSource.current;
        closePanel();
        // A later pick compares after the preceding preparation, not the menu's old selection.
        trackCommands.current = trackCommands.current
            .catch(() => {})
            .then(() => applyTrackPick(type, index, source));
    }
    function applyTrackPick(
        type: "Audio" | "Subtitles",
        index: number,
        source: string | undefined,
    ): void | Promise<void> {
        const d = viewing.current.decision;
        if (
            !d ||
            leaving.current ||
            playbackFailed.current ||
            (source &&
                source !==
                    `${d.source.Id}:${JSON.stringify(d.source.MediaStreams ?? [])}`)
        )
            return;
        const audio = type === "Audio";
        const currentStreams = d.source.MediaStreams ?? [];
        trackOrigins.current[audio ? "audio" : "subtitle"] = true;
        const current = audio
            ? video.current?.muted
                ? -1
                : (viewing.current.audio ?? -1)
            : (viewing.current.subtitle ?? -1);
        if (!audio) {
            const selection = ++subtitleSelection.current;
            console.info(
                `PictureshowPlayback subtitle-select item=${item.Id} session=${d.playSessionId ?? "none"} stream=${index < 0 ? "off" : index} paused=${pauseIntent.current} positionMs=${Math.floor(viewing.current.position * 1000)} selection=${selection}`,
            );
        }
        if (current === index) {
            if (!replacing.current) report("progress");
            return;
        }
        if (audio && index === -1) {
            if (video.current) video.current.muted = true;
            viewing.current.audio = -1;
            desired.current.audio = -1;
            desired.current.sourceId = d.source.Id;
            report("progress");
            setNotice("Audio off");
        } else if (
            !audio &&
            (current < 0 ||
                localText(
                    currentStreams.find(
                        (s) => s.Type === "Subtitle" && s.Index === current,
                    ),
                )) &&
            (index < 0 ||
                localText(
                    currentStreams.find(
                        (s) => s.Type === "Subtitle" && s.Index === index,
                    ),
                ))
        ) {
            viewing.current.subtitle = index;
            desired.current.subtitle = index;
            desired.current.sourceId = d.source.Id;
            setSelectionRevision((v) => v + 1);
            if (!replacing.current) report("progress");
        } else {
            if (audio && video.current) video.current.muted = false;
            change(
                audio ? index : desired.current.audio,
                audio ? desired.current.subtitle : index,
            );
            return new Promise<void>((resolve) =>
                trackReady.current.add(resolve),
            );
        }
    }
    function toggle() {
        if (completing.current || leaving.current) return;
        // Background Media Session commands work, but only an active foreground
        // Play can clear a stop imposed by page lifecycle or the browser/OS.
        if (stopLatched.current) {
            if (!foreground.current || document.hidden) return;
            stopLatched.current = false;
        }
        if (completionDeferred.current) {
            completionDeferred.current = false;
            void complete(completionNext.current);
            return;
        }
        pauseIntent.current = !pauseIntent.current;
        setPaused(pauseIntent.current);
        lastActivity.current = Date.now();
        setControls(true);
        const el = video.current;
        if (!el || replacing.current) return;
        if (!pauseIntent.current && (el.ended || pausedAtPhysicalEnd.current)) {
            void physicalEndRef.current();
            return;
        }
        if (pauseIntent.current) pauseDecoder(el);
        else
            playMedia(el, () => {
                if (document.hidden) latchStop("browser-play-rejected");
                pauseIntent.current = true;
                setPaused(true);
                setNotice("Press Play to start playback.");
            });
    }
    useEffect(() => {
        const e = () => back();
        window.addEventListener("pictureshow-stop", e);
        return () => window.removeEventListener("pictureshow-stop", e);
    }, [controls, panel, error]);
    useEffect(() => {
        const changed = () =>
            setOrientation(window.innerWidth > window.innerHeight);
        window.addEventListener("resize", changed);
        return () => {
            window.removeEventListener("resize", changed);
            if (orientationOwned.current) screen.orientation?.unlock?.();
        };
    }, []);
    useEffect(() => {
        alive.current = true;
        return () => {
            alive.current = false;
            if (
                !completionHandedOff.current &&
                completionProjection.current !== undefined
            ) {
                leaving.current = true;
                finishCompletion(client, item.Id, completionProjection.current);
                completionProjection.current = undefined;
            }
            void stop();
        };
    }, []);
    useEffect(() => {
        const t = setInterval(() => {
            if (
                !pauseIntent.current &&
                !panel &&
                !reader &&
                !buffering &&
                Date.now() - lastActivity.current >= 10000
            ) {
                const active = document.activeElement;
                if (active === video.current || active === document.body) {
                    setControls(false);
                    video.current?.focus();
                }
            }
        }, 500);
        return () => clearInterval(t);
    }, [panel, reader, buffering]);
    useEffect(() => {
        if (preferences.screenReader) setReader(true);
    }, [preferences.screenReader]);
    function backgroundPlayAllowed() {
        return visibilityPlay(
            true,
            WEB_FOREGROUND_REQUIRED,
            foreground.current && !document.hidden,
            stopLatched.current,
        );
    }
    function playMedia(el: HTMLMediaElement, rejected: () => void) {
        const attempt = ++playAttempt.current,
            revision = desired.current.revision,
            generation = stopGeneration.current;
        void el.play().catch(() => {
            // A decoder's late rejection cannot stop its successor, or undo a
            // newer deliberate command / lifecycle stop and foreground Play.
            if (
                !alive.current ||
                leaving.current ||
                video.current !== el ||
                desired.current.revision !== revision ||
                attempt !== playAttempt.current ||
                generation !== stopGeneration.current ||
                pauseIntent.current
            )
                return;
            rejected();
        });
    }
    function pauseDecoder(el = video.current) {
        if (!el || el.paused) return;
        // pause events are queued. Account for our own decoder swaps and holds
        // so a later pause event is not mistaken for a browser background stop.
        ownPauses.current.set(el, (ownPauses.current.get(el) ?? 0) + 1);
        el.pause();
    }
    function latchStop(reason: string) {
        foreground.current = false;
        stopGeneration.current++;
        stopLatched.current = true;
        pendingPanel.current = undefined;
        setPanel(undefined);
        pauseIntent.current = true;
        setPaused(true);
        setControls(true);
        sample();
        pauseDecoder();
        report("progress");
        periodicProgressAt.current = Date.now();
        latchPauseReported.current = video.current ?? undefined;
        console.info(
            `PictureshowPlayback lifecycle stop=${reason} paused=true positionMs=${Math.floor(viewing.current.position * 1000)}`,
        );
    }
    useEffect(() => {
        const visibility = () => {
            const el = video.current;
            // A suspended page may receive visibility before its queued native
            // pause event. Preserve that stop when the retained decoder was
            // playing, rather than waiting for event delivery while hidden.
            if (
                !foreground.current &&
                !document.hidden &&
                el?.paused &&
                !el.ended &&
                viewing.current.started &&
                !replacing.current &&
                !pauseIntent.current &&
                !(ownPauses.current.get(el) ?? 0)
            )
                latchStop("browser-pause-on-return");
            foreground.current = !document.hidden;
            console.info(
                `PictureshowPlayback lifecycle foreground=${foreground.current} paused=${pauseIntent.current} positionMs=${Math.floor(viewing.current.position * 1000)}`,
            );
        };
        const restore = () => {
            foreground.current = !document.hidden;
        };
        const pagehide = (event: PageTransitionEvent) => {
            latchStop("pagehide");
            // BFCache retains this viewing; permanent departure best-effort ends it.
            if (!event.persisted) void stop(false, true);
        };
        const freeze = () => latchStop("freeze");
        document.addEventListener("visibilitychange", visibility);
        document.addEventListener("freeze", freeze);
        document.addEventListener("resume", restore);
        window.addEventListener("pagehide", pagehide);
        window.addEventListener("pageshow", restore);
        visibility();
        return () => {
            document.removeEventListener("visibilitychange", visibility);
            document.removeEventListener("freeze", freeze);
            document.removeEventListener("resume", restore);
            window.removeEventListener("pagehide", pagehide);
            window.removeEventListener("pageshow", restore);
        };
    }, []);
    useEffect(() => {
        if (!("mediaSession" in navigator)) return;
        if (typeof MediaMetadata !== "undefined")
            navigator.mediaSession.metadata = new MediaMetadata({
                title: item.Name,
                artist: item.SeriesName ?? "Pictureshow",
                album:
                    item.Type === "Episode"
                        ? `Season ${item.ParentIndexNumber ?? ""}, Episode ${item.IndexNumber ?? ""}`
                        : "",
            });
        const handlers: Partial<
            Record<MediaSessionAction, MediaSessionActionHandler>
        > = {
            play: () => {
                if (pauseIntent.current) toggle();
            },
            pause: () => {
                if (!pauseIntent.current) toggle();
            },
            stop: () => {
                void leave();
            },
            seekbackward: () => seekStep(-10),
            seekforward: () => seekStep(10),
            seekto: (d) => {
                if (d.seekTime !== undefined) seek(d.seekTime);
            },
        };
        for (const [action, handler] of Object.entries(handlers))
            try {
                navigator.mediaSession.setActionHandler(
                    action as MediaSessionAction,
                    handler!,
                );
            } catch {
                /* unsupported action remains absent */
            }
        return () => {
            for (const action of Object.keys(handlers))
                try {
                    navigator.mediaSession.setActionHandler(
                        action as MediaSessionAction,
                        null,
                    );
                } catch {}
            navigator.mediaSession.metadata = null;
        };
    }, [duration, item.Id]);
    useEffect(() => {
        if (!("mediaSession" in navigator)) return;
        navigator.mediaSession.playbackState = paused ? "paused" : "playing";
        if (duration > 0 && navigator.mediaSession.setPositionState)
            try {
                navigator.mediaSession.setPositionState({
                    duration,
                    position: Math.min(duration, Math.max(0, position)),
                    playbackRate: video.current?.playbackRate ?? 1,
                });
            } catch {}
    }, [paused, position, duration]);
    useEffect(() => {
        let live = true;
        void client
            .catalog<Extras>(
                `Items/${encode(item.Id)}?fields=Chapters,Trickplay`,
            )
            .then((value) => {
                if (live) setExtras(value);
            })
            .catch(() => {});
        void client
            .catalog<{ Items: Segment[] }>(`MediaSegments/${encode(item.Id)}`)
            .then((value) => {
                if (live)
                    setSegments((value.Items ?? []).filter(usableSegment));
            })
            .catch(() => {});
        const lookup = async () => {
            if (item.Type !== "Episode" || !item.SeriesId) {
                if (live) {
                    setNext(null);
                    setNextState("ready");
                }
                return null;
            }
            try {
                const page = await client.catalog<{ Items: Item[] }>(
                    `Shows/${encode(item.SeriesId)}/Episodes?adjacentTo=${encode(item.Id)}&isMissing=false&enableUserData=true&fields=Overview`,
                );
                const index = page.Items.findIndex((v) => v.Id === item.Id),
                    following = index < 0 ? undefined : page.Items[index + 1];
                const found =
                    following &&
                    following.Type === "Episode" &&
                    following.Id !== item.Id
                        ? following
                        : null;
                if (live) {
                    setNext(found);
                    setNextState("ready");
                }
                return found;
            } catch (e) {
                if (live) setNextState("failed");
                throw e;
            }
        };
        nextPromise.current = lookup();
        void nextPromise.current.catch(() => {});
        return () => {
            live = false;
        };
    }, [item.Id]);
    async function saveCompleted(retry = false) {
        if (completionSaved.current) return;
        const projection = beginCompletion(client, item.Id);
        completionProjection.current = projection;
        let succeeded = false;
        try {
            if (retry) await retryFinalReport();
            await stop();
            if (stopError.current) throw new Error(stopError.current);
            if (leaving.current) throw new Error("Completion cancelled.");
            if (!completionMayApply(client, item.Id, projection)) {
                completionSaved.current = true;
                succeeded = true;
                return;
            }
            const fresh = await readCompletionDetail(client, item.Id);
            if (leaving.current) throw new Error("Completion cancelled.");
            if (!completionMayApply(client, item.Id, projection)) {
                completionSaved.current = true;
                succeeded = true;
                return;
            }
            if (!fresh.UserData?.Played)
                await client.request(
                    `UserPlayedItems/${encode(item.Id)}?userId=${encode(client.account!.user.Id)}`,
                    undefined,
                    "POST",
                );
            succeeded = true;
            completionSaved.current = true;
        } finally {
            finishCompletion(client, item.Id, projection, succeeded);
            if (completionProjection.current === projection)
                completionProjection.current = undefined;
        }
    }
    async function complete(
        nextItem?: Item | null,
        retry = false,
        automatic = false,
    ) {
        if (completing.current || leaving.current) return;
        if (!automatic && foreground.current && !document.hidden)
            stopLatched.current = false;
        if (!backgroundPlayAllowed()) return;
        if (automatic && pauseIntent.current) return;
        const generation = stopGeneration.current;
        completing.current = true;
        completionDeferred.current = false;
        setError("");
        completionNext.current = nextItem;
        pauseIntent.current = true;
        sample();
        pauseDecoder();
        setNotice(
            nextItem ? "Opening next episode…" : "Saving completed playback…",
        );
        const barrier = saveCompleted(retry);
        if (!nextItem && onLeaving) {
            // The returning detail now owns this barrier and its retry. Ordinary
            // page teardown must not cancel that intentional ownership transfer.
            completionHandedOff.current = true;
            onLeaving(barrier, () => saveCompleted(true));
            return;
        }
        try {
            await barrier;
        } catch (e) {
            completing.current = false;
            if (!alive.current || leaving.current) return;
            setCompletionError(true);
            setError(
                e instanceof Error
                    ? e.message
                    : "Completed playback could not be saved. Retry completion or return to details.",
            );
            return;
        }
        if (!alive.current || leaving.current) return;
        // A Stop already committed before a lifecycle stop cannot be undone. Keep
        // the completed destination and require an explicit foreground action.
        if (
            nextItem &&
            (!backgroundPlayAllowed() || stopGeneration.current !== generation)
        ) {
            completing.current = false;
            completionDeferred.current = true;
            setPaused(true);
            setControls(true);
            setNotice(
                "Playback finished. Choose Play now or return to details.",
            );
            return;
        }
        if (nextItem && onNext) onNext(nextItem);
        else onBack(stopError.current || undefined);
    }
    async function physicalEnd() {
        if (ended.current || pauseIntent.current || !backgroundPlayAllowed())
            return;
        ended.current = true;
        // A held final frame represents the logical endpoint, not its 1ms
        // decoder clamp, including when explicit Play completes the viewing.
        if (!pausedAtPhysicalEnd.current) sample();
        setPaused(true);
        setControls(true);

        let found: Item | null = null;
        if (!cancelNext && item.Type === "Episode") {
            setNotice("Finding next episode…");
            try {
                found = (await nextPromise.current) ?? null;
            } catch {
                if (!nextRetry.current) {
                    nextRetry.current = true;
                    try {
                        const page = await client.catalog<{ Items: Item[] }>(
                            `Shows/${encode(item.SeriesId!)}/Episodes?adjacentTo=${encode(item.Id)}&isMissing=false&enableUserData=true&fields=Overview`,
                        );
                        const index = page.Items.findIndex(
                            (v) => v.Id === item.Id,
                        );
                        const candidate = page.Items[index + 1];
                        found =
                            index >= 0 &&
                            candidate?.Type === "Episode" &&
                            candidate.Id !== item.Id
                                ? candidate
                                : null;
                        setNext(found);
                        setNextState("ready");
                    } catch {
                        setNextState("failed");
                    }
                }
            }
        }
        if (!alive.current || leaving.current) return;
        completionNext.current = found;
        if (!backgroundPlayAllowed() || pauseIntent.current) {
            completionDeferred.current = true;
            setNotice(
                "Playback finished. Press Play to continue or return to details.",
            );
            return;
        }
        if (reader && found && !cancelNext) {
            setNext(found);
            setNotice(
                "Playback finished. Choose Play now for the next episode.",
            );
            return;
        }
        await complete(found, false, true);
    }
    const physicalEndRef = useRef(physicalEnd);
    physicalEndRef.current = physicalEnd;
    // This cleanup runs before decoder cleanup on unmount, but not on a
    // request replacement—even if a newer request is pending while leaving.
    const decoderUnmounting = useRef(false);
    useLayoutEffect(() => {
        decoderUnmounting.current = false;
        return () => {
            decoderUnmounting.current = true;
        };
    }, []);
    // Dispose the native/MSE decoder before React detaches its video node.
    // In particular, WebKit can still own a paused final-frame presentation.
    useLayoutEffect(() => {
        const el = video.current!;
        // Each decoder reports its first Progress on its first clock update.
        periodicProgressAt.current = 0;
        let disposed = false,
            hls: Hls | undefined,
            current: Decision | undefined,
            hasPlayed = false,
            needsGesture = false,
            lastPlaybackClock = Number.NaN,
            finalFragmentBuffered = false,
            mediaDuration: number | undefined;
        let slow: ReturnType<typeof setTimeout> | undefined,
            deadline: ReturnType<typeof setTimeout> | undefined,
            directTimer: ReturnType<typeof setTimeout> | undefined;
        playbackFailed.current = false;
        setError("");
        setNotice("Preparing playback…");
        setBuffering(true);
        setDecision(undefined);
        setPlaybackReady(false);
        setReceived({});
        replacing.current = true;
        const probe = (mime: string) => el.canPlayType(mime) === "probably";
        const clearWaiting = () => {
            clearTimeout(slow);
            clearTimeout(deadline);
            slow = undefined;
            deadline = undefined;
        };
        function waiting() {
            if (disposed || pauseIntent.current || deadline) return;
            setBuffering(true);
            clearWaiting();
            slow = setTimeout(() => {
                if (!disposed)
                    setNotice(
                        "Still waiting for playback. You can go back and try again.",
                    );
            }, 10000);
            deadline = setTimeout(() => {
                if (!disposed) fail(true);
            }, 45000);
        }
        function update() {
            if (disposed || !current) return;
            if (replacing.current) {
                settleEndpoint();
                return;
            }
            sample();
            setPaused(pauseIntent.current);
            const advanced = el.currentTime > lastPlaybackClock;
            lastPlaybackClock = el.currentTime;
            if (
                advanced &&
                !el.paused &&
                !el.seeking &&
                pendingSeek.current === undefined &&
                !pauseIntent.current &&
                el.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
            ) {
                // Some engines send stalled without another playing event when
                // media data catches up. A moving decoder clock proves recovery.
                clearWaiting();
                setBuffering(false);
                setNotice((value) =>
                    value ===
                        "Still waiting for playback. You can go back and try again." ||
                    value === "Preparing playback…"
                        ? ""
                        : value,
                );
            }
            if (Date.now() - periodicProgressAt.current >= 10000) {
                periodicProgressAt.current = Date.now();
                report("progress");
            }
        }
        // Metadata establishes the item's logical duration. MSE endOfStream
        // may subsequently trim native duration to its last buffered packet.
        const fullDuration = () =>
            mediaDuration ??
            (plausibleDuration(el.duration) ? el.duration : duration);
        const logicalStart = () => {
            const end = fullDuration();
            return end > 0 ? Math.min(request.position, end) : request.position;
        };
        const atLogicalEnd = () =>
            fullDuration() > 0 && request.position >= fullDuration() - 0.001;
        const decoderStart = () => {
            const end = fullDuration(),
                logical = logicalStart();
            // HLS has an exclusive physical endpoint. Keep the bounded logical
            // target while decoding its last available frame, including a stale
            // resume position beyond the authoritative duration.
            if (!atLogicalEnd()) return logical;
            // A packet's end is not a presentation timestamp. A 1ms inset can
            // leave WebKit seeking forever even with HAVE_ENOUGH_DATA; keep a
            // small frame window inside the buffer, while reports retain EOF.
            let target = Math.max(0, Math.min(logical, end - 0.1));
            // The final encoded A/V intersection can end before the playlist's
            // declared duration. For MSE use the completed final fragment; native
            // HLS exposes no completion flag and can stay NETWORK_LOADING while
            // seeking beyond its final packet, so require its bounded tail below.
            if (
                (finalFragmentBuffered || hls?.bufferedToEnd || !hls) &&
                el.buffered.length
            ) {
                const last = el.buffered.length - 1;
                const tail = el.buffered.end(last),
                    beginning = el.buffered.start(last);
                // Native idle can mean partial preload, not EOF. A final
                // frame substitution must remain within the endpoint window.
                if (target >= tail - 0.1 && end - tail <= 0.5)
                    target = Math.max(
                        beginning,
                        tail - Math.min(0.1, (tail - beginning) / 2),
                    );
            }
            return target;
        };
        function settleEndpoint() {
            if (
                disposed ||
                !current ||
                !replacing.current ||
                desired.current.revision !== request.revision ||
                !atLogicalEnd()
            )
                return;
            const target = decoderStart();
            if (Math.abs(el.currentTime - target) >= 0.0005)
                try {
                    el.currentTime = target;
                } catch {}
            publishReady();
        }
        function publishReady() {
            if (!current || disposed || !replacing.current) return false;
            if (desired.current.revision !== request.revision) {
                setRequest({ ...desired.current });
                return false;
            }
            // Metadata is not an acknowledgement of a requested seek. All
            // browser engines must actually reach the full-timeline target.
            if (
                el.seeking ||
                el.readyState < HTMLMediaElement.HAVE_CURRENT_DATA ||
                Math.abs(el.currentTime - decoderStart()) >= 0.5
            )
                return false;
            if (!backgroundPlayAllowed()) pauseIntent.current = true;
            replacing.current = false;
            decoderReady(el);
            if (pendingSeek.current === request.position)
                pendingSeek.current = undefined;
            pausedAtPhysicalEnd.current = atLogicalEnd();
            if (pausedAtPhysicalEnd.current) {
                viewing.current.position = logicalStart();
                setPosition(viewing.current.position);
            }
            releaseTrackCommands();
            clearTimeout(directTimer);
            clearWaiting();
            setBuffering(false);
            setNotice("");
            if (!pausedAtPhysicalEnd.current) sample();
            if (pauseIntent.current) pauseDecoder(el);
            setPaused(pauseIntent.current);
            if (!viewing.current.started) {
                viewing.current.started = true;
                report("start");
            } else report("progress");
            // A foreground Play may have arrived after metadata while this
            // request still awaited actual readiness. Apply the latest intent
            // at acknowledgement rather than losing it behind replacement.
            if (!pauseIntent.current) {
                if (pausedAtPhysicalEnd.current) {
                    pauseDecoder(el);
                    void physicalEndRef.current();
                } else if (el.paused)
                    playMedia(el, () => {
                        if (
                            disposed ||
                            desired.current.revision !== request.revision ||
                            pauseIntent.current
                        )
                            return;
                        if (document.hidden) latchStop("browser-play-rejected");
                        pauseIntent.current = true;
                        setPaused(true);
                        setNotice("Press Play to start playback.");
                    });
            }
            return true;
        }
        function playing() {
            if (!current || disposed) return;
            // A latch without a following pause event must not swallow a later one.
            if (latchPauseReported.current === el)
                latchPauseReported.current = undefined;
            if (!backgroundPlayAllowed()) pauseIntent.current = true;
            if (desired.current.revision !== request.revision) {
                setRequest({ ...desired.current });
                return;
            }
            hasPlayed = true;
            if (replacing.current) {
                if (!publishReady() && pauseIntent.current) pauseDecoder(el);
                return;
            }
            releaseTrackCommands();
            clearTimeout(directTimer);
            clearWaiting();
            setBuffering(false);
            setNotice("");
            if (
                pendingSeek.current !== undefined &&
                Math.abs(el.currentTime - pendingSeek.current) < 0.5
            )
                pendingSeek.current = undefined;
            if (pauseIntent.current) pauseDecoder(el);
            setPaused(pauseIntent.current);
            if (!viewing.current.started) {
                viewing.current.started = true;
                report("start");
            } else report("progress");
        }
        function pause() {
            const owned = ownPauses.current.get(el) ?? 0;
            if (owned) ownPauses.current.set(el, owned - 1);
            if (disposed || !current) return;
            if (!owned && document.hidden && !el.ended && !pauseIntent.current)
                latchStop("browser-pause");
            if (latchPauseReported.current === el) {
                // latchStop sampled, paused the UI and reported this stop. Of
                // update(), only the clock baseline remains (its recovery
                // branch requires a playing decoder).
                latchPauseReported.current = undefined;
                if (!replacing.current) lastPlaybackClock = el.currentTime;
                return;
            }
            if (replacing.current) return;
            // This handler reports below; update() must not add its periodic one.
            periodicProgressAt.current = Date.now();
            update();
            if (!el.ended) {
                setPaused(pauseIntent.current);
                if (pauseIntent.current) setControls(true);
                report("progress");
            }
        }
        function fail(terminal = false) {
            if (disposed || leaving.current) return;
            pendingPanel.current = undefined;
            setPanel(undefined);
            sample();
            clearWaiting();
            if (
                !terminal &&
                current?.method === "DirectPlay" &&
                !desired.current.hls &&
                retries.current++ === 0
            ) {
                setNotice("Trying compatible playback…");
                replacing.current = false;
                change(
                    desired.current.audio,
                    desired.current.subtitle,
                    viewing.current.position,
                    true,
                );
                return;
            }
            playbackFailed.current = true;
            releaseTrackCommands();
            pendingPanel.current = undefined;
            setPanel(undefined);
            setBuffering(false);
            replacing.current = false;
            setControls(true);
            setError(
                terminal
                    ? "Playback did not become ready within 45 seconds. Retry or return to details."
                    : "Playback could not continue. Retry or return to details.",
            );
        }
        function metadata() {
            if (!current || disposed) return;
            if (desired.current.revision !== request.revision) {
                setRequest({ ...desired.current });
                return;
            }
            // Native HLS and direct playback use the same full-media timeline.
            // hls.js also receives this target as startPosition before loading.
            const target = decoderStart();
            if (Math.abs(el.currentTime - target) >= 0.0005)
                try {
                    el.currentTime = target;
                } catch {}
            applyCaptions();
            if (plausibleDuration(el.duration)) {
                mediaDuration = el.duration;
                setDuration(mediaDuration);
            }
            if (!backgroundPlayAllowed()) pauseIntent.current = true;
            if (pauseIntent.current) {
                pauseDecoder(el);
                setPaused(true);
                publishReady();
            } else
                playMedia(el, () => {
                    needsGesture = true;
                    if (document.hidden) latchStop("browser-play-rejected");
                    pauseIntent.current = true;
                    setPaused(true);
                    publishReady();
                    setNotice("Press Play to start playback.");
                });
        }
        const onError = () => fail();
        const onEnd = () => {
            if (!pauseIntent.current && !disposed)
                void physicalEndRef.current();
        };
        const seeking = () => {
            lastPlaybackClock = Number.NaN;
        };
        const seeked = () => {
            lastPlaybackClock = el.currentTime;
            if (replacing.current) {
                publishReady();
                return;
            }
            if (
                !disposed &&
                current &&
                Math.abs(
                    el.currentTime - (pendingSeek.current ?? el.currentTime),
                ) < 0.5
            ) {
                pendingSeek.current = undefined;
                sample();
            }
        };
        el.addEventListener("seeking", seeking);
        el.addEventListener("seeked", seeked);
        el.addEventListener("loadedmetadata", metadata);
        el.addEventListener("loadeddata", publishReady);
        el.addEventListener("canplay", publishReady);
        el.addEventListener("progress", settleEndpoint);
        el.addEventListener("suspend", settleEndpoint);
        el.addEventListener("timeupdate", update);
        el.addEventListener("playing", playing);
        el.addEventListener("pause", pause);
        el.addEventListener("ended", onEnd);
        el.addEventListener("error", onError);
        el.addEventListener("waiting", waiting);
        el.addEventListener("stalled", waiting);
        const timer = setInterval(update, 1000);
        waiting();
        void (async () => {
            await cleanup.current;
            const detail = await client.detail(item.Id);
            if (disposed || leaving.current) return;
            const source =
                detail.MediaSources?.find((s) => s.Id === request.sourceId) ??
                detail.MediaSources?.[0];
            const sameSource =
                !request.sourceId || source?.Id === request.sourceId;
            let preparedAudio = sameSource ? request.audio : undefined;
            let preparedSubtitle = sameSource
                ? desired.current.subtitle
                : undefined;
            if (!sameSource) {
                trackOrigins.current = { audio: false, subtitle: false };
                desired.current.audio = undefined;
                desired.current.subtitle = undefined;
            }
            // No standard browser API exposes the viewer's OS caption language.
            // Automatic opening therefore leaves selection to user-scoped PlaybackInfo,
            // whose valid default (including remembered Off) is authoritative. Only
            // explicit in-viewing choices are pinned to this source.
            const d = await client.prepare(
                detail,
                probe,
                request.position,
                preparedAudio,
                preparedSubtitle,
                request.hls ||
                    (location.protocol === "https:" &&
                        !!localAddressSpace(client.server.url)),
                preferences.bitrateMbps ?? null,
                source?.Id,
            );
            if (disposed || leaving.current) {
                await client.stopEncoding(d);
                return;
            }
            current = d;
            if (desired.current.revision !== request.revision) {
                await client.stopEncoding(d);
                setRequest({ ...desired.current });
                return;
            }
            viewing.current.decision = d;
            viewing.current.position = logicalStart();
            setPosition(viewing.current.position);
            viewing.current.audio =
                (trackOrigins.current.audio
                    ? desired.current.audio
                    : preparedAudio) ??
                d.source.DefaultAudioStreamIndex ??
                d.source.MediaStreams?.find((s) => s.Type === "Audio")?.Index ??
                -1;
            viewing.current.subtitle =
                (trackOrigins.current.subtitle
                    ? desired.current.subtitle
                    : preparedSubtitle) ??
                d.source.DefaultSubtitleStreamIndex ??
                -1;
            // Playback intent survives decoder changes independently of report provenance.
            desired.current.audio ??= viewing.current.audio;
            desired.current.subtitle ??= viewing.current.subtitle;
            desired.current.sourceId = d.source.Id;
            console.info(
                `PictureshowPlayback subtitle-select item=${item.Id} session=${d.playSessionId ?? "none"} stream=${viewing.current.subtitle < 0 ? "off" : viewing.current.subtitle} paused=${pauseIntent.current} positionMs=${Math.floor(viewing.current.position * 1000)} selection=${++subtitleSelection.current}`,
            );
            setDecision(d);
            if (
                d.method === "DirectPlay" ||
                (el.canPlayType("application/vnd.apple.mpegurl") &&
                    !(
                        location.protocol === "https:" &&
                        localAddressSpace(d.url)
                    ))
            ) {
                el.src = d.url;
                el.load();
                directTimer = setTimeout(() => {
                    if (
                        !disposed &&
                        !hasPlayed &&
                        !needsGesture &&
                        !pauseIntent.current &&
                        d.method === "DirectPlay"
                    )
                        fail();
                }, 8000);
            } else {
                const { default: Hls, FetchLoader } = await import("hls.js");
                if (disposed) return;
                if (!Hls.isSupported())
                    throw new Error(
                        "This browser has no supported HLS playback engine.",
                    );
                hls = new Hls({
                    startPosition: decoderStart(),
                    loader: FetchLoader,
                    fetchSetup(context, init) {
                        return authenticatedMediaRequest(
                            client.account!,
                            context.url,
                            init,
                        );
                    },
                });
                hls.on(Hls.Events.FRAG_BUFFERED, (_event, data) => {
                    if (data.frag.endList) {
                        finalFragmentBuffered = true;
                        settleEndpoint();
                    }
                });
                hls.on(Hls.Events.BUFFERED_TO_END, () => {
                    finalFragmentBuffered = true;
                    settleEndpoint();
                });
                // Codecs the demuxer found in the received stream, not the server's metadata.
                hls.on(Hls.Events.BUFFER_CODECS, (_event, data) => {
                    const codecs = [
                        data.video?.codec,
                        data.audio?.codec,
                        ...(data.audiovideo?.codec?.split(",") ?? []),
                    ]
                        .filter((c): c is string => !!c)
                        .map((c) => c.trim());
                    const audioCodec = (c: string) =>
                        /^(mp4a|ac-3|ec-3|opus|flac|mp3)/i.test(c);
                    if (!disposed)
                        setReceived({
                            video: codecs.find((c) => !audioCodec(c)),
                            audio: codecs.find(audioCodec),
                        });
                });
                hls.on(Hls.Events.ERROR, (_event, data) => {
                    if (data.fatal) fail();
                });
                hls.loadSource(d.url);
                hls.attachMedia(el);
            }
        })().catch((e) => {
            if (!disposed) {
                playbackFailed.current = true;
                releaseTrackCommands();
                pendingPanel.current = undefined;
                setPanel(undefined);
                clearWaiting();
                replacing.current = false;
                setBuffering(false);
                setControls(true);
                setError(e instanceof Error ? e.message : "Playback failed.");
                if (e instanceof ClientError && e.kind === "EXPIRED")
                    onExpired(e);
            }
        });
        return () => {
            // Preserve the last full-media position before decoder reset, also
            // for navigation whose final Stop runs in passive cleanup later.
            sample();
            disposed = true;
            clearInterval(timer);
            clearWaiting();
            clearTimeout(directTimer);
            el.removeEventListener("seeking", seeking);
            el.removeEventListener("seeked", seeked);
            el.removeEventListener("loadedmetadata", metadata);
            el.removeEventListener("loadeddata", publishReady);
            el.removeEventListener("canplay", publishReady);
            el.removeEventListener("progress", settleEndpoint);
            el.removeEventListener("suspend", settleEndpoint);
            el.removeEventListener("timeupdate", update);
            el.removeEventListener("playing", playing);
            el.removeEventListener("pause", pause);
            el.removeEventListener("ended", onEnd);
            el.removeEventListener("error", onError);
            el.removeEventListener("waiting", waiting);
            el.removeEventListener("stalled", waiting);
            pauseDecoder(el);
            if (hls) disposeHlsDecoder(hls, el, !decoderUnmounting.current);
            else {
                el.removeAttribute("src");
                if (el.isConnected) el.load();
            }
            if (current)
                cleanup.current = cleanup.current
                    .catch(() => {})
                    .then(() => client.stopEncoding(current!))
                    .catch(() => {});
        };
    }, [request]);
    const subtitleSource = activeSource?.Id;
    // The sideload always requests full authored time. Conversion is independent of A/V and its stall budget.
    const subtitleUrl =
        subtitleSource && textSubtitle
            ? `Videos/${encode(item.Id)}/${encode(subtitleSource)}/Subtitles/${subtitleIndex}/0/Stream.vtt?copyTimestamps=false&addVttTimeMap=false`
            : undefined;
    const [subtitleBlob, setSubtitleBlob] = useState<string>();
    const subtitleCache = useRef(new Map<string, string>());
    const loadedCaption = useRef<
        { index: number; selection: number; source: string } | undefined
    >(undefined);
    const cueReceipts = useRef(new Set<string>());
    const retrySubtitle = useRef<(() => void) | undefined>(undefined);
    useEffect(() => {
        let live = true,
            timer: ReturnType<typeof setTimeout> | undefined,
            controller: AbortController | undefined;
        setSubtitleBlob(undefined);
        setNotice((value) =>
            value.startsWith("Subtitles are still loading") ? "" : value,
        );
        retrySubtitle.current = undefined;
        if (!subtitleUrl) return;
        const origin = {
            index: subtitleIndex,
            selection: subtitleSelection.current,
            source: subtitleSource!,
        };
        let attempts = 0;
        const publish = (object: string) => {
            if (live) {
                loadedCaption.current = origin;
                setSubtitleBlob(object);
            }
        };
        const failed = () => {
            if (!live) return;
            setNotice(
                "Subtitles are still loading. Playback can continue while we retry.",
            );
            clearTimeout(timer);
            timer = setTimeout(
                load,
                externalSubtitleRetryDelay(Math.max(1, attempts)),
            );
        };
        const load = async () => {
            attempts++;
            controller = new AbortController();
            const timeout = setTimeout(() => controller?.abort(), 30000);
            try {
                const response = await fetchMedia(
                    client.account!,
                    subtitleUrl,
                    { signal: controller.signal },
                );
                const reader = response.body?.getReader();
                const chunks: Uint8Array[] = [];
                let size = 0;
                if (!reader) throw new Error("Empty captions");
                for (;;) {
                    const part = await reader.read();
                    if (part.done) break;
                    size += part.value.byteLength;
                    if (size > 16 * 1024 * 1024) {
                        await reader.cancel();
                        throw new Error("Caption limit");
                    }
                    chunks.push(part.value);
                }
                const text = new TextDecoder().decode(
                    new Uint8Array(
                        await new Blob(chunks as BlobPart[]).arrayBuffer(),
                    ),
                );
                if (!validSubtitleText(text))
                    throw new Error("Malformed captions");
                if (!live) return;
                const object = URL.createObjectURL(
                    new Blob([text], { type: "text/vtt" }),
                );
                subtitleCache.current.set(subtitleUrl, object);
                console.info(
                    `PictureshowPlayback subtitle-loaded item=${item.Id} session=${viewing.current.decision?.playSessionId ?? "none"} stream=${origin.index} attempts=${attempts}`,
                );
                publish(object);
                setNotice((value) =>
                    value.startsWith("Subtitles are still loading")
                        ? ""
                        : value,
                );
            } catch {
                failed();
            } finally {
                clearTimeout(timeout);
            }
        };
        retrySubtitle.current = () => {
            const cached = subtitleCache.current.get(subtitleUrl);
            if (cached) URL.revokeObjectURL(cached);
            subtitleCache.current.delete(subtitleUrl);
            setSubtitleBlob(undefined);
            failed();
        };
        const cached = subtitleCache.current.get(subtitleUrl);
        if (cached) publish(cached);
        else void load();
        return () => {
            live = false;
            clearTimeout(timer);
            controller?.abort();
        };
    }, [subtitleUrl]);
    useEffect(
        () => () => {
            for (const url of subtitleCache.current.values())
                URL.revokeObjectURL(url);
            subtitleCache.current.clear();
        },
        [],
    );
    function applyCaptions() {
        const track = video.current?.textTracks[0];
        if (!track) return;
        track.mode = "showing";
        const attached = new Set(Array.from(track.cues ?? []));
        let originals = trackCueSets.current.get(track);
        if (!originals) {
            originals = new Set();
            trackCueSets.current.set(track, originals);
        }
        // Retain full-file originals even when a cue is outside the decoder's
        // nonnegative timeline: a later subtitle delay or seek can restore it.
        for (const cue of attached) {
            originals.add(cue);
            if (!cues.current.has(cue))
                cues.current.set(cue, {
                    start: cue.startTime,
                    end: cue.endTime,
                    ...(cue instanceof VTTCue
                        ? { line: cue.line, snap: cue.snapToLines }
                        : {}),
                });
        }
        const authoredStarts = new Set(
            Array.from(originals, (cue) => cues.current.get(cue)!.start),
        );
        for (const cue of originals) {
            const source = cues.current.get(cue)!;
            const start = source.start + delayRef.current / 1000;
            let end = source.end + delayRef.current / 1000;
            // Chrome can keep both touching intervals active at an exact paused
            // boundary. Give that boundary to the arriving cue, preserving truly
            // overlapping authored intervals and immutable full-file times.
            if (authoredStarts.has(source.end))
                end -= Math.min(0.001, (source.end - source.start) / 2);
            if (end <= 0) {
                if (attached.has(cue)) track.removeCue(cue);
                continue;
            }
            cue.startTime = start;
            cue.endTime = Math.max(start + 0.000001, end);
            if (!attached.has(cue)) track.addCue(cue);
            if (cue instanceof VTTCue) {
                cue.snapToLines =
                    safeCaptionLine.current === null
                        ? (source.snap ?? true)
                        : false;
                cue.line =
                    safeCaptionLine.current === null
                        ? (source.line ?? "auto")
                        : safeCaptionLine.current;
            }
        }
        const origin = loadedCaption.current;
        if (origin && track.cues?.length) {
            const key = `${origin.source}:${origin.index}:${origin.selection}`;
            if (!cueReceipts.current.has(key)) {
                cueReceipts.current.add(key);
                const first =
                    cues.current.get(track.cues[0])?.start ??
                    track.cues[0].startTime;
                console.info(
                    `PictureshowPlayback subtitle-cues item=${item.Id} session=${viewing.current.decision?.playSessionId ?? "none"} stream=${origin.index} firstCueAtMs=${Math.floor(first * 1000)} selection=${origin.selection}`,
                );
            }
        }
    }
    useEffect(() => {
        delayRef.current = subtitleDelay;
        applyCaptions();
    }, [
        subtitleDelay,
        controls,
        panel,
        seekPreview,
        subtitleBlob,
        selectionRevision,
        request,
        decision,
    ]);
    useEffect(() => {
        if (panel && !panelElement.current?.open) {
            panelElement.current?.showModal();
            // The committed dialog already exists. Choose initial selection now;
            // a later animation frame must not override the viewer's next move.
            const selected = panelElement.current?.querySelector<HTMLElement>(
                '[aria-checked="true"], [aria-current="true"], [data-panel-first]',
            );
            selected?.focus();
            selected?.scrollIntoView({ block: "nearest" });
        } else if (!panel && panelElement.current?.open)
            panelElement.current.close();
    }, [panel]);
    const segment = segments
        .filter(
            (s) =>
                usableSegment(s) &&
                position >= s.StartTicks / 1e7 &&
                position < s.EndTicks / 1e7 &&
                preferences.segments?.[s.Type as SegmentType] !== "Off",
        )
        .sort((a, b) => a.StartTicks - b.StartTicks)[0];
    const segmentKey = segment
        ? (segment.Id ?? `${segment.Type}:${segment.StartTicks}`)
        : "";
    const skip = () => {
        if (segment && !skipUsed.current.has(segmentKey)) {
            skipUsed.current.add(segmentKey);
            seek(segment.EndTicks / 1e7);
            video.current?.focus();
        }
    };
    useEffect(() => {
        if (
            segment &&
            ["Auto", "Automatic"].includes(
                preferences.segments?.[segment.Type as SegmentType] ?? "",
            ) &&
            !skipUsed.current.has(segmentKey)
        )
            skip();
    }, [segmentKey]);
    useEffect(() => {
        if (
            !segment &&
            document.activeElement?.getAttribute("data-skip") === "true"
        )
            video.current?.focus();
    }, [segmentKey]);
    const credits = segments
        .filter(
            (s) =>
                s.Type === "Outro" &&
                usableSegment(s) &&
                s.StartTicks / 1e7 < duration,
        )
        .sort((a, b) => a.StartTicks - b.StartTicks)[0];
    const nextEligible =
        !!next &&
        !cancelNext &&
        (ended.current ||
            (duration > 0 &&
                position >=
                    (credits
                        ? credits.StartTicks / 1e7
                        : Math.max(0, duration - 10))));
    useEffect(() => {
        if (!nextEligible) {
            setCountdown(10);
            return;
        }
        if (
            !credits ||
            reader ||
            paused ||
            buffering ||
            panel ||
            seekPreview !== undefined ||
            cancelNext
        )
            return;
        const t = setInterval(() => {
            if (backgroundPlayAllowed() && !pauseIntent.current)
                setCountdown((n) => Math.max(0, n - 1));
        }, 1000);
        return () => clearInterval(t);
    }, [
        nextEligible,
        credits,
        reader,
        paused,
        buffering,
        panel,
        seekPreview,
        cancelNext,
    ]);
    useEffect(() => {
        if (
            countdown === 0 &&
            nextEligible &&
            !reader &&
            !paused &&
            backgroundPlayAllowed() &&
            !pauseIntent.current &&
            !buffering &&
            !panel &&
            seekPreview === undefined
        )
            void complete(next, false, true);
    }, [
        countdown,
        nextEligible,
        reader,
        paused,
        buffering,
        panel,
        seekPreview,
    ]);
    // PLAY-27: the decision behind this viewing's picture, gated on this browser's
    // own per-file check so a notice never blames it for video it could decode.
    // Only the current decision counts: while a new negotiation is pending there is
    // nothing settled to describe, and the previous stream's decision would be stale.
    const conversion = (() => {
        if (!decision) return null;
        const found = conversionNotice(
            {
                method: decision.method,
                url: decision.url,
                reasons: transcodeReasons(decision.url),
                source: decision.source,
            },
            subtitleIndex,
            BROWSER_DEVICE,
        );
        if (!found) return null;
        if (
            !found.burnIn &&
            canDecodeVideo(
                decision.source,
                (mime) => video.current?.canPlayType(mime) === "probably",
            )
        )
            return null;
        return found;
    })();
    useEffect(() => {
        if (
            noticePhase !== "waiting" ||
            !noticeEnabled ||
            !conversion ||
            !playbackReady
        )
            return;
        setNoticePhase("active");
        setShownNotice(conversion);
        // Announced once per viewing; a later return from under a panel is silent.
        setAnnouncement(
            `${conversion.title}. ${conversion.reason} ${BROWSER_NOTICE_ACTIONS[noticeInput]}`,
        );
        // The 10 seconds keep running while something hides the notice.
        noticeTimer.current = setTimeout(() => {
            if (!alive.current) return;
            setNoticePhase("done");
            setAnnouncement("");
        }, 10000);
    }, [noticePhase, conversion?.reason, playbackReady]);
    useEffect(
        () => () => {
            clearTimeout(noticeTimer.current);
            clearTimeout(speedNoteTimer.current);
        },
        [],
    );
    useEffect(() => {
        // The last input decides the notice's action line.
        const pointer = (e: PointerEvent) =>
            setNoticeInput(e.pointerType === "mouse" ? "mouse" : "touch");
        const key = (e: KeyboardEvent) => {
            if (!e.metaKey && !e.ctrlKey && !e.altKey)
                setNoticeInput("keyboard");
        };
        window.addEventListener("pointerdown", pointer, true);
        window.addEventListener("keydown", key, true);
        return () => {
            window.removeEventListener("pointerdown", pointer, true);
            window.removeEventListener("keydown", key, true);
        };
    }, []);
    useEffect(() => {
        const track = video.current?.textTracks[0];
        if (!track || !subtitleBlob) {
            setTopCue(false);
            return;
        }
        const check = () =>
            setTopCue(
                Array.from(track.activeCues ?? []).some((cue) => {
                    const authored = cues.current.get(cue);
                    return (
                        !!authored && cueNearTop(authored.line, authored.snap)
                    );
                }),
            );
        track.addEventListener("cuechange", check);
        check();
        return () => track.removeEventListener("cuechange", check);
    }, [subtitleBlob]);
    const noticeVisible =
        noticePhase === "active" &&
        !!shownNotice &&
        !panel &&
        !pendingPanel.current &&
        seekPreview === undefined &&
        !error &&
        !nextEligible &&
        !topCue;
    useLayoutEffect(() => {
        // Keep the notice clear of the visible controls' heading at the top.
        const heading =
            root.current?.querySelector<HTMLElement>(".player-heading");
        const top = root.current?.getBoundingClientRect().top ?? 0;
        setNoticeTop(
            controls && heading
                ? heading.getBoundingClientRect().bottom - top + 8
                : undefined,
        );
    }, [controls, noticeVisible]);
    useEffect(() => {
        const el = video.current;
        if (!el) return;
        const size = () =>
            setVideoSize(
                el.videoWidth ? `${el.videoWidth}×${el.videoHeight}` : "",
            );
        el.addEventListener("resize", size);
        el.addEventListener("loadedmetadata", size);
        return () => {
            el.removeEventListener("resize", size);
            el.removeEventListener("loadedmetadata", size);
        };
    }, []);
    function surfaceKey(e: React.KeyboardEvent) {
        if (e.key === "Enter" || e.key === "ArrowUp" || e.key === "ArrowDown") {
            e.preventDefault();
            e.stopPropagation();
            if (e.repeat) return;
            if (
                e.key === "Enter" &&
                segment &&
                (preferences.segments?.[segment.Type as SegmentType] ??
                    "Ask") === "Ask"
            )
                skip();
            else reveal();
        } else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
            e.preventDefault();
            e.stopPropagation();
            lastActivity.current = Date.now();
            const count = Number(video.current?.dataset.repeat ?? 0) + 1;
            if (video.current)
                video.current.dataset.repeat = String(e.repeat ? count : 1);
            seekStep(
                (e.key === "ArrowRight" ? 1 : -1) *
                    (e.repeat && count >= 5 ? 30 : 10),
            );
        } else if (e.key === " " || e.key === "MediaPlayPause") {
            e.preventDefault();
            e.stopPropagation();
            toggle();
        } else if (e.key === "Escape" || e.key === "Backspace") {
            e.preventDefault();
            e.stopPropagation();
            if (!e.repeat) back();
        }
    }
    const audio = menus?.audio.streams ?? [],
        subtitles = menus?.subtitles.streams ?? [];
    const labels =
        panel === "Audio"
            ? (menus?.audio.labels ?? [])
            : (menus?.subtitles.labels ?? []);
    const sourceSignature = activeSource
        ? `${activeSource.Id}:${JSON.stringify(activeSource.MediaStreams ?? [])}`
        : undefined;
    useEffect(() => {
        if (!sourceSignature || !activeSource) return;
        const previous = menuSource.current;
        const generation = ++menuGeneration.current;
        menuSource.current = sourceSignature;
        setMenus(undefined);
        if (previous && previous !== sourceSignature) {
            pendingPanel.current = undefined;
            if (panel === "Audio" || panel === "Subtitles") closePanel();
        }
        const worker = new Worker(
            new URL("./player-track-worker.ts", import.meta.url),
            { type: "module" },
        );
        worker.onmessage = (event) => {
            if (!alive.current || generation !== menuGeneration.current) return;
            const prepared = event.data as TrackMenus;
            setMenus(prepared);
            const pending = pendingPanel.current;
            if (
                pending &&
                (pending.source === null || pending.source === sourceSignature)
            ) {
                pendingPanel.current = undefined;
                panelSource.current = sourceSignature;
                setPanel(pending.value);
                setNotice("");
            }
        };
        worker.onerror = () => {
            if (generation === menuGeneration.current) {
                pendingPanel.current = undefined;
                setNotice("Track choices could not be prepared. Try again.");
            }
        };
        worker.postMessage({
            source: sourceSignature,
            streams: activeSource.MediaStreams ?? [],
        });
        return () => {
            worker.terminate();
        };
    }, [sourceSignature]);
    useEffect(() => {
        const pending = pendingPanel.current;
        if (
            !pending ||
            !decision ||
            !menus ||
            menus.source !== sourceSignature ||
            (pending.source !== null && pending.source !== sourceSignature)
        )
            return;
        pendingPanel.current = undefined;
        panelSource.current = sourceSignature;
        setPanel(pending.value);
        setNotice("");
    }, [decision, menus, sourceSignature]);
    const chapters = extras.Chapters ?? [];
    const currentChapter = chapters.reduce(
        (n, c, i) => ((c.StartPositionTicks ?? 0) / 1e7 <= position ? i : n),
        0,
    );
    const tpSets = extras.Trickplay ?? {},
        tpWidths = decision ? tpSets[decision.source.Id] : undefined;
    const validTrickplay = Object.values(tpWidths ?? {})
        .filter(
            (t) =>
                t.Width > 0 &&
                t.Width <= 4096 &&
                t.Height > 0 &&
                t.Height <= 4096 &&
                t.TileWidth > 0 &&
                t.TileWidth <= 100 &&
                t.TileHeight > 0 &&
                t.TileHeight <= 100 &&
                t.Interval > 0 &&
                t.ThumbnailCount > 0,
        )
        .sort((a, b) => a.Width - b.Width);
    const tp =
        validTrickplay.find((t) => t.Width >= 160) ?? validTrickplay.at(-1);
    const frame =
        tp && seekPreview !== undefined
            ? Math.min(
                  tp.ThumbnailCount - 1,
                  Math.floor((Math.max(0, seekPreview) * 1000) / tp.Interval),
              )
            : undefined;
    const size = {
        System: 1,
        Small: 0.75,
        Normal: 1,
        Large: 1.25,
        ExtraLarge: 1.5,
    }[preferences.subtitleSize ?? "System"];
    const captionClass = `caption-${preferences.subtitleBackground ?? "System"}`;
    useEffect(() => {
        const player = root.current,
            media = video.current;
        if (!player || !media) return;
        let frame: number | undefined;
        const overlays = Array.from(
            player.querySelectorAll<HTMLElement>(
                ".player-controls > .transport, .player-timeline, .track-controls, .skip-segment, .next-episode, .trickplay-preview",
            ),
        );
        const measure = () => {
            const bounds = media.getBoundingClientRect();
            const transport = player.querySelector<HTMLElement>(
                ".player-controls > .transport",
            );
            const controlsTop =
                transport && transport.getClientRects().length && bounds.height
                    ? Math.max(
                          0,
                          Math.min(
                              100,
                              ((transport.getBoundingClientRect().top -
                                  bounds.top) /
                                  bounds.height) *
                                  100,
                          ),
                      )
                    : 100;
            player.style.setProperty("--controls-top", `${controlsTop}%`);
            const occupied = overlays
                .filter(
                    (element) =>
                        element.getClientRects().length &&
                        getComputedStyle(element).visibility !== "hidden",
                )
                .map((element) => element.getBoundingClientRect())
                .filter(
                    (rect) =>
                        rect.height > 0 &&
                        rect.width > 0 &&
                        rect.bottom > bounds.top &&
                        rect.top < bounds.bottom,
                );
            if (!occupied.length || !bounds.height)
                safeCaptionLine.current = null;
            else {
                const edge = Math.min(...occupied.map((rect) => rect.top));
                const rootFont =
                    parseFloat(
                        getComputedStyle(document.documentElement).fontSize,
                    ) || 16;
                const font =
                    preferences.subtitleSize &&
                    preferences.subtitleSize !== "System"
                        ? rootFont * 1.3 * size
                        : Math.max(16, bounds.height * 0.05);
                const rows = Math.max(
                    1,
                    ...Array.from(
                        media.textTracks[0]?.activeCues ?? [],
                        (cue) =>
                            cue instanceof VTTCue
                                ? cue.text.split("\n").length
                                : 1,
                    ),
                );
                // Reserve the current caption's line box plus breathing room.
                // Overlay rectangles already include the layout's safe-area insets.
                const margin =
                    font * 1.3 * rows + Math.max(8, bounds.height * 0.01);
                safeCaptionLine.current = Math.max(
                    0,
                    Math.min(
                        95,
                        ((edge - bounds.top - margin) / bounds.height) * 100,
                    ),
                );
            }
            applyCaptions();
        };
        const schedule = () => {
            if (frame !== undefined) cancelAnimationFrame(frame);
            frame = requestAnimationFrame(measure);
        };
        const observer =
            typeof ResizeObserver === "undefined"
                ? undefined
                : new ResizeObserver(schedule);
        observer?.observe(media);
        for (const overlay of overlays) observer?.observe(overlay);
        media.textTracks[0]?.addEventListener("cuechange", schedule);
        window.addEventListener("resize", schedule);
        measure();
        return () => {
            if (frame !== undefined) cancelAnimationFrame(frame);
            observer?.disconnect();
            media.textTracks[0]?.removeEventListener("cuechange", schedule);
            window.removeEventListener("resize", schedule);
        };
    }, [
        nextEligible,
        segmentKey,
        seekPreview,
        controls,
        panel,
        size,
        preferences.subtitleSize,
        subtitleBlob,
    ]);
    function info() {
        // Only the current decision: a pending negotiation shows no previous stream's data.
        const d = decision;
        if (!d)
            return (
                <div
                    className="playback-info"
                    data-scroll
                    data-panel-first
                    tabIndex={0}
                    role="region"
                    aria-label="Playback info details"
                >
                    <p className="playback-info-summary" role="status">
                        Preparing the new stream. Its details appear here when
                        it is ready.
                    </p>
                </div>
            );
        const method = playbackMethod(d.method, d.url);
        const source = d.source;
        const sourceStreams = source.MediaStreams ?? [];
        const codes = d.method === "Transcode" ? transcodeReasons(d.url) : [];
        const reasons = [
            ...new Set(
                codes.map((code) =>
                    // Core wording names "this device"; on the web the decoder is the browser.
                    transcodeReason(code, preferences.bitrateMbps ?? undefined)
                        .replace(/\bThis device\b/g, "This browser")
                        .replace(/\bthis device\b/g, "this browser"),
                ),
            ),
        ];
        const videoStream = sourceStreams.find((s) => s.Type === "Video");
        const audioStream = sourceStreams.find(
            (s) => s.Type === "Audio" && s.Index === audioIndex,
        );
        const notReported = (v: unknown) =>
            v === undefined || v === null || v === ""
                ? "Not reported"
                : String(v);
        const resolution = (s?: Stream) =>
            s?.Width && s?.Height ? `${s.Width}×${s.Height}` : "Not reported";
        const rows = (entries: Array<[string, string]>) => (
            <dl>
                {entries.map(([k, v]) => (
                    <div key={k}>
                        <dt>{k}</dt>
                        <dd>{v}</dd>
                    </div>
                ))}
            </dl>
        );
        const subtitles = subtitleDelivery(source, subtitleIndex);
        const kind = conversionKind(
            d.method,
            d.url,
            codes,
            subtitles === "Burned in",
        );
        // Plain name only ("HEVC"), without the raw codec id codecName appends.
        const format = codecName(videoStream?.Codec).replace(/ \(.*\)$/, "");
        const summary = conversionSummary(kind, codes, {
            videoFormat: format === "Not reported" ? undefined : format,
            qualityMbps: preferences.bitrateMbps ?? undefined,
        });
        const remedies = conversionRemedies(
            kind,
            browserFamily(navigator.userAgent),
        );
        const browserSteps = kind === "video" && chromium();
        return (
            <div
                className="playback-info"
                data-scroll
                data-panel-first
                tabIndex={0}
                role="region"
                aria-label="Playback info details"
            >
                <p className="playback-info-summary">{summary}</p>
                {remedies.length > 0 && (
                    <section aria-label="What you can do">
                        <h3>What you can do</h3>
                        <ul>
                            {remedies.map((r) => (
                                <li key={r}>{r}</li>
                            ))}
                        </ul>
                    </section>
                )}
                <section aria-label="Playback">
                    <h3>Playback</h3>
                    {rows([
                        ["Subtitles", subtitles],
                        ["Speed", rateLabel(speed)],
                    ])}
                </section>
                <details className="technical-details">
                    <summary>Technical details</summary>
                    {rows([["Method", method]])}
                    {method !== "Direct play" && method !== "Not reported" && (
                        <section aria-label="Why the server converts">
                            <h3>Why the server converts</h3>
                            {reasons.length ? (
                                <ul>
                                    {reasons.map((r) => (
                                        <li key={r}>{r}</li>
                                    ))}
                                </ul>
                            ) : (
                                <p>
                                    The server did not say why it is converting.
                                </p>
                            )}
                            {browserSteps && (
                                <>
                                    <p>
                                        Chrome or Edge may be able to decode it
                                        with hardware help. Try these steps in
                                        order:
                                    </p>
                                    <ol>
                                        {CHROMIUM_DECODE_STEPS.map((step) => (
                                            <li key={step}>{step}</li>
                                        ))}
                                    </ol>
                                </>
                            )}
                        </section>
                    )}
                    <section aria-label="Reported by the server">
                        <h3>Reported by the server</h3>
                        <p className="muted">
                            The server may describe the converted stream rather
                            than the file.
                        </p>
                        {rows([
                            ["Container", notReported(source.Container)],
                            ...(d.method === "Transcode"
                                ? ([
                                      [
                                          "Stream container",
                                          notReported(
                                              source.TranscodingContainer,
                                          ),
                                      ],
                                  ] as Array<[string, string]>)
                                : []),
                            ["Bitrate", formatBitrate(source.Bitrate)],
                            ["Video codec", notReported(videoStream?.Codec)],
                            ["Video resolution", resolution(videoStream)],
                            [
                                "Video bitrate",
                                formatBitrate(videoStream?.BitRate),
                            ],
                            ["Audio codec", notReported(audioStream?.Codec)],
                            [
                                "Audio channels",
                                notReported(audioStream?.Channels),
                            ],
                            [
                                "Audio bitrate",
                                formatBitrate(audioStream?.BitRate),
                            ],
                        ])}
                    </section>
                    <section aria-label="Received by this browser">
                        <h3>Received by this browser</h3>
                        {!received.video && !received.audio && (
                            <p className="muted">
                                This browser plays this stream itself and does
                                not report the formats it received.
                            </p>
                        )}
                        {rows([
                            ["Video format", codecName(received.video)],
                            ["Resolution", videoSize || "Not reported"],
                            ["Audio format", codecName(received.audio)],
                        ])}
                    </section>
                </details>
            </div>
        );
    }
    const preview = seekPreview !== undefined && tp && frame !== undefined && (
        <aside
            className="trickplay-preview"
            aria-label={`Seek preview ${clock(seekPreview)}`}
        >
            <div
                style={{
                    width: tp.Width,
                    height: tp.Height,
                    overflow: "hidden",
                    position: "relative",
                }}
            >
                <MediaImage
                    client={client}
                    path={`Videos/${encode(item.Id)}/Trickplay/${tp.Width}/${Math.floor(frame / (tp.TileWidth * tp.TileHeight))}.jpg?mediaSourceId=${encode(decision!.source.Id)}`}
                    style={{
                        position: "absolute",
                        maxWidth: "none",
                        width: tp.Width * tp.TileWidth,
                        height: tp.Height * tp.TileHeight,
                        left: -(frame % tp.TileWidth) * tp.Width,
                        top:
                            -Math.floor(
                                (frame % (tp.TileWidth * tp.TileHeight)) /
                                    tp.TileWidth,
                            ) * tp.Height,
                    }}
                />
            </div>
            <p>{clock(seekPreview)}</p>
        </aside>
    );
    return (
        <section
            ref={root}
            className={`player player-overlay ${captionClass} caption-size-${preferences.subtitleSize ?? "System"} ${controls ? "controls-visible" : "controls-hidden"} ${orientation ? "orientation-wide" : ""}`}
            style={{ "--caption-scale": size } as React.CSSProperties}
            aria-label={`Playing ${item.Name}`}
            onPointerMove={() => {
                lastActivity.current = Date.now();
                if (!controls) setControls(true);
            }}
            onKeyDownCapture={(e) => {
                lastActivity.current = Date.now();
                if ((e.key === "Escape" || e.key === "Backspace") && !panel) {
                    e.preventDefault();
                    e.stopPropagation();
                    if (!e.repeat) back();
                }
                if (e.key === "MediaPlayPause") {
                    e.preventDefault();
                    e.stopPropagation();
                    toggle();
                }
            }}
        >
            <video
                ref={video}
                data-autofocus
                playsInline
                crossOrigin="anonymous"
                tabIndex={0}
                aria-label={`${item.Name} video. Enter shows controls. Left and right seek.`}
                onKeyDown={surfaceKey}
                onClick={() => (controls ? hide() : reveal(false))}
            >
                {subtitleBlob && (
                    <track
                        key={subtitleBlob}
                        kind="subtitles"
                        src={subtitleBlob}
                        label={selectedSubtitle?.DisplayTitle ?? "Subtitles"}
                        srcLang={selectedSubtitle?.Language ?? "en"}
                        default
                        onLoad={applyCaptions}
                        onError={() => retrySubtitle.current?.()}
                    />
                )}
            </video>
            <div className="playback-feedback" role="status">
                {error ||
                    reportError ||
                    notice ||
                    (buffering ? "Preparing playback…" : speedNote)}
            </div>
            {reportError && !error && (
                <button
                    className="report-retry"
                    onClick={() => report("progress")}
                >
                    Retry reporting
                </button>
            )}
            {error && (
                <div className="player-error">
                    <button
                        onClick={() => {
                            if (completionError) {
                                completing.current = false;
                                setCompletionError(false);
                                void complete(completionNext.current, true);
                            } else
                                change(
                                    desired.current.audio,
                                    desired.current.subtitle,
                                    viewing.current.position,
                                    true,
                                );
                        }}
                    >
                        Retry playback
                    </button>
                    <button onClick={() => leave(true)}>Back to details</button>
                </div>
            )}
            <div className="sr-only" aria-live="polite" aria-atomic="true">
                {announcement}
            </div>
            {noticeVisible && shownNotice && (
                <div
                    className="conversion-notice"
                    role="button"
                    aria-label={`${shownNotice.title}. ${shownNotice.reason} Opens Playback info.`}
                    style={
                        noticeTop !== undefined ? { top: noticeTop } : undefined
                    }
                    // Never takes keyboard focus: a press opens Playback info and
                    // the opener stays whatever the viewer had focused.
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={(e) => {
                        e.stopPropagation();
                        openPanel(
                            "Info",
                            (document.activeElement as HTMLElement | null) ??
                                video.current ??
                                undefined,
                        );
                    }}
                >
                    <p className="conversion-notice-title">
                        {shownNotice.title}
                    </p>
                    <p className="conversion-notice-line">
                        {shownNotice.reason}{" "}
                        {BROWSER_NOTICE_ACTIONS[noticeInput]}
                    </p>
                </div>
            )}
            {nextEligible && !panel && (
                <aside className="next-episode" aria-label="Next episode">
                    <h2>
                        Next episode
                        {!reader ? (
                            <span aria-hidden="true">
                                {" "}
                                ·{" "}
                                {credits
                                    ? countdown
                                    : Math.max(
                                          0,
                                          Math.ceil(duration - position),
                                      )}{" "}
                                s left
                            </span>
                        ) : null}
                    </h2>
                    <p>{next?.Name}</p>
                    <button className="primary" onClick={() => complete(next)}>
                        Play now
                    </button>
                    <button
                        onClick={() => {
                            setCancelNext(true);
                            completionNext.current = null;
                            if (
                                completionDeferred.current &&
                                viewing.current.stopped
                            ) {
                                void leave();
                                return;
                            }
                            video.current?.focus();
                        }}
                    >
                        Cancel next episode
                    </button>
                </aside>
            )}
            {segment &&
                !panel &&
                (preferences.segments?.[segment.Type as SegmentType] ??
                    "Ask") === "Ask" &&
                !skipUsed.current.has(segmentKey) && (
                    <button
                        className="skip-segment"
                        data-skip="true"
                        onClick={skip}
                    >
                        Skip{" "}
                        {segment.Type === "Outro"
                            ? "credits"
                            : segment.Type.toLowerCase()}
                    </button>
                )}
            {controls && (
                <div
                    className="player-controls"
                    onClick={(e) => e.stopPropagation()}
                >
                    <div className="player-heading">
                        <div>
                            <p className="eyebrow">
                                {item.SeriesName ?? "NOW PLAYING"}
                            </p>
                            <h1>{item.Name}</h1>
                        </div>
                        <button onClick={() => leave()}>
                            Stop and go back
                        </button>
                    </div>
                    <div className="transport">
                        <button
                            disabled={
                                (!decision && !viewing.current.decision) ||
                                !!error
                            }
                            onClick={() => seekStep(-10)}
                            aria-label="Back 10 seconds"
                        >
                            <span className="seek-long" aria-hidden="true">
                                Back 10 seconds
                            </span>
                            <span className="seek-short" aria-hidden="true">
                                −10 s
                            </span>
                        </button>
                        <button
                            ref={playButton}
                            className="primary"
                            disabled={
                                (!decision && !viewing.current.decision) ||
                                !!error
                            }
                            onClick={toggle}
                        >
                            {paused ? "Play" : "Pause"}
                        </button>
                        <button
                            disabled={
                                (!decision && !viewing.current.decision) ||
                                !!error
                            }
                            onClick={() => seekStep(10)}
                            aria-label="Forward 10 seconds"
                        >
                            <span className="seek-long" aria-hidden="true">
                                Forward 10 seconds
                            </span>
                            <span className="seek-short" aria-hidden="true">
                                +10 s
                            </span>
                        </button>
                    </div>
                    <div className="player-timeline">
                        <button
                            ref={positionButton}
                            className="current-position"
                            onClick={(e) => openPanel("Seek", e.currentTarget)}
                            aria-label={`Current position ${clock(position)}${duration ? ` of ${clock(duration)}` : ""}. Seek`}
                        >
                            {clock(position)}
                            {duration ? ` / ${clock(duration)}` : ""}
                        </button>
                        <input
                            id="seek"
                            aria-label="Position"
                            type="range"
                            min="0"
                            max={duration || 1}
                            step="any"
                            value={Math.min(
                                seekPreview ?? position,
                                duration || 1,
                            )}
                            onChange={(e) => setSeekPreview(+e.target.value)}
                            onPointerUp={(e) =>
                                seek(+(e.target as HTMLInputElement).value)
                            }
                            onKeyUp={(e) => {
                                if (
                                    [
                                        "ArrowLeft",
                                        "ArrowRight",
                                        "Home",
                                        "End",
                                        "PageUp",
                                        "PageDown",
                                    ].includes(e.key)
                                )
                                    seek(
                                        e.key === "End"
                                            ? duration
                                            : e.key === "Home"
                                              ? 0
                                              : +(e.target as HTMLInputElement)
                                                    .value,
                                    );
                            }}
                            aria-valuetext={`${clock(seekPreview ?? position)}${duration ? ` of ${clock(duration)}` : ""}`}
                            disabled={!decision || !duration}
                        />
                    </div>
                    <div className="track-controls">
                        <button
                            aria-label="Audio"
                            onClick={(e) => openPanel("Audio", e.currentTarget)}
                            disabled={!!error}
                        >
                            Audio
                        </button>
                        <button
                            aria-label="Subtitles"
                            onClick={(e) =>
                                openPanel("Subtitles", e.currentTarget)
                            }
                            disabled={!!error}
                        >
                            Subtitles
                        </button>
                        <button
                            onClick={(e) =>
                                openPanel("Chapters", e.currentTarget)
                            }
                            disabled={!chapters.length}
                        >
                            Chapters
                        </button>
                        <button
                            aria-label={`Speed, ${rateLabel(speed)}`}
                            onClick={(e) => openPanel("Speed", e.currentTarget)}
                            disabled={
                                (!decision && !viewing.current.decision) ||
                                !!error
                            }
                        >
                            Speed {rateLabel(speed)}
                        </button>
                        <button
                            onClick={(e) => openPanel("Info", e.currentTarget)}
                            disabled={!decision && !viewing.current.decision}
                        >
                            Playback info
                        </button>
                        <label>
                            Volume
                            <input
                                aria-label="Volume"
                                type="range"
                                min="0"
                                max="1"
                                step=".1"
                                value={volume}
                                onChange={(e) => {
                                    setVolume(+e.target.value);
                                    video.current!.volume = +e.target.value;
                                }}
                            />
                        </label>
                        <button
                            onClick={() => {
                                void root.current
                                    ?.requestFullscreen?.()
                                    .catch(() =>
                                        setNotice("Fullscreen is unavailable."),
                                    );
                            }}
                        >
                            Full screen
                        </button>
                        <button
                            className="orientation-control"
                            onClick={async () => {
                                const target = orientation
                                    ? "portrait"
                                    : "landscape";
                                const screenOrientation =
                                    screen.orientation as ScreenOrientation & {
                                        lock?: (value: string) => Promise<void>;
                                    };
                                if (!screenOrientation?.lock) {
                                    setNotice(
                                        "Rotate your device to change orientation.",
                                    );
                                    return;
                                }
                                try {
                                    if (
                                        !document.fullscreenElement &&
                                        root.current?.requestFullscreen
                                    )
                                        await root.current.requestFullscreen();
                                    await screenOrientation.lock(target);
                                    orientationOwned.current = true;
                                    setOrientation(target === "landscape");
                                } catch {
                                    setNotice(
                                        "Rotate your device to change orientation.",
                                    );
                                }
                            }}
                        >
                            {orientation ? "Portrait" : "Landscape"}
                        </button>
                    </div>
                </div>
            )}
            {!panel && preview}
            <dialog
                ref={panelElement}
                className="player-panel"
                aria-label={panel ? panelTitle(panel) : "Player controls"}
                onCancel={(e) => {
                    e.preventDefault();
                    closePanel();
                }}
                onClick={(e) => {
                    if (e.target === e.currentTarget) closePanel();
                }}
                onKeyDownCapture={(e) => {
                    if (e.key === "Escape" || e.key === "Backspace") {
                        e.preventDefault();
                        e.stopPropagation();
                        if (!e.repeat) closePanel();
                    } else if (e.key.startsWith("Arrow")) {
                        e.preventDefault();
                        e.stopPropagation();
                        // Playback info's text scrolls with Up/Down before focus moves on.
                        const scroller = (
                            document.activeElement as HTMLElement | null
                        )?.closest<HTMLElement>("[data-scroll]");
                        if (
                            scroller &&
                            e.currentTarget.contains(scroller) &&
                            (e.key === "ArrowDown" || e.key === "ArrowUp")
                        ) {
                            const down = e.key === "ArrowDown";
                            if (
                                down
                                    ? scroller.scrollTop +
                                          scroller.clientHeight <
                                      scroller.scrollHeight - 1
                                    : scroller.scrollTop > 0
                            ) {
                                scroller.scrollTop += down ? 64 : -64;
                                return;
                            }
                        }
                        if (e.repeat) return;
                        const nodes = [
                            ...e.currentTarget.querySelectorAll<HTMLElement>(
                                "button:not([disabled]),input:not([disabled]),[data-scroll],summary",
                            ),
                        ].filter((n) => n.getClientRects().length);
                        const current = nodes.indexOf(
                            document.activeElement as HTMLElement,
                        );
                        const offset =
                            e.key === "ArrowUp" || e.key === "ArrowLeft"
                                ? -1
                                : 1;
                        const target =
                            nodes[
                                Math.max(
                                    0,
                                    Math.min(
                                        nodes.length - 1,
                                        current + offset,
                                    ),
                                )
                            ];
                        target?.focus();
                        target?.scrollIntoView({ block: "nearest" });
                    }
                }}
            >
                <h2>{panel && panelTitle(panel)}</h2>
                {panel === "Speed" && (
                    <div>
                        <div role="radiogroup" aria-label="Playback speed">
                            {SPEEDS.map((rate) => (
                                <button
                                    key={rate}
                                    role="radio"
                                    aria-checked={speed === rate}
                                    onClick={() => chooseSpeed(rate)}
                                >
                                    {rate === 1
                                        ? "1× (normal)"
                                        : rateLabel(rate)}
                                </button>
                            ))}
                        </div>
                        <p className="muted">
                            Pitch is preserved. Speed applies to this video
                            only.
                            {decision?.method === "Transcode"
                                ? " The server is converting this video, so faster speeds may pause to buffer."
                                : ""}
                        </p>
                        <p role="status">{speedMessage}</p>
                    </div>
                )}
                {panel === "Info" && info()}
                {panel === "Seek" ? (
                    <div>
                        {preview}
                        <p>
                            Position {clock(position)}
                            {duration ? ` of ${clock(duration)}` : ""}
                        </p>
                        <div className="transport">
                            <button
                                data-panel-first
                                onClick={() => seekStep(-10)}
                            >
                                Back 10 seconds
                            </button>
                            <button onClick={() => seekStep(10)}>
                                Forward 10 seconds
                            </button>
                        </div>
                    </div>
                ) : null}
                {(panel === "Audio" || panel === "Subtitles") && (
                    <div role="radiogroup" aria-label={`${panel} tracks`}>
                        <button
                            role="radio"
                            aria-label="Off"
                            aria-checked={
                                panel === "Audio"
                                    ? video.current?.muted || audioIndex === -1
                                    : subtitleIndex === -1
                            }
                            onClick={() =>
                                pickTrack(panel as "Audio" | "Subtitles", -1)
                            }
                        >
                            Off
                        </button>
                        {(panel === "Audio" ? audio : subtitles).map((s, i) => (
                            <button
                                key={s.Index}
                                role="radio"
                                aria-label={labels[i]}
                                aria-checked={
                                    panel === "Audio"
                                        ? audioIndex === s.Index &&
                                          !video.current?.muted
                                        : subtitleIndex === s.Index
                                }
                                onClick={() =>
                                    pickTrack(
                                        panel as "Audio" | "Subtitles",
                                        s.Index,
                                    )
                                }
                            >
                                {labels[i]}
                            </button>
                        ))}
                    </div>
                )}
                {panel === "Subtitles" && (
                    <section aria-label="Subtitle timing">
                        <h3>Subtitle timing</h3>
                        {burned ? (
                            <p>
                                These subtitles are encoded into the video.
                                Timing and appearance cannot be changed.
                            </p>
                        ) : (
                            <p>
                                Positive delay shows captions later.{" "}
                                {subtitleDelay === 0
                                    ? "No delay"
                                    : `${subtitleDelay} milliseconds`}
                            </p>
                        )}
                        <div className="subtitle-delay">
                            <button
                                disabled={
                                    burned ||
                                    !textSubtitle ||
                                    subtitleDelay <= -10000
                                }
                                onClick={() =>
                                    setSubtitleDelay((v) =>
                                        Math.max(-10000, v - 100),
                                    )
                                }
                            >
                                Earlier
                            </button>
                            <button
                                disabled={burned || !textSubtitle}
                                onClick={() => setSubtitleDelay(0)}
                            >
                                Reset
                            </button>
                            <button
                                disabled={
                                    burned ||
                                    !textSubtitle ||
                                    subtitleDelay >= 10000
                                }
                                onClick={() =>
                                    setSubtitleDelay((v) =>
                                        Math.min(10000, v + 100),
                                    )
                                }
                            >
                                Later
                            </button>
                        </div>
                        <label className="reader-choice">
                            <input
                                type="checkbox"
                                checked={reader}
                                onChange={(e) => setReader(e.target.checked)}
                            />
                            Keep controls visible and require Play now for next
                            episode
                        </label>
                        <p className="muted">
                            Browsers do not expose a reliable screen-reader or
                            system-caption preference. Enable this for manual
                            continuation.
                        </p>
                    </section>
                )}
                {panel === "Chapters" && (
                    <div className="chapter-list">
                        {chapters.map((c, i) => (
                            <button
                                key={i}
                                aria-current={
                                    i === currentChapter ? "true" : undefined
                                }
                                onClick={() => {
                                    seek(
                                        Math.max(
                                            0,
                                            (c.StartPositionTicks ?? 0) / 1e7,
                                        ),
                                    );
                                    closePanel();
                                }}
                            >
                                {c.ImageTag && (
                                    <MediaImage
                                        client={client}
                                        path={`Items/${encode(item.Id)}/Images/Chapter/${i}?tag=${encode(c.ImageTag)}&maxWidth=320`}
                                    />
                                )}
                                <span>
                                    {c.Name || `Chapter ${i + 1}`} ·{" "}
                                    {clock((c.StartPositionTicks ?? 0) / 1e7)}
                                </span>
                            </button>
                        ))}
                    </div>
                )}
                <button
                    className="primary"
                    data-panel-first
                    onClick={closePanel}
                >
                    Done
                </button>
            </dialog>
            {nextState === "failed" && ended.current && (
                <p role="status">
                    Next episode lookup failed. Return to details and try again.
                </p>
            )}
        </section>
    );
}
