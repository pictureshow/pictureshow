/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import { playerShortcut } from "./player-keys";

const key = (k: string, mods: Partial<KeyboardEvent> = {}) => ({
    key: k,
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    ...mods,
});
const surface = {
    surface: true,
    control: false,
    editable: false,
    range: false,
};
const button = { surface: false, control: true, editable: false, range: false };
const slider = { surface: false, control: false, editable: true, range: true };
const text = { surface: false, control: false, editable: true, range: false };

describe("playerShortcut (PLAY-29)", () => {
    it("maps the documented keys on the picture or page", () => {
        expect(playerShortcut(key(" "), surface)).toBe("toggle");
        expect(playerShortcut(key("k"), surface)).toBe("toggle");
        expect(playerShortcut(key("K"), surface)).toBe("toggle");
        expect(playerShortcut(key("ArrowLeft"), surface)).toBe("back");
        expect(playerShortcut(key("ArrowRight"), surface)).toBe("forward");
        expect(playerShortcut(key("j"), surface)).toBe("back");
        expect(playerShortcut(key("l"), surface)).toBe("forward");
        expect(playerShortcut(key("f"), surface)).toBe("fullscreen");
        expect(playerShortcut(key("m"), surface)).toBe("mute");
        expect(playerShortcut(key("?"), surface)).toBe("help");
    });
    it("leaves Space and arrows to a focused button, so they press and navigate", () => {
        expect(playerShortcut(key(" "), button)).toBeUndefined();
        expect(playerShortcut(key("ArrowRight"), button)).toBeUndefined();
        expect(playerShortcut(key("k"), button)).toBe("toggle");
        expect(playerShortcut(key("m"), button)).toBe("mute");
    });
    it("leaves arrows to a slider but still takes letters there", () => {
        expect(playerShortcut(key("ArrowLeft"), slider)).toBeUndefined();
        expect(playerShortcut(key(" "), slider)).toBeUndefined();
        expect(playerShortcut(key("l"), slider)).toBe("forward");
    });
    it("never takes a key typed into a text field", () => {
        for (const k of ["k", "j", "l", "f", "m", "?", " "])
            expect(playerShortcut(key(k), text)).toBeUndefined();
    });
    it("never takes browser or system shortcuts", () => {
        expect(
            playerShortcut(key("f", { ctrlKey: true }), surface),
        ).toBeUndefined();
        expect(
            playerShortcut(key("m", { metaKey: true }), surface),
        ).toBeUndefined();
        expect(
            playerShortcut(key("l", { altKey: true }), surface),
        ).toBeUndefined();
    });
});
