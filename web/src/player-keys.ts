/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// PLAY-29: the web and desktop player keyboard, shown by the ? sheet.
/** One key as drawn on a keycap, with its spoken name when the cap is a symbol. */
export type ShortcutKey = { cap: string; name?: string };
/**
 * The ? sheet: each row lists alternative key groups ("Space" or "K"); the keys
 * inside a group are pressed for its two directions (← back, → forward).
 */
export const PLAYER_SHORTCUTS: ReadonlyArray<{
    keys: ShortcutKey[][];
    action: string;
}> = [
    { keys: [[{ cap: "Space" }], [{ cap: "K" }]], action: "Play or pause" },
    {
        keys: [
            [
                { cap: "\u2190", name: "Left arrow" },
                { cap: "\u2192", name: "Right arrow" },
            ],
            [{ cap: "J" }, { cap: "L" }],
        ],
        action: "Back or forward 10 seconds",
    },
    { keys: [[{ cap: "F" }]], action: "Full screen" },
    { keys: [[{ cap: "M" }]], action: "Mute or unmute" },
    { keys: [[{ cap: "Enter" }]], action: "Show the controls" },
    {
        keys: [[{ cap: "Esc", name: "Escape" }]],
        action: "Close a panel, hide the controls, then leave the player",
    },
    { keys: [[{ cap: "?", name: "Question mark" }]], action: "This list" },
];
/**
 * PLAY-29: which player shortcut a key press means, given what has focus.
 * Space keeps its meaning on buttons and form controls; arrows keep moving
 * between focused controls and sliders, and seek only from the picture.
 */
export function playerShortcut(
    e: Pick<KeyboardEvent, "key" | "ctrlKey" | "metaKey" | "altKey">,
    target: {
        surface: boolean;
        control: boolean;
        editable: boolean;
        range: boolean;
    },
): "toggle" | "back" | "forward" | "fullscreen" | "mute" | "help" | undefined {
    if (e.ctrlKey || e.metaKey || e.altKey) return undefined;
    const typing = target.editable && !target.range;
    switch (e.key) {
        case " ":
            return target.surface && !target.control && !target.editable
                ? "toggle"
                : undefined;
        case "k":
        case "K":
            return typing ? undefined : "toggle";
        case "ArrowLeft":
        case "ArrowRight":
            return target.surface && !target.control && !target.editable
                ? e.key === "ArrowLeft"
                    ? "back"
                    : "forward"
                : undefined;
        case "j":
        case "J":
            return typing ? undefined : "back";
        case "l":
        case "L":
            return typing ? undefined : "forward";
        case "f":
        case "F":
            return typing ? undefined : "fullscreen";
        case "m":
        case "M":
            return typing ? undefined : "mute";
        case "?":
            return typing ? undefined : "help";
        default:
            return undefined;
    }
}
