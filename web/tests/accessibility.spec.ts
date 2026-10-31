/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect } from "@playwright/test";
import axe from "axe-core";
test("server form is semantic, passes WCAG AA checks, and supports D-pad field navigation", async ({
    page,
}) => {
    await page.goto("/");
    await expect(page.getByLabel("Server address")).toBeFocused();
    await page.addScriptTag({ content: axe.source });
    const scan = await page.evaluate(
        async () =>
            await (window as unknown as { axe: typeof axe }).axe.run(document, {
                runOnly: {
                    type: "tag",
                    values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"],
                },
            }),
    );
    expect(scan.violations).toEqual([]);
    await expect(page.getByLabel("Server address")).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(
        page.getByRole("button", { name: "Connect to server →" }),
    ).toBeFocused();
    await page.keyboard.press("ArrowUp");
    await expect(page.getByLabel("Server address")).toBeFocused();
});
