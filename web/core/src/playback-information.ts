/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */

const reasons: Record<string, string> = {
    containernotsupported:
        "This device cannot open the file's container. The server repackages the stream into a compatible container.",
    videocodecnotsupported:
        "This device cannot decode this video format. The server converts the video.",
    audiocodecnotsupported:
        "This device cannot decode this audio format. The server converts the audio.",
    subtitlecodecnotsupported:
        "This device cannot display this subtitle format. The server converts the subtitles or burns them into the video.",
    audioisexternal:
        "The audio is in a separate file. The server combines it with the video.",
    secondaryaudionotsupported:
        "This device cannot play the selected secondary audio directly. The server converts the audio.",
    streamcountexceedslimit:
        "The file contains more streams than this device supports. The server selects compatible streams.",
    videoprofilenotsupported:
        "This device does not support the video's codec profile. The server converts the video.",
    videorangetypenotsupported:
        "This device does not support this video's HDR or color range. The server converts the video.",
    videocodectagnotsupported:
        "This device does not recognize the video's codec tag. The server makes a compatible stream.",
    videolevelnotsupported:
        "This video exceeds the decoder's supported codec level. The server converts the video.",
    videoresolutionnotsupported:
        "This video's resolution exceeds this device's limit. The server reduces the resolution.",
    videobitdepthnotsupported:
        "This device cannot decode the video's bit depth. The server converts the video.",
    videoframeratenotsupported:
        "This video's frame rate exceeds this device's limit. The server reduces the frame rate.",
    videorotationnotsupported:
        "This device cannot apply the video's rotation. The server rotates the video.",
    refframesnotsupported:
        "This video needs more reference frames than the decoder supports. The server converts the video.",
    anamorphicvideonotsupported:
        "This device cannot display the video's non-square pixels correctly. The server converts the video.",
    interlacedvideonotsupported:
        "This device cannot display this interlaced video directly. The server deinterlaces it.",
    audiochannelsnotsupported:
        "The selected audio has more channels than the current output supports. The server converts the audio.",
    audioprofilenotsupported:
        "This device does not support the audio's codec profile. The server converts the audio.",
    audiosampleratenotsupported:
        "This device does not support the audio's sample rate. The server converts the audio.",
    audiobitdepthnotsupported:
        "This device does not support the audio's bit depth. The server converts the audio.",
    audiobitratenotsupported:
        "The audio exceeds a supported bitrate limit. The server lowers its bitrate.",
    unknownvideostreaminfo:
        "The server could not identify the video's properties.",
    unknownaudiostreaminfo:
        "The server could not identify the audio's properties.",
    directplayerror:
        "This device can't play the file as it is, so the server provides a stream it can play.",
};

const unknownReason =
    "The server reported an additional conversion requirement that this client does not recognize.";

/** Translate Jellyfin's enum into safe, device-neutral text without echoing unknown values. */
export function transcodeReason(reason: unknown, qualityMbps?: unknown) {
    const code = typeof reason === "string" ? reason.toLowerCase() : "";
    if (
        typeof qualityMbps === "number" &&
        Number.isFinite(qualityMbps) &&
        qualityMbps > 0 &&
        ["containerbitrateexceedslimit", "videobitratenotsupported"].includes(
            code,
        )
    )
        return `Quality is capped at ${qualityMbps} Mbps in Settings. The server lowers the bitrate.`;
    if (code === "videobitratenotsupported")
        return "This stream exceeds a device or server bitrate limit. The server lowers its bitrate.";
    return reasons[code] ?? unknownReason;
}

function allCopy(url: unknown) {
    if (typeof url !== "string") return false;
    try {
        const parsed = new URL(url, "https://pictureshow.invalid");
        const copy = (key: string) =>
            parsed.searchParams
                .getAll(key)
                .some((v) => v.toLowerCase() === "copy");
        return copy("VideoCodec") && copy("AudioCodec");
    } catch {
        return false;
    }
}

