/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Stream } from "@pictureshow/core";
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
