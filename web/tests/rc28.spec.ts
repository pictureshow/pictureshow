/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { fixture, movie, signIn } from "./fixtures";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import axe from "axe-core";
const headers = {
    "access-control-allow-origin": "*",
    "content-type": "application/json",
};
function gate() {
    let release!: () => void;
    const promise = new Promise<void>((resolve) => {
        release = resolve;
    });
    return { promise, release };
}
async function observe(page: Page) {
    await page.addInitScript(() => {
        (window as any).detailCues = [];
        let last = "";
        new MutationObserver(() => {
            const text =
                document
                    .querySelector("[data-detail-feedback]")
                    ?.textContent?.trim() ?? "";
            if (text !== last) {
                last = text;
                if (text)
                    (window as any).detailCues.push({
                        text,
                        focus:
                            (document.activeElement as HTMLElement)?.dataset
                                .focus ?? "",
                        at: performance.now(),
                    });
            }
        }).observe(document, {
            subtree: true,
            childList: true,
            characterData: true,
        });
    });
}
async function recoveryGeometry(
    page: Page,
    state: "held" | "pending" | "failed",
) {
    const previous = page.viewportSize();
    for (const [width, height] of [
        [1920, 1080],
        [1280, 800],
        [600, 900],
        [768, 900],
        [800, 900],
        [390, 844],
        ...(state === "held"
            ? [
                  [360, 844],
                  [375, 844],
              ]
            : []),
    ]) {
        await page.setViewportSize({ width, height });
        await page.evaluate(() => window.scrollTo(0, 0));
        if (state === "held") {
            const restart = page.locator('[data-focus="restart"]');
            await expect(restart).toHaveAccessibleName(
                "Play from start when ready",
            );
            if (width < 600)
                expect(
                    (await restart.boundingBox())!.height,
                ).toBeLessThanOrEqual(49);
        } else if (state === "pending") {
            const status = page.locator(".catalog-detail-status");
            await expect(status).toBeVisible();
            expect(
                await status.evaluate((e) => {
                    const r = e.getBoundingClientRect(),
                        header = document
                            .querySelector(".app-header")!
                            .getBoundingClientRect();
                    const nav = document.querySelector(".root-navigation")!,
                        n = nav.getBoundingClientRect();
                    const overlaps = (a: DOMRect, b: DOMRect) =>
                        a.left < b.right &&
                        a.right > b.left &&
                        a.top < b.bottom &&
                        a.bottom > b.top;
                    const footer = document.querySelector("footer");
                    const footerClear =
                        !footer ||
                        getComputedStyle(footer).display === "none" ||
                        !overlaps(r, footer.getBoundingClientRect());
                    const originalPointerEvents = (e as HTMLElement).style
                        .pointerEvents;
                    (e as HTMLElement).style.pointerEvents = "auto";
                    const visibleAtPoint =
                        document
                            .elementFromPoint(
                                r.x + r.width / 2,
                                r.y + r.height / 2,
                            )
                            ?.closest(".catalog-detail-status") === e;
                    (e as HTMLElement).style.pointerEvents =
                        originalPointerEvents;
                    return (
                        !overlaps(r, header) &&
                        footerClear &&
                        ![
                            ...document.querySelectorAll(
                                ".catalog-detail-actions button",
                            ),
                        ].some((button) =>
                            overlaps(r, button.getBoundingClientRect()),
                        ) &&
                        (getComputedStyle(nav).display === "none" ||
                            !overlaps(r, n)) &&
                        visibleAtPoint
                    );
                }),
            ).toBe(true);
        } else {
            const retry = page.getByRole("button", {
                name: "Retry saving progress",
                exact: true,
            });
            await expect(retry).toBeVisible();
            const box = (await retry.boundingBox())!;
            const navTop = await page
                .locator(".root-navigation")
                .evaluate((e) =>
                    getComputedStyle(e).display === "none"
                        ? innerHeight
                        : e.getBoundingClientRect().top,
                );
            expect(box.y).toBeGreaterThanOrEqual(0);
            expect(box.y + box.height).toBeLessThanOrEqual(
                width < 600 ? navTop : height,
            );
            await expect(page.locator(".catalog-detail-status")).toHaveCount(0);
            await expect(page.locator(".catalog-error")).not.toContainText(
                "stop report",
            );
        }
    }
    if (previous) await page.setViewportSize(previous);
}

