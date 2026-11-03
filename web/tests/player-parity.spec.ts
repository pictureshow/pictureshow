/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { fixture, signIn, movie } from "./fixtures";
const jsonHeaders = {
    "access-control-allow-origin": "*",
    "content-type": "application/json",
};
async function openMovie(page: Page) {
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    await expect(page.locator("video")).toBeVisible();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    await expect
        .poll(
            async () =>
                (await page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime)) >
                    10.05 ||
                (await page
                    .getByRole("status")
                    .filter({ hasText: "Press Play" })
                    .count()) > 0,
            { timeout: 20000 },
        )
        .toBe(true);
    if (
        await page.getByRole("status").filter({ hasText: "Press Play" }).count()
    )
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime),
            { timeout: 20000 },
        )
        .toBeGreaterThan(10.05);
}
async function pause(page: Page) {
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime),
            { timeout: 20000 },
        )
        .toBeGreaterThan(10.05);
    const button = page.getByRole("button", { name: "Pause", exact: true });
    if (await button.isVisible()) await button.click();
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeVisible();
}

test("player overlay, panel Back priority, audio Off and one viewing across track changes", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    const bounds = await page.locator("video").boundingBox();
    expect(bounds!.width).toBe(page.viewportSize()!.width);
    expect(bounds!.height).toBe(page.viewportSize()!.height);
    await pause(page);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Audio", exact: true });
    await expect(dialog).toBeVisible();
    await dialog.getByRole("radio", { name: "Off", exact: true }).click();
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.muted),
    ).toBe(true);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await expect(
        page.getByRole("dialog", { name: "Subtitles", exact: true }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(
        page.getByRole("button", { name: "Subtitles", exact: true }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(
        page.getByRole("button", { name: "Stop and go back" }),
    ).toBeHidden();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeFocused();
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(1);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
});

test("caption timing changes real cues, sheets trap D-pad focus, and explicit Off survives reopen", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    await pause(page);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0,
                ),
        )
        .toBeGreaterThan(0);
    const original = await page
        .locator("video")
        .evaluate((v: HTMLVideoElement) => v.textTracks[0].cues![0].startTime);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("button", { name: "Later", exact: true }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0].cues![0].startTime,
                ),
        )
        .toBeCloseTo(original + 0.1, 3);
    await page.getByRole("button", { name: "Reset", exact: true }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0].cues![0].startTime,
                ),
        )
        .toBeCloseTo(original, 3);
    await page.getByRole("radio", { name: "Off", exact: true }).focus();
    await page.keyboard.press("ArrowDown");
    await expect(
        page.getByRole("radio", { name: /English captions/ }),
    ).toBeFocused();
    await page.keyboard.press("ArrowUp");
    await page.keyboard.press("Enter");
    await expect
        .poll(() => f.reports.at(-1)?.body.SubtitleStreamIndex)
        .toBe(-1);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("chapters and trickplay seek once and return to opener; credible intro exposes one skip action", async ({
    page,
}) => {
    const f = await fixture(page);
    await page.route(
        "https://jellyfin.example/Items/movie?**",
        async (route) => {
            const u = new URL(route.request().url());
            if (u.searchParams.get("fields") === "Chapters,Trickplay")
                return route.fulfill({
                    headers: jsonHeaders,
                    body: JSON.stringify({
                        Chapters: [
                            { Name: "Opening", StartPositionTicks: 0 },
                            { Name: "The middle", StartPositionTicks: 20e7 },
                        ],
                        Trickplay: {
                            source: {
                                "160": {
                                    Width: 160,
                                    Height: 90,
                                    TileWidth: 2,
                                    TileHeight: 2,
                                    ThumbnailCount: 8,
                                    Interval: 5000,
                                },
                            },
                        },
                    }),
                });
            await route.fallback();
        },
    );
    const sheets: string[] = [];
    await page.route(
        "https://jellyfin.example/Videos/movie/Trickplay/**",
        (route) => {
            sheets.push(new URL(route.request().url()).pathname);
            return route.fulfill({
                headers: { "access-control-allow-origin": "*" },
                contentType: "image/svg+xml",
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180"><rect width="320" height="180" fill="#625071"/></svg>',
            });
        },
    );
    await page.route(
        "https://jellyfin.example/MediaSegments/movie?**",
        (route) =>
            route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    Items: [
                        {
                            Id: "intro",
                            Type: "Intro",
                            StartTicks: 0,
                            EndTicks: 15e7,
                        },
                    ],
                }),
            }),
    );
    await openMovie(page);
    await pause(page);
    await expect(
        page.getByRole("button", { name: "Skip intro", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Skip intro", exact: true }).click();
    await expect(
        page.getByRole("button", { name: "Skip intro", exact: true }),
    ).toBeHidden();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.currentTime),
        )
        .toBeGreaterThanOrEqual(15);
    await page.getByRole("button", { name: "Chapters", exact: true }).click();
    await page
        .getByRole("button", { name: "The middle · 0:20", exact: true })
        .click();
    await expect(
        page.getByRole("button", { name: "Chapters", exact: true }),
    ).toBeFocused();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.currentTime),
        )
        .toBeGreaterThanOrEqual(20);
    await page.locator("video").focus();
    await page.keyboard.press("ArrowRight");
    await expect
        .poll(() => sheets.some((path) => path.endsWith("/160/1.jpg")))
        .toBe(true);
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(1);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("paused DirectPlay end waits for explicit Play, then advances to chronological next once", async ({
    page,
}) => {
    await page.addInitScript(() =>
        localStorage.setItem(
            "pictureshow.preferences",
            JSON.stringify({ screenReader: false }),
        ),
    );
    const f = await fixture(page);
    const current = {
            ...movie,
            Id: "episode",
            Name: "A Quiet Morning",
            Type: "Episode",
            SeriesId: "series",
            SeriesName: "Open Frames",
            ParentIndexNumber: 1,
            IndexNumber: 1,
        },
        next = {
            ...current,
            Id: "episode2",
            Name: "The Next Morning",
            IndexNumber: 2,
        };
    let completions = 0;
    await page.route(
        "https://jellyfin.example/Shows/series/Episodes?**",
        (route) =>
            route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Items: [current, next] }),
            }),
    );
    await page.route(
        "https://jellyfin.example/UserPlayedItems/episode?**",
        (route) => {
            completions++;
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Played: true }),
            });
        },
    );
    await page.route("https://jellyfin.example/Items/episode2?**", (route) =>
        route.fulfill({ headers: jsonHeaders, body: JSON.stringify(next) }),
    );
    await signIn(page);
    await page.locator('[data-focus="next:episode"]').click();
    await page
        .getByRole("button", { name: /Resume from/, exact: false })
        .click();
    await expect(page.locator("video")).toBeVisible();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    if (await page.locator("video").evaluate((v: HTMLVideoElement) => v.paused))
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await pause(page);
    await page.locator("video").focus();
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeVisible();
    expect(completions).toBe(0);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect.poll(() => completions, { timeout: 20000 }).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

