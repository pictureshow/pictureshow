/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import axe from "axe-core";
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
    const pending = page.getByRole("button", {
        name: "Play / Resume when ready",
        exact: true,
    });
    await expect(pending).toBeFocused();
    await expect(pending).toHaveAttribute("aria-disabled", "true");
    await page.keyboard.press("Enter");
    await page.keyboard.press("Enter");
    await pending.dispatchEvent("keydown", { key: "MediaPlay", bubbles: true });
    await expect(page.getByRole("status")).toContainText("Play requested");
    expect(negotiations).toBe(0);
    release();
    await expect.poll(() => negotiations).toBe(1);
    await page.waitForTimeout(300);
    expect(negotiations).toBe(1);
});