async function evidence(page: Page, name: string, screenshots = false) {
    const requestedDirectory = process.env.WEB_F3_EVIDENCE;
    if (!requestedDirectory) return;
    const directory = join(requestedDirectory, test.info().project.name);
    mkdirSync(directory, { recursive: true });
    mkdirSync(join(directory, "full"), { recursive: true });
    const base = join(directory, name);
    writeFileSync(
        base + ".aria.txt",
        await page.locator("body").ariaSnapshot(),
    );
    writeFileSync(
        base + ".json",
        JSON.stringify(
            await page.evaluate(() => ({
                cues: (window as any).detailCues,
                focus: {
                    key: (document.activeElement as HTMLElement)?.dataset.focus,
                    text: document.activeElement?.textContent?.trim(),
                },
                regions: [
                    ...document.querySelectorAll("[aria-live], [role=alert]"),
                ].map((e) => ({
                    live: e.getAttribute("aria-live"),
                    role: e.getAttribute("role"),
                    text: e.textContent?.trim(),
                })),
            })),
            null,
            2,
        ),
    );
    if (!screenshots) return;
    const previous = page.viewportSize();
    for (const [size, width, height] of [
        ["tv", 1920, 1080],
        ["desktop", 1280, 800],
        ["tablet", 600, 900],
        ["tablet768", 768, 900],
        ["tablet800", 800, 900],
        ["phone", 390, 844],
        ["phone360", 360, 844],
        ["phone375", 375, 844],
    ] as const) {
        if (
            ["tablet768", "tablet800", "phone360", "phone375"].includes(size) &&
            name !== "held"
        )
            continue;
        await page.setViewportSize({ width, height });
        await page.evaluate(
            () =>
                new Promise<void>((resolve) =>
                    requestAnimationFrame(() =>
                        requestAnimationFrame(() => resolve()),
                    ),
                ),
        );
        const target = `${base}-${size}.jpg`;
        await page.screenshot({
            path: join(directory, "full", `${name}-${size}.jpg`),
            type: "jpeg",
            quality: 90,
        });
        await page.screenshot({ path: target, type: "jpeg", quality: 50 });
        if (name === "completion-failed" && size === "phone") {
            const keys = ["play", "watched", "favorite", "restart"];
            await page.locator('[data-focus="play"]').focus();
            const phoneActions = [];
            for (let i = 0; i < keys.length; i++) {
                if (i) await page.keyboard.press("Tab");
                const button = page.locator(`[data-focus="${keys[i]}"]`);
                await expect(button).toBeFocused();
                const bounds = (await button.boundingBox())!;
                const navTop = await page
                    .locator(".root-navigation")
                    .evaluate((e) => e.getBoundingClientRect().top);
                expect(bounds.y).toBeGreaterThanOrEqual(0);
                expect(bounds.y + bounds.height).toBeLessThanOrEqual(navTop);
                phoneActions.push({ key: keys[i], bounds, navTop });
            }
            writeFileSync(
                join(directory, name + "-phone-actions.json"),
                JSON.stringify(phoneActions, null, 2),
            );
            await page.evaluate(() =>
                window.scrollTo(0, document.documentElement.scrollHeight),
            );
            await page.locator('[data-focus="favorite"]').focus();
            await page.screenshot({
                path: join(directory, "full", name + "-phone-actions.jpg"),
                type: "jpeg",
                quality: 90,
            });
            const actionsShot = join(directory, name + "-phone-actions.jpg");
            await page.screenshot({
                path: actionsShot,
                type: "jpeg",
                quality: 50,
            });
            if (process.platform === "darwin")
                execFileSync("sips", ["-Z", "800", actionsShot], {
                    stdio: "ignore",
                });
            await page.evaluate(() => window.scrollTo(0, 0));
        }
        if (process.platform === "darwin")
            execFileSync("sips", ["-Z", "800", target], { stdio: "ignore" });
        writeFileSync(
            `${base}-${size}.geom.json`,
            JSON.stringify(
                await page.locator(".catalog-detail-actions").evaluate((e) => ({
                    viewport: [innerWidth, innerHeight],
                    surfaces: Object.fromEntries(
                        [
                            ".catalog-error",
                            ".catalog-error p",
                            ".catalog-error button",
                            ".catalog-detail-status",
                            "footer",
                            ".app-header",
                            ".root-navigation",
                        ].map((selector) => {
                            const node = document.querySelector(selector);
                            return [
                                selector,
                                node
                                    ? {
                                          bounds: node
                                              .getBoundingClientRect()
                                              .toJSON(),
                                          display:
                                              getComputedStyle(node).display,
                                          font: getComputedStyle(node).font,
                                          padding:
                                              getComputedStyle(node).padding,
                                      }
                                    : null,
                            ];
                        }),
                    ),
                    focus: document.activeElement
                        ? {
                              tag: document.activeElement.tagName,
                              key: (document.activeElement as HTMLElement)
                                  .dataset.focus,
                              text: document.activeElement.textContent?.trim(),
                              bounds: document.activeElement
                                  .getBoundingClientRect()
                                  .toJSON(),
                          }
                        : null,
                    actions: [...e.querySelectorAll("button")].map((b) => ({
                        label: b.textContent?.trim(),
                        bounds: b.getBoundingClientRect().toJSON(),
                        disabled: b.disabled,
                        ariaDisabled: b.getAttribute("aria-disabled"),
                    })),
                })),
                null,
                2,
            ),
        );
    }
    if (previous) await page.setViewportSize(previous);
}
async function openDetail(page: Page) {
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await expect(page.locator('[data-focus="play"]')).toBeFocused();
}
async function playing(page: Page) {
    const video = page.locator("video");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    if (
        await page
            .getByRole("button", { name: "Play", exact: true })
            .isVisible()
    )
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), {
            timeout: 20000,
        })
        .toBeGreaterThan(10.05);
}
async function visible(page: Page, foreground: boolean) {
    await page.evaluate((f) => {
        Object.defineProperty(document, "hidden", {
            configurable: true,
            value: !f,
        });
        Object.defineProperty(document, "visibilityState", {
            configurable: true,
            value: f ? "visible" : "hidden",
        });
        document.dispatchEvent(new Event("visibilitychange"));
    }, foreground);
}

