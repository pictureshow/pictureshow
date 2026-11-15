/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import {
    historyEntries,
    rootForPush,
    rootPress,
    validateHistory,
    withRoots,
} from "./history";
describe("TOUCH-02 root ownership", () => {
    it("a page belongs to the root it was opened under, not one guessed from focus", () => {
        const stack = [
            { route: { kind: "home" }, focus: "libraries:films" },
            { route: { kind: "detail", id: "films" } },
        ];
        expect(withRoots(stack).map((e) => e.root)).toEqual(["home", "home"]);
        expect(rootForPush(stack, { kind: "detail", id: "movie" })).toBe(
            "home",
        );
    });
    it("a root page owns itself wherever it was pushed", () => {
        const stack = [{ route: { kind: "home" } }];
        expect(rootForPush(stack, { kind: "settings" })).toBe("settings");
        expect(
            withRoots([
                { route: { kind: "home" } },
                { route: { kind: "settings" } },
                { route: { kind: "about" } },
            ]).map((e) => e.root),
        ).toEqual(["home", "settings", "settings"]);
    });
    it("a stored root survives history and is kept over inheritance", () => {
        const stack = [
            { route: { kind: "home" }, root: "home" as const },
            { route: { kind: "detail", id: "x" }, root: "libraries" as const },
        ];
        const decoded = validateHistory(historyEntries(stack))!;
        expect(decoded.map((e) => e.root)).toEqual(["home", "libraries"]);
        expect(rootForPush(decoded, { kind: "detail", id: "y" })).toBe(
            "libraries",
        );
    });
    it("history with an unknown root is refused", () => {
        expect(
            validateHistory([{ route: { kind: "home" }, root: "player" }]),
        ).toBeUndefined();
    });
    it("a press opens the root's top page, and is no change only at that top page", () => {
        const top = [{ route: { kind: "libraries" }, root: "libraries" }];
        expect(rootPress([{ route: { kind: "libraries" } }], "libraries")).toBe(
            undefined,
        );
        expect(
            rootPress(
                [
                    { route: { kind: "libraries" } },
                    { route: { kind: "detail", id: "x" } },
                ],
                "libraries",
            ),
        ).toEqual(top);
        expect(
            rootPress(
                [
                    { route: { kind: "home" } },
                    { route: { kind: "detail", id: "x" } },
                ],
                "libraries",
            ),
        ).toEqual(top);
    });
});
