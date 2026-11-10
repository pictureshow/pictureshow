/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
    Jellyfin,
    ClientError,
    type Account,
    type Server,
    type Item,
} from "@pictureshow/core";
import { serverLabel } from "@pictureshow/core/parity";
import { spatialKey } from "./navigation";
import { installLastInput } from "./last-input";
import { Player } from "./player";
import { Home, Detail, Search, Libraries, type CatalogRoute } from "./catalog";
import {
    AccountChooser,
    Confirmation,
    ServerForm,
    SignIn,
    accountKey,
    newDevice,
    readSaved,
    saveSaved,
    type Saved,
} from "./accounts";
import {
    Settings,
    readPreferences,
    defaultPreferences,
    type Preferences,
} from "./settings";
import { Information, type InformationRoute } from "./information";
import "./style.css";
import "./shell.css";
import { useAppHistory, validateHistory } from "./history";
type Route =
    | CatalogRoute
    | InformationRoute
    | { kind: "settings" | "accounts" }
    | { kind: "player"; item: Item; start: number };
type Entry = { route: Route; focus?: string; scroll?: number };
function boot() {
    try {
        return {
            saved: readSaved(),
            error: "",
            preferences: readPreferences(),
        };
    } catch (e) {
        return {
            saved: undefined,
            error:
                e instanceof Error
                    ? e.message
                    : "Browser storage could not be read.",
            preferences: defaultPreferences,
        };
    }
}
function App() {
    const [initial] = useState(boot),
        [saved, setSaved] = useState<Saved | undefined>(initial.saved),
        [preferences, setPreferences] = useState<Preferences>(
            initial.preferences,
        ),
        [storageError, setStorageError] = useState(initial.error);
    const labelOf = (s: { id: string; name: string; url: string }) =>
        serverLabel(s, saved?.servers ?? []);
    const [account, setAccount] = useState(() =>
            initial.saved?.accounts.find(
                (a) => accountKey(a) === initial.saved?.current,
            ),
        ),
        [server, setServer] = useState<Server | undefined>(
            () =>
                initial.saved?.accounts.find(
                    (a) => accountKey(a) === initial.saved?.current,
                )?.server ?? initial.saved?.servers.at(-1),
        ),
        [username, setUsername] = useState(""),
        [chooser, setChooser] = useState(false),
        [stack, setStack] = useState<Entry[]>(() => {
            try {
                const k = initial.saved?.current;
                const entries = k
                    ? JSON.parse(
                          sessionStorage.getItem("pictureshow.routes." + k) ??
                              "null",
                      )
                    : null;
                const valid = validateHistory(entries);
                if (valid) return valid as Entry[];
            } catch {}
            return [{ route: { kind: "home" } }];
        }),
        [error, setError] = useState(""),
        [busy, setBusy] = useState(false),
        [restoring, setRestoring] = useState(!!initial.saved?.current),
        [verifyAttempt, setVerifyAttempt] = useState(0),
        [verified, setVerified] = useState(false),
        [generation, setGeneration] = useState(0),
        [returnBarrier, setReturnBarrier] = useState<Promise<void> | undefined>(
            undefined,
        ),
        [returnReportRetry, setReturnReportRetry] = useState<
            (() => Promise<void>) | undefined
        >(undefined),
        [confirmation, setConfirmation] = useState<{
            account?: Account;
            server?: Server;
            opener: HTMLElement | null;
        }>(),
        [confirmError, setConfirmError] = useState("");
    const device = useRef(
            (() => {
                try {
                    let id = sessionStorage.getItem("pictureshow.login.device");
                    if (!id) {
                        id = newDevice();
                        sessionStorage.setItem("pictureshow.login.device", id);
                    }
                    return id;
                } catch {
                    return newDevice();
                }
            })(),
        ),
        active = useRef(account);
    active.current = account;
    const client = useMemo(
        () =>
            new Jellyfin(
                server ?? { id: "", name: "", url: "", version: "" },
                account?.deviceId ?? device.current,
                account,
            ),
        [server, account],
    );
    const route = stack.at(-1)!.route;
    const rootEntry = [...stack].reverse().find((e) =>
        ["home", "libraries", "search", "settings"].includes(e.route.kind),
    );
    const navRoot =
        rootEntry?.route.kind === "home" && route.kind === "detail" &&
        rootEntry.focus?.startsWith("libraries:")
            ? "libraries"
            : rootEntry?.route.kind ?? "home";
    const player = !!account && route.kind === "player",
        roots = useRef<Record<string, Entry[]>>({});
    const appHistory = useAppHistory(
        account ? accountKey(account) : undefined,
        stack,
        (entries) => {
            setStack(entries);
            setError("");
        },
        () => player || !!confirmation,
        () => {
            if (confirmation && !busy) {
                document
                    .querySelector<HTMLDialogElement>("dialog[open]")
                    ?.close();
                const opener = confirmation.opener;
                setConfirmation(undefined);
                opener?.focus();
            } else if (player) {
                (
                    document.querySelector(".player-overlay dialog[open]") ??
                    document.querySelector(".player-overlay video") ??
                    window
                ).dispatchEvent(
                    new KeyboardEvent("keydown", {
                        key: "Escape",
                        bubbles: true,
                        cancelable: true,
                    }),
                );
            }
        },
    );
    function commit(next: Saved) {
        saveSaved(next);
        setSaved(next);
    }
    function fail(e: unknown) {
        setError(
            e instanceof Error ? e.message : "The request failed. Try again.",
        );
        if (
            e instanceof ClientError &&
            ["EXPIRED", "INVALID_CREDENTIALS"].includes(e.kind) &&
            client.account &&
            active.current?.accessToken === client.account.accessToken
        ) {
            const a = active.current;
            try {
                commit({
                    ...saved!,
                    accounts: saved!.accounts.filter(
                        (x) => x.accessToken !== a.accessToken,
                    ),
                    current: undefined,
                });
                setAccount(undefined);
                setUsername(a.user.Name);
                setServer(a.server);
                setChooser(false);
                setVerified(false);
                setRestoring(false);
                setError("Your saved sign-in has ended. Sign in again.");
            } catch {
                setError(
                    "Your sign-in ended, but browser storage could not update. Retry after browser storage is available.",
                );
            }
        }
    }
    function select(a: Account) {
        try {
            commit({ ...saved!, current: accountKey(a) });
            setAccount(a);
            setServer(a.server);
            setChooser(false);
            setStack([{ route: { kind: "home" } }]);
            setError("");
            setRestoring(true);
            setVerified(false);
            setVerifyAttempt((x) => x + 1);
            setGeneration((x) => x + 1);
        } catch {
            setError(
                "Could not save the selected account. Your previous account remains selected.",
            );
        }
    }
    function signedIn(a: Account) {
        try {
            const others = saved!.accounts.filter(
                (x) => accountKey(x) !== accountKey(a),
            );
            commit({
                ...saved!,
                accounts: [...others, a],
                servers: [
                    ...saved!.servers.filter((s) => s.id !== a.server.id),
                    a.server,
                ],
                current: accountKey(a),
            });
            setAccount(a);
            setServer(a.server);
            setChooser(false);
            setStack([{ route: { kind: "home" } }]);
            setRestoring(false);
            setVerified(true);
            setError("");
            setUsername("");
            setGeneration((x) => x + 1);
        } catch {
            setError(
                "Browser storage could not save this sign-in. Try again after storage is available.",
            );
        }
    }
    useEffect(() => {
        if (!account) return;
        let live = true;
        client
            .request("Users/Me")
            .then(() => {
                if (live) {
                    setRestoring(false);
                    setVerified(true);
                }
            })
            .catch((e) => {
                if (live) {
                    setRestoring(false);
                    setVerified(false);
                    fail(e);
                }
            });
        return () => {
            live = false;
        };
    }, [account?.accessToken, verifyAttempt]);
    function navigate(next: Route) {
        setReturnBarrier(undefined);
        setReturnReportRetry(undefined);
        const current = document.activeElement as HTMLElement;
        const nextStack = [
            ...stack.slice(0, -1),
            {
                ...stack.at(-1)!,
                focus: current?.dataset.focus,
                scroll: window.scrollY,
            },
            { route: next },
        ];
        appHistory.publish(nextStack, false, nextStack.slice(0, -1));
        setStack(nextStack);
        setError("");
    }
    function rawBack() {
        if (confirmation) {
            if (!busy) {
                document
                    .querySelector<HTMLDialogElement>("dialog[open]")
                    ?.close();
                const opener = confirmation.opener;
                setConfirmation(undefined);
                opener?.focus();
            }
            return;
        }
        if (chooser) {
            setChooser(false);
            return;
        }
        if (stack.length > 1) {
            appHistory.back(
                () => {
                    const parent = stack.slice(0, -1);
                    appHistory.publish(parent, true);
                    setStack(parent);
                },
                stack.slice(0, -1),
            );
            setError("");
        } else if (!account) {
            setServer(undefined);
            setError("");
        }
    }
    function back() {
        if (player) {
            window.dispatchEvent(new Event("pictureshow-stop"));
            return;
        }
        rawBack();
    }
    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (
                e.defaultPrevented ||
                (document.querySelector("dialog[open]") && !confirmation)
            )
                return;
            if (
                !player &&
                e.repeat &&
                (e.key === "Enter" ||
                    e.key === "Escape" ||
                    e.key === "Backspace" ||
                    e.key.startsWith("Arrow"))
            ) {
                e.preventDefault();
                return;
            }
            if (
                (e.key === "BrowserSearch" ||
                    (e.key === "f" && (e.ctrlKey || e.metaKey))) &&
                account &&
                !player
            ) {
                e.preventDefault();
                navigate({ kind: "search" });
                return;
            }
            spatialKey(e, back);
        };
        window.addEventListener("keydown", handler);
        return () => window.removeEventListener("keydown", handler);
    }, [stack, account, chooser, confirmation, busy]);
    useEffect(() => {
        if (player || restoring) return;
        const entry = stack.at(-1)!;
        let done = false;
        let selected: HTMLElement | undefined;
        const restore = () => {
            const active = document.activeElement;
            // Detail may replace its loading action with an authoritative action. Preserve
            // that action's focus only if its removal left the browser on the document.
            if (done && (selected?.isConnected ||
                (active !== document.body && active?.isConnected))) return;
            const focusKey = done ? selected?.dataset.focus : entry.focus;
            const targets = focusKey
                ? [...document.querySelectorAll<HTMLElement>("[data-focus]")]
                    .filter((e) => e.dataset.focus === focusKey)
                : [...document.querySelectorAll<HTMLElement>("[data-autofocus]")];
            const target = targets.find((e) => e.getClientRects().length && !e.closest("[inert]"));
            if (target) {
                target.focus({ preventScroll: true });
                if (!done) {
                    target.scrollIntoView({ block: "nearest", inline: "nearest" });
                    if (entry.scroll !== undefined) window.scrollTo(0, entry.scroll);
                }
                selected = target;
                done = true;
            }
        };
        const observer = new MutationObserver(restore);
        observer.observe(document.getElementById("main")!, {
            childList: true,
            subtree: true,
        });
        restore();
        return () => observer.disconnect();
    }, [stack, account, server, restoring, chooser]);
    // Retain safe catalog entries across reloads; never persist an Item DTO or an active player.
    useEffect(() => {
        if (!account || !verified) return;
        try {
            const safe = stack.filter((e) => e.route.kind !== "player");
            sessionStorage.setItem(
                "pictureshow.routes." + accountKey(account),
                JSON.stringify(safe),
            );
        } catch {}
    }, [stack, account, verified]);
    useEffect(() => {
        if (!account || !verified) return;
        let live = true,
            generation = 0;
        // A hash event with an already-consumed URL must not dispose its pending read.
        // Only a newer link or an account/effect release supersedes that work.
        const consume = () => {
            const q = new URLSearchParams(location.hash.slice(1)),
                id = q.get("item"),
                position = q.get("positionMs");
            if (!id) return;
            const request = ++generation;
            history.replaceState(null, "", location.pathname + location.search);
            if (
                !/^[a-zA-Z0-9_-]{1,128}$/.test(id) ||
                (position !== null &&
                    (!Number.isSafeInteger(Number(position)) ||
                        Number(position) < 0 ||
                        Number(position) > Number.MAX_SAFE_INTEGER / 10000))
            ) {
                setError(
                    "This playback link is invalid. Choose an item from your library.",
                );
                return;
            }
            client
                .detail(id)
                .then((item) => {
                    if (live && request === generation) {
                        const detail: Entry[] = [
                            { route: { kind: "home" } },
                            { route: { kind: "detail", id: item.Id } },
                        ];
                        appHistory.publish(detail, true);
                        const runtime =
                            item.RunTimeTicks &&
                            item.RunTimeTicks > 0 &&
                            item.RunTimeTicks < 99 * 3600 * 1e7
                                ? item.RunTimeTicks / 1e7
                                : undefined;
                        const start =
                            runtime === undefined
                                ? Number(position ?? 0) / 1000
                                : Math.min(
                                      Number(position ?? 0) / 1000,
                                      runtime,
                                  );
                        const playback: Entry[] = [
                            ...detail,
                            { route: { kind: "player", item, start } },
                        ];
                        appHistory.publish(playback);
                        setStack(playback);
                    }
                })
                .catch((e) => {
                    if (live && request === generation) fail(e);
                });
        };
        window.addEventListener("hashchange", consume);
        consume();
        return () => {
            live = false;
            window.removeEventListener("hashchange", consume);
        };
    }, [verified, client]);

    async function logout() {
        if (busy) return;
        setBusy(true);
        const old = client;
        try {
            commit({
                ...saved!,
                accounts: saved!.accounts.filter(
                    (a) => accountKey(a) !== accountKey(account!),
                ),
                current: undefined,
            });
            setAccount(undefined);
            setVerified(false);
            setUsername("");
            setStack([{ route: { kind: "home" } }]);
            setChooser(saved!.accounts.length > 1);
            setError("");
            void old.request("Sessions/Logout", {}, "POST").catch(() => {});
        } catch {
            setError(
                "Could not save sign-out locally. Your account is still available; try again.",
            );
        } finally {
            setBusy(false);
        }
    }
    function login(s: Server) {
        device.current = newDevice();
        try {
            sessionStorage.setItem("pictureshow.login.device", device.current);
        } catch {}
        setAccount(undefined);
        setServer(s);
        setChooser(false);
        setStack([{ route: { kind: "home" } }]);
        setError("");
        setUsername("");
    }
    function addServer() {
        setAccount(undefined);
        setServer(undefined);
        setChooser(false);
        setStack([{ route: { kind: "home" } }]);
    }
    function rootTab(kind: "home" | "libraries" | "search" | "settings") {
        if (navRoot === kind) return;
        roots.current[accountKey(account!) + ":" + navRoot] = stack;
        const next = roots.current[accountKey(account!) + ":" + kind] ?? [
            { route: { kind } },
        ];
        appHistory.publish(next);
        setStack(next);
        setError("");
    }
    async function remove() {
        if (!confirmation || busy) return;
        setBusy(true);
        setConfirmError("");
        try {
            const doomed = saved!.accounts.filter((a) =>
                    confirmation.account
                        ? accountKey(a) === accountKey(confirmation.account)
                        : a.server.id === confirmation.server!.id,
                ),
                keys = new Set(doomed.map(accountKey));
            commit({
                ...saved!,
                accounts: saved!.accounts.filter(
                    (a) => !keys.has(accountKey(a)),
                ),
                servers: confirmation.server
                    ? saved!.servers.filter(
                          (s) => s.id !== confirmation.server!.id,
                      )
                    : saved!.servers,
                current:
                    saved!.current && keys.has(saved!.current)
                        ? undefined
                        : saved!.current,
            });
            if (account && keys.has(accountKey(account))) {
                setAccount(undefined);
                setVerified(false);
                setChooser(true);
                setStack([{ route: { kind: "home" } }]);
            }
            for (const a of doomed) {
                try {
                    sessionStorage.removeItem(
                        "pictureshow.routes." + accountKey(a),
                    );
                } catch {}
                void new Jellyfin(a.server, a.deviceId!, a)
                    .request("Sessions/Logout", {}, "POST")
                    .catch(() => {});
            }
            setConfirmation(undefined);
        } catch {
            setConfirmError(
                "Could not save removal. Nothing was forgotten. Try again or Cancel.",
            );
        } finally {
            setBusy(false);
        }
    }
    if (storageError || !saved)
        return (
            <main id="main">
                <h1>Browser storage unavailable</h1>
                <p role="alert">{storageError}</p>
                <button
                    data-autofocus
                    onClick={() => {
                        try {
                            const recovered = readSaved();
                            const selected = recovered.accounts.find(
                                (a) => accountKey(a) === recovered.current,
                            );
                            setSaved(recovered);
                            setAccount(selected);
                            setServer(
                                selected?.server ?? recovered.servers.at(-1),
                            );
                            setRestoring(!!selected);
                            setVerified(false);
                            setPreferences(readPreferences());
                            setStorageError("");
                        } catch (e) {
                            setStorageError(
                                e instanceof Error
                                    ? e.message
                                    : "Could not read browser storage.",
                            );
                        }
                    }}
                >
                    Retry saved sign-ins
                </button>
            </main>
        );
    const accounts = (
        <AccountChooser
            saved={saved}
            onSelect={select}
            onLogin={login}
            onAdd={addServer}
            onRemove={(a, opener) => {
                setConfirmError("");
                setConfirmation({
                    account: a,
                    opener: opener ?? (document.activeElement as HTMLElement),
                });
            }}
            onRemoveServer={(s, opener) => {
                setConfirmError("");
                setConfirmation({
                    server: s,
                    opener: opener ?? (document.activeElement as HTMLElement),
                });
            }}
            onBack={account ? rawBack : undefined}
        />
    );
    return (
        <>
            <a className="skip" href="#main">
                Skip to content
            </a>
            <header className={player ? "theater-hidden" : `app-header ${route.kind === "home" ? "home-header" : ""}`}>
                <span className="wordmark">
                    pictureshow<span aria-hidden="true">.</span>
                </span>
                <span className="connection">
                    {account
                        ? `${account.user.Name} · ${server ? labelOf(server) : ""}`
                        : (server ? labelOf(server) : "Your library, everywhere.")}
                </span>
                {account && (
                    <nav className="header-actions" aria-label="Main navigation">
                        {(["home", "libraries", "search", "settings"] as const).map((kind) => (
                            <button
                                key={kind}
                                data-focus={`header:${kind}`}
                                data-root={kind}
                                aria-current={navRoot === kind ? "page" : undefined}
                                onClick={() => rootTab(kind)}
                            >
                                {kind[0].toUpperCase() + kind.slice(1)}
                            </button>
                        ))}
                    </nav>
                )}
            </header>
            <main
                id="main"
                onClickCapture={(e) => {
                    if (e.target instanceof Element)
                        e.target
                            .closest<HTMLElement>("[data-focus]")
                            ?.focus({ preventScroll: true });
                }}
                className={
                    player ? "theater" : account ? "app-content" : undefined
                }
            >
                <div className="topline">
                    {(stack.length > 1 || chooser || (!account && server)) && (
                        <button className="back" onClick={back}>
                            ← Back
                        </button>
                    )}
                    <span className="keyhint">
                        Arrows to explore · Enter to choose · Back to return
                    </span>
                </div>
                {error && (
                    <div role="alert" className="error">
                        {error}
                        <button onClick={() => setError("")}>Dismiss</button>
                    </div>
                )}
                {chooser ? (
                    accounts
                ) : !server ? (
                    <ServerForm
                        busy={busy}
                        remembered={saved.servers}
                        onRemembered={login}
                        onAccounts={
                            saved.accounts.length
                                ? () => setChooser(true)
                                : undefined
                        }
                        onSubmit={async (address) => {
                            if (busy) return;
                            setBusy(true);
                            setError("");
                            try {
                                const s = await Jellyfin.probe(
                                    address,
                                    device.current,
                                );
                                commit({
                                    ...saved,
                                    servers: [
                                        ...saved.servers.filter(
                                            (x) => x.id !== s.id,
                                        ),
                                        s,
                                    ],
                                });
                                login(s);
                            } catch (e) {
                                fail(e);
                            } finally {
                                setBusy(false);
                            }
                        }}
                    />
                ) : !account ? (
                    <SignIn
                        key={server.id + device.current}
                        client={client}
                        label={labelOf(server)}
                        initialUsername={username}
                        onAccount={signedIn}
                        onError={fail}
                        onBack={() => {
                            setServer(undefined);
                            setError("");
                        }}
                    />
                ) : restoring ? (
                    <p role="status">Checking your sign-in…</p>
                ) : !verified ? (
                    <section>
                        <h1>Could not verify your sign-in</h1>
                        <p>
                            Your saved account remains available. Check your
                            connection and retry.
                        </p>
                        <button
                            data-autofocus
                            onClick={() => {
                                setRestoring(true);
                                setVerifyAttempt((x) => x + 1);
                            }}
                        >
                            Retry sign-in check
                        </button>
                        <button onClick={() => setChooser(true)}>
                            Remembered accounts
                        </button>
                    </section>
                ) : route.kind === "home" ? (
                    <Home
                        key={generation}
                        client={client}
                        label={server ? labelOf(server) : undefined}
                        navigate={navigate}
                        onError={fail}
                        onSearch={() => navigate({ kind: "search" })}
                        onSettings={() => navigate({ kind: "settings" })}
                        onSwitchUser={() => navigate({ kind: "accounts" })}
                        onSignOut={logout}
                    />
                ) : route.kind === "libraries" ? (
                    <Libraries
                        key={accountKey(account)}
                        client={client}
                        navigate={navigate}
                        onError={fail}
                    />
                ) : route.kind === "search" ? (
                    <Search
                        key={accountKey(account)}
                        client={client}
                        navigate={navigate}
                        onError={fail}
                    />
                ) : route.kind === "detail" ? (
                    <Detail
                        key={accountKey(account) + ":" + route.id}
                        client={client}
                        id={route.id}
                        reportBarrier={returnBarrier}
                        retryReport={returnReportRetry}
                        navigate={navigate}
                        onError={fail}
                        onBack={rawBack}
                    />
                ) : route.kind === "player" ? (
                    <Player
                        key={route.item.Id}
                        client={client}
                        item={route.item}
                        start={route.start}
                        preferences={preferences}
                        onLeaving={(
                            barrier: Promise<void>,
                            retryReport?: () => Promise<void>,
                        ) => {
                            void barrier.catch(() => {});
                            setReturnBarrier(barrier);
                            setReturnReportRetry(() => retryReport);
                            rawBack();
                        }}
                        onBack={(message?: string) => {
                            rawBack();
                            if (message) setError(message);
                        }}
                        onExpired={fail}
                        onNext={(item) => {
                            const detail: Entry[] = [
                                ...stack.slice(0, -2),
                                { route: { kind: "detail", id: item.Id } },
                            ];
                            appHistory.publish(detail, true);
                            const next: Entry[] = [
                                ...detail,
                                { route: { kind: "player", item, start: 0 } },
                            ];
                            appHistory.publish(next);
                            setStack(next);
                        }}
                    />
                ) : route.kind === "settings" ? (
                    <Settings
                        client={client}
                        value={preferences}
                        onChange={setPreferences}
                        navigate={navigate}
                        onError={fail}
                    />
                ) : route.kind === "accounts" ? (
                    accounts
                ) : (
                    <Information
                        route={route as InformationRoute}
                        navigate={navigate}
                    />
                )}
            </main>
            {account && !player && !chooser && (
                <nav className="root-navigation" aria-label="Main navigation">
                    {(["home", "libraries", "search", "settings"] as const).map(
                        (kind) => (
                            <button
                                key={kind}
                                aria-current={
                                    navRoot === kind ? "page" : undefined
                                }
                                onClick={() => rootTab(kind)}
                            >
                                <svg className="navigation-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                                    <path d={kind === "home" ? "M3 10 12 3l9 7v11h-6v-7H9v7H3Z" : kind === "libraries" ? "M4 3h4v18H4Zm6 0h4v18h-4Zm6 1 4-1 4 17-4 1Z" : kind === "search" ? "M21 21l-6-6m2-5a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z" : "m9 3-.7 3-2.7 1-2.5-1.5L1 9l2.3 2v3L1 16l2.1 3.5L6 18l2.3 1 .7 3h4l.7-3 2.7-1 2.5 1.5L23 16l-2.3-2v-3L23 9l-2.1-3.5L18 7l-2.3-1L15 3ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z"} />
                                </svg>
                                <span>{kind[0].toUpperCase() + kind.slice(1)}</span>
                            </button>
                        ),
                    )}
                </nav>
            )}
            <footer className={player ? "theater-hidden" : undefined}>
                Made for your own Jellyfin server. No Pictureshow account
                needed.
            </footer>
            {confirmation && (
                <Confirmation
                    question={
                        confirmation.account
                            ? `Remove ${confirmation.account.user.Name} on ${labelOf(confirmation.account.server)} from this browser?`
                            : `Remove ${labelOf(confirmation.server!)} and ${saved.accounts.filter((a) => a.server.id === confirmation.server!.id).length} saved account(s) from this browser?`
                    }
                    busy={busy}
                    error={confirmError}
                    onCancel={() => {
                        const o = confirmation.opener;
                        setConfirmation(undefined);
                        o?.focus();
                    }}
                    onConfirm={() => void remove()}
                />
            )}
        </>
    );
}
installLastInput();
createRoot(document.getElementById("root")!).render(<App />);