test("rc28 arrival inside grace keeps the plain name and never emits Ready", async ({
    page,
}) => {
    await observe(page);
    await fixture(page);
    const read = gate();
    await page.route(
        "https://jellyfin.example/Items/movie?**",
        async (route) => {
            await read.promise;
            await route.fulfill({ headers, body: JSON.stringify(movie) });
        },
    );
    await openDetail(page);
    const play = page.locator('[data-focus="play"]');
    await expect(play).toHaveAccessibleName("Play");
    await expect(play).toHaveAttribute("aria-disabled", "true");
    expect(await play.evaluate((e: HTMLButtonElement) => e.disabled)).toBe(
        false,
    );
    read.release();
    await expect(play).toHaveAttribute("aria-disabled", "false");
    await page.waitForTimeout(500);
    await evidence(page, "arrival-grace", true);
    expect(await page.evaluate(() => (window as any).detailCues)).toEqual([]);
    await expect(play).toBeFocused();
});
test("rc28 a held keyboard press is accepted once and runs once without Ready", async ({
    page,
}) => {
    await observe(page);
    const f = await fixture(page),
        read = gate();
    await page.route(
        "https://jellyfin.example/Items/movie?**",
        async (route) => {
            await read.promise;
            await route.fulfill({ headers, body: JSON.stringify(movie) });
        },
    );
    await openDetail(page);
    const play = page.locator('[data-focus="play"]');
    await expect(play).toHaveAccessibleName("Play when ready", {
        timeout: 4000,
    });
    await recoveryGeometry(page, "held");
    await evidence(page, "held", true);
    expect(await play.evaluate((e: HTMLButtonElement) => e.disabled)).toBe(
        false,
    );
    await page.keyboard.press("Enter");
    await expect(page.locator("[data-detail-feedback]")).toHaveText(
        "Play requested",
    );
    await page.keyboard.press("Enter");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowLeft");
    await expect(play).toHaveAccessibleName("Play already requested");
    await page.keyboard.press("Enter");
    expect(f.negotiations).toHaveLength(0);
    await evidence(page, "accepted-once");
    read.release();
    await expect.poll(() => f.negotiations.length).toBe(1);
    await page.waitForTimeout(400);
    expect(f.negotiations).toHaveLength(1);
    const cues = await page.evaluate(() =>
        (window as any).detailCues.map((e: any) => e.text),
    );
    expect(cues.filter((s: string) => s === "Play requested")).toHaveLength(1);
    expect(cues).not.toContain("Ready");
});
test("rc28 a failed read cues Retry once without moving heading focus; a retry owns its next cue", async ({
    page,
}) => {
    await observe(page);
    await fixture(page);
    let read = gate();
    await page.route(
        "https://jellyfin.example/Items/movie?**",
        async (route) => {
            const pending = read;
            await pending.promise;
            await route.fulfill({ status: 503, headers });
        },
    );
    await openDetail(page);
    const heading = page.locator("main h1").first();
    await heading.evaluate((e) => e.setAttribute("tabindex", "-1"));
    await heading.focus();
    read.release();
    const retry = page.getByRole("button", { name: "Retry", exact: true });
    await expect(retry).toBeVisible();
    await expect(heading).toHaveCSS("outline-style", "solid");
    expect((await heading.boundingBox())!.width).toBeLessThan(
        (await page.locator("main").boundingBox())!.width * 0.7,
    );
    await evidence(page, "failure-retry", true);
    await expect(heading).toBeFocused();
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Retry is available",
    );
    await expect(page.locator('[role="alert"]')).toHaveCount(0);
    read = gate();
    await retry.click();
    await heading.focus();
    read.release();
    await expect(retry).toBeVisible();
    await expect
        .poll(() =>
            page.evaluate(
                () =>
                    (window as any).detailCues.filter((e: any) =>
                        e.text.includes("Retry is available"),
                    ).length,
            ),
        )
        .toBe(2);
    await expect(heading).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(page.locator('[data-focus="resume:movie"]')).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator('[data-focus="play"]')).toBeFocused();
    await evidence(page, "returned-reader-state");
    await page.addScriptTag({ content: axe.source });
    expect(
        await page.evaluate(async () =>
            (window as any).axe
                .run(document, {
                    runOnly: {
                        type: "tag",
                        values: ["wcag2a", "wcag2aa", "wcag21aa"],
                    },
                })
                .then((r: any) => r.violations),
        ),
    ).toEqual([]);
});
test("rc28 natural completion projects Watched before acknowledgement and withdraws on failed report", async ({
    page,
}) => {
    await observe(page);
    await fixture(page);
    const raw = { ...movie, UserData: { ...movie.UserData, Played: false } };
    await page.route("https://jellyfin.example/Items/movie?**", (route) =>
        route.fulfill({ headers, body: JSON.stringify(raw) }),
    );
    await page.route("https://jellyfin.example/Items?**", (route) => {
        const url = new URL(route.request().url());
        return url.searchParams.get("ids") === "movie"
            ? route.fulfill({ headers, body: JSON.stringify({ Items: [raw] }) })
            : route.fallback();
    });
    const stop = gate();
    let fail = false;
    await page.route(
        "https://jellyfin.example/Sessions/Playing/Stopped",
        async (route) => {
            await stop.promise;
            await route.fulfill({ status: fail ? 503 : 204, headers });
        },
    );
    await openDetail(page);
    await page.keyboard.press("Enter");
    await playing(page);
    await page.locator("video").evaluate((v: HTMLVideoElement) => {
        v.currentTime = 39.75;
        v.playbackRate = 16;
    });
    const watched = page.locator('[data-focus="watched"]');
    await expect(watched).toBeVisible();
    await recoveryGeometry(page, "pending");
    await evidence(page, "completion-pending", true);
    await expect(watched).toHaveAttribute("aria-pressed", "true");
    await expect(watched).toHaveAccessibleName(/watched/i);
    // A real queued action exercises the longest held status beside the narrow rail.
    await page.locator('[data-focus="favorite"]').focus();
    await page.keyboard.press("Enter");
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Favorite change requested",
    );
    await recoveryGeometry(page, "pending");
    fail = true;
    stop.release();
    await expect(
        page.getByRole("button", {
            name: "Retry saving progress",
            exact: true,
        }),
    ).toBeVisible();
    await expect(watched).toHaveAttribute("aria-pressed", "false");
    await recoveryGeometry(page, "failed");
    await evidence(page, "completion-failed", true);
    await expect(watched).toHaveAccessibleName(/watched/i);
});
test("rc28 freeze during first preparation stays paused, denies hidden Play, and needs deliberate visible Play", async ({
    page,
}) => {
    const f = await fixture(page),
        prepare = gate();
    let started = false;
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        async (route) => {
            started = true;
            await prepare.promise;
            await route.fallback();
        },
    );
    await openDetail(page);
    await page.keyboard.press("Enter");
    await expect.poll(() => started).toBe(true);
    await visible(page, false);
    await page.evaluate(() => document.dispatchEvent(new Event("freeze")));
    prepare.release();
    const video = page.locator("video");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    await expect
        .poll(() => f.reports.some((r) => r.path === "/Sessions/Playing"))
        .toBe(true);
    expect(
        f.reports.find((r) => r.path === "/Sessions/Playing")!.body.IsPaused,
    ).toBe(true);
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await page.waitForTimeout(350);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(true);
    await visible(page, true);
    await page.waitForTimeout(350);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(true);
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await playing(page);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

for (const selected of [-1, 4, 5]) {
    test(`rc28 fresh server subtitle ${selected} beats stale item text default without remembering automatic selection`, async ({
        page,
    }) => {
        const f = await fixture(page);
        const source = {
            ...movie.MediaSources[0],
            DefaultSubtitleStreamIndex: 3,
            MediaStreams: [
                ...movie.MediaSources[0].MediaStreams.map((s) =>
                    s.Type === "Subtitle" ? { ...s, Language: "eng" } : s,
                ),
                {
                    Type: "Subtitle",
                    Index: 4,
                    Codec: "subrip",
                    Language: "ara",
                    IsTextSubtitleStream: true,
                    DisplayTitle: "Arabic text",
                },
                {
                    Type: "Subtitle",
                    Index: 5,
                    Codec: "pgssub",
                    Language: "eng",
                    DisplayTitle: "English image",
                },
            ],
        };
        await page.route("https://jellyfin.example/Items/movie?**", (route) =>
            route.fulfill({
                headers,
                body: JSON.stringify({ ...movie, MediaSources: [source] }),
            }),
        );
        await page.route(
            "https://jellyfin.example/Items/movie/PlaybackInfo",
            (route) => {
                const body = route.request().postDataJSON();
                f.negotiations.push(body);
                return route.fulfill({
                    headers,
                    body: JSON.stringify({
                        PlaySessionId: "rc28-server-default",
                        MediaSources: [
                            {
                                ...source,
                                DefaultSubtitleStreamIndex: selected,
                                SupportsDirectPlay: body.EnableDirectPlay,
                                TranscodingUrl: "/Videos/movie/master.m3u8",
                            },
                        ],
                    }),
                });
            },
        );
        await openDetail(page);
        await page.keyboard.press("Enter");
        await playing(page);
        await page.getByRole("button", { name: "Pause", exact: true }).click();
        expect(f.negotiations.length).toBeGreaterThan(0);
        for (const body of f.negotiations)
            expect(body).not.toHaveProperty("SubtitleStreamIndex");
        const start = f.reports.find((r) => r.path === "/Sessions/Playing")!;
        expect(start.body).not.toHaveProperty("SubtitleStreamIndex");
        await page
            .getByRole("button", { name: "Subtitles", exact: true })
            .click();
        const active = page
            .getByRole("dialog", { name: "Subtitles", exact: true })
            .getByRole("radio", { checked: true });
        await expect(active).toHaveAccessibleName(
            selected === -1 ? "Off" : selected === 4 ? /Arabic/ : /PGSSUB/,
        );
        await page.keyboard.press("Escape");
        await page
            .getByRole("button", { name: "Stop and go back", exact: true })
            .click();
    });
}

test("rc28 persisted pagehide retains the paused viewing while permanent departure sends final Stop", async ({
    page,
}) => {
    const f = await fixture(page);
    await openDetail(page);
    await page.keyboard.press("Enter");
    await playing(page);
    await page.evaluate(() =>
        window.dispatchEvent(
            new PageTransitionEvent("pagehide", { persisted: true }),
        ),
    );
    await expect
        .poll(() =>
            page.locator("video").evaluate((v: HTMLVideoElement) => v.paused),
        )
        .toBe(true);
    await page.waitForTimeout(300);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await page.evaluate(() =>
        window.dispatchEvent(
            new PageTransitionEvent("pageshow", { persisted: true }),
        ),
    );
    await page.waitForTimeout(200);
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.paused),
    ).toBe(true);
    await page.evaluate(() =>
        window.dispatchEvent(
            new PageTransitionEvent("pagehide", { persisted: false }),
        ),
    );
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
});

