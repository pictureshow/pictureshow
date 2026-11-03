/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import type { Item, Jellyfin } from "@pictureshow/core";
import {
    beginCompletion,
    completionReadVersion,
    completionMayApply,
    finishCompletion,
    projectCompletion,
    recordPlayedChoice,
    settleCompletionRead,
    subscribeCompletion,
} from "./completion-intents";
const client = (user: string, token = "token") =>
    ({
        server: { url: "https://fixture.invalid" },
        account: { user: { Id: user }, accessToken: token },
    }) as Jellyfin;
const raw: Item = {
    Id: "episode",
    Type: "Episode",
    Name: "Fixture",
    UserData: { Played: false, IsFavorite: true },
};
describe("owned completion presentation", () => {
    it("projects immediately without changing raw or favorite; failure withdraws it", () => {
        const api = client("failure"),
            token = beginCompletion(api, raw.Id);
        expect(projectCompletion(api, raw).UserData).toEqual({
            Played: true,
            IsFavorite: true,
        });
        expect(raw.UserData?.Played).toBe(false);
        finishCompletion(api, raw.Id, token);
        expect(projectCompletion(api, raw)).toBe(raw);
    });
    it("an old failure and ACK cannot clear the newer completion", () => {
        const api = client("overlap"),
            old = beginCompletion(api, raw.Id),
            newer = beginCompletion(api, raw.Id);
        finishCompletion(api, raw.Id, old);
        settleCompletionRead(api, raw);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(true);
        finishCompletion(api, raw.Id, newer, true);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(true);
        settleCompletionRead(api, raw);
        expect(projectCompletion(api, raw)).toBe(raw);
    });
    it("retains during active reads, then lets the next authoritative episode read win", () => {
        const api = client("settlement"),
            token = beginCompletion(api, raw.Id);
        settleCompletionRead(api, raw);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(true);
        finishCompletion(api, raw.Id, token, true);
        settleCompletionRead(api, raw);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(false);
    });
    it("a read begun before the new completion cannot settle its acknowledged projection", () => {
        const api = client("old-read");
        const started = completionReadVersion(),
            token = beginCompletion(api, raw.Id);
        finishCompletion(api, raw.Id, token, true);
        settleCompletionRead(api, raw, started);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(true);
        settleCompletionRead(api, raw, completionReadVersion());
        expect(projectCompletion(api, raw)).toBe(raw);
    });
    it("an accepted later watched choice retires only its report and a later report captures the new revision", () => {
        const api = client("choice-precedence"),
            first = beginCompletion(api, raw.Id);
        expect(completionMayApply(api, raw.Id, first)).toBe(true);
        expect(projectCompletion(api, raw).UserData?.Played).toBe(true);

        recordPlayedChoice(api, raw.Id);
        expect(completionMayApply(api, raw.Id, first)).toBe(false);
        expect(projectCompletion(api, raw)).toBe(raw);

        const next = beginCompletion(api, raw.Id);
        expect(completionMayApply(api, raw.Id, first)).toBe(false);
        expect(completionMayApply(api, raw.Id, next)).toBe(true);
        recordPlayedChoice(api, raw.Id);
        expect(completionMayApply(api, raw.Id, next)).toBe(false);
    });
    it("notifies only its account and isolates replaced credentials", () => {
        const api = client("owned"),
            other = client("other");
        let own = 0,
            foreign = 0;
        const off = subscribeCompletion(api, () => own++),
            offOther = subscribeCompletion(other, () => foreign++);
        const token = beginCompletion(api, raw.Id);
        expect(own).toBe(1);
        expect(foreign).toBe(0);
        expect(projectCompletion(client("owned", "replacement"), raw)).toBe(
            raw,
        );
        finishCompletion(api, raw.Id, token);
        expect(own).toBe(2);
        off();
        offOther();
    });
});
