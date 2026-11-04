/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { useEffect, useRef } from "react";
type SafeEntry = {
    route: Record<string, unknown>;
    focus?: string;
    scroll?: number;
};
// History carries navigation only: no credentials, Item DTOs or active viewing.
export function historyEntries(entries: SafeEntry[]): SafeEntry[] {
    return entries
        .filter((e) => e.route.kind !== "player")
        .map((e) => ({
            route: { ...e.route },
            focus: e.focus,
            scroll: e.scroll,
        }));
}
export function validateHistory(value: unknown): SafeEntry[] | undefined {
    if (!Array.isArray(value) || !value.length || value.length > 64) return;
    const roots = [
        "home",
        "libraries",
        "search",
        "settings",
        "accounts",
        "about",
        "device",
        "support",
        "licenses",
    ];
    for (const e of value) {
        if (!e?.route || typeof e.route.kind !== "string") return;
        if (e.route.kind === "detail") {
            if (
                typeof e.route.id !== "string" ||
                !/^[-a-zA-Z0-9_]{1,128}$/.test(e.route.id)
            )
                return;
        } else if (e.route.kind === "license") {
            if (
                !Number.isInteger(e.route.index) ||
                e.route.index < 0 ||
                e.route.index > 10000
            )
                return;
        } else if (!roots.includes(e.route.kind)) return;
        if (e.focus !== undefined && typeof e.focus !== "string") return;
        if (
            e.scroll !== undefined &&
            (!Number.isFinite(e.scroll) || e.scroll < 0)
        )
            return;
    }
    return value.map((e) => ({
        route:
            e.route.kind === "detail"
                ? { kind: "detail", id: e.route.id }
                : e.route.kind === "license"
                  ? { kind: "license", index: e.route.index }
                  : { kind: e.route.kind },
        ...(e.focus !== undefined ? { focus: e.focus } : {}),
        ...(e.scroll !== undefined ? { scroll: e.scroll } : {}),
    }));
}
export function useAppHistory<T extends SafeEntry>(
    scope: string | undefined,
    entries: T[],
    onEntries: (entries: T[]) => void,
    shouldHandleBack: () => boolean,
    handleBack: () => void,
) {
    const latest = useRef({
        scope,
        entries,
        onEntries,
        shouldHandleBack,
        handleBack,
    });
    latest.current = {
        scope,
        entries,
        onEntries,
        shouldHandleBack,
        handleBack,
    };
    const map = useRef(
            new Map<string, { entries: SafeEntry[]; parent?: string }>(),
        ),
        current = useRef<string | undefined>(undefined),
        counter = useRef(0),
        allowed = useRef(false),
        undo = useRef<(() => void) | undefined>(undefined),
        previousScope = useRef<string | undefined>(undefined);
    function publish(next: T[], replace = false, parentEntries?: T[]) {
        if (parentEntries && current.current) {
            const prior = map.current.get(current.current);
            if (prior) prior.entries = historyEntries(parentEntries);
        }
        if (!scope) return;
        const id = "pictureshow-" + Date.now() + "-" + ++counter.current,
            parent = replace
                ? map.current.get(current.current ?? "")?.parent
                : current.current,
            safe = historyEntries(next);
        map.current.set(id, { entries: safe, parent });
        current.current = id;
        history[replace ? "replaceState" : "pushState"](
            { pictureshow: true, scope, key: id, entries: safe },
            "",
        );
    }
    useEffect(() => {
        map.current.clear();
        current.current = undefined;
        previousScope.current = scope;
        allowed.current = false;
        undo.current = undefined;
        if (scope) publish(entries, true);
    }, [scope]);
    useEffect(() => {
        const pop = (event: PopStateEvent) => {
            const l = latest.current;
            if (!l.scope) return;
            if (undo.current) {
                const action = undo.current;
                undo.current = undefined;
                current.current = event.state?.key;
                queueMicrotask(action);
                return;
            }
            if (!allowed.current && l.shouldHandleBack()) {
                undo.current = l.handleBack;
                history.forward();
                return;
            }
            allowed.current = false;
            const state = event.state;
            if (state?.pictureshow && state.scope === l.scope) {
                const decoded = validateHistory(
                    map.current.get(state.key)?.entries ?? state.entries,
                );
                if (decoded) {
                    current.current = state.key;
                    l.onEntries(decoded as T[]);
                    return;
                }
            }
            l.onEntries([{ route: { kind: "home" } }] as unknown as T[]);
        };
        window.addEventListener("popstate", pop);
        return () => window.removeEventListener("popstate", pop);
    }, []);
    function back(fallback: () => void, desired?: T[]) {
        const entry = map.current.get(current.current ?? "");
        if (
            scope &&
            previousScope.current === scope &&
            entry?.parent &&
            map.current.has(entry.parent) &&
            (!desired ||
                JSON.stringify(
                    map.current.get(entry.parent)!.entries.map((e) => e.route),
                ) ===
                    JSON.stringify(historyEntries(desired).map((e) => e.route)))
        ) {
            allowed.current = true;
            history.back();
        } else {
            fallback();
        }
    }
    return { publish, back };
}
