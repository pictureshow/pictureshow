/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useState } from "react";
import "./information.css";
import {
    browserPlaybackSummary,
    browserProfile,
    elementProbe,
} from "@pictureshow/core";
export type InformationRoute =
    | { kind: "about" | "device" | "support" | "licenses" }
    | { kind: "license"; index: number };
export function chunks(text: string, size = 1000) {
    const lines = text.match(/[^\n]*\n|[^\n]+$/g) ?? [];
    const out: string[] = [];
    let current = "";
    for (const line of lines) {
        if (current && current.length + line.length > size) {
            out.push(current);
            current = "";
        }
        current += line;
    }
    if (current) out.push(current);
    return out;
}
type License = {
    name: string;
    version: string;
    id: string;
    license: string;
    text: string;
};
export function Information({
    route,
    navigate,
}: {
    route: InformationRoute;
    navigate: (r: any) => void;
}) {
    const licenseIndex = "index" in route ? route.index : 0;
    const [licenses, setLicenses] = useState<License[]>(),
        [error, setError] = useState(""),
        [attempt, setAttempt] = useState(0),
        [profile, setProfile] = useState<object>(),
        [summary, setSummary] =
            useState<ReturnType<typeof browserPlaybackSummary>>(),
        [show, setShow] = useState(false);
    useEffect(() => {
        let live = true;
        if (route.kind === "licenses" || route.kind === "license")
            fetch(new URL("licenses.json", document.baseURI), {
                credentials: "omit",
                referrerPolicy: "no-referrer",
            })
                .then((r) => {
                    if (!r.ok) throw Error("Could not load bundled notices.");
                    return r.json();
                })
                .then((v) => {
                    if (live) setLicenses(v);
                })
                .catch((e) => {
                    if (live) setError(e.message);
                });
        return () => {
            live = false;
        };
    }, [route.kind, attempt]);
    function refresh() {
        try {
            const video = document.createElement("video");
            const probe = elementProbe(video);
            setSummary(
                browserPlaybackSummary(
                    probe,
                    typeof MediaSource !== "undefined" ||
                        // A bare type answers "maybe" at best.
                        !!video.canPlayType("application/vnd.apple.mpegurl"),
                ),
            );
            setProfile({
                profile: browserProfile(probe),
                display: {
                    width: screen.width,
                    height: screen.height,
                    pixelRatio: devicePixelRatio,
                    colorDepth: screen.colorDepth,
                    hdr: matchMedia("(dynamic-range: high)").matches
                        ? "High dynamic range reported"
                        : "Not reported",
                    refreshRate: "Not reported",
                },
                audio: {
                    outputChannels: "Not reported",
                    passthrough: "Not exposed by browser",
                    outputDeviceSelection:
                        typeof (video as any).setSinkId === "function"
                            ? "Available with browser permission"
                            : "Not exposed",
                },
                playback: {
                    mse: typeof MediaSource !== "undefined",
                    nativeHls: !!video.canPlayType(
                        "application/vnd.apple.mpegurl",
                    ),
                },
                platform:
                    (navigator as any).userAgentData?.platform ??
                    navigator.platform,
            });
            setError("");
        } catch {
            setError(
                "Pictureshow couldn't check this browser. Press Check again to retry.",
            );
        }
    }
    useEffect(() => {
        if (route.kind === "device") refresh();
    }, [route.kind]);
    if (route.kind === "device")
        return (
            <section className="information">
                <h1>Troubleshooting</h1>
                <p>What this browser can play, and why.</p>
                {error && <p role="alert">{error}</p>}
                {profile && summary && (
                    <>
                        <h2>Playback</h2>
                        <p className="verdict">{summary.verdict}</p>
                        <p>
                            Video formats it plays:{" "}
                            {summary.video.join(", ") || "none reported"}
                        </p>
                        <p>
                            Audio formats it plays:{" "}
                            {summary.audio.join(", ") || "none reported"}
                        </p>
                        <p>
                            Browsers decode the sound themselves, so audio can't
                            pass through to a receiver.
                        </p>
                        <details className="technical">
                            <summary>Technical details</summary>
                            <p>
                                Media Source Extensions:{" "}
                                {(profile as any).playback.mse
                                    ? "Available"
                                    : "Not reported"}{" "}
                                · Native HLS:{" "}
                                {(profile as any).playback.nativeHls
                                    ? "Available"
                                    : "Not reported"}
                            </p>
                            <p>
                                Formats come from the browser's MIME type check,
                                which doesn't prove that every file decodes.
                                Your server decides the actual stream for each
                                video. Browsers don't report encoded
                                passthrough, speaker channels, decoder hardware,
                                profile or level, or the display's refresh rate.
                            </p>
                            <button
                                aria-expanded={show}
                                onClick={() => setShow(!show)}
                            >
                                {show
                                    ? "Hide device profile JSON"
                                    : "View device profile JSON"}
                            </button>
                            {show &&
                                chunks(JSON.stringify(profile, null, 2)).map(
                                    (s, i) => (
                                        <pre key={i} tabIndex={0}>
                                            {s}
                                        </pre>
                                    ),
                                )}
                        </details>
                    </>
                )}
                <button data-autofocus onClick={refresh}>
                    Check again
                </button>
            </section>
        );
    if (route.kind === "about")
        return (
            <section className="information">
                <h1>Pictureshow</h1>
                <p>Your collection. Your screen.</p>
                <p>Web 0.1.0 · MPL-2.0 · Legitimate LLC</p>
                <p>Free and open source. Built for your own Jellyfin server.</p>
                <p className="muted">
                    These links open the website in a new tab; Pictureshow stays
                    open here.
                </p>
                <p>
                    Source:{" "}
                    <a
                        href="https://github.com/pictureshow/pictureshow"
                        target="_blank"
                        rel="noreferrer"
                    >
                        github.com/pictureshow/pictureshow
                        <span aria-hidden="true"> ↗</span>
                        <span className="sr-only"> (opens in a new tab)</span>
                    </a>
                </p>
                <p>
                    Privacy:{" "}
                    <a
                        href="https://pictureshow.app/privacy/"
                        target="_blank"
                        rel="noreferrer"
                    >
                        pictureshow.app/privacy/
                        <span aria-hidden="true"> ↗</span>
                        <span className="sr-only"> (opens in a new tab)</span>
                    </a>
                </p>
                <button
                    data-autofocus
                    data-focus="about:licenses"
                    onClick={() => navigate({ kind: "licenses" })}
                >
                    Open-source licenses
                </button>
            </section>
        );
    if (route.kind === "support")
        return (
            <section className="information">
                <h1>Support Pictureshow</h1>
                <p>
                    Pictureshow is free and open source. Monthly supporters help
                    pay for its development, and their feature requests are
                    considered first.
                </p>
                <p>
                    Supporting doesn't promise a feature or a date. Watching
                    stays free.
                </p>
                <h2 tabIndex={-1} data-autofocus>
                    You can't become a supporter in the web app yet
                </h2>
                <p>Everything here stays free.</p>
            </section>
        );
    return (
        <section className="information">
            <h1>
                {route.kind === "licenses"
                    ? "Open-source licenses"
                    : (licenses?.[licenseIndex]?.name ?? "License")}
            </h1>
            {error ? (
                <div role="alert">
                    <p>{error}</p>
                    <button
                        data-autofocus
                        onClick={() => {
                            setError("");
                            setAttempt((x) => x + 1);
                        }}
                    >
                        Retry licenses
                    </button>
                </div>
            ) : !licenses ? (
                <p role="status">Loading bundled notices…</p>
            ) : route.kind === "licenses" ? (
                <>
                    <p>{licenses.length} bundled dependencies</p>
                    <p>Choose a library to read its license and notices.</p>
                    <ul className="license-list">
                        {licenses.map((l, i) => (
                            <li key={l.id}>
                                <button
                                    data-autofocus
                                    data-focus={"license:" + i}
                                    onClick={() =>
                                        navigate({ kind: "license", index: i })
                                    }
                                >
                                    {l.name} {l.version}
                                    <span className="muted">{l.license}</span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </>
            ) : licenses[licenseIndex] ? (
                <>
                    <p>
                        {licenses[licenseIndex].version} ·{" "}
                        {licenses[licenseIndex].license}
                    </p>
                    {chunks(licenses[licenseIndex].text).map((c, i) => (
                        <p
                            className="license-text"
                            tabIndex={0}
                            data-autofocus={i === 0 || undefined}
                            key={i}
                        >
                            {c}
                        </p>
                    ))}
                </>
            ) : (
                <p role="alert">
                    This license is unavailable. Go Back to the license list.
                </p>
            )}
        </section>
    );
}
