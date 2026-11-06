/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */

type Dict = Record<string, any>;
export const controlSuites = new Set([
    "session-owner",
    "session-command",
    "queue-edit",
    "queue-order",
    "playback-mode",
]);

const MIN_I64 = -(1n << 63n);
const MAX_I64 = (1n << 63n) - 1n;
const INT64 = /^(?:0|-?[1-9][0-9]*)$/;
const obj = (value: unknown): value is Dict =>
    value !== null && typeof value === "object" && !Array.isArray(value);

function record(value: unknown, label: string): Dict {
    if (!obj(value)) throw new Error(`${label} must be an object`);
    return value;
}
function keys(value: unknown, allowed: string[], label: string) {
    const input = record(value, label),
        set = new Set(allowed);
    for (const key of Object.keys(input))
        if (!set.has(key)) throw new Error(`Unknown ${label} field: ${key}`);
    return input;
}
function string(value: unknown, label: string): string;
function string(value: unknown, label: string, nullable: true): string | null;
function string(value: unknown, label: string, nullable = false): string | null {
    if (nullable && value === null) return value;
    if (typeof value !== "string") throw new Error(`${label} must be a string`);
    return value;
}
function int64(value: unknown, label: string, negative = true): string {
    if (typeof value !== "string" || !INT64.test(value))
        throw new Error(`${label} must be a canonical signed 64-bit decimal string`);
    const n = BigInt(value);
    if (n < MIN_I64 || n > MAX_I64 || (!negative && n < 0n))
        throw new Error(`${label} is outside its signed 64-bit range`);
    return value;
}
function smallInteger(value: unknown, label: string, minimum = 0) {
    if (!Number.isInteger(value) || (value as number) < minimum)
        throw new Error(`${label} must be an integer >= ${minimum}`);
    return value as number;
}
function clone<T>(value: T): T {
    return structuredClone(value);
}
function distinct<T>(values: T[]) {
    return [...new Set(values)];
}
function same(a: unknown, b: unknown) {
    return JSON.stringify(a) === JSON.stringify(b);
}
function identity(value: unknown, label: string): Dict {
    const x = keys(
        value,
        ["server", "viewer", "device", "session", "generation", "kind"],
        label,
    );
    for (const field of ["server", "viewer", "device", "session", "kind"])
        string(x[field], `${label}.${field}`);
    int64(x.generation, `${label}.generation`, false);
    if (!["local", "jellyfin", "cast"].includes(x.kind))
        throw new Error(`${label}.kind is unknown`);
    return clone(x);
}
function sameIdentity(a: Dict | null, b: Dict | null) {
    return !!a && !!b &&
        ["server", "viewer", "device", "session", "generation", "kind"].every(
            (k) => a[k] === b[k],
        );
}
function intent(value: unknown = {}): Dict {
    const x = keys(
        value,
        ["entry", "ticks", "paused", "queue", "repeat", "shuffle", "audio", "subtitle"],
        "intent",
    );
    const out: Dict = {
        entry: x.entry ?? null,
        ticks: int64(x.ticks ?? "0", "intent.ticks", false),
        paused: x.paused ?? true,
        queue: clone(x.queue ?? []),
        repeat: x.repeat ?? "off",
        shuffle: clone(x.shuffle ?? []),
        audio: x.audio ?? null,
        subtitle: x.subtitle ?? null,
    };
    if (out.entry !== null) string(out.entry, "intent.entry");
    if (typeof out.paused !== "boolean") throw new Error("intent.paused must be boolean");
    if (!Array.isArray(out.queue) || out.queue.some((x: unknown) => typeof x !== "string"))
        throw new Error("intent.queue must be a string array");
    if (!Array.isArray(out.shuffle) || out.shuffle.some((x: unknown) => typeof x !== "string"))
        throw new Error("intent.shuffle must be a string array");
    if (!["off", "one", "all"].includes(out.repeat)) throw new Error("intent.repeat is unknown");
    for (const field of ["audio", "subtitle"])
        if (out[field] !== null) smallInteger(out[field], `intent.${field}`, -1);
    return out;
}

const identityFields = ["server", "viewer", "device", "session", "generation", "kind"];
const intentFields = ["entry", "ticks", "paused", "queue", "repeat", "shuffle", "audio", "subtitle"];
const queueFields = [
    "server", "viewer", "entries", "order", "history", "operations", "failed", "current",
    "positionTicks", "revision", "generation", "completion", "paused", "repeat", "shuffled", "visited",
 "automatic",];
const queueEntryFields = ["id", "media", "downloaded"];

function validateQueueInput(value: unknown, label: string) {
    const q = keys(value, queueFields, label);
    for (const field of ["server", "viewer"])
        if (q[field] !== undefined) string(q[field], `${label}.${field}`);
    for (const field of ["positionTicks", "revision", "generation", "completion"])
        if (q[field] !== undefined)
            int64(q[field], `${label}.${field}`, field === "positionTicks" ? false : false);
    for (const field of ["entries"]) {
        if (q[field] === undefined) continue;
        if (!Array.isArray(q[field])) throw new Error(`${label}.${field} must be an array`);
        for (const [index, item] of q[field].entries()) {
            const entry = keys(item, queueEntryFields, `${label}.${field}[${index}]`);
            string(entry.id, `${label}.${field}[${index}].id`);
            string(entry.media, `${label}.${field}[${index}].media`);
            if (entry.downloaded !== undefined && typeof entry.downloaded !== "boolean")
                throw new Error(`${label}.${field}[${index}].downloaded must be boolean`);
        }
    }
    for (const field of ["order", "history", "operations", "failed", "visited", "automatic"])
        if (q[field] !== undefined && (!Array.isArray(q[field]) || q[field].some((x: unknown) => typeof x !== "string")))
            throw new Error(`${label}.${field} must be a string array`);
    if (q.current !== undefined && q.current !== null) string(q.current, `${label}.current`);
    if (q.paused !== undefined && typeof q.paused !== "boolean") throw new Error(`${label}.paused must be boolean`);
    if (q.shuffled !== undefined && typeof q.shuffled !== "boolean") throw new Error(`${label}.shuffled must be boolean`);
    if (q.repeat !== undefined && !["off", "one", "all"].includes(q.repeat))
        throw new Error(`${label}.repeat is unknown`);
    return q;
}