export function playbackMethod(method: unknown, url?: unknown) {
    if (method === "DirectPlay") return "Direct play";
    if (method === "DirectStream") return "Direct stream (remux)";
    if (method === "Transcode")
        return allCopy(url) ? "Direct stream (remux)" : "Transcode";
    return "Not reported";
}

export function subtitleDelivery(
    source:
        { MediaStreams?: Array<Record<string, unknown>> } | null | undefined,
    index: unknown,
) {
    if (index === -1) return "Off";
    if (typeof index !== "number" || !Array.isArray(source?.MediaStreams))
        return "Not reported";
    const stream = source.MediaStreams.find(
        (candidate) =>
            candidate.Type === "Subtitle" && candidate.Index === index,
    );
    if (!stream) return "Not reported";
    if (stream.DeliveryMethod === "Encode") return "Burned in";
    if (stream.IsExternal === true || stream.DeliveryMethod === "External")
        return "External";
    return "Embedded";
}

export function formatBitrate(bitrate: unknown) {
    if (
        typeof bitrate !== "number" ||
        !Number.isFinite(bitrate) ||
        bitrate <= 0
    )
        return "Not reported";
    if (bitrate < 1_000_000) return `${Math.round(bitrate / 1000)} kbps`;
    return `${(bitrate / 1_000_000).toFixed(2)} Mbps`;
}

const speedChoices = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2] as const;
export function speedSupported(speed: unknown, encoded: boolean) {
    return (
        typeof speed === "number" &&
        speedChoices.includes(speed as (typeof speedChoices)[number]) &&
        (!encoded || speed === 1)
    );
}

/** PLAY-27: the default device noun; each platform passes its own ("this browser" on web). */
export const CONVERSION_DEVICE = "this device";
export const BROWSER_DEVICE = "this browser";
/**
 * Web's path to Playback info, written for the input in use: the notice is clickable or
 * tappable, and from the keyboard Down reveals the controls that hold Playback info.
 */
export const BROWSER_NOTICE_ACTIONS = {
    mouse: "Click this note for details and fixes.",
    touch: "Tap this note for details and fixes.",
    keyboard: "Press Down, then choose Playback info.",
} as const;
export type NoticeInput = keyof typeof BROWSER_NOTICE_ACTIONS;

const copyFix = (copy: string) =>
    `It still plays, and ${copy} on the server plays directly.`;

/**
 * Ordered by how useful fixing it is: the first category lets the video play directly.
 * Each carries the one true next step (core ConversionNotice): nothing in the browser fixes a
 * video limit, so it names a copy that would play as it is.
 */
const noticeCategories: Array<[string[], string, string]> = [
    [
        ["videocodecnotsupported"],
        "Its video format won't play on %s.",
        copyFix("a 1080p H.264 copy"),
    ],
    [
        ["videorangetypenotsupported"],
        "Its HDR format won't play on %s.",
        copyFix("an SDR copy"),
    ],
    [
        [
            "videoprofilenotsupported",
            "videolevelnotsupported",
            "videobitdepthnotsupported",
            "refframesnotsupported",
        ],
        "Its video encoding is too demanding for %s.",
        copyFix("an 8-bit 1080p H.264 copy"),
    ],
    [
        ["videoresolutionnotsupported"],
        "Its resolution is too high for %s.",
        copyFix("a 1080p copy"),
    ],
    [
        ["videoframeratenotsupported"],
        "Its frame rate is too high for %s.",
        copyFix("a 30 fps copy"),
    ],
    [
        ["interlacedvideonotsupported"],
        "Its interlaced video won't play on %s.",
        copyFix("a deinterlaced copy"),
    ],
    [
        ["anamorphicvideonotsupported"],
        "Its non-square pixels won't display on %s.",
        copyFix("a square-pixel copy"),
    ],
    [
        ["videorotationnotsupported"],
        "Its rotation won't display on %s.",
        copyFix("an upright copy"),
    ],
];
const burnedInReason = "The selected subtitles must be burned in for %s.";
const burnedInFix =
    "Choose text subtitles, such as SRT, or turn them off to avoid it.";

