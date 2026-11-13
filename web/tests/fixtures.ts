/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
const media = join(tmpdir(), "pictureshow-web-media");
const user = { Id: "viewer", Name: "Viewer" };
// Expected chooser names follow the shared Android-derived labels, not Jellyfin DisplayTitle.
export const trackNames = {
    englishAudio: "English · AAC stereo",
    spanishAudio: "Spanish · AAC stereo",
    englishCaptions: "English · SRT",
} as const;
const streams = [
    { Type: "Video", Index: 0, Codec: "h264" },
    {
        Type: "Audio",
        Index: 1,
        Codec: "aac",
        Language: "eng",
        Channels: 2,
        DisplayTitle: "English stereo",
    },
    {
        Type: "Audio",
        Index: 2,
        Codec: "aac",
        Language: "spa",
        Channels: 2,
        DisplayTitle: "Spanish stereo",
    },
    {
        Type: "Subtitle",
        Index: 3,
        Codec: "subrip",
        Language: "eng",
        IsTextSubtitleStream: true,
        DisplayTitle: "English captions",
    },
];
const source = {
    Id: "source",
    Container: "mp4",
    DefaultAudioStreamIndex: 1,
    MediaStreams: streams,
};
export const movie = {
    Id: "movie",
    Type: "Movie",
    Name: "The Test Picture",
    Overview: "A short film for your evening.",
    RunTimeTicks: 40e7,
    ProductionYear: 2026,
    ImageTags: { Primary: "art" },
    UserData: { PlaybackPositionTicks: 10e7 },
    MediaSources: [source],
};
export const series = { Id: "series", Name: "Open Frames", Type: "Series" },
    season = {
        Id: "season",
        Name: "Season 1",
        Type: "Season",
        SeriesId: "series",
    },
    episode = {
        ...movie,
        Id: "episode",
        Name: "A Quiet Morning",
        Type: "Episode",
        SeriesId: "series",
        SeriesName: "Open Frames",
        ParentIndexNumber: 1,
        IndexNumber: 1,
    };