function normalizeQueue(value: unknown): Dict {
    const q = validateQueueInput(value, "queue");
    const entries = clone(q.entries ?? []),
        order = clone(q.order ?? entries.map((e: Dict) => e.id)),
        history = clone(q.history ?? []);
    for (const field of ["entries", "order", "history", "operations", "failed", "visited", "automatic"])
        if (!Array.isArray(q[field] ?? (field === "entries" ? entries : field === "order" ? order : field === "history" ? history : [])))
            throw new Error(`queue.${field} must be an array`);
    const seen = new Set<string>();
    for (const e of entries) {
        if (seen.has(e.id)) throw new Error("Queue entry IDs must be unique");
        seen.add(e.id);
    }
    return {
        server: q.server ?? "",
        viewer: q.viewer ?? "",
        entries,
        order,
        history,
        operations: clone(q.operations ?? []),
        failed: clone(q.failed ?? []),
        current: q.current ?? null,
        positionTicks: int64(q.positionTicks ?? "0", "queue.positionTicks", false),
        revision: int64(q.revision ?? "0", "queue.revision", false),
        generation: int64(q.generation ?? "0", "queue.generation", false),
        completion: int64(q.completion ?? "0", "queue.completion", false),
        paused: q.paused ?? true,
        repeat: q.repeat ?? "off",
        shuffled: q.shuffled ?? false,
        visited: clone(q.visited ?? distinct(history)),
        automatic: clone(q.automatic ?? []),
    };
}

function defaultState(operation: string, input: unknown): Dict {
    if (operation === "owner") {
        const s = keys(input, ["owner", "local", "candidate", "phase", "generation", "intent", "route", "reportSequence", "effect", "reason"], "owner state");
        const owner = identity(s.owner, "owner state.owner"),
            generation = int64(s.generation ?? owner.generation, "owner state.generation", false);
        return {
            owner,
            local: s.local === undefined ? (owner.kind === "local" ? clone(owner) : null) : identity(s.local, "owner state.local"),
            candidate: s.candidate == null ? null : identity(s.candidate, "owner state.candidate"),
            phase: s.phase ?? "active",
            generation,
            intent: intent(s.intent),
            route: s.route ?? "native",
            reportSequence: int64(s.reportSequence ?? "-1", "owner state.reportSequence"),
            effect: s.effect ?? "none",
            reason: s.reason ?? null,
        };
    }
    if (operation === "temporary") {
        const s = keys(input, ["records", "effect", "reason"], "temporary state"),
            records = clone(s.records ?? []);
        if (!Array.isArray(records)) throw new Error("temporary state.records must be an array");
        for (const [i, r] of records.entries()) {
            const entry = keys(r, ["server", "viewer", "device", "sender", "generation", "phase", "secretRef"], `record[${i}]`);
            for (const f of ["server", "viewer", "device", "sender", "phase"]) string(entry[f], `record[${i}].${f}`);
            int64(entry.generation, `record[${i}].generation`, false);
            if (entry.secretRef !== null) string(entry.secretRef, `record[${i}].secretRef`);
        }
        return { records, effect: s.effect ?? "none", reason: s.reason ?? null };
    }
    if (operation === "command") {
        const s = keys(input, ["owner", "observed", "durationTicks", "authorized", "fresh", "capabilities", "sequence", "seekTarget", "pending", "effect", "rejection"], "command state"),
            owner = identity(s.owner, "command state.owner"),
            observed = keys(s.observed ?? {}, ["entry", "ticks", "paused"], "observed");
        if (observed.entry !== undefined && observed.entry !== null) string(observed.entry, "observed.entry");
        if (observed.paused !== undefined && typeof observed.paused !== "boolean") throw new Error("observed.paused must be boolean");
        if (s.capabilities !== undefined && (!Array.isArray(s.capabilities) || s.capabilities.some((x: unknown) => typeof x !== "string")))
            throw new Error("command state.capabilities must be a string array");
        return {
            owner,
            observed: { entry: observed.entry ?? null, ticks: int64(observed.ticks ?? "0", "observed.ticks", false), paused: observed.paused ?? true },
            durationTicks: int64(s.durationTicks ?? "9223372036854775807", "durationTicks", false),
            authorized: s.authorized ?? true,
            fresh: s.fresh ?? true,
            capabilities: clone(s.capabilities ?? ["play", "pause", "seek", "stop"]),
            sequence: int64(s.sequence ?? "0", "command state.sequence", false),
            seekTarget: s.seekTarget == null ? null : int64(s.seekTarget, "seekTarget", false),
            pending: s.pending == null ? null : clone(s.pending),
            effect: s.effect ?? "none",
            rejection: s.rejection ?? null,
        };
    }
    if (operation === "queue") return normalizeQueue(input);
    if (operation === "store") {
        const s = keys(input, ["committed", "pending", "retry", "ticket", "effect", "reason", "removed"], "store state");
        return {
            committed: normalizeQueue(s.committed),
            pending: s.pending == null ? null : normalizeQueue(s.pending),
            retry: s.retry == null ? null : normalizeQueue(s.retry),
            ticket: int64(s.ticket ?? "0", "store ticket", false),
            effect: s.effect ?? "none",
            reason: s.reason ?? null,
            removed: clone(s.removed ?? []),
        };
    }
    if (operation === "transfer") {
        const s = keys(input, ["queue", "intent", "effect", "unavailable"], "transfer state");
        return { queue: normalizeQueue(s.queue), intent: intent(s.intent), effect: s.effect ?? "none", unavailable: clone(s.unavailable ?? []) };
    }
    if (operation === "mode") {
        const s = keys(input, ["ownerKind", "media", "explicitAudio", "group", "requestedRoute", "leaving", "effect"], "mode state");
        return {
            ownerKind: s.ownerKind ?? "local",
            media: s.media ?? "video",
            explicitAudio: s.explicitAudio ?? false,
            group: s.group ?? null,
            requestedRoute: s.requestedRoute ?? null,
            leaving: s.leaving ?? false,
            effect: s.effect ?? "none",
        };
    }
    throw new Error(`Unknown control operation: ${operation}`);
}

