/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { afterEach, describe, it, expect, vi } from "vitest";
import { readPreferences } from "../../src/settings";
import { readSaved, saveSaved, accountKey } from "../../src/accounts";
function storage() {
    const data = new Map<string, string>();
    return {
        getItem: (k: string) => data.get(k) ?? null,
        setItem: (k: string, v: string) => {
            data.set(k, v);
        },
        removeItem: (k: string) => {
            data.delete(k);
        },
    };
}
afterEach(() => vi.unstubAllGlobals());
describe("browser persistence boundaries", () => {
    it("malformed preferences use Android defaults and unsupported choices cannot grant capabilities", () => {
        const local = storage();
        vi.stubGlobal("localStorage", local);
        local.setItem("pictureshow.preferences", "not-json");
        expect(readPreferences().bitrateMbps).toBe(null);
        local.setItem(
            "pictureshow.preferences",
            JSON.stringify({
                bitrateMbps: 999,
                subtitleSize: "Huge",
                segments: { Intro: "No" },
                passthrough: "yes",
            }),
        );
        expect(readPreferences()).toMatchObject({
            bitrateMbps: null,
            passthrough: true,
            subtitleSize: "System",
            segments: { Intro: "Ask" },
            screenReader: true,
        });
    });
    it("unreadable account state is recoverable rather than silently discarded", () => {
        const local = storage();
        vi.stubGlobal("localStorage", local);
        vi.stubGlobal("sessionStorage", storage());
        local.setItem("pictureshow.accounts.v1", "bad-json");
        expect(() => readSaved()).toThrow();
        expect(local.getItem("pictureshow.accounts.v1")).toBe("bad-json");
    });
    it("legacy sign-in migrates locally and a failed commit leaves original state intact", () => {
        const local = storage(),
            session = storage();
        vi.stubGlobal("localStorage", local);
        vi.stubGlobal("sessionStorage", session);
        const account = {
            server: {
                id: "s",
                name: "Cinema",
                url: "https://media.example",
                version: "12.0.0",
            },
            user: { Id: "u", Name: "Viewer" },
            accessToken: "test-token",
            deviceId: "old-device",
        };
        session.setItem("pictureshow.account", JSON.stringify(account));
        const state = readSaved();
        expect(state.current).toBe(accountKey(account));
        saveSaved(state);
        expect(session.getItem("pictureshow.account")).toBe(null);
        expect(readSaved().accounts[0].accessToken).toBe("test-token");
        const saved = local.getItem("pictureshow.accounts.v1");
        local.setItem = () => {
            throw Error("Quota");
        };
        expect(() => saveSaved({ ...state, accounts: [] })).toThrow("Quota");
        expect(local.getItem("pictureshow.accounts.v1")).toBe(saved);
    });
});

import { historyEntries, validateHistory } from "../../src/history";
it("browser history never restores serialized player DTOs or routes from malformed IDs", () => {
    expect(
        historyEntries([
            { route: { kind: "home" } },
            {
                route: {
                    kind: "player",
                    item: { accessToken: "must-not-persist" },
                },
            },
        ]),
    ).toEqual([
        { route: { kind: "home" }, focus: undefined, scroll: undefined },
    ]);
    expect(
        validateHistory([{ route: { kind: "detail", id: "../secret" } }]),
    ).toBeUndefined();
    expect(
        validateHistory([{ route: { kind: "player", item: { Id: "stale" } } }]),
    ).toBeUndefined();
    expect(
        validateHistory([
            {
                route: {
                    kind: "detail",
                    id: "movie",
                    item: { accessToken: "must-not-revive" },
                },
            },
        ]),
    ).toEqual([{ route: { kind: "detail", id: "movie" } }]);
});

import { chunks } from "../../src/information";
it("readable license chunks preserve all original line breaks and text", () => {
    const text =
        "First notice\n\n" + "  Indented line\n".repeat(200) + "FINAL\n";
    const result = chunks(text);
    expect(result.length).toBeGreaterThan(1);
    expect(result.join("")).toBe(text);
});
