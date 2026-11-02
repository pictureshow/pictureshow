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
        page.getByRole("heading", { name: "Catalog Viewer", exact: true }),
    ).toBeVisible();
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
    ).toHaveAccessibleName("Films Movies");
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
        page.getByText("2026 · PG · 2h 30m · ★ 8.1", { exact: true }),
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
    await expect(retry).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("button", { name: "Retrying…", exact: true }),
    ).toBeFocused();
    release!();
    await expect(page.locator('[data-focus="play"]')).toBeFocused();
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
    await page.locator('[data-focus="home-search"]').click();
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
    ).toHaveAccessibleName("Empty library Library");
    await expect(
        page.locator('[data-focus="libraries:nonempty"]'),
    ).toHaveAccessibleName("Nonempty library Library, Watched");
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
    await expect(pending).toHaveAttribute("aria-disabled", "true");
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

test("rc26 cold detail holds plain disabled actions, accepts early navigation, and announces Ready only for the same held control", async ({
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
    await expect(play).toHaveAttribute("aria-disabled", "true");
    await page.keyboard.press("ArrowRight");
    const watched = page.locator('[data-focus="watched"]');
    await expect(watched).toBeFocused();
    await expect(watched).toHaveAccessibleName("Watched");
    await expect(watched).not.toHaveAttribute("aria-pressed");
    await captureRecovery(page, "held");
    release();
    await expect(watched).toBeFocused();
    await expect(watched).toHaveAttribute("aria-disabled", "false");
    await expect(page.locator("[data-detail-feedback]")).toHaveText("Ready");
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
    await expect(retry).toBeFocused();
    await expect(page.getByRole("alert")).toContainText("503");
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
    await expect(page.getByRole("alert")).toHaveCount(1);
    await expect(page.getByRole("alert")).toContainText("503");
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
    await page.keyboard.press("Escape");
    fail = true;
    await opener.click();
    await expect(
        page.getByRole("button", { name: "Retry", exact: true }),
    ).toBeFocused();
    await expect(selectedSeason).not.toHaveAttribute("aria-pressed");
    await expect(selectedSeason).toHaveAttribute("aria-disabled", "true");
    for (const label of ["Watched", "Favorite"]) {
        const action = page.getByRole("button", { name: label, exact: true });
        await expect(action).toHaveAttribute("aria-disabled", "true");
        await expect(action).not.toHaveAttribute("aria-pressed");
    }
    await expect(page.getByRole("alert")).toHaveCount(1);
    await expect(page.getByRole("alert")).toContainText("503");
    await expect(page.locator("[data-detail-feedback]")).toHaveText("");
});
