/* Ports of AddressCandidatesTest, AuthorizationHeaderTest, RuntimeDisplayTest,
 * CatalogClientTest, DetailResumeHttpTest, PlayDecisionTest, PlaybackClientTest.
 * SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, it, expect } from "vitest";
import {
    canDecodeVideo,
    browserProfile,
    browserPlaybackSummary,
    hlsCopyVideoCodecs,
    elementProbe,
    authenticatedMediaRequest,
    progressReport,
    ticks,
    addressCandidates,
    authorization,
    supportedVersion,
    mediaUrl,
    sameOrigin,
    resolve,
    redactUrl,
    resumeLabel,
    runtimeLabel,
    progress,
    assembleHome,
    episodeLabel,
    canDirect,
    Jellyfin,
    type Account,
    type Item,
    type Source,
} from "./index";
const server = {
    id: "s",
    name: "Cinema",
    url: "https://media.example/jellyfin",
    version: "12.0.0",
};
const account: Account = {
    server,
    user: { Id: "u", Name: "Viewer" },
    deviceId: "web",
    accessToken: "viewer-token",
};
const movie: Item = {
    Id: "movie",
    Name: "Movie",
    Type: "Movie",
    RunTimeTicks: 6e9,
    UserData: { PlaybackPositionTicks: 3e9 },
};
const source: Source = {
    Id: "file",
    Container: "mp4",
    SupportsDirectPlay: true,
    DefaultAudioStreamIndex: 1,
    MediaStreams: [
        { Type: "Video", Index: 0, Codec: "h264" },
        { Type: "Audio", Index: 1, Codec: "aac" },
        { Type: "Audio", Index: 2, Codec: "aac" },
    ],
};
describe("AddressCandidatesTest port", () => {
    it.each([
        [
            "media.example",
            [
                "https://media.example",
                "https://media.example:8920",
                "http://media.example",
                "http://media.example:8096",
            ],
        ],
        [
            " https://media.example:8443/jellyfin/ ",
            ["https://media.example:8443/jellyfin"],
        ],
        [
            "media.example/jellyfin",
            [
                "https://media.example/jellyfin",
                "https://media.example:8920/jellyfin",
                "http://media.example/jellyfin",
                "http://media.example:8096/jellyfin",
            ],
        ],
        [
            "https://media.example",
            ["https://media.example", "https://media.example:8920"],
        ],
        [
            "media.example:8123",
            ["https://media.example:8123", "http://media.example:8123"],
        ],
        ["http://[::1]:8096/jellyfin", ["http://[::1]:8096/jellyfin"]],
        ["https://media.example:443", ["https://media.example"]],
    ] as [string, string[]][])(
        "%s preserves schemes, ports and base paths",
        (input, expected) => expect(addressCandidates(input)).toEqual(expected),
    );
    it.each([
        "",
        " ",
        "ftp://host",
        "http://user:password@host",
        "host/path?token=secret",
        "host/#fragment",
        "bad host",
        "http://host:invalid",
        "http://host:99999",
        "http://host:0",
        "http://host\\evil",
    ])("rejects %s", (v) => expect(() => addressCandidates(v)).toThrow());
});
it("AuthorizationHeader encodes delimiter bytes and UTF-8", () => {
    expect(authorization('TV",\n雪', "a/b")).toContain(
        'DeviceId="TV%22%2C%0A%E9%9B%AA"',
    );
    expect(authorization("id", "a/b")).toContain('Token="a%2Fb"');
});
it.each([
    ["10.9.9", false],
    ["10.10.0", true],
    ["10.10.0-rc1", false],
    ["10.11.0-rc1", true],
    ["12.0.0", true],
    ["invalid", false],
])("minimum version %s", (v, result) =>
    expect(supportedVersion(v as string)).toBe(result),
);
it("media auth replaces token aliases with the viewer token and keeps base paths", () => {
    const u = new URL(
        mediaUrl(
            account,
            "/Videos/movie/master.m3u8?api_key=admin&X-Emby-Token=old&x=1",
        ),
    );
    expect(u.pathname).toBe("/jellyfin/Videos/movie/master.m3u8");
    expect([...u.searchParams]).toEqual([
        ["x", "1"],
        ["ApiKey", "viewer-token"],
    ]);
    expect(redactUrl(u.href)).not.toContain("viewer-token");
});
it.each([
    "https://evil.example/a",
    "https://media.example:444/a",
    "http://media.example/a",
    "https://user@media.example/a",
    "https://media.example\\@evil.example/a",
    "https://media.example:0/a",
    "javascript:alert(1)",
])("blocks foreign or ambiguous media URL %s", (url) => {
    expect(sameOrigin(server.url, url)).toBe(false);
    expect(() => mediaUrl(account, url)).toThrow();
});
it("same origin normalizes scheme host and default port", () =>
    expect(sameOrigin(server.url, "https://MEDIA.EXAMPLE:443/elsewhere")).toBe(
        true,
    ));
it("resolve retains configured server base path", () => {
    expect(resolve(server.url, "/Videos/a")).toBe(server.url + "/Videos/a");
    expect(resolve(server.url, "//evil.example/a")).toBe(
        "https://evil.example/a",
    );
});
it("runtime, resume and progress match catalog formatting boundaries", () => {
    expect(resumeLabel(0)).toBe("Resume from 0:00");
    expect(resumeLabel(3661e7)).toBe("Resume from 1:01:01");
    expect(runtimeLabel(864e9 + 1)).toBe("");
    expect(runtimeLabel(1)).toBe("1 min");
    expect(runtimeLabel(9 * 6e8)).toBe("9 min");
    expect(runtimeLabel(90 * 6e8)).toBe("1 hr 30 min");
    expect(runtimeLabel(120 * 6e8)).toBe("2 hr");
    expect(progress(movie)).toBe(0.5);
    expect(progress({ ...movie, UserData: { PlayedPercentage: 200 } })).toBe(1);
    expect(
        episodeLabel({ ...movie, ParentIndexNumber: 0, IndexNumber: 2 }),
    ).toBe("Special E2");
});
it("home keeps meaningful view order and omits empty rows", () => {
    const views = [
        { ...movie, Id: "tv", Name: "TV", CollectionType: "tvshows" },
        { ...movie, Id: "movies", CollectionType: "movies" },
    ];
    expect(
        assembleHome(views, [movie], [], { tv: [movie], movies: [] }).map(
            (r) => r.key,
        ),
    ).toEqual(["resume", "libraries", "latest:tv"]);
});
it("direct play requires known browser-decodable container and both codecs", () => {
    expect(canDirect(source, () => true)).toBe(true);
    expect(canDirect(source, () => false)).toBe(false);
    expect(canDirect({ ...source, Container: "mkv" }, () => true)).toBe(false);
    expect(canDirect(source, () => true, 2)).toBe(false);
    expect(
        canDirect(
            {
                ...source,
                MediaStreams: [{ Type: "Video", Index: 0, Codec: "unknown" }],
            },
            () => true,
        ),
    ).toBe(false);
});
function fake(responses: unknown[]) {
    const calls: { url: string; init: RequestInit }[] = [];
    const f = (async (url: string | URL | Request, init: RequestInit) => {
        calls.push({ url: String(url), init });
        const body = responses.shift();
        return new Response(body === undefined ? null : JSON.stringify(body), {
            status: body === undefined ? 204 : 200,
        });
    }) as typeof fetch;
    return { client: new Jellyfin(server, "web", account, f), calls };
}
it("detail overlays batch UserData and retains full metadata", async () => {
    const { client, calls } = fake([
        {
            ...movie,
            Overview: "Full description",
            UserData: { PlaybackPositionTicks: 0 },
        },
        { Items: [movie] },
    ]);
    const detail = await client.detail("movie");
    expect(detail.UserData?.PlaybackPositionTicks).toBe(3e9);
    expect(detail.Overview).toBe("Full description");
    expect(new URL(calls[1].url).searchParams.get("ids")).toBe("movie");
});
it("playback negotiation advertises no unsupported direct stream and binds reports to session", async () => {
    const { client, calls } = fake([
        {
            MediaSources: [
                {
                    ...source,
                    SupportsDirectPlay: false,
                    TranscodingUrl:
                        "/Videos/movie/master.m3u8?ApiKey=server-token",
                },
            ],
            PlaySessionId: "session",
        },
        undefined,
        undefined,
        undefined,
    ]);
    const d = await client.prepare(
        { ...movie, MediaSources: [source] },
        () => false,
        30,
        2,
        -1,
    );
    expect(d.method).toBe("Transcode");
    expect(d.url).toContain("ApiKey=viewer-token");
    const request = JSON.parse(String(calls[0].init.body));
    expect(request.StartTimeTicks).toBe(30e7);
    expect(request.EnableDirectPlay).toBe(false);
    expect(request.EnableDirectStream).toBe(false);
    expect(request.DeviceProfile.DirectPlayProfiles).toEqual([]);
    await client.report(d, "start", 30, false, 2, -1);
    await client.report(d, "stop", 41, true, 2, -1);
    expect(JSON.parse(String(calls[2].init.body))).toMatchObject({
        ItemId: "movie",
        PlaySessionId: "session",
        PositionTicks: 41e7,
    });
    await client.stopEncoding(d);
    expect(new URL(calls[3].url).searchParams.get("deviceId")).toBe("web");
    expect(calls[3].init.method).toBe("DELETE");
});
it("negotiation accepts 5.1 so the server copies a 5.1 track instead of converting it to stereo", async () => {
    // Demo Jellyfin 12, Big Buck Bunny (MKV, H.264, AAC 5.1): a cap of 2 made the server
    // re-encode the sound (AudioChannelsNotSupported); at 6 it copies both streams.
    const { client, calls } = fake([
        {
            MediaSources: [
                {
                    ...source,
                    TranscodingUrl: "/Videos/movie/master.m3u8?VideoCodec=h264",
                },
            ],
            PlaySessionId: "session",
        },
    ]);
    await client.prepare({ ...movie, MediaSources: [source] }, () => false, 0);
    const request = JSON.parse(String(calls[0].init.body));
    expect(request.MaxAudioChannels).toBe(6);
    expect(request.DeviceProfile.TranscodingProfiles[0].MaxAudioChannels).toBe(
        "6",
    );
});
it("unsafe transcode origin cannot receive viewer auth", async () => {
    const { client } = fake([
        {
            MediaSources: [
                {
                    ...source,
                    TranscodingUrl: "https://evil.example/master.m3u8",
                },
            ],
        },
    ]);
    await expect(client.prepare(movie, () => false, 0)).rejects.toMatchObject({
        kind: "UNSAFE_STREAM_URL",
    });
});
it("direct original stream is offered only after independent codec verification", async () => {
    const { client, calls } = fake([
        { MediaSources: [source], PlaySessionId: "play" },
    ]);
    const d = await client.prepare(
        { ...movie, MediaSources: [source] },
        () => true,
        20,
    );
    expect(d.method).toBe("DirectPlay");
    expect(new URL(d.url).searchParams.get("static")).toBe("true");
    expect(JSON.parse(String(calls[0].init.body)).EnableDirectPlay).toBe(true);
});

it("MSE HLS retains byte ranges supplied in a Headers instance", () => {
    const request = authenticatedMediaRequest(
        account,
        "/Videos/film/segment.mp4",
        { headers: new Headers({ Range: "bytes=1024-2047" }) },
    );
    expect(request.headers.get("Range")).toBe("bytes=1024-2047");
    expect(request.headers.get("Authorization")).toContain(
        'Token="viewer-token"',
    );
    expect(request.redirect).toBe("error");
});

it("detail uses authoritative container counts and refuses failed freshness projection", async () => {
    const { client } = fake([
        {
            Id: "s",
            Name: "Show",
            Type: "Series",
            ChildCount: 3,
            RecursiveItemCount: 8,
            UserData: { Played: true },
        },
        {
            Items: [
                {
                    Id: "s",
                    Name: "Show",
                    Type: "Series",
                    ChildCount: 0,
                    RecursiveItemCount: 0,
                    UserData: { Played: false },
                },
            ],
        },
    ]);
    const d = await client.detail("s");
    expect(d.ChildCount).toBe(0);
    expect(d.RecursiveItemCount).toBe(0);
    expect(d.UserData?.Played).toBe(false);
    const stale = fake([movie, { Items: [] }]).client;
    await expect(stale.detail("movie")).rejects.toMatchObject({
        kind: "INVALID_RESPONSE",
    });
});
it("muted audio is reported honestly without changing the stop payload", () => {
    const d = {
        itemId: "movie",
        source,
        method: "DirectPlay" as const,
        url: "",
    };
    expect(progressReport(d, 1, false, 1, -1, true, true).IsMuted).toBe(true);
});

it("pending seeks retain an exact ten-second tick delta at fractional browser-clock boundaries", () => {
    const start = 10.118449;
    expect(ticks(start + 10 + 10 - 10) - ticks(start)).toBe(100_000_000);
    expect(ticks(-0.00000015)).toBe(-1);
});

describe("PLAY-27 per-file browser video gate", () => {
    const source = (codec: string, extra: Record<string, unknown> = {}) =>
        ({
            Id: "s",
            MediaStreams: [{ Type: "Video", Index: 0, Codec: codec, ...extra }],
        }) as any;
    const probe = (supported: string[]) => (mime: string) =>
        supported.some((codec) => mime.includes(codec));
    it("asks the browser about the source's own video codec", () => {
        expect(canDecodeVideo(source("hevc"), probe(["hvc1"]))).toBe(true);
        expect(canDecodeVideo(source("hevc"), probe(["avc1"]))).toBe(false);
        expect(canDecodeVideo(source("h264"), probe(["avc1"]))).toBe(true);
    });
    it("treats limits the browser path cannot meet as not decodable", () => {
        const all = probe(["avc1", "hvc1"]);
        expect(canDecodeVideo(source("hevc", { BitDepth: 10 }), all)).toBe(
            false,
        );
        expect(canDecodeVideo(source("h264", { Width: 7680 }), all)).toBe(
            false,
        );
        expect(
            canDecodeVideo(source("h264", { RealFrameRate: 120 }), all),
        ).toBe(false);
        expect(canDecodeVideo(source("mpeg2video"), all)).toBe(false);
        expect(canDecodeVideo(undefined, all)).toBe(false);
    });
});
describe("browser profile claims exactly what the probe plays (web-ana N1)", () => {
    const probe = (accepted: string[]) => (mime: string) =>
        accepted.some((c) => mime.includes(c));
    const av1 = (bitDepth = 8): Source => ({
        Id: "s",
        Container: "mkv",
        MediaStreams: [
            {
                Type: "Video",
                Index: 0,
                Codec: "av1",
                BitDepth: bitDepth,
                Width: 640,
                Height: 360,
            },
            { Type: "Audio", Index: 1, Codec: "eac3" },
        ],
    });
    it("offers fMP4 HLS that may copy AV1 when the browser plays AV1", () => {
        const p = browserProfile(probe(["avc1", "av01", "mp4a"]), 1, av1());
        expect(
            p.TranscodingProfiles.map((t) => [t.Container, t.VideoCodec]),
        ).toEqual([
            ["ts", "h264"],
            ["mp4", "h264,av1"],
        ]);
        expect(
            browserPlaybackSummary(probe(["avc1", "av01"]), true).video,
        ).toContain("AV1");
    });
    it("claims no AV1 copy when the browser can't play AV1, matching Troubleshooting", () => {
        const p = browserProfile(probe(["avc1", "mp4a"]), 1, av1());
        expect(p.TranscodingProfiles.map((t) => t.Container)).toEqual(["ts"]);
        expect(
            browserPlaybackSummary(probe(["avc1"]), true).video,
        ).not.toContain("AV1");
    });
    it("does not offer to copy this file's AV1 when the browser can't decode this file", () => {
        expect(hlsCopyVideoCodecs(probe(["avc1", "av01"]), av1(10))).toEqual(
            [],
        );
        // Other formats stay claimed for that file.
        expect(
            hlsCopyVideoCodecs(probe(["avc1", "av01", "vp09"]), av1(10)),
        ).toEqual(["vp9"]);
    });
    it('treats a "maybe" answer as no, on both screens', () => {
        const maybe = elementProbe({ canPlayType: () => "maybe" });
        expect(maybe('video/mp4; codecs="av01.0.08M.08"')).toBe(false);
        expect(elementProbe({ canPlayType: () => "probably" })("x")).toBe(true);
    });
    it("sends the file-aware profile to PlaybackInfo", async () => {
        const calls: { init: RequestInit }[] = [];
        const client = new Jellyfin(server, "device", account, (async (
            _url: string,
            init: RequestInit,
        ) => {
            calls.push({ init });
            return new Response(
                JSON.stringify({
                    MediaSources: [
                        { ...av1(), TranscodingUrl: "/videos/m/master.m3u8" },
                    ],
                }),
                { status: 200 },
            );
        }) as typeof fetch);
        await client.prepare(
            { ...movie, MediaSources: [av1()] },
            probe(["avc1", "av01", "mp4a"]),
            0,
        );
        const request = JSON.parse(String(calls[0].init.body));
        expect(request.DeviceProfile.TranscodingProfiles[1]).toMatchObject({
            Container: "mp4",
            Protocol: "hls",
            VideoCodec: "h264,av1",
            AudioCodec: "aac",
        });
    });
});

it("legacy audio Off is omitted and a negative server default is pinned before opening media", async () => {
    const silent = {
        ...source,
        DefaultAudioStreamIndex: -1,
        MediaStreams: source.MediaStreams!.map((s) => ({
            ...s,
            IsDefault: s.Index === 2,
        })),
        SupportsDirectPlay: false,
        TranscodingUrl: "/Videos/movie/master.m3u8?AudioStreamIndex=-1",
    };
    const { client, calls } = fake([
        { MediaSources: [silent], PlaySessionId: "silent" },
        {
            MediaSources: [
                {
                    ...silent,
                    TranscodingUrl:
                        "/Videos/movie/master.m3u8?AudioStreamIndex=2",
                },
            ],
            PlaySessionId: "audible",
        },
    ]);
    const d = await client.prepare(
        { ...movie, MediaSources: [source] },
        () => false,
        30,
        -1,
        -1,
    );
    const bodies = calls.map((c) => JSON.parse(String(c.init.body)));
    expect(bodies).toHaveLength(2);
    expect(bodies[0]).not.toHaveProperty("AudioStreamIndex");
    expect(bodies[1]).toMatchObject({
        MediaSourceId: "file",
        AudioStreamIndex: 2,
        SubtitleStreamIndex: -1,
        StartTimeTicks: 30e7,
    });
    expect(d.source.DefaultAudioStreamIndex).toBe(2);
    expect(d.playSessionId).toBe("audible");
    expect(d.url).toContain("AudioStreamIndex=2");
});

it("a stale negative default remains playable directly after fallback negotiation", async () => {
    const stale = { ...source, DefaultAudioStreamIndex: -1 };
    const { client, calls } = fake([
        { MediaSources: [stale] },
        { MediaSources: [stale] },
    ]);
    const d = await client.prepare(
        { ...movie, MediaSources: [stale] },
        () => true,
        0,
    );
    expect(d.method).toBe("DirectPlay");
    expect(d.source.DefaultAudioStreamIndex).toBe(1);
    expect(JSON.parse(String(calls[1].init.body))).toMatchObject({
        AudioStreamIndex: 1,
    });
});