async function creditsEpisode(page: Page, automatic: boolean) {
    await page.addInitScript(
        ({ automatic }) =>
            localStorage.setItem(
                "pictureshow.preferences",
                JSON.stringify({ screenReader: !automatic }),
            ),
        { automatic },
    );
    const f = await fixture(page);
    const current = {
            ...movie,
            Id: "episode",
            Name: "A Quiet Morning",
            Type: "Episode",
            SeriesId: "series",
            SeriesName: "Open Frames",
            ParentIndexNumber: 1,
            IndexNumber: 1,
        },
        next = {
            ...current,
            Id: "episode2",
            Name: "The Next Morning",
            IndexNumber: 2,
        };
    let completions = 0;
    await page.route(
        "https://jellyfin.example/Shows/series/Episodes?**",
        (route) =>
            route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Items: [current, next] }),
            }),
    );
    await page.route(
        "https://jellyfin.example/MediaSegments/episode?**",
        (route) =>
            route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    Items: [
                        {
                            Id: "credits",
                            Type: "Outro",
                            StartTicks: 10e7,
                            EndTicks: 40e7,
                        },
                    ],
                }),
            }),
    );
    await page.route(
        "https://jellyfin.example/UserPlayedItems/episode?**",
        (route) => {
            completions++;
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Played: true }),
            });
        },
    );
    await page.route("https://jellyfin.example/Items/episode2?**", (route) =>
        route.fulfill({ headers: jsonHeaders, body: JSON.stringify(next) }),
    );
    // The strict authoritative detail read uses Items?ids, not only Items/:id.
    await page.route("https://jellyfin.example/Items?**", (route) => {
        if (
            new URL(route.request().url()).searchParams.get("ids") ===
            "episode2"
        )
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Items: [next] }),
            });
        return route.fallback();
    });
    await signIn(page);
    await page.locator('[data-focus="next:episode"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    await expect
        .poll(
            async () =>
                (await page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime)) >
                    10.05 ||
                (await page
                    .getByRole("status")
                    .filter({ hasText: "Press Play" })
                    .count()) > 0,
            { timeout: 20000 },
        )
        .toBe(true);
    if (
        await page.getByRole("status").filter({ hasText: "Press Play" }).count()
    )
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime),
            { timeout: 20000 },
        )
        .toBeGreaterThan(10.05);
    await expect(
        page.getByRole("button", { name: "Play now", exact: true }),
    ).toBeVisible();
    return { ...f, completed: () => completions };
}
test("manual credits continuation completes once before next; reader choice has no countdown", async ({
    page,
}) => {
    const f = await creditsEpisode(page, false);
    await expect(
        page.getByRole("heading", { name: "Next episode", exact: true }),
    ).toBeVisible();
    expect(f.completed()).toBe(0);
    await page.getByRole("button", { name: "Play now", exact: true }).click();
    await expect.poll(f.completed).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(
        f.reports.filter(
            (r) =>
                r.path === "/Sessions/Playing" && r.body.ItemId === "episode",
        ),
    ).toHaveLength(1);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});
