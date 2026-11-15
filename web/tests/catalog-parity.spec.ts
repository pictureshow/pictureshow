/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import axe from "axe-core";
import { mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
async function captureRecovery(page: Page, state: string) {
    const directory = process.env.WEB_F2_EVIDENCE;
    if (!directory) return;
    mkdirSync(directory, { recursive: true });
    const previous = page.viewportSize();
    for (const [size, width, height] of [
        ["tv", 1920, 1080],
        ["desktop", 1280, 800],
        ["phone", 390, 844],
    ] as const) {
        await page.setViewportSize({ width, height });
        const target = join(directory, `detail-${state}-${size}.jpg`);
        await page.evaluate(async () => {
            await Promise.all(
                document
                    .getAnimations()
                    .map((animation) => animation.finished.catch(() => {})),
            );
            await new Promise<void>((resolve) =>
                requestAnimationFrame(() =>
                    requestAnimationFrame(() => resolve()),
                ),
            );
        });
        await page.screenshot({ path: target, type: "jpeg", quality: 50 });
        execFileSync("sips", ["-Z", "800", target], { stdio: "ignore" });
        writeFileSync(
            target.replace(/\.jpg$/, ".json"),
            JSON.stringify(
                await page.evaluate(() => {
                    const element = document.activeElement as HTMLElement,
                        style = getComputedStyle(element);
                    return {
                        label: element.textContent?.trim(),
                        focus: element.dataset.focus,
                        color: style.color,
                        background: style.backgroundColor,
                        opacity: style.opacity,
                        viewport: [innerWidth, innerHeight],
                    };
                }),
                null,
                2,
            ),
        );
    }
    if (previous) await page.setViewportSize(previous);
}
const server = {
    id: "catalog-server",
    name: "Catalog Cinema",
    url: "https://catalog.example",
    version: "12.0.0",
};
const viewer = {
    server,
    user: { Id: "catalog-viewer", Name: "Catalog Viewer" },
    accessToken: "fixture-viewer-only",
    deviceId: "fixture-catalog-device",
};
const movie = (id = "movie", name = "A picture") => ({
    Id: id,
    Name: name,
    Type: "Movie",
    RunTimeTicks: 90e9,
    ProductionYear: 2026,
    Genres: ["Drama"],
    OfficialRating: "PG",
    CommunityRating: 8.1,
    Overview: "A story told in pictures.",
    UserData: { Played: false, IsFavorite: false, PlaybackPositionTicks: 0 },
});
async function start(
    page: Page,
    respond: (
        url: URL,
        method: string,
    ) =>
        | Promise<{ body?: unknown; status?: number }>
        | { body?: unknown; status?: number } = () => ({}),
) {
    await page.addInitScript((value) => {
        localStorage.setItem(
            "pictureshow.accounts.v1",
            JSON.stringify({
                version: 1,
                accounts: [value],
                servers: [value.server],
                current: "catalog-server:catalog-viewer",
            }),
        );
    }, viewer);
    await page.route("https://catalog.example/**", async (route) => {
        const url = new URL(route.request().url()),
            method = route.request().method();
        expect(route.request().headers().authorization).toContain(
            "fixture-viewer-only",
        );
        const custom = await respond(url, method);
        const body =
            custom.body ??
            (url.pathname === "/Users/Me"
                ? viewer.user
                : url.pathname === "/UserViews"
                  ? {
                        Items: [
                            {
                                Id: "library",
                                Name: "Films",
                                Type: "CollectionFolder",
                                CollectionType: "movies",
                            },
                        ],
                    }
                  : url.pathname === "/Items/Latest"
                    ? [movie()]
                    : { Items: [] });
        await route.fulfill({
            status: custom.status ?? 200,
            contentType: "application/json",
            body: JSON.stringify(body),
        });
    });
    await page.goto("/");
    await expect(
        page.getByRole("heading", { name: "Home", exact: true, level: 1 }),
    ).toBeVisible();
    // The signed-in user is named once, in the header, not again above "Home".
    await expect(page.locator("header .connection")).toContainText(
        "Catalog Viewer",
    );
    // On a wide screen the account line above "Home" stays hidden.
    await expect(page.locator(".catalog-home-account")).toBeHidden();
    await expect(
        page.locator('[data-focus="libraries:library"]'),
    ).toBeVisible();
}
async function openMovie(page: Page) {
    await page.locator('[data-focus="latest:library:movie"]').click();
    await expect(
        page.getByRole("heading", { name: "A picture", exact: true }),
    ).toBeVisible();
}
async function scan(page: Page) {
    await page.addScriptTag({ content: axe.source });
    const violations = await page.evaluate(async () =>
        (window as unknown as { axe: typeof axe }).axe
            .run(document, {
                runOnly: {
                    type: "tag",
                    values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"],
                },
            })
            .then((result) => result.violations),
    );
    expect(violations).toEqual([]);
}

test("catalog partial rows survive, library labels are friendly, paging deduplicates, and sort resets results", async ({
    page,
}) => {
    let failedResume = true;
    const browseQueries: URLSearchParams[] = [];
    await start(page, async (url) => {
        if (url.pathname === "/UserItems/Resume" && failedResume)
            return { status: 500 };
        if (url.pathname === "/Items/library")
            return {
                body: {
                    Id: "library",
                    Name: "Films",
                    Type: "CollectionFolder",
                    CollectionType: "movies",
                    ChildCount: 61,
                },
            };
        if (url.pathname === "/Items" && url.searchParams.has("ids"))
            return {
                body: {
                    Items: [
                        {
                            Id: "library",
                            Name: "Films",
                            Type: "CollectionFolder",
                            CollectionType: "movies",
                            ChildCount: 61,
                        },
                    ],
                },
            };
        if (url.pathname === "/Items" && url.searchParams.has("parentId")) {
            browseQueries.push(url.searchParams);
            if (url.searchParams.get("sortBy") === "DateCreated")
                return {
                    body: {
                        Items: [movie("newest", "Newest picture")],
                        TotalRecordCount: 1,
                    },
                };
            const second = Number(url.searchParams.get("startIndex")) >= 60;
            return {
                body: {
                    Items: second
                        ? [
                              movie("i59", "Picture 59"),
                              movie("i60", "Picture 60"),
                          ]
                        : Array.from({ length: 60 }, (_, i) =>
                              movie(`i${i}`, `Picture ${i}`),
                          ),
                    TotalRecordCount: 61,
                },
            };
        }
        return {};
    });
    await expect(page.getByRole("alert")).toContainText(
        "Some rows could not load",
    );
    await expect(
        page.locator('[data-focus="libraries:library"]'),
    ).toHaveAccessibleName("Films");
    await expect(
        page.getByText("CollectionFolder", { exact: true }),
    ).toHaveCount(0);
    failedResume = false;
    await page.getByRole("button", { name: "Retry", exact: true }).click();
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.locator('[data-focus="libraries:library"]').click();
    await expect(
        page.getByRole("heading", { name: "Films", exact: true }),
    ).toBeVisible();
    // One title for the page, not a second "Browse library" heading.
    await expect(
        page.getByRole("heading", { name: "Browse library" }),
    ).toHaveCount(0);
    // A library lands on its first poster, never on Sort by: Down on a
    // closed select changes its value in Chrome on Windows and Linux.
    await expect(
        page.getByLabel("Sort by", { exact: true }),
    ).not.toHaveAttribute("data-autofocus");
    await expect(
        page.locator('[data-focus^="grid:library:"]').first(),
    ).toHaveAttribute("data-autofocus", "true");
    // Page-end scrolling exposes the IntersectionObserver sentinel. It may
    // finish and remove Load more before a browser action can target that node.
    await expect
        .poll(() => page.locator('[data-focus^="grid:library:"]').count())
        .toBeGreaterThanOrEqual(60);
    await page.evaluate(() =>
        window.scrollTo(0, document.documentElement.scrollHeight),
    );
    await expect(page.locator('[data-focus^="grid:library:"]')).toHaveCount(61);
    await expect(page.locator('[data-focus="grid:library:i59"]')).toHaveCount(
        1,
    );
    await page
        .getByLabel("Sort by", { exact: true })
        .selectOption("DateCreated");
    await expect(
        page.locator('[data-focus="grid:library:newest"]'),
    ).toBeVisible();
    await expect(page.locator('[data-focus^="grid:library:"]')).toHaveCount(1);
    expect(browseQueries.at(-1)?.get("startIndex")).toBe("0");
    await page.getByRole("button", { name: /Sort ascending/ }).click();
    await expect
        .poll(() => browseQueries.at(-1)?.get("sortOrder"))
        .toBe("Descending");
    await scan(page);
});

test("desired-state favorite retry never inverts intent and authoritative metadata remains accessible", async ({
    page,
}) => {
    let favorite = false,
        firstWrite = true;
    const writes: string[] = [];
    await start(page, (url, method) => {
        if (url.pathname === "/Items/movie") return { body: movie() };
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return {
                body: {
                    Items: [
                        {
                            ...movie(),
                            UserData: {
                                ...movie().UserData,
                                IsFavorite: favorite,
                            },
                        },
                    ],
                },
            };
        if (url.pathname === "/UserFavoriteItems/movie") {
            writes.push(method);
            if (firstWrite) {
                firstWrite = false;
                return { status: 500 };
            }
            favorite = method === "POST";
            return {
                body: {
                    IsFavorite: favorite,
                    Played: false,
                    PlaybackPositionTicks: 0,
                },
            };
        }
        return {};
    });
    await openMovie(page);
    await page
        .getByRole("button", { name: "Add favorite", exact: true })
        .click();
    await expect(
        page.getByRole("button", { name: "Retry saving", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByRole("button", { name: "Add favorite", exact: true }),
    ).toHaveAttribute("aria-pressed", "false");
    await page
        .getByRole("button", { name: "Retry saving", exact: true })
        .click();
    await expect(
        page.getByRole("button", { name: "Remove favorite", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(writes).toEqual(["POST", "POST"]);
    await expect(
        page.getByText("2026 · PG · 2 hr 30 min · ★ 8.1", { exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Drama", { exact: true })).toBeVisible();
    await scan(page);
});

test("detail Retry retains its target while loading and hands focus to fresh primary", async ({
    page,
}) => {
    let first = true,
        release: (() => void) | undefined;
    const held = new Promise<void>((resolve) => {
        release = resolve;
    });
    await start(page, async (url) => {
        if (url.pathname === "/Items/movie") {
            if (first) {
                first = false;
                return { status: 500 };
            }
            await held;
            return { body: movie() };
        }
        if (url.pathname === "/Items" && url.searchParams.has("ids"))
            return { body: { Items: [movie()] } };
        return {};
    });
    await page.locator('[data-focus="latest:library:movie"]').click();
    const retry = page.getByRole("button", { name: "Retry", exact: true });
    await expect(retry).toBeVisible();
    // A failure cue preserves the reader's current control. Retry is reached
    // deliberately rather than stealing focus when the request fails.
    await retry.focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("button", { name: "Retrying…", exact: true }),
    ).toBeFocused();
    release!();
    await expect(page.locator('[data-focus="play"]')).toBeFocused();
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "data-state",
        "ready",
    );
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "aria-disabled",
        "false",
    );
});

test("search bounds and generation guard preserve ordered partial results, account history, and person opener", async ({
    page,
}) => {
    const searched: string[] = [];
    await start(page, async (url) => {
        if (url.searchParams.has("searchTerm")) {
            const term = url.searchParams.get("searchTerm")!;
            searched.push(term);
            if (term === "old")
                await new Promise((resolve) => setTimeout(resolve, 700));
            if (url.searchParams.get("includeItemTypes") === "Episode")
                return { status: 500 };
            if (url.pathname === "/Persons")
                return {
                    body: {
                        Items: [
                            {
                                Id: "person",
                                Name: `${term} Person`,
                                Type: "Person",
                                Overview: "A filmmaker.",
                            },
                        ],
                    },
                };
            return {
                body: {
                    Items:
                        url.searchParams.get("includeItemTypes") === "Movie"
                            ? [movie(`${term}-film`, `${term} Film`)]
                            : [],
                },
            };
        }
        if (url.pathname === "/Items/person")
            return {
                body: {
                    Id: "person",
                    Name: "new Person",
                    Type: "Person",
                    Overview: "A filmmaker.",
                },
            };
        if (url.pathname === "/Items" && url.searchParams.has("ids"))
            return {
                body: {
                    Items: [
                        { Id: "person", Name: "new Person", Type: "Person" },
                    ],
                },
            };
        if (url.searchParams.has("personIds"))
            return {
                body: {
                    Items: [movie("credit", "A linked picture")],
                    TotalRecordCount: 1,
                },
            };
        return {};
    });
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Search", exact: true })
        .click();
    const field = page.getByRole("searchbox");
    await field.fill("a");
    await page.waitForTimeout(350);
    expect(searched).toEqual([]);
    await field.fill("old");
    await expect.poll(() => searched.includes("old")).toBe(true);
    await field.fill("new");
    await expect(page.getByRole("button", { name: /new Film/ })).toBeVisible();
    await page.waitForTimeout(750);
    await expect(page.getByRole("button", { name: /old Film/ })).toHaveCount(0);
    const headings = await page
        .locator(".catalog-search .catalog-shelf h2")
        .allTextContents();
    expect(headings).toEqual(["Movies", "People"]);
    await expect(page.getByRole("alert")).toContainText(
        "Some search groups could not load",
    );
    const person = page.getByRole("button", {
        name: "new Person Person",
        exact: true,
    });
    await person.focus();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "new Person", exact: true }),
    ).toBeVisible();
    await expect(
        page.getByRole("button", { name: /A linked picture/ }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(person).toBeFocused();
    await expect(field).toHaveValue("new");
    await expect(
        page.getByRole("button", { name: "new", exact: true }),
    ).toBeVisible();
    await scan(page);
});

test("empty containers suppress vacuous watched state but a positive count wins over zero projection", async ({
    page,
}) => {
    await start(page, (url) =>
        url.pathname === "/UserViews"
            ? {
                  body: {
                      Items: [
                          {
                              Id: "library",
                              Name: "Empty library",
                              Type: "CollectionFolder",
                              ChildCount: 0,
                              UserData: { Played: true },
                          },
                          {
                              Id: "nonempty",
                              Name: "Nonempty library",
                              Type: "CollectionFolder",
                              ChildCount: 0,
                              RecursiveItemCount: 2,
                              UserData: { Played: true },
                          },
                      ],
                  },
              }
            : {},
    );
    await expect(
        page.locator('[data-focus="libraries:library"]'),
    ).toHaveAccessibleName("Empty library");
    await expect(
        page.locator('[data-focus="libraries:nonempty"]'),
    ).toHaveAccessibleName("Nonempty library, Watched");
});

test("fresh unresolved detail accepts one pending Play and negotiates only after authoritative release", async ({
    page,
}) => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
        release = resolve;
    });
    let negotiations = 0;
    await start(page, async (url) => {
        if (url.pathname === "/Items/movie") {
            await held;
            return { body: movie() };
        }
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return { body: { Items: [movie()] } };
        if (url.pathname === "/Items/movie/PlaybackInfo") {
            negotiations++;
            return {
                body: { PlaySessionId: "pending-play", MediaSources: [] },
            };
        }
        return {};
    });
    await page.locator('[data-focus="latest:library:movie"]').click();
    const pending = page.locator('[data-focus="play"]');
    await expect(pending).toBeFocused();
    await expect(pending).toHaveAttribute("data-state", "pending");
    await expect(pending).toHaveAttribute("aria-disabled", "false");
    await page.keyboard.press("Enter");
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Play requested",
    );
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowLeft");
    await expect(pending).toHaveAccessibleName("Play already requested");
    await expect(page.locator("[data-detail-feedback]")).toHaveText("");
    await page.keyboard.press("Enter");
    await pending.dispatchEvent("keydown", { key: "MediaPlay", bubbles: true });
    await expect(page.locator("[data-detail-feedback]")).toHaveText("");
    expect(negotiations).toBe(0);
    release();
    await expect.poll(() => negotiations).toBe(1);
    await page.waitForTimeout(300);
    expect(negotiations).toBe(1);
});

test("rc28 cold detail starts plain, then announces Ready only after the same focused held label", async ({
    page,
}) => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
        release = resolve;
    });
    await start(page, async (url) => {
        if (url.pathname === "/Items/movie") {
            await held;
            return { body: movie() };
        }
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return { body: { Items: [movie()] } };
        return {};
    });
    await page.locator('[data-focus="latest:library:movie"]').click();
    const play = page.locator('[data-focus="play"]');
    await expect(play).toBeFocused();
    await expect(play).toHaveAccessibleName("Play");
    await expect(play).toHaveAttribute("data-state", "pending");
    await expect(play).toHaveAttribute("aria-disabled", "false");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator('[data-focus="restart"]')).toBeFocused();
    await page.keyboard.press("ArrowRight");
    const watched = page.locator('[data-focus="watched"]');
    await expect(watched).toBeFocused();
    await expect(watched).toHaveAccessibleName("Watched");
    await expect(watched).not.toHaveAttribute("aria-pressed");
    await expect(watched).toHaveAccessibleName("Watched when ready");
    await captureRecovery(page, "held");
    release();
    await expect(watched).toBeFocused();
    await expect(watched).toHaveAttribute("data-state", "ready");
    await expect(watched).toHaveAttribute("aria-disabled", "false");
    await expect(page.locator("[data-detail-feedback]")).toHaveText(
        "Ready. Mark watched",
    );
    await captureRecovery(page, "ready");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("[data-detail-feedback]")).toHaveText("");
    await page.keyboard.press("ArrowLeft");
    await expect(watched).toBeFocused();
    await expect(page.locator("[data-detail-feedback]")).toHaveText("");
});

