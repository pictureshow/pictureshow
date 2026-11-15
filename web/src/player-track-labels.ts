/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Stream } from "@pictureshow/core";

/**
 * PLAY-08 track labels: the shared policy of Kotlin core's TrackLabelPolicy.kt,
 * checked by the `track-labels` vectors. A track's language names it; without
 * one, its own title or the short format ("SRT", "PGS", "AAC stereo") does, and
 * a track number keeps same-looking tracks apart. The server's DisplayTitle is
 * never used: it repeats codec names and says "Undefined" for a missing
 * language.
 */
type LabelStream = Stream & {
    Title?: string;
    IsDefault?: boolean;
    IsForced?: boolean;
    IsHearingImpaired?: boolean;
    IsExternalUrl?: boolean;
};

// Exact generated names only; see spec/features/track-labels.md and shared vectors.
const muxerHandlerNames = new Set([
    "core media audio",
    "core media video",
    "core media text",
    "core media subtitle",
    "core media closed caption",
    "core media metadata",
    "datahandler",
    "picturehandler",
    "videohandler",
    "soundhandler",
    "closedcaptionhandler",
    "subtitlehandler",
    "hinthandler",
    "timecodehandler",
    "gopro met",
    "cam meta",
    "cam dbgi",
    "gpac iso video handler",
    "gpac iso auxiliary video handler",
    "gpac iso picture sequence handler",
    "gpac iso audio handler",
    "gpac iso hint handler",
    "gpac timed metadata handler",
    "gpac mpeg-4 od handler",
    "gpac mpeg-4 ocr handler",
    "gpac mpeg-4 scene description handler",
    "gpac mpeg-4 mpeg-7 handler",
    "gpac mpeg-4 oci handler",
    "gpac mpeg-4 ipmp handler",
    "gpac mpeg-4 mpeg-j handler",
    "gpac streaming text handler",
    "gpac mpeg subtitle handler",
    "gpac dims handler",
    "gpac tmcd handler",
    "gpac isomedia handler",
]);
const lavfIdentifier = /^lavf(?:[0-9]+\.[0-9]+\.[0-9]+)?$/;
function isMuxerHandlerName(title: string): boolean {
    const lower = title.toLowerCase();
    // RegExp's $ also accepts a final newline; compare the entire match for Kotlin parity.
    return (
        muxerHandlerNames.has(lower) ||
        lavfIdentifier.exec(lower)?.[0] === lower
    );
}

const bibliographic: Record<string, string> = {
    fre: "fr",
    ger: "de",
    chi: "zh",
    dut: "nl",
    gre: "el",
    cze: "cs",
    rum: "ro",
    slo: "sk",
    ice: "is",
    per: "fa",
    arm: "hy",
    baq: "eu",
    geo: "ka",
    mac: "mk",
    mao: "mi",
    may: "ms",
    alb: "sq",
    bur: "my",
    wel: "cy",
    tib: "bo",
};
let displayNames: Intl.DisplayNames | undefined;
export function trackLanguage(language?: string | null): string | undefined {
    if (!language || !language.trim() || language === "und") return undefined;
    const tag = language.replace(/_/g, "-");
    try {
        displayNames ??= new Intl.DisplayNames(["en"], { type: "language" });
        for (const candidate of [tag, bibliographic[tag.toLowerCase()]]) {
            if (!candidate) continue;
            const name = displayNames.of(candidate);
            if (name && name.toLowerCase() !== candidate.toLowerCase())
                return name;
        }
    } catch {}
    return language;
}

/** A whole phrase of `title`, case-insensitive, not preceded by "non-". */
export function trackMetadataNames(title: string, value: string): boolean {
    const lowerTitle = title.toLowerCase(),
        lowerValue = value.toLowerCase();
    const word = /[\p{L}\p{N}]/u;
    for (
        let at = lowerTitle.indexOf(lowerValue);
        at >= 0;
        at = lowerTitle.indexOf(lowerValue, at + 1)
    ) {
        const end = at + lowerValue.length;
        if (at > 0 && word.test(title[at - 1])) continue;
        if (end < title.length && word.test(title[end])) continue;
        if (/\bnon\p{Pd}$/iu.test(title.slice(0, at))) continue;
        return true;
    }
    return false;
}

function format(s: LabelStream): string {
    const codec = (() => {
        switch (s.Codec?.toLowerCase()) {
            case "eac3":
                return "E-AC-3";
            case "ac3":
                return "AC-3";
            case "truehd":
                return "TrueHD";
            case "dts":
                return "DTS";
            case "subrip":
            case "srt":
                return "SRT";
            case "hdmv_pgs_subtitle":
            case "pgssub":
            case "pgs":
                return "PGS";
            case "dvd_subtitle":
            case "dvdsub":
            case "vobsub":
                return "VobSub";
            case "mov_text":
                return "mov_text";
            case "opus":
                return "Opus";
            default:
                return s.Codec?.toUpperCase() ?? "Unknown format";
        }
    })();
    if (s.Type !== "Audio" || s.Channels == null) return codec;
    const layout =
        { 1: "mono", 2: "stereo", 6: "5.1", 8: "7.1" }[s.Channels] ??
        `${s.Channels} channels`;
    return `${codec} ${layout}`;
}

export function playerTrackLabel(
    s: LabelStream,
    trackNumber?: number,
    showDefault = true,
): string {
    const parts: string[] = [];
    const fmt = format(s);
    const language = trackLanguage(s.Language);
    const title =
        s.Title?.trim() && !isMuxerHandlerName(s.Title) ? s.Title : undefined;
    const name =
        language && title && trackMetadataNames(title, language)
            ? title
            : (language ?? title);
    if (name) {
        parts.push(name);
        const titleCarriesFormat =
            !!title &&
            title.toLowerCase() !== name.toLowerCase() &&
            title.toLowerCase() !== fmt.toLowerCase() &&
            trackMetadataNames(title, fmt);
        if (!trackMetadataNames(name, fmt) && !titleCarriesFormat)
            parts.push(fmt);
    } else parts.push(fmt);
    // Keep descriptive titles (audio description, commentary) beside a language.
    if (
        language &&
        title &&
        title.toLowerCase() !== name?.toLowerCase() &&
        title.toLowerCase() !== fmt.toLowerCase()
    )
        parts.push(title);
    if (!language && trackNumber !== undefined)
        parts.push(`Track ${trackNumber}`);
    const badge = (word: string, enabled: boolean | undefined) => {
        if (enabled && !(title && trackMetadataNames(title, word)))
            parts.push(word);
    };
    badge("Default", showDefault && s.IsDefault);
    badge("Forced", s.IsForced);
    badge("SDH", s.IsHearingImpaired);
    badge("External", s.IsExternal || s.IsExternalUrl);
    return parts.join(" · ");
}

export function playerTrackLabels(streams: Stream[]): string[] {
    const list = streams as LabelStream[];
    const defaults = list.filter((s) => s.IsDefault).length;
    let labels = list.map((s, i) =>
        playerTrackLabel(s, i + 1, defaults === 1 && list.length > 1),
    );
    // Menu order only; repeat in case a server title already ends in a number.
    while (new Set(labels).size !== labels.length) {
        const seen = new Map<string, number>();
        for (const l of labels) seen.set(l, (seen.get(l) ?? 0) + 1);
        labels = labels.map((l, i) =>
            (seen.get(l) ?? 0) > 1 ? `${l} · Track ${i + 1}` : l,
        );
    }
    return labels;
}
