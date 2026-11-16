/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test as base, expect, chromium, type Page } from "@playwright/test";
import { fixture, signIn, movie } from "./fixtures";
import { mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
const test = process.env.R3_CDP
    ? base.extend({
          browser: [
              async ({}, use) => {
                  await use(await chromium.connectOverCDP(process.env.R3_CDP!));
              },
              { scope: "worker" },
          ],
      })
    : base;
const headers = {
    "access-control-allow-origin": "*",
    "content-type": "application/json",
};
async function shot(page: Page, name: string) {
    const dir = process.env.R3_SHOTS;
    if (!dir) return;
    mkdirSync(dir, { recursive: true });
    const size = name.endsWith("phone")
        ? { width: 390, height: 844 }
        : name.endsWith("zoom200")
          ? { width: 640, height: 400 }
          : { width: 1280, height: 800 };
    await page.setViewportSize(size);
    // CDP-attached Chrome can reset emulation when the media worker attaches.
    if (process.env.R3_CDP) {
        const cdp = await page.context().newCDPSession(page);
        await cdp.send("Emulation.setDeviceMetricsOverride", {
            ...size,
            deviceScaleFactor: name.endsWith("zoom200") ? 2 : 1,
            mobile: false,
        });
        // Keep the CDP session attached: detaching clears its emulation.
    }
    await expect.poll(() => page.evaluate(() => innerWidth)).toBe(size.width);
    await page.evaluate(
        () =>
            new Promise<void>((resolve) =>
                requestAnimationFrame(() =>
                    requestAnimationFrame(() => resolve()),
                ),
            ),
    );
    await page.waitForTimeout(150);
    const path = join(dir, `${name}.jpg`);
    await page.screenshot({ path, type: "jpeg", quality: 50 });
    execFileSync("sips", ["-Z", "1000", path], { stdio: "ignore" });
    writeFileSync(
        join(dir, `${name}.geom.json`),
        JSON.stringify(
            await page.evaluate(() => ({
                viewport: [innerWidth, innerHeight],
                focus: document.activeElement?.outerHTML.slice(0, 500),
                boxes: [
                    ...document.querySelectorAll(
                        "h1,h2,.catalog-art,.catalog-letter-rail,.transport,.track-controls,.next-episode,.skip-segment,.playback-feedback",
                    ),
                ]
                    .filter((e) => e.getClientRects().length)
                    .slice(0, 45)
                    .map((e) => ({
                        selector: e.className || e.tagName,
                        text: e.textContent?.slice(0, 100),
                        rect: e.getBoundingClientRect().toJSON(),
                    })),
            })),
            null,
            2,
        ),
    );
    writeFileSync(
        join(dir, `${name}.aria.txt`),
        await page.locator("body").ariaSnapshot(),
    );
}
/** Boxes of `selectors` read in one frame, once the layout has stopped moving. */
async function settledBoxes(page: Page, selectors: string[]) {
    return page.evaluate(async (selectors) => {
        const read = () =>
            selectors.map((q) => {
                const r = document.querySelector(q)!.getBoundingClientRect();
                return { x: r.x, y: r.y, width: r.width, height: r.height };
            });
        const frame = () => new Promise((r) => requestAnimationFrame(r));
        // A resize is observed after layout in frame N and re-measured in
        // frame N + 1, so frame N alone still shows the stale layout.
        await frame();
        await frame();
        let last = JSON.stringify(read());
        for (let n = 0; n < 60; n++) {
            await frame();
            const now = JSON.stringify(read());
            if (now === last) return read();
            last = now;
        }
        throw new Error(`layout still moving after 60 frames: ${last}`);
    }, selectors);
}
async function play(page: Page) {
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page.locator('[data-focus="play"]').click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.currentTime),
        )
        .toBeGreaterThan(0.1);
    await expect(page.locator(".playback-feedback")).not.toContainText(
        "Preparing playback",
    );
    await page.locator("video").evaluate((v: HTMLVideoElement) => v.pause());
}
async function library(page: Page, box = false) {
    await fixture(page);
    const parent = {
        Id: box ? "boxset" : "library",
        Name: box ? "Shorts Collection" : "Movies",
        Type: box ? "BoxSet" : "CollectionFolder",
        CollectionType: "movies",
        IsFolder: true,
        ChildCount: 2,
    };
    await page.route("https://jellyfin.example/**", (route) => {
        const url = new URL(route.request().url()),
            p = url.pathname;
        const json = (body: unknown) =>
            route.fulfill({ headers, body: JSON.stringify(body) });
        if (
            p === "/Items/movie2" ||
            (p === "/Items" && url.searchParams.get("ids") === "movie2")
        ) {
            const next = { ...movie, Id: "movie2", Name: "Another Picture" };
            return json(p === "/Items" ? { Items: [next] } : next);
        }
        if (p === "/UserViews") return json({ Items: [parent] });
        if (p === "/Items/Latest" && box) return json([parent]);
        if (
            p === `/Items/${parent.Id}` ||
            (p === "/Items" && url.searchParams.get("ids") === parent.Id)
        )
            return json(p === "/Items" ? { Items: [parent] } : parent);
        if (p === "/Items" && url.searchParams.get("parentId") === parent.Id)
            return json({
                Items: [
                    movie,
                    { ...movie, Id: "movie2", Name: "Another Picture" },
                ],
                TotalRecordCount: 2,
            });
        if (p === "/Items/Filters")
            return json({
                Genres: ["Animation"],
                Years: [2026],
                OfficialRatings: ["PG"],
            });
        if (p === "/Persons") return json({ Items: [] });
        if (["/Genres", "/Years", "/OfficialRatings"].includes(p))
            return json({ Items: [] });
        return route.fallback();
    });
    await signIn(page);
    await page
        .locator(
            box
                ? '[data-focus="libraries:boxset"]'
                : '[data-focus="libraries:library"]',
        )
        .click();
    await expect(page.locator(".catalog-grid")).toBeVisible();
}
test("R3-1: pending password retains focus and failure selects the described password", async ({
    page,
}) => {
    await fixture(page);
    let release!: () => void,
        requests = 0;
    const gate = new Promise<void>((r) => (release = r));
    await page.route("**/Users/AuthenticateByName", async (route) => {
        requests++;
        await gate;
        await route.fulfill({ status: 401, headers, body: "" });
    });
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.keyboard.press("Enter");
    await expect.soft(page.locator(".signin button.primary")).toHaveCount(1);
    await page.getByLabel("Username").fill("Viewer");
    await page.getByLabel("Password", { exact: true }).fill("wrong");
    await page.getByRole("button", { name: "Sign in", exact: true }).focus();
    await page.keyboard.press("Enter");
    await expect.poll(() => requests).toBe(1);
    await shot(page, "signin-busy");
    await shot(page, "signin-busy-phone");
    const retained = await page
        .getByRole("button", { name: "Signing in…" })
        .evaluate((e) => e === document.activeElement);
    await page.keyboard.press("Enter");
    release();
    await expect(page.locator(".error")).toBeVisible();
    await shot(page, "signin-failed");
    await shot(page, "signin-failed-phone");
    expect.soft(retained).toBe(true);
    expect(requests).toBe(1);
    const password = page.getByLabel("Password", { exact: true });
    await expect(password).toBeFocused();
    expect(
        await password.evaluate((e: HTMLInputElement) => [
            e.selectionStart,
            e.selectionEnd,
        ]),
    ).toEqual([0, 5]);
    await expect(password).toHaveAccessibleDescription(/didn't work/);
});
test("R3-1: saving a server language keeps the focused select after failure", async ({
    page,
}) => {
    await fixture(page);
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route("**/Users/Configuration?**", async (route) => {
        await gate;
        await route.fulfill({ status: 503, headers });
    });
    await signIn(page);
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    const select = page.getByRole("combobox", {
        name: "Audio language",
        exact: true,
    });
    await select.focus();
    await select.selectOption("spa");
    const retained = await select.evaluate((e) => e === document.activeElement);
    release();
    await expect(
        page.getByRole("button", { name: "Retry preferences" }),
    ).toBeVisible();
    expect(retained).toBe(true);
    await expect(select).toBeFocused();
});
test("R3-6/7/9/10/11: aligned shelves, distinct symbols, quiet headings and playback actions first", async ({
    page,
}) => {
    await fixture(page);
    await page.route("**/UserViews?**", (route) =>
        route.fulfill({
            headers,
            body: JSON.stringify({
                Items: [
                    {
                        Id: "library",
                        Name: "Movies",
                        Type: "CollectionFolder",
                        CollectionType: "movies",
                    },
                    {
                        Id: "collections",
                        Name: "Collections",
                        Type: "CollectionFolder",
                        CollectionType: "boxsets",
                    },
                    {
                        Id: "tv",
                        Name: "Live TV",
                        Type: "CollectionFolder",
                        CollectionType: "livetv",
                    },
                ],
            }),
        }),
    );
    await signIn(page);
    await page
        .locator(".catalog-card")
        .first()
        .evaluate((e) => (e as HTMLElement).blur());
    await page.waitForTimeout(170);
    await shot(page, "home");
    const heading = await page
            .getByRole("heading", { name: "Home", exact: true })
            .boundingBox(),
        art = await page
            .locator(".catalog-cards .catalog-art")
            .first()
            .boundingBox();
    expect.soft(Math.abs(heading!.x - art!.x)).toBeLessThanOrEqual(2);
    await expect.soft(page.locator('[data-focus="home-search"]')).toBeHidden();
    const symbols = await page
        .locator(".catalog-library-art > span")
        .allTextContents();
    expect.soft(new Set(symbols).size).toBe(symbols.length);
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    await shot(page, "settings-mouse");
    const settings = page.getByRole("heading", {
        name: "Settings",
        exact: true,
    });
    expect
        .soft(Math.abs((await settings.boundingBox())!.x - heading!.x))
        .toBeLessThanOrEqual(2);
    await expect.soft(settings).toBeFocused();
    expect
        .soft(await settings.evaluate((e) => getComputedStyle(e).outlineStyle))
        .toBe("none");
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Home", exact: true })
        .click();
    await page.locator('[data-focus="resume:movie"]').click();
    await expect(page.locator('[data-focus="restart"]')).toBeVisible();
    await shot(page, "detail-resume");
    expect
        .soft(
            await page
                .locator(".catalog-detail-actions > button")
                .evaluateAll((es) =>
                    es.map((e) => (e as HTMLElement).dataset.focus),
                ),
        )
        .toEqual(["play", "restart", "watched", "favorite"]);
});
for (const [size, width, height] of [
    ["desktop", 1280, 800],
    ["zoom200", 640, 400],
    ["phone", 390, 844],
] as const) {
    test(`R3-6/8/12: ${size} library alignment, Filters and Escape on Sort`, async ({
        page,
    }) => {
        await page.setViewportSize({ width, height });
        await library(page);
        await shot(page, `movies-${size}`);
        const count = page.locator(".catalog-shelf h2").last(),
            art = page.locator(".catalog-grid .catalog-art").first();
        expect
            .soft(
                Math.abs(
                    (await count.boundingBox())!.x -
                        (await art.boundingBox())!.x,
                ),
            )
            .toBeLessThanOrEqual(2);
        await page.getByRole("button", { name: /^Filters/ }).click();
        await shot(page, `filters-${size}`);
        await page.getByRole("button", { name: "Cancel", exact: true }).click();
        await page
            .getByRole("combobox", { name: "Sort by", exact: true })
            .focus();
        await page.keyboard.press("Escape");
        await expect(page.locator(".catalog-grid")).toBeVisible();
    });
    test(`R3-2/3/12: ${size} player overlays stack, compact tools and pointer panel return`, async ({
        page,
    }) => {
        await fixture(page);
        await page.route("**/MediaSegments/movie?**", (route) =>
            route.fulfill({
                headers,
                body: JSON.stringify({
                    Items: [
                        {
                            Id: "intro",
                            Type: "Intro",
                            StartTicks: 0,
                            EndTicks: 20e7,
                        },
                    ],
                }),
            }),
        );
        await page.addInitScript(() => {
            Element.prototype.requestFullscreen = async () => {
                throw new Error("blocked");
            };
        });
        await play(page);
        await page.locator("video").focus();
        await page.keyboard.press("f");
        await expect(page.locator(".skip-segment")).toBeVisible();
        await expect(page.locator(".playback-feedback")).toContainText(
            "blocked",
        );
        await page.setViewportSize({ width, height });
        await shot(page, `player-${size}`);
        // The player re-measures its controls one animation frame after a
        // resize (ResizeObserver, then requestAnimationFrame), and both boxes
        // move together with it. Two separate boundingBox() calls can straddle
        // that frame and compare a stale Skip with a settled status (CI on
        // 6ef83573: Skip 21 px and 41 px too high). Read both from one frame,
        // once two consecutive frames agree.
        const { skip, feedback } = await settledBoxes(page, [
            ".skip-segment",
            ".playback-feedback",
        ]).then(([skip, feedback]) => ({ skip, feedback }));
        expect
            .soft(feedback.y + feedback.height + 6)
            .toBeLessThanOrEqual(skip.y);
        if (width < 700) {
            await expect
                .soft(
                    page.locator(".track-controls").getByRole("button", {
                        name: "Playback info",
                        exact: true,
                    }),
                )
                .toBeHidden();
            await expect
                .soft(page.getByRole("button", { name: "More", exact: true }))
                .toBeVisible();
        }
        await page.getByRole("button", { name: "Audio", exact: true }).click();
        await page.getByRole("button", { name: "Done", exact: true }).click();
        await expect(page.locator("video")).toBeFocused();
        await page.keyboard.press("Space");
        await expect(page.getByRole("dialog")).toBeHidden();
    });
    test(`R3-2/12: ${size} Next title folds credits and paused decoder has a Play label`, async ({
        page,
    }) => {
        await page.setViewportSize({ width, height });
        await library(page, true);
        await page.route("**/MediaSegments/movie?**", (route) =>
            route.fulfill({
                headers,
                body: JSON.stringify({
                    Items: [
                        {
                            Id: "outro",
                            Type: "Outro",
                            StartTicks: 10e7,
                            EndTicks: 40e7,
                        },
                    ],
                }),
            }),
        );
        await page.locator('[data-focus="queue-play"]').click();
        const video = page.locator("video");
        await expect
            .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime))
            .toBeGreaterThan(0.1);
        await expect(page.locator(".playback-feedback")).not.toContainText(
            "Preparing playback",
        );
        await video.evaluate((v: HTMLVideoElement) => {
            v.currentTime = 31;
        });
        await expect
            .poll(() =>
                video.evaluate(
                    (v: HTMLVideoElement) => !v.seeking && v.currentTime >= 31,
                ),
            )
            .toBe(true);
        await video.evaluate((v: HTMLVideoElement) => v.pause());
        await expect(page.locator(".next-episode")).toBeVisible();
        await shot(page, `next-title-${size}`);
        const cardBox = (await page.locator(".next-episode").boundingBox())!,
            transportBox = (await page.locator(".transport").boundingBox())!;
        expect
            .soft(cardBox.y + cardBox.height + 8)
            .toBeLessThanOrEqual(transportBox.y);
        for (const button of await page.locator(".next-episode button").all())
            await expect.soft(button).toBeInViewport({ ratio: 1 });
        await expect
            .soft(
                page.locator(".next-episode").getByRole("button", {
                    name: "Cancel next title",
                    exact: true,
                }),
            )
            .toHaveText("Cancel");
        if (width === 390) {
            const primary = (await page
                .locator(".next-episode")
                .getByRole("button", { name: "Play now", exact: true })
                .boundingBox())!;
            const skip = (await page
                .locator(".next-episode")
                .getByRole("button", { name: "Skip credits", exact: true })
                .boundingBox())!;
            const cancel = (await page
                .locator(".next-episode")
                .getByRole("button", { name: "Cancel next title", exact: true })
                .boundingBox())!;
            expect.soft(primary.y).toBeLessThan(skip.y);
            expect.soft(primary.width).toBeGreaterThan(skip.width * 1.8);
            expect.soft(Math.abs(skip.y - cancel.y)).toBeLessThanOrEqual(2);
        }
        await expect.soft(page.locator(".skip-segment")).toHaveCount(0);
        await expect
            .soft(
                page
                    .locator(".next-episode")
                    .getByRole("button", { name: "Skip credits" }),
            )
            .toBeVisible();
        await expect(
            page.getByRole("button", { name: "Play", exact: true }),
        ).toBeVisible();
    });
}
test("R3-8/12: a box set counts titles without an empty art panel", async ({
    page,
}) => {
    await library(page, true);
    await shot(page, "boxset");
    await expect
        .soft(page.getByRole("heading", { name: "2 titles", exact: true }))
        .toBeVisible();
    await expect
        .soft(page.locator(".catalog-detail-art.catalog-placeholder"))
        .toHaveCount(0);
});