test("rc26 Retry qualifies the predecessor reason while publishing current loading and one new failure episode", async ({
    page,
}) => {
    let reads = 0,
        release!: () => void;
    const held = new Promise<void>((resolve) => {
        release = resolve;
    });
    await start(page, async (url) => {
        if (url.pathname === "/Items/movie") {
            reads++;
            if (reads > 1) await held;
            return { status: 503 };
        }
        return {};
    });
    await page.locator('[data-focus="latest:library:movie"]').click();
    const retry = page.getByRole("button", { name: "Retry", exact: true });
    await expect(retry).toBeVisible();
    await expect(page.locator("[data-detail-feedback]")).toContainText("503");
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Retry is available",
    );
    await expect(page.getByRole("alert")).toHaveCount(0);
    await captureRecovery(page, "failed");
    await retry.click();
    await expect(
        page.getByRole("button", { name: "Retrying…", exact: true }),
    ).toBeFocused();
    await expect(page.locator(".catalog-error")).toContainText(
        "Loading item. Please wait.",
    );
    await expect(
        page.locator(".catalog-error .catalog-previous-reason"),
    ).toContainText("Previous attempt:");
    await expect(
        page.locator(".catalog-error .catalog-previous-reason"),
    ).toContainText("503");
    await captureRecovery(page, "retrying");
    await page.keyboard.press("Enter");
    expect(reads).toBe(2);
    release();
    await expect(retry).toBeFocused();
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect(page.locator("[data-detail-feedback]")).toContainText("503");
    await expect(
        page.getByRole("button", { name: "Watched", exact: true }),
    ).not.toHaveAttribute("aria-pressed");
    await scan(page);
});