function ownerEvent(state: Dict, event: Dict): Dict {
    const op = event.op;
    if (op === "replace" || op === "viewer-switch") {
        const next = identity(event.identity, "owner event.identity");
        if (BigInt(next.generation) <= BigInt(state.generation))
            return { ...state, effect: "none", reason: "stale-generation" };
        return {
            owner: next,
            local: next.kind === "local" ? clone(next) : state.local,
            candidate: null,
            phase: "active",
            generation: next.generation,
            intent: intent(),
            route: "native",
            reportSequence: "-1",
            effect: "none",
            reason: null,
        };
    }
    if (op === "report") {
        const who = identity(event.identity, "owner event.identity"),
            sequence = int64(event.sequence, "owner event.sequence", false);
        if (!sameIdentity(who, state.owner))
            return { ...state, effect: "none", reason: "stale-report" };
        if (!(["active", "preparing"].includes(state.phase)) || BigInt(sequence) <= BigInt(state.reportSequence))
            return { ...state, effect: "none", reason: "stale-report" };
        return { ...state, reportSequence: sequence, effect: "report", reason: null };
    }
    if (op === "airplay" || op === "native-route") {
        const who = identity(event.identity, "owner event.identity");
        if (!sameIdentity(who, state.owner) || state.owner.kind !== "local")
            return { ...state, effect: "none" };
        return { ...state, route: op === "airplay" ? "airplay" : "native", effect: "none", reason: null };
    }
    if (op === "select") {
        const candidate = identity(event.identity, "owner event.identity");
        if (
            candidate.server !== state.owner.server ||
            candidate.viewer !== state.owner.viewer ||
            candidate.kind === "local" ||
            BigInt(candidate.generation) <= BigInt(state.generation)
        )
            return { ...state, candidate: null, effect: "none", reason: "invalid-target" };
        return { ...state, candidate, intent: intent(event.intent ?? state.intent), phase: "selecting", effect: "none", reason: null };
    }
    if (["connect", "prepare", "local-paused", "ready", "fail", "timeout", "cancel"].includes(op)) {
        const who = identity(event.identity, "owner event.identity");
        if (!sameIdentity(who, state.candidate)) return { ...state, effect: "none", reason: "not-ready" };
        if (op === "connect" && state.phase === "selecting") return { ...state, phase: "connecting", effect: "none", reason: null };
        if (op === "prepare" && state.phase === "connecting") return { ...state, phase: "preparing", effect: "pause-local", reason: null };
        if (op === "local-paused" && state.phase === "preparing") return { ...state, phase: "awaiting-ready", effect: "request-remote", reason: null };
        if (op === "ready" && state.phase === "awaiting-ready" && int64(event.ticks, "ready.ticks", false) === state.intent.ticks) {
            return { ...state, owner: clone(state.candidate), generation: state.candidate.generation, phase: "active", candidate: null, effect: "none", reason: null };
        }
        if (["fail", "timeout", "cancel"].includes(op) && ["selecting", "connecting", "preparing", "awaiting-ready"].includes(state.phase))
            return { ...state, candidate: null, phase: "failed", intent: { ...state.intent, paused: true }, effect: "resume-here", reason: null };
        return { ...state, effect: "none", reason: "not-ready" };
    }
    if (op === "disconnect") {
        const who = identity(event.identity, "owner event.identity");
        if (!sameIdentity(who, state.owner)) return { ...state, effect: "none" };
        return { ...state, phase: "disconnected", effect: "refresh", reason: null };
    }
    if (op === "return") {
        const who = identity(event.identity, "owner event.identity");
        if (!sameIdentity(who, state.owner) || state.owner.kind === "local") return { ...state, effect: "none" };
        return { ...state, phase: "returning", effect: "stop-remote", reason: null };
    }
    if (op === "remote-stopped") {
        const who = identity(event.identity, "owner event.identity");
        if (!sameIdentity(who, state.owner) || state.phase !== "returning") return { ...state, effect: "none" };
        return event.confirmed
            ? { ...state, phase: "return-ready", effect: "resume-here", reason: null }
            : { ...state, phase: "return-uncertain", effect: "require-choice", reason: null };
    }
    if (op === "resume-here") {
        const local = identity(event.identity, "owner event.identity");
        if (local.kind !== "local" || BigInt(local.generation) <= BigInt(state.generation))
            return { ...state, effect: "none", reason: "stale-generation" };
        if (state.phase === "return-uncertain" && event.confirmed !== true)
            return { ...state, effect: "none", reason: "explicit-choice-required" };
        if (!["failed", "return-ready", "return-uncertain"].includes(state.phase))
            return { ...state, effect: "none", reason: "not-ready" };
        return {
            ...state,
            owner: local,
            local: clone(local),
            generation: local.generation,
            candidate: null,
            phase: "active",
            intent: { ...state.intent, paused: false },
            route: "native",
            effect: "play-local",
            reason: null,
        };
    }
    throw new Error(`Unknown session-owner event operation: ${op}`);
}

function binding(value: unknown, label: string): Dict {
    const b = keys(value, ["server", "viewer", "device", "sender", "generation"], label);
    for (const field of ["server", "viewer", "device", "sender"]) string(b[field], `${label}.${field}`);
    int64(b.generation, `${label}.generation`, false);
    return b;
}
function sameBinding(a: Dict, b: Dict) {
    return ["server", "viewer", "device", "sender", "generation"].every((k) => a[k] === b[k]);
}
function temporaryEvent(state: Dict, event: Dict): Dict {
    const b = binding(event.binding, "temporary event.binding"),
        records = clone(state.records) as Dict[],
        index = records.findIndex((r) => sameBinding(r, b));
    const fail = (reason: string) => ({ ...state, effect: "none", reason });
    if (event.op === "create") {
        const exists = records.some((r) => r.server === b.server && r.viewer === b.viewer && r.device === b.device);
        if (exists) return fail("cleanup-required");
        records.push({ ...b, phase: "creating", secretRef: null });
        return { ...state, records, effect: "provision", reason: null };
    }
    if (index < 0) return fail("stale-binding");
    const r = records[index];
    if (event.op === "issued") {
        string(event.returnedViewer, "returnedViewer");
        if (r.phase === "cancelled-creating") {
            r.phase = "pending-persist";
            r.secretRef = event.secretRef ?? null;
            return { ...state, records, effect: "persist-revocation", reason: null };
        }
        if (r.phase !== "creating") return fail("cleanup-required");
        r.secretRef = event.secretRef ?? null;
        if (r.secretRef !== null) string(r.secretRef, "secretRef");
        if (event.returnedViewer !== r.viewer) {
            r.phase = "pending-persist";
            return { ...state, records, effect: "persist-revocation", reason: null };
        }
        r.phase = "active";
        return { ...state, records, effect: "use", reason: null };
    }
    if (event.op === "use")
        return r.phase === "active"
            ? { ...state, effect: "use", reason: null }
            : fail("cleanup-required");
    if (event.op === "end") {
        if (r.phase === "creating") {
            r.phase = "cancelled-creating";
            return { ...state, records, effect: "await-issue", reason: null };
        }
        if (r.phase === "active") {
            r.phase = "pending-persist";
            return { ...state, records, effect: "persist-revocation", reason: null };
        }
        return fail("cleanup-required");
    }
    if (event.op === "persisted" && r.phase === "pending-persist") {
        r.phase = "revoking";
        return { ...state, records, effect: "logout", reason: null };
    }
    if (event.op === "logout-failed" && r.phase === "revoking") {
        r.phase = "pending-revoke";
        return { ...state, records, effect: "pending", reason: null };
    }
    if (event.op === "reconnect" && ["pending-revoke", "revoking"].includes(r.phase)) {
        r.phase = "revoking";
        return { ...state, records, effect: "logout", reason: null };
    }
    if (event.op === "logout-ok" && ["revoking", "pending-revoke"].includes(r.phase)) {
        records.splice(index, 1);
        return { ...state, records, effect: "erase-secret", reason: null };
    }
    return fail("not-ready");
}

