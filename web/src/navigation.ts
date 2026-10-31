/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
export const focusable =
    'button:not([disabled]),input:not([disabled]),select:not([disabled]),a[href],[tabindex="0"]';
export function spatialKey(e: KeyboardEvent, back: () => void) {
    const active = document.activeElement as HTMLElement | null;
    const editing = active?.matches("input,textarea,select");
    if (e.key === "Escape" || (e.key === "Backspace" && !editing)) {
        e.preventDefault();
        back();
        return;
    }
    if (
        !e.key.startsWith("Arrow") ||
        active?.matches("textarea") ||
        (active?.matches("video,input[type=range]") &&
            (e.key === "ArrowLeft" || e.key === "ArrowRight")) ||
        (active?.matches("select") &&
            (e.key === "ArrowUp" || e.key === "ArrowDown")) ||
        (active?.matches("input:not([type=range])") &&
            (e.key === "ArrowLeft" || e.key === "ArrowRight"))
    )
        return;
    const scope = document.querySelector("dialog[open]") ?? document;
    const nodes = [...scope.querySelectorAll<HTMLElement>(focusable)].filter(
        (n) => n.getClientRects().length && !n.closest("[inert]"),
    );
    if (!active || !nodes.includes(active)) {
        nodes[0]?.focus();
        return;
    }
    const r = active.getBoundingClientRect(),
        x = r.x + r.width / 2,
        y = r.y + r.height / 2;
    const horizontal = e.key === "ArrowLeft" || e.key === "ArrowRight",
        sign = e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 1;
    const candidates = nodes
        .filter((n) => n !== active)
        .map((n) => {
            const t = n.getBoundingClientRect(),
                dx = t.x + t.width / 2 - x,
                dy = t.y + t.height / 2 - y;
            const along = horizontal ? dx : dy,
                cross = horizontal ? dy : dx;
            return {
                n,
                along: along * sign,
                cross: Math.abs(cross),
                score: Math.abs(along) + Math.abs(cross) * 3,
            };
        })
        .filter((t) => t.along > 2)
        .sort((a, b) => a.score - b.score);
    if (candidates[0]) {
        e.preventDefault();
        candidates[0].n.focus();
        candidates[0].n.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
}