test("rc26 Home return uses retained rows without repeating unchanged reads", async ({
    page,
}) => {
    let homeReads = 0;
    await start(page, (url) => {
        if (
            [
                "/UserViews",
                "/UserItems/Resume",
                "/Shows/NextUp",
                "/Items/Latest",
            ].includes(url.pathname)
        )
            homeReads++;
        if (url.pathname === "/Items/movie") return { body: movie() };
        if (url.pathname === "/Items" && url.searchParams.has("ids"))
            return { body: { Items: [movie()] } };
        return {};
    });
    const initial = homeReads;
    await openMovie(page);
    await page.keyboard.press("Escape");
    await expect(
        page.locator('[data-focus="latest:library:movie"]'),
    ).toBeFocused();
    await page.waitForTimeout(150);
    expect(homeReads).toBe(initial);
});

test("rc26 failed Series read keeps unknown season and toggle state neutral with one idle reason", async ({
    page,
}) => {
    const show = {
        Id: "show",
        Name: "A series",
        Type: "Series",
        UserData: { Played: true, IsFavorite: true },
    };
    const season = {
        Id: "s1",
        Name: "Season 1",
        Type: "Season",
        IndexNumber: 1,
    };
    const episode = {
        ...movie("e1", "First episode"),
        Type: "Episode",
        SeasonId: "s1",
        ParentIndexNumber: 1,
    };
    let fail = false;
    await start(page, (url) => {
        if (url.pathname === "/Items/Latest") return { body: [show] };
        if (url.pathname === "/Items/show")
            return fail ? { status: 503 } : { body: show };
        if (url.pathname === "/Items" && url.searchParams.get("ids") === "show")
            return { body: { Items: [show] } };
        if (url.pathname === "/Shows/show/Seasons")
            return { body: { Items: [season] } };
        if (url.pathname === "/Shows/show/Episodes")
            return { body: { Items: [episode] } };
        return {};
    });
    const opener = page.locator('[data-focus="latest:library:show"]');
    await opener.click();
    const selectedSeason = page.getByRole("button", {
        name: "Show episodes from Season 1",
        exact: true,
    });
    await expect(selectedSeason).toHaveAttribute("aria-pressed", "true");
    // W-16: the season chips are the only season picker.
    await expect(
        page.getByRole("heading", { name: "Browse seasons" }),
    ).toHaveCount(0);
    await page.keyboard.press("Escape");
    fail = true;
    await opener.click();
    await expect(
        page.getByRole("button", { name: "Retry", exact: true }),
    ).toBeVisible();
    await expect(selectedSeason).not.toHaveAttribute("aria-pressed");
    await expect(selectedSeason).toHaveAttribute("aria-disabled", "true");
    for (const label of ["Watched", "Favorite"]) {
        const action = page.getByRole("button", { name: label, exact: true });
        await expect(action).toHaveAttribute("aria-disabled", "true");
        await expect(action).toHaveAttribute("data-state", "unavailable");
        await expect(action).not.toHaveAttribute("aria-pressed");
    }
    await expect(page.getByRole("alert")).toHaveCount(0);
    await expect(page.locator("[data-detail-feedback]")).toContainText("503");
});

