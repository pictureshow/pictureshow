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

// Scrolls the page the least distance that shows all of `e` and its focus ring.
// Whole pixels with a margin: scrollIntoView's "nearest" can stop a fraction of a
// pixel short at the window's edge, leaving the control and its ring clipped.
function keepInView(e: HTMLElement) {
    const r = e.getBoundingClientRect(),
        margin = 8;
    if (r.top < margin) window.scrollBy(0, Math.floor(r.top - margin));
    else if (r.bottom > innerHeight - margin)
        window.scrollBy(
            0,
            Math.ceil(
                Math.min(r.bottom - innerHeight + margin, r.top - margin),
            ),
        );
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
        holding: HTMLElement | undefined,
        // Set by the viewer's first key, pointer, wheel or touch on the page. Until
        // then a control this function placed focus on is kept fully on screen when
        // content (a late error, a loaded row) is inserted above it (WCAG 2.4.11);
        // after it, the viewer's own scrolling and focus are never overridden.
        acted = false;
    const act = () => {
        acted = true;
    };
    const inputs = ["keydown", "pointerdown", "wheel", "touchstart"] as const;
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
            if (settled.isConnected && active === settled && !acted)
                keepInView(settled);
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
            // Back's saved scroll comes first, then the focused control is kept on
            // screen: restoring the scroll after it could leave focus below the fold.
            // A successor (the loaded page's Play after the loading one) can also sit
            // lower on the page than the control it replaces.
            if (first && entry.scroll !== undefined)
                window.scrollTo(0, entry.scroll);
            target.scrollIntoView({ block: "nearest", inline: "nearest" });
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
    for (const type of inputs)
        window.addEventListener(type, act, { capture: true, passive: true });
    restore();
    return () => {
        observer.disconnect();
        for (const type of inputs)
            window.removeEventListener(type, act, { capture: true });
    };
}
