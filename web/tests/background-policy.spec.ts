/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { fixture, signIn, movie } from "./fixtures";
async function hidden(page: Page, value: boolean) {
    await page.evaluate((value) => {
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value,
        });
        document.dispatchEvent(new Event("visibilitychange"));
    }, value);
}
async function media(page: Page, action: string, details = {}) {
    await page.evaluate(
        ({ action, details }) => {
            const handler = (window as any).mediaActions[action];
            if (!handler)
                throw Error("Missing Media Session handler: " + action);
            handler({ action, ...details });
        },
        { action, details },
    );
}
async function setup(page: Page, hls = false) {
    // Invoke the actual registered app actions in engines whose headless OS
    // does not surface hardware Media Session controls. This is not an OS pilot.
    await page.addInitScript(() => {
        (window as any).mediaActions = {};
        Object.defineProperty(navigator, "mediaSession", {
            configurable: true,
            value: {
                setActionHandler(action: string, handler: unknown) {
                    (window as any).mediaActions[action] = handler;
                },
                setPositionState() {},
                metadata: null,
                playbackState: "none",
            },
        });
    });
    const f = await fixture(page);
    if (hls)
        await page.route(
            "https://jellyfin.example/Items/movie/PlaybackInfo",
            (route) => {
                f.negotiations.push(route.request().postDataJSON());
                return route.fulfill({
                    contentType: "application/json",
                    headers: { "access-control-allow-origin": "*" },
                    body: JSON.stringify({
                        PlaySessionId: "background-" + f.negotiations.length,
                        MediaSources: [
                            {
                                ...movie.MediaSources[0],
                                SupportsDirectPlay: false,
                                TranscodingUrl: "/Videos/movie/master.m3u8",
                            },
                        ],
                    }),
                });
            },
        );
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    const video = page.locator("video");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    if (
        await page.getByRole("status").filter({ hasText: "Press Play" }).count()
    )
        await media(page, "play");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), {
            timeout: 20000,
        })
        .toBeGreaterThan(10.05);
    return f;
}
test("background tab keeps playing and Media Session pause, play and seeks work without a stop latch", async ({
    page,
}) => {
    const f = await setup(page),
        video = page.locator("video");
    await hidden(page, true);
    const before = await video.evaluate((v: HTMLVideoElement) => v.currentTime);
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeGreaterThan(before + 0.3);
    await media(page, "pause");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.paused))
        .toBe(true);
    await media(page, "seekto", { seekTime: 12 });
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeCloseTo(12, 0);
    await media(page, "seekforward");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeCloseTo(22, 0);
    await media(page, "seekbackward");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeCloseTo(12, 0);
    await media(page, "play");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => !v.paused))
        .toBe(true);
    const resumed = await video.evaluate(
        (v: HTMLVideoElement) => v.currentTime,
    );
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeGreaterThan(resumed + 0.2);
    await hidden(page, false);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(false);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});
for (const event of ["pagehide", "freeze", "browser-pause"] as const)
    test(`${event} latches a background viewing; late playing and hidden media Play cannot release it`, async ({
        page,
    }) => {
        const f = await setup(page),
            video = page.locator("video");
        await hidden(page, true);
        await page.evaluate((event) => {
            if (event === "pagehide")
                window.dispatchEvent(
                    new PageTransitionEvent("pagehide", { persisted: true }),
                );
            else if (event === "freeze")
                document.dispatchEvent(new Event("freeze"));
            else document.querySelector("video")!.pause(); // no app pause command
        }, event);
        await expect
            .poll(() => video.evaluate((v: HTMLVideoElement) => v.paused))
            .toBe(true);
        await media(page, "play");
        await video.evaluate((v) => v.dispatchEvent(new Event("playing")));
        await page.waitForTimeout(200);
        expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(
            true,
        );
        await hidden(page, false);
        await page.evaluate(
            (event) =>
                event === "pagehide"
                    ? window.dispatchEvent(
                          new PageTransitionEvent("pageshow", {
                              persisted: true,
                          }),
                      )
                    : document.dispatchEvent(new Event("resume")),
            event,
        );
        await page.waitForTimeout(200);
        expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(
            true,
        );
        await expect
            .poll(() =>
                f.reports.some(
                    (r) => r.path.endsWith("/Progress") && r.body.IsPaused,
                ),
            )
            .toBe(true);
        await media(page, "play");
        await expect
            .poll(() => video.evaluate((v: HTMLVideoElement) => !v.paused))
            .toBe(true);
        expect(
            f.reports.filter((r) => r.path.endsWith("/Stopped")),
        ).toHaveLength(0);
        await page.getByRole("button", { name: "Stop and go back" }).click();
    });

