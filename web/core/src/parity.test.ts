/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, it, expect, vi } from "vitest";
import {
    nextTrigger,
    defaultStream,
    captionIndex,
    ParityCatalogClient,
    playbackRequest,
    validateMaxStreamingBitrate,
} from "./parity";
const context = {
    server: { url: "https://media.example/jellyfin" },
    identity: { deviceId: "installation", deviceName: "Web", version: "0.1.0" },
    account: {
        userId: "viewer",
        token: "viewer-only",
        deviceId: "viewer-device",
    },
};
describe("portable policy and native transport boundaries", () => {
    it("credits may start at zero without falling back to last window", () =>
        expect(
            nextTrigger(60000, [
                {
                    Id: "credits",
                    Type: "Outro",
                    StartTicks: 0,
                    EndTicks: 600000000,
                },
            ]),
        ).toBe(0));
    it("ISO language conversion covers browser locale beyond editable language choices", () =>
        expect(
            defaultStream(
                [
                    { Type: "Audio", Index: 1, Language: "eng" },
                    { Type: "Audio", Index: 2, Language: "pol" },
                ],
                "Audio",
                null,
                "pl-PL",
            ),
        ).toBe(2));
    it("bibliographic aliases affect caption override without rewriting default-audio fallback", () => {
        const streams = [
            { Type: "Audio", Index: 1, Language: "eng" },
            { Type: "Audio", Index: 2, Language: "fra" },
        ];
        expect(defaultStream(streams, "Audio", null, "fre")).toBe(1);
        expect(
            captionIndex(
                {
                    MediaStreams: [
                        { Type: "Subtitle", Index: 2, Language: "fra" },
                    ],
                },
                "fre",
            ),
        ).toBe(2);
    });
    it("positive bitrate must be finite", () => {
        expect(() => validateMaxStreamingBitrate(Infinity)).toThrow();
        expect(validateMaxStreamingBitrate(1)).toBe(1);
    });
    it("request preserves explicit off and copy flags without Android capability claims", () =>
        expect(
            playbackRequest(
                "viewer",
                { Name: "Browser" },
                {
                    startMs: 123,
                    subtitleStreamIndex: -1,
                    enableDirectStream: false,
                    allowAudioStreamCopy: false,
                },
                4000000,
            ),
        ).toEqual({
            UserId: "viewer",
            DeviceProfile: { Name: "Browser" },
            MaxStreamingBitrate: 4000000,
            StartTimeTicks: 1230000,
            SubtitleStreamIndex: -1,
            EnableDirectPlay: true,
            EnableDirectStream: false,
            EnableTranscoding: true,
            AutoOpenLiveStream: false,
            AlwaysBurnInSubtitleWhenTranscoding: false,
            AllowVideoStreamCopy: true,
            AllowAudioStreamCopy: false,
        }));
    it("blocks foreign request before viewer authentication leaves the client", async () => {
        const send = vi.fn();
        const client = new ParityCatalogClient(context, send);
        await expect(
            client.request("https://other.example/Items"),
        ).rejects.toMatchObject({ kind: "UNSAFE_STREAM_URL" });
        expect(send).not.toHaveBeenCalled();
    });
    it("keeps cookies and redirects out of private catalog transport", async () => {
        const send = vi.fn<typeof fetch>(
            async () => new Response('{"Items":[]}'),
        );
        await new ParityCatalogClient(context, send).views();
        expect(send.mock.calls[0]?.[1]).toMatchObject({
            credentials: "omit",
            redirect: "error",
            referrerPolicy: "no-referrer",
        });
    });
    it("maps offline failure without expiring credential", async () => {
        const client = new ParityCatalogClient(context, async () => {
            throw new TypeError("Offline");
        });
        await expect(client.views()).rejects.toMatchObject({ kind: "NETWORK" });
        expect(client.context.account.token).toBe("viewer-only");
    });
    it("unreadable catalog response is actionable", async () => {
        const client = new ParityCatalogClient(
            context,
            async () => new Response("not json"),
        );
        await expect(client.views()).rejects.toMatchObject({
            kind: "INVALID_RESPONSE",
        });
    });
    it("adjacency sends missing filter before selecting immediate successor", async () => {
        const send = vi.fn<typeof fetch>(
            async () =>
                new Response(
                    '{"Items":[{"Id":"current","Type":"Episode"},{"Id":"special","Type":"Episode","ParentIndexNumber":0}]}',
                ),
        );
        const next = await new ParityCatalogClient(context, send).adjacent({
            Id: "current",
            Type: "Episode",
            SeriesId: "series",
        });
        const url = new URL(String(send.mock.calls[0]?.[0]));
        expect(url.searchParams.get("isMissing")).toBe("false");
        expect(url.searchParams.get("adjacentTo")).toBe("current");
        expect(next.Id).toBe("special");
    });
});