export type ConversionNotice = {
    title: string;
    reason: string;
    /** The one next step the viewer, or whoever keeps the server's files, can take. */
    fix: string;
    /** True when the reason is subtitle burn-in rather than a video limit. */
    burnIn: boolean;
};

export function conversionNoticeTitle(device = CONVERSION_DEVICE) {
    return `Converting this video for ${device}`;
}

/** The URL's single parameter value, matching the name case-insensitively. */
function urlParameter(url: unknown, name: string) {
    if (typeof url !== "string") return undefined;
    try {
        const parsed = new URL(url, "https://pictureshow.invalid");
        const values = [...parsed.searchParams.entries()]
            .filter(([key]) => key.toLowerCase() === name.toLowerCase())
            .map(([, value]) => value);
        return values.length === 1 ? values[0].toLowerCase() : undefined;
    } catch {
        return undefined;
    }
}

/** Jellyfin lists its reasons in the transcoding URL's TranscodeReasons parameter. */
export function transcodeReasons(url: unknown): string[] {
    if (typeof url !== "string") return [];
    try {
        const parsed = new URL(url, "https://pictureshow.invalid");
        return [...parsed.searchParams.entries()]
            .filter(([key]) => key.toLowerCase() === "transcodereasons")
            .flatMap(([, value]) => value.split(","))
            .map((value) => value.trim())
            .filter(Boolean);
    } catch {
        return [];
    }
}

/**
 * PLAY-27. Returns null when no notice applies. Only a device limit that re-encodes
 * the video qualifies; a copied video stream is a remux unless burn-in is reported.
 */
export function conversionNotice(
    decision: {
        method: unknown;
        url: unknown;
        reasons: readonly unknown[];
        source?: { MediaStreams?: Array<Record<string, unknown>> } | null;
    },
    subtitleIndex: unknown,
    device = CONVERSION_DEVICE,
): ConversionNotice | null {
    if (decision.method !== "Transcode") return null;
    const reasons = new Set(
        decision.reasons
            .filter((r): r is string => typeof r === "string")
            .map((r) => r.toLowerCase()),
    );
    const burnedIn =
        reasons.has("subtitlecodecnotsupported") &&
        subtitleDelivery(decision.source, subtitleIndex) === "Burned in";
    const copied =
        playbackMethod(decision.method, decision.url) !== "Transcode" ||
        urlParameter(decision.url, "VideoCodec") === "copy";
    const subtitle: [string, string] | undefined = burnedIn
        ? [burnedInReason, burnedInFix]
        : undefined;
    const category = copied
        ? undefined
        : noticeCategories.find(([codes]) =>
              codes.some((code) => reasons.has(code)),
          );
    const chosen: [string, string] | undefined = category
        ? [category[1], category[2]]
        : subtitle;
    if (!chosen) return null;
    const [why, fix] = chosen;
    return {
        title: conversionNoticeTitle(device),
        reason: why.replaceAll("%s", device),
        fix,
        burnIn: why === burnedInReason,
    };
}

const noticeInputKeys = new Set([
    "method",
    "url",
    "reasons",
    "device",
    "subtitleIndex",
    "source",
]);
const noticeMethods = new Set(["DirectPlay", "DirectStream", "Transcode"]);
const streamKeys = new Set([
    "Type",
    "Index",
    "Codec",
    "DeliveryMethod",
    "IsExternal",
]);
/** Jellyfin SubtitleDeliveryMethod values. */
const deliveryMethods = new Set(["Encode", "Embed", "External", "Hls", "Drop"]);
const plainObject = (v: unknown): v is Record<string, unknown> =>
    !!v && typeof v === "object" && !Array.isArray(v);
const nonEmptyString = (v: unknown): v is string =>
    typeof v === "string" && v.trim() !== "";

