/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// PLAY-28: the web and desktop player keyboard, shown by the ? sheet.
export const PLAYER_SHORTCUTS: ReadonlyArray<[string, string]> = [
    ["Space or K", "Play or pause"],
    ["Left and Right arrows, or J and L", "Back or forward 10 seconds"],
    ["F", "Full screen"],
    ["M", "Mute or unmute"],
    ["Enter", "Show the controls"],
    ["Escape", "Close a panel, hide the controls, then leave the player"],
    ["?", "This list"],
];
/**
 * PLAY-28: which player shortcut a key press means, given what has focus.
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
