/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// Where keyboard focus lands when a page shows. Back returns to the control the
// viewer left (its `data-focus` key); a page opened forward lands on its
// `data-autofocus` control. Focus never stays on the page background: while the
// page is still loading its controls, its heading holds focus (or <main> when it
// has no heading), so keyboard and screen-reader users know where they are.
const visible = (e: HTMLElement) =>
    e.getClientRects().length > 0 && !e.closest("[inert]");

export function pageHeading(main: HTMLElement): HTMLElement {
    const heading = [...main.querySelectorAll<HTMLElement>("h1")].find(visible);
    if (!heading) return main;
    if (!heading.hasAttribute("tabindex")) heading.tabIndex = -1;
    return heading;
}

export function restorePageFocus(
    main: HTMLElement,
    entry: { focus?: string; scroll?: number },
): () => void {
    let wanted = entry.focus,
        // Back waits for its own control; a page opened forward takes its autofocus one.
        acceptAutofocus = !entry.focus,
        first = true,
        settled: HTMLElement | undefined,
        holding: HTMLElement | undefined;
    const find = () => {
        if (wanted) {
            const keyed = [
                ...document.querySelectorAll<HTMLElement>("[data-focus]"),
            ]
                .filter((e) => e.dataset.focus === wanted)
                .find(visible);
            if (keyed) return keyed;
        }
        if (acceptAutofocus)
            return [
                ...document.querySelectorAll<HTMLElement>("[data-autofocus]"),
            ].find(visible);
    };
    const restore = () => {
        const active = document.activeElement as HTMLElement | null;
        const lost = !active || active === document.body || !active.isConnected;
        if (settled) {
            if (settled.isConnected || !lost) return;
            // The focused control went away, as Detail's loading Play does when the
            // loaded page arrives. Its successor has the same key; a page with no such
            // control (a library has no Play) takes its own autofocus control.
            wanted = settled.dataset.focus;
            acceptAutofocus = true;
            settled = undefined;
        } else if (holding && !lost && active !== holding) {
            // The viewer moved on while the page loaded; leave them there.
            settled = active!;
            holding = undefined;
            return;
        }
        const target = find();
        if (target) {
            target.focus({ preventScroll: true });
            // A successor (the loaded page's Play after the loading one) can sit lower
            // on the page than the control it replaces; keep it on screen too.
            target.scrollIntoView({ block: "nearest", inline: "nearest" });
            if (first && entry.scroll !== undefined)
                window.scrollTo(0, entry.scroll);
            first = false;
            settled = target;
            holding = undefined;
            return;
        }
        if (lost) {
            holding = pageHeading(main);
            holding.focus({ preventScroll: true });
        }
    };
    const observer = new MutationObserver(restore);
    observer.observe(main, { childList: true, subtree: true });
    restore();
    return () => observer.disconnect();
}
