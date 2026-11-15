/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Locator } from "@playwright/test";
import { fixture, signIn, signedInAs } from "./fixtures";

async function focusedColors(target: Locator) {
    return target.evaluate((element: HTMLElement) => {
        element.focus();
        const luminance = (color: string) => {
            const channels = color
                .match(/[\d.]+/g)!
                .slice(0, 3)
                .map(Number)
                .map((v) => {
                    const c = v / 255;
                    return c <= 0.04045
                        ? c / 12.92
                        : ((c + 0.055) / 1.055) ** 2.4;
                });
            return (
                channels[0] * 0.2126 +
                channels[1] * 0.7152 +
                channels[2] * 0.0722
            );
        };
        const style = getComputedStyle(element),
            background = luminance(style.backgroundColor);
        return {
            background: style.backgroundColor,
            color: style.color,
            secondary: [
                ...element.querySelectorAll(
                    ".catalog-muted,.catalog-card-state,.muted",
                ),
            ].map((child) => {
                const color = getComputedStyle(child).color,
                    foreground = luminance(color);
                return {
                    text: child.textContent,
                    color,
                    unobscured: (() => {
                        const box = child.getBoundingClientRect();
                        return element.contains(
                            document.elementFromPoint(
                                box.x + box.width / 2,
                                box.y + box.height / 2,
                            ),
                        );
                    })(),
                    ratio:
                        (Math.max(background, foreground) + 0.05) /
                        (Math.min(background, foreground) + 0.05),
                };
            }),
        };
    });
}

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
        await expect(play).toHaveAttribute("data-state", "ready");
        await play.focus();
        await expect(play).toBeInViewport({ ratio: 1 });
        await page.keyboard.press("Escape");
        await expect(card).toBeFocused();
        await page
            .getByRole("navigation", { name: "Main navigation" })
            .getByRole("button", { name: "Settings", exact: true })
            .click();
        // Settings announces its heading first; that landing target stays in
        // view at every size without the test moving it.
        const heading = page.getByRole("heading", {
            name: "Settings",
            exact: true,
        });
        await expect(heading).toBeFocused();
        await expect(heading).toBeInViewport({ ratio: 1 });
        const overflow = await page.evaluate(
            () => document.documentElement.scrollWidth > innerWidth + 1,
        );
        expect(overflow).toBe(false);
        await page.goBack();
        await expect(
            page.locator(signedInAs, { hasText: /^Viewer · / }),
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
        const failures = await page.evaluate(async () => {
            const result = await (window as any).axe.run(document, {
                runOnly: { type: "rule", values: ["color-contrast"] },
            });
            // Axe reports bgOverlap/incomplete for transformed card text even
            // after the scale settles. The direct first-frame sample above
            // resolves that uncertainty with text/background contrast and a hit
            // test proving that the focused secondary text is not obscured.
            return result.violations;
        });
        expect(failures).toEqual([]);
    };
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.keyboard.press("Shift");
    // Sample the first focus frame: animating only the background briefly puts
    // dark focused text on the old dark card, even when settled contrast passes.
    const card = page.locator('[data-focus="resume:movie"]');
    const firstFrame = await focusedColors(card);
    expect(firstFrame).toMatchObject({
        background: "rgb(230, 225, 229)",
        color: "rgb(49, 48, 51)",
    });
    expect(firstFrame.secondary).toHaveLength(2);
    for (const line of firstFrame.secondary) {
        expect(line.unobscured, line.text || "secondary text").toBe(true);
        expect(
            line.ratio,
            line.text || "secondary text",
        ).toBeGreaterThanOrEqual(4.5);
    }
    // The direct sample checks the first paint; axe checks the settled rendered
    // text after the scale animation.
    await card.evaluate((el) =>
        Promise.all(el.getAnimations().map((a) => a.finished.catch(() => {}))),
    );
    await contrast();
    await page.keyboard.press("Enter");
    await expect(page.locator('[data-focus="play"]')).toHaveAttribute(
        "data-state",
        "ready",
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

test("wide keyboard-focused license rows keep their secondary text readable", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    await page
        .getByRole("button", { name: "About Pictureshow", exact: true })
        .click();
    await page
        .getByRole("button", { name: "Open-source licenses", exact: true })
        .click();
    await expect(page.getByText("4 bundled dependencies")).toBeVisible();
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.keyboard.press("Shift");
    const colors = await focusedColors(
        page.locator('[data-focus="license:0"]'),
    );
    expect(colors.secondary).toHaveLength(1);
    for (const line of colors.secondary)
        expect(line.ratio, line.text || "license text").toBeGreaterThanOrEqual(
            4.5,
        );
});
