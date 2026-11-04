/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { playerTrackLabels } from "./player-track-labels";
import type { Stream } from "@pictureshow/core";
self.onmessage = (
    event: MessageEvent<{ source: string; streams: Stream[] }>,
) => {
    const menu = (type: string) => {
        const streams = event.data.streams.filter((s) => s.Type === type);
        return { streams, labels: playerTrackLabels(streams) };
    };
    self.postMessage({
        source: event.data.source,
        audio: menu("Audio"),
        subtitles: menu("Subtitle"),
    });
};
