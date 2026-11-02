/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Item, Jellyfin } from "@pictureshow/core";

// Provisional presentation is separate from the authoritative server snapshots.
// The account includes its token so a late operation cannot affect a new login.
const account = (client: Jellyfin) =>
    `${client.server.url}:${client.account!.user.Id}:${client.account!.accessToken}`;
const claims = new Map<string, { token: number; active: boolean }>();
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
    claims.set(key(client, id), { token, active: true });
    changed(client);
    return token;
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
    else claims.delete(identity);
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
