/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useRef, useState } from "react";
import { Jellyfin, encode } from "@pictureshow/core";
export type Preferences = {
    bitrateMbps: number | null;
    passthrough: boolean;
    /** SET-11: PLAY-27's notice, default On; read when the player opens. */
    conversionNotice: boolean;
    subtitleSize: "System" | "Small" | "Normal" | "Large" | "ExtraLarge";
    subtitleBackground: "System" | "None" | "Shadow" | "Box";
    segments: Record<
        "Intro" | "Recap" | "Preview" | "Commercial" | "Outro",
        "Ask" | "Auto" | "Off"
    >;
    screenReader: boolean;
};
export const defaultPreferences: Preferences = {
    bitrateMbps: null,
    passthrough: true,
    conversionNotice: true,
    subtitleSize: "System",
    subtitleBackground: "System",
    segments: {
        Intro: "Ask",
        Recap: "Ask",
        Preview: "Ask",
        Commercial: "Ask",
        Outro: "Ask",
    },
    screenReader: true,
};
export function readPreferences(): Preferences {
    const raw = localStorage.getItem("pictureshow.preferences");
    let v: any = {};
    try {
        const parsed = JSON.parse(raw ?? "{}");
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed))
            v = parsed;
    } catch {}
    return {
        bitrateMbps: [4, 10, 20, 40, 80, 120].includes(v.bitrateMbps)
            ? v.bitrateMbps
            : null,
        passthrough: typeof v.passthrough === "boolean" ? v.passthrough : true,
        conversionNotice:
            typeof v.conversionNotice === "boolean" ? v.conversionNotice : true,
        subtitleSize: [
            "System",
            "Small",
            "Normal",
            "Large",
            "ExtraLarge",
        ].includes(v.subtitleSize)
            ? v.subtitleSize
            : "System",
        subtitleBackground: ["System", "None", "Shadow", "Box"].includes(
            v.subtitleBackground,
        )
            ? v.subtitleBackground
            : "System",
        segments: Object.fromEntries(
            Object.keys(defaultPreferences.segments).map((k) => [
                k,
                ["Ask", "Auto", "Off"].includes(v.segments?.[k])
                    ? v.segments[k]
                    : "Ask",
            ]),
        ) as Preferences["segments"],
        screenReader:
            typeof v.screenReader === "boolean" ? v.screenReader : true,
    };
}
const languages = [
    ["", "No preference"],
    ["eng", "English"],
    ["spa", "Spanish"],
    ["fra", "French"],
    ["deu", "German"],
    ["jpn", "Japanese"],
    ["zho", "Chinese"],
    ["kor", "Korean"],
    ["por", "Portuguese"],
    ["ita", "Italian"],
    ["rus", "Russian"],
    ["ara", "Arabic"],
    ["hin", "Hindi"],
];
export function Settings({
    client,
    value,
    onChange,
    navigate,
    onError,
}: {
    client: Jellyfin;
    value: Preferences;
    onChange: (p: Preferences) => void;
    navigate: (r: any) => void;
    onError: (e: unknown) => void;
}) {
    const [config, setConfig] = useState<Record<string, unknown>>(),
        [error, setError] = useState(""),
        [busy, setBusy] = useState(false),
        [attempt, setAttempt] = useState(0);
    const live = useRef(true),
        write = useRef(false),
        pending = useRef<{ name: string; value: string } | undefined>(
            undefined,
        );
    useEffect(() => {
        live.current = true;
        setConfig(undefined);
        setError("");
        client
            .request<{ Configuration: Record<string, unknown> }>(
                "Users/" + encode(client.account!.user.Id),
            )
            .then((v) => {
                if (live.current) setConfig(v.Configuration ?? {});
            })
            .catch((e) => {
                if (live.current) {
                    setError(e.message);
                    onError(e);
                }
            });
        return () => {
            live.current = false;
        };
    }, [client.account!.accessToken, attempt]);
    async function save(name: string, val: string) {
        if (!config || write.current) return;
        write.current = true;
        setBusy(true);
        pending.current = { name, value: val };
        try {
            const next = { ...config, [name]: val };
            await client.request(
                "Users/Configuration?userId=" + encode(client.account!.user.Id),
                next,
                "POST",
            );
            if (live.current) {
                setConfig(next);
                setError("");
                pending.current = undefined;
            }
        } catch (e) {
            if (live.current) {
                setError(
                    e instanceof Error
                        ? e.message
                        : "Could not save preference.",
                );
                onError(e);
            }
        } finally {
            write.current = false;
            if (live.current) setBusy(false);
        }
    }
    function patch<K extends keyof Preferences>(k: K, v: Preferences[K]) {
        try {
            const next = { ...value, [k]: v };
            localStorage.setItem(
                "pictureshow.preferences",
                JSON.stringify(next),
            );
            onChange(next);
            setError("");
        } catch {
            setError(
                "This browser could not save your preference. Your previous choice is still active.",
            );
        }
    }
    return (
        <section className="settings-page">
            <h1>Playback settings</h1>
            {error && (
                <div role="alert">
                    <p>{error}</p>
                    <button
                        onClick={() =>
                            pending.current
                                ? void save(
                                      pending.current.name,
                                      pending.current.value,
                                  )
                                : setAttempt((x) => x + 1)
                        }
                    >
                        Retry preferences
                    </button>
                </div>
            )}
            <div className="setting-list">
                <label>
                    Max streaming bitrate
                    <select
                        data-autofocus
                        data-focus="setting:bitrate"
                        value={value.bitrateMbps ?? "auto"}
                        onChange={(e) =>
                            patch(
                                "bitrateMbps",
                                e.target.value === "auto"
                                    ? null
                                    : Number(e.target.value),
                            )
                        }
                    >
                        <option value="auto">Auto · up to 200 Mbps</option>
                        {[120, 80, 40, 20, 10, 4].map((v) => (
                            <option key={v} value={v}>
                                {v} Mbps
                            </option>
                        ))}
                    </select>
                </label>
                <label className="switch-label">
                    <input
                        type="checkbox"
                        role="switch"
                        checked={value.passthrough}
                        onChange={(e) => patch("passthrough", e.target.checked)}
                    />
                    Allow audio passthrough
                </label>
                <p className="muted">
                    This browser does not expose encoded audio passthrough or
                    the connected speaker layout. Playback uses supported
                    decoded audio or server conversion; this preference cannot
                    grant a capability.
                </p>
                <label className="switch-label">
                    <input
                        type="checkbox"
                        role="switch"
                        data-focus="setting:conversion-notice"
                        aria-describedby="conversion-notice-caption"
                        checked={value.conversionNotice}
                        onChange={(e) =>
                            patch("conversionNotice", e.target.checked)
                        }
                    />
                    Conversion notice: {value.conversionNotice ? "On" : "Off"}
                </label>
                <p className="muted" id="conversion-notice-caption">
                    Shows a short note when the server has to convert a video
                    for this browser.
                </p>
                <label>
                    Subtitle size
                    <select
                        value={value.subtitleSize}
                        onChange={(e) =>
                            patch(
                                "subtitleSize",
                                e.target.value as Preferences["subtitleSize"],
                            )
                        }
                    >
                        {[
                            "System",
                            "Small",
                            "Normal",
                            "Large",
                            "ExtraLarge",
                        ].map((v) => (
                            <option key={v}>{v}</option>
                        ))}
                    </select>
                </label>
                <label>
                    Subtitle background
                    <select
                        value={value.subtitleBackground}
                        onChange={(e) =>
                            patch(
                                "subtitleBackground",
                                e.target
                                    .value as Preferences["subtitleBackground"],
                            )
                        }
                    >
                        {["System", "None", "Shadow", "Box"].map((v) => (
                            <option key={v}>{v}</option>
                        ))}
                    </select>
                </label>
                <p className="muted">
                    System uses browser caption styling where available.
                    Burned-in captions retain their authored appearance.
                </p>
                <h2>Skip segments</h2>
                {Object.entries(value.segments).map(([kind, mode]) => (
                    <label key={kind}>
                        {kind === "Outro" ? "Credits" : kind}
                        <select
                            value={mode}
                            onChange={(e) =>
                                patch("segments", {
                                    ...value.segments,
                                    [kind]: e.target.value as
                                        "Ask" | "Auto" | "Off",
                                })
                            }
                        >
                            <option value="Ask">Ask</option>
                            <option value="Auto">Skip automatically</option>
                            <option value="Off">Off</option>
                        </select>
                    </label>
                ))}
                <label className="switch-label">
                    <input
                        type="checkbox"
                        role="switch"
                        checked={value.screenReader}
                        onChange={(e) =>
                            patch("screenReader", e.target.checked)
                        }
                    />
                    Require Play now for the next episode
                </label>
                <p className="muted">
                    Recommended for screen readers. Browsers do not report
                    whether a screen reader is enabled; manual continuation is
                    the default.
                </p>
                <h2>Server preferences</h2>
                {!config ? (
                    <p role="status">Loading server preferences…</p>
                ) : (
                    <fieldset disabled={busy}>
                        <legend>Language and subtitles</legend>
                        {[
                            ["AudioLanguagePreference", "Audio language"],
                            ["SubtitleLanguagePreference", "Subtitle language"],
                        ].map(([key, label]) => (
                            <label key={key}>
                                {label}
                                <select
                                    value={String(config[key] ?? "")}
                                    onChange={(e) =>
                                        void save(key, e.target.value)
                                    }
                                >
                                    {languages.map(([code, label]) => (
                                        <option key={code} value={code}>
                                            {label}
                                        </option>
                                    ))}
                                </select>
                            </label>
                        ))}
                        <label>
                            Subtitle mode
                            <select
                                value={String(config.SubtitleMode ?? "Default")}
                                onChange={(e) =>
                                    void save("SubtitleMode", e.target.value)
                                }
                            >
                                {[
                                    "Default",
                                    "Always",
                                    "OnlyForced",
                                    "None",
                                    "Smart",
                                ].map((v) => (
                                    <option key={v} value={v}>
                                        {v === "OnlyForced" ? "Only forced" : v}
                                    </option>
                                ))}
                            </select>
                        </label>
                    </fieldset>
                )}
                {busy && <p role="status">Saving server preferences…</p>}
            </div>
            <h2>Pictureshow</h2>
            <div className="settings-links">
                <button
                    data-focus="settings:accounts"
                    onClick={() => navigate({ kind: "accounts" })}
                >
                    Accounts
                </button>
                <button
                    data-focus="settings:support"
                    onClick={() => navigate({ kind: "support" })}
                >
                    Support Pictureshow
                </button>
                <button
                    data-focus="settings:about"
                    onClick={() => navigate({ kind: "about" })}
                >
                    About Pictureshow
                </button>
                <button
                    data-focus="settings:device"
                    onClick={() => navigate({ kind: "device" })}
                >
                    Troubleshooting · This browser
                </button>
            </div>
        </section>
    );
}
