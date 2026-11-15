/* Real-user review 2026-11-15: the words people read on errors and diagnostics.
 * SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, it, expect } from "vitest";
import {
    ClientError,
    Jellyfin,
    browserPlaybackSummary,
    errorCopy,
} from "./index";

const server = {
    id: "s",
    name: "Cinema",
    url: "https://jf.example",
    version: "10.10.7",
};
const reply = (status: number, body: unknown = {}) =>
    (async () =>
        new Response(JSON.stringify(body), {
            status,
        })) as unknown as typeof fetch;
const offline = (async () => {
    throw new TypeError("Failed to fetch");
}) as unknown as typeof fetch;
async function failure(p: Promise<unknown>) {
    try {
        await p;
    } catch (e) {
        return e as ClientError;
    }
    throw Error("expected a failure");
}

describe("sign-in errors", () => {
    it("a wrong password at sign-in says so, not that a sign-in expired", async () => {
        const e = await failure(
            new Jellyfin(server, "d", undefined, reply(401)).authenticate(
                "a",
                "b",
            ),
        );
        expect(e.kind).toBe("INVALID_CREDENTIALS");
        expect(e.message).toBe(
            "That username and password didn't work. Check them and try again.",
        );
        expect(e.message).not.toMatch(/expired|refused/i);
    });
    it("a 401 on a saved sign-in still reads as ended", async () => {
        const account = {
            server,
            user: { Id: "u", Name: "Viewer" },
            accessToken: "t",
            deviceId: "d",
        };
        const e = await failure(
            new Jellyfin(server, "d", account as never, reply(401)).request(
                "UserViews",
            ),
        );
        expect(e.kind).toBe("EXPIRED");
        expect(e.message).toBe("Your sign-in has ended. Sign in again.");
    });
});

describe("unreachable server errors", () => {
    it("connecting to an address nobody answers names the address and an example", async () => {
        const original = globalThis.fetch;
        globalThis.fetch = offline;
        try {
            const e = await failure(Jellyfin.probe("jf.example", "d"));
            expect(e.kind).toBe("NETWORK");
            expect(e.message).toBe(errorCopy.unreachableAddress);
            expect(e.message).toContain("http://myserver:8096");
            // The technical causes stay available, but out of the headline.
            expect(e.message).not.toMatch(/CORS|certificate/);
            expect(e.detail?.join(" ")).toMatch(/certificate/);
            expect(e.detail?.join(" ")).toMatch(/CORS/);
        } finally {
            globalThis.fetch = original;
        }
    });
    it("a lost connection later is plain, with the causes as detail", async () => {
        const e = await failure(
            new Jellyfin(server, "d", undefined, offline).request(
                "System/Info/Public",
            ),
        );
        expect(e.message).toBe(
            "Couldn't reach your server. Check your connection and try again.",
        );
        expect(e.detail).toBe(errorCopy.networkDetail);
    });
});

describe("Troubleshooting verdict", () => {
    const probe = (codecs: string[]) => (mime: string) =>
        codecs.some((c) => mime.includes(c));
    it("leads with a plain verdict and names formats people know", () => {
        const s = browserPlaybackSummary(
            probe(["avc1", "mp4a.40.2", "opus"]),
            true,
        );
        expect(s.verdict).toMatch(/^This browser can play most videos\./);
        expect(s.video).toEqual(["H.264"]);
        expect(s.audio).toEqual(["AAC", "Opus"]);
        expect(s.verdict).not.toMatch(/MIME|MSE|HLS|profile/);
    });
    it("says when the browser can't play converted streams", () => {
        expect(browserPlaybackSummary(probe(["avc1"]), false).verdict).toMatch(
            /can't play the server's converted streams/,
        );
    });
    it("says when H.264 is missing", () => {
        expect(browserPlaybackSummary(probe(["vp09"]), true).verdict).toMatch(
            /can't play H\.264/,
        );
        expect(browserPlaybackSummary(probe([]), false).verdict).toMatch(
            /can't play videos from your server/,
        );
    });
});
