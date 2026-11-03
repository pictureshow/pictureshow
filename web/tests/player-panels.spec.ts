/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// PLAY-25 speed, PLAY-26 Playback info, PLAY-27 conversion notice, SET-11 switch.
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { test, expect, type Page } from "@playwright/test";
import { fixture, signIn, movie } from "./fixtures";

const jsonHeaders = {
    "access-control-allow-origin": "*",
    "content-type": "application/json",
};
const TITLE = "Converting this video for this browser";
const LINE =
    "Its video format won't play on this browser. Click or tap it, or press Down, then Playback info.";

/** A 10-bit HEVC MKV no browser path here decodes, so the server converts its video. */
const convertedSource = {
    ...movie.MediaSources[0],
    Container: "mkv",
    Bitrate: 18_400_000,
    TranscodingContainer: "ts",
    MediaStreams: movie.MediaSources[0].MediaStreams.map((s) =>
        s.Type === "Video"
            ? {
                  ...s,
                  Codec: "hevc",
                  BitDepth: 10,
                  Width: 3840,
                  Height: 2160,
                  BitRate: 17_000_000,
              }
            : s,
    ),
};

async function converting(page: Page, reasons: string) {
    const f = await fixture(page);
    await page.route("https://jellyfin.example/Items/movie?**", (route) =>
        route.fulfill({
            headers: jsonHeaders,
            body: JSON.stringify({
                ...movie,
                MediaSources: [convertedSource],
            }),
        }),
    );
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        (route) => {
            f.negotiations.push(route.request().postDataJSON());
            return route.fulfill({
                headers: jsonHeaders,
                body: JSON.stringify({
                    PlaySessionId: "convert-" + f.negotiations.length,
                    MediaSources: [
                        {
                            ...convertedSource,
                            SupportsDirectPlay: false,
                            TranscodingUrl: `/Videos/movie/master.m3u8?VideoCodec=h264&AudioCodec=aac&TranscodeReasons=${reasons}`,
                        },
                    ],
                }),
            });
        },
    );
    return f;
}

async function play(page: Page) {
    await signIn(page);
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    await expect
        .poll(
            async () =>
                (await page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.currentTime)) >
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
}

/** Records every distinct text the polite live region is given. */
async function watchAnnouncements(page: Page) {
    await page.addInitScript(() => {
        (window as any).announced = [];
        new MutationObserver(() => {
            const region = document.querySelector(
                '.player [aria-live="polite"].sr-only',
            );
            const text = region?.textContent ?? "";
            const list = (window as any).announced as string[];
            if (text && list.at(-1) !== text) list.push(text);
        }).observe(document, {
            subtree: true,
            childList: true,
            characterData: true,
        });
    });
}

async function capture(page: Page, name: string) {
    const directory = process.env.WEB_PANEL_EVIDENCE;
    if (!directory) return;
    const dir = join(directory, test.info().project.name);
    mkdirSync(dir, { recursive: true });
    await page.screenshot({
        path: join(dir, `${name}.jpg`),
        type: "jpeg",
        quality: 80,
    });
}

const notice = (page: Page) => page.locator(".conversion-notice");

