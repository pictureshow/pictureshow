/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { fixture, signIn } from "./fixtures";
// TOUCH-02: a navigation press opens that root's top page; the lit item is the
// root the page on screen was opened under; browser Back still returns.
const nav = (page: Page, name: string) =>
    page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name, exact: true });
const lit = (page: Page) =>
    page
        .getByRole("navigation", { name: "Main navigation" })
        .locator('[aria-current="page"]');
const heading = (page: Page, name: string) =>
    page.getByRole("heading", { level: 1, name, exact: true });
// The shared fixture has no library item; serve the Films library itself.
async function withLibrary(page: Page) {
    await fixture(page);
    const library = {
        Id: "library",
        Name: "Films",
        Type: "CollectionFolder",
        CollectionType: "movies",
    };
    await page.route("https://jellyfin.example/Items**", (route) => {
        const u = new URL(route.request().url());
        const body =
            u.pathname === "/Items/library"
                ? library
                : u.pathname === "/Items" &&
                    u.searchParams.get("ids") === "library"
                  ? { Items: [library] }
                  : undefined;
        if (!body) return route.fallback();
        return route.fulfill({
            contentType: "application/json",
            headers: { "access-control-allow-origin": "*" },
            body: JSON.stringify(body),
        });
    });
}
async function openFilmsThenMovie(page: Page) {
    await page.locator('[data-focus="libraries:library"]').click();
    await expect(heading(page, "Films")).toBeVisible();
    await page.locator('[data-focus="grid:library:movie"]').click();
    await expect(heading(page, "The Test Picture")).toBeVisible();
}
test("a navigation press opens the root's top page from anywhere, and Back returns", async ({
    page,
}) => {
    await withLibrary(page);
    await signIn(page);
    await expect(lit(page)).toHaveText("Home");
    await nav(page, "Libraries").click();
    await expect(heading(page, "Libraries")).toBeVisible();
    await openFilmsThenMovie(page);
    await expect(lit(page)).toHaveText("Libraries");
    // Leaving and coming back does not restore the inner page.
    await nav(page, "Home").click();
    await expect(heading(page, "Home")).toBeVisible();
    await nav(page, "Libraries").click();
    await expect(heading(page, "Libraries")).toBeVisible();
    await expect(lit(page)).toHaveText("Libraries");
    // Reselecting the lit root from an inner page returns to its top page.
    await openFilmsThenMovie(page);
    await nav(page, "Libraries").click();
    await expect(heading(page, "Libraries")).toBeVisible();
    await expect(page.getByRole("button", { name: "← Back" })).toHaveCount(0);
    await page.goBack();
    await expect(heading(page, "The Test Picture")).toBeVisible();
    await expect(lit(page)).toHaveText("Libraries");
    await page.goBack();
    await expect(heading(page, "Films")).toBeVisible();
    // Pressing the root already at its top page adds no history entry.
    await nav(page, "Libraries").click();
    await expect(heading(page, "Libraries")).toBeVisible();
    const depth = await page.evaluate(() => history.length);
    await nav(page, "Libraries").click();
    await expect(heading(page, "Libraries")).toBeVisible();
    expect(await page.evaluate(() => history.length)).toBe(depth);
});
test("a page opened from Home stays under Home, and pressing Home returns to Home", async ({
    page,
}) => {
    await withLibrary(page);
    await signIn(page);
    await openFilmsThenMovie(page);
    await expect(lit(page)).toHaveText("Home");
    await nav(page, "Home").click();
    await expect(heading(page, "Home")).toBeVisible();
    await expect(lit(page)).toHaveText("Home");
    // The sidebar's Settings button opens Settings, and Settings is lit there.
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    await expect(heading(page, "Settings")).toBeVisible();
    await expect(lit(page)).toHaveText("Settings");
    await page.reload();
    await expect(heading(page, "Settings")).toBeVisible();
    await expect(lit(page)).toHaveText("Settings");
    await page.getByRole("button", { name: "← Back" }).click();
    await expect(heading(page, "Home")).toBeVisible();
    await expect(lit(page)).toHaveText("Home");
});
test("one Main navigation landmark is exposed at every width", async ({
    page,
}) => {
    await withLibrary(page);
    await signIn(page);
    for (const width of [390, 800, 1280, 1600]) {
        await page.setViewportSize({ width, height: 800 });
        await expect(
            page.getByRole("navigation", { name: "Main navigation" }),
        ).toHaveCount(1);
    }
});
// W-7: after forward navigation focus is on the new page, never the background.
for (const zoom of [1, 2])
    test(`opening a library or a title by keyboard puts focus on the new page (zoom ${zoom})`, async ({
        browser,
    }) => {
        const context = await browser.newContext(
            zoom === 2
                ? {
                      viewport: { width: 640, height: 400 },
                      deviceScaleFactor: 2,
                  }
                : {},
        );
        const page = await context.newPage();
        await withLibrary(page);
        await signIn(page);
        const focused = () =>
            page.evaluate(() => {
                const a = document.activeElement as HTMLElement | null;
                return !a || a === document.body
                    ? "BODY"
                    : (a.dataset.focus ?? a.tagName.toLowerCase());
            });
        await page.locator('[data-focus="libraries:library"]').focus();
        await page.keyboard.press("Enter");
        await expect(heading(page, "Films")).toBeVisible();
        // Poster first: the library opens on its first title.
        await expect.poll(focused).toBe("grid:library:movie");
        // Tab order continues from there, not from where the pressed card used to
        // be: the letter rail (BROWSE-05, one tab stop) comes just before the
        // posters, and the sort controls before it.
        await page.keyboard.press("Shift+Tab");
        await expect(page.locator(":focus")).toHaveAccessibleName(/^Jump to/);
        await page.locator('[data-focus="grid:library:movie"]').focus();
        await page.keyboard.press("Enter");
        await expect(heading(page, "The Test Picture")).toBeVisible();
        await expect.poll(focused).toBe("play");
        // At 200% the loaded Play sits lower than the loading one; it is kept on screen.
        await expect(page.locator(":focus")).toBeInViewport();
        await page.keyboard.press("Escape");
        await expect(heading(page, "Films")).toBeVisible();
        await expect.poll(focused).toBe("grid:library:movie");
        // The skip link lands on <main>, and Tab goes on into the page.
        await page.locator("a.skip").focus();
        await page.keyboard.press("Enter");
        await expect.poll(focused).toBe("main");
        await page.keyboard.press("Tab");
        await expect(
            page.getByRole("button", { name: "← Back" }),
        ).toBeFocused();
        await context.close();
    });
// Down from a newly opened library reaches its posters; it never changes the sort.
// Passes once the library page stops autofocusing the Sort select (catalog.tsx).
test("Down from a newly opened library lands on a poster", async ({ page }) => {
    await withLibrary(page);
    await signIn(page);
    await page.locator('[data-focus="libraries:library"]').focus();
    await page.keyboard.press("Enter");
    await expect(heading(page, "Films")).toBeVisible();
    await expect(
        page.locator('[data-focus="grid:library:movie"]'),
    ).toBeVisible();
    await expect
        .poll(() =>
            page.evaluate(() => document.activeElement !== document.body),
        )
        .toBe(true);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator(":focus")).toHaveAttribute(
        "data-focus",
        /^grid:library:/,
    );
    await expect(page.getByLabel("Sort by", { exact: true })).toHaveValue(
        "SortName",
    );
});
