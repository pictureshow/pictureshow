/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Stream } from "./index";

const languageNames = new Intl.DisplayNames(["en"], {
    type: "language",
    fallback: "none",
    languageDisplay: "standard",
});
export function trackLanguage(value?: string | null): string | null {
    if (!value?.trim() || value === "und") return null;
    const base = value.replaceAll("_", "-").split("-")[0];
    try {
        return languageNames.of(base) ?? value;
    } catch {
        return value;
    }
}

function names(text: string, phrase: string): boolean {
    const literal = phrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const pattern = new RegExp(
        `(?<![\\p{L}\\p{N}])${literal}(?![\\p{L}\\p{N}])`,
        "giu",
    );
    for (const match of text.matchAll(pattern)) {
        const prefix = text.slice(0, match.index);
        // Java/ICU's word boundary treats non- as negation only at a word start.
        if (/(?:^|[^\p{L}\p{N}\p{M}_])non\p{Pd}$/iu.test(prefix)) continue;
        return true;
    }
    return false;
}
const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();
export function trackLabel(
    stream: Stream,
    trackNumber?: number,
    showDefault = true,
): string {
    const codecs: Record<string, string> = {
        eac3: "E-AC-3",
        ac3: "AC-3",
        truehd: "TrueHD",
        dts: "DTS",
        subrip: "SRT",
        srt: "SRT",
        hdmv_pgs_subtitle: "PGS",
        pgssub: "PGS",
        pgs: "PGS",
        dvd_subtitle: "VobSub",
        dvdsub: "VobSub",
        vobsub: "VobSub",
        mov_text: "mov_text",
        opus: "Opus",
    };
    const codec =
        stream.Codec == null
            ? "Unknown format"
            : (codecs[stream.Codec.toLowerCase()] ??
              stream.Codec.toUpperCase());
    const channels =
        stream.Type === "Audio" && stream.Channels != null
            ? ({ 1: " mono", 2: " stereo", 6: " 5.1", 8: " 7.1" }[
                  stream.Channels
              ] ?? ` ${stream.Channels} channels`)
            : "";
    const format = codec + channels;
    const language = trackLanguage(stream.Language);
    const title = stream.Title?.trim() ? stream.Title : null;
    const name =
        language && title && names(title, language)
            ? title
            : (language ?? title);
    const parts: string[] = [];
    if (name != null) {
        parts.push(name);
        const titleCarriesFormat =
            title != null &&
            !same(title, name) &&
            !same(title, format) &&
            names(title, format);
        if (!names(name, format) && !titleCarriesFormat) parts.push(format);
    } else parts.push(format);
    if (language && title && !same(title, name ?? "") && !same(title, format))
        parts.push(title);
    if (language == null && trackNumber != null)
        parts.push(`Track ${trackNumber}`);
    for (const [word, enabled] of [
        ["Default", showDefault && stream.IsDefault],
        ["Forced", stream.IsForced],
        ["SDH", stream.IsHearingImpaired],
        ["External", stream.IsExternal || stream.IsExternalUrl],
    ] as const) {
        if (enabled && !(title && names(title, word))) parts.push(word);
    }
    return parts.join(" · ");
}

/** Supply an audio or subtitle chooser in display order; numbers never reveal server indices. */
export function trackLabels(streams: Stream[]): string[] {
    const showDefault =
        streams.length > 1 && streams.filter((s) => s.IsDefault).length === 1;
    let result = streams.map((stream) =>
        trackLabel(stream, undefined, showDefault),
    );
    while (new Set(result).size !== result.length) {
        const counts = new Map<string, number>();
        for (const label of result)
            counts.set(label, (counts.get(label) ?? 0) + 1);
        result = result.map((label, index) =>
            (counts.get(label) ?? 0) > 1
                ? `${label} · Track ${index + 1}`
                : label,
        );
    }
    return result;
}
