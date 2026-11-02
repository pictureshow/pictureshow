/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { expect, it } from "vitest";
import { Jellyfin, type Source } from "./index";
import { captionIndex } from "./parity";
import { DetailReadyFeedback } from "./rc26";
import {
    DetailRequestOnceFeedback,
    detailRawReadAcknowledgement,
    visibilityPlay,
} from "./rc28";

it("rc28 explicit server captions outrank fallback language, flags and text codec", () => {
    const source = {
        DefaultSubtitleStreamIndex: 4,
        MediaStreams: [
            {
                Type: "Subtitle",
                Index: 2,
                Language: "eng",
                IsTextSubtitleStream: true,
                IsHearingImpaired: true,
            },
            {
                Type: "Subtitle",
                Index: 4,
                Language: "ara",
                Codec: "pgssub",
                IsForced: true,
            },
        ],
    };
    expect(captionIndex(source, "eng")).toBe(4);
    expect(captionIndex(source, "")).toBe(4);
    expect(
        captionIndex({ ...source, DefaultSubtitleStreamIndex: -1 }, "eng"),
    ).toBe(-1);
    expect(
        captionIndex({ ...source, DefaultSubtitleStreamIndex: 90 }, "eng"),
    ).toBe(2);
});

it("rc28 rejected Ready/request submissions retry until accepted and departure cancels Ready", () => {
    const ready = new DetailReadyFeedback();
    expect(
        ready.update(true, "primary", true, true, { presented: true }),
    ).toBeNull();
    expect(
        ready.update(false, "primary", true, true, { submitted: false }),
    ).toBe("Ready");
    expect(ready.update(false, "other", true)).toBeNull();
    expect(ready.update(false, "primary", true)).toBeNull();
    const request = new DetailRequestOnceFeedback();
    expect(request.update(1, true, false)).toBe(true);
    expect(request.update(1, true, true)).toBe(true);
    expect(request.update(1, true, true)).toBe(false);
    expect(request.update(2, true, true)).toBe(true);
});

it("rc28 lifecycle policy preserves pause latch even when background playback is permitted", () => {
    expect(visibilityPlay(true, false, false, false)).toBe(true);
    expect(visibilityPlay(true, true, false, false)).toBe(false);
    expect(visibilityPlay(true, true, true, true)).toBe(false);
    expect(visibilityPlay(true, false, false, true)).toBe(false);
});
it("rc28 acknowledgement compares raw data independently of object-key order", () => {
    expect(
        detailRawReadAcknowledgement(
            { Played: true, IsFavorite: false },
            { IsFavorite: false, Played: true },
        ),
    ).toBe(true);
    expect(
        detailRawReadAcknowledgement({ Played: false }, { Played: true }),
    ).toBe(false);
});

const server = {
    id: "s",
    name: "Fixture",
    url: "https://media.example",
    version: "12.0.0",
};
const base: Source = {
    Id: "source",
    Container: "mp4",
    SupportsDirectPlay: true,
    DefaultSubtitleStreamIndex: 2,
    MediaStreams: [
        { Type: "Video", Index: 0, Codec: "h264" },
        { Type: "Audio", Index: 1, Codec: "aac" },
        { Type: "Subtitle", Index: 2, Language: "eng", Codec: "subrip" },
        {
            Type: "Subtitle",
            Index: 4,
            Language: "ara",
            Codec: "pgssub",
            DeliveryMethod: "Encode",
        },
    ],
};
it.each([true, false])(
    "rc28 negotiated remembered image subtitle forces burn-in when initial URL supplied=%s",
    async (hasUrl) => {
        const bodies: any[] = [];
        const negotiated = { ...base, DefaultSubtitleStreamIndex: 4 };
        const transcode = {
            ...negotiated,
            TranscodingUrl: "/Videos/film/master.m3u8?SubtitleMethod=Encode",
        };
        const client = new Jellyfin(
            server,
            "device",
            {
                server,
                user: { Id: "viewer", Name: "Viewer" },
                deviceId: "device",
                accessToken: "fixture",
            },
            (async (_url: any, init: any) => {
                bodies.push(JSON.parse(init.body));
                return new Response(
                    JSON.stringify({
                        MediaSources: [
                            hasUrl || bodies.length === 2
                                ? transcode
                                : negotiated,
                        ],
                        PlaySessionId: "viewing",
                    }),
                );
            }) as typeof fetch,
        );
        const decision = await client.prepare(
            { Id: "film", Name: "Film", Type: "Movie", MediaSources: [base] },
            () => true,
            30,
        );
        expect(decision.method).toBe("Transcode");
        expect(decision.source.DefaultSubtitleStreamIndex).toBe(4);
        expect(bodies).toHaveLength(hasUrl ? 1 : 2);
        for (const body of bodies) {
            expect(body).not.toHaveProperty("SubtitleStreamIndex");
            expect(body).toMatchObject({
                UserId: "viewer",
                StartTimeTicks: 300000000,
            });
        }
        if (!hasUrl)
            expect(bodies[1]).toMatchObject({
                MediaSourceId: "source",
                EnableDirectPlay: false,
                DeviceProfile: { DirectPlayProfiles: [] },
            });
    },
);
