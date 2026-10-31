/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect } from "@playwright/test";
import { fixture, signIn } from "./fixtures";

test("130 percent layout budget keeps phone, desktop and TV keyboard targets reachable", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    // Browser zoom reduces the CSS viewport available to layout. Exercise that
    // budget explicitly; this is not a claim of a native OS text-scale pilot.
    for (const [width, height] of [
        [1920, 1080],
        [1280, 800],
        [390, 844],
    ]) {
        await page.setViewportSize({
            width: Math.round(width / 1.3),
            height: Math.round(height / 1.3),
        });
        const card = page.locator('[data-focus="resume:movie"]');
        await card.focus();
        await expect(card).toBeFocused();
        await page.keyboard.press("Enter");
        const play = page.locator('[data-focus="play"]');
        await expect(play).toHaveAttribute("aria-disabled", "false");
        await play.focus();
        await expect(play).toBeInViewport({ ratio: 1 });
        await page.keyboard.press("Escape");
        await expect(card).toBeFocused();
        await page.locator('[data-focus="home-settings"]').click();
        const select = page.getByLabel("Max streaming bitrate");
        await select.focus();
        await expect(select).toBeInViewport({ ratio: 1 });
        const overflow = await page.evaluate(
            () => document.documentElement.scrollWidth > innerWidth + 1,
        );
        expect(overflow).toBe(false);
        await page.keyboard.press("Escape");
        await expect(
            page.getByRole("heading", { name: "Viewer", exact: true }),
        ).toBeVisible();
    }
});

test("focused TV card metadata and selected track rows keep WCAG text contrast", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    const { default: axe } = await import("axe-core");
    await page.addScriptTag({ content: axe.source });
    const contrast = async () => {
        const failures = await page.evaluate(
            async () =>
                (
                    await (window as any).axe.run(document, {
                        runOnly: { type: "rule", values: ["color-contrast"] },
                    })
                ).violations,
        );
        expect(failures).toEqual([]);
    };
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.locator('[data-focus="resume:movie"]').focus();
    await contrast();
    await page.keyboard.press("Enter");
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "aria-disabled",
        "false",
    );
    await page.keyboard.press("Enter");
    await expect(page.locator("video")).toBeVisible();
    for (const [width, height] of [
        [1920, 1080],
        [1280, 800],
        [390, 844],
    ]) {
        await page.setViewportSize({ width, height });
        await page.locator("video").focus();
        await page.keyboard.press("Enter");
        await page.getByRole("button", { name: "Audio", exact: true }).click();
        const selected = page
            .getByRole("dialog")
            .locator('[aria-checked="true"]');
        await selected.focus();
        await expect(selected).toBeFocused();
        // Wait only for CSS color transitions, not for a deferred focus move.
        await page.waitForTimeout(200);
        await contrast();
        await page.keyboard.press("Escape");
    }
});
