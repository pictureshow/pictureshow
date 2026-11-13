/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect } from "@playwright/test";
import { fixture, signIn, trackNames } from "./fixtures";
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
    // The same 20 s the video polls above get: a loaded runner reports late, and a report that never comes still fails.
    await expect
        .poll(() => f.reports.some((r) => r.path === "/Sessions/Playing"), {
            timeout: 20000,
        })
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
    await page.keyboard.press("Enter");
    await page
        .getByRole("radio", { name: trackNames.spanishAudio, exact: true })
        .focus();
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
    const beforeLocalCaptions = f.negotiations.length;
    await page.getByLabel("Subtitles", { exact: true }).focus();
    await page.keyboard.press("Enter");
    await page
        .getByRole("radio", { name: trackNames.englishCaptions, exact: true })
        .focus();
    await page.keyboard.press("Enter");
    await expect.poll(() => f.reports.at(-1)?.body.SubtitleStreamIndex).toBe(3);
    expect(f.reports.at(-1)?.body.AudioStreamIndex).toBe(2);
    expect(f.negotiations).toHaveLength(beforeLocalCaptions);
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
    const season = page.getByRole("button", { name: /^Season 1/ });
    await expect(season).toBeVisible();
    await season.focus();
    await page.keyboard.press("Enter");
    const ep = page.getByRole("button", { name: /A Quiet Morning/ });
    await expect(ep).toBeVisible();
    await expect(page.locator(".catalog-eyebrow")).toContainText("Open Frames");
    await ep.focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "A Quiet Morning", exact: true }),
    ).toBeVisible();
    await expect
        .poll(async () => {
            // Authenticated artwork replaces its loading placeholder. Query the
            // current node's geometry rather than retaining a detached element.
            const art = await page.locator(".catalog-detail-art").boundingBox();
            return art ? art.width / art.height : 0;
        })
        .toBeGreaterThan(1);
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
