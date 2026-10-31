/* Ports of AddressCandidatesTest, AuthorizationHeaderTest, RuntimeDisplayTest,
 * CatalogClientTest, DetailResumeHttpTest, PlayDecisionTest, PlaybackClientTest.
 * SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, it, expect } from "vitest";
import {
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
    expect(runtimeLabel(1)).toBe("1m");
    expect(runtimeLabel(90 * 6e8)).toBe("1h 30m");
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
    const d = await client.prepare(movie, () => false, 30, 2, -1);
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
        kind: "NOT_FOUND",
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