test("CAT-09/11 pending Watched is accepting (not aria-disabled) and runs after release; read-failure Watched refuses", async ({
    page,
}) => {
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
        release = resolve;
    });
    let phase: "held" | "fail" | "ok" = "held",
        played = false,
        writes = 0;
    await start(page, async (url, method) => {
        if (url.pathname === "/Items/movie") {
            if (phase === "held") await held;
            if (phase === "fail") return { status: 503 };
            const item = movie();
            return {
                body: {
                    ...item,
                    UserData: { ...item.UserData, Played: played },
                },
            };
        }
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        ) {
            const item = movie();
            return {
                body: {
                    Items: [
                        {
                            ...item,
                            UserData: { ...item.UserData, Played: played },
                        },
                    ],
                },
            };
        }
        if (url.pathname.endsWith("/UserPlayedItems/movie")) {
            writes++;
            played = method === "POST";
            return { body: { Played: played } };
        }
        return {};
    });
    const press = async (target: ReturnType<Page["locator"]>) => {
        const box = await target.boundingBox();
        expect(box).not.toBeNull();
        await page.mouse.click(
            box!.x + box!.width / 2,
            box!.y + box!.height / 2,
        );
    };
    const opener = page.locator('[data-focus="latest:library:movie"]');
    await opener.click();
    const watched = page.locator('[data-focus="watched"]');
    await expect(watched).toHaveAttribute("data-state", "pending");
    await expect(watched).toHaveAttribute("aria-disabled", "false");
    await expect(watched).toHaveAttribute(
        "aria-description",
        "Runs when this item is ready",
    );
    await press(watched);
    await expect(watched).toContainText("already requested");
    await expect(watched).toHaveAccessibleName("Watched already requested");
    expect(writes).toBe(0);
    phase = "ok";
    release();
    await expect.poll(() => writes).toBe(1);
    expect(played).toBe(true);
    await expect(watched).toHaveAttribute("data-state", "ready");
    await expect(watched).toHaveAttribute("aria-pressed", "true");
    await expect(watched).not.toHaveAttribute("aria-description");

    await page.keyboard.press("Escape");
    phase = "fail";
    await opener.click();
    const retry = page.getByRole("button", { name: "Retry", exact: true });
    await expect(retry).toBeVisible();
    await expect(watched).toHaveAttribute("data-state", "unavailable");
    await expect(watched).toHaveAttribute("aria-disabled", "true");
    await expect(watched).not.toHaveAttribute("aria-description");
    await press(watched);
    await expect(watched).not.toContainText("already requested");
    await expect(page.locator("[data-detail-feedback]")).not.toContainText(
        "Watched change requested",
    );
    phase = "ok";
    await retry.click();
    await expect(watched).toHaveAttribute("data-state", "ready");
    await page.waitForTimeout(300);
    expect(writes).toBe(1);
    expect(played).toBe(true);
});

