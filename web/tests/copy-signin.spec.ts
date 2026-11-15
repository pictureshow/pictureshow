/* Real-user review 2026-11-15 (W-8, W-9, W-10, W-11, W-12, W-14, W-17, W-21):
 * the words on sign-in, Settings, Support and Troubleshooting.
 * SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { test, expect, type Page } from "@playwright/test";
import { fixture, signIn } from "./fixtures";

const cors = { "access-control-allow-origin": "*" };
async function toSignIn(page: Page) {
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://jellyfin.example");
    await page.keyboard.press("Enter");
    await expect(
        page.getByRole("heading", { name: "Welcome back." }),
    ).toBeVisible();
}
const people = (n: number) =>
    Array.from({ length: n }, (_, i) => ({ Id: "u" + i, Name: "viewer" + i }));

test("an empty address gets the app's own inline error, not the browser bubble", async ({
    page,
}) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Connect to server →" }).click();
    const field = page.getByLabel("Server address");
    await expect(page.getByText("Enter your server's address.")).toBeVisible();
    await expect(field).toBeFocused();
    await expect(field).toHaveAttribute("aria-invalid", "true");
    expect(
        await field.evaluate((e) => (e as HTMLInputElement).validationMessage),
    ).toBe("");
    await field.fill("https://jellyfin.example");
    await expect(page.getByText("Enter your server's address.")).toHaveCount(0);
});

test("an address nobody answers says what to check, with technical causes under More details", async ({
    page,
}) => {
    await page.route("https://nobody.example/**", (route) => route.abort());
    await page.goto("/");
    await page.getByLabel("Server address").fill("https://nobody.example");
    await page.keyboard.press("Enter");
    const alert = page.locator(".error[role=alert]");
    await expect(alert).toContainText(
        "Pictureshow couldn't reach a server at that address.",
    );
    await expect(alert).toContainText("http://myserver:8096");
    const more = alert.locator("details");
    await expect(more.locator("p")).toBeHidden();
    await more.locator("summary").click();
    await expect(more).toContainText("certificate");
    await expect(more).toContainText("CORS");
    // Causes are a bulleted list, not one semicolon sentence.
    await expect(more.locator("li")).toHaveCount(5);
    // Dismiss sits top right, on the message's row, and the banner shares
    // the form column's left edge.
    const box = (await alert.boundingBox())!;
    const msg = (await alert.locator(".error-message").boundingBox())!;
    const dismiss = (await alert
        .getByRole("button", { name: "Dismiss" })
        .boundingBox())!;
    const form = (await page.locator(".welcome").boundingBox())!;
    expect(dismiss.x).toBeGreaterThan(msg.x + msg.width - 1);
    expect(dismiss.y).toBeLessThan(msg.y + msg.height);
    expect(Math.abs(box.x - form.x)).toBeLessThan(2);
    expect(Math.abs(box.width - form.width)).toBeLessThan(2);
});

test("a wrong password says so, and the form stays usable", async ({
    page,
}) => {
    await fixture(page);
    await page.route(
        "https://jellyfin.example/Users/AuthenticateByName",
        (route) => route.fulfill({ status: 401, headers: cors, body: "" }),
    );
    await toSignIn(page);
    await page.getByLabel("Username").fill("Viewer");
    await page.getByLabel("Password", { exact: true }).fill("wrong");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.locator(".error[role=alert]")).toContainText(
        "That username and password didn't work. Check them and try again.",
    );
    await expect(page.locator(".error[role=alert]")).not.toContainText(
        /expired|refused/,
    );
    await expect(page.getByLabel("Username")).toHaveValue("Viewer");
});

test("an empty username gets an inline error instead of a request", async ({
    page,
}) => {
    await fixture(page);
    let tried = false;
    page.on("request", (r) => {
        if (r.url().includes("AuthenticateByName")) tried = true;
    });
    await toSignIn(page);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.getByText("Enter your username.")).toBeVisible();
    await expect(page.getByLabel("Username")).toBeFocused();
    expect(tried).toBe(false);
});

test("the form comes before the user list; a picked user is shown with Not you?", async ({
    page,
}) => {
    await fixture(page);
    await page.route("https://jellyfin.example/Users/Public", (route) =>
        route.fulfill({
            headers: cors,
            contentType: "application/json",
            body: JSON.stringify(people(19)),
        }),
    );
    await page.setViewportSize({ width: 640, height: 450 }); // ~1280×900 at 200%
    await toSignIn(page);
    await expect(page.getByLabel("Username")).toBeInViewport();
    await expect(page.getByLabel("Password", { exact: true })).toBeInViewport();
    const picker = page.locator(".user-picker");
    await expect(picker.getByRole("button", { name: /^viewer/ })).toHaveCount(
        8,
    );
    await picker.getByRole("button", { name: "Show all 19 users" }).focus();
    await page.keyboard.press("Enter");
    // The pressed button goes away; focus lands on the 9th user, not the page.
    await expect(
        picker.getByRole("button", { name: "viewer8", exact: true }),
    ).toBeFocused();
    await expect(picker.getByRole("button", { name: /^viewer/ })).toHaveCount(
        19,
    );
    const chip = picker.getByRole("button", { name: "viewer3", exact: true });
    await expect(chip).toHaveAttribute("aria-pressed", "false");
    await chip.click();
    await expect(chip).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByLabel("Password", { exact: true })).toBeFocused();
    await expect(page.locator(".signing-in-as")).toHaveText(
        "Signing in as viewer3 · Not you?",
    );
    await page.getByRole("button", { name: "Not you?" }).click();
    await expect(page.getByLabel("Username")).toHaveValue("");
    await expect(page.getByLabel("Username")).toBeFocused();
    await expect(page.locator(".signing-in-as")).toHaveCount(0);
    await expect(chip).toHaveAttribute("aria-pressed", "false");
});

test("Quick Connect says where to approve the code, and not Pictureshow Settings", async ({
    page,
}) => {
    await fixture(page);
    await page.route("https://jellyfin.example/QuickConnect/Connect**", (r) =>
        r.fulfill({
            headers: cors,
            contentType: "application/json",
            body: JSON.stringify({ Authenticated: false }),
        }),
    );
    await toSignIn(page);
    await page.getByRole("button", { name: "Use Quick Connect" }).click();
    const page_ = page.locator(".signin");
    await expect(page_).toContainText("already signed in to this server");
    await expect(page_).toContainText("https://jellyfin.example");
    await expect(page_).toContainText("Quick Connect");
    await expect(page_).not.toContainText("Settings → Quick Connect");
    await expect(page_).toContainText("Pictureshow can't approve codes");
});

test("Settings: jump list, read-only passthrough, Accessibility switch writes screenReader, Sign out in Accounts", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    await expect(
        page.getByRole("heading", { name: "Settings", exact: true }),
    ).toBeVisible();
    // SET-02 on web: text, not a switch.
    await expect(
        page.getByRole("switch", { name: /passthrough/i }),
    ).toHaveCount(0);
    await expect(page.locator("#passthrough-row")).toHaveText(
        /Audio passthrough\s*Not available in browsers\. The browser decodes the sound itself\./,
    );
    // W-12: the setting the player reads, in plain words.
    const ask = page.getByRole("switch", {
        name: "Ask before playing the next episode",
    });
    await expect(ask).toHaveAccessibleDescription(
        "Useful with a screen reader: the next episode waits until you press Play now.",
    );
    await expect(ask).toBeChecked();
    await ask.click();
    expect(
        await page.evaluate(
            () =>
                JSON.parse(localStorage.getItem("pictureshow.preferences")!)
                    .screenReader,
        ),
    ).toBe(false);
    await expect(
        page.getByText(/Browsers do not report|screen reader is enabled/),
    ).toHaveCount(0);
    // W-17: the jump list reaches Account and help in a few presses.
    const jump = page.getByRole("navigation", { name: "Settings sections" });
    await jump.getByRole("button", { name: "Account and help" }).click();
    await expect(
        page.getByRole("heading", { name: "Account and help" }),
    ).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(
        page.getByRole("button", { name: "Accounts and sign out" }),
    ).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator(".current-account")).toContainText(
        "Signed in as Viewer on Evening Cinema.",
    );
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByLabel("Username")).toBeVisible();
});

test("Support and Troubleshooting speak plainly; the technical report sits under a disclosure", async ({
    page,
}) => {
    await fixture(page);
    await signIn(page);
    await page
        .getByRole("navigation", { name: "Main navigation" })
        .getByRole("button", { name: "Settings", exact: true })
        .click();
    await page
        .getByRole("button", { name: "Support Pictureshow", exact: true })
        .click();
    const support = page.locator(".information");
    await expect(support).not.toContainText(
        /configured|provider|Google Play billing/,
    );
    await expect(support).toContainText(
        "You can't become a supporter in the web app yet",
    );
    await expect(support).toContainText("Everything here stays free.");
    // Play is internal testing only: no pointer to a store nobody can reach.
    await expect(support).not.toContainText("Android");
    await page.keyboard.press("Escape");
    await page
        .getByRole("button", { name: "Troubleshooting", exact: true })
        .click();
    const report = page.locator(".information");
    await expect(report.locator(".verdict")).toHaveText(/^This browser /);
    await expect(report.locator(".verdict")).not.toHaveText(/MSE|MIME|HLS/);
    await expect(report).not.toContainText("No Android capabilities");
    await expect(
        page.getByRole("button", { name: "View device profile JSON" }),
    ).toBeHidden();
    await report.getByText("Technical details").click();
    await expect(report).toContainText("Media Source Extensions");
    await expect(
        page.getByRole("button", { name: "View device profile JSON" }),
    ).toBeVisible();
});
