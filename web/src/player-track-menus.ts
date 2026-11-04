/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Stream } from "@pictureshow/core";
export type TrackMenus = {
    source: string;
    audio: { streams: Stream[]; labels: string[] };
    subtitles: { streams: Stream[]; labels: string[] };
};
