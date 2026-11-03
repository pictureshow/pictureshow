/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import {
    BROWSER_DEVICE,
    BROWSER_NOTICE_ACTIONS,
    browserFamily,
    conversionKind,
    conversionRemedies,
    conversionSummary,
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
        ["misspelled method", { ...good.input, method: "Transcod" }],
        ["lower-case method", { ...good.input, method: "transcode" }],
        ["NaN subtitle index", { ...good.input, subtitleIndex: Number.NaN }],
        [
            "infinite subtitle index",
            { ...good.input, subtitleIndex: Number.POSITIVE_INFINITY },
        ],
        ["fractional subtitle index", { ...good.input, subtitleIndex: 2.5 }],
        ["subtitle index below -1", { ...good.input, subtitleIndex: -2 }],
        ["array source", { ...good.input, source: [] }],
        [
            "misspelled source key",
            { ...good.input, source: { MediaStream: [] } },
        ],
        [
            "MediaStreams not an array",
            { ...good.input, source: { MediaStreams: {} } },
        ],
        [
            "stream not an object",
            { ...good.input, source: { MediaStreams: ["Subtitle"] } },
        ],
        [
            "stream with unknown Type",
            {
                ...good.input,
                source: { MediaStreams: [{ Type: "subtitle", Index: 3 }] },
            },
        ],
        [
            "stream with NaN Index",
            {
                ...good.input,
                source: {
                    MediaStreams: [{ Type: "Subtitle", Index: Number.NaN }],
                },
            },
        ],
        [
            "stream with misspelled key",
            {
                ...good.input,
                source: {
                    MediaStreams: [
                        { Type: "Subtitle", Index: 3, Delivery: "Encode" },
                    ],
                },
            },
        ],
        [
            "stream with non-string DeliveryMethod",
            {
                ...good.input,
                source: {
                    MediaStreams: [
                        { Type: "Subtitle", Index: 3, DeliveryMethod: 1 },
                    ],
                },
            },
        ],
        ["unknown input key", { ...good.input, subtitle: 3 }],
    ])("fails a malformed conversion-notice case: %s", (_, input) => {
        expect(() => runVector({ ...good, input })).toThrow(/Malformed/);
    });
    it("a typo'd negative case cannot pass as an expected none", () => {
        // Before strict validation this returned "none" and so matched.
        const typo = {
            id: "typo",
            operation: "conversion-notice",
            input: { ...good.input, method: "Transcod" },
            expected: "none",
        };
        expect(() => runVector(typo)).toThrow(/method is not/);
        const nanIndex = {
            id: "nan",
            operation: "conversion-notice",
            input: {
                ...good.input,
                reasons: ["SubtitleCodecNotSupported"],
                subtitleIndex: Number.NaN,
            },
            expected: "none",
        };
        expect(() => runVector(nanIndex)).toThrow(/subtitleIndex/);
    });
    it("accepts every well-formed shape the shared vectors use", () => {
        expect(
            runVector({
                ...good,
                input: {
                    ...good.input,
                    method: "DirectStream",
                    subtitleIndex: -1,
                    source: {
                        MediaStreams: [
                            { Type: "Video", Index: 0, Codec: "hevc" },
                            {
                                Type: "Subtitle",
                                Index: 3,
                                Codec: "pgssub",
                                DeliveryMethod: "Encode",
                            },
                        ],
                    },
                },
            }),
        ).toBe("none");
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
            for (const action of Object.values(BROWSER_NOTICE_ACTIONS)) {
                const line = `${n.reason} ${action}`;
                expect(line.split(/\s+/).length).toBeLessThanOrEqual(20);
                expect(line).not.toMatch(/transcod/i);
            }
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

describe("PLAY-26 web plain summary and remedies", () => {
    const transcode = (reasons: string, extra = "") =>
        `https://server.example/v/master.m3u8?VideoCodec=h264${extra}&TranscodeReasons=${reasons}`;
    it("touch and pointer lines never name a key; the keyboard line never says tap or click", () => {
        expect(BROWSER_NOTICE_ACTIONS.touch).not.toMatch(/press|down|click/i);
        expect(BROWSER_NOTICE_ACTIONS.mouse).not.toMatch(/press|down|tap/i);
        expect(BROWSER_NOTICE_ACTIONS.keyboard).not.toMatch(/tap|click/i);
        for (const line of Object.values(BROWSER_NOTICE_ACTIONS))
            expect(line).not.toMatch(/\bit\b/i);
    });
    it.each([
        [
            "Transcode",
            transcode("VideoCodecNotSupported,AudioCodecNotSupported"),
            false,
            "video",
        ],
        [
            "Transcode",
            "https://server.example/v/master.m3u8?VideoCodec=copy&AudioCodec=aac&TranscodeReasons=VideoCodecNotSupported",
            false,
            "unexplained",
        ],
        ["Transcode", transcode("SubtitleCodecNotSupported"), true, "burn-in"],
        [
            "Transcode",
            transcode("ContainerBitrateExceedsLimit"),
            false,
            "bitrate",
        ],
        ["Transcode", transcode("AudioCodecNotSupported"), false, "audio"],
        ["Transcode", transcode("SomethingNew"), false, "unexplained"],
        [
            "Transcode",
            "https://server.example/v/master.m3u8?VideoCodec=copy&AudioCodec=copy",
            false,
            "remux",
        ],
        ["DirectStream", "x", false, "remux"],
        ["DirectPlay", "x", false, "direct"],
    ])("%s %s burn-in=%s is %s", (method, url, burnedIn, kind) => {
        expect(
            conversionKind(
                method,
                url,
                (url.match(/TranscodeReasons=([^&]*)/)?.[1] ?? "")
                    .split(",")
                    .filter(Boolean),
                burnedIn,
            ),
        ).toBe(kind);
    });
    it("summaries are one plain sentence that says convert and never transcode", () => {
        const kinds = [
            "direct",
            "remux",
            "video",
            "burn-in",
            "bitrate",
            "audio",
            "unexplained",
        ] as const;
        for (const kind of kinds) {
            const s = conversionSummary(kind, ["VideoCodecNotSupported"], {
                videoFormat: "HEVC",
                qualityMbps: 8,
            });
            expect(s).not.toMatch(/transcod|codec|decode/i);
            expect(s.match(/[.!?](\s|$)/g)).toHaveLength(1);
        }
        expect(
            conversionSummary("video", ["VideoCodecNotSupported"], {
                videoFormat: "HEVC",
            }),
        ).toBe(
            "The server is converting this video because this browser can't play its video format (HEVC).",
        );
        expect(conversionSummary("bitrate", [], { qualityMbps: 8 })).toContain(
            "8 Mbps",
        );
    });
    it.each([
        [
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
            "chrome",
        ],
        [
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/141.0 Safari/537.36",
            "chrome",
        ],
        [
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36 Edg/141.0",
            "edge",
        ],
        [
            "Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0",
            "firefox",
        ],
        [
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            "safari",
        ],
        [
            "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/141.0 Mobile/15E148 Safari/604.1",
            "safari",
        ],
    ])("browser family of %s is %s", (ua, family) => {
        expect(browserFamily(ua)).toBe(family);
    });
    it("every browser gets a useful video remedy, and Edge is never told to try Edge", () => {
        for (const b of [
            "chrome",
            "edge",
            "firefox",
            "safari",
            "other",
        ] as const) {
            const r = conversionRemedies("video", b);
            expect(r.length).toBeGreaterThanOrEqual(2);
            expect(r.join(" ")).not.toMatch(/transcod|codec|chrome:\/\//i);
        }
        expect(conversionRemedies("video", "edge").join(" ")).not.toMatch(
            /Try Microsoft Edge/,
        );
        expect(conversionRemedies("video", "firefox").join(" ")).toMatch(
            /Chrome or Edge/,
        );
        expect(conversionRemedies("direct", "chrome")).toEqual([]);
    });
});