test("credits countdown advances once and suspends while a panel is open", async ({
    page,
}) => {
    const f = await creditsEpisode(page, true);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    // Keep the native media clock and the countdown on the same wall clock.
    // Fast-forwarding JS alone can create a real WebKit buffering hold.
    await page.waitForTimeout(12000);
    expect(f.completed()).toBe(0);
    await page.getByRole("button", { name: "Done", exact: true }).click();
    await expect.poll(f.completed, { timeout: 14000 }).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("unready HLS reaches bounded failure and Back remains usable", async ({
    page,
}) => {
    await page.clock.install();
    await fixture(page);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
        release = resolve;
    });
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) =>
            route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    PlaySessionId: "waiting-session",
                    MediaSources: [
                        {
                            ...movie.MediaSources[0],
                            SupportsDirectPlay: false,
                            TranscodingUrl: "/Videos/movie/master.m3u8",
                        },
                    ],
                }),
            }),
    );
    await page.route(
        "https://jellyfin.example/Videos/movie/master.m3u8?**",
        async (route) => {
            await gate;
            await route.abort().catch(() => {});
        },
    );
    try {
        await signIn(page);
        await page.locator('[data-focus="resume:movie"]').click();
        await page
            .getByRole("button", { name: "Resume from 0:10", exact: true })
            .click();
        await expect(
            page.getByRole("button", { name: "Stop and go back" }),
        ).toBeVisible();
        await page.clock.runFor(46000);
        await expect(
            page.getByRole("button", { name: "Retry playback", exact: true }),
        ).toBeVisible();
        await page
            .getByRole("button", { name: "Back to details", exact: true })
            .click();
        await expect(
            page.getByRole("heading", {
                name: "The Test Picture",
                exact: true,
            }),
        ).toBeVisible();
    } finally {
        release();
    }
});

test("failed completion Stop retries its frozen report then advances once", async ({
    page,
}) => {
    const f = await creditsEpisode(page, false);
    const bodies: any[] = [];
    await page.route(
        "https://jellyfin.example/Sessions/Playing/Stopped",
        async (route) => {
            bodies.push(route.request().postDataJSON());
            if (bodies.length === 1)
                return route.fulfill({
                    status: 503,
                    headers: jsonHeaders,
                    body: "{}",
                });
            await route.fallback();
        },
    );
    await page.getByRole("button", { name: "Play now", exact: true }).click();
    await expect(
        page.getByRole("button", { name: "Retry playback", exact: true }),
    ).toBeVisible();
    expect(f.completed()).toBe(0);
    await page
        .getByRole("button", { name: "Retry playback", exact: true })
        .click();
    await expect.poll(f.completed).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toEqual(bodies[0]);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("accepted Stop is not resent when only encoding cleanup fails", async ({
    page,
}) => {
    const f = await creditsEpisode(page, false);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    let deletes = 0;
    await page.route(
        "https://jellyfin.example/Videos/ActiveEncodings?**",
        async (route) => {
            deletes++;
            if (deletes === 1)
                return route.fulfill({
                    status: 503,
                    headers: jsonHeaders,
                    body: "{}",
                });
            await route.fallback();
        },
    );
    await page.getByRole("button", { name: "Play now", exact: true }).click();
    await expect(
        page.getByRole("button", { name: "Retry playback", exact: true }),
    ).toBeVisible();
    expect(f.completed()).toBe(0);
    expect(
        f.reports.filter(
            (r) => r.path.endsWith("/Stopped") && r.body.ItemId === "episode",
        ),
    ).toHaveLength(1);
    await page
        .getByRole("button", { name: "Retry playback", exact: true })
        .click();
    await expect.poll(f.completed).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(
        f.reports.filter(
            (r) => r.path.endsWith("/Stopped") && r.body.ItemId === "episode",
        ),
    ).toHaveLength(1);
    expect(deletes).toBeGreaterThanOrEqual(2);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("paused replacement composes pending Right Right Left as exactly ten seconds", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    await pause(page);
    let release!: () => void;
    let entered!: () => void;
    const started = new Promise<void>((resolve) => {
            entered = resolve;
        }),
        gate = new Promise<void>((resolve) => {
            release = resolve;
        });
    let held: any;
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        async (route) => {
            if (!held) {
                held = route.request().postDataJSON();
                entered();
                await gate;
            }
            await route.fallback();
        },
    );
    try {
        await page.getByRole("button", { name: "Audio", exact: true }).click();
        await page.getByRole("radio", { name: /Spanish/ }).click();
        await started;
        await page.locator("video").focus();
        await page.keyboard.press("ArrowRight");
        await page.keyboard.press("ArrowRight");
        await page.keyboard.press("ArrowLeft");
        release();
        await expect
            .poll(() => f.negotiations.at(-1)?.StartTimeTicks)
            .toBe(held.StartTimeTicks + 10e7);
        await expect(
            page.getByRole("button", { name: "Play", exact: true }),
        ).toBeVisible();
        await expect
            .poll(() =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.paused),
            )
            .toBe(true);
        expect(
            f.reports.filter((r) => r.path === "/Sessions/Playing"),
        ).toHaveLength(1);
        expect(
            f.reports.filter((r) => r.path.endsWith("/Stopped")),
        ).toHaveLength(0);
        await page.getByRole("button", { name: "Stop and go back" }).click();
    } finally {
        release();
    }
});

test("paused HLS seek to physical playlist end waits for explicit Play", async ({
    page,
}) => {
    const f = await fixture(page);
    let completed = 0,
        pageCrashed = false;
    page.on("crash", () => {
        pageCrashed = true;
    });
    await page.route(
        "https://jellyfin.example/UserPlayedItems/movie?**",
        (route) => {
            completed++;
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({ Played: true }),
            });
        },
    );
    await openMovie(page);
    await pause(page);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect.poll(() => f.mediaRequests.includes("manifest")).toBe(true);
    await expect(page.getByRole("status", { includeHidden: true })).toHaveText(
        "",
    );
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    await page.getByRole("slider", { name: "Position", exact: true }).focus();
    await page.keyboard.press("End");
    await expect
        .poll(() =>
            page.locator("video").evaluate((v: HTMLVideoElement) => ({
                paused: v.paused,
                atEnd: Math.abs(v.currentTime - v.duration) < 0.5,
                ready: v.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA,
                seeking: v.seeking,
            })),
        )
        .toEqual({ paused: true, atEnd: true, ready: true, seeking: false });
    expect(completed).toBe(0);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(() => ({ completed, pageCrashed }))
        .toEqual({ completed: 1, pageCrashed: false });
    await expect(
        page.getByRole("heading", { name: "The Test Picture", exact: true }),
    ).toBeVisible();
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
});