/** Why a conversion-notice vector input is malformed, or undefined when it is well formed. */
export function conversionNoticeInputProblem(
    input: unknown,
    extraKeys: readonly string[] = [],
) {
    if (!plainObject(input)) return "input is not an object";
    const unknownKey = Object.keys(input).find(
        (k) => !noticeInputKeys.has(k) && !extraKeys.includes(k),
    );
    if (unknownKey) return `unknown input key ${unknownKey}`;
    if (!noticeMethods.has(input.method as string))
        return "method is not DirectPlay, DirectStream or Transcode";
    if (typeof input.url !== "string") return "url is not a string";
    if (
        !Array.isArray(input.reasons) ||
        !input.reasons.every((r) => typeof r === "string")
    )
        return "reasons is not an array of strings";
    if (!nonEmptyString(input.device))
        return "device is not a non-empty string";
    if (
        input.subtitleIndex !== undefined &&
        !(
            Number.isInteger(input.subtitleIndex) &&
            (input.subtitleIndex as number) >= -1
        )
    )
        return "subtitleIndex is not an integer of at least -1";
    if (input.source !== undefined) {
        if (!plainObject(input.source)) return "source is not an object";
        const sourceKey = Object.keys(input.source).find(
            (k) => k !== "MediaStreams",
        );
        if (sourceKey) return `unknown source key ${sourceKey}`;
        const streams = input.source.MediaStreams;
        if (streams !== undefined) {
            if (!Array.isArray(streams))
                return "source.MediaStreams is not an array";
            for (const stream of streams) {
                if (!plainObject(stream)) return "a stream is not an object";
                const key = Object.keys(stream).find((k) => !streamKeys.has(k));
                if (key) return `unknown stream key ${key}`;
                if (
                    !["Video", "Audio", "Subtitle"].includes(
                        stream.Type as string,
                    )
                )
                    return "a stream Type is not Video, Audio or Subtitle";
                if (
                    !Number.isInteger(stream.Index) ||
                    (stream.Index as number) < 0
                )
                    return "a stream Index is not a non-negative integer";
                if (stream.Codec !== undefined && !nonEmptyString(stream.Codec))
                    return "a stream Codec is not a non-empty string";
                if (
                    stream.DeliveryMethod !== undefined &&
                    !deliveryMethods.has(stream.DeliveryMethod as string)
                )
                    return "a stream DeliveryMethod is not Encode, Embed, External, Hls or Drop";
                if (
                    stream.IsExternal !== undefined &&
                    typeof stream.IsExternal !== "boolean"
                )
                    return "a stream IsExternal is not a boolean";
            }
        }
    }
    // A selected subtitle must be a stream the case carries, or the case no longer
    // exercises what it says; -1 (or absent) is the format's "no subtitle".
    if (typeof input.subtitleIndex === "number" && input.subtitleIndex >= 0) {
        const streams =
            plainObject(input.source) &&
            Array.isArray(input.source.MediaStreams)
                ? (input.source.MediaStreams as Array<Record<string, unknown>>)
                : [];
        const matches = streams.filter(
            (s) => s.Type === "Subtitle" && s.Index === input.subtitleIndex,
        ).length;
        if (matches !== 1)
            return `subtitleIndex ${input.subtitleIndex} matches ${matches} Subtitle streams, not exactly one`;
    }
    return undefined;
}

/** Strictly validated vector input; malformed cases throw instead of passing. */
export function conversionNoticeVector(input: unknown): string {
    const problem = conversionNoticeInputProblem(input);
    if (problem)
        throw new Error(`Malformed conversion-notice vector input: ${problem}`);
    const i = input as Record<string, unknown> & {
        device: string;
        reasons: string[];
    };
    const notice = conversionNotice(
        {
            method: i.method,
            url: i.url,
            reasons: i.reasons,
            source: i.source as {
                MediaStreams?: Array<Record<string, unknown>>;
            },
        },
        i.subtitleIndex ?? -1,
        i.device,
    );
    return notice ? `${notice.title}\n${notice.reason}\n${notice.fix}` : "none";
}

function record(input: unknown) {
    if (!input || typeof input !== "object" || Array.isArray(input))
        throw new Error("Malformed playback-information vector input");
    return input as Record<string, unknown>;
}

/**
 * The strict runner for `playback-information.json`. Every operation is known here;
 * an unknown operation or a malformed input throws so the vector fails.
 */
