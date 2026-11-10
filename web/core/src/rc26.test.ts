/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { it, expect } from "vitest";
import { Jellyfin, type Account, type Decision, type Item } from "./index";
import {
    DetailReadyFeedback,
    seekStepTarget,
    playbackReportFields,
    type PlaybackHttpOutcome,
} from "./rc26";
const server = {
    id: "s",
    name: "Fixture",
    url: "https://media.example",
    version: "12.0.0",
};
const account: Account = {
    server,
    user: { Id: "viewer", Name: "Viewer" },
    deviceId: "device",
    accessToken: "fixture-token",
};
const source = {
    Id: "bound",
    Container: "mkv",
    MediaStreams: [{ Index: 0, Type: "Audio", Codec: "aac" }],
    TranscodingUrl: "/Videos/film/master.m3u8",
};
function clientWith(responses: { status: number; body?: unknown }[]) {
    const calls: { url: string; body: any }[] = [];
    const client = new Jellyfin(server, "device", account, (async (
        url: any,
        init: any,
    ) => {
        calls.push({
            url: String(url),
            body: init.body ? JSON.parse(init.body) : null,
        });
        const response = responses.shift();
        if (!response) throw new Error("Unexpected request");
        return new Response(
            response.status === 204 ? null : JSON.stringify(response.body),
            { status: response.status },
        );
    }) as typeof fetch);
    return { client, calls };
}
it("rc26 source discovery reapplies explicit Off bound to preferred source before opening media", async () => {
    const { client, calls } = clientWith([
        {
            status: 200,
            body: { MediaSources: [source], PlaySessionId: "discovery" },
        },
        {
            status: 200,
            body: {
                MediaSources: [{ ...source, DefaultSubtitleStreamIndex: -1 }],
                PlaySessionId: "selected",
            },
        },
    ]);
    const result = await client.prepare(
        { Id: "film", Name: "Film", Type: "Movie" },
        () => false,
        120,
        undefined,
        -1,
    );
    expect(calls).toHaveLength(2);
    expect(calls[0].body).not.toHaveProperty("MediaSourceId");
    expect(calls[1].body).toMatchObject({
        MediaSourceId: "bound",
        SubtitleStreamIndex: -1,
        StartTimeTicks: 1200000000,
    });
    expect(result.playSessionId).toBe("selected");
    expect(result.source.DefaultSubtitleStreamIndex).toBe(-1);
});
it("rc26 explicit source is retained when response changes source order", async () => {
    const item: Item = {
        Id: "film",
        Type: "Movie",
        Name: "Film",
        MediaSources: [{ ...source, Id: "other" }, source],
    };
    const { client, calls } = clientWith([
        {
            status: 200,
            body: {
                MediaSources: [{ ...source, Id: "other" }, source],
                PlaySessionId: "selected",
            },
        },
    ]);
    const result = await client.prepare(
        item,
        () => false,
        0,
        0,
        -1,
        false,
        null,
        "bound",
    );
    expect(calls[0].body).toMatchObject({
        MediaSourceId: "bound",
        AudioStreamIndex: 0,
        SubtitleStreamIndex: -1,
    });
    expect(result.source.Id).toBe("bound");
});
it("rc26 reports omit automatic tracks, preserve explicit Off and observe actual HTTP outcomes", async () => {
    const { client, calls } = clientWith([
        { status: 204 },
        { status: 204 },
        { status: 503, body: {} },
        { status: 204 },
    ]);
    const d: Decision = {
        itemId: "film",
        source: {
            ...source,
            DefaultAudioStreamIndex: 0,
            DefaultSubtitleStreamIndex: 2,
        },
        method: "Transcode",
        url: "https://media.example/video",
        playSessionId: "session",
    };
    const outcomes: PlaybackHttpOutcome[] = [];
    await client.report(
        d,
        "start",
        12,
        false,
        undefined,
        undefined,
        false,
        false,
        false,
        (o) => outcomes.push(o),
    );
    await client.report(
        d,
        "progress",
        13,
        true,
        0,
        -1,
        false,
        false,
        false,
        (o) => outcomes.push(o),
    );
    await expect(
        client.report(
            d,
            "progress",
            14,
            true,
            0,
            -1,
            false,
            false,
            false,
            (o) => outcomes.push(o),
        ),
    ).rejects.toMatchObject({ kind: "SERVER" });
    await client.report(d, "stop", 14, true, 0, -1, false, false, false, (o) =>
        outcomes.push(o),
    );
    expect(calls[0].body).not.toHaveProperty("AudioStreamIndex");
    expect(calls[0].body).not.toHaveProperty("SubtitleStreamIndex");
    expect(calls[1].body).toMatchObject({
        AudioStreamIndex: 0,
        SubtitleStreamIndex: -1,
    });
    expect(calls[3].body).not.toHaveProperty("AudioStreamIndex");
    expect(outcomes.map((o) => [o.status, o.failure])).toEqual([
        [204, null],
        [204, null],
        [503, "Http"],
        [204, null],
    ]);
    expect(
        outcomes.every(
            (o) => Number.isInteger(o.elapsedMs) && o.elapsedMs >= 0,
        ),
    ).toBe(true);
});
it("rc26 incomplete authoritative leaf projection cannot release detail actions", async () => {
    const { client } = clientWith([
        {
            status: 200,
            body: {
                Id: "film",
                Type: "Movie",
                Name: "Film",
                UserData: { Played: true },
            },
        },
        { status: 200, body: { Items: [{ Id: "other" }] } },
    ]);
    await expect(client.detail("film")).rejects.toMatchObject({
        kind: "INVALID_RESPONSE",
    });
});
it("rc26 seek steps accumulate on pending target and Ready consumes only its held focus episode", () => {
    let pending: number | null = null;
    for (let n = 0; n < 3; n++)
        pending = seekStepTarget(10000, pending, 10000, 120000);
    expect(pending).toBe(40000);
    expect(seekStepTarget(10000, pending, -10000, 120000)).toBe(30000);
    const feedback = new DetailReadyFeedback();
    expect(
        feedback.update(true, "primary", true, true, { presented: true }),
    ).toBe(null);
    expect(feedback.update(false, "primary", true, false)).toBe(null);
    expect(feedback.update(false, "primary", true, true)).toBe("Ready");
    expect(feedback.update(false, "primary", true, true)).toBe(null);
    expect(playbackReportFields("stop", 145000000, 0, -1, true, false)).toBe(
        "kind=stop positionMs=14500 audio=omitted subtitle=omitted audioOrigin=viewer subtitleOrigin=automatic",
    );
});

