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
            <h1>Settings</h1>
            <nav className="settings-jump" aria-label="Settings sections">
                <ul>
                    {sections.map(([id, label]) => (
                        <li key={id}>
                            <button
                                data-focus={"settings-jump:" + id}
                                onClick={() => jump(id)}
                            >
                                {label}
                            </button>
                        </li>
                    ))}
                </ul>
            </nav>
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
                <Heading id="playback">Playback</Heading>
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
                {/* SET-02: a browser always decodes the sound itself, so there is
                    no passthrough to switch on or off; say so instead. */}
                <div className="setting-readonly" id="passthrough-row">
                    <span className="switch-text">Audio passthrough</span>
                    <span className="muted">
                        Not available in browsers. The browser decodes the sound
                        itself.
                    </span>
                </div>
                <SwitchRow
                    label="Conversion notice"
                    focus="setting:conversion-notice"
                    captionId="conversion-notice-caption"
                    caption="Shows a short note when the server has to convert a video for this browser."
                    checked={value.conversionNotice}
                    onChange={(v) => patch("conversionNotice", v)}
                />
                <Heading id="subtitles">Subtitles</Heading>
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
                        {(
                            [
                                ["System", "System"],
                                ["Small", "Small"],
                                ["Normal", "Normal"],
                                ["Large", "Large"],
                                ["ExtraLarge", "Extra large"],
                            ] as const
                        ).map(([v, label]) => (
                            <option key={v} value={v}>
                                {label}
                            </option>
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
                    System follows your browser's caption settings. Subtitles
                    the server draws into the video keep their original look.
                </p>
                <Heading id="segments">Skip segments</Heading>
                <p className="muted">
                    What happens when a video reaches an intro, recap, preview,
                    ad break or credits.
                </p>
                {Object.entries(value.segments).map(([kind, mode]) => (
                    <label key={kind}>
                        {kind === "Outro"
                            ? "Credits"
                            : kind === "Commercial"
                              ? "Ad breaks"
                              : kind}
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
                <Heading id="accessibility">Accessibility</Heading>
                <SwitchRow
                    label="Ask before playing the next episode"
                    focus="setting:next-episode-ask"
                    captionId="screen-reader-caption"
                    caption="Useful with a screen reader: the next episode waits until you press Play now."
                    checked={value.screenReader}
                    onChange={(v) => patch("screenReader", v)}
                />
                <Heading id="languages">Languages</Heading>
                <p className="muted">
                    Saved on your server, so they apply in every Jellyfin app
                    you use.
                </p>
                {!config ? (
                    <p role="status">Loading your language settings…</p>
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
                {busy && <p role="status">Saving…</p>}
            </div>
            <Heading id="account">Account and help</Heading>
            <div className="settings-links">
                <button
                    data-focus="settings:accounts"
                    onClick={() => navigate({ kind: "accounts" })}
                >
                    Accounts and sign out
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

/** W-17: the sections the jump list at the top of Settings opens. */
const sections = [
    ["playback", "Playback"],
    ["subtitles", "Subtitles"],
    ["segments", "Skip segments"],
    ["accessibility", "Accessibility"],
    ["languages", "Languages"],
    ["account", "Account and help"],
] as const;
function jump(id: string) {
    const h = document.getElementById("settings-" + id);
    h?.scrollIntoView({ block: "start" });
    h?.focus({ preventScroll: true });
}
function Heading({ id, children }: { id: string; children: string }) {
    return (
        <h2 id={"settings-" + id} tabIndex={-1} className="settings-heading">
            {children}
        </h2>
    );
}

/**
 * The one on/off control for every boolean setting: a drawn switch (track and thumb)
 * at the row's end, its label and help text at the start. The thumb carries the
 * state, and role="switch" tells assistive tech, so no On/Off text is needed.
 */
function SwitchRow({
    label,
    caption,
    captionId,
    checked,
    onChange,
    focus,
}: {
    label: string;
    caption: string;
    captionId: string;
    checked: boolean;
    onChange: (value: boolean) => void;
    focus?: string;
}) {
    const labelId = `${captionId}-label`;
    // The whole row (title and help text) is one label, so a click or tap anywhere
    // on it flips the switch. aria-labelledby keeps the name to the title alone;
    // the help text is the description.
    return (
        <label className="switch-row">
            <span className="switch-copy">
                <span className="switch-text" id={labelId}>
                    {label}
                </span>
                <span className="muted switch-caption" id={captionId}>
                    {caption}
                </span>
            </span>
            <input
                type="checkbox"
                role="switch"
                className="switch"
                data-focus={focus}
                aria-labelledby={labelId}
                aria-describedby={captionId}
                checked={checked}
                onChange={(e) => onChange(e.target.checked)}
            />
        </label>
    );
}
