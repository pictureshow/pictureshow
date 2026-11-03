/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, test } from "@playwright/test";
import { fixture, movie, signIn } from "./fixtures";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

test("rc26 newer authoritative detail releases Saving progress while the completion read remains held", async ({
    page,
}) => {
    const f = await fixture(page);
    let finishing = false,
        reads = 0,
        playedWrites = 0,
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
        if (url.pathname === "/UserPlayedItems/movie") {
            playedWrites++;
            return route.fulfill({ status: 204, headers });
        }
        if (
            finishing &&
            url.pathname === "/Items/movie" &&
            !url.searchParams.has("fields")
        ) {
            const ordinal = ++reads;
            // The returned destination reads while Stop is still held. Its
            // deliberate failure provides Retry; completion's later read stays
            // held while that real Retry supplies a newer full snapshot.
            if (ordinal === 1) return route.fulfill({ status: 503, headers });
            if (ordinal === 2) {
                expect(stopAccepted).toBe(true);
                await oldGate;
            }
            return route.fulfill({
                headers,
                body: JSON.stringify({
                    ...movie,
                    UserData: { Played: true, PlaybackPositionTicks: 0 },
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
                                Played: true,
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
    // Read failure publishes a polite cue without moving the reader to Retry.
    await expect(page.locator("[data-detail-feedback]")).toContainText(
        "Retry is available",
    );
    releaseStop();
    await expect.poll(() => reads).toBe(2);
    await expect(page.locator(".catalog-detail-status")).toContainText(
        "Saving progress",
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
    await retry.click();
    const play = page.locator('[data-focus="play"]');
    await expect(play).toHaveAttribute("aria-disabled", "false");
    await expect(play).toBeFocused();
    await expect(
        page.getByRole("button", { name: "Mark unwatched", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".catalog-detail-status")).toHaveCount(0);
    expect(reads).toBe(3);
    expect(playedWrites).toBe(0);
    expect(f.reports.filter((r) => r.path.endsWith("/Stopped"))).toHaveLength(
        1,
    );
    releaseOld();
});