it.each([
    [new DOMException("Private transport text", "TimeoutError"), "Timeout"],
    [new DOMException("Private transport text", "AbortError"), "Cancelled"],
    [new TypeError("Private transport text"), "Network"],
] as const)(
    "rc26 report transport diagnostic exposes only fixed outcome %s",
    async (error, expected) => {
        const client = new Jellyfin(server, "device", account, (async () => {
            throw error;
        }) as typeof fetch);
        const outcomes: PlaybackHttpOutcome[] = [];
        await expect(
            client.request("Sessions/Playing", {}, "POST", false, (o) =>
                outcomes.push(o),
            ),
        ).rejects.toMatchObject({ kind: "NETWORK" });
        expect(outcomes).toHaveLength(1);
        expect(outcomes[0]).toMatchObject({ status: null, failure: expected });
        expect(JSON.stringify(outcomes)).not.toContain(
            "Private transport text",
        );
    },
);
it("rc26 malformed report response retains HTTP status with fixed decode failure", async () => {
    const client = new Jellyfin(
        server,
        "device",
        account,
        (async () => new Response("not json", { status: 200 })) as typeof fetch,
    );
    const outcomes: PlaybackHttpOutcome[] = [];
    await expect(
        client.request("Sessions/Playing", {}, "POST", false, (o) =>
            outcomes.push(o),
        ),
    ).rejects.toMatchObject({ kind: "INVALID_RESPONSE" });
    expect(outcomes[0]).toMatchObject({
        status: 200,
        failure: "InvalidResponse",
    });
});

it.each([
    ["TimeoutError", "Timeout"],
    ["AbortError", "Cancelled"],
] as const)(
    "rc26 report body %s retains received status and fixed outcome",
    async (name, expected) => {
        const error = new DOMException("Private response body text", name);
        const response = new Response("", { status: 200 });
        response.text = async () => {
            throw error;
        };
        const client = new Jellyfin(
            server,
            "device",
            account,
            (async () => response) as typeof fetch,
        );
        const outcomes: PlaybackHttpOutcome[] = [];
        await expect(
            client.request("Sessions/Playing", {}, "POST", false, (o) =>
                outcomes.push(o),
            ),
        ).rejects.toBe(error);
        expect(outcomes).toHaveLength(1);
        expect(outcomes[0]).toMatchObject({ status: 200, failure: expected });
        expect(JSON.stringify(outcomes)).not.toContain(
            "Private response body text",
        );
    },
);

it("rc26 text codec with Encode delivery negotiates burn-in instead of advertising direct playback", async () => {
    const metadata = {
        Id: "bound",
        Container: "mp4",
        SupportsDirectPlay: true,
        TranscodingUrl: "/Videos/film/master.m3u8?SubtitleMethod=Encode",
        MediaStreams: [
            { Index: 0, Type: "Video", Codec: "h264" },
            { Index: 1, Type: "Audio", Codec: "aac" },
            {
                Index: 2,
                Type: "Subtitle",
                Codec: "subrip",
                DeliveryMethod: "Encode",
            },
        ],
        DefaultAudioStreamIndex: 1,
    };
    const { client, calls } = clientWith([
        {
            status: 200,
            body: { MediaSources: [metadata], PlaySessionId: "burn-in" },
        },
    ]);
    const result = await client.prepare(
        { Id: "film", Type: "Movie", Name: "Film", MediaSources: [metadata] },
        () => true,
        0,
        undefined,
        2,
    );
    expect(calls[0].body).toMatchObject({
        MediaSourceId: "bound",
        SubtitleStreamIndex: 2,
        EnableDirectPlay: false,
    });
    expect(result.method).toBe("Transcode");
});
