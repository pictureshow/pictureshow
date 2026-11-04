/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Validate optional converted WebVTT before caching it. A bad optional response
 * retries independently rather than leaving a permanently failed native track. */
export function validSubtitleText(text: string): boolean {
    const normalized = text.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
    if (!/^WEBVTT(?:[ \t].*)?(?:\n|$)/.test(normalized)) return false;
    const timestamp = (value: string) => {
        const m = /^(?:(\d{2,}):)?(\d{2}):(\d{2})\.(\d{3})$/.exec(value);
        if (!m || +m[2] > 59 || +m[3] > 59) return NaN;
        return (+m[1] || 0) * 3600 + +m[2] * 60 + +m[3] + +m[4] / 1000;
    };
    for (const block of normalized.split(/\n[ \t]*\n/).slice(1)) {
        if (
            !block.trim() ||
            /^(?:NOTE(?:[ \t\n]|$)|STYLE(?:\n|$)|REGION(?:\n|$))/.test(block)
        )
            continue;
        const lines = block.split("\n");
        const timing = lines.find((line) => line.includes("-->"));
        if (!timing) return false;
        const match = /^([^ \t]+)[ \t]+-->[ \t]+([^ \t]+)(?:[ \t].*)?$/.exec(
            timing,
        );
        if (!match) return false;
        const start = timestamp(match[1]),
            end = timestamp(match[2]);
        if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start)
            return false;
    }
    return true;
}