test("R3-12: saved account removal takes an explicit management step rather than the next Tab", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("button", { name: "Switch user", exact: true })
        .click();
    const remove = page.getByRole("button", {
        name: "Remove Viewer",
        exact: true,
    });
    await shot(page, "accounts");
    await expect(remove).toBeHidden();
    await expect(
        page.getByRole("button", {
            name: "Remove server Evening Cinema",
            exact: true,
        }),
    ).toBeHidden();
    await page.getByRole("button", { name: "Continue as Viewer" }).focus();
    await page.keyboard.press("Tab");
    expect(
        await page.evaluate(() => document.activeElement?.textContent),
    ).not.toBe("Remove Viewer");
    await page
        .getByText("Manage saved sign-ins on Evening Cinema", { exact: true })
        .click();
    await expect(remove).toBeVisible();
    await remove.click();
    await expect(
        page.getByRole("button", { name: "Cancel", exact: true }),
    ).toBeFocused();
});

test("R3-1: pending server connection keeps focus and rejects repeated Enter", async ({
    page,
}) => {
    await fixture(page);
    let release!: () => void,
        requests = 0;
    const gate = new Promise<void>((r) => (release = r));
    await page.route("**/System/Info/Public", async (route) => {
        requests++;
        await gate;
        await route.fallback();
    });
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.getByRole("button", { name: "Connect to server →" }).focus();
    await page.keyboard.press("Enter");
    await expect.poll(() => requests).toBe(1);
    const kept = await page
        .getByRole("button", { name: "Connecting…" })
        .evaluate((e) => e === document.activeElement);
    await page.keyboard.press("Enter");
    release();
    await expect(
        page.getByRole("heading", { name: "Welcome back." }),
    ).toBeVisible();
    expect(kept).toBe(true);
    expect(requests).toBe(1);
});

