/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import {
    BROWSER_DEVICE,
    BROWSER_NOTICE_ACTION,
    conversionNotice,
    cueNearTop,
    runVector,
    transcodeReasons,
} from "./playback-information";

const url =
    "https://server.example/videos/item/master.m3u8?VideoCodec=h264&AudioCodec=aac";
const good = {
    id: "t",
    operation: "conversion-notice",
    input: {
        method: "Transcode",
        url,
        reasons: ["VideoCodecNotSupported"],
        device: BROWSER_DEVICE,
    },
    expected: "x",
};

describe("playback-information strict vector runner", () => {
    it("runs a well-formed conversion-notice case", () => {
        expect(runVector(good)).toBe(
            "Converting this video for this browser\nIts video format won't play on this browser.",
        );
    });
    it("fails unknown operations instead of passing them", () => {
        expect(() =>
            runVector({ ...good, operation: "conversion-notices" }),
        ).toThrow(/Unknown playback-information operation/);
        expect(() => runVector({ ...good, operation: undefined })).toThrow();
    });
    it.each([
        ["missing input", undefined],
        ["array input", []],
        ["missing method", { ...good.input, method: undefined }],
        ["missing url", { ...good.input, url: undefined }],
        [
            "reasons not an array",
            { ...good.input, reasons: "VideoCodecNotSupported" },
        ],
        ["non-string reason", { ...good.input, reasons: [7] }],
        ["missing device", { ...good.input, device: undefined }],
        ["empty device", { ...good.input, device: "" }],
        ["string subtitle index", { ...good.input, subtitleIndex: "3" }],
        ["null source", { ...good.input, source: null }],
    ])("fails a malformed conversion-notice case: %s", (_, input) => {
        expect(() => runVector({ ...good, input })).toThrow(/Malformed/);
    });
    it("fails a case with no id or no expected value", () => {
        const { expected: _e, ...noExpected } = good;
        expect(() => runVector(noExpected)).toThrow(/Malformed/);
        expect(() => runVector({ ...good, id: undefined })).toThrow(
            /Malformed/,
        );
    });
    it("fails malformed inputs of the other operations", () => {
        expect(() =>
            runVector({ ...good, operation: "speed", input: { speed: 1 } }),
        ).toThrow(/Malformed/);
        expect(() =>
            runVector({ ...good, operation: "reason", input: {} }),
        ).toThrow(/Malformed/);
    });
});

describe("conversion notice on web", () => {
    it("reads Jellyfin's TranscodeReasons from the transcoding URL", () => {
        expect(
            transcodeReasons(
                "/videos/1/master.m3u8?VideoCodec=h264&transcodeReasons=VideoCodecNotSupported,%20AudioCodecNotSupported",
            ),
        ).toEqual(["VideoCodecNotSupported", "AudioCodecNotSupported"]);
        expect(transcodeReasons("/videos/1/stream?static=true")).toEqual([]);
        expect(transcodeReasons(undefined)).toEqual([]);
    });
    it("is a title plus one line of at most 20 words with the web action path", () => {
        for (const reason of [
            "VideoCodecNotSupported",
            "VideoRangeTypeNotSupported",
            "VideoLevelNotSupported",
            "VideoResolutionNotSupported",
            "VideoFramerateNotSupported",
            "InterlacedVideoNotSupported",
            "AnamorphicVideoNotSupported",
            "VideoRotationNotSupported",
            "SubtitleCodecNotSupported",
        ]) {
            const n = conversionNotice(
                {
                    method: "Transcode",
                    url,
                    reasons: [reason],
                    source: {
                        MediaStreams: [
                            {
                                Type: "Subtitle",
                                Index: 3,
                                DeliveryMethod: "Encode",
                            },
                        ],
                    },
                },
                3,
                BROWSER_DEVICE,
            )!;
            expect(n.title).toBe("Converting this video for this browser");
            const line = `${n.reason} ${BROWSER_NOTICE_ACTION}`;
            expect(line.split(/\s+/).length).toBeLessThanOrEqual(20);
            expect(line).not.toMatch(/transcod/i);
        }
    });
});

describe("top-positioned cue classifier", () => {
    it.each([
        [10, false, true],
        [49.9, false, true],
        [50, false, false],
        [90, false, false],
        [0, true, true],
        [2, true, true],
        [-1, true, false],
        ["auto", true, false],
        ["auto", false, false],
    ])("line %s snap %s → %s", (line, snap, top) => {
        expect(cueNearTop(line, snap)).toBe(top);
    });
});
