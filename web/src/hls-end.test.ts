/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import { atNaturalEnd, playsNativeHls, trimSubFrameTail } from "./hls-end";

// The shape Jellyfin 12 returned for a 60.031 s file transcoded in 3 s segments.
const playlist = (tail: string, end = "#EXT-X-ENDLIST") =>
    [
        "#EXTM3U",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:3",
        "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXTINF:3.000000, nodesc",
        "hls1/main/18.ts?runtimeTicks=540000000&actualSegmentLengthTicks=30000000",
        "#EXTINF:3.000000, nodesc",
        "hls1/main/19.ts?runtimeTicks=570000000&actualSegmentLengthTicks=30000000",
        `#EXTINF:${tail}, nodesc`,
        "hls1/main/20.ts?runtimeTicks=600000000&actualSegmentLengthTicks=310000",
        end,
        "",
    ].join("\n");

describe("trimSubFrameTail", () => {
    it("drops a closed playlist's final segment shorter than one frame", () => {
        const out = trimSubFrameTail(playlist("0.031000"));
        expect(out).not.toContain("20.ts");
        expect(out).toContain("19.ts");
        expect(out.trimEnd().endsWith("#EXT-X-ENDLIST")).toBe(true);
        expect(out.match(/#EXTINF/g)).toHaveLength(2);
    });
    it("keeps a final segment that holds whole frames", () => {
        const text = playlist("0.250000");
        expect(trimSubFrameTail(text)).toBe(text);
    });
    it("leaves an open (live or growing) playlist alone", () => {
        const text = playlist("0.031000", "");
        expect(trimSubFrameTail(text)).toBe(text);
    });
    it("keeps the only segment of a one-segment playlist", () => {
        const text = "#EXTM3U\n#EXTINF:0.050000,\nonly.ts\n#EXT-X-ENDLIST\n";
        expect(trimSubFrameTail(text)).toBe(text);
    });
    it("leaves a master playlist alone", () => {
        const text =
            '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.64001F"\nmain.m3u8\n';
        expect(trimSubFrameTail(text)).toBe(text);
    });
    it("removes a byte-range tag that belongs to the dropped segment", () => {
        const text = [
            "#EXTM3U",
            "#EXTINF:3.0,",
            "a.ts",
            "#EXTINF:0.02,",
            "#EXT-X-BYTERANGE:100@0",
            "b.ts",
            "#EXT-X-ENDLIST",
        ].join("\n");
        expect(trimSubFrameTail(text)).toBe(
            ["#EXTM3U", "#EXTINF:3.0,", "a.ts", "#EXT-X-ENDLIST"].join("\n"),
        );
    });
});

describe("atNaturalEnd", () => {
    const base = {
        position: 59.79,
        duration: 60.031,
        bufferedEnd: 59.792,
        finalBuffered: true,
    };
    it("is the end when the clock sits at the buffered end of the final fragment", () => {
        expect(atNaturalEnd(base)).toBe(true);
    });
    it("is not the end before the final fragment is buffered", () => {
        expect(atNaturalEnd({ ...base, finalBuffered: false })).toBe(false);
    });
    it("is not the end while buffered media remains ahead of the clock", () => {
        expect(atNaturalEnd({ ...base, position: 56.03 })).toBe(false);
    });
    it("is not the end when the buffer stops well short of the runtime", () => {
        expect(atNaturalEnd({ ...base, position: 40, bufferedEnd: 40.1 })).toBe(
            false,
        );
    });
    it("needs a known duration and buffer", () => {
        expect(atNaturalEnd({ ...base, duration: 0 })).toBe(false);
        expect(atNaturalEnd({ ...base, bufferedEnd: undefined })).toBe(false);
    });
});

describe("playsNativeHls", () => {
    it("keeps WebKit's own HLS", () => {
        expect(
            playsNativeHls({
                nativeHls: true,
                mediaSource: true,
                chromium: false,
            }),
        ).toBe(true);
    });
    it("uses hls.js in Chromium even when it claims native HLS", () => {
        expect(
            playsNativeHls({
                nativeHls: true,
                mediaSource: true,
                chromium: true,
            }),
        ).toBe(false);
    });
    it("falls back to native HLS where Media Source Extensions are missing", () => {
        expect(
            playsNativeHls({
                nativeHls: true,
                mediaSource: false,
                chromium: true,
            }),
        ).toBe(true);
    });
    it("never plays native HLS the browser cannot play", () => {
        expect(
            playsNativeHls({
                nativeHls: false,
                mediaSource: true,
                chromium: false,
            }),
        ).toBe(false);
    });
});
