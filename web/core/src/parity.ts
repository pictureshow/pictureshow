/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import {
    authorization,
    encode,
    resolve,
    sameOrigin,
    stripApiKey,
    networkOptions,
    ClientError,
} from "./index";

/**
 * What an entry for this server is called, against the others listed with it. Jellyfin's name is free text and defaults to
 * the machine's hostname, so two entries can share it. The address then tells them apart. A server with no name is its address.
 */
export function serverLabel(
    server: { id: string; name: string; url: string },
    all: { id: string; name: string; url: string }[],
): string {
    const name = server.name.trim();
    if (!name) return server.url;
    const distinct = new Map<string, { name: string }>();
    for (const s of all) {
        const key = s.id + " " + s.url;
        if (!distinct.has(key)) distinct.set(key, s);
    }
    const repeats =
        [...distinct.values()].filter(
            (s) => s.name.trim().toLowerCase() === name.toLowerCase(),
        ).length > 1;
    return repeats ? name + " · " + server.url : name;
}

export const BITRATES = [null, 120, 80, 40, 20, 10, 4] as const;
export const SEGMENT_TYPES = [
    "Intro",
    "Outro",
    "Recap",
    "Preview",
    "Commercial",
] as const;
export type SegmentMode = "Ask" | "Automatic" | "Off";
export type SegmentModes = Record<string, SegmentMode>;
export type Segment = {
    Id: string;
    Type: string;
    StartTicks: number;
    EndTicks: number;
};
export type Settings = {
    bitrateMbps: number | null;
    passthrough: boolean;
    subtitleSize: string;
    subtitleBackground: string;
    segmentModes: SegmentModes;
    maxStreamingBitrate: number;
    passthroughEnabled: boolean;
};
export function validateBitrate(value: unknown) {
    return BITRATES.includes(value as any);
}
export function validateMaxStreamingBitrate(value: number): number {
    if (!Number.isFinite(value) || value <= 0)
        throw new RangeError("Bitrate must be positive.");
    return value;
}
export function decodeSettings(raw: any = {}): Settings {
    const bitrateMbps = validateBitrate(raw.bitrateMbps)
        ? raw.bitrateMbps
        : null;
    const passthrough =
        typeof raw.passthrough === "boolean" ? raw.passthrough : true;
    return {
        bitrateMbps,
        passthrough,
        subtitleSize: [
            "System",
            "Small",
            "Normal",
            "Large",
            "ExtraLarge",
        ].includes(raw.subtitleSize)
            ? raw.subtitleSize
            : "System",
        subtitleBackground: ["System", "None", "Shadow", "Box"].includes(
            raw.subtitleBackground,
        )
            ? raw.subtitleBackground
            : "System",
        segmentModes: Object.fromEntries(
            SEGMENT_TYPES.map((k) => [
                k,
                ["Ask", "Automatic", "Off"].includes(raw.segmentModes?.[k])
                    ? raw.segmentModes[k]
                    : "Ask",
            ]),
        ),
        maxStreamingBitrate:
            bitrateMbps == null ? 200000000 : bitrateMbps * 1000000,
        passthroughEnabled: passthrough,
    };
}
const languages: Record<string, string> = {
    en: "eng",
    es: "spa",
    fr: "fra",
    de: "deu",
    ja: "jpn",
    zh: "zho",
    ko: "kor",
    pt: "por",
    it: "ita",
    ru: "rus",
    ar: "ara",
    hi: "hin",
    sq: "sqi",
    hy: "hye",
    eu: "eus",
    my: "mya",
    cs: "ces",
    nl: "nld",
    ka: "kat",
    el: "ell",
    is: "isl",
    mk: "mkd",
    mi: "mri",
    ms: "msa",
    fa: "fas",
    ro: "ron",
    sk: "slk",
    bo: "bod",
    cy: "cym",
    alb: "sqi",
    arm: "hye",
    baq: "eus",
    bur: "mya",
    chi: "zho",
    cze: "ces",
    dut: "nld",
    fre: "fra",
    geo: "kat",
    ger: "deu",
    gre: "ell",
    ice: "isl",
    mac: "mkd",
    mao: "mri",
    may: "msa",
    per: "fas",
    rum: "ron",
    slo: "slk",
    tib: "bod",
    wel: "cym",
};
// ISO 639-1→639-2 terminology projection from java.util.Locale.getISOLanguages / getISO3Language,
// the same API as Android StreamDefaults. Bibliographic aliases apply only to captions.
const ISO_LANGUAGE: Record<string, string> = Object.fromEntries(
    "aa:aar ab:abk ae:ave af:afr ak:aka am:amh an:arg ar:ara as:asm av:ava ay:aym az:aze ba:bak be:bel bg:bul bh:bih bi:bis bm:bam bn:ben bo:bod br:bre bs:bos ca:cat ce:che ch:cha co:cos cr:cre cs:ces cu:chu cv:chv cy:cym da:dan de:deu dv:div dz:dzo ee:ewe el:ell en:eng eo:epo es:spa et:est eu:eus fa:fas ff:ful fi:fin fj:fij fo:fao fr:fra fy:fry ga:gle gd:gla gl:glg gn:grn gu:guj gv:glv ha:hau he:heb hi:hin ho:hmo hr:hrv ht:hat hu:hun hy:hye hz:her ia:ina id:ind ie:ile ig:ibo ii:iii ik:ipk in:ind io:ido is:isl it:ita iu:iku iw:heb ja:jpn ji:yid jv:jav ka:kat kg:kon ki:kik kj:kua kk:kaz kl:kal km:khm kn:kan ko:kor kr:kau ks:kas ku:kur kv:kom kw:cor ky:kir la:lat lb:ltz lg:lug li:lim ln:lin lo:lao lt:lit lu:lub lv:lav mg:mlg mh:mah mi:mri mk:mkd ml:mal mn:mon mo:mol mr:mar ms:msa mt:mlt my:mya na:nau nb:nob nd:nde ne:nep ng:ndo nl:nld nn:nno no:nor nr:nbl nv:nav ny:nya oc:oci oj:oji om:orm or:ori os:oss pa:pan pi:pli pl:pol ps:pus pt:por qu:que rm:roh rn:run ro:ron ru:rus rw:kin sa:san sc:srd sd:snd se:sme sg:sag si:sin sk:slk sl:slv sm:smo sn:sna so:som sq:sqi sr:srp ss:ssw st:sot su:sun sv:swe sw:swa ta:tam te:tel tg:tgk th:tha ti:tir tk:tuk tl:tgl tn:tsn to:ton tr:tur ts:tso tt:tat tw:twi ty:tah ug:uig uk:ukr ur:urd uz:uzb ve:ven vi:vie vo:vol wa:wln wo:wol xh:xho yi:yid yo:yor za:zha zh:zho zu:zul"
        .split(" ")
        .map((pair) => pair.split(":")),
);
export function languageKey(
    value: string | undefined | null,
    captionAliases = true,
): string | null {
    const key = (value ?? "")
        .trim()
        .toLowerCase()
        .replaceAll("_", "-")
        .split("-")[0];
    return !key || key === "und"
        ? null
        : (ISO_LANGUAGE[key] ??
              (captionAliases ? languages[key] : undefined) ??
              key);
}
export function defaultStream(
    streams: any[],
    type: string,
    serverIndex?: number | null,
    preferredLanguage = "",
): number {
    if (type === "Subtitle") return serverIndex ?? -1;
    if (serverIndex != null) return serverIndex;
    const list = streams.filter((s) => s.Type === type),
        language = languageKey(preferredLanguage, false);
    return (
        (
            list.find((s) => s.IsDefault) ||
            list.find(
                (s) => language && languageKey(s.Language, false) === language,
            ) ||
            list[0]
        )?.Index ?? -1
    );
}
export function captionLanguage(
    enabled: boolean,
    configuration: any,
    tvLanguage: string,
): string | null {
    if (
        !enabled ||
        !configuration ||
        configuration.SubtitleMode?.toLowerCase() === "none"
    )
        return null;
    return configuration.SubtitleLanguagePreference?.trim()
        ? configuration.SubtitleLanguagePreference
        : tvLanguage;
}
const TEXT_CAPTION_CODECS = new Set([
    "subrip",
    "srt",
    "ass",
    "ssa",
    "webvtt",
    "vtt",
    "ttml",
    "mov_text",
    "tx3g",
    "text",
    "smi",
    "sami",
]);
export function captionIndex(
    source: any,
    preferredLanguage: string,
): number | null {
    const serverIndex = source.DefaultSubtitleStreamIndex;
    if (
        serverIndex === -1 ||
        (serverIndex >= 0 &&
            (source.MediaStreams ?? []).some(
                (stream: any) =>
                    stream.Type === "Subtitle" && stream.Index === serverIndex,
            ))
    )
        return serverIndex;
    const key = languageKey(preferredLanguage);
    if (!key) return null;
    return (
        (source.MediaStreams ?? [])
            .filter(
                (s: any) =>
                    s.Type === "Subtitle" && languageKey(s.Language) === key,
            )
            .sort(
                (a: any, b: any) =>
                    Number(!!a.IsForced) - Number(!!b.IsForced) ||
                    Number(!!b.IsHearingImpaired) -
                        Number(!!a.IsHearingImpaired) ||
                    Number(
                        !!b.IsTextSubtitleStream ||
                            TEXT_CAPTION_CODECS.has(b.Codec?.toLowerCase()),
                    ) -
                        Number(
                            !!a.IsTextSubtitleStream ||
                                TEXT_CAPTION_CODECS.has(a.Codec?.toLowerCase()),
                        ) ||
                    Number(!!b.IsDefault) - Number(!!a.IsDefault) ||
                    a.Index - b.Index,
            )[0]?.Index ?? null
    );
}
export function userConfiguration(
    fields: Record<string, unknown>,
    change?: { name: string; value: string } | null,
) {
    const updated = { ...fields };
    if (
        change &&
        [
            "AudioLanguagePreference",
            "SubtitleLanguagePreference",
            "SubtitleMode",
        ].includes(change.name)
    )
        updated[change.name] = change.value;
    return {
        audioLanguage: updated.AudioLanguagePreference ?? "",
        subtitleLanguage: updated.SubtitleLanguagePreference ?? "",
        subtitleMode: updated.SubtitleMode ?? "Default",
        fields: updated,
    };
}
export function endPosition(positionMs: number, durationMs: number) {
    const bounded = Math.max(
        0,
        durationMs > 0 ? Math.min(positionMs, durationMs) : positionMs,
    );
    return {
        bounded,
        completed:
            durationMs > 0 &&
            durationMs <= 86400000 &&
            durationMs - bounded >= 0 &&
            durationMs - bounded <= 1000
                ? durationMs
                : bounded,
    };
}
export function validSegment(s: Segment) {
    const start = s.StartTicks / 10000,
        end = s.EndTicks / 10000;
    return (
        SEGMENT_TYPES.includes(s.Type as any) &&
        Number.isFinite(start) &&
        Number.isFinite(end) &&
        start >= 0 &&
        end > start &&
        end <= 86400000 &&
        end - start <=
            (["Outro", "Commercial"].includes(s.Type) ? 1800000 : 600000)
    );
}
export function activeSegment(
    segments: Segment[],
    positionMs: number,
    modes: SegmentModes = {},
): Segment | null {
    return (
        segments
            .filter(
                (s) =>
                    validSegment(s) &&
                    modes[s.Type] !== "Off" &&
                    positionMs >= s.StartTicks / 10000 &&
                    positionMs < s.EndTicks / 10000,
            )
            .sort((a, b) => a.StartTicks - b.StartTicks)[0] ?? null
    );
}
export function gridVerticalMove(
    index: number,
    columns: number,
    count: number,
    down: boolean,
    loadingMore: boolean,
): number | null {
    if (columns <= 0 || index < 0 || index >= count) return null;
    if (!down) return index >= columns ? index - columns : null;
    const last = count - 1;
    if (index + columns <= last) return index + columns;
    if (loadingMore) return null;
    return Math.floor(last / columns) > Math.floor(index / columns) ? last : null;
}
export function nextCountdown(
    wallSeconds: number,
    creditsCountdown: boolean,
    mediaLeftMs: number,
    speed: number,
    autoPlay: boolean,
    ended: boolean,
    suspended: boolean,
): number | null {
    if (!autoPlay || (suspended && !ended)) return null;
    if (mediaLeftMs <= 0 || speed <= 0) return wallSeconds;
    const toEnd = Math.ceil(mediaLeftMs / speed / 1000);
    return creditsCountdown ? Math.min(wallSeconds, toEnd) : toEnd;
}
export function nextTrigger(
    durationMs: number,
    segments: Segment[],
): number | null {
    if (durationMs <= 0) return null;
    const outro = segments
        .filter(
            (s) =>
                s.Type === "Outro" &&
                validSegment(s) &&
                s.StartTicks / 10000 < durationMs,
        )
        .sort((a, b) => a.StartTicks - b.StartTicks)[0];
    return outro ? outro.StartTicks / 10000 : Math.max(durationMs - 10000, 0);
}
export function nextAdjacency(item: any, items: any[]): any | null {
    if (item.Type !== "Episode" || !item.SeriesId?.trim()) return null;
    const index = items.findIndex((s) => s.Id === item.Id);
    if (index < 0) return null;
    const next = items[index + 1];
    return next?.Type === "Episode" && next.Id !== item.Id ? next : null;
}