export async function fixture(page: Page) {
    const reports: { path: string; body: any }[] = [],
        negotiations: any[] = [],
        mediaRequests: string[] = [];
    let stoppedAt = 10e7;
    await page.route("https://jellyfin.example/**", async (route) => {
        const req = route.request(),
            u = new URL(req.url()),
            p = u.pathname;
        let body: unknown;
        const headers = {
            "access-control-allow-origin": "*",
            "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
            "access-control-allow-headers": "authorization,content-type,range",
        };
        if (req.method() === "OPTIONS")
            return route.fulfill({ status: 204, headers });
        if (p === "/System/Info/Public")
            body = {
                Id: "server",
                ServerName: "Evening Cinema",
                Version: "12.0.0",
                ProductName: "Jellyfin Server",
            };
        else if (p === "/QuickConnect/Enabled") body = true;
        else if (p === "/QuickConnect/Initiate")
            body = { Code: "123456", Secret: "private-quick-secret" };
        else if (p === "/QuickConnect/Connect") body = { Authenticated: true };
        else if (p.startsWith("/Users/Authenticate"))
            body = {
                User: user,
                ServerId: "server",
                AccessToken: "viewer-only-token",
            };
        else if (p === "/Users/Me") body = user;
        else if (p === "/UserViews")
            body = {
                Items: [
                    {
                        Id: "library",
                        Name: "Films",
                        Type: "CollectionFolder",
                        CollectionType: "movies",
                    },
                ],
            };
        else if (p === "/UserItems/Resume")
            body = {
                Items: [
                    {
                        ...movie,
                        UserData: { PlaybackPositionTicks: stoppedAt },
                    },
                ],
            };
        else if (p === "/Shows/NextUp") body = { Items: [episode] };
        else if (p === "/Items/Latest") body = [movie, series];
        else if (p === "/Items/movie") body = movie;
        else if (p === "/Items/series") body = series;
        else if (p === "/Items/season") body = season;
        else if (p === "/Items/episode") body = episode;
        else if (p === "/Shows/series/Seasons") body = { Items: [season] };
        else if (p === "/Shows/series/Episodes") body = { Items: [episode] };
        else if (p === "/Items") {
            const id = u.searchParams.get("ids");
            body = {
                Items: id
                    ? [
                          id === "movie"
                              ? {
                                    ...movie,
                                    UserData: {
                                        PlaybackPositionTicks: stoppedAt,
                                    },
                                }
                              : id === "series"
                                ? series
                                : id === "season"
                                  ? season
                                  : episode,
                      ]
                    : [movie, series],
            };
        } else if (p.endsWith("/PlaybackInfo")) {
            const b = req.postDataJSON();
            negotiations.push(b);
            body = {
                PlaySessionId: "play-" + negotiations.length,
                MediaSources: [
                    {
                        ...source,
                        SupportsDirectPlay: b.EnableDirectPlay,
                        TranscodingUrl:
                            "/Videos/movie/master.m3u8?ApiKey=server-token",
                    },
                ],
            };
        } else if (p.startsWith("/Sessions/Playing")) {
            const b = req.postDataJSON();
            reports.push({ path: p, body: b });
            if (p.endsWith("/Stopped")) stoppedAt = b.PositionTicks;
            return route.fulfill({ status: 204, headers });
        } else if (p === "/Videos/ActiveEncodings" || p === "/Sessions/Logout")
            return route.fulfill({ status: 204, headers });
        else if (p.includes("/Images/")) {
            expect(u.searchParams.get("ApiKey")).toBe(
                req.headers()["authorization"]?.includes("guest-token")
                    ? "guest-token"
                    : "viewer-only-token",
            );
            mediaRequests.push("image");
            return route.fulfill({
                headers,
                contentType: "image/svg+xml",
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="300"><rect width="200" height="300" fill="#625071"/></svg>',
            });
        } else if (p.includes("/Subtitles/")) {
            expect(u.searchParams.get("ApiKey")).toBe(
                req.headers()["authorization"]?.includes("guest-token")
                    ? "guest-token"
                    : "viewer-only-token",
            );
            mediaRequests.push("subtitle");
            return route.fulfill({
                headers,
                contentType: "text/vtt",
                body: "WEBVTT\n\n00:00.000 --> 00:40.000\nA caption for the test picture.\n",
            });
        } else if (p.includes("/Videos/")) {
            const hls = p.endsWith(".m3u8"),
                segment = p.endsWith(".ts");
            if (!segment)
                expect(u.searchParams.get("ApiKey")).toBe(
                    req.headers()["authorization"]?.includes("guest-token")
                        ? "guest-token"
                        : "viewer-only-token",
                );
            mediaRequests.push(
                hls ? "manifest" : segment ? "segment" : "direct",
            );
            let file = hls
                ? "master.m3u8"
                : segment
                  ? p.split("/").pop()!
                  : "movie.mp4";
            let bytes = readFileSync(join(media, file));
            if (hls)
                bytes = Buffer.from(
                    bytes
                        .toString()
                        .replace(
                            /segment(\d+)\.ts/g,
                            "segment$1.ts?ApiKey=viewer-only-token",
                        ),
                );
            if (!hls && !segment) {
                const range = req.headers()["range"];
                if (range) {
                    const match = /bytes=(\d+)-(\d*)/.exec(range);
                    if (match) {
                        const start = +match[1],
                            end = match[2]
                                ? Math.min(+match[2], bytes.length - 1)
                                : bytes.length - 1;
                        return route.fulfill({
                            status: 206,
                            headers: {
                                ...headers,
                                "accept-ranges": "bytes",
                                "content-range": `bytes ${start}-${end}/${bytes.length}`,
                            },
                            contentType: "video/mp4",
                            body: bytes.subarray(start, end + 1),
                        });
                    }
                }
            }
            return route.fulfill({
                headers,
                contentType: hls
                    ? "application/vnd.apple.mpegurl"
                    : segment
                      ? "video/mp2t"
                      : "video/mp4",
                body: bytes,
            });
        } else
            return route.fulfill({ status: 404, headers, body: "Not found" });
        return route.fulfill({
            headers,
            contentType: "application/json",
            body: JSON.stringify(body),
        });
    });
    return { reports, negotiations, mediaRequests };
}
export async function signIn(page: Page, quick = false) {
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "Welcome back." }),
    ).toBeVisible();
    if (quick) {
        await page.getByRole("button", { name: "Use Quick Connect" }).focus();
        await page.keyboard.press("Enter");
    } else {
        await page.getByLabel("Username").fill("Viewer");
        await page.keyboard.press("Tab");
        await page.keyboard.type("password");
        await page.keyboard.press("Enter");
    }
    await expect(
        page.getByRole("heading", { name: "Viewer", exact: true }),
    ).toBeVisible();
}