test("phone rotation preserves viewing, pause intent and selected sheet", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    await pause(page);
    const at = await page
        .locator("video")
        .evaluate((v: HTMLVideoElement) => v.currentTime);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(
        page.getByRole("button", { name: "Landscape", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await expect(
        page.getByRole("dialog", { name: "Subtitles", exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 844, height: 390 });
    await expect(
        page.getByRole("dialog", { name: "Subtitles", exact: true }),
    ).toBeVisible();
    await page
        .getByRole("button", { name: "Done", exact: true })
        .scrollIntoViewIfNeeded();
    await page.getByRole("button", { name: "Done", exact: true }).click();
    await expect(
        page.getByRole("button", { name: "Subtitles", exact: true }),
    ).toBeFocused();
    const state = await page
        .locator("video")
        .evaluate((v: HTMLVideoElement) => ({
            paused: v.paused,
            time: v.currentTime,
        }));
    expect(state.paused).toBe(true);
    expect(state.time).toBeCloseTo(at, 0);
    expect(f.negotiations).toHaveLength(1);
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(1);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("native ended event completes once after actual final frames, independently of credits countdown", async ({
    page,
}) => {
    const f = await creditsEpisode(page, false);
    await pause(page);
    await page.getByRole("slider", { name: "Position", exact: true }).focus();
    await page.keyboard.press("End");
    await page
        .getByRole("button", { name: "Back 10 seconds", exact: true })
        .click();
    await page.locator("video").evaluate((video: HTMLVideoElement) => {
        (window as unknown as { nativeEnds: number }).nativeEnds = 0;
        video.addEventListener("ended", () => {
            (window as unknown as { nativeEnds: number }).nativeEnds++;
        });
        video.playbackRate = 16;
    });
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(
            () =>
                page.evaluate(
                    () =>
                        (window as unknown as { nativeEnds: number })
                            .nativeEnds,
                ),
            { timeout: 20000 },
        )
        .toBe(1);
    expect(f.completed()).toBe(0);
    await expect(page.getByRole("status")).toContainText("Playback finished");
    await page.getByRole("button", { name: "Play now", exact: true }).click();
    await expect.poll(f.completed).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    expect(
        f.reports.filter(
            (r) => r.path.endsWith("/Stopped") && r.body.ItemId === "episode",
        ),
    ).toHaveLength(1);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("freeze policy pauses and retains the same viewing across a hidden and visible event", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    if (
        await page
            .locator("video")
            .evaluate((v) => (v as HTMLVideoElement).paused)
    )
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(
            () =>
                f.reports.filter((r) => r.path === "/Sessions/Playing").length,
        )
        .toBe(1);
    const decisions = f.negotiations.length;
    // Exercise the visibility policy deterministically. This is not a claim
    // that headless automation reproduced a phone OS suspending its browser.
    await page.evaluate(() => {
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value: true,
        });
        document.dispatchEvent(new Event("visibilitychange"));
        document.dispatchEvent(new Event("freeze"));
    });
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v) => (v as HTMLVideoElement).paused),
        )
        .toBe(true);
    await expect
        .poll(() =>
            f.reports.some(
                (r) => r.path.endsWith("/Progress") && r.body.IsPaused,
            ),
        )
        .toBe(true);
    await page.evaluate(() => {
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value: false,
        });
        document.dispatchEvent(new Event("visibilitychange"));
    });
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeVisible();
    expect(f.negotiations.length).toBe(decisions);
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(1);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v) => !(v as HTMLVideoElement).paused),
        )
        .toBe(true);
    await page
        .getByRole("button", { name: "Stop and go back", exact: true })
        .click();
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
});

test("rc26 same active track preserves stream and reports only viewer choices", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    await pause(page);
    const initial = f.reports.find((r) => r.path === "/Sessions/Playing")!.body;
    expect(initial).not.toHaveProperty("AudioStreamIndex");
    expect(initial).not.toHaveProperty("SubtitleStreamIndex");
    const negotiations = f.negotiations.length;
    const source = await page
        .locator("video")
        .evaluate((v: HTMLVideoElement) => v.currentSrc);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /English stereo/ }).click();
    await expect.poll(() => f.reports.at(-1)?.body.AudioStreamIndex).toBe(1);
    expect(f.reports.at(-1)?.body).not.toHaveProperty("SubtitleStreamIndex");
    expect(f.negotiations).toHaveLength(negotiations);
    expect(
        await page
            .locator("video")
            .evaluate((v: HTMLVideoElement) => v.currentSrc),
    ).toBe(source);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await expect
        .poll(() => f.reports.at(-1)?.body.SubtitleStreamIndex)
        .toBe(-1);
    expect(f.negotiations).toHaveLength(negotiations);
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
});