function queryUrl(
    server: string,
    path: string,
    query: Record<string, unknown> = {},
) {
    const u = new URL(resolve(server, path));
    for (const [k, v] of Object.entries(query))
        if (v != null) u.searchParams.append(k, String(v));
    return u.href;
}
export function imageUrl(
    server: string,
    item: any,
    width: number,
    height?: number | null,
    wide = false,
): string | null {
    const own = (type: string) =>
        item.ImageTags?.[type]?.trim()
            ? { id: item.Id, type, tag: item.ImageTags[type] }
            : null;
    const backdrop = () =>
        item.BackdropImageTags?.[0]?.trim()
            ? { id: item.Id, type: "Backdrop", tag: item.BackdropImageTags[0] }
            : item.ParentBackdropItemId &&
                item.ParentBackdropImageTags?.[0]?.trim()
              ? {
                    id: item.ParentBackdropItemId,
                    type: "Backdrop",
                    tag: item.ParentBackdropImageTags[0],
                }
              : null;
    const parentThumb = () =>
        item.ParentThumbItemId && item.ParentThumbImageTag?.trim()
            ? {
                  id: item.ParentThumbItemId,
                  type: "Thumb",
                  tag: item.ParentThumbImageTag,
              }
            : null;
    const image =
        wide || item.Type === "Episode"
            ? own("Thumb") ||
              (item.Type === "Episode" ? own("Primary") : null) ||
              backdrop() ||
              parentThumb() ||
              own("Primary")
            : own("Primary") ||
              (item.SeriesId && item.SeriesPrimaryImageTag?.trim()
                  ? {
                        id: item.SeriesId,
                        type: "Primary",
                        tag: item.SeriesPrimaryImageTag,
                    }
                  : null) ||
              backdrop() ||
              own("Thumb");
    return image
        ? queryUrl(server, `Items/${encode(image.id)}/Images/${image.type}`, {
              tag: image.tag,
              maxWidth: Math.max(1, width),
              maxHeight: height == null ? null : Math.max(1, height),
              quality: 85,
          })
        : null;
}
export const chapterImageUrl = (
    server: string,
    itemId: string,
    index: number,
    tag: string,
) =>
    queryUrl(server, `Items/${encode(itemId)}/Images/Chapter/${index}`, {
        tag,
        maxWidth: 320,
    });