function boundedTicks(value: string, duration: string) {
    const n = BigInt(value), max = BigInt(duration);
    return (n < 0n ? 0n : n > max ? max : n).toString();
}
function commandEvent(state: Dict, event: Dict): Dict {
    const op = event.op,
        who = identity(event.owner, "command event.owner"),
        freshState = { ...state, effect: "none", rejection: null };
    if (!sameIdentity(who, state.owner))
        return { ...state, effect: "none", rejection: "stale-owner" };
    if (op === "intent") {
        if (!state.fresh) return { ...freshState, rejection: "refresh-required" };
        if (!state.authorized) return { ...freshState, rejection: "denied" };
        const value = string(event.value, "command intent.value"),
            capability =
                value === "play"
                    ? "play"
                    : value === "pause"
                      ? "pause"
                      : value === "stop"
                        ? "stop"
                        : value === "play-now"
                          ? "play-now"
                          : value === "seek-absolute" || value === "seek-relative"
                            ? "seek"
                            : null;
        if (!capability || !state.capabilities.includes(capability))
            return { ...freshState, rejection: "unsupported" };
        if (value === "play-now" && (typeof event.entry !== "string" || !event.entry))
            return { ...freshState, rejection: "missing-entry" };
        let id: string;
        try {
            id = int64(event.id, "command intent.id", false);
        } catch {
            return { ...freshState, rejection: "stale-command" };
        }
        if (BigInt(id) <= BigInt(state.sequence))
            return { ...freshState, rejection: "stale-command" };
        const sentAtMs = int64(event.nowMs ?? "0", "command intent.nowMs", false);
        let ticks: string | undefined,
            entry: string | null | undefined,
            paused: boolean | undefined,
            stopped: boolean | undefined;
        if (value === "seek-absolute" || value === "seek-relative") {
            let raw: string;
            try {
                raw = int64(event.entry, "seek ticks");
                if (value === "seek-absolute" && BigInt(raw) < 0n)
                    throw new Error("negative absolute seek");
            } catch {
                return { ...freshState, rejection: "invalid-ticks" };
            }
            if (value === "seek-relative") {
                const base = BigInt(state.seekTarget ?? state.observed.ticks),
                    delta = BigInt(raw), sum = base + delta;
                // BigInt holds the exact sum; clamping to [0,duration] has the
                // same result as saturating to Int64 first, then clamping.
                ticks = boundedTicks(sum.toString(), state.durationTicks);
            } else ticks = boundedTicks(raw, state.durationTicks);
            entry = state.observed.entry;
        } else if (value === "play-now") {
            entry = event.entry;
            ticks = "0";
            paused = false;
        } else if (value === "play") paused = false;
        else if (value === "pause") paused = true;
        else if (value === "stop") {
            paused = true;
            stopped = true;
        }
        const pending: Dict = { id, operation: value, result: "sent", sentAtMs };
        if (entry !== undefined && entry !== null) pending.entry = entry;
        if (ticks !== undefined) pending.ticks = ticks;
        if (paused !== undefined) pending.paused = paused;
        if (stopped !== undefined) pending.stopped = stopped;
        return {
            ...freshState,
            sequence: id,
            seekTarget: ticks === undefined ? state.seekTarget : ticks,
            pending,
            effect: "dispatch",
            rejection: null,
        };
    }
    if (op === "http") {
        const id = int64(event.id, "command http.id", false);
        return state.pending?.id === id && state.pending.result === "sent"
            ? freshState
            : { ...freshState, effect: "none" };
    }
    if (op === "timeout") {
        const id = int64(event.id, "command timeout.id", false),
            now = int64(event.nowMs, "command timeout.nowMs", false),
            p = state.pending;
        if (!p || p.id !== id || p.result !== "sent") return freshState;
        if (BigInt(now) - BigInt(p.sentAtMs ?? "0") < 5000n) return freshState;
        return { ...freshState, pending: { ...p, result: "unknown" }, effect: "refresh" };
    }
    if (op === "observe") {
        const id = int64(event.id, "command observe.id", false),
            p = state.pending;
        if (!p || p.id !== id || p.result !== "sent") return freshState;
        if (event.nowMs !== undefined) {
            const now = int64(event.nowMs, "command observe.nowMs", false);
            if (BigInt(now) - BigInt(p.sentAtMs ?? "0") >= 5000n)
                return { ...freshState, pending: { ...p, result: "unknown" }, effect: "refresh" };
        }
        const observedTicks = event.value === undefined
            ? state.observed.ticks
            : int64(event.value, "command observe.value", false);
        const observedEntry = event.entry === undefined ? state.observed.entry : event.entry;
        if (observedEntry !== null && observedEntry !== undefined) string(observedEntry, "command observe.entry");
        if (event.paused !== undefined && typeof event.paused !== "boolean")
            throw new Error("command observe.paused must be boolean");
        const observedPaused = event.paused ?? state.observed.paused;
        const matches =
            (p.entry === undefined || observedEntry === p.entry) &&
            (p.ticks === undefined || observedTicks === p.ticks) &&
            (p.paused === undefined || observedPaused === p.paused) &&
            (!p.stopped || event.stopped === true);
        const observed = { entry: observedEntry ?? null, ticks: observedTicks, paused: observedPaused };
        return matches
            ? { ...freshState, observed, pending: { ...p, result: "confirmed" } }
            : { ...freshState, observed };
    }
    if (op === "denied") {
        const p = state.pending;
        return {
            ...freshState,
            authorized: false,
            capabilities: [],
            pending: p ? { ...p, result: "rejected" } : null,
        };
    }
    if (op === "disconnect") {
        const p = state.pending;
        return {
            ...freshState,
            fresh: false,
            pending: p?.result === "sent" ? { ...p, result: "unknown" } : p,
            effect: "refresh",
        };
    }
    if (op === "refresh") {
        const observedTicks = int64(event.value ?? "0", "command refresh.value", false),
            entry = event.entry ?? null;
        if (entry !== null) string(entry, "command refresh.entry");
        if (event.paused !== undefined && typeof event.paused !== "boolean")
            throw new Error("command refresh.paused must be boolean");
        if (!Array.isArray(event.capabilities) || event.capabilities.some((x: unknown) => typeof x !== "string"))
            throw new Error("command refresh.capabilities must be a string array");
        return {
            ...freshState,
            observed: { entry, ticks: observedTicks, paused: event.paused ?? true },
            capabilities: clone(event.capabilities),
            authorized: event.authorized ?? state.authorized,
            fresh: true,
            effect: "none",
        };
    }
    throw new Error(`Unknown session-command event operation: ${op}`);
}

