/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// SYN-08 (operator, 2026-11-15): closing the player keeps this viewer in its group.
// Translated from core/src/commonMain/kotlin/app/pictureshow/core/sync/SyncStay.kt.

export type SyncCloseReason =
    | "BACK"
    | "CLOSE"
    | "STOP_BUTTON"
    | "ERROR_BACK"
    | "NATURAL_END"
    | "BACKGROUND_IDLE"
    | "BACKGROUND_RENDERING"
    | "EXTERNAL_ROUTE"
    | "MANUAL_LEAVE"
    | "SIGN_OUT";
export type SyncCloseOutcome = "NONE" | "STAY" | "LEAVE";
export type SyncDepartureCause =
    | "MANUAL_LEAVE"
    | "PLAYBACK_STOPPED"
    | "PLAYBACK_ENDED"
    | "PLAYBACK_CLOSED"
    | "EXTERNAL_ROUTE"
    | "MEMBERSHIP_LOST";
export interface SyncCloseDecision {
    outcome: SyncCloseOutcome;
    ignoreWait: boolean;
    departure: SyncDepartureCause | null;
}

const decision = (
    outcome: SyncCloseOutcome,
    ignoreWait = false,
    departure: SyncDepartureCause | null = null,
): SyncCloseDecision => ({ outcome, ignoreWait, departure });

export function syncCloseDecision(
    reason: SyncCloseReason,
    participating: boolean,
    following: boolean,
): SyncCloseDecision {
    if (!participating) return decision("NONE");
    switch (reason) {
        case "BACK":
        case "CLOSE":
        case "STOP_BUTTON":
        case "ERROR_BACK":
        case "NATURAL_END":
            return decision("STAY", following);
        case "BACKGROUND_IDLE":
            return decision("STAY");
        case "BACKGROUND_RENDERING":
            return decision("LEAVE", false, "MEMBERSHIP_LOST");
        case "EXTERNAL_ROUTE":
            return decision("LEAVE", false, "EXTERNAL_ROUTE");
        case "MANUAL_LEAVE":
            return decision("LEAVE", false, "MANUAL_LEAVE");
        case "SIGN_OUT":
            return decision("LEAVE");
        default:
            throw new Error("Unknown close reason: " + String(reason));
    }
}

export interface SyncStayFacts {
    groupId: string | null;
    groupName: string | null;
    phase: string;
    queueEntries: number;
    playerOpen: boolean;
    connected: boolean;
    leavePending: boolean;
}
export interface SyncStayReminder {
    groupName: string | null;
    rejoin: boolean;
    leaveUnconfirmed: boolean;
}

export function syncStayReminder(f: SyncStayFacts): SyncStayReminder | null {
    if (f.playerOpen) return null;
    const name =
        f.groupName !== null && f.groupName.trim() !== "" ? f.groupName : null;
    if (f.leavePending)
        return { groupName: name, rejoin: false, leaveUnconfirmed: true };
    if (f.groupId === null) return null;
    const rejoin =
        f.connected &&
        f.queueEntries > 0 &&
        f.phase !== "Error" &&
        f.phase !== "Reconnecting";
    return { groupName: name, rejoin, leaveUnconfirmed: false };
}

export const syncStayEndsOnDenial = (reason: string, playerOpen: boolean) =>
    !playerOpen && (reason === "NotInGroup" || reason === "GroupDoesNotExist");

export const syncOpensGroupEntry = (following: boolean) => following;

/** Strict vector runner: unknown operations throw rather than pass. */
export function runSyncStayVector(c: { op: string; input: any }): unknown {
    const i = c.input;
    switch (c.op) {
        case "close":
            return syncCloseDecision(i.reason, i.participating, i.following);
        case "reminder":
            return syncStayReminder(i);
        case "denial":
            return syncStayEndsOnDenial(i.reason, i.playerOpen);
        case "open":
            return syncOpensGroupEntry(i.following);
        default:
            throw new Error("Unknown sync-stay op: " + c.op);
    }
}
