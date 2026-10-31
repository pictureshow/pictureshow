/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
const media = join(tmpdir(), "pictureshow-web-media");
const user = { Id: "viewer", Name: "Viewer" };
const streams = [
    { Type: "Video", Index: 0, Codec: "h264" },
    { Type: "Audio", Index: 1, Codec: "aac", DisplayTitle: "English stereo" },
    { Type: "Audio", Index: 2, Codec: "aac", DisplayTitle: "Spanish stereo" },
    {
        Type: "Subtitle",
        Index: 3,
        Codec: "subrip",
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
const movie = {
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
const series = { Id: "series", Name: "Open Frames", Type: "Series" },
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
async function fixture(page: Page) {
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
            expect(u.searchParams.get("ApiKey")).toBe("viewer-only-token");
            mediaRequests.push("image");
            return route.fulfill({
                headers,
                contentType: "image/svg+xml",
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="300"><rect width="200" height="300" fill="#625071"/></svg>',
            });
        } else if (p.includes("/Subtitles/")) {
            expect(u.searchParams.get("ApiKey")).toBe("viewer-only-token");
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
                expect(u.searchParams.get("ApiKey")).toBe("viewer-only-token");
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
async function signIn(page: Page, quick = false) {
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
        page.getByRole("heading", { name: "Make yourself at home." }),
    ).toBeVisible();
}
test("keyboard path: password, resume, real video, reports, tracks, Back restores focus", async ({
    page,
}) => {
    const f = await fixture(page);
    await signIn(page);
    const card = page.locator('[data-focus="resume:movie"]');
    await expect(card).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "The Test Picture", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByRole("button", { name: "Resume from 0:10" }),
    ).toBeFocused();
    await page.keyboard.press("Enter");
    await expect
        .poll(
            async () =>
                (await page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => !v.paused)) ||
                (await page
                    .getByRole("status")
                    .filter({ hasText: "Press Play" })
                    .count()) > 0,
            { timeout: 20000 },
        )
        .toBe(true);
    if (
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.paused)
    ) {
        await page.getByRole("button", { name: "Play", exact: true }).focus();
        await page.keyboard.press("Enter");
    }
    try {
        await expect
            .poll(
                () =>
                    page
                        .locator("video")
                        .evaluate((v: HTMLVideoElement) => v.currentTime),
                { timeout: 20000 },
            )
            .toBeGreaterThan(10);
    } catch (e) {
        console.log(
            "VIDEO_STATE",
            await page.locator("video").evaluate((v: HTMLVideoElement) => ({
                time: v.currentTime,
                ready: v.readyState,
                paused: v.paused,
                seeking: v.seeking,
                error: v.error?.code,
                buffered: [...Array(v.buffered.length)].map((_, i) => [
                    v.buffered.start(i),
                    v.buffered.end(i),
                ]),
            })),
        );
        throw e;
    }
    await expect
        .poll(() => f.reports.some((r) => r.path === "/Sessions/Playing"))
        .toBe(true);
    await page.getByRole("button", { name: "Pause", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect
        .poll(() =>
            f.reports.some(
                (r) => r.path.endsWith("/Progress") && r.body.IsPaused === true,
            ),
        )
        .toBe(true);
    await page.getByRole("button", { name: "Play", exact: true }).focus();
    await page.keyboard.press("Enter");
    await page.keyboard.press("Escape");
    await expect(
        page.getByRole("button", { name: /Resume from/ }),
    ).toBeFocused();
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
    expect(f.reports.at(-1)!.body.PositionTicks).toBeGreaterThanOrEqual(10e7);
    await page.keyboard.press("Enter");
    await expect(page.getByLabel("Audio", { exact: true })).toBeEnabled();
    await page.getByLabel("Audio", { exact: true }).focus();
    await page.keyboard.press("s");
    await page.keyboard.press("Enter");
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect
        .poll(() => f.mediaRequests.includes("manifest"), { timeout: 20000 })
        .toBe(true);
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    await page.getByLabel("Subtitles", { exact: true }).focus();
    await page.keyboard.press("e");
    await page.keyboard.press("Enter");
    await expect.poll(() => f.negotiations.at(-1)?.SubtitleStreamIndex).toBe(3);
    await expect.poll(() => f.mediaRequests.includes("subtitle")).toBe(true);
    await page.getByRole("button", { name: "Stop and go back" }).focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("button", { name: /Resume from/ }),
    ).toBeFocused();
    await page.keyboard.press("Backspace");
    await expect(card).toBeFocused();
    expect(f.mediaRequests).toContain("image");
});
test("Quick Connect and D-pad series → season → episode with focus return", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page, true);
    const seriesCard = page.locator('[data-focus="latest:library:series"]');
    await page.locator('[data-focus="latest:library:movie"]').focus();
    await page.keyboard.press("ArrowRight");
    await expect(seriesCard).toBeFocused();
    await page.keyboard.press("Enter");
    const season = page.getByRole("button", { name: "Season 1 Season" });
    await expect(season).toBeVisible();
    await season.focus();
    await page.keyboard.press("Enter");
    const ep = page.getByRole("button", { name: /A Quiet Morning/ });
    await expect(ep).toBeVisible();
    await ep.focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "A Quiet Morning", exact: true }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(ep).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(season).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(seriesCard).toBeFocused();
});
test("connection failure can be corrected with keyboard", async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("Server address").fill("ftp://bad.example");
    await page.keyboard.press("Enter");
    await expect(page.getByRole("alert")).toContainText("HTTP");
    await expect(page.getByLabel("Server address")).toBeVisible();
});

test("Quick Connect renews an expired attempt before exchanging viewer authorization", async ({
    page,
}) => {
    await fixture(page);
    let attempts = 0;
    await page.route(
        "https://jellyfin.example/QuickConnect/Initiate",
        async (route) => {
            attempts++;
            await route.fulfill({
                contentType: "application/json",
                headers: { "access-control-allow-origin": "*" },
                body: JSON.stringify({
                    Code: attempts === 1 ? "111111" : "222222",
                    Secret: attempts === 1 ? "expired" : "renewed",
                }),
            });
        },
    );
    await page.route(
        "https://jellyfin.example/QuickConnect/Connect?**",
        async (route) => {
            const expired =
                new URL(route.request().url()).searchParams.get("secret") ===
                "expired";
            await route.fulfill({
                status: expired ? 404 : 200,
                contentType: "application/json",
                headers: { "access-control-allow-origin": "*" },
                body: JSON.stringify({ Authenticated: !expired }),
            });
        },
    );
    await signIn(page, true);
    expect(attempts).toBe(2);
});

test("an original file that the browser refuses recovers through HLS once", async ({
    page,
}) => {
    const f = await fixture(page);
    await page.route(
        "https://jellyfin.example/Videos/movie/stream?**",
        (route) => route.abort("failed"),
    );
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').focus();
    await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "Resume from 0:10" }).focus();
    await page.keyboard.press("Enter");
    await expect
        .poll(() => f.negotiations.at(-1)?.EnableDirectPlay, { timeout: 20000 })
        .toBe(false);
    await expect
        .poll(() => f.mediaRequests.includes("manifest"), { timeout: 20000 })
        .toBe(true);
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    expect(f.negotiations).toHaveLength(2);
});
