/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
export type Server = { id: string; name: string; url: string; version: string };
export type Account = {
    server: Server;
    user: { Id: string; Name: string };
    accessToken: string;
    deviceId: string;
};
export type Stream = {
    Type: string;
    Index: number;
    Codec?: string;
    DisplayTitle?: string;
    Language?: string;
    IsExternal?: boolean;
    IsTextSubtitleStream?: boolean;
    Profile?: string;
    Level?: number;
    BitDepth?: number;
    Width?: number;
    Height?: number;
    BitRate?: number;
    RealFrameRate?: number;
    DeliveryUrl?: string;
    Channels?: number;
};
export type Source = {
    Id: string;
    Container?: string;
    MediaStreams?: Stream[];
    SupportsDirectPlay?: boolean;
    SupportsDirectStream?: boolean;
    TranscodingUrl?: string;
    RequiredHttpHeaders?: Record<string, string>;
    DefaultAudioStreamIndex?: number;
    DefaultSubtitleStreamIndex?: number;
    LiveStreamId?: string;
};
export type Item = {
    Id: string;
    Name: string;
    Type: string;
    Overview?: string;
    ProductionYear?: number;
    RunTimeTicks?: number;
    SeriesId?: string;
    SeriesName?: string;
    IndexNumber?: number;
    ParentIndexNumber?: number;
    CollectionType?: string;
    ImageTags?: Record<string, string>;
    UserData?: {
        PlaybackPositionTicks?: number;
        PlayedPercentage?: number;
        Played?: boolean;
        IsFavorite?: boolean;
        UnplayedItemCount?: number;
    };
    ChildCount?: number;
    RecursiveItemCount?: number;
    MediaSources?: Source[];
};
export type Page = { Items: Item[] };
export type Row = { key: string; title: string; items: Item[] };
export class ClientError extends Error {
    constructor(
        public kind: string,
        message: string,
    ) {
        super(message);
    }
}
export const encode = (s: string) =>
    encodeURIComponent(s).replace(
        /[!'()*]/g,
        (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase(),
    );
export function addressCandidates(input: string): string[] {
    const text = input.trim().replace(/\/+$/, "");
    if (!text || /[\s\\]/.test(text))
        throw new ClientError(
            "INVALID_ADDRESS",
            "Enter a valid HTTP or HTTPS server address.",
        );
    const explicit = text.includes("://");
    let u: URL;
    try {
        u = new URL(explicit ? text : "https://" + text);
    } catch {
        throw new ClientError(
            "INVALID_ADDRESS",
            "Enter a valid server address.",
        );
    }
    if (
        !["http:", "https:"].includes(u.protocol) ||
        u.username ||
        u.password ||
        u.search ||
        u.hash ||
        u.port === "0"
    )
        throw new ClientError(
            "INVALID_ADDRESS",
            "Use HTTP or HTTPS without credentials, a query or a fragment.",
        );
    const authority = text.split("://").pop()!.split("/")[0];
    const port = authority.startsWith("[")
        ? authority.split("]")[1]?.startsWith(":")
        : authority.includes(":");
    const explicitPort = port
        ? authority.slice(authority.lastIndexOf(":") + 1)
        : "";
    if (
        port &&
        (!/^\d+$/.test(explicitPort) ||
            +explicitPort < 1 ||
            +explicitPort > 65535)
    )
        throw new ClientError("INVALID_ADDRESS", "Use a valid port.");
    return (explicit ? [u.protocol.slice(0, -1)] : ["https", "http"]).flatMap(
        (s) =>
            (port
                ? [+explicitPort]
                : s === "https"
                  ? [443, 8920]
                  : [80, 8096]
            ).map(
                (p) =>
                    `${s}://${u.hostname}${(s === "https" && p === 443) || (s === "http" && p === 80) ? "" : ":" + p}${u.pathname.replace(/\/+$/, "")}`,
            ),
    );
}
export function supportedVersion(v: string) {
    const n = v.split(/[-+]/)[0].split(".").map(Number);
    if (n.length < 3 || n.some((x) => !Number.isInteger(x))) return false;
    for (let i = 0; i < 3; i++) {
        if (n[i] !== [10, 10, 0][i]) return n[i] > [10, 10, 0][i];
    }
    return !v.includes("-");
}
export function authorization(
    deviceId: string,
    token?: string,
    deviceName = "Web browser",
    version = "0.1.0",
) {
    return (
        "MediaBrowser " +
        Object.entries({
            Client: "Pictureshow",
            Device: deviceName,
            DeviceId: deviceId,
            Version: version,
            ...(token ? { Token: token } : {}),
        })
            .map(([k, v]) => `${k}="${encode(v)}"`)
            .join(", ")
    );
}
const tokenNames = [
    "apikey",
    "api_key",
    "x-emby-token",
    "x-mediabrowser-token",
    "token",
    "access_token",
    "secret",
];
export function stripTokens(u: URL) {
    for (const k of [...u.searchParams.keys()])
        if (tokenNames.includes(k.toLowerCase())) u.searchParams.delete(k);
    return u;
}
export function redactUrl(value: string) {
    try {
        const u = new URL(value);
        for (const k of [...u.searchParams.keys()])
            if (tokenNames.includes(k.toLowerCase()))
                u.searchParams.set(k, "REDACTED");
        return u.href;
    } catch {
        return "[invalid URL]";
    }
}
export function resolve(server: string, path: string) {
    return /^([a-z][a-z\d+.-]*:|\/\/)/i.test(path)
        ? new URL(path, server).href
        : server.replace(/\/$/, "") + "/" + path.replace(/^\//, "");
}
export function sameOrigin(server: string, target: string) {
    try {
        if (/[\s\\\x00-\x1f\x7f]/.test(target) || !/^https?:\/\//i.test(target))
            return false;
        const authority = target.split("://")[1].split(/[/?#]/)[0];
        if (authority.includes("%") || authority.endsWith(":")) return false;
        const u = new URL(target),
            s = new URL(server);
        return (
            !u.username &&
            !u.password &&
            u.port !== "0" &&
            u.origin === s.origin
        );
    } catch {
        return false;
    }
}
export function mediaUrl(account: Account, path: string) {
    if (/[\s\\\x00-\x1f\x7f]/.test(path))
        throw new ClientError("UNSAFE_STREAM_URL", "Invalid media URL.");
    const target = resolve(account.server.url, path);
    if (!sameOrigin(account.server.url, target))
        throw new ClientError(
            "UNSAFE_STREAM_URL",
            "The server returned a media URL outside its origin.",
        );
    const u = stripTokens(new URL(target));
    u.searchParams.set("ApiKey", account.accessToken);
    return u.href;
}
/** Chromium's local HTTP mixed-content exemption requires an explicit fetch address space. Other engines ignore the extension. */
export function localAddressSpace(
    value: string,
): "local" | "loopback" | undefined {
    const u = new URL(value);
    if (u.protocol !== "http:") return;
    const h = u.hostname.toLowerCase();
    if (
        h === "localhost" ||
        h.endsWith(".localhost") ||
        h === "[::1]" ||
        /^127\./.test(h)
    )
        return "loopback";
    if (
        h.endsWith(".local") ||
        /^(10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h) ||
        /^\[(fc|fd|fe80)/.test(h)
    )
        return "local";
}
export function networkOptions(url: string): RequestInit {
    const targetAddressSpace = localAddressSpace(url);
    return {
        credentials: "omit",
        referrerPolicy: "no-referrer",
        redirect: "error",
        ...(targetAddressSpace ? { targetAddressSpace } : {}),
    } as RequestInit;
}
export async function fetchMedia(account: Account, path: string) {
    const url = mediaUrl(account, path);
    let r: Response;
    try {
        r = await fetch(url, {
            ...networkOptions(url),
            headers: {
                Authorization: authorization(
                    account.deviceId,
                    account.accessToken,
                ),
            },
            signal: AbortSignal.timeout(30000),
        });
    } catch {
        throw new ClientError(
            "NETWORK",
            "Media could not be loaded. Check HTTPS, CORS and local network permission.",
        );
    }
    if (!r.ok)
        throw new ClientError(
            r.status === 401 ? "EXPIRED" : "SERVER",
            `Media could not be loaded (${r.status}).`,
        );
    return r;
}
// Normalize the browser clock to integral nanoseconds before protocol truncation.
export const ticks = (seconds: number) =>
    Math.trunc(Math.round(seconds * 1_000_000_000) / 100);
export function resumeLabel(t: number) {
    const s = Math.floor(Math.max(0, t) / 1e7);
    return (
        "Resume from " +
        (s >= 3600
            ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}`
            : Math.floor(s / 60)) +
        ":" +
        String(s % 60).padStart(2, "0")
    );
}
export function runtimeLabel(t?: number) {
    if (!t || t < 1 || t > 864e9) return "";
    const m = Math.max(1, Math.floor(t / 6e8));
    return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}
export function episodeLabel(i: Item) {
    const s = i.ParentIndexNumber,
        e = i.IndexNumber;
    return s === 0
        ? "Special" + (e != null ? " E" + e : "")
        : s != null
          ? "S" + s + (e != null ? ":E" + e : "")
          : e != null
            ? "E" + e
            : "Episode";
}
export function progress(i: Item) {
    const p = i.UserData?.PlayedPercentage;
    return Math.min(
        1,
        Math.max(
            0,
            p != null && Number.isFinite(p)
                ? p / 100
                : i.RunTimeTicks
                  ? (i.UserData?.PlaybackPositionTicks ?? 0) / i.RunTimeTicks
                  : 0,
        ),
    );
}
export function assembleHome(
    views: Item[],
    resume: Item[],
    next: Item[],
    latest: Record<string, Item[]>,
): Row[] {
    return [
        ...(resume.length
            ? [{ key: "resume", title: "Continue watching", items: resume }]
            : []),
        ...(next.length
            ? [{ key: "nextup", title: "Next up", items: next }]
            : []),
        ...(views.length
            ? [{ key: "libraries", title: "Libraries", items: views }]
            : []),
        ...views
            .filter(
                (v) =>
                    ["movies", "tvshows"].includes(v.CollectionType ?? "") &&
                    latest[v.Id]?.length,
            )
            .map((v) => ({
                key: "latest:" + v.Id,
                title: "Latest in " + v.Name,
                items: latest[v.Id],
            })),
    ];
}
export type Decision = {
    itemId: string;
    source: Source;
    method: "DirectPlay" | "DirectStream" | "Transcode";
    url: string;
    playSessionId?: string;
};
export type Probe = (mime: string) => boolean;
const videoCodecs: Record<string, string> = {
    h264: "avc1.640028",
    hevc: "hvc1.1.6.L120.B0",
    av1: "av01.0.08M.08",
    vp9: "vp09.00.40.08",
    vp8: "vp8",
    mpeg4: "mp4v.20.9",
};
const audioCodecs: Record<string, string> = {
    aac: "mp4a.40.2",
    mp3: "mp3",
    opus: "opus",
    vorbis: "vorbis",
    flac: "flac",
    ac3: "ac-3",
    eac3: "ec-3",
};
export function canDirect(source: Source, probe: Probe, audioIndex?: number) {
    const container = source.Container?.toLowerCase();
    const mime =
        container === "mp4" || container === "m4v"
            ? "video/mp4"
            : container === "webm"
              ? "video/webm"
              : undefined;
    if (!mime) return false;
    const streams = source.MediaStreams ?? [],
        v = streams.find((s) => s.Type === "Video"),
        a =
            audioIndex != null
                ? streams.find(
                      (s) => s.Type === "Audio" && s.Index === audioIndex,
                  )
                : (streams.find(
                      (s) =>
                          s.Type === "Audio" &&
                          s.Index === source.DefaultAudioStreamIndex,
                  ) ?? streams.find((s) => s.Type === "Audio"));
    if (
        (v?.BitDepth ?? 8) > 8 ||
        (v?.Width ?? 0) > 3840 ||
        (v?.Height ?? 0) > 2160 ||
        (v?.RealFrameRate ?? 0) > 60 ||
        !v?.Codec ||
        !videoCodecs[v.Codec] ||
        (a && (!a.Codec || !audioCodecs[a.Codec]))
    )
        return false;
    // A plain video URL cannot choose another embedded audio track portably.
    if (
        audioIndex != null &&
        audioIndex !==
            (source.DefaultAudioStreamIndex ??
                streams.find((s) => s.Type === "Audio")?.Index)
    )
        return false;
    return probe(
        `${mime}; codecs="${[videoCodecs[v.Codec], a?.Codec ? audioCodecs[a.Codec] : undefined].filter(Boolean).join(",")}"`,
    );
}
export function browserProfile(probe: Probe, bitrate = 200_000_000) {
    const video = Object.entries(videoCodecs)
        .filter(([, v]) => probe(`video/mp4; codecs="${v}"`))
        .map(([k]) => k);
    const audio = Object.entries(audioCodecs)
        .filter(([, v]) => probe(`audio/mp4; codecs="${v}"`))
        .map(([k]) => k);
    return {
        Name: "Pictureshow Web",
        MaxStreamingBitrate: bitrate,
        DirectPlayProfiles: [],
        TranscodingProfiles: [
            {
                Container: "ts",
                Type: "Video",
                VideoCodec: video.includes("h264")
                    ? "h264"
                    : video.includes("vp9")
                      ? "vp9"
                      : "h264",
                AudioCodec: audio.includes("aac") ? "aac" : "mp3",
                Protocol: "hls",
                Context: "Streaming",
                MaxAudioChannels: "2",
                MinSegments: 2,
                BreakOnNonKeyFrames: true,
            },
        ],
        SubtitleProfiles: [
            { Format: "vtt", Method: "External" },
            { Format: "srt", Method: "External" },
            { Format: "ass", Method: "Encode" },
            { Format: "pgssub", Method: "Encode" },
        ],
    };
}
export class Jellyfin {
    constructor(
        public server: Server,
        public deviceId: string,
        public account?: Account,
        private fetcher: typeof fetch = fetch,
    ) {}
    async request<T>(
        path: string,
        body?: unknown,
        method = body === undefined ? "GET" : "POST",
        keepalive = false,
    ): Promise<T> {
        const url = resolve(this.server.url, path);
        if (!sameOrigin(this.server.url, url))
            throw new ClientError("UNSAFE_STREAM_URL", "Invalid API origin.");
        let r: Response;
        try {
            r = await this.fetcher.call(globalThis, url, {
                ...networkOptions(url),
                method,
                headers: {
                    Authorization: authorization(
                        this.account?.deviceId ?? this.deviceId,
                        this.account?.accessToken,
                    ),
                    ...(body === undefined
                        ? {}
                        : { "Content-Type": "application/json" }),
                },
                body: body === undefined ? undefined : JSON.stringify(body),
                credentials: "omit",
                referrerPolicy: "no-referrer",
                redirect: "error",
                signal: AbortSignal.timeout(30000),
                keepalive,
            });
        } catch {
            throw new ClientError(
                "NETWORK",
                "Could not reach the server. Check the address, certificate, CORS settings and local network permission.",
            );
        }
        if (!r.ok)
            throw new ClientError(
                r.status === 401
                    ? "EXPIRED"
                    : r.status === 404
                      ? "NOT_FOUND"
                      : "SERVER",
                r.status === 401
                    ? "Sign-in was refused or expired. Sign in again."
                    : `The server could not complete the request (${r.status}).`,
            );
        if (r.status === 204) return undefined as T;
        const text = await r.text();
        try {
            return (text ? JSON.parse(text) : undefined) as T;
        } catch {
            throw new ClientError(
                "INVALID_RESPONSE",
                "The server returned an unreadable response.",
            );
        }
    }
    static async probe(input: string, deviceId: string) {
        let error: unknown;
        for (const url of addressCandidates(input)) {
            try {
                const client = new Jellyfin(
                    { id: "", name: "", url, version: "" },
                    deviceId,
                );
                const v = await client.request<{
                    Id: string;
                    ServerName: string;
                    Version: string;
                    ProductName: string;
                }>("System/Info/Public");
                if (v.ProductName !== "Jellyfin Server")
                    throw new ClientError(
                        "NOT_JELLYFIN",
                        "This address is not a Jellyfin server.",
                    );
                if (!supportedVersion(v.Version))
                    throw new ClientError(
                        "UNSUPPORTED_VERSION",
                        "Jellyfin 10.10.0 or newer is required.",
                    );
                return {
                    id: v.Id,
                    name: v.ServerName,
                    url,
                    version: v.Version,
                };
            } catch (e) {
                error = e;
            }
        }
        throw error;
    }
    async authenticate(username: string, password: string) {
        return this.accept(
            await this.request<{
                User: Account["user"];
                AccessToken: string;
                ServerId: string;
            }>("Users/AuthenticateByName", {
                Username: username,
                Pw: password,
            }),
        );
    }
    accept(v: {
        User: Account["user"];
        AccessToken: string;
        ServerId: string;
    }) {
        if (v.ServerId !== this.server.id || !v.User?.Id || !v.AccessToken)
            throw new ClientError(
                "INVALID_RESPONSE",
                "The server returned an incomplete sign-in.",
            );
        return (this.account = {
            server: this.server,
            user: v.User,
            accessToken: v.AccessToken,
            deviceId: this.deviceId,
        });
    }
    catalog<T>(path: string) {
        return this.request<T>(
            path +
                (path.includes("?") ? "&" : "?") +
                "userId=" +
                encode(this.account!.user.Id),
        );
    }
    async detail(id: string) {
        const d = await this.catalog<Item>("Items/" + encode(id));
        const b = await this.catalog<Page>("Items?ids=" + encode(id));
        const i = b.Items?.find((i) => i.Id === id);
        if (!i)
            throw new ClientError(
                "NOT_FOUND",
                "This item is no longer available. Go Back or Retry.",
            );
        d.UserData = i.UserData;
        if (i.ChildCount !== undefined) d.ChildCount = i.ChildCount;
        if (i.RecursiveItemCount !== undefined)
            d.RecursiveItemCount = i.RecursiveItemCount;
        return d;
    }
    async home() {
        const [views, resume, next] = await Promise.all([
            this.catalog<Page>("UserViews"),
            this.catalog<Page>("UserItems/Resume?mediaTypes=Video&limit=24"),
            this.catalog<Page>("Shows/NextUp?limit=24"),
        ]);
        const latest = Object.fromEntries(
            await Promise.all(
                views.Items.filter((v) =>
                    ["movies", "tvshows"].includes(v.CollectionType ?? ""),
                ).map(async (v) => [
                    v.Id,
                    await this.catalog<Item[]>(
                        "Items/Latest?limit=24&parentId=" + encode(v.Id),
                    ),
                ]),
            ),
        );
        return assembleHome(views.Items, resume.Items, next.Items, latest);
    }
    async prepare(
        item: Item,
        probe: Probe,
        position: number,
        audio?: number,
        subtitle?: number,
        forceHls = false,
        bitrateMbps: number | null = null,
    ): Promise<Decision> {
        const bitrate = [4, 10, 20, 40, 80, 120].includes(bitrateMbps ?? 0)
            ? bitrateMbps! * 1_000_000
            : 200_000_000;
        const metadata = item.MediaSources?.[0];
        const chosenSubtitle = subtitle ?? metadata?.DefaultSubtitleStreamIndex;
        const subtitleStream = metadata?.MediaStreams?.find(
            (s) => s.Type === "Subtitle" && s.Index === chosenSubtitle,
        );
        const encodedSubtitle =
            !!subtitleStream &&
            !["srt", "subrip", "vtt", "webvtt"].includes(
                subtitleStream.Codec?.toLowerCase() ?? "",
            );
        const direct =
            !forceHls &&
            !encodedSubtitle &&
            !!metadata &&
            canDirect(metadata, probe, audio);
        const body = {
            UserId: this.account!.user.Id,
            StartTimeTicks: ticks(position),
            AudioStreamIndex: audio,
            SubtitleStreamIndex: subtitle,
            MaxStreamingBitrate: bitrate,
            MaxAudioChannels: 2,
            EnableDirectPlay: direct,
            EnableDirectStream: false,
            EnableTranscoding: true,
            AllowVideoStreamCopy: true,
            AllowAudioStreamCopy: true,
            DeviceProfile: {
                ...browserProfile(probe, bitrate),
                DirectPlayProfiles: direct
                    ? [
                          {
                              Container: metadata!.Container,
                              Type: "Video",
                              VideoCodec: metadata!.MediaStreams?.find(
                                  (s) => s.Type === "Video",
                              )?.Codec,
                              AudioCodec: metadata!.MediaStreams?.find(
                                  (s) => s.Type === "Audio",
                              )?.Codec,
                          },
                      ]
                    : [],
            },
        };
        const info = await this.request<{
            MediaSources: Source[];
            PlaySessionId?: string;
            ErrorCode?: string;
        }>("Items/" + encode(item.Id) + "/PlaybackInfo", body);
        if (info.ErrorCode)
            throw new ClientError(
                "SERVER_REFUSED",
                "The server refused playback.",
            );
        const source = info.MediaSources?.find(
            (s) =>
                (direct &&
                    s.SupportsDirectPlay &&
                    canDirect(s, probe, audio)) ||
                s.TranscodingUrl,
        );
        if (!source)
            throw new ClientError(
                "NO_COMPATIBLE_SOURCE",
                "This video cannot be played in this browser.",
            );
        if (
            Object.keys(source.RequiredHttpHeaders ?? {}).some(
                (k) =>
                    ![
                        "authorization",
                        "x-emby-token",
                        "apikey",
                        "api_key",
                    ].includes(k.toLowerCase()),
            )
        )
            throw new ClientError(
                "NO_COMPATIBLE_SOURCE",
                "This media requires headers that browser video cannot send.",
            );
        const useDirect =
            direct &&
            source.SupportsDirectPlay &&
            canDirect(source, probe, audio);
        const path = useDirect
            ? `Videos/${encode(item.Id)}/stream?static=true&mediaSourceId=${encode(source.Id)}&playSessionId=${encode(info.PlaySessionId ?? "")}`
            : source.TranscodingUrl!;
        return {
            itemId: item.Id,
            source,
            method: useDirect ? "DirectPlay" : "Transcode",
            url: mediaUrl(this.account!, path),
            playSessionId: info.PlaySessionId,
        };
    }
    report(
        decision: Decision,
        event: "start" | "progress" | "stop",
        position: number,
        paused: boolean,
        audio?: number,
        subtitle = -1,
        failed = false,
        keepalive = false,
        muted = false,
    ) {
        return this.request<void>(
            "Sessions/Playing" +
                (event === "start"
                    ? ""
                    : event === "stop"
                      ? "/Stopped"
                      : "/Progress"),
            event === "stop"
                ? stopReport(decision, position, failed)
                : progressReport(
                      decision,
                      position,
                      paused,
                      audio ?? decision.source.DefaultAudioStreamIndex,
                      subtitle,
                      true,
                      muted,
                  ),
            "POST",
            keepalive,
        );
    }
    stopEncoding(d: Decision) {
        return d.method === "Transcode" && d.playSessionId
            ? this.request<void>(
                  `Videos/ActiveEncodings?deviceId=${encode(this.deviceId)}&playSessionId=${encode(d.playSessionId)}`,
                  undefined,
                  "DELETE",
              )
            : Promise.resolve();
    }
}

export const resumeStartMs = (
    value: number,
    resume = true,
    explicitPositionMs?: number | null,
) =>
    Math.max(0, explicitPositionMs ?? (resume ? Math.floor(value / 10000) : 0));
export function stripApiKey(value: string) {
    const q = value.indexOf("?");
    if (q < 0) return value;
    const h = value.indexOf("#", q),
        end = h < 0 ? value.length : h;
    const pairs = value
        .slice(q + 1, end)
        .split("&")
        .filter((p) => {
            try {
                return !tokenNames.includes(
                    decodeURIComponent(p.split("=")[0]).toLowerCase(),
                );
            } catch {
                return true;
            }
        });
    return (
        value.slice(0, q) +
        (pairs.length ? "?" + pairs.join("&") : "") +
        value.slice(end)
    );
}

export const ticksFromNanoseconds = (value: number) => Math.trunc(value / 100);
function reportBase(d: Decision, seconds: number) {
    return {
        ItemId: d.itemId,
        MediaSourceId: d.source.Id,
        PlaySessionId: d.playSessionId,
        ...(d.source.LiveStreamId
            ? { LiveStreamId: d.source.LiveStreamId }
            : {}),
        PositionTicks: ticks(seconds),
    };
}
export function progressReport(
    d: Decision,
    seconds: number,
    paused: boolean,
    audio?: number | null,
    subtitle?: number | null,
    canSeek = true,
    muted = false,
) {
    return {
        ...reportBase(d, seconds),
        PlayMethod: d.method,
        IsPaused: paused,
        IsMuted: muted,
        CanSeek: canSeek,
        RepeatMode: "RepeatNone",
        PlaybackOrder: "Default",
        ...(audio != null ? { AudioStreamIndex: audio } : {}),
        ...(subtitle != null ? { SubtitleStreamIndex: subtitle } : {}),
    };
}
export function stopReport(d: Decision, seconds: number, failed = false) {
    return { ...reportBase(d, seconds), Failed: failed };
}

/** The hls.js fetch loader supplies Headers (including Range), not a plain record. */
export function authenticatedMediaRequest(
    account: Account,
    path: string,
    init: RequestInit = {},
) {
    const url = mediaUrl(account, path);
    const headers = new Headers(init.headers);
    headers.set(
        "Authorization",
        authorization(account.deviceId, account.accessToken),
    );
    return new Request(url, { ...init, ...networkOptions(url), headers });
}
