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
const REASON = "Its video format won't play on this browser.";
const ACTION = {
    mouse: "Click this note for details and fixes.",
    touch: "Tap this note for details and fixes.",
    keyboard: "Press Down, then choose Playback info.",
};
const LINE = `${REASON} ${ACTION.mouse}`;
const SUMMARY =
    "The server is converting this video because this browser can't play its video format (HEVC).";

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
            // Each change to a non-empty text is one announcement, even a repeat
            // after the region was cleared.
            if (text && (window as any).lastAnnounced !== text) list.push(text);
            (window as any).lastAnnounced = text;
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
    expect(announced).toEqual([`${TITLE}. ${LINE}`]);
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
    // A plain one-sentence summary comes first, then remedies for this browser;
    // the technical details start collapsed.
    await expect(details.locator("> :first-child")).toHaveText(SUMMARY);
    const project = test.info().project.name;
    const remedies = details.getByRole("region", { name: "What you can do" });
    await expect(remedies).toContainText(
        project === "chromium"
            ? "Use graphics acceleration when available"
            : project === "webkit"
              ? "newer versions of Safari"
              : "Open Pictureshow in Chrome or Edge",
    );
    await expect(remedies).toContainText("Use a Pictureshow app");
    const collapsed = (await details.innerText()).replace(/\s+/g, " ");
    expect(collapsed).not.toMatch(/transcod|chrome:\/\/|Method|Video codec/i);
    expect(collapsed).toContain("Technical details");
    // Down scrolls to Technical details; Enter expands it.
    for (let i = 0; i < 40; i++) {
        if (
            await page.evaluate(
                () =>
                    (document.activeElement as HTMLElement)?.dataset.scroll ===
                    undefined,
            )
        )
            break;
        await page.keyboard.press("ArrowDown");
    }
    const technical = details.locator("summary");
    await expect(technical).toBeFocused();
    await expect(technical).toHaveText("Technical details");
    await page.keyboard.press("Enter");
    await expect(details.locator("details")).toHaveAttribute("open", "");
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
    expect(text.includes("chrome://gpu")).toBe(project === "chromium");
    // The countdown, skip and next actions are suppressed while it is open.
    await expect(page.locator(".skip-segment, .next-episode")).toHaveCount(0);
    await capture(page, "playback-info-expanded");
    // Down scrolls the expanded details until the end, then reaches Done.
    for (let i = 0; i < 80; i++) {
        if (
            await page.evaluate(
                () =>
                    !(document.activeElement as HTMLElement)?.closest(
                        "[data-scroll]",
                    ),
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
    await page
        .getByRole("button", { name: "Playback info", exact: true })
        .click();
    const details = page.getByRole("region", { name: "Playback info details" });
    await expect(details.locator("> :first-child")).toHaveText(
        "This browser is playing the file directly, with no conversion.",
    );
    await expect(details).not.toContainText("What you can do");
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
    await page
        .getByRole("button", { name: "Playback info", exact: true })
        .click();
    const details = page.getByRole("region", { name: "Playback info details" });
    await expect(details.locator("> :first-child")).toHaveText(
        "The server is converting this video to keep it under a bitrate limit.",
    );
    await expect(details).toContainText(
        "Raise Max streaming bitrate in Settings to try the original quality.",
    );
    await expect(details).toContainText(
        "This stream exceeds a device or server bitrate limit.",
    );
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
    // The label names the setting; the switch carries the state.
    await expect(toggle).toHaveAccessibleName("Conversion notice");
    await expect(toggle).toBeChecked();
    const row = page.locator(".switch-row").filter({ has: toggle });
    await expect(row.locator(".switch-state")).toHaveText("On");
    // The help text sits with its own switch and starts where the label text starts.
    const caption = row.locator(".switch-caption");
    const [toggleBox, captionBox] = [
        (await toggle.boundingBox())!,
        (await caption.boundingBox())!,
    ];
    const textStart = (selector: string) =>
        row.evaluate((r, sel) => {
            const host = r.querySelector(sel)!;
            const walker = document.createTreeWalker(
                host,
                NodeFilter.SHOW_TEXT,
            );
            let node: Node | null;
            while ((node = walker.nextNode()))
                if (node.textContent?.trim()) break;
            const range = document.createRange();
            range.selectNodeContents(node!);
            return range.getClientRects()[0].left;
        }, selector);
    expect(
        Math.abs(
            (await textStart(".switch-caption")) - (await textStart("label")),
        ),
    ).toBeLessThanOrEqual(1);
    expect(captionBox.y - (toggleBox.y + toggleBox.height)).toBeLessThan(16);
    await expect(toggle).toHaveAccessibleDescription(
        "Shows a short note when the server has to convert a video for this browser.",
    );
    // Its own row, directly after audio passthrough and before Subtitle size.
    const order = await page
        .locator(".setting-list > label, .setting-list > .switch-row")
        .evaluateAll((rows) =>
            rows.map((r) =>
                ((r.querySelector("label") ?? r).textContent ?? "").trim(),
            ),
        );
    const at = order.findIndex((t) => t.startsWith("Conversion notice"));
    expect(order[at - 1]).toContain("Allow audio passthrough");
    expect(order[at + 1]).toContain("Subtitle size");
    await capture(page, "settings-row-on");
    await toggle.click();
    await expect(toggle).toHaveAccessibleName("Conversion notice");
    await expect(toggle).not.toBeChecked();
    await expect(row.locator(".switch-state")).toHaveText("Off");
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
    await page
        .getByRole("button", { name: "Playback info", exact: true })
        .click();
    await expect(
        page
            .getByRole("region", { name: "Playback info details" })
            .locator("> :first-child"),
    ).toHaveText(SUMMARY);
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
    await page
        .getByRole("button", { name: "Playback info", exact: true })
        .click();
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

/** Resolves a held route when released. */
function gate() {
    let release!: () => void;
    const promise = new Promise<void>((resolve) => (release = resolve));
    return { promise, release };
}

async function chooseSpanish(page: Page) {
    await page.getByRole("button", { name: "Audio", exact: true }).click();
    await page.getByRole("radio", { name: /Spanish/ }).click();
}

async function ready(page: Page) {
    await expect
        .poll(
            () =>
                page
                    .locator("video")
                    .evaluate((v: HTMLVideoElement) => v.readyState),
            { timeout: 20000 },
        )
        .toBeGreaterThanOrEqual(2);
}

test("the notice is announced once per viewing across a seek and a track change that replaces the decoder; the next viewing announces once", async ({
    page,
}) => {
    await watchAnnouncements(page);
    const f = await converting(page, "VideoCodecNotSupported");
    await play(page);
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    const shownAt = Date.now();
    // A seek while it is up.
    await page.locator("video").focus();
    await page.keyboard.press("ArrowRight");
    await expect(notice(page)).toBeVisible();
    // An audio change while it is up renegotiates and replaces the decoder.
    const before = f.negotiations.length;
    await chooseSpanish(page);
    await expect.poll(() => f.negotiations.length).toBeGreaterThan(before);
    await ready(page);
    if (Date.now() - shownAt < 8000) await expect(notice(page)).toBeVisible();
    await expect(notice(page)).toHaveCount(0, { timeout: 12000 });
    const announced = (): Promise<string[]> =>
        page.evaluate(() => (window as any).announced);
    expect(await announced()).toHaveLength(1);
    // The notice does not come back for the rest of this viewing.
    await page.waitForTimeout(1500);
    await expect(notice(page)).toHaveCount(0);
    // The next viewing announces exactly once more.
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await page
        .getByRole("button", { name: /Resume from|Play/ })
        .first()
        .click();
    await expect(notice(page)).toBeVisible({ timeout: 15000 });
    await page.waitForTimeout(1000);
    expect(await announced()).toHaveLength(2);
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("speed returns to 1× with a visible explanation when a replacement stream cannot keep the rate", async ({
    page,
}) => {
    const f = await fixture(page);
    await play(page);
    const speed = page.getByRole("button", { name: /^Speed, / });
    await speed.click();
    await page.getByRole("radio", { name: "1.5×" }).click();
    await expect(speed).toHaveAccessibleName("Speed, 1.5×");
    // From here this decoder cannot change speed: it reports 1× whatever is set.
    await page.locator("video").evaluate((v: HTMLVideoElement) => {
        Object.defineProperty(v, "playbackRate", {
            configurable: true,
            get: () => 1,
            set: () => {},
        });
    });
    const before = f.negotiations.length;
    await chooseSpanish(page);
    await expect.poll(() => f.negotiations.length).toBeGreaterThan(before);
    const explanation =
        "Speed returned to 1× because this stream could not keep 1.5×. Choose Speed to try again.";
    // Visible in the player without opening Speed.
    await expect(page.locator(".playback-feedback")).toHaveText(explanation, {
        timeout: 20000,
    });
    await expect(speed).toHaveAccessibleName("Speed, 1×");
    await capture(page, "speed-reset");
    await speed.click();
    const dialog = page.getByRole("dialog", { name: "Speed" });
    await expect(dialog.getByRole("status")).toHaveText(explanation);
    await expect(
        dialog.getByRole("radio", { name: "1× (normal)" }),
    ).toHaveAttribute("aria-checked", "true");
    await capture(page, "speed-reset-panel");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("Playback info never shows the previous stream while a new negotiation is pending", async ({
    page,
}) => {
    const f = await converting(page, "VideoCodecNotSupported");
    await play(page);
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    const hold = gate();
    await page.route(
        "https://jellyfin.example/Items/movie/PlaybackInfo",
        async (route) => {
            await hold.promise;
            await route.fallback();
        },
    );
    const before = f.negotiations.length;
    await chooseSpanish(page);
    await page
        .getByRole("button", { name: "Playback info", exact: true })
        .click();
    const details = page.getByRole("region", { name: "Playback info details" });
    await expect(details).toHaveText(
        "Preparing the new stream. Its details appear here when it is ready.",
    );
    // Nothing from the previous decision: no method, codecs or reasons.
    await expect(details).not.toContainText(/Transcode|hevc|mkv|convert/i);
    expect(f.negotiations.length).toBe(before);
    hold.release();
    await expect(details.locator("> :first-child")).toHaveText(SUMMARY, {
        timeout: 20000,
    });
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("keyboard: Down then Playback info opens the panel the notice points to", async ({
    page,
}) => {
    await converting(page, "VideoCodecNotSupported");
    await play(page);
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    await page.locator("video").focus();
    await page.keyboard.press("ArrowDown");
    // The line now speaks to the keyboard.
    await expect(notice(page).locator(".conversion-notice-line")).toHaveText(
        `${REASON} ${ACTION.keyboard}`,
    );
    for (let i = 0; i < 30; i++) {
        if (
            (await page.evaluate(() => document.activeElement?.textContent)) ===
            "Playback info"
        )
            break;
        // The web controls are a Tab row after Down reveals them on Play.
        await page.keyboard.press("Tab");
    }
    const opener = page.getByRole("button", {
        name: "Playback info",
        exact: true,
    });
    await expect(opener).toBeFocused();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog", { name: "Playback info" });
    await expect(dialog).toBeVisible();
    await expect(
        dialog.getByRole("region", { name: "Playback info details" }),
    ).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(opener).toBeFocused();
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("pointer: a click on the backdrop closes a panel and restores its opener", async ({
    page,
}) => {
    await fixture(page);
    await play(page);
    const opener = page.getByRole("button", {
        name: "Playback info",
        exact: true,
    });
    await opener.click();
    const dialog = page.getByRole("dialog", { name: "Playback info" });
    await expect(dialog).toBeVisible();
    await page.mouse.click(4, 4);
    await expect(dialog).toBeHidden();
    await expect(opener).toBeFocused();
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test("the conversion notice switch applies the next time the player opens, both ways", async ({
    page,
}) => {
    await converting(page, "VideoCodecNotSupported");
    await signIn(page);
    const settings = async (checked: boolean) => {
        await page.locator('[data-focus="home-settings"]').click();
        const toggle = page.getByRole("switch", { name: "Conversion notice" });
        if ((await toggle.isChecked()) !== checked) await toggle.click();
        await expect(toggle).toBeChecked({ checked });
        await page.getByRole("button", { name: "← Back" }).click();
    };
    const open = async () => {
        await page.locator('[data-focus="resume:movie"]').click();
        await page
            .getByRole("button", { name: /^(Resume from|Play)/ })
            .first()
            .click();
        await ready(page);
    };
    await settings(false);
    await open();
    await page.waitForTimeout(3000);
    await expect(notice(page)).toHaveCount(0);
    await page.getByRole("button", { name: "Stop and go back" }).click();
    await page.getByRole("button", { name: "← Back" }).click();
    await settings(true);
    await open();
    await expect(notice(page)).toBeVisible({ timeout: 10000 });
    await page.getByRole("button", { name: "Stop and go back" }).click();
});

test.describe("touch", () => {
    test.use({ hasTouch: true, viewport: { width: 390, height: 844 } });
    test("tapping the notice opens Playback info, and Done closes it", async ({
        page,
    }) => {
        await converting(page, "VideoCodecNotSupported");
        await signIn(page);
        await page.locator('[data-focus="resume:movie"]').tap();
        await page
            .getByRole("button", { name: "Resume from 0:10", exact: true })
            .tap();
        await ready(page);
        if (
            await page
                .getByRole("status")
                .filter({ hasText: "Press Play" })
                .count()
        )
            await page.getByRole("button", { name: "Play", exact: true }).tap();
        await expect(notice(page)).toBeVisible({ timeout: 10000 });
        await expect(
            notice(page).locator(".conversion-notice-line"),
        ).toHaveText(`${REASON} ${ACTION.touch}`);
        await notice(page).tap();
        const dialog = page.getByRole("dialog", { name: "Playback info" });
        await expect(dialog).toBeVisible();
        // On a phone the plain summary is on screen without scrolling.
        const summary = dialog.locator(".playback-info-summary");
        await expect(summary).toHaveText(SUMMARY);
        await expect(summary).toBeInViewport({ ratio: 1 });
        await dialog.getByRole("button", { name: "Done" }).tap();
        await expect(dialog).toBeHidden();
        await page.getByRole("button", { name: "Stop and go back" }).tap();
    });
});