test("R3-9/12: headings follow input modality and delayed search leaves focus in the query", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await expect(page.locator(".catalog-card").first()).toBeVisible();
    const nav = page.getByRole("navigation", { name: "Main navigation" });
    await nav.getByRole("button", { name: "Settings", exact: true }).focus();
    await page.keyboard.press("Enter");
    const heading = page.getByRole("heading", {
        name: "Settings",
        exact: true,
    });
    await expect(heading).toBeFocused();
    expect(
        await heading.evaluate((e) => getComputedStyle(e).outlineStyle),
    ).toBe("solid");
    await nav.getByRole("button", { name: "Search", exact: true }).click();
    const field = page.locator("#catalog-search-field");
    await field.fill("picture");
    await expect(page.getByRole("heading", { name: /Movies/ })).toBeVisible();
    await expect(field).toBeFocused();
});

test("R3-3: More supports keyboard volume and focuses each nested tool before returning to its keyboard opener", async ({
    page,
}) => {
    await page.setViewportSize({ width: 640, height: 400 });
    await fixture(page);
    await play(page);
    const more = page.getByRole("button", { name: "More", exact: true });
    await more.focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog", {
        name: "More controls",
        exact: true,
    });
    await expect(dialog).toBeVisible();
    const volume = dialog.getByRole("slider", { name: "Volume", exact: true });
    await volume.focus();
    await page.keyboard.press("ArrowLeft");
    await expect(volume).toHaveValue("0.9");
    await expect(volume).toBeFocused();
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.volume),
    ).toBeCloseTo(0.9);
    const speed = dialog.getByRole("button", {
        name: "Speed, 1×",
        exact: true,
    });
    await speed.focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("radio", { name: /1× \(normal\)/ }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(more).toBeFocused();
    await page.keyboard.press("Enter");
    await page
        .getByRole("dialog", { name: "More controls" })
        .getByRole("button", { name: "Playback info", exact: true })
        .focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("region", { name: "Playback info details" }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(more).toBeFocused();
});

