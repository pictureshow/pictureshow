/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, test } from "@playwright/test";
import { fixture, movie, signIn } from "./fixtures";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

test("a later accepted unwatch choice wins while the completion read remains held", async ({
    page,
}) => {
    const f = await fixture(page);
    let finishing = false,
        reads = 0,
        completionWrites = 0,
        choiceWrites = 0,
        serverPlayed = true,
        stopAccepted = false;
    let releaseStop!: () => void, releaseOld!: () => void;
    const stopGate = new Promise<void>((resolve) => {
        releaseStop = resolve;
    });
    const oldGate = new Promise<void>((resolve) => {
        releaseOld = resolve;
    });
    const headers = {
        "access-control-allow-origin": "*",
        "content-type": "application/json",
    };
    await page.route("https://jellyfin.example/**", async (route) => {
        const request = route.request(),
            url = new URL(request.url());
        if (url.pathname === "/Sessions/Playing/Stopped") {
            await stopGate;
            stopAccepted = true;
            return route.fallback();
        }
        if (
            url.pathname === "/UserPlayedItems/movie" &&
            request.method() === "POST"
        ) {
            completionWrites++;
            return route.fulfill({ status: 204, headers });
        }
        if (
            url.pathname === "/UserPlayedItems/movie" &&
            request.method() === "DELETE"
        ) {
            choiceWrites++;
            serverPlayed = false;
            return route.fulfill({
                headers,
                body: JSON.stringify({ Played: serverPlayed }),
            });
        }
        if (
            !finishing &&
            url.pathname === "/Items/movie" &&
            !url.searchParams.has("fields")
        ) {
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    ...movie,
                    UserData: { ...movie.UserData, Played: serverPlayed },
                }),
            });
        }
        if (
            !finishing &&
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        ) {
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    Items: [
                        {
                            ...movie,
                            UserData: {
                                ...movie.UserData,
                                Played: serverPlayed,
                            },
                        },
                    ],
                }),
            });
        }
        if (
            finishing &&
            url.pathname === "/Items/movie" &&
            !url.searchParams.has("fields")
        ) {
            const ordinal = ++reads;
            // The destination read deliberately fails while Stop is held.
            // Completion's separate authoritative read stays held until the
            // returning viewer can accept an Unwatched choice.
            if (ordinal === 1) return route.fulfill({ status: 503, headers });
            if (ordinal === 2) {
                expect(stopAccepted).toBe(true);
                await oldGate;
            }
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    ...movie,
                    UserData: {
                        Played: serverPlayed,
                        PlaybackPositionTicks: 0,
                    },
                }),
            });
        }
        if (
            finishing &&
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    Items: [
                        {
                            ...movie,
                            UserData: {
                                Played: serverPlayed,
                                PlaybackPositionTicks: 0,
                            },
                        },
                    ],
                }),
            });
        return route.fallback();
    });
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    const video = page.locator("video");
    await expect
        .poll(
            async () =>
                (await video.evaluate((v: HTMLVideoElement) => v.currentTime)) >
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
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.currentTime), {
            timeout: 20000,
        })
        .toBeGreaterThan(10.05);
    finishing = true;
    await video.evaluate((v: HTMLVideoElement) => {
        (window as any).nativeEnds = 0;
        v.addEventListener("ended", () => (window as any).nativeEnds++);
        v.currentTime = 39.75;
        v.playbackRate = 16;
    });
    await expect
        .poll(() => page.evaluate(() => (window as any).nativeEnds))
        .toBe(1);
    const retry = page.getByRole("button", { name: "Retry", exact: true });
    await expect(retry).toBeVisible();
    // Finishing a film says so politely and rings the control that took focus.
    await expect(page.locator(".app-announcement")).toHaveText("Finished");
    await expect
        .poll(() =>
            page.evaluate(
                () =>
                    document.activeElement?.tagName === "BUTTON" &&
                    document.activeElement.hasAttribute("data-ring"),
            ),
        )
        .toBe(true);
    // Read failure publishes a polite cue without moving the reader to Retry.
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Retry is available",
    );
    releaseStop();
    await expect.poll(() => reads).toBe(2);
    await expect(page.locator(".catalog-detail-status")).toHaveText(
        "Saving progress…",
    );
    const evidenceDirectory = process.env.WEB_F3_EVIDENCE;
    if (evidenceDirectory) {
        const directory = join(evidenceDirectory, test.info().project.name);
        mkdirSync(join(directory, "full"), { recursive: true });
        writeFileSync(
            join(directory, "completion-read-error-pending.aria.txt"),
            await page.locator("body").ariaSnapshot(),
        );
        const previous = page.viewportSize();
        for (const [name, width, height] of [
            ["desktop", 1280, 800],
            ["phone", 390, 844],
        ] as const) {
            await page.setViewportSize({ width, height });
            await expect(retry).toBeVisible();
            await expect(page.locator(".catalog-detail-status")).toContainText(
                "Saving progress",
            );
            await page.screenshot({
                path: join(
                    directory,
                    "full",
                    `completion-read-error-pending-${name}.jpg`,
                ),
                type: "jpeg",
                quality: 90,
            });
            await page.screenshot({
                path: join(
                    directory,
                    `completion-read-error-pending-${name}.jpg`,
                ),
                type: "jpeg",
                quality: 50,
            });
        }
        if (previous) await page.setViewportSize(previous);
    }
    const watched = page.locator('[data-focus="watched"]');
    // The report barrier will schedule a fresh read, so Watched accepts.
    await expect(watched).toHaveAttribute("data-state", "pending");
    await expect(watched).toHaveAttribute("aria-disabled", "false");
    const watchedBox = await watched.boundingBox();
    expect(watchedBox).not.toBeNull();
    await page.mouse.click(
        watchedBox!.x + watchedBox!.width / 2,
        watchedBox!.y + watchedBox!.height / 2,
    );
    await expect(watched).toContainText("already requested");
    releaseOld();
    await expect.poll(() => reads).toBeGreaterThanOrEqual(3);
    await expect.poll(() => choiceWrites).toBe(1);
    await expect(watched).toHaveAttribute("aria-pressed", "false");
    expect(completionWrites).toBe(0);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
});