test("rc28 timed-out held request expires, Retry explains it, and release cannot perform the old write", async ({
    page,
}) => {
    await observe(page);
    await fixture(page);
    const read = gate();
    let writes = 0;
    await page.route(
        "https://jellyfin.example/Items/movie?**",
        async (route) => {
            await read.promise;
            await route.fulfill({ headers, body: JSON.stringify(movie) });
        },
    );
    await page.route(
        "https://jellyfin.example/UserPlayedItems/movie?**",
        (route) => {
            writes++;
            return route.fulfill({ status: 204, headers });
        },
    );
    await page.clock.install();
    await openDetail(page);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await page.clock.runFor(200);
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Watched change requested",
    );
    await page.clock.fastForward(30001);
    await page.clock.runFor(200);
    await expect(
        page.getByRole("button", { name: "Retry", exact: true }),
    ).toBeVisible();
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "The previous request expired and will not run",
    );
    expect(writes).toBe(0);
    read.release();
    await page.getByRole("button", { name: "Retry", exact: true }).click();
    await page.clock.runFor(200);
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "aria-disabled",
        "false",
    );
    expect(writes).toBe(0);
    await evidence(page, "expired-request");
});

test("rc28 advancing native media clears a stale stalled latch without another playing event", async ({
    page,
}) => {
    await fixture(page);
    await openDetail(page);
    await page.keyboard.press("Enter");
    await playing(page);
    const video = page.locator("video");
    const before = await video.evaluate((v: HTMLVideoElement) => {
        (window as any).playingAfterStall = 0;
        v.addEventListener(
            "playing",
            () => (window as any).playingAfterStall++,
        );
        v.dispatchEvent(new Event("stalled"));
        return v.currentTime;
    });
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeGreaterThan(before + 0.5);
    await expect(page.locator(".playback-feedback")).toHaveText("");
    expect(await page.evaluate(() => (window as any).playingAfterStall)).toBe(
        0,
    );
    await page
        .getByRole("button", { name: "Stop and go back", exact: true })
        .click();
});

