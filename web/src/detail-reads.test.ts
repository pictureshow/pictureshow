/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { describe, expect, it } from "vitest";
import type { Item, Jellyfin } from "@pictureshow/core";
import {
    beginDetailRead,
    publishDetailRead,
    readCompletionDetail,
    detailReadConfirmedCompletion,
} from "./detail-reads";
const item = (id = "leaf", played = true): Item => ({
    Id: id,
    Name: "Fixture",
    Type: "Movie",
    UserData: { Played: played },
});
function client(user = "viewer", read = () => new Promise<Item>(() => {})) {
    return {
        server: { url: "https://fixture.invalid" },
        account: { user: { Id: user } },
        detail: read,
    } as unknown as Jellyfin;
}
describe("completion detail arbitration", () => {
    it("uses the newest confirmed same-account/item full read while the obsolete read is held", async () => {
        const api = client();
        const old = beginDetailRead();
        let settled = false;
        const waiting = readCompletionDetail(api, "leaf").then((value) => {
            settled = true;
            return value;
        });
        publishDetailRead(api, item(), old);
        publishDetailRead(client("other"), item(), beginDetailRead());
        publishDetailRead(api, item("other"), beginDetailRead());
        await Promise.resolve();
        expect(settled).toBe(false);
        const fresh = beginDetailRead(),
            value = item();
        publishDetailRead(api, value, fresh);
        expect(await waiting).toBe(value);
        expect(detailReadConfirmedCompletion(api, "leaf", fresh)).toBe(true);
        expect(detailReadConfirmedCompletion(api, "leaf", fresh)).toBe(false);
    });
    it("releases the obsolete wait for Played=false but does not claim the required mutation is confirmed", async () => {
        const api = client("second");
        const waiting = readCompletionDetail(api, "leaf");
        const fresh = beginDetailRead();
        publishDetailRead(api, item("leaf", false), fresh);
        expect((await waiting).UserData?.Played).toBe(false);
        expect(detailReadConfirmedCompletion(api, "leaf", fresh)).toBe(false);
    });
    it("rejects an actual completion read failure and detaches its observer", async () => {
        const api = client("failed", async () => {
            throw new Error("503");
        });
        await expect(readCompletionDetail(api, "leaf")).rejects.toThrow("503");
        const fresh = beginDetailRead();
        publishDetailRead(api, item(), fresh);
        expect(detailReadConfirmedCompletion(api, "leaf", fresh)).toBe(false);
    });
});
