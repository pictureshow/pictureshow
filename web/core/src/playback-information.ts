/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */

const reasons: Record<string, string> = {
    containernotsupported:
        "This device cannot open the file's container. The server repackages the stream into a compatible container.",
    videocodecnotsupported:
        "This device cannot decode this video format. The server converts the video. Try a version with a supported video codec.",
    audiocodecnotsupported:
        "This device cannot decode this audio format. The server converts the audio. Choose a compatible audio track if available.",
    subtitlecodecnotsupported:
        "This device cannot display this subtitle format. The server converts the subtitles or burns them into the video. Choose a text subtitle track or turn subtitles off to avoid burn-in.",
    audioisexternal:
        "The audio is in a separate file. The server combines it with the video.",
    secondaryaudionotsupported:
        "This device cannot play the selected secondary audio directly. Choose the default audio track to try direct playback.",
    streamcountexceedslimit:
        "The file contains more streams than this device supports. The server selects compatible streams.",
    videoprofilenotsupported:
        "This device does not support the video's codec profile. The server converts the video.",
    videorangetypenotsupported:
        "This device does not support this video's HDR or color range. The server converts the video. Try an SDR version if available.",
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
        "The selected audio has more channels than the current output supports. The server converts the audio. Choose a track with fewer channels or check your audio output settings.",
    audioprofilenotsupported:
        "This device does not support the audio's codec profile. The server converts the audio. Choose another audio track if available.",
    audiosampleratenotsupported:
        "This device does not support the audio's sample rate. The server converts the audio.",
    audiobitdepthnotsupported:
        "This device does not support the audio's bit depth. The server converts the audio.",
    audiobitratenotsupported:
        "The audio exceeds a supported bitrate limit. The server lowers its bitrate. Choose another audio track if available.",
    unknownvideostreaminfo:
        "The server could not identify the video's properties. Refresh this item's metadata on the server and try again.",
    unknownaudiostreaminfo:
        "The server could not identify the audio's properties. Refresh this item's metadata on the server and try again.",
    directplayerror:
        "Direct playback failed. The server is providing a converted stream. Try another version or check the device's codec support.",
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
        return `Quality is capped at ${qualityMbps} Mbps in Settings. Raise the limit to try the original quality; device or server limits may still require conversion.`;
    if (code === "videobitratenotsupported")
        return "This stream exceeds a device or server bitrate limit. The server lowers its bitrate. Check the server's streaming limits.";
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
/** Web's path to Playback info: the notice itself is clickable; Down reveals the controls. */
export const BROWSER_NOTICE_ACTION =
    "Click or tap it, or press Down, then Playback info.";

/** Ordered by how useful fixing it is: the first category lets the video play directly. */
const noticeCategories: Array<[string[], string]> = [
    [["videocodecnotsupported"], "Its video format won't play on %s."],
    [["videorangetypenotsupported"], "Its HDR format won't play on %s."],
    [
        [
            "videoprofilenotsupported",
            "videolevelnotsupported",
            "videobitdepthnotsupported",
            "refframesnotsupported",
        ],
        "Its video encoding is too demanding for %s.",
    ],
    [["videoresolutionnotsupported"], "Its resolution is too high for %s."],
    [["videoframeratenotsupported"], "Its frame rate is too high for %s."],
    [["interlacedvideonotsupported"], "Its interlaced video won't play on %s."],
    [
        ["anamorphicvideonotsupported"],
        "Its non-square pixels won't display on %s.",
    ],
    [["videorotationnotsupported"], "Its rotation won't display on %s."],
];
const burnedInReason = "The selected subtitles must be burned in for %s.";

export type ConversionNotice = {
    title: string;
    reason: string;
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
    const why = copied
        ? burnedIn
            ? burnedInReason
            : undefined
        : (noticeCategories.find(([codes]) =>
              codes.some((code) => reasons.has(code)),
          )?.[1] ?? (burnedIn ? burnedInReason : undefined));
    if (!why) return null;
    return {
        title: conversionNoticeTitle(device),
        reason: why.replaceAll("%s", device),
        burnIn: why === burnedInReason,
    };
}

/** Strictly validated vector input; malformed cases throw instead of passing. */
export function conversionNoticeVector(input: unknown): string {
    const i = input as Record<string, unknown> | null;
    if (
        !i ||
        typeof i !== "object" ||
        typeof i.method !== "string" ||
        typeof i.url !== "string" ||
        !Array.isArray(i.reasons) ||
        i.reasons.some((r) => typeof r !== "string") ||
        typeof i.device !== "string" ||
        !i.device ||
        (i.subtitleIndex !== undefined &&
            typeof i.subtitleIndex !== "number") ||
        (i.source !== undefined &&
            (typeof i.source !== "object" || i.source === null))
    )
        throw new Error("Malformed conversion-notice vector input");
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
    return notice ? `${notice.title}\n${notice.reason}` : "none";
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
 * PLAY-27 web note: Chrome/Edge steps, shown before the core remedy in Playback info
 * only when this browser's own decision is the limit.
 */
export const CHROMIUM_DECODE_STEPS = [
    "Open chrome://settings/system, turn on “Use graphics acceleration when available”, then select Relaunch.",
    "Open chrome://gpu and look under “Video Acceleration Information” for a “Decode hevc main” (or “main 10”) row. It is missing without a GPU and driver that decode it, and over Remote Desktop or in a virtual machine.",
    "Update the graphics driver from its vendor, or through Windows Update optional updates.",
    "Try Microsoft Edge.",
    "Use a Pictureshow app for TV, phone or desktop.",
] as const;