test("rc28 full-timeline HLS seeks to resume, owns caption boundaries, and restores delayed cues", async ({
    page,
}) => {
    const f = await fixture(page);
    const source = {
        ...movie.MediaSources[0],
        DefaultSubtitleStreamIndex: 3,
        SupportsDirectPlay: false,
        TranscodingUrl: "/Videos/movie/master.m3u8",
    };
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) => {
            f.negotiations.push(route.request().postDataJSON());
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    PlaySessionId: "caption-boundary",
                    MediaSources: [source],
                }),
            });
        },
    );
    await page.route("https://jellyfin.example/**/Subtitles/**", (route) =>
        route.fulfill({
            headers,
            contentType: "text/vtt",
            body: "WEBVTT\n\n00:00.000 --> 00:00.100\nEarly\n\n00:09.000 --> 00:10.000\nExpired\n\n00:10.000 --> 00:11.000\nCurrent\n\n00:11.000 --> 00:40.000\nNext\n",
        }),
    );
    await openDetail(page);
    await page.keyboard.press("Enter");
    const video = page.locator("video");
    await expect
        .poll(() =>
            video.evaluate(
                (v: HTMLVideoElement) => v.textTracks[0]?.cues?.length ?? 0,
            ),
        )
        .toBeGreaterThan(0);
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    // PlaybackInfo's master has no start query: its decoder clock must seek to
    // the requested 10s instead of adding a fictitious UI/subtitle offset.
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
        .toBeGreaterThanOrEqual(10);
    await video.evaluate((v: HTMLVideoElement) => {
        v.pause();
        v.currentTime = 10;
    });
    const active = () =>
        video.evaluate((v: HTMLVideoElement) =>
            [...(v.textTracks[0].activeCues ?? [])].map(
                (c) => (c as VTTCue).text,
            ),
        );
    await expect.poll(active).toEqual(["Current"]);
    await video.evaluate((v: HTMLVideoElement) => {
        v.currentTime = 11;
    });
    await expect.poll(active).toEqual(["Next"]);
    await page.getByRole("button", { name: "Subtitles", exact: true }).click();
    // A negative delay moves the early cue before native zero. Its immutable
    // original must return when delay is reset, without another subtitle fetch.
    await page.getByRole("button", { name: "Earlier", exact: true }).click();
    await expect
        .poll(() =>
            video.evaluate((v: HTMLVideoElement) =>
                [...(v.textTracks[0].cues ?? [])].some(
                    (c) => (c as VTTCue).text === "Early",
                ),
            ),
        )
        .toBe(false);
    await page.getByRole("button", { name: "Later", exact: true }).click();
    await expect
        .poll(() =>
            video.evaluate((v: HTMLVideoElement) =>
                [...(v.textTracks[0].cues ?? [])].some(
                    (c) => (c as VTTCue).text === "Early" && c.endTime > 0,
                ),
            ),
        )
        .toBe(true);
    await page.getByRole("button", { name: "Earlier", exact: true }).click();
    await page.getByRole("button", { name: "Reset", exact: true }).click();
    await expect
        .poll(() =>
            video.evaluate((v: HTMLVideoElement) =>
                [...(v.textTracks[0].cues ?? [])].some(
                    (c) => (c as VTTCue).text === "Early" && c.startTime === 0,
                ),
            ),
        )
        .toBe(true);
    await page.keyboard.press("Escape");
    for (const [width, height] of [
        [1920, 1080],
        [1280, 800],
        [390, 844],
    ]) {
        await page.setViewportSize({ width, height });
        await expect
            .poll(() =>
                video.evaluate((v: HTMLVideoElement) => {
                    const cue = v.textTracks[0]?.cues?.[0] as
                        VTTCue | undefined;
                    const top = document
                        .querySelector(".transport")!
                        .getBoundingClientRect().top;
                    const player =
                        document.querySelector<HTMLElement>(".player-overlay")!;
                    const controlsTop =
                        player.style.getPropertyValue("--controls-top");
                    const gradient = getComputedStyle(
                        document.querySelector(".player-controls")!,
                    ).backgroundImage;
                    if (
                        !controlsTop ||
                        ![...gradient.matchAll(/([0-9]+(?:\.[0-9]+)?)%/g)].some(
                            (match) =>
                                Math.abs(
                                    Number(match[1]) - parseFloat(controlsTop),
                                ) < 0.01,
                        )
                    )
                        return false;
                    return (
                        cue &&
                        typeof cue.line === "number" &&
                        (cue.line / 100) * innerHeight < top &&
                        (innerWidth < 600 || cue.line >= 60)
                    );
                }),
            )
            .toBe(true);
    }
    await page
        .getByRole("button", { name: "Stop and go back", exact: true })
        .click();
});

