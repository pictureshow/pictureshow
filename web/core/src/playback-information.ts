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
            parsed.searchParams.getAll(key).some((v) => v.toLowerCase() === "copy");
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
    source: { MediaStreams?: Array<Record<string, unknown>> } | null | undefined,
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
    if (
        stream.IsExternal === true ||
        stream.DeliveryMethod === "External"
    )
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