export function runVector(c: {
    id?: unknown;
    operation?: unknown;
    input?: unknown;
    expected?: unknown;
}): unknown {
    if (typeof c?.id !== "string" || !("expected" in c))
        throw new Error("Malformed playback-information vector case");
    const i = record(c.input);
    switch (c.operation) {
        case "reason":
            if (!("reason" in i))
                throw new Error("Malformed reason vector input");
            return transcodeReason(i.reason, i.qualityMbps);
        case "method":
            return playbackMethod(i.method, i.url);
        case "subtitle":
            if (!("index" in i))
                throw new Error("Malformed subtitle vector input");
            return subtitleDelivery(
                i.source as { MediaStreams?: Array<Record<string, unknown>> },
                i.index,
            );
        case "bitrate":
            return formatBitrate(i.bitrate);
        case "speed":
            if (typeof i.encoded !== "boolean")
                throw new Error("Malformed speed vector input");
            return String(speedSupported(i.speed, i.encoded));
        case "conversion-notice":
            return conversionNoticeVector(i);
        case "conversion-explanation":
            return conversionExplanationVector(i);
        default:
            throw new Error(
                "Unknown playback-information operation: " +
                    String(c.operation),
            );
    }
}

/**
 * PLAY-27 yields to top-positioned cues: a fraction line in the top half, or a
 * non-negative line number. "auto" and negative (bottom-relative) lines do not count.
 */
export function cueNearTop(line: unknown, snapToLines: unknown) {
    if (typeof line !== "number" || !Number.isFinite(line)) return false;
    return snapToLines === false ? line < 50 : line >= 0;
}

/**
 * PLAY-27 web note: Chrome/Edge steps, shown in Playback info's technical details only
 * when this browser's own decision is the limit. They diagnose; the "What you can do"
 * remedies own the advice (such as trying another browser), so no step repeats one.
 */
export function chromiumDecodeSteps(browser: "chrome" | "edge"): string[] {
    const scheme = browser === "edge" ? "edge" : "chrome";
    const relaunch = browser === "edge" ? "Restart" : "Relaunch";
    return [
        `Open ${scheme}://settings/system, turn on “Use graphics acceleration when available”, then select ${relaunch}.`,
        `Open ${scheme}://gpu and look under “Video Acceleration Information” for a “Decode hevc main” (or “main 10”) row. It is missing without a GPU and driver that decode it, and over Remote Desktop or in a virtual machine.`,
        "Update the graphics driver from its vendor, or through Windows Update optional updates.",
    ];
}
/** Chrome's steps; kept for callers that predate Edge detection. */
export const CHROMIUM_DECODE_STEPS = chromiumDecodeSteps("chrome");

const lower = (codes: readonly string[]) =>
    new Set(codes.map((c) => c.toLowerCase()));
const videoWhat: Array<[string[], string]> = [
    [["videocodecnotsupported"], "video format"],
    [["videorangetypenotsupported"], "HDR format"],
    [
        [
            "videoprofilenotsupported",
            "videolevelnotsupported",
            "videobitdepthnotsupported",
            "refframesnotsupported",
        ],
        "video encoding",
    ],
    [["videoresolutionnotsupported"], "resolution"],
    [["videoframeratenotsupported"], "frame rate"],
    [["interlacedvideonotsupported"], "interlaced video"],
    [["anamorphicvideonotsupported"], "non-square pixels"],
    [["videorotationnotsupported"], "rotation"],
];
const bitrateCodes = [
    "containerbitrateexceedslimit",
    "videobitratenotsupported",
    "audiobitratenotsupported",
];
const audioFormatCodes = [
    "audiocodecnotsupported",
    "audioprofilenotsupported",
    "audiosampleratenotsupported",
    "audiobitdepthnotsupported",
    "secondaryaudionotsupported",
];
/**
 * Reasons that only change the package: the server re-encodes a stream only when a reason
 * says that stream must change. Jellyfin 12 sends DirectPlayError whenever no direct-play
 * profile matched the file (an MKV on the web), with both streams copied.
 */
