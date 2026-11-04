/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import type { Item, Jellyfin } from "@pictureshow/core";

// A completed viewing can use a later authoritative leaf read for the same
// account and item. Failed, canceled and incomplete dependent reads never publish.
let sequence = 0;
const observers = new Map<string, Set<(ticket: number, item: Item) => void>>();
const confirmed = new Map<string, number>();
const key = (client: Jellyfin, item: string) =>
    `${client.server.url}:${client.account!.user.Id}:${item}`;
export function beginDetailRead() {
    return ++sequence;
}
export function publishDetailRead(
    client: Jellyfin,
    item: Item,
    ticket: number,
) {
    for (const observer of observers.get(key(client, item.Id)) ?? [])
        observer(ticket, item);
}
export function detailReadConfirmedCompletion(
    client: Jellyfin,
    item: string,
    ticket: number,
) {
    const identity = key(client, item);
    if (confirmed.get(identity) !== ticket) return false;
    confirmed.delete(identity);
    return true;
}
export async function readCompletionDetail(
    client: Jellyfin,
    id: string,
): Promise<Item> {
    const identity = key(client, id),
        started = beginDetailRead();
    let receive!: (ticket: number, item: Item) => void;
    const newer = new Promise<Item>((resolve) => {
        receive = (ticket, item) => {
            if (ticket <= started) return;
            // Only Played=true can already confirm the final report refresh.
            // A false result still releases the obsolete wait, but completion
            // must perform its required Played write and refresh afterwards.
            if (item.UserData?.Played) confirmed.set(identity, ticket);
            resolve(item);
        };
    });
    const listeners = observers.get(identity) ?? new Set();
    listeners.add(receive);
    observers.set(identity, listeners);
    try {
        return await Promise.race([client.detail(id), newer]);
    } finally {
        listeners.delete(receive);
        if (!listeners.size) observers.delete(identity);
    }
}

const catalogRevisions = new Map<string, number>();
const accountKey = (client: Jellyfin) =>
    `${client.server.url}:${client.account!.user.Id}`;
export function catalogAccountRevision(client: Jellyfin) {
    return catalogRevisions.get(accountKey(client)) ?? 0;
}
export function invalidateCatalogAccount(client: Jellyfin) {
    const account = accountKey(client);
    catalogRevisions.set(account, (catalogRevisions.get(account) ?? 0) + 1);
}