function increment(value: string) {
    const n = BigInt(value) + 1n;
    if (n > MAX_I64) throw new Error("64-bit control counter overflow");
    return n.toString();
}
function currentIndex(queue: Dict) {
    return queue.current === null ? -1 : queue.order.indexOf(queue.current);
}
function queueResult(queue: Dict, outcome: string, removed: string[] = []) {
    return { queue, outcome, removed };
}
function rejectQueue(queue: Dict, outcome: string) {
    return queueResult(queue, outcome);
}
function validNewEntries(queue: Dict, values: unknown, replace = false) {
    if (!Array.isArray(values) || values.length === 0) return null;
    const entries: Dict[] = [];
    const known = new Set<string>(replace ? [] : queue.entries.map((e: Dict) => e.id));
    for (const [i, value] of values.entries()) {
        const e = keys(value, queueEntryFields, `event.entries[${i}]`);
        if (typeof e.id !== "string" || !e.id || typeof e.media !== "string" || !e.media)
            return null;
        if (e.downloaded !== undefined && typeof e.downloaded !== "boolean") return null;
        if (known.has(e.id)) return null;
        known.add(e.id);
        entries.push({ id: e.id, media: e.media, downloaded: e.downloaded ?? false });
    }
    return entries;
}
function addOperation(queue: Dict, operationId: unknown) {
    if (typeof operationId !== "string" || !operationId) return queue;
    return { ...queue, operations: [...queue.operations, operationId] };
}
function updateRevision(queue: Dict) {
    return { ...queue, revision: increment(queue.revision) };
}
function invalidateCompletion(queue: Dict) {
    return { ...queue, completion: increment(queue.completion) };
}

function queueEditEvent(input: Dict, event: Dict): Dict {
    const q = clone(input),
        op = string(event.op, "queue event.op");
    if (event.operationId && q.operations.includes(event.operationId))
        return rejectQueue(q, "duplicate");
    const generation = int64(event.generation ?? "0", "queue event.generation", false),
        revision = int64(event.revision ?? "0", "queue event.revision", false);
    if (generation !== q.generation) return rejectQueue(q, "stale-generation");
    if (revision !== q.revision) return rejectQueue(q, "refresh-required");

    let next = q,
        outcome = "changed",
        removed: string[] = [];
    if (op === "play-now" || op === "play-next" || op === "append") {
        const block = (values: unknown) =>
            values === undefined || (Array.isArray(values) && values.length === 0) ? [] : validNewEntries(q, values, op === "play-now");
        const named = block(event.entries),
            automatic = block(event.automatic);
        if (!named || !automatic) return rejectQueue(q, "invalid-entries");
        const namedIds = named.map((e) => e.id),
            continuation = automatic.map((e) => e.id);
        if (namedIds.length + continuation.length === 0 || new Set([...namedIds, ...continuation]).size !== namedIds.length + continuation.length)
            return rejectQueue(q, "invalid-entries");
        if (op === "play-now") {
            if (namedIds.length === 0) return rejectQueue(q, "invalid-entries");
            next = {
                ...q,
                entries: [...named, ...automatic],
                order: [...namedIds, ...continuation],
                current: namedIds[0],
                positionTicks: "0",
                paused: false,
                history: [],
                visited: [],
                failed: [],
                automatic: continuation,
                shuffled: false,
            };
            next = invalidateCompletion(next);
        } else {
            const upcoming = new Set<string>(q.order.slice(currentIndex(q) + 1)),
                namedMedia = new Set(named.map((e: Dict) => e.media)),
                superseded = new Set<string>(
                    q.entries
                        .filter((e: Dict) => q.automatic.includes(e.id) && upcoming.has(e.id) && namedMedia.has(e.media))
                        .map((e: Dict) => e.id),
                ),
                remaining = q.automatic.filter((id: string) => !superseded.has(id)),
                ahead = new Set<string>(remaining.filter((id: string) => upcoming.has(id))),
                insertAt = (list: string[]) => {
                    if (q.current === null) return 0;
                    const at = list.findIndex((id) => ahead.has(id)),
                        beforeAutomatic = at < 0 ? list.length : at;
                    // Canonical order can hold current after the automatic entries while shuffled; unshuffle must still play this first.
                    return op === "play-next" ? Math.min(list.indexOf(q.current) + 1, beforeAutomatic) : beforeAutomatic;
                },
                order: string[] = q.order.filter((id: string) => !superseded.has(id));
            order.splice(insertAt(order), 0, ...namedIds);
            const canonical: Dict[] = q.entries.filter((e: Dict) => !superseded.has(e.id));
            canonical.splice(insertAt(canonical.map((e) => e.id)), 0, ...named);
            const present = new Set(canonical.map((e) => e.media)),
                fresh = automatic.filter((e: Dict) => {
                    if (present.has(e.media)) return false;
                    present.add(e.media);
                    return true;
                }),
                freshIds = fresh.map((e: Dict) => e.id);
            next = {
                ...q,
                entries: [...canonical, ...fresh],
                order: [...order, ...freshIds],
                automatic: [...remaining, ...freshIds],
                history: q.history.filter((id: string) => !superseded.has(id)),
                visited: q.visited.filter((id: string) => !superseded.has(id)),
                failed: q.failed.filter((id: string) => !superseded.has(id)),
                ...(q.current === null ? { positionTicks: "0", paused: true } : {}),
            };
        }
    } else if (op === "remove") {
        const id = string(event.entry, "queue remove.entry"),
            index = q.order.indexOf(id);
        if (index < 0) return rejectQueue(q, "invalid-entry");
        const wasCurrent = q.current === id,
            order = q.order.filter((x: string) => x !== id),
            entries = q.entries.filter((e: Dict) => e.id !== id),
            history = q.history.filter((x: string) => x !== id),
            visited = q.visited.filter((x: string) => x !== id),
            failed = q.failed.filter((x: string) => x !== id);
        removed = [id];
        next = { ...q, order, entries, history, visited, failed, automatic: q.automatic.filter((x: string) => x !== id) };
        if (wasCurrent) {
            const following = order[index] ?? order[order.length - 1] ?? null;
            next = {
                ...next,
                current: following,
                positionTicks: "0",
                paused: following === null ? true : q.paused,
                ...(following === null ? {} : { history: history.filter((x: string) => x !== following) }),
            };
            next = invalidateCompletion(next);
        } else if (q.history.includes(id)) {
            next = { ...next, history };
        }
    } else if (op === "move") {
        const id = string(event.entry, "queue move.entry"),
            from = q.entries.findIndex((e: Dict) => e.id === id),
            index = smallInteger(event.index, "queue move.index");
        if (from < 0 || index >= q.entries.length) return rejectQueue(q, "invalid-entry");
        if (from === index) return rejectQueue(q, "unchanged");
        const entries = [...q.entries], [entry] = entries.splice(from, 1);
        entries.splice(index, 0, entry);
        const order = q.shuffled ? [...q.order] : entries.map((e: Dict) => e.id);
        next = { ...q, entries, order };
    } else if (op === "clear-upcoming") {
        const keep = new Set<string>([...q.history, ...(q.current ? [q.current] : [])]);
        removed = q.order.filter((id: string) => !keep.has(id));
        const order = q.order.filter((id: string) => keep.has(id));
        next = { ...q, entries: q.entries.filter((e: Dict) => keep.has(e.id)), order, automatic: q.automatic.filter((id: string) => keep.has(id)) };
        if (!removed.length) outcome = "unchanged";
    } else if (op === "clear-all") {
        removed = q.entries.map((e: Dict) => e.id).filter((id: string) => id !== q.current);
        next = { ...q, entries: [], order: [], current: null, history: [], operations: [], failed: [], visited: [], automatic: [], positionTicks: "0", paused: true, shuffled: false };
        next = invalidateCompletion(next);
    } else if (op === "previous") {
        next = queuePrevious(q);
        if (next === q) outcome = "unchanged";
    } else if (["end", "next", "error"].includes(op)) {
        const completion = int64(event.completion ?? q.completion, `queue ${op}.completion`, false);
        if (completion !== q.completion) return rejectQueue(q, "duplicate-completion");
        const advanced = queueAdvance(q, op, event);
        return queueResult(advanced.queue, advanced.outcome, removed);
    } else if (op === "retry") {
        next = { ...q, failed: [], paused: false, positionTicks: "0" };
        next = invalidateCompletion(next);
    } else if (op === "play") {
        next = { ...q, paused: q.current === null };
    } else if (op === "pause") {
        next = { ...q, paused: true };
    } else if (op === "shuffle") {
        const permutation = event.permutation;
        if (!Array.isArray(permutation) || permutation.some((x: unknown) => typeof x !== "string"))
            return rejectQueue(q, "invalid-permutation");
        const current = currentIndex(q),
            prefix = current < 0 ? [] : q.order.slice(0, current + 1),
            upcoming = current < 0 ? q.order : q.order.slice(current + 1),
            expected = upcoming.filter((id: string) => !q.visited.includes(id));
        if (permutation.length !== expected.length || new Set(permutation).size !== permutation.length ||
            expected.some((id: string) => !permutation.includes(id)))
            return rejectQueue(q, "invalid-permutation");
        // Shuffle mixes within each group; what the viewer added still plays ahead of the automatic continuation.
        const added = permutation.filter((id: string) => !q.automatic.includes(id)),
            automatic = permutation.filter((id: string) => q.automatic.includes(id));
        next = { ...q, order: [...prefix, ...added, ...automatic], shuffled: true };
    } else if (op === "unshuffle") {
        const current = currentIndex(q),
            prefix = current < 0 ? [] : q.order.slice(0, current + 1),
            used = new Set<string>([...prefix, ...q.visited]),
            upcoming = q.entries.map((e: Dict) => e.id).filter((id: string) => !used.has(id));
        next = { ...q, order: [...prefix, ...upcoming], shuffled: false };
    } else return rejectQueue(q, "unsupported");

    if (outcome === "unchanged") return queueResult(q, outcome, removed);
    next = updateRevision(next);
    next = addOperation(next, event.operationId);
    return queueResult(next, outcome, removed);
}

