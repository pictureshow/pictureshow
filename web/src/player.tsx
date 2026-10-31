/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { useEffect, useRef, useState } from "react";
import type Hls from "hls.js";
import {
    Jellyfin,
    ClientError,
    mediaUrl,
    authenticatedMediaRequest,
    fetchMedia,
    localAddressSpace,
    encode,
    ticks,
    type Item,
    type Decision,
} from "@pictureshow/core";
export function Player({
    client,
    item,
    start,
    onBack,
    onExpired,
}: {
    client: Jellyfin;
    item: Item;
    start: number;
    onBack: () => void;
    onExpired: (e: unknown) => void;
}) {
    const video = useRef<HTMLVideoElement>(null),
        session = useRef<
            | {
                  decision: Decision;
                  position: number;
                  started: boolean;
                  stopped: boolean;
                  queue: Promise<unknown>;
              }
            | undefined
        >(undefined);
    const [request, setRequest] = useState({
            position: start,
            audio: undefined as number | undefined,
            subtitle: -1,
            hls: false,
            revision: 0,
        }),
        [decision, setDecision] = useState<Decision>(),
        [error, setError] = useState(""),
        [notice, setNotice] = useState("Preparing playback…"),
        [paused, setPaused] = useState(true),
        [position, setPosition] = useState(start),
        [duration, setDuration] = useState((item.RunTimeTicks ?? 0) / 1e7),
        [volume, setVolume] = useState(1);
    const stopping = useRef<Promise<unknown>>(Promise.resolve());
    const leaving = useRef(false),
        switching = useRef(false);
    async function stop(failed = false, keepalive = false) {
        const s = session.current;
        if (!s || s.stopped) return stopping.current;
        s.stopped = true;
        stopping.current = s.queue
            .catch(() => {})
            .then(async () => {
                try {
                    if (s.started)
                        await client.report(
                            s.decision,
                            "stop",
                            s.position,
                            true,
                            request.audio,
                            request.subtitle,
                            failed,
                            keepalive,
                        );
                } finally {
                    if (!keepalive) await client.stopEncoding(s.decision);
                }
            })
            .catch(() => {
                setNotice(
                    "The stop report could not reach your server. Resume may be out of date.",
                );
            });
        return stopping.current;
    }
    async function leave() {
        if (leaving.current) return;
        leaving.current = true;
        video.current?.pause();
        await stop();
        onBack();
    }
    useEffect(() => {
        const handler = () => {
            void leave();
        };
        window.addEventListener("pictureshow-stop", handler);
        return () => window.removeEventListener("pictureshow-stop", handler);
    }, [request]);
    function switchStream(
        audio: number | undefined,
        subtitle: number,
        at = session.current?.position ?? position,
        forceHls = request.hls,
    ) {
        if (switching.current || leaving.current) return;
        switching.current = true;
        video.current?.pause();
        void stop().then(() =>
            setRequest({
                position: at,
                audio,
                subtitle,
                hls: forceHls,
                revision: request.revision + 1,
            }),
        );
    }
    useEffect(() => {
        const el = video.current!;
        let disposed = false,
            hls: Hls | undefined;
        let current: Decision | undefined,
            lastProgress = 0;
        let startup: ReturnType<typeof setTimeout> | undefined,
            hasPlayed = false,
            needsGesture = false;
        setError("");
        setNotice("Preparing playback…");
        setDecision(undefined);
        const probe = (mime: string) => el.canPlayType(mime) === "probably";
        function report(event: "start" | "progress") {
            const s = session.current;
            if (!s || s.stopped || (!s.started && event === "progress")) return;
            s.queue = s.queue
                .catch(() => {})
                .then(() =>
                    client.report(
                        s.decision,
                        event,
                        s.position,
                        el.paused,
                        request.audio,
                        request.subtitle,
                    ),
                )
                .catch((e) => {
                    if (!disposed)
                        setNotice(
                            "Playback reports are not reaching the server.",
                        );
                    if (e instanceof ClientError && e.kind === "EXPIRED")
                        onExpired(e);
                });
        }
        function update() {
            if (!current) return;
            const p =
                (current.method === "Transcode" ? request.position : 0) +
                (Number.isFinite(el.currentTime) ? el.currentTime : 0);
            const s = session.current;
            if (s && !s.stopped) s.position = p;
            if (!disposed) {
                setPosition(p);
                setPaused(el.paused);
            }
            if (Date.now() - lastProgress > 10000) {
                lastProgress = Date.now();
                report("progress");
            }
        }
        function playing() {
            if (!current || disposed) return;
            hasPlayed = true;
            clearTimeout(startup);
            setNotice("");
            setPaused(false);
            const s = session.current;
            if (s && !s.started) {
                s.started = true;
                report("start");
            } else report("progress");
        }
        function pause() {
            update();
            report("progress");
        }
        function ended() {
            update();
            void stop().then(() => {
                if (!disposed) {
                    setNotice("Playback finished.");
                    setPaused(true);
                }
            });
        }
        function failed() {
            if (!disposed && current?.method === "DirectPlay" && !request.hls) {
                setDecision(undefined);
                setNotice("Trying compatible playback…");
                void stop(true).then(() => {
                    if (!disposed)
                        switchStream(
                            request.audio,
                            request.subtitle,
                            session.current?.position ?? request.position,
                            true,
                        );
                });
                return;
            }
            if (!disposed) {
                setError(
                    "Playback could not continue. Retry or return to the item.",
                );
                void stop(true);
            }
        }
        function metadata() {
            if (!current || disposed) return;
            if (current.method === "DirectPlay")
                el.currentTime = request.position;
            const d =
                (current.method === "Transcode" ? request.position : 0) +
                el.duration;
            if (Number.isFinite(d) && d > 0)
                setDuration(item.RunTimeTicks ? item.RunTimeTicks / 1e7 : d);
            void el.play().catch(() => {
                if (!disposed) {
                    needsGesture = true;
                    setNotice("Press Play to start playback.");
                }
            });
        }
        el.addEventListener("loadedmetadata", metadata);
        el.addEventListener("timeupdate", update);
        el.addEventListener("playing", playing);
        el.addEventListener("pause", pause);
        el.addEventListener("ended", ended);
        el.addEventListener("error", failed);
        const pagehide = () => {
            update();
            void stop(false, true);
        };
        window.addEventListener("pagehide", pagehide);
        const timer = setInterval(() => {
            update();
        }, 10000);
        void (async () => {
            await stopping.current;
            const d = await client.prepare(
                item,
                probe,
                request.position,
                request.audio,
                request.subtitle,
                request.hls ||
                    (location.protocol === "https:" &&
                        !!localAddressSpace(client.server.url)),
            );
            if (disposed) {
                await client.stopEncoding(d);
                return;
            }
            current = d;
            session.current = {
                decision: d,
                position: request.position,
                started: false,
                stopped: false,
                queue: Promise.resolve(),
            };
            setDecision(d);
            switching.current = false;
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
                startup = setTimeout(() => {
                    if (!disposed && !hasPlayed && !needsGesture) failed();
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
                    if (data.fatal) failed();
                });
                hls.loadSource(d.url);
                hls.attachMedia(el);
            }
        })().catch((e) => {
            if (!disposed) {
                switching.current = false;
                setError(e instanceof Error ? e.message : "Playback failed.");
                if (e instanceof ClientError && e.kind === "EXPIRED")
                    onExpired(e);
            }
        });
        return () => {
            disposed = true;
            clearInterval(timer);
            clearTimeout(startup);
            window.removeEventListener("pagehide", pagehide);
            el.removeEventListener("loadedmetadata", metadata);
            el.removeEventListener("timeupdate", update);
            el.removeEventListener("playing", playing);
            el.removeEventListener("pause", pause);
            el.removeEventListener("ended", ended);
            el.removeEventListener("error", failed);
            el.pause();
            void stop();
            hls?.destroy();
            el.removeAttribute("src");
            el.load();
        };
    }, [request]);
    const streams =
        decision?.source.MediaStreams ??
        item.MediaSources?.[0]?.MediaStreams ??
        [];
    const selectedSubtitle = streams.find(
        (s) => s.Type === "Subtitle" && s.Index === request.subtitle,
    );
    const textSubtitle =
        selectedSubtitle &&
        ["srt", "vtt", "subrip"].includes(selectedSubtitle.Codec ?? "");
    const subtitleUrl =
        decision && textSubtitle
            ? mediaUrl(
                  client.account!,
                  `Videos/${encode(item.Id)}/${encode(decision.source.Id)}/Subtitles/${selectedSubtitle.Index}/${decision.method === "Transcode" ? ticks(request.position) : 0}/Stream.vtt?copyTimestamps=false&addVttTimeMap=false`,
              )
            : undefined;
    const [subtitleBlob, setSubtitleBlob] = useState<string>();
    useEffect(() => {
        let live = true,
            object: string | undefined;
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
    useEffect(() => {
        if (video.current?.textTracks[0])
            video.current.textTracks[0].mode = "showing";
    }, [subtitleBlob]);
    function seek(to: number) {
        const at = Math.max(0, Math.min(duration, to));
        if (decision?.method === "Transcode") {
            switchStream(request.audio, request.subtitle, at);
        } else if (video.current) {
            video.current.currentTime = at;
            setPosition(at);
        }
    }
    function videoKey(e: React.KeyboardEvent) {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            toggle();
        } else if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
            e.preventDefault();
            seek(position + (e.key === "ArrowRight" ? 10 : -10));
        }
    }
    function toggle() {
        const el = video.current!;
        if (el.paused)
            void el
                .play()
                .catch(() => setNotice("Press Play to start playback."));
        else el.pause();
    }
    const time = (s: number) =>
        `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
    return (
        <section className="player" aria-label={"Playing " + item.Name}>
            <div className="player-heading">
                <div>
                    <p className="eyebrow">NOW PLAYING</p>
                    <h1>{item.Name}</h1>
                </div>
                <button data-autofocus onClick={leave}>
                    Stop and go back
                </button>
            </div>
            <video
                ref={video}
                playsInline
                crossOrigin="anonymous"
                tabIndex={0}
                aria-label={
                    item.Name +
                    " video. Enter to play or pause, left and right to seek."
                }
                onKeyDown={videoKey}
            >
                {subtitleBlob && (
                    <track
                        key={subtitleBlob}
                        kind="subtitles"
                        src={subtitleBlob}
                        label={selectedSubtitle?.DisplayTitle ?? "Subtitles"}
                        srcLang={selectedSubtitle?.Language ?? "en"}
                        default
                    />
                )}
            </video>
            <p role="status">
                {error || notice || (paused ? "Paused" : "Playing")}
            </p>
            {error && (
                <button
                    onClick={() =>
                        switchStream(
                            request.audio,
                            request.subtitle,
                            session.current?.position ?? position,
                            true,
                        )
                    }
                >
                    Retry playback
                </button>
            )}
            <div className="transport">
                <button
                    disabled={!decision || !!error}
                    onClick={() => seek(position - 10)}
                >
                    Back 10 seconds
                </button>
                <button
                    className="primary"
                    disabled={!decision || !!error}
                    onClick={toggle}
                >
                    {paused ? "Play" : "Pause"}
                </button>
                <button
                    disabled={!decision || !!error}
                    onClick={() => seek(position + 10)}
                >
                    Forward 10 seconds
                </button>
            </div>
            <label htmlFor="seek">
                Position {time(position)} / {time(duration)}
            </label>
            <input
                id="seek"
                type="range"
                min="0"
                max={Math.max(1, duration)}
                step="10"
                value={Math.min(position, duration)}
                onChange={(e) => seek(+e.target.value)}
                aria-valuetext={`${time(position)} of ${time(duration)}`}
                disabled={!decision}
            />
            <div className="track-controls">
                <label>
                    Audio
                    <select
                        aria-label="Audio"
                        disabled={!decision}
                        value={
                            request.audio ??
                            decision?.source.DefaultAudioStreamIndex ??
                            streams.find((s) => s.Type === "Audio")?.Index ??
                            ""
                        }
                        onChange={(e) =>
                            switchStream(+e.target.value, request.subtitle)
                        }
                    >
                        {streams
                            .filter((s) => s.Type === "Audio")
                            .map((s) => (
                                <option key={s.Index} value={s.Index}>
                                    {s.DisplayTitle ??
                                        s.Language ??
                                        s.Codec ??
                                        "Audio " + s.Index}
                                </option>
                            ))}
                    </select>
                </label>
                <label>
                    Subtitles
                    <select
                        aria-label="Subtitles"
                        disabled={!decision}
                        value={request.subtitle}
                        onChange={(e) =>
                            switchStream(request.audio, +e.target.value)
                        }
                    >
                        <option value="-1">Off</option>
                        {streams
                            .filter((s) => s.Type === "Subtitle")
                            .map((s) => (
                                <option key={s.Index} value={s.Index}>
                                    {s.DisplayTitle ??
                                        s.Language ??
                                        "Subtitle " + s.Index}
                                </option>
                            ))}
                    </select>
                </label>
                <label>
                    Volume
                    <input
                        aria-label="Volume"
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={volume}
                        onChange={(e) => {
                            setVolume(+e.target.value);
                            video.current!.volume = +e.target.value;
                        }}
                    />
                </label>
                <button
                    onClick={() => {
                        void video.current
                            ?.requestFullscreen?.()
                            .catch(() =>
                                setNotice("Fullscreen is unavailable."),
                            );
                    }}
                >
                    Full screen
                </button>
            </div>
        </section>
    );
}