test("rc26 selected external text retries independently, caches Off on, and keeps full-file seek offsets", async ({
    page,
}) => {
    const f = await fixture(page);
    let attempts = 0;
    const paths: string[] = [];
    await page.route(
        "https://jellyfin.example/**/Subtitles/**",
        async (route) => {
            paths.push(new URL(route.request().url()).pathname);
            attempts++;
            if (attempts === 1)
                return route.fulfill({
                    status: 503,
                    headers: jsonHeaders,
                    body: "{}",
                });
            return route.fulfill({
                headers: jsonHeaders,
                contentType: "text/vtt",
                body: "WEBVTT\n\n00:15.000 --> 00:35.000\nRetry caption.\n",
            });
        },
    );
    await openMovie(page);
    expect(attempts).toBe(0);
    const negotiations = f.negotiations.length;
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect(page.getByRole("status")).toContainText(
        "Playback can continue",
    );
    const before = await page
        .locator("video")
        .evaluate((v: HTMLVideoElement) => v.currentTime);
    if (process.env.WEB_F2_EVIDENCE)
        await page.screenshot({
            path: process.env.WEB_F2_EVIDENCE,
            type: "jpeg",
            quality: 50,
        });
    // A same selected pick must preserve the original retry rather than restart its loader.
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect.poll(() => attempts).toBe(2);
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0,
                ),
        )
        .toBe(1);
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.currentTime),
        )
        .toBeGreaterThan(before);
    expect(f.negotiations).toHaveLength(negotiations);
    await pause(page);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0,
                ),
        )
        .toBe(1);
    expect(attempts).toBe(2);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect(page.getByRole("status", { includeHidden: true })).toHaveText(
        "",
    );
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate(
                    (v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0,
                ),
        )
        .toBe(1);
    expect(
        await page
            .locator("video")
            .evaluate(
                (v: HTMLVideoElement) => v.textTracks[0].cues![0].startTime,
            ),
    ).toBeCloseTo(15, 2);
    expect(paths.every((p) => /\/Subtitles\/3\/0\/Stream.vtt$/.test(p))).toBe(
        true,
    );
    expect(attempts).toBe(2);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("rc26 malformed selected captions retry and Off cancels optional loading without holding video", async ({
    page,
}) => {
    const f = await fixture(page);
    let attempts = 0;
    await page.route("https://jellyfin.example/**/Subtitles/**", (route) => {
        attempts++;
        return route.fulfill({
            headers: jsonHeaders,
            contentType: "text/vtt",
            body: "WEBVTT\n\nbad timestamp --> wrong timestamp\nmalformed optional captions\n",
        });
    });
    await openMovie(page);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect(page.getByRole("status")).toContainText(
        "Playback can continue",
    );
    await expect.poll(() => attempts).toBe(2);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await page.clock.install();
    await page.clock.runFor(12000);
    expect(attempts).toBe(2);
    expect(f.negotiations).toHaveLength(1);
    expect(
        await page
            .locator("video")
            .evaluate((v: HTMLVideoElement) => v.readyState),
    ).toBeGreaterThanOrEqual(2);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("rc26 report diagnostics bind actual status and independent origins without media secrets", async ({
    page,
}) => {
    const f = await fixture(page);
    const lines: string[] = [];
    page.on("console", (event) => {
        if (event.text().startsWith("PictureshowPlayback report"))
            lines.push(event.text());
    });
    await openMovie(page);
    await pause(page);
    await expect
        .poll(() =>
            lines.some(
                (l) =>
                    l.includes("kind=start") &&
                    l.includes(
                        "audio=omitted subtitle=omitted audioOrigin=automatic subtitleOrigin=automatic status=204 failure=none",
                    ),
            ),
        )
        .toBe(true);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /English stereo/ }).click();
    await expect
        .poll(() =>
            lines.some(
                (l) =>
                    l.includes("kind=progress") &&
                    l.includes(
                        "audio=1 subtitle=omitted audioOrigin=viewer subtitleOrigin=automatic status=204",
                    ),
            ),
        )
        .toBe(true);
    await page.route(
        "https://jellyfin.example/Sessions/Playing/Progress",
        (route) =>
            route.fulfill({ status: 503, headers: jsonHeaders, body: "{}" }),
    );
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await expect
        .poll(() => lines.some((l) => l.includes("status=503 failure=http")))
        .toBe(true);
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await expect
        .poll(() =>
            lines.some(
                (l) =>
                    l.includes("kind=stop") &&
                    l.includes(
                        "audio=omitted subtitle=omitted audioOrigin=viewer subtitleOrigin=viewer status=204",
                    ),
            ),
        )
        .toBe(true);
    for (const line of lines)
        expect(line).not.toMatch(
            /https?:|token|ApiKey|The Test Picture|caption for/,
        );
    const sequences = lines.map((l) => Number(/ seq=(\d+)/.exec(l)![1]));
    expect(sequences).toEqual([...sequences].sort((a, b) => a - b));
    expect(new Set(sequences).size).toBe(sequences.length);
    expect(f.negotiations).toHaveLength(1);
});