test("R3-1: Quick Connect stays focused while password sign-in is pending", async ({
    page,
}) => {
    await fixture(page);
    let release!: () => void;
    const held = new Promise<void>((r) => (release = r));
    await page.route("**/Users/AuthenticateByName", async (route) => {
        await held;
        await route.fulfill({ status: 401, headers });
    });
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.getByRole("button", { name: "Connect to server →" }).click();
    await page.getByLabel("Username", { exact: true }).fill("Viewer");
    await page.getByLabel("Password", { exact: true }).fill("wrong");
    const quick = page.getByRole("button", {
        name: "Use Quick Connect",
        exact: true,
    });
    await quick.focus();
    await page
        .getByLabel("Password", { exact: true })
        .evaluate((e) => (e as HTMLInputElement).form!.requestSubmit());
    await expect(quick).toHaveAttribute("aria-disabled", "true");
    await expect(quick).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByText("123456", { exact: true })).toHaveCount(0);
    release();
    await expect(page.getByLabel("Password", { exact: true })).toBeFocused();
});

test("R3-1: an empty library's focused Refresh survives its pending request", async ({
    page,
}) => {
    await fixture(page);
    let release!: () => void,
        held: Promise<void> | undefined,
        reads = 0;
    await page.route("https://jellyfin.example/**", async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (
            [
                "/UserViews",
                "/UserItems/Resume",
                "/Shows/NextUp",
                "/Items/Latest",
            ].includes(path)
        ) {
            if (path === "/UserViews") {
                reads++;
                if (held) await held;
            }
            return route.fulfill({
                headers,
                body: JSON.stringify(
                    path === "/Items/Latest" ? [] : { Items: [] },
                ),
            });
        }
        return route.fallback();
    });
    await signIn(page);
    const refresh = page.getByRole("button", { name: "Refresh", exact: true });
    await expect(refresh).toBeVisible();
    held = new Promise<void>((r) => (release = r));
    const before = reads;
    await refresh.focus();
    await page.keyboard.press("Enter");
    await expect(refresh).toHaveAttribute("aria-disabled", "true");
    await expect(refresh).toBeFocused();
    await page.keyboard.press("Enter");
    release();
    await expect(refresh).toHaveAttribute("aria-disabled", "false");
    await expect(refresh).toBeFocused();
    expect(reads).toBe(before + 1);
});