export const trickplaySheetUrl = (
    server: string,
    itemId: string,
    mediaSourceId: string,
    width: number,
    sheet: number,
) =>
    queryUrl(
        server,
        `Videos/${encode(itemId)}/Trickplay/${width}/${sheet}.jpg`,
        { mediaSourceId },
    );
export type ParityContext = {
    server: { url: string };
    identity: { deviceId: string; deviceName: string; version: string };
    account: { userId: string; token: string; deviceId?: string | null };
};
export function contextAuthorization(c: ParityContext) {
    return authorization(
        c.account.deviceId ?? c.identity.deviceId,
        c.account.token,
        c.identity.deviceName,
        c.identity.version,
    );
}
export type ProtocolDecision = {
    itemId: string;
    source: any;
    method: string;
    url: string;
    headers: Record<string, string>;
    playSessionId: string | null;
    transcodeReasons: string[];
};
export function protocolDecision(
    context: ParityContext,
    itemId: string,
    response: any,
    mediaSourceId?: string | null,
): ProtocolDecision | { unplayable: string } {
    if (response.ErrorCode != null) return { unplayable: "SERVER_REFUSED" };
    let sources = response.MediaSources ?? [];
    if (mediaSourceId != null) {
        sources = sources.filter((s: any) => s.Id === mediaSourceId);
        if (!sources.length) return { unplayable: "SOURCE_NOT_FOUND" };
    }
    const source = sources.find(
        (s: any) =>
            s.SupportsDirectPlay ||
            s.SupportsDirectStream ||
            s.TranscodingUrl?.trim(),
    );
    if (!source) return { unplayable: "NO_COMPATIBLE_SOURCE" };
    const method = source.SupportsDirectPlay
            ? "DirectPlay"
            : source.SupportsDirectStream
              ? "DirectStream"
              : "Transcode",
        session = response.PlaySessionId ?? null;
    let url: string;
    if (method !== "Transcode") {
        const q = [
            ["static", "true"],
            ...(source.Id != null ? [["mediaSourceId", source.Id]] : []),
            ...(session != null ? [["playSessionId", session]] : []),
            ...(source.ETag != null ? [["tag", source.ETag]] : []),
        ];
        url = resolve(
            context.server.url,
            `Videos/${encode(itemId)}/stream?` +
                q.map(([k, v]) => k + "=" + encode(v)).join("&"),
        );
    } else
        url = resolve(context.server.url, stripApiKey(source.TranscodingUrl));
    if (!sameOrigin(context.server.url, url))
        return { unplayable: "UNSAFE_STREAM_URL" };
    const headers: Record<string, string> = {};
    for (const [k, v] of Object.entries(source.RequiredHttpHeaders ?? {}))
        if (
            v != null &&
            ![
                "authorization",
                "apikey",
                "api_key",
                "x-emby-token",
                "x-mediabrowser-token",
            ].includes(k.toLowerCase())
        )
            headers[k] = String(v);
    headers.Authorization = contextAuthorization(context);
    const reasons =
        method === "Transcode"
            ? (parameter(new URL(url), "TranscodeReasons") ?? "")
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean)
            : [];
    return {
        itemId,
        source,
        method,
        url,
        headers,
        playSessionId: session,
        transcodeReasons: reasons,
    };
}
export function decisionProjection(
    d: ProtocolDecision | { unplayable: string },
) {
    return "unplayable" in d
        ? d
        : {
              method: d.method,
              mediaSourceId: d.source.Id ?? null,
              url: d.url,
              headers: d.headers,
              playSessionId: d.playSessionId,
              transcodeReasons: d.transcodeReasons,
          };
}
function parameter(u: URL, name: string): string | null {
    return (
        [...u.searchParams.entries()].find(
            ([k]) => k.toLowerCase() === name.toLowerCase(),
        )?.[1] ?? null
    );
}
function removeParameters(u: URL, names: string[]) {
    for (const k of [...u.searchParams.keys()])
        if (names.some((n) => n.toLowerCase() === k.toLowerCase()))
            u.searchParams.delete(k);
}
export function seekRequired(d: ProtocolDecision) {
    const s = d.source;
    if (
        d.method !== "Transcode" ||
        s.IsInfiniteStream ||
        s.LiveStreamId != null ||
        !(s.RunTimeTicks > 0) ||
        s.TranscodingSubProtocol?.toLowerCase() !== "hls" ||
        s.TranscodingContainer?.toLowerCase() !== "ts"
    )
        return false;
    try {
        const u = new URL(d.url);
        if (
            !u.pathname.toLowerCase().endsWith("/master.m3u8") ||
            parameter(u, "VideoCodec")?.toLowerCase() === "copy"
        )
            return false;
        const video = (s.MediaStreams ?? []).find(
                (x: any) => x.Type === "Video",
            ),
            rate = video?.RealFrameRate ?? video?.AverageFrameRate,
            cap = Number(
                parameter(u, "Framerate") ?? parameter(u, "MaxFramerate"),
            );
        return (
            Number.isFinite(rate) &&
            Number.isFinite(cap) &&
            rate > 30 &&
            cap > 0 &&
            cap + 0.001 < rate
        );
    } catch {
        return false;
    }
}
export function seekStart(d: ProtocolDecision, targetMs: number) {
    return Math.max(
        0,
        Math.min(
            targetMs,
            Math.max(
                0,
                Math.trunc((d.source.RunTimeTicks ?? 0) / 10000) - 5000,
            ),
        ),
    );
}
export function audioEncodes(d: ProtocolDecision) {
    try {
        const u = new URL(d.url),
            streams = (d.source.MediaStreams ?? []).filter(
                (s: any) => s.Type === "Audio",
            ),
            raw = parameter(u, "AudioStreamIndex"),
            index =
                raw != null && /^-?\d+$/.test(raw)
                    ? Number(raw)
                    : d.source.DefaultAudioStreamIndex;
        if (!streams.length || index === -1) return true;
        const audio =
            index != null
                ? streams.find((s: any) => s.Index === index)
                : streams[0];
        if (!audio) return false;
        const targets = parameter(u, "AudioCodec")
            ?.split(",")
            .map((s) => s.trim().toLowerCase());
        if (targets?.includes("copy")) return false;
        if (
            parameter(u, "AllowAudioStreamCopy")?.toLowerCase() === "false" ||
            parameter(u, "EnableAutoStreamCopy")?.toLowerCase() === "false" ||
            d.transcodeReasons.includes("AudioChannelsNotSupported")
        )
            return true;
        const codec = audio.Codec?.trim().toLowerCase();
        if (!codec || !targets?.length || targets.some((s) => !s)) return false;
        if (!targets.includes(codec)) return true;
        if (audio.Channels == null) return false;
        const limit = [
            codec + "-audiochannels",
            "MaxAudioChannels",
            "AudioChannels",
            "TranscodingMaxAudioChannels",
        ]
            .map((n) => parameter(u, n))
            .find((v) => v != null && /^-?\d+$/.test(v));
        return limit != null && audio.Channels > Number(limit);
    } catch {
        return false;
    }
}
export function seekUrl(d: ProtocolDecision, startMs: number) {
    const u = new URL(stripApiKey(d.url)),
        subtitle = parameter(u, "SubtitleMethod") ?? "External",
        encodes = audioEncodes(d);
    u.pathname =
        u.pathname.slice(0, u.pathname.lastIndexOf("/")) + "/stream.ts";
    removeParameters(u, [
        "StartTimeTicks",
        "Static",
        "CopyTimestamps",
        "SubtitleMethod",
        ...(encodes ? ["AllowAudioStreamCopy"] : []),
    ]);
    u.searchParams.append(
        "StartTimeTicks",
        String(Math.max(startMs, 0) * 10000),
    );
    u.searchParams.append("Static", "false");
    u.searchParams.append("CopyTimestamps", "false");
    if (encodes) u.searchParams.append("AllowAudioStreamCopy", "false");
    u.searchParams.append("SubtitleMethod", subtitle);
    return u.href;
}
export function preserveVideo(
    d: ProtocolDecision,
    subtitleIndex?: number | null,
): ProtocolDecision | { unplayable: string } {
    if (d.method !== "Transcode") return d;
    const selected = subtitleIndex ?? d.source.DefaultSubtitleStreamIndex ?? -1,
        u = new URL(d.url);
    if (
        selected >= 0 &&
        ((d.source.MediaStreams ?? []).some(
            (s: any) =>
                s.Type === "Subtitle" &&
                s.Index === selected &&
                s.DeliveryMethod === "Encode",
        ) ||
            [...u.searchParams.entries()].some(
                ([k, v]) =>
                    k.toLowerCase() === "subtitlemethod" &&
                    v.toLowerCase() === "encode",
            ))
    )
        return { unplayable: "NO_COMPATIBLE_SOURCE" };
    removeParameters(u, ["VideoCodec", "AllowVideoStreamCopy"]);
    u.searchParams.append("VideoCodec", "copy");
    u.searchParams.append("AllowVideoStreamCopy", "true");
    return { ...d, url: u.href };
}
export function urlProjection(value: string) {
    const u = new URL(value);
    return {
        scheme: u.protocol.slice(0, -1),
        host: u.hostname,
        port: Number(u.port) || (u.protocol === "https:" ? 443 : 80),
        path: u.pathname,
        query: Object.fromEntries(
            [...new Set(u.searchParams.keys())].map((k) => [
                k,
                u.searchParams.getAll(k),
            ]),
        ),
    };
}
export function playbackRequest(
    userId: string,
    deviceProfile: any,
    options: any = {},
    maxStreamingBitrate = 200000000,
) {
    return {
        UserId: userId,
        DeviceProfile: deviceProfile,
        MaxStreamingBitrate: maxStreamingBitrate,
        StartTimeTicks: (options.startMs ?? 0) * 10000,
        ...(options.mediaSourceId != null
            ? { MediaSourceId: options.mediaSourceId }
            : {}),
        ...(options.audioStreamIndex != null
            ? { AudioStreamIndex: options.audioStreamIndex }
            : {}),
        ...(options.subtitleStreamIndex != null
            ? { SubtitleStreamIndex: options.subtitleStreamIndex }
            : {}),
        EnableDirectPlay: options.enableDirectPlay ?? true,
        EnableDirectStream: options.enableDirectStream ?? true,
        EnableTranscoding: true,
        AutoOpenLiveStream: false,
        AlwaysBurnInSubtitleWhenTranscoding: false,
        AllowVideoStreamCopy: options.allowVideoStreamCopy ?? true,
        AllowAudioStreamCopy: options.allowAudioStreamCopy ?? true,
    };
}