function queuePrevious(q: Dict): Dict {
    const current = q.current,
        at = currentIndex(q),
        history = [...q.history];
    if (current === null) return q;
    const restart = BigInt(q.positionTicks) > 30_000_000n;
    let target: string = current;
    if (!restart) {
        if (history.length) target = history.pop()!;
        else if (at > 0) target = q.order[at - 1];
        else if (q.repeat === "all" && q.order.length) target = q.order[q.order.length - 1];
    }
    const targetIndex = q.order.indexOf(target),
        visited = targetIndex < 0 ? [] : q.order.slice(0, targetIndex);
    return {
        ...invalidateCompletion(q),
        current: target,
        history,
        visited: distinct(visited),
        positionTicks: "0",
    };
}

function queueAdvance(q: Dict, op: string, event: Dict): Dict {
    const original = q,
        completion = increment(q.completion),
        revision = increment(q.revision),
        current = q.current,
        at = currentIndex(q);
    if (current === null || at < 0)
        return { queue: { ...q, completion, revision, paused: true }, outcome: "stopped" };
    if (op === "error") {
        if (event.music === true) {
            const failed = distinct([...q.failed, current]),
                nextId = q.order.slice(at + 1).find((id: string) => !failed.includes(id));
            if (nextId) {
                const history = distinct([...q.history, current]),
                    visited = q.shuffled ? distinct([...q.visited, current]) : q.visited;
                return {
                    queue: { ...q, failed, current: nextId, positionTicks: "0", history, visited, completion, revision },
                    outcome: "skipped-unavailable",
                };
            }
            return { queue: { ...q, failed, paused: true, completion, revision }, outcome: "exhausted" };
        }
        return { queue: { ...q, paused: true, completion, revision }, outcome: "video-error" };
    }
    if (op === "end" && q.repeat === "one")
        return { queue: { ...q, positionTicks: "0", paused: false, completion, revision }, outcome: "changed" };
    let nextId = q.order[at + 1],
        wrap = false;
    if (!nextId && q.repeat === "all" && q.order.length) {
        nextId = q.order[0];
        wrap = true;
    }
    if (!nextId)
        return { queue: { ...q, paused: true, completion, revision }, outcome: "stopped" };
    const history = [...q.history, current];
    const visited = wrap ? [] : distinct([...q.visited, current]);
    return {
        queue: { ...q, current: nextId, positionTicks: "0", paused: false, history, visited, completion, revision },
        outcome: "changed",
    };
}