for (const [width, height] of [
    [390, 844],
    [640, 400],
]) {
    test(`R3 follow-up: ${width} action groups fit without orphan buttons or hidden filter fields`, async ({
        page,
    }) => {
        await page.setViewportSize({ width, height });
        await library(page);
        if (width === 390) {
            const sort = (await page
                .locator('[data-focus="library-sort"]')
                .boundingBox())!;
            const filters = (await page
                .locator('[data-focus="library-filters"]')
                .boundingBox())!;
            expect
                .soft(
                    Math.abs(
                        sort.y +
                            sort.height / 2 -
                            filters.y -
                            filters.height / 2,
                    ),
                )
                .toBeLessThanOrEqual(2);
        }
        await page
            .getByRole("button", { name: "Filters", exact: true })
            .click();
        const dialog = page.getByRole("dialog", {
            name: "Filters",
            exact: true,
        });
        const clear = (await dialog
            .getByRole("button", { name: "Clear all", exact: true })
            .boundingBox())!;
        const cancel = (await dialog
            .getByRole("button", { name: "Cancel", exact: true })
            .boundingBox())!;
        expect.soft(Math.abs(clear.y - cancel.y)).toBeLessThanOrEqual(2);
        if (width === 390) {
            const apply = (await dialog
                .getByRole("button", { name: "Apply", exact: true })
                .boundingBox())!;
            expect.soft(apply.width).toBeGreaterThan(clear.width * 1.8);
            expect.soft(apply.y).toBeLessThan(clear.y);
        } else {
            // At 200% zoom, title, both toggle labels and the first two complete
            // label/field pairs fit above the pinned footer, not just Genre.
            await expect
                .soft(dialog.getByLabel("Genre", { exact: true }))
                .toBeInViewport({ ratio: 1 });
            await expect
                .soft(dialog.getByLabel("Year", { exact: true }))
                .toBeInViewport({ ratio: 1 });
            const year = (await dialog
                .getByLabel("Year", { exact: true })
                .boundingBox())!;
            const footer = (await dialog
                .locator(".library-filter-actions")
                .boundingBox())!;
            expect.soft(year.y + year.height).toBeLessThanOrEqual(footer.y);
        }
    });
}
test("R3 follow-up: pointer More has quiet focus and labelled rows, keyboard keeps a visible focus ring", async ({
    page,
}) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await fixture(page);
    await page.route("**/Items/movie?**", (route) =>
        route.fulfill({
            headers,
            body: JSON.stringify({
                ...movie,
                Chapters: [
                    { Name: "Opening", StartPositionTicks: 0 },
                    { Name: "Closing", StartPositionTicks: 20e7 },
                ],
            }),
        }),
    );
    await play(page);
    const more = page.getByRole("button", { name: "More", exact: true });
    await more.click();
    const dialog = page.getByRole("dialog", { name: "More controls" });
    const chapters = dialog.getByRole("button", {
        name: "Chapters",
        exact: true,
    });
    await expect(chapters).toBeFocused();
    expect(
        await chapters.evaluate((e) => getComputedStyle(e).outlineStyle),
    ).toBe("none");
    const full = dialog.getByRole("button", {
        name: "Full screen",
        exact: true,
    });
    await expect(full).toContainText("Full screen");
    expect((await full.boundingBox())!.width).toBeGreaterThan(
        (await dialog.boundingBox())!.width * 0.7,
    );
    await shot(page, "more-phone");
    const labels = await dialog
        .locator(".more-tools .tool-label")
        .evaluateAll((es) =>
            es.map((e) => ({
                x: e.getBoundingClientRect().x,
                size: getComputedStyle(e).fontSize,
                weight: getComputedStyle(e).fontWeight,
            })),
        );
    expect(
        Math.max(...labels.map((e) => e.x)) -
            Math.min(...labels.map((e) => e.x)),
    ).toBeLessThanOrEqual(1);
    expect(new Set(labels.map((e) => e.size)).size).toBe(1);
    expect(new Set(labels.map((e) => e.weight)).size).toBe(1);
    await page.keyboard.press("Escape");
    await more.focus();
    await page.keyboard.press("Enter");
    await expect(chapters).toBeFocused();
    expect(
        await chapters.evaluate((e) => getComputedStyle(e).outlineStyle),
    ).toBe("solid");
    await shot(page, "more-keyboard-phone");
    await page.keyboard.press("Enter");
    await expect(
        page
            .getByRole("dialog", { name: "Chapters", exact: true })
            .getByRole("button", { name: /Opening/ }),
    ).toBeFocused();
});

test("R3-12: a remembered server can still be forgotten after its last saved sign-in is removed", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("button", { name: "Switch user", exact: true })
        .click();
    const manage = page.locator(".account-management");
    await manage.locator("summary").click();
    await page
        .getByRole("button", { name: "Remove Viewer", exact: true })
        .click();
    await page
        .getByRole("dialog")
        .getByRole("button", { name: "Remove", exact: true })
        .click();
    await expect(
        page.getByRole("button", { name: "Continue as Viewer" }),
    ).toHaveCount(0);
    await expect(manage).toBeVisible();
    if (!(await manage.evaluate((e: HTMLDetailsElement) => e.open)))
        await manage.locator("summary").click();
    await page
        .getByRole("button", {
            name: "Remove server Evening Cinema",
            exact: true,
        })
        .click();
    await page
        .getByRole("dialog")
        .getByRole("button", { name: "Remove", exact: true })
        .click();
    await expect
        .poll(() =>
            page.evaluate(
                () =>
                    JSON.parse(localStorage.getItem("pictureshow.accounts.v1")!)
                        .servers.length,
            ),
        )
        .toBe(0);
});