const repackageCodes = [
    "directplayerror",
    "containernotsupported",
    "videocodectagnotsupported",
];

/** What the viewer can act on, most useful first. */
export type ConversionKind =
    | "direct"
    | "remux"
    | "video"
    | "burn-in"
    | "bitrate"
    | "audio"
    | "audio-channels"
    | "unexplained";

export function conversionKind(
    method: unknown,
    url: unknown,
    codes: readonly string[],
    burnedIn: boolean,
): ConversionKind {
    const label = playbackMethod(method, url);
    if (label === "Direct play") return "direct";
    if (label !== "Transcode")
        return label === "Not reported" ? "unexplained" : "remux";
    const set = lower(codes);
    if (
        urlParameter(url, "VideoCodec") !== "copy" &&
        videoWhat.some(([c]) => c.some((code) => set.has(code)))
    )
        return "video";
    // Burning subtitles in always re-encodes the picture. Jellyfin often sends no
    // SubtitleCodecNotSupported for it (an image subtitle on the web), so the
    // subtitle's delivery method alone decides.
    if (burnedIn) return "burn-in";
    if (bitrateCodes.some((c) => set.has(c))) return "bitrate";
    if (audioFormatCodes.some((c) => set.has(c))) return "audio";
    if (set.has("audiochannelsnotsupported")) return "audio-channels";
    if (set.size && [...set].every((c) => repackageCodes.includes(c)))
        return "remux";
    return "unexplained";
}

/** PLAY-26 web: one plain sentence that opens Playback info. Uses "convert", never "transcode". */
export function conversionSummary(
    kind: ConversionKind,
    codes: readonly string[],
    options: {
        device?: string;
        videoFormat?: string;
        qualityMbps?: number;
        /** The stream URL, whose TranscodingMaxAudioChannels names the mix-down target. */
        url?: unknown;
    } = {},
) {
    const device = options.device ?? BROWSER_DEVICE;
    switch (kind) {
        case "direct":
            return `${capitalize(device)} is playing the file directly, with no conversion.`;
        case "remux":
            return `The server is repackaging this video for ${device} without converting it, so the quality is unchanged.`;
        case "video": {
            const set = lower(codes);
            const what =
                videoWhat.find(([c]) => c.some((code) => set.has(code)))?.[1] ??
                "video format";
            const format =
                what === "video format" && options.videoFormat
                    ? ` (${options.videoFormat})`
                    : "";
            return `The server is converting this video because ${device} can't play its ${what}${format}.`;
        }
        case "burn-in":
            return "The server is converting this video to burn the selected subtitles into the picture.";
        case "bitrate":
            return typeof options.qualityMbps === "number" &&
                Number.isFinite(options.qualityMbps) &&
                options.qualityMbps > 0
                ? `The server is converting this video to keep it under your ${options.qualityMbps} Mbps quality limit.`
                : "The server is converting this video to keep it under a bitrate limit.";
        case "audio":
            return `The server is converting this video's sound into a format ${device} can play.`;
        case "audio-channels": {
            const target =
                {
                    "1": "mono",
                    "2": "stereo",
                    "6": "5.1",
                    "8": "7.1",
                }[urlParameter(options.url, "TranscodingMaxAudioChannels") ?? ""] ??
                "fewer channels";
            return `The server is mixing this video's sound down to ${target} for ${device}, and nothing needs fixing.`;
        }
        case "unexplained":
            return "The server is converting this video and did not say why.";
    }
}
const capitalize = (text: string) =>
    text.charAt(0).toUpperCase() + text.slice(1);

export type BrowserFamily = "chrome" | "edge" | "firefox" | "safari" | "other";
/**
 * The browser, from Client Hints brands when the browser gives them (Edge lists
 * "Microsoft Edge"), else from the user agent string.
 */