function storeEvent(state: Dict, event: Dict): Dict {
    if (event.op === "edit") {
        if (state.pending !== null)
            return { ...state, effect: "none", reason: "commit-pending" };
        const edit = record(event.edit, "store edit"),
            result = queueEditEvent(state.committed, edit);
        if (result.outcome === "changed" || result.outcome === "stopped" || result.outcome === "skipped-unavailable" || result.outcome === "exhausted" || result.outcome === "video-error") {
            const ticket = increment(state.ticket);
            return {
                ...state,
                ticket,
                pending: result.queue,
                retry: null,
                effect: "persist",
                reason: null,
                removed: result.removed,
            };
        }
        return { ...state, effect: "none", reason: result.outcome, removed: result.removed };
    }
    if (event.op === "persisted") {
        const ticket = int64(event.ticket, "store persisted.ticket", false);
        if (ticket !== state.ticket || state.pending === null)
            return { ...state, effect: "none", reason: "stale-ticket" };
        return { ...state, committed: state.pending, pending: null, retry: null, effect: "publish", reason: null };
    }
    if (event.op === "persist-failed") {
        const ticket = int64(event.ticket, "store persist-failed.ticket", false);
        if (ticket !== state.ticket || state.pending === null)
            return { ...state, effect: "none", reason: "stale-ticket" };
        return { ...state, pending: null, retry: state.pending, effect: "offer-retry", reason: null };
    }
    if (event.op === "retry-persist") {
        if (state.pending !== null) return { ...state, effect: "none", reason: "commit-pending" };
        if (state.retry === null) return { ...state, effect: "none", reason: "no-retry" };
        return { ...state, ticket: increment(state.ticket), pending: state.retry, retry: null, effect: "persist", reason: null };
    }
    if (event.op === "restore") {
        if (state.pending !== null) return { ...state, effect: "none", reason: "commit-pending" };
        const saved = normalizeQueue(event.saved);
        if (saved.server !== state.committed.server || saved.viewer !== state.committed.viewer)
            return { ...state, pending: null, effect: "none", reason: "wrong-partition" };
        if (!Array.isArray(event.accessible) || event.accessible.some((x: unknown) => typeof x !== "string"))
            throw new Error("restore.accessible must be a string array");
        const accessible = new Set<string>(event.accessible),
            offline = event.offline === true,
            keep = new Set<string>(),
            removed: string[] = [];
        for (const entry of saved.entries) {
            const usable = accessible.has(entry.id) && (!offline || entry.downloaded === true);
            if (usable) keep.add(entry.id);
            else removed.push(entry.id);
        }
        const entries = saved.entries.filter((e: Dict) => keep.has(e.id)),
            order = saved.order.filter((id: string) => keep.has(id)),
            current = saved.current && keep.has(saved.current) ? saved.current : order[0] ?? null,
            preservePosition = current === saved.current,
            pending = {
                ...saved,
                entries,
                order,
                current,
                history: saved.history.filter((id: string) => keep.has(id)),
                failed: saved.failed.filter((id: string) => keep.has(id)),
                visited: saved.visited.filter((id: string) => keep.has(id)),
                automatic: saved.automatic.filter((id: string) => keep.has(id)),
                positionTicks: preservePosition ? saved.positionTicks : "0",
                paused: true,
                generation: increment(state.committed.generation),
                revision: increment(state.committed.revision),
            };
        return { ...state, ticket: increment(state.ticket), pending, retry: null, effect: "persist", reason: null, removed };
    }
    if (event.op === "partition") {
        const server = string(event.server, "store partition.server"),
            viewer = string(event.viewer, "store partition.viewer"),
            generation = increment(state.committed.generation),
            ticket = increment(state.ticket);
        const committed = normalizeQueue({ server, viewer, generation, revision: "0", current: null, paused: true });
        return { ...state, committed, ticket, pending: null, retry: null, effect: "none", reason: null, removed: [] };
    }
    throw new Error(`Unknown store event operation: ${event.op}`);
}

function consistentTransfer(queue: Dict, selected: Dict) {
    const expectedShuffle = queue.shuffled ? queue.order : [];
    return (
        selected.entry === queue.current &&
        selected.ticks === queue.positionTicks &&
        selected.paused === queue.paused &&
        same(selected.queue, queue.order) &&
        selected.repeat === queue.repeat &&
        same(selected.shuffle, expectedShuffle)
    );
}
function transferEvent(state: Dict, event: Dict): Dict {
    if (event.op === "prepare") {
        if (!consistentTransfer(state.queue, state.intent))
            return { ...state, effect: "refresh", unavailable: [] };
        if (!Array.isArray(event.capabilities) || event.capabilities.some((x: unknown) => typeof x !== "string"))
            throw new Error("transfer capabilities must be a string array");
        const caps = new Set<string>(event.capabilities),
            missing: string[] = [];
        if (!caps.has("queue")) missing.push("queue");
        if (state.intent.repeat !== "off" && !caps.has("repeat")) missing.push("repeat");
        if (state.intent.shuffle.length && !caps.has("shuffle")) missing.push("shuffle");
        if (state.intent.audio !== null && (!caps.has("audio") || !event.matchingAudio?.includes(state.intent.audio))) missing.push("audio");
        if (state.intent.subtitle !== null && (!caps.has("subtitle") || !event.matchingSubtitles?.includes(state.intent.subtitle))) missing.push("subtitle");
        if (missing.length && event.confirmed !== true)
            return { ...state, effect: "require-replacement", unavailable: missing };
        const next = { ...state.intent };
        if (missing.includes("queue")) {
            next.entry = null;
            next.ticks = "0";
            next.paused = true;
            next.queue = [];
        }
        if (missing.includes("repeat")) next.repeat = "off";
        if (missing.includes("shuffle")) next.shuffle = [];
        if (missing.includes("audio")) next.audio = null;
        if (missing.includes("subtitle")) next.subtitle = null;
        return { ...state, intent: next, effect: "transfer", unavailable: missing };
    }
    if (event.op === "attach") {
        if (event.authoritative == null) return { ...state, effect: "refresh" };
        const authoritative = normalizeQueue(event.authoritative);
        if (authoritative.server !== state.queue.server || authoritative.viewer !== state.queue.viewer)
            return { ...state, effect: "reject-partition" };
        if (authoritative.current !== null && !authoritative.entries.some((e: Dict) => e.id === authoritative.current))
            return { ...state, effect: "refresh" };
        const nextIntent = {
            ...state.intent,
            entry: authoritative.current,
            ticks: authoritative.positionTicks,
            paused: authoritative.paused,
            queue: [...authoritative.order],
            repeat: authoritative.repeat,
            shuffle: authoritative.shuffled ? [...authoritative.order] : [],
            audio: null,
            subtitle: null,
        };
        return { ...state, queue: authoritative, intent: nextIntent, effect: "none", unavailable: [] };
    }
    throw new Error(`Unknown transfer event operation: ${event.op}`);
}

function modeEvent(state: Dict, event: Dict): Dict {
    if (event.op === "background") {
        const effect = state.ownerKind !== "local"
            ? "keep-receiver"
            : state.media === "audio" && state.explicitAudio
              ? "keep-audio"
              : "pause-video";
        return { ...state, effect };
    }
    if (event.op === "transport")
        return { ...state, effect: state.group ? "group-request" : "owner-command" };
    if (event.op === "route") {
        const route = string(event.route, "mode route.route");
        if (!["cast", "airplay"].includes(route)) throw new Error("mode route is unknown");
        return state.group
            ? { ...state, requestedRoute: route, effect: "require-leave" }
            : { ...state, requestedRoute: route, effect: "select-route" };
    }
    if (event.op === "cancel")
        return { ...state, requestedRoute: null, leaving: false, effect: "none" };
    if (event.op === "leave")
        return state.group
            ? { ...state, leaving: true, effect: "leave-group" }
            : { ...state, effect: "none" };
    if (event.op === "left") {
        if (!state.leaving || event.confirmed === false)
            return { ...state, effect: "none" };
        return { ...state, group: null, leaving: false, effect: state.requestedRoute ? "select-route" : "none" };
    }
    throw new Error(`Unknown playback-mode event operation: ${event.op}`);
}

