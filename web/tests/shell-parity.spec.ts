/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect } from "@playwright/test";
import axe from "axe-core";
import { fixture, signIn, movie } from "./fixtures";
async function scan(page: any) {
    await page.addScriptTag({ content: axe.source });
    const violations = await page.evaluate(
        async () =>
            await (window as any).axe.run(document, {
                runOnly: {
                    type: "tag",
                    values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"],
                },
            }),
    );
    expect(violations.violations).toEqual([]);
}
test("saved accounts isolate device identities, Cancel restores opener, and local removal works offline", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("button", { name: "Switch user", exact: true })
        .click();
    await page
        .getByRole("button", { name: "Sign in another user on Evening Cinema" })
        .click();
    await page.route(
        "https://jellyfin.example/Users/AuthenticateByName",
        (route) =>
            route.fulfill({
                contentType: "application/json",
                headers: { "access-control-allow-origin": "*" },
                body: JSON.stringify({
                    User: { Id: "guest", Name: "Guest" },
                    ServerId: "server",
                    AccessToken: "guest-token",
                }),
            }),
    );
    await page.getByLabel("Username").fill("Guest");
    await page.getByLabel("Password", { exact: true }).fill("password");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(
        page.getByRole("heading", { name: "Guest", exact: true }),
    ).toBeVisible();
    const state = await page.evaluate(() =>
        JSON.parse(localStorage.getItem("pictureshow.accounts.v1")!),
    );
    expect(state.accounts).toHaveLength(2);
    expect(new Set(state.accounts.map((a: any) => a.deviceId)).size).toBe(2);
    await page
        .getByRole("button", { name: "Switch user", exact: true })
        .click();
    const remove = page.getByRole("button", {
        name: "Remove Viewer",
        exact: true,
    });
    await remove.click();
    await expect(
        page.getByRole("button", { name: "Cancel", exact: true }),
    ).toBeFocused();
    await page.keyboard.press("ArrowRight");
    await expect(
        page.getByRole("button", { name: "Remove", exact: true }),
    ).toBeFocused();
    await page.keyboard.press("ArrowLeft");
    await expect(
        page.getByRole("button", { name: "Cancel", exact: true }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(remove).toBeFocused();
    await page.route("https://jellyfin.example/Sessions/Logout", (route) =>
        route.abort("failed"),
    );
    await remove.click();
    await page.getByRole("button", { name: "Remove", exact: true }).click();
    await expect(remove).toHaveCount(0);
    const saved = await page.evaluate(() =>
        JSON.parse(localStorage.getItem("pictureshow.accounts.v1")!),
    );
    expect(saved.accounts.map((a: any) => a.user.Id)).toEqual(["guest"]);
    await scan(page);
});
test("settings preserve unknown server fields and rejected desired writes can retry", async ({
    page,
}) => {
    await fixture(page);
    let config: any = {
            AudioLanguagePreference: "eng",
            SubtitleMode: "Default",
            FuturePreference: { retained: true },
        },
        writes: any[] = [],
        fail = true;
    await page.route("https://jellyfin.example/Users/viewer", (route) =>
        route.fulfill({
            contentType: "application/json",
            headers: { "access-control-allow-origin": "*" },
            body: JSON.stringify({ Configuration: config }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Users/Configuration?**",
        (route) => {
            const b = route.request().postDataJSON();
            writes.push(b);
            if (fail) {
                fail = false;
                return route.fulfill({
                    status: 503,
                    headers: { "access-control-allow-origin": "*" },
                    body: "Try again",
                });
            }
            config = b;
            return route.fulfill({
                status: 204,
                headers: { "access-control-allow-origin": "*" },
            });
        },
    );
    await signIn(page);
    await page.locator('[data-focus="home-settings"]').click();
    await expect(
        page.getByRole("combobox", { name: "Credits", exact: true }),
    ).toHaveValue("Ask");
    await page
        .getByRole("combobox", { name: "Max streaming bitrate", exact: true })
        .selectOption("10");
    await page
        .getByRole("combobox", { name: "Subtitle size", exact: true })
        .selectOption("Large");
    await page
        .getByRole("combobox", { name: "Intro", exact: true })
        .selectOption("Auto");
    await page
        .getByRole("combobox", { name: "Audio language", exact: true })
        .selectOption("spa");
    await expect(
        page.getByRole("button", { name: "Retry preferences" }),
    ).toBeVisible();
    await expect(
        page.getByRole("combobox", { name: "Audio language", exact: true }),
    ).toHaveValue("eng");
    await page.getByRole("button", { name: "Retry preferences" }).click();
    await expect(
        page.getByRole("combobox", { name: "Audio language", exact: true }),
    ).toHaveValue("spa");
    expect(writes[1]).toEqual({
        ...writes[0],
        FuturePreference: { retained: true },
    });
    await scan(page);
    await page.reload();
    // Reload restores the safe Settings route; its desktop navigation is the rail.
    await expect(
        page.getByRole("heading", { name: "Playback settings", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByRole("combobox", {
            name: "Max streaming bitrate",
            exact: true,
        }),
    ).toHaveValue("10");
    await expect(
        page.getByRole("combobox", { name: "Subtitle size", exact: true }),
    ).toHaveValue("Large");
});
test("About, all bundled license text, device JSON and unavailable support are reachable and readable", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page.locator('[data-focus="home-settings"]').click();
    await page
        .getByRole("button", { name: "About Pictureshow", exact: true })
        .click();
    await expect(
        page.getByText("Web 0.1.0 · MPL-2.0 · Legitimate LLC"),
    ).toBeVisible();
    await page
        .getByRole("button", { name: "Open-source licenses", exact: true })
        .click();
    await expect(page.getByText("4 bundled dependencies")).toBeVisible();
    const hls = page.locator('[data-focus="license:3"]');
    await hls.click();
    await expect(
        page.getByRole("heading", { name: "hls.js", exact: true }),
    ).toBeVisible();
    await expect(page.locator(".license-text").last()).toBeVisible();
    await scan(page);
    await page.keyboard.press("Escape");
    await expect(hls).toBeFocused();
    await page.keyboard.press("Escape");
    await page.keyboard.press("Escape");
    await page
        .getByRole("button", { name: "Troubleshooting · This browser" })
        .click();
    await page
        .getByRole("button", { name: "View device profile JSON" })
        .click();
    await expect(page.locator("pre").first()).toContainText("Pictureshow Web");
    await page.keyboard.press("Escape");
    await page
        .getByRole("button", { name: "Support Pictureshow", exact: true })
        .click();
    await expect(
        page.getByRole("heading", {
            name: "Purchases are unavailable in this browser",
        }),
    ).toBeFocused();
    await expect(
        page.getByRole("button", { name: /Become a supporter/ }),
    ).toHaveCount(0);
    await scan(page);
});
test("phone bottom tabs, touch password visibility and persistence survive resize and reload", async ({
    page,
}) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await fixture(page);
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.keyboard.press("Enter");
    await page.getByLabel("Password", { exact: true }).fill("private");
    await page
        .getByRole("button", { name: "Show password", exact: true })
        .click();
    await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute(
        "type",
        "text",
    );
    await page
        .getByRole("button", { name: "Hide password", exact: true })
        .click();
    await page.getByLabel("Username").fill("Viewer");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Libraries", exact: true })
        .click();
    await expect(
        page.getByRole("heading", { name: "Libraries", exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 844, height: 390 });
    await expect(
        page.getByRole("heading", { name: "Libraries", exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await scan(page);
    await page.reload();
    await expect(
        page.getByRole("heading", { name: "Libraries", exact: true }),
    ).toBeVisible();
});

test("returning detail stays visible and queues the offered favorite until final reporting finishes", async ({
    page,
}) => {
    const f = await fixture(page);
    let release!: () => void;
    const held = new Promise<void>((r) => (release = r));
    let favorites = 0,
        acknowledged = false;
    await page.route("https://jellyfin.example/Items?**", (route) =>
        route.fulfill({
            contentType: "application/json",
            headers: { "access-control-allow-origin": "*" },
            body: JSON.stringify({
                Items: [
                    {
                        ...movie,
                        UserData: {
                            PlaybackPositionTicks: acknowledged ? 20e7 : 10e7,
                        },
                    },
                ],
            }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Sessions/Playing/Stopped",
        async (route) => {
            await held;
            acknowledged = true;
            await route.fulfill({
                status: 204,
                headers: { "access-control-allow-origin": "*" },
            });
        },
    );
    await page.route(
        "https://jellyfin.example/UserFavoriteItems/movie?**",
        async (route) => {
            favorites++;
            await route.fulfill({
                contentType: "application/json",
                headers: { "access-control-allow-origin": "*" },
                body: JSON.stringify({
                    IsFavorite: true,
                    PlaybackPositionTicks: 10e7,
                }),
            });
        },
    );
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page.getByRole("button", { name: "Resume from 0:10" }).click();
    await expect
        .poll(() => f.reports.some((r) => r.path === "/Sessions/Playing"))
        .toBe(true);
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await expect(
        page.getByRole("heading", { name: "The Test Picture", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByText(/^(Item loaded\. )?Saving progress…$/),
    ).toBeVisible();
    const favorite = page.locator('[data-focus="favorite"]');
    await favorite.focus();
    await page.keyboard.press("Enter");
    expect(favorites).toBe(0);
    release();
    await expect.poll(() => favorites).toBe(1);
    await expect(
        page.getByRole("button", { name: "Resume from 0:20", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByText(/^(Item loaded\. )?Saving progress…$/),
    ).toHaveCount(0);
    await expect(page.locator("video")).toHaveCount(0);
});

test("failed final report retries its exact viewing snapshot before fresh actions release", async ({
    page,
}) => {
    await fixture(page);
    let attempts: any[] = [];
    await page.route(
        "https://jellyfin.example/Sessions/Playing/Stopped",
        (route) => {
            attempts.push(route.request().postDataJSON());
            return route.fulfill({
                status: attempts.length === 1 ? 503 : 204,
                headers: { "access-control-allow-origin": "*" },
                body: attempts.length === 1 ? "Unavailable" : undefined,
            });
        },
    );
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page.getByRole("button", { name: "Resume from 0:10" }).click();
    await expect
        .poll(() =>
            page
                .locator("video")
                .evaluate((v: HTMLVideoElement) => v.currentTime),
        )
        .toBeGreaterThan(10);
    await page.getByRole("button", { name: "Stop and go back" }).click();
    const retry = page.getByRole("button", {
        name: "Retry saving progress",
        exact: true,
    });
    await expect(retry).toBeVisible();
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "aria-disabled",
        "true",
    );
    await retry.click();
    await expect.poll(() => attempts.length).toBe(2);
    expect(attempts[1]).toEqual(attempts[0]);
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "aria-disabled",
        "false",
    );
});

test("native browser Back restores catalog focus and unwinds player controls before stopping", async ({
    page,
}) => {
    const f = await fixture(page);
    await signIn(page);
    const card = page.locator('[data-focus="resume:movie"]');
    await card.click();
    await expect(
        page.getByRole("heading", { name: "The Test Picture", exact: true }),
    ).toBeVisible();
    await page.goBack();
    await expect(card).toBeFocused();
    await page.goForward();
    await expect(
        page.getByRole("button", { name: "Resume from 0:10" }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Resume from 0:10" }).click();
    await expect
        .poll(() => f.reports.some((r) => r.path === "/Sessions/Playing"))
        .toBe(true);
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.goBack();
    await expect(page.getByRole("dialog")).toBeHidden();
    await expect(
        page.getByRole("button", { name: "Audio", exact: true }),
    ).toBeFocused();
    await page.goBack();
    await expect(
        page.getByRole("button", { name: "Stop and go back" }),
    ).toBeHidden();
    await expect(page.locator("video")).toBeVisible();
    await page.goBack();
    await expect(
        page.getByRole("button", { name: /Resume from/ }),
    ).toBeVisible();
    await expect
        .poll(() => f.reports.filter((r) => r.path.endsWith("/Stopped")).length)
        .toBe(1);
});

test("external playback entry resolves current-account detail and bounded position, while malformed links recover", async ({
    page,
}) => {
    const observed = await fixture(page);
    await signIn(page);
    await page.goto("/#item=movie&positionMs=20000");
    await expect(page.locator("video")).toBeVisible();
    await expect.poll(() => observed.negotiations.length).toBeGreaterThan(0);
    expect(observed.negotiations.at(-1).StartTimeTicks).toBe(20e7);
    await page
        .getByRole("button", { name: "Stop and go back", exact: true })
        .click();
    await expect(
        page.getByRole("heading", { name: movie.Name, exact: true }),
    ).toBeVisible();
    await page.goto("/#item=..%2Fforeign&positionMs=-1");
    await expect(page.getByRole("alert")).toContainText(
        "playback link is invalid",
    );
    await expect(page.locator("video")).toHaveCount(0);
});

test("a transient saved-account read failure keeps the sign-in and retries hydration", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page.addInitScript(() => {
        const read = Storage.prototype.getItem;
        let first = true;
        Storage.prototype.getItem = function (key: string) {
            if (key === "pictureshow.accounts.v1" && first) {
                first = false;
                throw Error("Temporary storage unavailable");
            }
            return read.call(this, key);
        };
    });
    await page.reload();
    await expect(
        page.getByRole("button", { name: "Retry saved sign-ins" }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Retry saved sign-ins" }).click();
    await expect(
        page.getByRole("heading", { name: "Viewer", exact: true }),
    ).toBeVisible();
    expect(
        await page.evaluate(
            () =>
                JSON.parse(localStorage.getItem("pictureshow.accounts.v1")!)
                    .accounts.length,
        ),
    ).toBe(1);
});

test("an upgraded session sign-in commits persistently before a fresh browser tab reopens it", async ({
    page,
    context,
}) => {
    await fixture(page);
    await signIn(page);
    const oldIdentity = await page.evaluate(() => {
        const old = JSON.parse(
            localStorage.getItem("pictureshow.accounts.v1")!,
        );
        sessionStorage.setItem(
            "pictureshow.account",
            JSON.stringify(old.accounts[0]),
        );
        localStorage.removeItem("pictureshow.accounts.v1");
        return old.accounts[0].deviceId;
    });
    await page.reload();
    await expect(
        page.getByRole("heading", { name: "Viewer", exact: true }),
    ).toBeVisible();
    expect(
        await page.evaluate(() =>
            sessionStorage.getItem("pictureshow.account"),
        ),
    ).toBe(null);
    const reopened = await context.newPage();
    await fixture(reopened);
    await reopened.goto("/");
    await expect(
        reopened.getByRole("heading", { name: "Viewer", exact: true }),
    ).toBeVisible();
    expect(
        await reopened.evaluate(
            () =>
                JSON.parse(localStorage.getItem("pictureshow.accounts.v1")!)
                    .accounts[0].deviceId,
        ),
    ).toBe(oldIdentity);
    await reopened.close();
});