test("a film that plays to its end returns to a visible Finished line with Play again (web-kofi F11)", async ({
    page,
}) => {
    await fixture(page);
    let finishing = false;
    const headers = {
        "access-control-allow-origin": "*",
        "content-type": "application/json",
    };
    const done = {
        ...movie,
        UserData: { Played: true, PlaybackPositionTicks: 0 },
    };
    await page.route("https://jellyfin.example/**", async (route) => {
        const url = new URL(route.request().url());
        if (
            finishing &&
            url.pathname === "/Items/movie" &&
            !url.searchParams.has("fields")
        )
            return route.fulfill({ headers, body: JSON.stringify(done) });
        if (
            finishing &&
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "movie"
        )
            return route.fulfill({
                headers,
                body: JSON.stringify({ Items: [done] }),
            });
        return route.fallback();
    });
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
        await page.getByRole("button", { name: "Play", exact: true }).click();
    finishing = true;
    await video.evaluate((v: HTMLVideoElement) => {
        v.currentTime = 39.75;
        v.playbackRate = 16;
        void v.play();
    });
    const line = page.locator(".catalog-finished");
    await expect(line).toHaveText(
        `Finished. You watched ${movie.Name} to the end.`,
        {
            timeout: 20000,
        },
    );
    const play = page.locator('[data-focus="play"]');
    await expect(play).toHaveText("▶ Play again");
    await expect(play).toBeFocused();
    await expect(page.locator('[data-focus="restart"]')).toHaveCount(0);
});

test("BROWSE-06 with PLAY-17: a queue's last title ends the queue, even on an episode with a next one, and its page says Finished", async ({
    page,
}) => {
    await fixture(page);
    const headers = {
        "access-control-allow-origin": "*",
        "content-type": "application/json",
    };
    const set = {
            Id: "boxset",
            Name: "Shorts Collection",
            Type: "BoxSet",
            IsFolder: true,
            ChildCount: 1,
        },
        last = {
            ...movie,
            Id: "queue-last",
            Name: "Queue Finale",
            Type: "Episode",
            SeriesId: "series",
        },
        following = { ...last, Id: "after-queue", Name: "Not In Queue" };
    const seriesLookups: string[] = [];
    await page.route("https://jellyfin.example/**", async (route) => {
        const url = new URL(route.request().url()),
            json = (body: unknown) =>
                route.fulfill({ headers, body: JSON.stringify(body) });
        if (url.pathname === "/Items/Latest") return json([set]);
        if (url.pathname === "/Items/boxset") return json(set);
        if (url.pathname === "/Items/queue-last") return json(last);
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "boxset"
        )
            return json({ Items: [set] });
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("ids") === "queue-last"
        )
            return json({ Items: [last] });
        if (
            url.pathname === "/Items" &&
            url.searchParams.get("parentId") === "boxset"
        )
            return json({ Items: [last], TotalRecordCount: 1 });
        if (url.pathname === "/Shows/series/Episodes") {
            seriesLookups.push(url.search);
            return json({ Items: [last, following] });
        }
        return route.fallback();
    });
    await signIn(page);
    await page.locator('[data-focus="latest:library:boxset"]').click();
    await expect(
        page.getByRole("heading", { name: "Shorts Collection", exact: true }),
    ).toBeVisible();
    await page.locator('[data-focus="queue-play"]').click();
    const video = page.locator("video");
    await expect
        .poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), {
            timeout: 20000,
        })
        .toBeGreaterThanOrEqual(2);
    if (
        await page.getByRole("status").filter({ hasText: "Press Play" }).count()
    )
        await page.getByRole("button", { name: "Play", exact: true }).click();
    await video.evaluate((v: HTMLVideoElement) => {
        v.currentTime = v.duration - 0.25;
        void v.play();
    });
    // The queue ends here: no "Next episode" from the series, no "Next title".
    await expect(page.locator(".catalog-finished")).toHaveText(
        "Finished. You watched Queue Finale to the end.",
        { timeout: 20000 },
    );
    await expect(
        page.getByRole("heading", { name: "Shorts Collection", exact: true }),
    ).toBeVisible();
    await expect(page.locator('[data-focus="queue-play"]')).toBeVisible();
    await expect(page.locator("aside.next-episode")).toHaveCount(0);
    expect(seriesLookups).toEqual([]);
});
