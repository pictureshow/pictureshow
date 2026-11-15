/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
// Shared PLAY-26 format wording. Browser UI still uses its own capability/output evidence.
export function sourceRange(range?: string, profile?: number): string {
    const dv = "Dolby Vision" + (profile != null && profile >= 1 && profile <= 10 ? ` profile ${profile}` : "");
    switch (range) {
        case "SDR": return "SDR";
        case "HDR10": return "HDR10";
        case "HDR10Plus": return "HDR10+";
        case "HLG": return "HLG";
        case "DOVI": return dv;
        case "DOVIWithHDR10": return `${dv} · HDR10 base layer`;
        case "DOVIWithHLG": return `${dv} · HLG base layer`;
        case "DOVIWithSDR": return `${dv} · SDR base layer`;
        case "DOVIWithEL": return `${dv} · enhancement layer`;
        case "DOVIWithHDR10Plus": return `${dv} · HDR10+ base layer`;
        case "DOVIWithELHDR10Plus": return `${dv} · HDR10+ enhancement layer`;
        case "DOVIInvalid": return "Dolby Vision (invalid metadata)";
        default: return "Not reported";
    }
}
export function transfer(value?: string | null): string {
    switch (value?.toLowerCase()) {
        case "pq": case "smpte2084": return "HDR (PQ)";
        case "hlg": case "arib-std-b67": return "HDR (HLG)";
        case "bt.1886": case "bt.709": case "srgb": case "gamma2.2": case "gamma2.4": return "SDR";
        case "linear": return "Linear light";
        default: return "Not reported";
    }
}
export function display(value: string | null | undefined, output: string): string {
    const received = transfer(value);
    if (received === "SDR") return "SDR video";
    if (!received.startsWith("HDR")) return "Not reported";
    switch (output) {
        case "HdrEdR": return "HDR active (EDR)";
        case "HdrScRgb": return "HDR active (scRGB)";
        case "Sdr": return "HDR video on an SDR display · mapped to SDR";
        case "WaylandPq": return "HDR sent to compositor (PQ / BT.2020) · display HDR not reported";
        default: return "Not reported";
    }
}
export function decoder(hwdec?: string | null): string {
    if (hwdec === "no") return "Software (mpv)";
    return hwdec && /^[a-zA-Z0-9_-]{1,40}$/.test(hwdec) ? `Hardware (mpv · ${hwdec})` : "Not reported";
}

export function profile(value?: string | null): string {
    return value && /^[A-Za-z0-9_. ()-]{1,48}$/.test(value) ? value : "Not reported";
}