const queueEventFields: Record<string, string[]> = {
    "play-now": ["op", "revision", "generation", "entries", "automatic", "operationId"],
    "play-next": ["op", "revision", "generation", "entries", "automatic", "operationId"],
    append: ["op", "revision", "generation", "entries", "automatic", "operationId"],
    remove: ["op", "revision", "generation", "entry", "operationId"],
    move: ["op", "revision", "generation", "entry", "index", "operationId"],
    "clear-upcoming": ["op", "revision", "generation", "operationId"],
    "clear-all": ["op", "revision", "generation", "operationId"],
    previous: ["op", "revision", "generation", "operationId"],
    end: ["op", "revision", "generation", "completion", "operationId"],
    next: ["op", "revision", "generation", "completion", "operationId"],
    error: ["op", "revision", "generation", "completion", "music", "operationId"],
    retry: ["op", "revision", "generation", "completion", "operationId"],
    play: ["op", "revision", "generation", "operationId"],
    pause: ["op", "revision", "generation", "operationId"],
    shuffle: ["op", "revision", "generation", "permutation", "operationId"],
    unshuffle: ["op", "revision", "generation", "operationId"],
};
const ownerEventFields: Record<string, string[]> = {
    replace: ["op", "identity"],
    "viewer-switch": ["op", "identity"],
    report: ["op", "identity", "sequence"],
    airplay: ["op", "identity"],
    "native-route": ["op", "identity"],
    select: ["op", "identity", "intent"],
    connect: ["op", "identity"],
    prepare: ["op", "identity"],
    "local-paused": ["op", "identity"],
    ready: ["op", "identity", "ticks"],
    fail: ["op", "identity"],
    timeout: ["op", "identity"],
    cancel: ["op", "identity"],
    disconnect: ["op", "identity"],
    return: ["op", "identity"],
    "remote-stopped": ["op", "identity", "confirmed"],
    "resume-here": ["op", "identity", "confirmed"],
};
const temporaryEventFields: Record<string, string[]> = {
    create: ["op", "binding"],
    issued: ["op", "binding", "returnedViewer", "secretRef"],
    use: ["op", "binding"],
    end: ["op", "binding"],
    persisted: ["op", "binding"],
    "logout-failed": ["op", "binding"],
    reconnect: ["op", "binding"],
    "logout-ok": ["op", "binding"],
};
const commandEventFields: Record<string, string[]> = {
    intent: ["op", "owner", "id", "nowMs", "value", "entry"],
    http: ["op", "owner", "id"],
    observe: ["op", "owner", "id", "nowMs", "entry", "value", "paused", "stopped"],
    timeout: ["op", "owner", "id", "nowMs"],
    denied: ["op", "owner"],
    disconnect: ["op", "owner"],
    refresh: ["op", "owner", "entry", "value", "paused", "authorized", "capabilities"],
};
const storeEventFields: Record<string, string[]> = {
    edit: ["op", "edit"],
    persisted: ["op", "ticket"],
    "persist-failed": ["op", "ticket"],
    "retry-persist": ["op"],
    restore: ["op", "saved", "accessible", "offline"],
    partition: ["op", "server", "viewer"],
};
const transferEventFields: Record<string, string[]> = {
    prepare: ["op", "capabilities", "confirmed", "matchingAudio", "matchingSubtitles"],
    attach: ["op", "authoritative"],
};
const modeEventFields: Record<string, string[]> = {
    background: ["op"],
    transport: ["op"],
    route: ["op", "route"],
    cancel: ["op"],
    leave: ["op"],
    left: ["op", "confirmed"],
};

function eventFor(operation: string, value: unknown): Dict {
    const event = record(value, `${operation} event`), op = string(event.op, `${operation} event.op`);
    let allowed: string[] | undefined;
    if (operation === "owner") allowed = ownerEventFields[op];
    else if (operation === "temporary") allowed = temporaryEventFields[op];
    else if (operation === "command") allowed = commandEventFields[op];
    else if (operation === "queue") allowed = queueEventFields[op];
    else if (operation === "store") allowed = storeEventFields[op];
    else if (operation === "transfer") allowed = transferEventFields[op];
    else if (operation === "mode") allowed = modeEventFields[op];
    if (!allowed) throw new Error(`Unknown ${operation} event operation: ${op}`);
    keys(event, allowed, `${operation} event ${op}`);
    if (event.identity !== undefined) identity(event.identity, `${operation} event.identity`);
    if (event.owner !== undefined) identity(event.owner, `${operation} event.owner`);
    if (event.binding !== undefined) binding(event.binding, `${operation} event.binding`);
    if (event.intent !== undefined) intent(event.intent);
    if (operation === "store" && op === "edit") {
        const nested = record(event.edit, "store event.edit");
        eventFor("queue", nested);
    }
    return event;
}

function operationForSuite(suite: string, operation: string) {
    const allowed: Record<string, string[]> = {
        "session-owner": ["owner", "temporary"],
        "session-command": ["command"],
        "queue-edit": ["queue", "store"],
        "queue-order": ["queue", "transfer"],
        "playback-mode": ["mode"],
    };
    if (!controlSuites.has(suite)) throw new Error(`Unknown control suite: ${suite}`);
    if (!allowed[suite].includes(operation))
        throw new Error(`Unknown ${suite} case operation: ${operation}`);
}

function applyEvent(operation: string, state: Dict, event: Dict): Dict {
    if (operation === "owner") return ownerEvent(state, event);
    if (operation === "temporary") return temporaryEvent(state, event);
    if (operation === "command") return commandEvent(state, event);
    if (operation === "queue") return queueEditEvent(state, event);
    if (operation === "store") return storeEvent(state, event);
    if (operation === "transfer") return transferEvent(state, event);
    if (operation === "mode") return modeEvent(state, event);
    throw new Error(`Unknown control operation: ${operation}`);
}

export function controlProjection(actual: unknown, expected: unknown, path = "expected"): unknown {
    if (obj(expected)) {
        if (!obj(actual)) throw new Error(`${path} projects fields from a non-object actual state`);
        const selected: Dict = {};
        for (const [field, value] of Object.entries(expected)) {
            if (!Object.hasOwn(actual, field))
                throw new Error(`Unknown ${path} projection field: ${field}`);
            selected[field] = controlProjection(actual[field], value, `${path}.${field}`);
        }
        return selected;
    }
    return actual;
}

export function runControlCase(suite: string, value: unknown) {
    const c = keys(value, ["id", "operation", "state", "steps"], "control case");
    string(c.id, "control case.id");
    const operation = string(c.operation, "control case.operation");
    operationForSuite(suite, operation);
    if (!Array.isArray(c.steps) || c.steps.length === 0)
        throw new Error(`Control case ${c.id} must have nonempty steps`);
    let state = defaultState(operation, c.state);
    const results: Array<{ actual: unknown; expected: unknown }> = [];
    for (const [index, raw] of c.steps.entries()) {
        const step = keys(raw, ["event", "expected"], `control case ${c.id} step ${index}`),
            expected = record(step.expected, `control case ${c.id} expected`);
        if (Object.keys(expected).length === 0)
            throw new Error(`Control case ${c.id} step ${index} has an empty expectation`);
        const event = eventFor(operation, step.event),
            actual = applyEvent(operation, state, event);
        results.push({ actual, expected });
        state = operation === "queue" ? actual.queue : actual;
    }
    return results;
}
