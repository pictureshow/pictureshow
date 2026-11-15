/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Audio Off from older clients falls back; -1 is only retained for a file without audio. */
export function audioTrackIndex(
    streams: { Type?: string; Index: number; IsDefault?: boolean }[],
    selected?: number | null,
    serverDefault?: number | null,
): number {
    return selected != null && selected >= 0
        ? selected
        : serverDefault != null && serverDefault >= 0
          ? serverDefault
          : ((
                streams.find((s) => s.Type === "Audio" && s.IsDefault) ??
                streams.find((s) => s.Type === "Audio")
            )?.Index ?? -1);
}
