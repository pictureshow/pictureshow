/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { useEffect, useRef, useState } from "react";
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
} from "@pictureshow/core";
import "./player.css";

type SegmentType = "Intro" | "Recap" | "Preview" | "Commercial" | "Outro";
export type PlayerPreferences = {
    bitrateMbps?: number | null;
    subtitleSize?: "System" | "Small" | "Normal" | "Large" | "ExtraLarge";
    subtitleBackground?: "System" | "None" | "Shadow" | "Box";
    segments?: Partial<
        Record<SegmentType, "Ask" | "Auto" | "Automatic" | "Off">
    >;
    screenReader?: boolean;
};
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
};
type Viewing = {
    decision?: Decision;
    position: number;
    offset?: number;
    audio?: number;
    subtitle?: number;
    started: boolean;
    stopped: boolean;
    queue: Promise<unknown>;
};
type Panel = "Audio" | "Subtitles" | "Chapters" | "Seek";
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
export function playerTrackLabels(streams: Stream[]): string[] {
    const labels = streams.map((s, i) => {
        const extra = s as Stream & {
            Title?: string;
            IsDefault?: boolean;
            IsForced?: boolean;
            IsHearingImpaired?: boolean;
            IsExternalUrl?: boolean;
        };
        const lang =
            s.Language && s.Language !== "und"
                ? ({
                      eng: "English",
                      spa: "Spanish",
                      fra: "French",
                      fre: "French",
                      deu: "German",
                      ger: "German",
                      jpn: "Japanese",
                      zho: "Chinese",
                      chi: "Chinese",
                      kor: "Korean",
                      por: "Portuguese",
                      ita: "Italian",
                      rus: "Russian",
                      ara: "Arabic",
                      hin: "Hindi",
                  }[s.Language] ?? s.Language)
                : "";
        const channels =
            s.Type === "Audio"
                ? ({ 1: "mono", 2: "stereo", 6: "5.1", 8: "7.1" }[
                      s.Channels ?? 0
                  ] ?? (s.Channels ? `${s.Channels} channels` : ""))
                : "";
        const title = extra.Title ?? s.DisplayTitle ?? "";
        const format = [s.Codec?.toUpperCase() ?? "Unknown format", channels]
            .filter(Boolean)
            .join(" ");
        const parts = [lang || title || format];
        if (!parts[0].toLowerCase().includes(format.toLowerCase()))
            parts.push(format);
        if (
            lang &&
            title &&
            !title.toLowerCase().includes(lang.toLowerCase()) &&
            title.toLowerCase() !== format.toLowerCase()
        )
            parts.push(title);
        if (!lang) parts.push(`Track ${i + 1}`);
        const named = (word: string) =>
            new RegExp(`(?<!non[-–—])\\b${word}\\b`, "i").test(title);
        if (
            extra.IsDefault &&
            streams.filter((t) => (t as typeof extra).IsDefault).length === 1 &&
            streams.length > 1 &&
            !named("Default")
        )
            parts.push("Default");
        if (extra.IsForced && !named("Forced")) parts.push("Forced");
        if (extra.IsHearingImpaired && !named("SDH")) parts.push("SDH");
        if ((s.IsExternal || extra.IsExternalUrl) && !named("External"))
            parts.push("External");
        return parts.join(" · ");
    });
    return labels.map((label, i) =>
        labels.filter((v) => v === label).length > 1
            ? `${label} · Track ${i + 1}`
            : label,
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
    const video = useRef<HTMLVideoElement>(null),
        root = useRef<HTMLElement>(null),
        playButton = useRef<HTMLButtonElement>(null),
        positionButton = useRef<HTMLButtonElement>(null),
        panelElement = useRef<HTMLDialogElement>(null);
    const alive = useRef(true),
        leaving = useRef(false),
        replacing = useRef(true),
        pauseIntent = useRef(false),
        ended = useRef(false),
        pausedAtPhysicalEnd = useRef(false),
        completing = useRef(false),
        retries = useRef(0),
        lastActivity = useRef(Date.now()),
        panelOpener = useRef<HTMLElement | null>(null),
        pendingSeek = useRef<number | undefined>(undefined),
        previewTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
            undefined,
        ),
        cleanup = useRef<Promise<unknown>>(Promise.resolve());
    const viewing = useRef<Viewing>({
        position: start,
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
        [position, setPosition] = useState(start),
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
        safeCaptionLine = useRef(90),
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
              }
            | undefined
        >(undefined);
    const streams =
        decision?.source.MediaStreams ??
        item.MediaSources?.[0]?.MediaStreams ??
        [];
    const audioIndex =
        request.audio ??
        decision?.source.DefaultAudioStreamIndex ??
        streams.find((s) => s.Type === "Audio")?.Index ??
        -1;
    const subtitleIndex =
        request.subtitle ?? decision?.source.DefaultSubtitleStreamIndex ?? -1;
    const selectedSubtitle = streams.find(
        (s) => s.Type === "Subtitle" && s.Index === subtitleIndex,
    );
    const textSubtitle =
        !!selectedSubtitle &&
        ["srt", "vtt", "subrip", "mov_text", "webvtt"].includes(
            selectedSubtitle.Codec?.toLowerCase() ?? "",
        );
    const burned = subtitleIndex >= 0 && !textSubtitle;
    const report = (
        event: "start" | "progress",
        pausedState = pauseIntent.current,
    ) => {
        const s = viewing.current,
            d = s.decision;
        if (!d || s.stopped || (event === "progress" && !s.started)) return;
        const at = s.position,
            audio = s.audio,
            subtitle = s.subtitle,
            muted = video.current?.muted ?? false;
        s.queue = s.queue
            .catch(() => {})
            .then(() =>
                client.report(
                    d,
                    event,
                    at,
                    pausedState,
                    audio,
                    subtitle,
                    false,
                    false,
                    muted,
                ),
            )
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
        if (!el || !d || replacing.current) return s.position;
        const p =
            (d.method === "Transcode" ? (s.offset ?? 0) : 0) +
            (Number.isFinite(el.currentTime) ? el.currentTime : 0);
        s.position = Math.max(0, p);
        if (alive.current) setPosition(s.position);
        return s.position;
    };
    async function stop(failed = false, keepalive = false) {
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
            };
        const snapshot = stopSnapshot.current;
        s.queue = s.queue
            .catch(() => {})
            .then(async () => {
                try {
                    if (s.started && snapshot) {
                        await client.report(
                            snapshot.decision,
                            "stop",
                            snapshot.position,
                            true,
                            snapshot.audio,
                            snapshot.subtitle,
                            snapshot.failed,
                            keepalive,
                            snapshot.muted,
                        );
                        stopStages.current.reported = true;
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
                    : "The stop report could not reach your server. Resume may be out of date.";
                if (alive.current) setReportError(stopError.current);
            });
        return s.queue;
    }
    async function retryFinalReport() {
        const snapshot = stopSnapshot.current;
        if (!snapshot || !stopError.current) return;
        // An acknowledged Stop is never resent merely because ActiveEncodings cleanup failed.
        if (!stopStages.current.reported) {
            await client.report(
                snapshot.decision,
                "stop",
                snapshot.position,
                true,
                snapshot.audio,
                snapshot.subtitle,
                snapshot.failed,
                false,
                snapshot.muted,
            );
            stopStages.current.reported = true;
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
        pauseIntent.current = true;
        sample();
        video.current?.pause();
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
        setPanel(value);
        setControls(true);
        lastActivity.current = Date.now();
        if (value === "Seek") setSeekPreview(viewing.current.position);
    }
    function closePanel() {
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
        };
        setPosition(desired.current.position);
        if (replacing.current) return;
        replacing.current = true;
        sample();
        video.current?.pause();
        setRequest({ ...desired.current });
    }
    function seek(to: number) {
        if (completing.current || leaving.current) return;
        const at = Math.max(0, duration ? Math.min(duration, to) : to);
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
        if (
            viewing.current.decision?.method === "Transcode" &&
            pauseIntent.current &&
            duration > 0 &&
            at >= duration &&
            video.current &&
            Number.isFinite(video.current.duration) &&
            video.current.duration > 0
        ) {
            // Reaching a known physical playlist end while paused does not need an empty server reopen.
            // MSE may request a nonexistent segment at the exact exclusive end; hold the final frame.
            video.current.currentTime = Math.max(
                0,
                video.current.duration - 0.001,
            );
            pausedAtPhysicalEnd.current = true;
            viewing.current.position =
                (viewing.current.offset ?? 0) + video.current.duration;
            setPosition(viewing.current.position);
            report("progress", true);
        } else if (viewing.current.decision?.method === "Transcode")
            change(desired.current.audio, desired.current.subtitle, at);
        else if (video.current) {
            video.current.currentTime = at;
            viewing.current.position = at;
            setPosition(at);
            report("progress");
        }
    }
    function toggle() {
        if (completing.current || leaving.current) return;
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
        if (pauseIntent.current) el.pause();
        else
            void el.play().catch(() => {
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
    useEffect(() => {
        const hidden = () => {
            if (document.hidden) {
                pauseIntent.current = true;
                setPaused(true);
                setControls(true);
                sample();
                video.current?.pause();
                report("progress", true);
            }
        };
        document.addEventListener("visibilitychange", hidden);
        return () => document.removeEventListener("visibilitychange", hidden);
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
            seekbackward: () => seek(viewing.current.position - 10),
            seekforward: () => seek(viewing.current.position + 10),
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
    async function complete(nextItem?: Item | null, retry = false) {
        if (completing.current || leaving.current) return;
        completing.current = true;
        setError("");
        completionNext.current = nextItem;
        pauseIntent.current = true;
        sample();
        video.current?.pause();
        setNotice(
            nextItem ? "Opening next episode…" : "Saving completed playback…",
        );
        const barrier = (async () => {
            if (retry) await retryFinalReport();
            await stop();
            if (stopError.current) throw new Error(stopError.current);
            const fresh = await client.detail(item.Id);
            if (!fresh.UserData?.Played)
                await client.request(
                    `UserPlayedItems/${encode(item.Id)}?userId=${encode(client.account!.user.Id)}`,
                    undefined,
                    "POST",
                );
        })();
        if (!nextItem && onLeaving) {
            onLeaving(barrier, async () => {
                await retryFinalReport();
                const fresh = await client.detail(item.Id);
                if (!fresh.UserData?.Played)
                    await client.request(
                        `UserPlayedItems/${encode(item.Id)}?userId=${encode(client.account!.user.Id)}`,
                        undefined,
                        "POST",
                    );
            });
            return;
        }
        try {
            await barrier;
        } catch (e) {
            completing.current = false;
            setCompletionError(true);
            setError(
                e instanceof Error
                    ? e.message
                    : "Completed playback could not be saved. Retry completion or return to details.",
            );
            return;
        }
        if (!alive.current || leaving.current) return;
        if (nextItem && onNext) onNext(nextItem);
        else onBack(stopError.current || undefined);
    }
    async function physicalEnd() {
        if (ended.current || pauseIntent.current) return;
        ended.current = true;
        sample();
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
        if (reader && found && !cancelNext) {
            setNext(found);
            setNotice(
                "Playback finished. Choose Play now for the next episode.",
            );
            return;
        }
        await complete(found);
    }
    const physicalEndRef = useRef(physicalEnd);
    physicalEndRef.current = physicalEnd;
    useEffect(() => {
        const el = video.current!;
        let disposed = false,
            hls: Hls | undefined,
            current: Decision | undefined,
            hasPlayed = false,
            needsGesture = false,
            lastProgress = 0;
        let slow: ReturnType<typeof setTimeout> | undefined,
            deadline: ReturnType<typeof setTimeout> | undefined,
            directTimer: ReturnType<typeof setTimeout> | undefined;
        setError("");
        setNotice("Preparing playback…");
        setBuffering(true);
        setDecision(undefined);
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
            if (disposed || !current || replacing.current) return;
            sample();
            setPaused(pauseIntent.current);
            if (Date.now() - lastProgress >= 10000) {
                lastProgress = Date.now();
                report("progress");
            }
        }
        function playing() {
            if (!current || disposed) return;
            if (desired.current.revision !== request.revision) {
                setRequest({ ...desired.current });
                return;
            }
            hasPlayed = true;
            clearTimeout(directTimer);
            clearWaiting();
            setBuffering(false);
            setNotice("");
            replacing.current = false;
            if (pauseIntent.current) {
                el.pause();
                setPaused(true);
            } else setPaused(false);
            const s = viewing.current;
            if (!s.started) {
                s.started = true;
                report("start");
            } else report("progress");
        }
        function pause() {
            if (disposed || replacing.current) return;
            update();
            if (!el.ended) {
                setPaused(pauseIntent.current);
                if (pauseIntent.current) setControls(true);
                report("progress");
            }
        }
        function fail(terminal = false) {
            if (disposed || leaving.current) return;
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
            if (current.method === "DirectPlay")
                try {
                    el.currentTime = request.position;
                } catch {}
            const measured =
                (current.method === "Transcode" ? request.position : 0) +
                el.duration;
            if (plausibleDuration(measured)) setDuration(measured);
            if (pauseIntent.current) {
                replacing.current = false;
                setPaused(true);
                setBuffering(false);
                setNotice("");
                clearWaiting();
                clearTimeout(directTimer);
                el.pause();
                if (viewing.current.started) report("progress", true);
            } else
                void el.play().catch(() => {
                    needsGesture = true;
                    pauseIntent.current = true;
                    replacing.current = false;
                    setPaused(true);
                    setBuffering(false);
                    clearWaiting();
                    setNotice("Press Play to start playback.");
                });
        }
        const onError = () => fail();
        const onEnd = () => {
            if (!pauseIntent.current && !disposed)
                void physicalEndRef.current();
        };
        const pagehide = () => {
            sample();
            void stop(false, true);
        };
        el.addEventListener("loadedmetadata", metadata);
        el.addEventListener("timeupdate", update);
        el.addEventListener("playing", playing);
        el.addEventListener("pause", pause);
        el.addEventListener("ended", onEnd);
        el.addEventListener("error", onError);
        el.addEventListener("waiting", waiting);
        el.addEventListener("stalled", waiting);
        window.addEventListener("pagehide", pagehide);
        const timer = setInterval(update, 1000);
        waiting();
        void (async () => {
            await cleanup.current;
            const d = await client.prepare(
                item,
                probe,
                request.position,
                request.audio,
                request.subtitle,
                request.hls ||
                    (location.protocol === "https:" &&
                        !!localAddressSpace(client.server.url)),
                preferences.bitrateMbps ?? null,
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
            viewing.current.position = request.position;
            viewing.current.offset = request.position;
            pendingSeek.current = undefined;
            viewing.current.audio =
                request.audio ?? d.source.DefaultAudioStreamIndex;
            viewing.current.subtitle =
                request.subtitle ?? d.source.DefaultSubtitleStreamIndex ?? -1;
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
                    startPosition: 0,
                    loader: FetchLoader,
                    fetchSetup(context, init) {
                        return authenticatedMediaRequest(
                            client.account!,
                            context.url,
                            init,
                        );
                    },
                });
                hls.on(Hls.Events.ERROR, (_event, data) => {
                    if (data.fatal) fail();
                });
                hls.loadSource(d.url);
                hls.attachMedia(el);
            }
        })().catch((e) => {
            if (!disposed) {
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
            disposed = true;
            clearInterval(timer);
            clearWaiting();
            clearTimeout(directTimer);
            el.removeEventListener("loadedmetadata", metadata);
            el.removeEventListener("timeupdate", update);
            el.removeEventListener("playing", playing);
            el.removeEventListener("pause", pause);
            el.removeEventListener("ended", onEnd);
            el.removeEventListener("error", onError);
            el.removeEventListener("waiting", waiting);
            el.removeEventListener("stalled", waiting);
            window.removeEventListener("pagehide", pagehide);
            el.pause();
            hls?.destroy();
            el.removeAttribute("src");
            el.load();
            if (current)
                cleanup.current = cleanup.current
                    .catch(() => {})
                    .then(() => client.stopEncoding(current!))
                    .catch(() => {});
        };
    }, [request]);
    const subtitleUrl =
        decision && textSubtitle
            ? `Videos/${encode(item.Id)}/${encode(decision.source.Id)}/Subtitles/${subtitleIndex}/${decision.method === "Transcode" ? ticks(request.position) : 0}/Stream.vtt?copyTimestamps=false&addVttTimeMap=false`
            : undefined;
    const [subtitleBlob, setSubtitleBlob] = useState<string>();
    useEffect(() => {
        let live = true,
            object = "";
        setSubtitleBlob(undefined);
        if (subtitleUrl)
            void fetchMedia(client.account!, subtitleUrl)
                .then((r) => r.blob())
                .then((blob) => {
                    if (live) {
                        object = URL.createObjectURL(
                            new Blob([blob], { type: "text/vtt" }),
                        );
                        setSubtitleBlob(object);
                    }
                })
                .catch(() => {
                    if (live)
                        setNotice(
                            "Subtitles could not be loaded. Try another track.",
                        );
                });
        return () => {
            live = false;
            if (object) URL.revokeObjectURL(object);
        };
    }, [subtitleUrl]);
    function applyCaptions() {
        const track = video.current?.textTracks[0];
        if (!track) return;
        track.mode = "showing";
        for (const cue of Array.from(track.cues ?? [])) {
            let source = cues.current.get(cue);
            if (!source) {
                source = {
                    start: cue.startTime,
                    end: cue.endTime,
                    ...(cue instanceof VTTCue
                        ? { line: cue.line, snap: cue.snapToLines }
                        : {}),
                };
                cues.current.set(cue, source);
            }
            cue.startTime = Math.max(0, source.start + delayRef.current / 1000);
            cue.endTime = Math.max(
                cue.startTime + 0.001,
                source.end + delayRef.current / 1000,
            );
            if (cue instanceof VTTCue) {
                cue.snapToLines =
                    safeCaptionLine.current >= 90
                        ? (source.snap ?? true)
                        : false;
                cue.line =
                    safeCaptionLine.current >= 90
                        ? (source.line ?? "auto")
                        : safeCaptionLine.current;
            }
        }
    }
    useEffect(() => {
        delayRef.current = subtitleDelay;
        applyCaptions();
    }, [subtitleDelay, controls, panel, seekPreview, subtitleBlob]);
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
        const t = setInterval(
            () => setCountdown((n) => Math.max(0, n - 1)),
            1000,
        );
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
            !buffering &&
            !panel &&
            seekPreview === undefined
        )
            void complete(next);
    }, [
        countdown,
        nextEligible,
        reader,
        paused,
        buffering,
        panel,
        seekPreview,
    ]);
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
            seek(
                (pendingSeek.current ?? viewing.current.position) +
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
    const audio = streams.filter((s) => s.Type === "Audio"),
        subtitles = streams.filter((s) => s.Type === "Subtitle"),
        labels = playerTrackLabels(panel === "Audio" ? audio : subtitles);
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
        safeCaptionLine.current =
            nextEligible || segment || seekPreview !== undefined
                ? 30
                : controls || panel
                  ? 55
                  : 90;
        applyCaptions();
    }, [nextEligible, segmentKey, seekPreview, controls, panel]);
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
                    />
                )}
            </video>
            <div className="playback-feedback" role="status">
                {error ||
                    reportError ||
                    notice ||
                    (buffering ? "Preparing playback…" : "")}
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
                            onClick={() => seek(viewing.current.position - 10)}
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
                            onClick={() => seek(viewing.current.position + 10)}
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
                            disabled={!decision && !viewing.current.decision}
                        >
                            Audio
                        </button>
                        <button
                            aria-label="Subtitles"
                            onClick={(e) =>
                                openPanel("Subtitles", e.currentTarget)
                            }
                            disabled={!decision && !viewing.current.decision}
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
                aria-label={panel ?? "Player controls"}
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
                        if (e.repeat) return;
                        const nodes = [
                            ...e.currentTarget.querySelectorAll<HTMLElement>(
                                "button:not([disabled]),input:not([disabled])",
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
                <h2>{panel}</h2>
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
                                onClick={() =>
                                    seek(viewing.current.position - 10)
                                }
                            >
                                Back 10 seconds
                            </button>
                            <button
                                onClick={() =>
                                    seek(viewing.current.position + 10)
                                }
                            >
                                Forward 10 seconds
                            </button>
                        </div>
                    </div>
                ) : null}
                {(panel === "Audio" || panel === "Subtitles") && (
                    <div role="radiogroup" aria-label={`${panel} tracks`}>
                        <button
                            role="radio"
                            aria-checked={
                                panel === "Audio"
                                    ? video.current?.muted || audioIndex === -1
                                    : subtitleIndex === -1
                            }
                            onClick={() => {
                                if (panel === "Audio") {
                                    if (video.current)
                                        video.current.muted = true;
                                    viewing.current.audio = -1;
                                    report("progress");
                                    setNotice("Audio off");
                                } else change(desired.current.audio, -1);
                                closePanel();
                            }}
                        >
                            Off
                        </button>
                        {(panel === "Audio" ? audio : subtitles).map((s, i) => (
                            <button
                                key={s.Index}
                                role="radio"
                                aria-checked={
                                    panel === "Audio"
                                        ? audioIndex === s.Index &&
                                          !video.current?.muted
                                        : subtitleIndex === s.Index
                                }
                                onClick={() => {
                                    if (panel === "Audio") {
                                        if (video.current)
                                            video.current.muted = false;
                                        change(
                                            s.Index,
                                            desired.current.subtitle,
                                        );
                                    } else
                                        change(desired.current.audio, s.Index);
                                    closePanel();
                                }}
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
