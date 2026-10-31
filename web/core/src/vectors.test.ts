/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
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
import {
    decodeSettings,
    validateBitrate,
    validateMaxStreamingBitrate,
    defaultStream,
    captionLanguage,
    captionIndex,
    userConfiguration,
    endPosition,
    activeSegment,
    nextTrigger,
    nextAdjacency,
    imageUrl,
    chapterImageUrl,
    trickplaySheetUrl,
    protocolDecision,
    decisionProjection,
    seekRequired,
    seekStart,
    seekUrl,
    audioEncodes,
    preserveVideo,
    urlProjection,
    ParityCatalogClient,
    playbackRequest,
} from "./parity";
import { referenceProfile, referenceCompatibility } from "./reference-profile";
const spec =
    process.env.PICTURESHOW_SPEC ??
    fileURLToPath(new URL("../../../spec", import.meta.url));
const files = readdirSync(spec + "/vectors", { recursive: true })
    .filter((p) => String(p).endsWith(".json"))
    .map((p) => String(p));
const ids = new Set<string>();
// Full Media3 profile fixtures are evaluated as pure reference-policy translations.
// Runtime browser playback uses its own actual MIME/MSE/native capability probes.
// Passing these inputs is not a claim that browsers render Android formats.
for (const file of files) {
    const suite = JSON.parse(readFileSync(spec + "/vectors/" + file, "utf8"));
    if (
        suite.schemaVersion !== 1 ||
        !["reference", "application", "recovery"].includes(suite.suite) ||
        !Array.isArray(suite.cases)
    )
        throw new Error("Unsupported vector envelope: " + file);
    for (const c of suite.cases) {
        if (ids.has(c.id)) throw new Error("Duplicate vector ID: " + c.id);
        ids.add(c.id);
    }
    const runnable = suite.cases;
    describe("shared " + file, () => {
        for (const c of runnable)
            it(c.id, async () => {
                const i = c.input,
                    ctx = suite.context;
                let value: unknown;
                switch (c.operation) {
                    case "profile":
                        value = referenceProfile(i.capabilities);
                        break;
                    case "compatibility":
                        value = referenceCompatibility(
                            referenceProfile(i.capabilities),
                            i.combo,
                        );
                        break;
                    case "request":
                        value = playbackRequest(
                            ctx.account.userId,
                            referenceProfile(i.capabilities),
                            i,
                            i.capabilities.settings?.maxStreamingBitrate ??
                                200000000,
                        );
                        break;
                    case "address":
                        try {
                            value = { urls: addressCandidates(i.value) };
                        } catch {
                            value = { error: "IllegalArgumentException" };
                        }
                        break;
                    case "authorization":
                        value = authorization(
                            i.deviceId ?? ctx.identity.deviceId,
                            i.token,
                            i.deviceName ?? ctx.identity.deviceName,
                            i.version ?? ctx.identity.version,
                        );
                        break;
                    case "sameOrigin":
                        value = sameOrigin(ctx.server.url, i.url);
                        break;
                    case "resolve":
                        value = resolve(ctx.server.url, i.url);
                        break;
                    case "stripApiKey":
                        value = stripApiKey(i.url);
                        break;
                    case "image":
                        value = imageUrl(
                            ctx.server.url,
                            i.item,
                            i.width,
                            i.height,
                            i.wide,
                        );
                        break;
                    case "chapterImage":
                        value = chapterImageUrl(
                            ctx.server.url,
                            i.itemId,
                            i.index,
                            i.tag,
                        );
                        break;
                    case "trickplaySheet":
                        value = trickplaySheetUrl(
                            ctx.server.url,
                            i.itemId,
                            i.mediaSourceId,
                            i.width,
                            i.sheet,
                        );
                        break;
                    case "decision":
                        value = decisionProjection(
                            protocolDecision(
                                ctx,
                                i.itemId,
                                i.response,
                                i.mediaSourceId,
                            ),
                        );
                        break;
                    case "ticks":
                        value = ticksFromNanoseconds(i.nanoseconds);
                        break;
                    case "reports": {
                        const d = {
                            itemId: "film",
                            source: { Id: "source", LiveStreamId: "live" },
                            method: i.method,
                            url: "",
                            playSessionId: "session",
                        };
                        value = {
                            progress: progressReport(
                                d,
                                i.positionMs / 1000,
                                i.paused,
                                i.audioStreamIndex,
                                i.subtitleStreamIndex,
                                i.canSeek,
                            ),
                            stopped: stopReport(
                                d,
                                i.positionMs / 1000,
                                i.failed,
                            ),
                        };
                        break;
                    }
                    case "validateBitrate":
                        try {
                            value = validateMaxStreamingBitrate(i.value);
                        } catch {
                            value = { error: "IllegalArgumentException" };
                        }
                        break;
                    case "settings":
                        value = decodeSettings(i);
                        break;
                    case "validate-bitrate":
                        value = { valid: validateBitrate(i.bitrateMbps) };
                        break;
                    case "default-stream":
                        value = defaultStream(
                            i.streams,
                            i.type,
                            i.serverIndex,
                            i.preferredLanguage,
                        );
                        break;
                    case "caption-language":
                        value = captionLanguage(
                            i.enabled,
                            i.configuration,
                            i.tvLanguage,
                        );
                        break;
                    case "caption-index":
                        value = captionIndex(i.source, i.preferredLanguage);
                        break;
                    case "resume-start":
                        value = resumeStartMs(
                            i.ticks,
                            i.resume,
                            i.explicitPositionMs,
                        );
                        break;
                    case "user-configuration":
                        value = userConfiguration(i.fields, i.change);
                        break;
                    case "end-position":
                        value = endPosition(i.positionMs, i.durationMs);
                        break;
                    case "active-segment":
                        value =
                            activeSegment(i.segments, i.positionMs, i.modes)
                                ?.Id ?? null;
                        break;
                    case "next-trigger":
                        value = nextTrigger(i.durationMs, i.segments);
                        break;
                    case "next-adjacency":
                        value = nextAdjacency(i.item, i.items)?.Id ?? null;
                        break;
                    case "seek-required":
                        value = seekRequired(i.decision);
                        break;
                    case "seek-start":
                        value = seekStart(i.decision, i.targetMs);
                        break;
                    case "seek-url":
                        value = urlProjection(seekUrl(i.decision, i.startMs));
                        break;
                    case "audio-encodes":
                        value = audioEncodes(i.decision);
                        break;
                    case "preserve-video": {
                        const d = preserveVideo(
                            i.decision,
                            i.subtitleStreamIndex,
                        );
                        value =
                            "unplayable" in d
                                ? d
                                : {
                                      ...decisionProjection(d),
                                      url: urlProjection(d.url),
                                  };
                        break;
                    }
                    case "prepare-preserve-video": {
                        let sent: any;
                        const client = new ParityCatalogClient(
                            ctx,
                            async (url, init) => {
                                expect(String(url)).toBe(
                                    resolve(
                                        ctx.server.url,
                                        "Items/film/PlaybackInfo",
                                    ),
                                );
                                expect(init?.method).toBe("POST");
                                if (sent)
                                    throw new Error(
                                        "Unexpected duplicate prepare",
                                    );
                                sent = JSON.parse(String(init?.body));
                                return new Response(
                                    JSON.stringify(i.response),
                                    { status: 200 },
                                );
                            },
                        );
                        // The operation's documented projection excludes platform DeviceProfile.
                        // Use a native-adapter slot, never Android decoder declarations.
                        const outcome = await client.prepare(
                            "film",
                            {},
                            i.request,
                            ctx.capabilities.settings.maxStreamingBitrate,
                        );
                        for (const key of [
                            "DeviceProfile",
                            "EnableTranscoding",
                            "AutoOpenLiveStream",
                            "AlwaysBurnInSubtitleWhenTranscoding",
                        ])
                            delete sent[key];
                        value = {
                            request: sent,
                            outcome:
                                "unplayable" in outcome
                                    ? outcome
                                    : {
                                          ...decisionProjection(outcome),
                                          url: urlProjection(outcome.url),
                                      },
                        };
                        break;
                    }
                    case "http": {
                        const requests: any[] = [],
                            responses = [...i.responses];
                        const client = new ParityCatalogClient(
                            ctx,
                            async (url, init) => {
                                const u = new URL(String(url));
                                requests.push({
                                    method: init?.method ?? "GET",
                                    path: u.pathname,
                                    query: urlProjection(u.href).query,
                                    authorization: new Headers(
                                        init?.headers,
                                    ).get("Authorization"),
                                    body: init?.body
                                        ? JSON.parse(String(init.body))
                                        : null,
                                });
                                const response = responses.shift();
                                if (!response)
                                    throw new Error("Unexpected extra request");
                                return new Response(
                                    response.status === 204
                                        ? null
                                        : JSON.stringify(response.body),
                                    { status: response.status },
                                );
                            },
                        );
                        let result: any = { kind: "success" };
                        try {
                            let detail: any;
                            switch (i.action) {
                                case "prepare": {
                                    const outcome = await client.prepare(
                                        i.itemId,
                                        referenceProfile(i.capabilities),
                                        i,
                                        i.capabilities.settings
                                            ?.maxStreamingBitrate ?? 200000000,
                                    );
                                    result.outcome =
                                        decisionProjection(outcome);
                                    break;
                                }
                                case "catalog.views":
                                    await client.views();
                                    break;
                                case "catalog.resume":
                                    await client.resume();
                                    break;
                                case "catalog.nextUp":
                                    await client.nextUp(i.seriesId);
                                    break;
                                case "catalog.latest":
                                    await client.latest(i.parentId);
                                    break;
                                case "catalog.items":
                                    await client.items(i);
                                    break;
                                case "catalog.item":
                                    detail = await client.item(i.itemId);
                                    result.resumeTicks =
                                        detail.UserData
                                            ?.PlaybackPositionTicks ?? 0;
                                    break;
                                case "catalog.seasons":
                                    await client.seasons(i.seriesId);
                                    break;
                                case "catalog.episodes":
                                    await client.episodes(
                                        i.seriesId,
                                        i.seasonId,
                                    );
                                    break;
                                case "catalog.played":
                                    await client.played(i.itemId, i.value);
                                    break;
                                case "catalog.favorite":
                                    await client.favorite(i.itemId, i.value);
                                    break;
                                case "search":
                                    await client.search(i.kind, i.query);
                                    break;
                                case "personItems":
                                    await client.personItems(
                                        i.personId,
                                        i.startIndex,
                                        i.limit,
                                    );
                                    break;
                                case "stopEncoding":
                                    await client.stopEncoding(i.playSessionId);
                                    break;
                                case "reportStart":
                                    await client.report("Start", i.body);
                                    break;
                                case "reportProgress":
                                    await client.report("Progress", i.body);
                                    break;
                                case "reportStopped":
                                    await client.report("Stopped", i.body);
                                    break;
                                default:
                                    throw new Error(
                                        "Unknown HTTP action " + i.action,
                                    );
                            }
                        } catch (e: any) {
                            if (!e.kind) throw e;
                            result = { kind: "failure", errorKind: e.kind };
                        }
                        value = { requests, result };
                        break;
                    }
                    default:
                        throw new Error(
                            "Unimplemented portable operation: " + c.operation,
                        );
                }
                expect(value).toEqual(c.expected);
            });
    });
}