test("rc26 pending menu is cancelled by Back and selected entry never pulls later focus back", async ({
    page,
}) => {
    await fixture(page);
    await page.addInitScript(() => {
        const Original = window.Worker;
        window.Worker = class extends Original {
            constructor(...args: ConstructorParameters<typeof Worker>) {
                super(...args);
                const dispatch = this.dispatchEvent.bind(this);
                this.addEventListener("message", (event) => {
                    if ((event as MessageEvent & { delayed?: boolean }).delayed)
                        return;
                    event.stopImmediatePropagation();
                    setTimeout(() => {
                        const delayed = new MessageEvent("message", {
                            data: event.data,
                        }) as MessageEvent & { delayed?: boolean };
                        delayed.delayed = true;
                        (window as any).__trackMenuMessages =
                            ((window as any).__trackMenuMessages ?? 0) + 1;
                        dispatch(delayed);
                    }, 700);
                });
            }
        };
    });
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.keyboard.press("Escape");
    await expect(
        page.getByRole("dialog", { name: "Audio", exact: true }),
    ).toBeHidden();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.readyState),
        )
        .toBeGreaterThanOrEqual(2);
    await expect
        .poll(() =>
            page.evaluate(() => (window as any).__trackMenuMessages ?? 0),
        )
        .toBe(1);
    await expect(
        page.getByRole("dialog", { name: "Audio", exact: true }),
    ).toBeHidden();
    // A complete current menu opens only after this new explicit request.
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await expect(
        page.getByRole("radio", { name: /English stereo/ }),
    ).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(page.getByRole("radio", { name: /Spanish/ })).toBeFocused();
    await page.clock.install();
    await page.clock.runFor(8000);
    await expect(page.getByRole("radio", { name: /Spanish/ })).toBeFocused();
    expect(await page.evaluate(() => (window as any).__trackMenuMessages)).toBe(
        1,
    );
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("rc26 relative steps retain target after negotiation until replacement READY", async ({
    page,
}) => {
    const f = await fixture(page);
    await openMovie(page);
    await pause(page);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.at(-1)?.AudioStreamIndex).toBe(2);
    await expect(page.getByRole("status", { includeHidden: true })).toHaveText(
        "",
    );
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.readyState),
        )
        .toBeGreaterThanOrEqual(2);
    const base = f.negotiations.at(-1)!.StartTimeTicks;
    let release!: () => void, entered!: () => void;
    const gate = new Promise<void>((r) => (release = r)),
        manifest = new Promise<void>((r) => (entered = r));
    await page.route(
        "https://jellyfin.example/Videos/movie/master.m3u8?**",
        async (route) => {
            entered();
            await gate;
            await route.fallback();
        },
    );
    try {
        await page.locator("video").focus();
        await page.keyboard.press("ArrowRight");
        await manifest;
        // The decoder has the first server response but no metadata/READY acknowledgement.
        await expect
            .poll(() => f.negotiations.at(-1)?.StartTimeTicks)
            .toBe(base + 10e7);
        await page.waitForTimeout(800);
        await page.keyboard.press("ArrowRight");
        await page.waitForTimeout(800);
        await page.keyboard.press("ArrowRight");
        await page.waitForTimeout(900); // preview timeout cannot erase the pending target
        release();
        await expect
            .poll(() => f.negotiations.at(-1)?.StartTimeTicks)
            .toBe(Math.min(base + 30e7, 40e7));
        try {
            await expect(
                page.getByRole("status", { includeHidden: true }),
            ).toHaveText("");
        } catch (error) {
            console.log(
                "Native endpoint READY state",
                await page.locator("video").evaluate((v: HTMLVideoElement) => ({
                    time: v.currentTime,
                    duration: v.duration,
                    paused: v.paused,
                    seeking: v.seeking,
                    readyState: v.readyState,
                    networkState: v.networkState,
                    srcScheme: new URL(v.currentSrc).protocol,
                    nativeHls: v.canPlayType("application/vnd.apple.mpegurl"),
                    buffered: Array.from(
                        { length: v.buffered.length },
                        (_, i) => [v.buffered.start(i), v.buffered.end(i)],
                    ),
                    seekable: Array.from(
                        { length: v.seekable.length },
                        (_, i) => [v.seekable.start(i), v.seekable.end(i)],
                    ),
                })),
            );
            throw error;
        }
        await expect(
            page.getByRole("button", { name: "Play", exact: true }),
        ).toBeVisible();
        expect(
            f.reports.filter((r) => r.path === "/Sessions/Playing"),
        ).toHaveLength(1);
        expect(
            f.reports.filter((r) => r.path.endsWith("/Stopped")),
        ).toHaveLength(0);
        await page.getByRole("button", { name: "Stop and go back" }).click();
    } finally {
        release();
    }
});

