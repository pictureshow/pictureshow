/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { readFileSync, existsSync } from "node:fs";
import { resolve as fsResolve } from "node:path";
import { describe, it, expect } from "vitest";
import {
    addressCandidates,
    authorization,
    sameOrigin,
    resolve,
    stripApiKey,
    resumeStartMs,
    ticksFromNanoseconds,
    progressReport,
    stopReport,
} from "./index";
const spec = process.env.PICTURESHOW_SPEC ?? fsResolve("../spec");
// Point PICTURESHOW_SPEC at the spec worktree until the parity branch is integrated.
if (existsSync(spec + "/vectors/network.json")) {
    const network = JSON.parse(
        readFileSync(spec + "/vectors/network.json", "utf8"),
    );
    const operations = [
        "address",
        "authorization",
        "sameOrigin",
        "resolve",
        "stripApiKey",
    ];
    describe("shared network vectors", () => {
        for (const c of network.cases.filter((c: any) =>
            operations.includes(c.operation),
        ))
            it(c.id, () => {
                const i = c.input;
                let result: unknown;
                if (c.operation === "address") {
                    if (c.expected.error) {
                        expect(() => addressCandidates(i.value)).toThrow();
                        return;
                    }
                    result = { urls: addressCandidates(i.value) };
                }
                if (c.operation === "authorization")
                    result = authorization(
                        i.deviceId ?? network.context.identity.deviceId,
                        i.token,
                        i.deviceName ?? network.context.identity.deviceName,
                        i.version ?? network.context.identity.version,
                    );
                if (c.operation === "sameOrigin")
                    result = sameOrigin(network.context.server.url, i.url);
                if (c.operation === "resolve")
                    result = resolve(network.context.server.url, i.url);
                if (c.operation === "stripApiKey") result = stripApiKey(i.url);
                expect(result).toEqual(c.expected);
            });
    });
    const app = JSON.parse(
        readFileSync(spec + "/vectors/application.json", "utf8"),
    );
    describe("shared resume-start vectors", () => {
        for (const c of app.cases.filter(
            (c: any) => c.operation === "resume-start",
        ))
            it(c.id, () =>
                expect(
                    resumeStartMs(
                        c.input.ticks,
                        c.input.resume,
                        c.input.explicitPositionMs,
                    ),
                ).toEqual(c.expected),
            );
    });
    const playback = JSON.parse(
        readFileSync(spec + "/vectors/playback.json", "utf8"),
    );
    describe("shared tick/report vectors", () => {
        for (const c of playback.cases.filter((c: any) =>
            ["ticks", "reports"].includes(c.operation),
        ))
            it(c.id, () => {
                if (c.operation === "ticks") {
                    expect(ticksFromNanoseconds(c.input.nanoseconds)).toBe(
                        c.expected,
                    );
                    return;
                }
                const i = c.input,
                    d = {
                        itemId: "film",
                        source: { Id: "source", LiveStreamId: "live" },
                        method: i.method,
                        url: "",
                        playSessionId: "session",
                    };
                expect({
                    progress: progressReport(
                        d,
                        i.positionMs / 1000,
                        i.paused,
                        i.audioStreamIndex,
                        i.subtitleStreamIndex,
                        i.canSeek,
                    ),
                    stopped: stopReport(d, i.positionMs / 1000, i.failed),
                }).toEqual(c.expected);
            });
    });
} else {
    throw new Error(
        "Set PICTURESHOW_SPEC to the parity spec directory, or integrate spec/ before running test:vectors.",
    );
}