test("hidden HLS replacement keeps playing and its owned pause cannot swallow a later browser stop", async ({
    page,
}) => {
    const f = await setup(page, true),
        video = page.locator("video");
    await hidden(page, true);
    await media(page, "seekto", { seekTime: 20 });
    await expect.poll(() => f.negotiations.length).toBe(2);
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), {
            timeout: 20000,
        })
        .toBeGreaterThan(20.1);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(false);
    await video.evaluate((v: HTMLVideoElement) => v.pause());
    await expect.poll(() => f.reports.at(-1)?.body.IsPaused).toBe(true);
    await media(page, "play");
    await page.waitForTimeout(200);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(true);
    await hidden(page, false);
    await media(page, "play");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => !v.paused))
        .toBe(true);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("a browser pause queued behind visibility return still requires deliberate foreground Play", async ({
    page,
}) => {
    await setup(page);
    await hidden(page, true);
    await page.evaluate(() => {
        document.querySelector("video")!.pause();
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value: false,
        });
        document.dispatchEvent(new Event("visibilitychange"));
    });
    await page.waitForTimeout(200);
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.paused),
    ).toBe(true);
    await media(page, "play");
    await expect
        .poll(() =>
            page.locator("video").evaluate((v: HTMLVideoElement) => !v.paused),
        )
        .toBe(true);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

for (const origin of ["metadata", "media-command"] as const)
    test(`a stale ${origin} play rejection cannot latch a newer background HLS decoder`, async ({
        page,
    }) => {
        await page.addInitScript((origin) => {
            const nativePlay = HTMLMediaElement.prototype.play;
            (window as any).holdNextPlay = origin === "metadata";
            HTMLMediaElement.prototype.play = function () {
                const result = nativePlay.call(this);
                if (!(window as any).holdNextPlay) return result;
                (window as any).holdNextPlay = false;
                result.catch(() => {});
                return new Promise<void>((_, reject) => {
                    (window as any).rejectOldPlay = () =>
                        reject(
                            new DOMException(
                                "Superseded decoder",
                                "AbortError",
                            ),
                        );
                });
            };
        }, origin);
        const f = await setup(page, true),
            video = page.locator("video");
        await hidden(page, true);
        if (origin === "media-command") {
            await media(page, "pause");
            await page.evaluate(() => {
                (window as any).holdNextPlay = true;
            });
            await media(page, "play");
        }
        await expect
            .poll(() =>
                page.evaluate(() => typeof (window as any).rejectOldPlay),
            )
            .toBe("function");
        await media(page, "seekto", { seekTime: 20 });
        await expect.poll(() => f.negotiations.length).toBe(2);
        await expect
            .poll(
                () => video.evaluate((v: HTMLVideoElement) => v.currentTime),
                { timeout: 20000 },
            )
            .toBeGreaterThan(20.1);
        await page.evaluate(() => (window as any).rejectOldPlay());
        const at = await video.evaluate((v: HTMLVideoElement) => v.currentTime);
        await expect
            .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
            .toBeGreaterThan(at + 0.2);
        expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(
            false,
        );
        await hidden(page, false);
        await page.getByRole("button", { name: "Stop and go back" }).click();
    });