test("rc28 stopped hidden HLS resume beyond media end reaches a paused bounded READY and completes only on Play", async ({
    page,
}) => {
    const f = await fixture(page);
    // Stale catalog runtime must not let the deep-link clamp mask native bounds.
    await page.route(
        /^https:\/\/jellyfin\.example\/Items\/movie(?:\?|$)/,
        (route) =>
            route.fulfill({
                headers,
                body: JSON.stringify({ ...movie, RunTimeTicks: 50e7 }),
            }),
    );
    const source = {
        ...movie.MediaSources[0],
        SupportsDirectPlay: false,
        TranscodingUrl: "/Videos/movie/master.m3u8",
    };
    const prepare = gate();
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        async (route) => {
            f.negotiations.push(route.request().postDataJSON());
            await prepare.promise;
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    PlaySessionId: "stale-end",
                    MediaSources: [source],
                }),
            });
        },
    );
    await signIn(page);
    await visible(page, false);
    await page.evaluate(() => {
        location.hash = "item=movie&positionMs=45000";
    });
    await expect.poll(() => f.negotiations.length).toBe(1);
    await page.evaluate(() => document.dispatchEvent(new Event("freeze")));
    prepare.release();
    const video = page.locator("video");
    await expect
        .poll(
            () =>
                f.reports.filter((r) => r.path === "/Sessions/Playing").length,
            { timeout: 20000 },
        )
        .toBe(1);
    const start = f.reports.find((r) => r.path === "/Sessions/Playing")!.body;
    expect(f.negotiations[0].StartTimeTicks).toBe(45e7);
    expect(start.IsPaused).toBe(true);
    expect(start.PositionTicks).toBe(40e7);
    expect(
        await video.evaluate((v: HTMLVideoElement) => ({
            paused: v.paused,
            atEnd: Math.abs(v.currentTime - v.duration) < 0.5,
            buffered: Array.from({ length: v.buffered.length }, (_, i) => [
                v.buffered.start(i),
                v.buffered.end(i),
            ]).some(
                ([start, end]) => v.currentTime >= start && v.currentTime < end,
            ),
            seeking: v.seeking,
            ready: v.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA,
        })),
    ).toEqual({
        paused: true,
        atEnd: true,
        buffered: true,
        seeking: false,
        ready: true,
    });
    await expect(page.locator(".playback-feedback")).toHaveText("");
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        0,
    );
    await visible(page, true);
    await expect(
        page.getByRole("button", { name: "Play", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Play", exact: true }).click();
    await expect(page.locator("video")).toHaveCount(0);
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(1);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
    expect(
        f.reports.find((r) => r.path.endsWith("/Stopped"))!.body.PositionTicks,
    ).toBe(40e7);
});

test("rc28 foreground Play before decoder READY is retained and applied when preparation settles", async ({
    page,
}) => {
    await page.addInitScript(() => {
        const state = window as any;
        state.holdDecoderReady = true;
        const descriptor = Object.getOwnPropertyDescriptor(
            HTMLMediaElement.prototype,
            "readyState",
        )!;
        const read = descriptor.get!;
        state.actualDecoderReady = () => {
            const video = document.querySelector("video");
            return video ? read.call(video) : 0;
        };
        Object.defineProperty(HTMLMediaElement.prototype, "readyState", {
            ...descriptor,
            get() {
                const actual = read.call(this);
                return state.holdDecoderReady ? Math.min(actual, 1) : actual;
            },
        });
    });
    const f = await fixture(page);
    await signIn(page);
    await visible(page, false);
    await page.evaluate(() => {
        location.hash = "item=movie&positionMs=10000";
    });
    const video = page.locator("video");
    await expect
        .poll(() => page.evaluate(() => (window as any).actualDecoderReady()), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.seeking))
        .toBe(false);
    await page.evaluate(() => document.dispatchEvent(new Event("freeze")));
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.paused))
        .toBe(true);
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(0);
    await visible(page, true);
    const before = await video.evaluate((v: HTMLVideoElement) => v.currentTime);
    await page.getByRole("button", { name: "Play", exact: true }).click();
    expect(
        f.reports.filter((r) => r.path === "/Sessions/Playing"),
    ).toHaveLength(0);
    await page.evaluate(() => {
        (window as any).holdDecoderReady = false;
        document.querySelector("video")!.dispatchEvent(new Event("canplay"));
    });
    await expect
        .poll(
            () =>
                f.reports.filter((r) => r.path === "/Sessions/Playing").length,
        )
        .toBe(1);
    expect(
        f.reports.find((r) => r.path === "/Sessions/Playing")!.body.IsPaused,
    ).toBe(false);
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), {
            timeout: 8000,
        })
        .toBeGreaterThan(before + 0.1);
    expect(await video.evaluate((v: HTMLVideoElement) => v.paused)).toBe(false);
    await page
        .getByRole("button", { name: "Stop and go back", exact: true })
        .click();
});
