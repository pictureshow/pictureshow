/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useState } from "react";
import "./information.css";
import { browserProfile } from "@pictureshow/core";
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
            setProfile({
                profile: browserProfile((t) => !!video.canPlayType(t)),
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
                "Browser capabilities could not be read. Use Refresh to try again.",
            );
        }
    }
    useEffect(() => {
        if (route.kind === "device") refresh();
    }, [route.kind]);
    if (route.kind === "device")
        return (
            <section className="information">
                <h1>This browser</h1>
                <p>
                    Current browser capabilities. MIME support is a capability
                    probe, not proof every file can decode. The actual source is
                    negotiated with your server.
                </p>
                <button data-autofocus onClick={refresh}>
                    Refresh browser capabilities
                </button>
                {error && <p role="alert">{error}</p>}
                {profile && (
                    <>
                        <h2>Playback support</h2>
                        <p>
                            Native HLS:{" "}
                            {(profile as any).playback.nativeHls
                                ? "Available"
                                : "Not reported"}{" "}
                            · MSE:{" "}
                            {(profile as any).playback.mse
                                ? "Available"
                                : "Not reported"}
                        </p>
                        <p>
                            Encoded passthrough, speaker channels, decoder
                            hardware/profile/level and display refresh rate are
                            not reported by browser APIs. No Android
                            capabilities are assumed.
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
                    </>
                )}
            </section>
        );
    if (route.kind === "about")
        return (
            <section className="information">
                <h1>Pictureshow</h1>
                <p>Your collection. Your screen.</p>
                <p>Web 0.1.0 · MPL-2.0 · Legitimate LLC</p>
                <p>Free and open source. Built for your own Jellyfin server.</p>
                <p>
                    Source:{" "}
                    <a
                        href="https://github.com/pictureshow/pictureshow"
                        target="_blank"
                        rel="noreferrer"
                    >
                        github.com/pictureshow/pictureshow
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
                    Pictureshow is free and open source. Voluntary monthly
                    support helps development and gives feature requests
                    priority for consideration.
                </p>
                <p>
                    Support does not guarantee delivery of a feature or a
                    delivery date. Playback stays free.
                </p>
                <h2 tabIndex={0} data-autofocus>
                    Purchases are unavailable in this browser
                </h2>
                <p>
                    Only Google Play billing is configured in the current
                    Android app. No web subscription provider, price, purchase
                    or management flow is configured. There is no subscription
                    to purchase here.
                </p>
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