const CARD_FIELDS =
        "Overview,Genres,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount",
    SEARCH_FIELDS =
        "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount";
export class ParityCatalogClient {
    constructor(
        public context: ParityContext,
        private transport: typeof fetch = fetch,
    ) {}
    async request(
        path: string,
        query: Record<string, unknown> = {},
        method = "GET",
        body?: unknown,
        includeUser = true,
    ): Promise<any> {
        const url = queryUrl(this.context.server.url, path, {
            ...(includeUser ? { userId: this.context.account.userId } : {}),
            ...query,
        });
        if (!sameOrigin(this.context.server.url, url))
            throw new ClientError(
                "UNSAFE_STREAM_URL",
                "The request is outside this server.",
            );
        let r: Response;
        try {
            r = await this.transport(url, {
                ...networkOptions(url),
                method,
                headers: {
                    Authorization: contextAuthorization(this.context),
                    ...(body != null
                        ? { "Content-Type": "application/json" }
                        : {}),
                },
                ...(body != null ? { body: JSON.stringify(body) } : {}),
                signal: AbortSignal.timeout(30000),
            });
        } catch {
            throw new ClientError(
                "NETWORK",
                "Could not reach the server. Check your connection and try again.",
            );
        }
        if (!r.ok)
            throw new ClientError(
                r.status === 401 ? "EXPIRED" : "SERVER",
                r.status === 401
                    ? "Your session has expired. Sign in again."
                    : `The server could not complete the request (${r.status}).`,
            );
        if (r.status === 204) return {};
        try {
            return await r.json();
        } catch {
            throw new ClientError(
                "INVALID_RESPONSE",
                "The server returned an unreadable response.",
            );
        }
    }
    views() {
        return this.request("UserViews", { fields: CARD_FIELDS });
    }
    resume() {
        return this.request("UserItems/Resume", {
            mediaTypes: "Video",
            limit: 24,
            fields: CARD_FIELDS,
        });
    }
    nextUp(seriesId?: string) {
        return this.request("Shows/NextUp", {
            limit: 24,
            fields: CARD_FIELDS,
            seriesId,
        });
    }
    latest(parentId: string) {
        return this.request("Items/Latest", {
            parentId,
            limit: 24,
            fields: CARD_FIELDS,
        });
    }
    items(q: any = {}) {
        const limit = Math.max(1, Math.min(q.limit ?? 60, 200));
        return this.request(
            "Items",
            q.ids?.length
                ? {
                      ids: q.ids.join(","),
                      limit,
                      enableUserData: true,
                      fields: "ChildCount,RecursiveItemCount",
                  }
                : {
                      parentId: q.parentId ?? "library",
                      recursive: q.recursive ?? false,
                      includeItemTypes: q.includeItemTypes,
                      sortBy: q.sortBy ?? "SortName",
                      sortOrder: q.descending ? "Descending" : "Ascending",
                      startIndex: Math.max(0, q.startIndex ?? 0),
                      limit,
                      enableTotalRecordCount: true,
                      fields: CARD_FIELDS,
                  },
        );
    }
    async item(id: string) {
        const detail = await this.request(`Items/${encode(id)}`);
        if (
            ![
                "Movie",
                "Episode",
                "Video",
                "Series",
                "Season",
                "BoxSet",
                "Playlist",
                "Folder",
                "CollectionFolder",
            ].includes(detail.Type)
        )
            return detail;
        const batch = await this.request("Items", { ids: id });
        const projection = batch.Items?.find((x: any) => x.Id === id);
        if (!projection)
            throw new ClientError(
                "INVALID_RESPONSE",
                "The server returned an incomplete response.",
            );
        return {
            ...detail,
            UserData: projection.UserData,
            ...(projection.ChildCount !== undefined
                ? { ChildCount: projection.ChildCount }
                : {}),
            ...(projection.RecursiveItemCount !== undefined
                ? { RecursiveItemCount: projection.RecursiveItemCount }
                : {}),
        };
    }
    async configuration() {
        const result = await this.request(
            `Users/${encode(this.context.account.userId)}`,
            {},
            "GET",
            undefined,
            false,
        );
        return result.Configuration ?? {};
    }
    saveConfiguration(configuration: any) {
        return this.request("Users/Configuration", {}, "POST", configuration);
    }

