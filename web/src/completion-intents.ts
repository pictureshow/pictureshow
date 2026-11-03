/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Item, Jellyfin } from "@pictureshow/core";
import { detailCompletionMayApply } from "@pictureshow/core/rc28";

// Provisional presentation is separate from the authoritative server snapshots.
// The account includes its token so a late operation cannot affect a new login.
const account = (client: Jellyfin) =>
    `${client.server.url}:${client.account!.user.Id}:${client.account!.accessToken}`;
const claims = new Map<
    string,
    { token: number; active: boolean; reportChoiceRevision: number }
>();
const choiceRevisions = new Map<string, number>();
const listeners = new Map<string, Set<() => void>>();
let sequence = 0;
const key = (client: Jellyfin, id: string) => `${account(client)}:${id}`;
function changed(client: Jellyfin) {
    for (const listener of listeners.get(account(client)) ?? []) listener();
}
export function subscribeCompletion(client: Jellyfin, listener: () => void) {
    const identity = account(client),
        set = listeners.get(identity) ?? new Set();
    set.add(listener);
    listeners.set(identity, set);
    return () => {
        set.delete(listener);
        if (!set.size) listeners.delete(identity);
    };
}
export function beginCompletion(client: Jellyfin, id: string) {
    const token = ++sequence;
    claims.set(key(client, id), {
        token,
        active: true,
        reportChoiceRevision: choiceRevisions.get(key(client, id)) ?? 0,
    });
    changed(client);
    return token;
}

/** Record an accepted Played/Unplayed choice before its queued write starts. */
export function recordPlayedChoice(client: Jellyfin, id: string) {
    const identity = key(client, id),
        claim = claims.get(identity);
    if (!claim) return 0;
    const revision =
        (choiceRevisions.get(identity) ?? claim.reportChoiceRevision) + 1;
    choiceRevisions.set(identity, revision);
    if (!detailCompletionMayApply(claim.reportChoiceRevision, revision)) {
        claims.delete(identity);
        choiceRevisions.delete(identity);
    }
    changed(client);
    return revision;
}

/** Only the completion report that captured this choice revision may apply. */
export function completionMayApply(
    client: Jellyfin,
    id: string,
    token: number,
) {
    const claim = claims.get(key(client, id));
    return (
        claim?.token === token &&
        detailCompletionMayApply(
            claim.reportChoiceRevision,
            choiceRevisions.get(key(client, id)) ?? 0,
        )
    );
}
export function finishCompletion(
    client: Jellyfin,
    id: string,
    token: number,
    succeeded = false,
) {
    const identity = key(client, id),
        claim = claims.get(identity);
    if (claim?.token !== token) return;
    if (succeeded) claim.active = false;
    else {
        claims.delete(identity);
        choiceRevisions.delete(identity);
    }
    changed(client);
}
// Called only for a complete, successful leaf/episode read, never a projection.
// An active completion/report owns presentation until its work has ended.
export function completionReadVersion() {
    return sequence;
}
export function settleCompletionRead(
    client: Jellyfin,
    item: Item,
    started = sequence,
) {
    const identity = key(client, item.Id),
        claim = claims.get(identity);
    if (claim && !claim.active && started >= claim.token) {
        claims.delete(identity);
        choiceRevisions.delete(identity);
        changed(client);
    }
}
export function projectCompletion<T extends Item>(
    client: Jellyfin,
    item: T,
): T {
    return claims.has(key(client, item.Id))
        ? { ...item, UserData: { ...item.UserData, Played: true } }
        : item;
}