export function browserFamily(
    userAgent: string,
    brands?: readonly string[],
): BrowserFamily {
    if (brands?.some((b) => b === "Microsoft Edge")) return "edge";
    if (/\bEdg(e|A|iOS)?\//.test(userAgent)) return "edge";
    if (/(iPhone|iPad|iPod)/.test(userAgent)) return "safari";
    if (/\b(Firefox|FxiOS)\//.test(userAgent)) return "firefox";
    if (/(Chrome|Chromium)\//.test(userAgent) && !/\bOPR\//.test(userAgent))
        return "chrome";
    if (/\bSafari\//.test(userAgent) && /\bVersion\//.test(userAgent))
        return "safari";
    return "other";
}
export type OsFamily = "windows" | "mac" | "other";
/** The operating system, from Client Hints platform when given, else the user agent. */
export function osFamily(userAgent: string, platform?: string): OsFamily {
    const text = platform || userAgent;
    if (/Windows/i.test(text)) return "windows";
    if (
        /(macOS|Macintosh|Mac OS X)/i.test(text) &&
        !/(iPhone|iPad|iPod)/.test(userAgent)
    )
        return "mac";
    return "other";
}

const appRemedy = "Use a Pictureshow app on your TV, phone or computer.";
/** Plain remedies for the viewer, tailored to the browser; empty when nothing helps. */
export function conversionRemedies(
    kind: ConversionKind,
    browser: BrowserFamily,
    os: OsFamily = "other",
    /** Other audio tracks that play as they are (`playableOtherAudioTracks`). */
    audioTracks = 0,
): string[] {
    switch (kind) {
        case "video":
            switch (browser) {
                case "chrome":
                    return [
                        "Turn on “Use graphics acceleration when available” in Chrome's System settings, then relaunch Chrome.",
                        // Edge's extra formats (HEVC through Windows) are a Windows fact; on a Mac, Safari is the better browser.
                        ...(os === "windows"
                            ? [
                                  "Try Microsoft Edge, which plays more video formats on Windows.",
                              ]
                            : os === "mac"
                              ? [
                                    "Try Safari, which plays more video formats on a Mac.",
                                ]
                              : []),
                        appRemedy,
                    ];
                case "edge":
                    return [
                        "Turn on “Use graphics acceleration when available” in Edge's System and performance settings, then restart Edge.",
                        appRemedy,
                    ];
                case "firefox":
                    return [
                        "Open Pictureshow in Chrome or Edge, which play more video formats.",
                        appRemedy,
                    ];
                case "safari":
                    return [
                        "Update macOS or iOS; newer versions of Safari play more video formats.",
                        appRemedy,
                    ];
                default:
                    return [
                        "Open Pictureshow in Chrome, Edge or Safari, which play more video formats.",
                        appRemedy,
                    ];
            }
        default:
            return sharedRemedies(kind, audioTracks, BROWSER_DEVICE);
    }
}

/**
 * PLAY-26: how many of the source's other audio tracks the server would copy as they are.
 * The transcoding URL names the formats its stream carries (`AudioCodec`) and the track
 * being converted (`AudioStreamIndex`); a track in another format is converted too, so
 * advice to switch tracks needs a track in one of those formats. No list, no advice.
 */
export function playableOtherAudioTracks(
    source: { MediaStreams?: Array<Record<string, unknown>> } | null | undefined,
    url: unknown,
): number {
    const name = (codec: unknown) => {
        const c = typeof codec === "string" ? codec.trim().toLowerCase() : "";
        return c === "dca" ? "dts" : c;
    };
    const carried = new Set(
        (urlParameter(url, "AudioCodec") ?? "")
            .split(",")
            .map(name)
            .filter((c) => c && c !== "copy"),
    );
    const selected = Number(urlParameter(url, "AudioStreamIndex"));
    return (source?.MediaStreams ?? []).filter(
        (s) =>
            s.Type === "Audio" &&
            !(Number.isInteger(selected) && s.Index === selected) &&
            carried.has(name(s.Codec)),
    ).length;
}

/**
 * PLAY-26 remedies every platform shares. A video limit has none here: its fixes depend on
 * the platform (web adds browser steps). A remedy that names another track needs one that
 * plays without conversion (`playableOtherAudioTracks`).
 */
export function sharedRemedies(
    kind: ConversionKind,
    playableAudioTracks: number,
    device = CONVERSION_DEVICE,
): string[] {
    switch (kind) {
        case "burn-in":
            return [
                "Turn subtitles off, or choose a text subtitle track, so they don't need to be burned in.",
            ];
        case "bitrate":
            return [
                "Raise Max streaming bitrate in Settings to try the original quality.",
            ];
        case "audio":
            return playableAudioTracks >= 1
                ? [
                      `Choose another audio track in Audio; one that ${device} can play needs no conversion.`,
                  ]
                : [];
        default:
            return [];
    }
}

export type ConversionExplanation = {
    kind: ConversionKind;
    /** The Method row, from the same classification as the summary. */
    method: string;
    /** Heading over the server's reasons; undefined when nothing is converted. */
    heading?: string;
    summary: string;
    /** Shared remedies; a platform may add its own for a video limit. */
    remedies: string[];
};

/**
 * The Method row and reasons heading follow the kind, not the URL alone: a Transcode whose
 * reasons only call for repackaging is reported as a direct stream, matching the summary.
 */
export function explainedMethod(
    kind: ConversionKind,
    method: unknown,
    url: unknown,
): { method: string; heading?: string } {
    if (kind === "direct") return { method: "Direct play" };
    if (kind === "remux")
        return {
            method: "Direct stream (repackaged)",
            heading: "Why the server repackages",
        };
    if (playbackMethod(method, url) === "Not reported")
        return { method: "Not reported" };
    return { method: "Transcode", heading: "Why the server converts" };
}

/** PLAY-26: what Playback info leads with, from the decision and the selected subtitle. */
export function conversionExplanation(
    decision: {
        method: unknown;
        url: unknown;
        reasons: readonly unknown[];
        source?: { MediaStreams?: Array<Record<string, unknown>> } | null;
    },
    subtitleIndex: unknown,
    device = CONVERSION_DEVICE,
    options: { qualityMbps?: number; videoFormat?: string } = {},
): ConversionExplanation {
    const codes = decision.reasons.filter(
        (r): r is string => typeof r === "string",
    );
    const kind = conversionKind(
        decision.method,
        decision.url,
        codes,
        subtitleDelivery(decision.source, subtitleIndex) === "Burned in",
    );
    const audioTracks = playableOtherAudioTracks(decision.source, decision.url);
    return {
        kind,
        ...explainedMethod(kind, decision.method, decision.url),
        summary: conversionSummary(kind, codes, {
            ...options,
            device,
            url: decision.url,
        }),
        remedies: sharedRemedies(kind, audioTracks, device),
    };
}

/** Vector form: kind, method, heading (when any), summary, then each shared remedy, one per line. */
export function conversionExplanationVector(input: unknown): string {
    const problem = conversionNoticeInputProblem(input, [
        "qualityMbps",
        "videoFormat",
    ]);
    if (problem)
        throw new Error(
            `Malformed conversion-explanation vector input: ${problem}`,
        );
    const i = input as Record<string, unknown> & {
        device: string;
        reasons: string[];
    };
    if (
        i.qualityMbps !== undefined &&
        !(Number.isInteger(i.qualityMbps) && (i.qualityMbps as number) > 0)
    )
        throw new Error(
            "Malformed conversion-explanation vector input: qualityMbps is not a positive integer",
        );
    if (i.videoFormat !== undefined && !nonEmptyString(i.videoFormat))
        throw new Error(
            "Malformed conversion-explanation vector input: videoFormat is not a non-empty string",
        );
    const e = conversionExplanation(
        {
            method: i.method,
            url: i.url,
            reasons: i.reasons,
            source: i.source as {
                MediaStreams?: Array<Record<string, unknown>>;
            },
        },
        i.subtitleIndex ?? -1,
        i.device,
        {
            qualityMbps: i.qualityMbps as number | undefined,
            videoFormat: i.videoFormat as string | undefined,
        },
    );
    return [
        e.kind,
        `method: ${e.method}`,
        ...(e.heading ? [`heading: ${e.heading}`] : []),
        e.summary,
        ...e.remedies,
    ].join("\n");
}