    seasons(seriesId: string) {
        return this.request(`Shows/${encode(seriesId)}/Seasons`, {
            fields: CARD_FIELDS,
        });
    }
    episodes(seriesId: string, seasonId: string) {
        return this.request(`Shows/${encode(seriesId)}/Episodes`, {
            seasonId,
            fields: CARD_FIELDS,
        });
    }
    played(id: string, value: boolean) {
        return this.request(
            `UserPlayedItems/${encode(id)}`,
            {},
            value ? "POST" : "DELETE",
        );
    }
    favorite(id: string, value: boolean) {
        return this.request(
            `UserFavoriteItems/${encode(id)}`,
            {},
            value ? "POST" : "DELETE",
        );
    }
    search(kind: string, query: string) {
        const searchTerm = query.trim().slice(0, 200);
        if (searchTerm.length < 2) return Promise.resolve({ Items: [] });
        return kind === "PEOPLE"
            ? this.request("Persons", { searchTerm, limit: 24 })
            : this.request("Items", {
                  searchTerm,
                  limit: 24,
                  recursive: true,
                  includeItemTypes: (
                      {
                          MOVIES: "Movie",
                          SHOWS: "Series",
                          EPISODES: "Episode",
                          COLLECTIONS: "BoxSet",
                      } as Record<string, string>
                  )[kind],
                  enableTotalRecordCount: true,
                  fields: SEARCH_FIELDS,
              });
    }
    personItems(personId: string, startIndex = 0, limit = 60) {
        return this.request("Items", {
            personIds: personId,
            recursive: true,
            includeItemTypes: "Movie,Series",
            sortBy: "ProductionYear,SortName",
            sortOrder: "Descending",
            startIndex: Math.max(0, startIndex),
            limit: Math.max(1, Math.min(limit, 200)),
            enableTotalRecordCount: true,
            fields: SEARCH_FIELDS,
        });
    }
    async adjacent(item: any) {
        if (item.Type !== "Episode" || !item.SeriesId?.trim()) return null;
        const result = await this.request(
            `Shows/${encode(item.SeriesId)}/Episodes`,
            {
                adjacentTo: item.Id,
                isMissing: false,
                enableUserData: true,
                fields: "Overview",
            },
        );
        return nextAdjacency(item, result.Items ?? []);
    }
    stopEncoding(playSessionId: string) {
        return this.request(
            "Videos/ActiveEncodings",
            {
                deviceId:
                    this.context.account.deviceId ??
                    this.context.identity.deviceId,
                playSessionId,
            },
            "DELETE",
            undefined,
            false,
        );
    }
    report(event: "Start" | "Progress" | "Stopped", body: any) {
        return this.request(
            "Sessions/Playing" + (event === "Start" ? "" : "/" + event),
            {},
            "POST",
            body,
            false,
        );
    }
    async prepare(
        itemId: string,
        profile: any,
        options: any = {},
        maxStreamingBitrate = 200000000,
    ) {
        const body = playbackRequest(
            this.context.account.userId,
            profile,
            options,
            maxStreamingBitrate,
        );
        let response = await this.request(
            `Items/${encode(itemId)}/PlaybackInfo`,
            {},
            "POST",
            body,
            false,
        );
        let sourceId = options.mediaSourceId;
        if (
            !response.ErrorCode &&
            sourceId == null &&
            (options.audioStreamIndex != null ||
                options.subtitleStreamIndex != null)
        ) {
            const preferred =
                response.MediaSources?.find(
                    (s: any) =>
                        s.SupportsDirectPlay ||
                        s.SupportsDirectStream ||
                        s.TranscodingUrl?.trim(),
                ) ?? response.MediaSources?.[0];
            if (preferred?.Id?.trim()) {
                sourceId = preferred.Id;
                response = await this.request(
                    `Items/${encode(itemId)}/PlaybackInfo`,
                    {},
                    "POST",
                    { ...body, MediaSourceId: sourceId },
                    false,
                );
            }
        }
        const decision = protocolDecision(
            this.context,
            itemId,
            response,
            sourceId,
        );
        return options.requireVideoStreamCopy && !("unplayable" in decision)
            ? preserveVideo(decision, options.subtitleStreamIndex)
            : decision;
    }
}