test("rc28 automatic captions retain the server text default with source binding and omit automatic reports", async ({
    page,
}) => {
    const f = await fixture(page);
    const source = {
        ...movie.MediaSources[0],
        DefaultSubtitleStreamIndex: 3,
        MediaStreams: [
            ...movie.MediaSources[0].MediaStreams.map((s) =>
                s.Index === 3 ? { ...s, Language: "eng" } : s,
            ),
            {
                Type: "Subtitle",
                Index: 4,
                Codec: "pgssub",
                Language: "eng",
                DisplayTitle: "English image",
            },
        ],
    };
    await page.route("https://jellyfin.example/Items/movie?**", (route) =>
        route.fulfill({
            headers: jsonHeaders,
            body: JSON.stringify({ ...movie, MediaSources: [source] }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) => {
            const body = route.request().postDataJSON();
            f.negotiations.push(body);
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    PlaySessionId: "auto-text",
                    MediaSources: [
                        {
                            ...source,
                            SupportsDirectPlay: body.EnableDirectPlay,
                            TranscodingUrl: "/Videos/movie/master.m3u8",
                        },
                    ],
                }),
            });
        },
    );
    await openMovie(page);
    await pause(page);
    expect(f.negotiations[0]).not.toHaveProperty("SubtitleStreamIndex");
    expect(f.negotiations[0].MediaSourceId).toBe("source");
    const start = f.reports.find((r) => r.path === "/Sessions/Playing")!.body;
    expect(start).not.toHaveProperty("AudioStreamIndex");
    expect(start).not.toHaveProperty("SubtitleStreamIndex");
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await expect(
        page.getByRole("radio", { name: /English · SUBRIP/ }),
    ).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("rc26 text-codec Encode transitions renegotiate and Audio Off remains explicit across replacement", async ({
    page,
}) => {
    const f = await fixture(page);
    const source = {
        ...movie.MediaSources[0],
        MediaStreams: movie.MediaSources[0].MediaStreams.map((s) =>
            s.Index === 3 ? { ...s, DeliveryMethod: "Encode" } : s,
        ),
    };
    await page.route("https://jellyfin.example/Items/movie?**", (route) =>
        route.fulfill({
            headers: jsonHeaders,
            body: JSON.stringify({ ...movie, MediaSources: [source] }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) => {
            const body = route.request().postDataJSON();
            f.negotiations.push(body);
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    PlaySessionId: "encode-" + f.negotiations.length,
                    MediaSources: [
                        {
                            ...source,
                            SupportsDirectPlay: body.EnableDirectPlay,
                            TranscodingUrl: "/Videos/movie/master.m3u8",
                        },
                    ],
                }),
            });
        },
    );
    await openMovie(page);
    await pause(page);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await expect.poll(() => f.reports.at(-1)?.body.AudioStreamIndex).toBe(-1);
    const before = f.negotiations.length;
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English captions/ }).click();
    await expect.poll(() => f.negotiations.length).toBe(before + 1);
    expect(f.negotiations.at(-1).EnableDirectPlay).toBe(false);
    await expect(page.getByRole("status", { includeHidden: true })).toHaveText(
        "",
    );
    await expect.poll(() => f.reports.at(-1)?.body.AudioStreamIndex).toBe(-1);
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.muted),
    ).toBe(true);
    expect(f.mediaRequests.filter((p) => p === "subtitle")).toHaveLength(0);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await expect.poll(() => f.negotiations.length).toBe(before + 2);
    await expect(page.getByRole("status", { includeHidden: true })).toHaveText(
        "",
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("rc26 delayed optional subtitle HTTP outlives the A/V deadline without blocking playback", async ({
    page,
}) => {
    const f = await fixture(page);
    let release!: () => void, entered!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve)),
        requested = new Promise<void>((resolve) => (entered = resolve));
    await page.route(
        "https://jellyfin.example/**/Subtitles/**",
        async (route) => {
            entered();
            await gate;
            await route.abort().catch(() => {});
        },
    );
    try {
        await openMovie(page);
        await page
            .getByRole("button", { name: "Subtitles", exact: true })
            .click();
        await page.getByRole("radio", { name: /English captions/ }).click();
        await requested;
        const before = await page
            .locator("video")
            .evaluate((v: HTMLVideoElement) => v.currentTime);
        await expect
            .poll(() =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime),
            )
            .toBeGreaterThan(before + 0.2);
        await page.clock.install();
        await page.clock.runFor(46000);
        await expect(
            page.getByRole("button", { name: "Retry playback", exact: true }),
        ).toBeHidden();
        expect(
            await page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.readyState),
        ).toBeGreaterThanOrEqual(2);
        expect(f.negotiations).toHaveLength(1);
        expect(
            f.reports.filter((r) => r.path.endsWith("/Stopped")),
        ).toHaveLength(0);
        await page.locator("video").focus();
        await page.keyboard.press("Enter");
        await page
            .getByRole("button", { name: "Subtitles", exact: true })
            .click();
        await page.getByRole("radio", { name: "Off", exact: true }).click();
        await page.getByRole("button", { name: "Stop and go back" }).click();
    } finally {
        release();
    }
});