test("conversion notice appears once after ready, never takes focus, hides under a panel without a second announcement, and leaves after 10 s", async ({
    page,
}) => {
    await watchAnnouncements(page);
    await converting(page, "VideoCodecNotSupported,AudioCodecNotSupported");
    await play(page);
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    await expect(notice(page)).toHaveText(TITLE + LINE);
    const shownAt = Date.now();
    // Focus stays with the viewer's control; the notice is not focusable.
    expect(
        await page.evaluate(() =>
            document.activeElement?.classList.contains("conversion-notice"),
        ),
    ).toBe(false);
    expect(await notice(page).getAttribute("tabindex")).toBeNull();
    expect(
        await page.locator("video").evaluate((v: HTMLVideoElement) => v.paused),
    ).toBe(false);
    await capture(page, "notice-over-video");
    // A panel hides it; on return it is visible again but not re-announced.
    const speed = page.getByRole("button", { name: /^Speed, / });
    await speed.click();
    await expect(notice(page)).toHaveCount(0);
    await page.keyboard.press("Escape");
    await expect(speed).toBeFocused();
    await expect(notice(page)).toBeVisible();
    const announced: string[] = await page.evaluate(
        () => (window as any).announced,
    );
    expect(announced).toEqual([
        `${TITLE}. Its video format won't play on this browser. Click or tap it, or press Down, then Playback info.`,
    ]);
    // Gone about 10 s after it first appeared, the hidden time included.
    await expect(notice(page)).toHaveCount(0, { timeout: 12000 });
    expect(Date.now() - shownAt).toBeGreaterThan(8500);
    await page.waitForTimeout(1500);
    await expect(notice(page)).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).announced)).toHaveLength(
        1,
    );
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("pressing the conversion notice opens Playback info with reasons first, and Back restores the opener", async ({
    page,
}) => {
    await converting(
        page,
        "VideoCodecNotSupported,AudioCodecNotSupported,SomeFutureReason",
    );
    await play(page);
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    await page.locator("video").focus();
    await notice(page).click();
    const dialog = page.getByRole("dialog", { name: "Playback info" });
    await expect(dialog).toBeVisible();
    const details = dialog.getByRole("region", {
        name: "Playback info details",
    });
    await expect(details).toBeFocused();
    const text = (await details.innerText()).replace(/\s+/g, " ");
    // Reasons precede format sections; unknown codes get generic wording, never raw text.
    expect(text.indexOf("Why the server converts")).toBeLessThan(
        text.indexOf("Reported by the server"),
    );
    expect(text).toContain(
        "This browser cannot decode this video format. The server converts the video.",
    );
    expect(text).toContain(
        "The server reported an additional conversion requirement that this client does not recognize.",
    );
    expect(text).not.toContain("SomeFutureReason");
    expect(text).not.toMatch(/https?:|m3u8|ApiKey|token/i);
    expect(text).toContain("Method Transcode");
    expect(text).toContain("Container mkv");
    expect(text).toContain("Video codec hevc");
    expect(text).toContain("Video resolution 3840×2160");
    expect(text).toContain("Bitrate 18.40 Mbps");
    expect(text).toContain("Subtitles Off");
    // The received picture is measured from the decoder, not from server metadata.
    await expect
        .poll(
            async () =>
                (await details.innerText()).match(/Resolution\s+(\S+)/)?.[1],
        )
        .toMatch(/^\d+×\d+$/);
    // Received formats come from the media pipeline or are honestly unreported.
    const received = (await details.innerText()).replace(/\s+/g, " ");
    expect(
        /Video format (H\.264|HEVC|AV1|VP9)/.test(received) ||
            (received.includes("Video format Not reported") &&
                received.includes("does not report the formats it received")),
    ).toBe(true);
    const isChromium = test.info().project.name === "chromium";
    expect(text.includes("chrome://gpu")).toBe(isChromium);
    // The countdown, skip and next actions are suppressed while it is open.
    await expect(page.locator(".skip-segment, .next-episode")).toHaveCount(0);
    // Down scrolls the details until the end, then reaches Done.
    for (let i = 0; i < 40; i++) {
        if (
            await page.evaluate(
                () =>
                    !(document.activeElement as HTMLElement)?.dataset ||
                    (document.activeElement as HTMLElement).dataset.scroll ===
                        undefined,
            )
        )
            break;
        await page.keyboard.press("ArrowDown");
    }
    await expect(dialog.getByRole("button", { name: "Done" })).toBeFocused();
    expect(
        await details.evaluate(
            (e) => e.scrollTop + e.clientHeight >= e.scrollHeight - 2,
        ),
    ).toBe(true);
    await page.keyboard.press("Escape");
    await expect(page.locator("video")).toBeFocused();
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("no conversion notice for direct play, and Playback info says Direct play", async ({
    page,
}) => {
    // Direct play from the default fixture.
    await fixture(page);
    await play(page);
    await page.waitForTimeout(2500);
    await expect(notice(page)).toHaveCount(0);
    await page.getByRole("button", { name: "Playback info" }).click();
    const details = page.getByRole("region", { name: "Playback info details" });
    await expect(details).toContainText("Direct play");
    await expect(details).not.toContainText("Why the server converts");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("no conversion notice for a quality cap or audio-only reason", async ({
    page,
}) => {
    await converting(page, "VideoBitrateNotSupported,AudioCodecNotSupported");
    await play(page);
    await page.waitForTimeout(2500);
    await expect(notice(page)).toHaveCount(0);
    await page.getByRole("button", { name: "Playback info" }).click();
    await expect(
        page.getByRole("region", { name: "Playback info details" }),
    ).toContainText("This stream exceeds a device or server bitrate limit.");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("conversion notice switch: settings row defaults On, Off is stored and suppresses only the notice", async ({
    page,
}) => {
    await converting(page, "VideoCodecNotSupported");
    await signIn(page);
    await page.locator('[data-focus="home-settings"]').click();
    const toggle = page.getByRole("switch", { name: /Conversion notice/ });
    await expect(toggle).toHaveAccessibleName("Conversion notice: On");
    await expect(toggle).toBeChecked();
    await expect(toggle).toHaveAccessibleDescription(
        "Shows a short note when the server has to convert a video for this browser.",
    );
    // Its own row, directly after audio passthrough and before Subtitle size.
    const order = await page
        .locator(".setting-list > label")
        .evaluateAll((labels) =>
            labels.map((l) => (l.textContent ?? "").trim().split("\n")[0]),
        );
    const at = order.findIndex((t) => t.startsWith("Conversion notice"));
    expect(order[at - 1]).toContain("Allow audio passthrough");
    expect(order[at + 1]).toContain("Subtitle size");
    await capture(page, "settings-row-on");
    await toggle.click();
    await expect(toggle).toHaveAccessibleName("Conversion notice: Off");
    expect(
        await page.evaluate(
            () =>
                JSON.parse(localStorage.getItem("pictureshow.preferences")!)
                    .conversionNotice,
        ),
    ).toBe(false);
    await capture(page, "settings-row-off");
    await page.getByRole("button", { name: "← Back" }).click();
    await page.locator('[data-focus="resume:movie"]').click();
    await page
        .getByRole("button", { name: "Resume from 0:10", exact: true })
        .click();
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    await page.waitForTimeout(3000);
    await expect(notice(page)).toHaveCount(0);
    await capture(page, "notice-off-playing");
    // Playback info still explains the conversion.
    await page.getByRole("button", { name: "Playback info" }).click();
    await expect(
        page.getByRole("region", { name: "Playback info details" }),
    ).toContainText("This browser cannot decode this video format.");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("speed panel opens on the current rate, applies with pitch preserved, survives decoder replacement and resets for the next viewing", async ({
    page,
}) => {
    const f = await fixture(page);
    await play(page);
    const speed = page.getByRole("button", { name: /^Speed, / });
    await expect(speed).toHaveAccessibleName("Speed, 1×");
    await speed.click();
    const dialog = page.getByRole("dialog", { name: "Speed" });
    await expect(
        dialog.getByRole("radio", { name: "1× (normal)" }),
    ).toBeFocused();
    await expect(dialog.getByRole("radio")).toHaveCount(7);
    await capture(page, "speed-panel");
    // Keyboard: Down twice from 1× reaches 1.5×, Enter selects and restores the opener.
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await expect(dialog.getByRole("radio", { name: "1.5×" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(speed).toBeFocused();
    await expect(speed).toHaveAccessibleName("Speed, 1.5×");
    const rate = () =>
        page.locator("video").evaluate((v: HTMLVideoElement) => ({
            rate: v.playbackRate,
            pitch: (v as any).preservesPitch ?? (v as any).webkitPreservesPitch,
        }));
    expect(await rate()).toEqual({ rate: 1.5, pitch: true });
    // Reopening enters on the active rate.
    await speed.click();
    await expect(dialog.getByRole("radio", { name: "1.5×" })).toBeFocused();
    await expect(dialog.getByRole("radio", { name: "1.5×" })).toHaveAttribute(
        "aria-checked",
        "true",
    );
    await page.keyboard.press("Escape");
    // Another audio track renegotiates and replaces the decoder; the speed stays.
    const before = f.negotiations.length;
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
    await expect.poll(() => f.negotiations.length).toBeGreaterThan(before);
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
    await expect.poll(async () => (await rate()).rate).toBe(1.5);
    await expect(speed).toHaveAccessibleName("Speed, 1.5×");
    // Playback info shows the rate actually in use.
    await page.getByRole("button", { name: "Playback info" }).click();
    await expect(
        page.getByRole("region", { name: "Playback info details" }),
    ).toContainText(/Speed\s*1\.5×/);
    await page.keyboard.press("Escape");
    // The next viewing starts at 1×.
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await page
        .getByRole("button", { name: /Resume from|Play/ })
        .first()
        .click();
    await expect(page.locator("video")).toBeVisible();
    await expect(
        page.getByRole("button", { name: /^Speed, / }),
    ).toHaveAccessibleName("Speed, 1×");
    expect((await rate()).rate).toBe(1);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("a rate the browser refuses is explained and never claimed active", async ({
    page,
}) => {
    await fixture(page);
    await play(page);
    // Simulate an output that cannot change speed: the element keeps 1×.
    await page.locator("video").evaluate((v: HTMLVideoElement) => {
        Object.defineProperty(v, "playbackRate", {
            configurable: true,
            get: () => 1,
            set: () => {},
        });
    });
    await page.getByRole("button", { name: /^Speed, / }).click();
    const dialog = page.getByRole("dialog", { name: "Speed" });
    await dialog.getByRole("radio", { name: "2×" }).click();
    await expect(dialog.getByRole("status")).toHaveText(
        "This browser could not play this video at 2×. Speed stays at 1×.",
    );
    await expect(dialog.getByRole("radio", { name: "2×" })).toHaveAttribute(
        "aria-checked",
        "false",
    );
    await expect(
        dialog.getByRole("radio", { name: "1× (normal)" }),
    ).toHaveAttribute("aria-checked", "true");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});