test("presses during Saving… are queued, not dropped: a different action runs next and a repeat deduplicates", async ({
    page,
}) => {
    // Android queueReadyMutation: returning controls keep focus while a mutation is busy,
    // and a fresh press joins the same ordered queue (CAT-07, CAT-09).
    let favorite = false,
        played = false,
        holdFavorite: (() => void) | undefined;
    const writes: string[] = [];
    const item = () => ({
        ...movie(),
        UserData: { ...movie().UserData, IsFavorite: favorite, Played: played },
    });
    await start(page, async (url, method) => {
        if (url.pathname === "/Items/movie") return { body: item() };
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return { body: { Items: [item()] } };
        if (url.pathname === "/UserFavoriteItems/movie") {
            writes.push(`favorite ${method}`);
            await new Promise<void>((resolve) => (holdFavorite = resolve));
            favorite = method === "POST";
            return { body: { IsFavorite: favorite, Played: played } };
        }
        if (url.pathname === "/UserPlayedItems/movie") {
            writes.push(`watched ${method}`);
            played = method === "POST";
            return { body: { IsFavorite: favorite, Played: played } };
        }
        return {};
    });
    await openMovie(page);
    const favoriteButton = page.locator('[data-focus="favorite"]');
    const watchedButton = page.locator('[data-focus="watched"]');
    await favoriteButton.click();
    await expect(favoriteButton).toHaveText("Saving favorite…");
    // The same button again while saving deduplicates; Mark watched is a distinct request.
    await favoriteButton.click();
    await watchedButton.click();
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Watched change requested",
    );
    expect(writes).toEqual(["favorite POST"]);
    holdFavorite!();
    await expect(
        page.getByRole("button", { name: "Remove favorite", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(
        page.getByRole("button", { name: "Mark unwatched", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await page.waitForTimeout(500);
    // One favorite write (the repeat joined it) and the watched press ran after it.
    expect(writes).toEqual(["favorite POST", "watched POST"]);
    // The "requested" status does not outlive the request.
    await expect(page.locator(".catalog-detail-status")).toHaveCount(0);
});

/** A movie whose favorite POST waits for release, then fails or succeeds. */
async function heldFavorite(page: Page, outcome: "fail" | "ok") {
    const state = {
        favorite: false,
        played: false,
        writes: [] as string[],
        release: undefined as (() => void) | undefined,
    };
    const item = () => ({
        ...movie(),
        UserData: {
            ...movie().UserData,
            IsFavorite: state.favorite,
            Played: state.played,
        },
    });
    await start(page, async (url, method) => {
        if (url.pathname === "/Items/movie") return { body: item() };
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return { body: { Items: [item()] } };
        if (url.pathname === "/UserFavoriteItems/movie") {
            state.writes.push(`favorite ${method}`);
            await new Promise<void>((resolve) => (state.release = resolve));
            if (outcome === "fail") return { status: 500, body: {} };
            state.favorite = method === "POST";
            return {
                body: { IsFavorite: state.favorite, Played: state.played },
            };
        }
        if (url.pathname === "/UserPlayedItems/movie") {
            state.writes.push(`watched ${method}`);
            state.played = method === "POST";
            return {
                body: { IsFavorite: state.favorite, Played: state.played },
            };
        }
        return {};
    });
    await openMovie(page);
    return state;
}

test("a failed save still runs the change queued behind it, and a queued Play says it did not start", async ({
    page,
}) => {
    // Android's consumer runs a queued mutation after a write error (CAT-09: a failed
    // first mutation must not cancel an independent one); a queued Play does not start.
    const state = await heldFavorite(page, "fail");
    const favoriteButton = page.locator('[data-focus="favorite"]');
    await favoriteButton.click();
    await expect(favoriteButton).toHaveText("Saving favorite…");
    await page.locator('[data-focus="watched"]').click();
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Watched change requested",
    );
    state.release!();
    await expect(
        page.getByRole("button", { name: "Mark unwatched", exact: true }),
    ).toHaveAttribute("aria-pressed", "true", { timeout: 10000 });
    expect(state.writes).toEqual(["favorite POST", "watched POST"]);
    // The favorite stays as the server has it: not favorite.
    await expect(
        page.getByRole("button", { name: "Add favorite", exact: true }),
    ).toHaveAttribute("aria-pressed", "false");
    await expect(page.locator(".catalog-detail-status")).toHaveCount(0);
});

test("a failed save with Play queued behind it reports that Play did not start, and offers Retry saving", async ({
    page,
}) => {
    const state = await heldFavorite(page, "fail");
    const favoriteButton = page.locator('[data-focus="favorite"]');
    await favoriteButton.click();
    await expect(favoriteButton).toHaveText("Saving favorite…");
    await page.locator('[data-focus="play"]').click();
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Play requested",
    );
    state.release!();
    const failure = page.getByText(
        /Change failed\..*Play did not start\. Choose it again\./,
    );
    await expect(failure).toBeVisible({ timeout: 10000 });
    await expect(
        page.getByRole("button", { name: "Retry saving" }),
    ).toBeVisible();
    // Still on the detail page: the player never opened over an unsaved change.
    await expect(
        page.getByRole("heading", { name: "A picture", exact: true }),
    ).toBeVisible();
    await expect(page.locator("video")).toHaveCount(0);
    expect(state.writes).toEqual(["favorite POST"]);
});

test("leaving the item while a save is in flight drops its queued presses", async ({
    page,
}) => {
    const state = await heldFavorite(page, "ok");
    const favoriteButton = page.locator('[data-focus="favorite"]');
    await favoriteButton.click();
    await expect(favoriteButton).toHaveText("Saving favorite…");
    await page.locator('[data-focus="watched"]').click();
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Watched change requested",
    );
    await page.getByRole("button", { name: "← Back" }).click();
    await expect(
        page.getByRole("heading", { name: "A picture", exact: true }),
    ).toHaveCount(0);
    state.release!();
    await page.waitForTimeout(1500);
    // The in-flight write completes on the server; the queued press never runs.
    expect(state.writes).toEqual(["favorite POST"]);
});

test("W-5/W-15: library tiles are wide with no repeated type, and Live TV says where to watch it", async ({
    page,
}) => {
    await start(page, (url) => {
        if (url.pathname === "/UserViews")
            return {
                body: {
                    Items: [
                        {
                            Id: "library",
                            Name: "Films",
                            Type: "CollectionFolder",
                            CollectionType: "movies",
                        },
                        {
                            Id: "live",
                            Name: "Live TV",
                            Type: "UserView",
                            CollectionType: "livetv",
                        },
                    ],
                },
            };
        if (url.pathname === "/Items/live")
            return {
                body: {
                    Id: "live",
                    Name: "Live TV",
                    Type: "UserView",
                    CollectionType: "livetv",
                },
            };
        if (url.pathname === "/Items" && url.searchParams.get("ids") === "live")
            return {
                body: {
                    Items: [
                        {
                            Id: "live",
                            Name: "Live TV",
                            Type: "UserView",
                            CollectionType: "livetv",
                        },
                    ],
                },
            };
        return {};
    });
    const tile = page.locator('[data-focus="libraries:library"]');
    await expect(tile).toBeVisible();
    await expect(tile.locator(".catalog-muted")).toHaveCount(0);
    const art = await tile
        .locator(".catalog-art")
        .evaluate((e) => e.getBoundingClientRect());
    expect(art.width / art.height).toBeCloseTo(16 / 9, 1);
    await page.locator('[data-focus="libraries:live"]').click();
    await expect(
        page.getByRole("heading", { name: "Live TV is coming to the web app" }),
    ).toBeVisible();
    await expect(page.getByText("This library is empty.")).toHaveCount(0);
    await expect(page.getByText(/Pictureshow app for Android/)).toBeVisible();
    // One way back: the shell's Back, not a second button on the page.
    await expect(page.getByRole("button", { name: /Back/ })).toHaveCount(1);
    await page.getByRole("button", { name: /Back/ }).click();
    await expect(tile).toBeVisible();
});

test("W-20: an unstarted film offers Play without Play from start", async ({
    page,
}) => {
    const film = movie("film", "Unstarted");
    await start(page, (url) => {
        if (url.pathname === "/Items/Latest") return { body: [film] };
        if (url.pathname === "/Items/film") return { body: film };
        if (url.pathname === "/Items" && url.searchParams.get("ids") === "film")
            return { body: { Items: [film] } };
        return {};
    });
    const opener = page.locator('[data-focus="latest:library:film"]');
    await opener.click();
    await expect(
        page.getByRole("heading", { name: "Unstarted" }),
    ).toBeVisible();
    await expect(page.locator('[data-focus="play"]')).toBeEnabled();
    await expect(page.locator('[data-focus="restart"]')).toHaveCount(0);
    // A second visit renders from the cached detail; it still offers no restart.
    await page.keyboard.press("Escape");
    await opener.click();
    await expect(
        page.getByRole("heading", { name: "Unstarted" }),
    ).toBeVisible();
    await expect(page.locator('[data-focus="watched"]')).toHaveText(
        "Mark watched",
    );
    await expect(page.locator('[data-focus="restart"]')).toHaveCount(0);
});

test("CAT-14a: two search results that would look the same name their libraries; a unique one does not", async ({
    page,
}) => {
    const ancestors: string[] = [];
    await start(page, (url) => {
        if (url.searchParams.has("searchTerm"))
            return {
                body: {
                    Items:
                        url.searchParams.get("includeItemTypes") === "Movie"
                            ? [
                                  movie("copy-1", "Sintel"),
                                  movie("copy-2", "Sintel"),
                                  movie("other", "Sintel Returns"),
                              ]
                            : [],
                },
            };
        const match = url.pathname.match(/^\/Items\/([^/]+)\/Ancestors$/);
        if (match) {
            ancestors.push(match[1]);
            return {
                body: [
                    {
                        Id: "lib-" + match[1],
                        Name: match[1] === "copy-1" ? "Films" : "Open Movies",
                        Type: "CollectionFolder",
                    },
                    {
                        Id: "root",
                        Name: "Media Folders",
                        Type: "UserRootFolder",
                    },
                ],
            };
        }
        return {};
    });
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Search", exact: true })
        .click();
    await page.getByRole("searchbox").fill("sintel");
    const shelf = page.locator('.catalog-search [data-shelf="search:MOVIES"]');
    await expect(
        shelf.getByRole("button", { name: /^Sintel 2026 · Films(,|$)/ }),
    ).toBeVisible();
    await expect(
        shelf.getByRole("button", { name: /^Sintel 2026 · Open Movies(,|$)/ }),
    ).toBeVisible();
    await expect(
        shelf.getByRole("button", { name: /^Sintel Returns 2026(,|$)/ }),
    ).toBeVisible();
    await expect(shelf).toContainText("2026 · Open Movies");
    expect(ancestors.sort()).toEqual(["copy-1", "copy-2"]);
});

const films = {
    Id: "library",
    Name: "Films",
    Type: "CollectionFolder",
    CollectionType: "movies",
    ChildCount: 70,
};
test("BROWSE-01/04/05/06: Movies lists titles by type, filters intersect and clear one at a time, the rail jumps, Play queues", async ({
    page,
}) => {
    const queries: URLSearchParams[] = [];
    // 70 titles: Apple 0–64 then Zebra 65–69, so Z sits past the first page.
    const all = Array.from({ length: 70 }, (_, i) => ({
        ...movie(`i${i}`, `${i < 65 ? "Apple" : "Zebra"} ${i}`),
        SortName: `${i < 65 ? "apple" : "zebra"} ${String(i).padStart(2, "0")}`,
    }));
    await start(page, (url) => {
        if (url.pathname === "/Items/library") return { body: films };
        if (url.pathname === "/Items" && url.searchParams.has("ids"))
            return { body: { Items: [films] } };
        if (url.pathname === "/Items/Filters")
            return {
                body: {
                    Genres: ["Comedy", "Drama"],
                    Years: [2026, 2019],
                    OfficialRatings: ["PG"],
                },
            };
        if (url.pathname === "/Persons")
            return { body: { Items: [{ Id: "ada", Name: "Ada Lens" }] } };
        if (url.pathname === "/Items" && url.searchParams.has("parentId")) {
            queries.push(url.searchParams);
            const start = Number(url.searchParams.get("startIndex") ?? 0),
                limit = Number(url.searchParams.get("limit") ?? 60);
            return {
                body: {
                    Items: all.slice(start, start + limit),
                    TotalRecordCount: all.length,
                },
            };
        }
        return {};
    });
    await page.locator('[data-focus="libraries:library"]').click();
    await expect(
        page.getByRole("heading", { name: "70 titles", exact: true }),
    ).toBeVisible();
    // BROWSE-04: titles by item type through every folder level.
    const pages = () =>
        queries.filter(
            (q) => q.get("fields") !== "SortName" && q.get("limit") !== "300",
        );
    expect(pages()[0].get("recursive")).toBe("true");
    expect(pages()[0].get("includeItemTypes")).toBe("Movie");

    // BROWSE-01: two facets intersect; the Filters button counts them.
    await page.getByRole("button", { name: "Filters", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Filters" });
    await expect(dialog.getByLabel("Genre")).toBeEnabled();
    await dialog.getByLabel("Genre").selectOption("Drama");
    await dialog.getByLabel("Unwatched").check();
    await dialog.getByRole("radio", { name: "Ada Lens" }).check();
    await dialog.getByRole("button", { name: "Apply" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(
        page.getByRole("button", { name: "Filters, 3 on" }),
    ).toBeFocused();
    await expect.poll(() => pages().at(-1)?.get("genres")).toBe("Drama");
    let last = pages().at(-1)!;
    expect(last.get("isPlayed")).toBe("false");
    expect(last.get("personIds")).toBe("ada");
    expect(last.get("startIndex")).toBe("0");
    expect(last.get("includeItemTypes")).toBe("Movie");
    // Removing a chip clears that facet only.
    await page.getByRole("button", { name: "Remove Drama" }).click();
    await expect.poll(() => pages().at(-1)?.has("genres")).toBe(false);
    last = pages().at(-1)!;
    expect(last.get("isPlayed")).toBe("false");
    expect(last.get("personIds")).toBe("ada");
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect.poll(() => pages().at(-1)?.has("isPlayed")).toBe(false);
    expect(pages().at(-1)?.has("personIds")).toBe(false);
    await expect(page.locator(".catalog-filter-chips")).toHaveCount(0);

    // BROWSE-05: one tab stop; arrows skip empty letters; a jump pages in
    // through its title and focuses it without filtering the list.
    const rail = page.getByRole("navigation", { name: "Jump to letter" });
    await expect(
        rail.getByRole("button", { name: "Jump to B" }),
    ).toBeDisabled();
    await expect(rail.locator('button[tabindex="0"]')).toHaveCount(1);
    await rail.getByRole("button", { name: "Jump to A" }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(rail.getByRole("button", { name: "Jump to Z" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator('[data-focus="grid:library:i65"]')).toBeFocused();
    await expect(page.locator('[data-focus="grid:library:i0"]')).toHaveCount(1);
    // The rail is only for title order.
    await page
        .getByLabel("Sort by", { exact: true })
        .selectOption("DateCreated");
    await expect(rail).toHaveCount(0);
    await page.getByLabel("Sort by", { exact: true }).selectOption("SortName");

    // BROWSE-06: Play queues this library's movies in its order, up to 300.
    await page.getByRole("button", { name: "▶ Play" }).click();
    await expect
        .poll(() =>
            queries.find((q) => q.get("limit") === "300")?.get("sortBy"),
        )
        .toBe("SortName");
    const queue = queries.find((q) => q.get("limit") === "300")!;
    expect(queue.get("includeItemTypes")).toBe("Movie");
    expect(queue.get("recursive")).toBe("true");
});

test("BROWSE-06: a box set lands on Play, Shuffle asks for random order, and nothing playable says so", async ({
    page,
}) => {
    const set = {
            Id: "boxset",
            Name: "Shorts Collection",
            Type: "BoxSet",
            IsFolder: true,
            ChildCount: 2,
            ProductionYear: 2006,
        },
        queues: URLSearchParams[] = [];
    let playable = false;
    await start(page, (url) => {
        if (url.pathname === "/Items/Latest") return { body: [set] };
        if (url.pathname === "/Items/boxset") return { body: set };
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "boxset"
        )
            return { body: { Items: [set] } };
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("parentId") === "boxset"
        ) {
            if (url.searchParams.get("limit") === "300") {
                queues.push(url.searchParams);
                return {
                    body: {
                        Items: playable ? [movie("m1", "First short")] : [],
                    },
                };
            }
            return {
                body: {
                    Items: [
                        movie("m1", "First short"),
                        movie("m2", "Second short"),
                    ],
                    TotalRecordCount: 2,
                },
            };
        }
        return {};
    });
    await page.locator('[data-focus="latest:library:boxset"]').click();
    await expect(
        page.getByRole("heading", { name: "Shorts Collection", exact: true }),
    ).toBeVisible();
    await expect(page.locator('[data-focus="queue-play"]')).toBeFocused();
    await page.getByRole("button", { name: "Shuffle" }).click();
    await expect(page.getByRole("alert")).toHaveText(
        "Nothing here can be played.",
    );
    const shuffle = queues.at(-1)!;
    expect(shuffle.get("sortBy")).toBe("Random");
    expect(shuffle.get("recursive")).toBe("true");
    expect(shuffle.get("includeItemTypes")).toBe("Movie,Episode,Video");
    // The page did not move to the player.
    await expect(page.locator('[data-focus="queue-play"]')).toBeVisible();
    playable = true;
    await page.getByRole("button", { name: "▶ Play" }).click();
    await expect.poll(() => queues.length).toBe(2);
    expect(queues[1].get("sortBy")).toBe("SortName");
    await expect(page.locator('[data-focus="queue-play"]')).toHaveCount(0);
});