test("rc26 server-declared text with unknown codec loads converted cues and switches locally", async ({
    page,
}) => {
    const f = await fixture(page);
    const source = {
        ...movie.MediaSources[0],
        MediaStreams: movie.MediaSources[0].MediaStreams.map((s) =>
            s.Index === 3
                ? { ...s, Codec: "future_text", IsTextSubtitleStream: true }
                : s,
        ),
    };
    await page.route("https://jellyfin.example/Items/movie?**", (route) =>
        route.fulfill({
            headers: jsonHeaders,
            body: JSON.stringify({ ...movie, MediaSources: [source] }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) => {
            const body = route.request().postDataJSON();
            f.negotiations.push(body);
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    PlaySessionId: "flagged-text",
                    MediaSources: [
                        {
                            ...source,
                            SupportsDirectPlay: body.EnableDirectPlay,
                            TranscodingUrl: "/Videos/movie/master.m3u8",
                        },
                    ],
                }),
            });
        },
    );
    await openMovie(page);
    await pause(page);
    const before = f.negotiations.length;
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: /English.*FUTURE_TEXT/ }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) =>
                    [...v.textTracks].some(
                        (t) =>
                            t.mode === "showing" && (t.cues?.length ?? 0) > 0,
                    ),
                ),
        )
        .toBe(true);
    expect(f.negotiations).toHaveLength(before);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    await page.getByRole("radio", { name: "Off", exact: true }).click();
    await expect
        .poll(() => f.reports.at(-1)?.body.SubtitleStreamIndex)
        .toBe(-1);
    expect(f.negotiations).toHaveLength(before);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

for (const action of ["Play now", "Cancel next episode", "background"] as const)
    test(`background continuation during committed Stop: ${action}`, async ({
        page,
    }) => {
        await page.clock.install();
        const f = await creditsEpisode(page, true);
        const successorRequests: string[] = [];
        page.on("request", (request) => {
            if (
                new URL(request.url()).pathname ===
                "/Items/episode2/PlaybackInfo"
            )
                successorRequests.push(request.url());
        });
        let release!: () => void, entered!: () => void;
        const gate = new Promise<void>((resolve) => (release = resolve)),
            stopped = new Promise<void>((resolve) => (entered = resolve));
        await page.route(
            "https://jellyfin.example/Sessions/Playing/Stopped",
            async (route) => {
                if (route.request().postDataJSON().ItemId === "episode") {
                    entered();
                    await gate;
                }
                await route.fallback();
            },
        );
        try {
            await page.clock.runFor(11000);
            await stopped;
            await page.evaluate(() => {
                Object.defineProperty(document, "hidden", {
                    configurable: true,
                    value: true,
                });
                document.dispatchEvent(new Event("visibilitychange"));
            });
            if (action !== "background")
                await page.evaluate(() =>
                    document.dispatchEvent(new Event("freeze")),
                );
            release();
            await expect.poll(f.completed).toBe(1);
            if (action === "background") {
                await expect.poll(() => successorRequests.length).toBe(1);
                await expect(
                    page.getByRole("heading", {
                        name: "The Next Morning",
                        exact: true,
                    }),
                ).toBeVisible();
                await expect
                    .poll(() =>
                        page
                            .locator("video")
                            .evaluate((v: HTMLVideoElement) => !v.paused),
                    )
                    .toBe(true);
                await page
                    .getByRole("button", { name: "Stop and go back" })
                    .click();
                return;
            }
            await expect(page.getByRole("status")).toContainText(
                "Choose Play now",
            );
            expect(successorRequests).toHaveLength(0);
            await page.evaluate(() => {
                Object.defineProperty(document, "hidden", {
                    configurable: true,
                    value: false,
                });
                document.dispatchEvent(new Event("visibilitychange"));
            });
            await page.clock.runFor(15000);
            await expect(
                page.getByRole("heading", {
                    name: "The Next Morning",
                    exact: true,
                }),
            ).toBeHidden();
            await page
                .getByRole("button", { name: action, exact: true })
                .click();
            if (action === "Play now") {
                await expect(
                    page.getByRole("heading", {
                        name: "The Next Morning",
                        exact: true,
                    }),
                ).toBeVisible();
                await page
                    .getByRole("button", { name: "Stop and go back" })
                    .click();
            } else {
                await expect(page.locator("video")).toBeHidden();
                await expect(
                    page.getByRole("heading", {
                        name: "The Next Morning",
                        exact: true,
                    }),
                ).toBeHidden();
            }
            expect(f.completed()).toBe(1);
            expect(
                f.reports.filter(
                    (r) =>
                        r.path.endsWith("/Stopped") &&
                        r.body.ItemId === "episode",
                ),
            ).toHaveLength(1);
        } finally {
            release();
        }
    });

test("background countdown opens the next decoder while hidden without a stop latch", async ({
    page,
}) => {
    const f = await creditsEpisode(page, true);
    await page.evaluate(() => {
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value: true,
        });
        document.dispatchEvent(new Event("visibilitychange"));
    });
    // Use real decoder/wall time: changing the JS clock does not advance media.
    await expect.poll(f.completed, { timeout: 20000 }).toBe(1);
    await expect(
        page.getByRole("heading", { name: "The Next Morning", exact: true }),
    ).toBeVisible();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => !v.paused),
            { timeout: 20000 },
        )
        .toBe(true);
    expect(await page.evaluate(() => document.hidden)).toBe(true);
    expect(
        f.reports.filter(
            (r) => r.path.endsWith("/Stopped") && r.body.ItemId === "episode",
        ),
    ).toHaveLength(1);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});
