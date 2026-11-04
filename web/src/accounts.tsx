/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useRef, useState } from "react";
import {
    Jellyfin,
    ClientError,
    encode,
    type Account,
    type Server,
} from "@pictureshow/core";
export const accountKey = (a: Account) => a.server.id + ":" + a.user.Id;
export const newDevice = () =>
    globalThis.crypto?.randomUUID?.() ??
    "web-" + Date.now() + "-" + Math.random().toString(36).slice(2);
export type Saved = {
    version: 1;
    accounts: Account[];
    servers: Server[];
    current?: string;
};
const key = "pictureshow.accounts.v1";
export function readSaved(): Saved {
    const text = localStorage.getItem(key);
    if (text) {
        const s = JSON.parse(text);
        if (
            s.version !== 1 ||
            !Array.isArray(s.accounts) ||
            !Array.isArray(s.servers) ||
            s.accounts.some(
                (a: Account) =>
                    !a?.accessToken || !a?.user?.Id || !a?.server?.url,
            )
        )
            throw Error(
                "Saved sign-ins could not be read. Retry without clearing browser data.",
            );
        return s;
    }
    const legacy = JSON.parse(
        sessionStorage.getItem("pictureshow.account") ?? "null",
    );
    const migrated: Saved = {
        version: 1,
        accounts: legacy?.accessToken ? [legacy] : [],
        servers: legacy?.server ? [legacy.server] : [],
        current: legacy?.accessToken ? accountKey(legacy) : undefined,
    };
    // Commit the persistent replacement before forgetting a working session.
    // A blocked write leaves the original available to the startup Retry flow.
    if (legacy?.accessToken) saveSaved(migrated);
    return migrated;
}
export function saveSaved(s: Saved) {
    localStorage.setItem(key, JSON.stringify(s));
    sessionStorage.removeItem("pictureshow.account");
}
export function AccountChooser({
    saved,
    onSelect,
    onLogin,
    onAdd,
    onRemove,
    onRemoveServer,
    onBack,
}: {
    saved: Saved;
    onSelect: (a: Account) => void;
    onLogin: (s: Server) => void;
    onAdd: () => void;
    onRemove: (a: Account, opener?: HTMLElement) => void;
    onRemoveServer: (s: Server, opener?: HTMLElement) => void;
    onBack?: () => void;
}) {
    return (
        <section className="account-page">
            <h1>Remembered accounts</h1>
            <p>
                Choose a viewer or sign in another user. Removing a sign-in only
                forgets it in this browser.
            </p>
            {saved.servers.map((s) => (
                <section key={s.id}>
                    <h2>{s.name}</h2>
                    <p className="muted">{s.url}</p>
                    <ul className="account-list">
                        {saved.accounts
                            .filter((a) => a.server.id === s.id)
                            .map((a) => (
                                <li key={accountKey(a)}>
                                    <button
                                        className="primary"
                                        data-autofocus
                                        data-focus={"account:" + accountKey(a)}
                                        onClick={() => onSelect(a)}
                                    >
                                        Continue as {a.user.Name}
                                    </button>
                                    <button
                                        data-focus={"remove:" + accountKey(a)}
                                        onClick={(e) =>
                                            onRemove(a, e.currentTarget)
                                        }
                                    >
                                        Remove {a.user.Name}
                                    </button>
                                </li>
                            ))}
                    </ul>
                    <div className="actions">
                        <button
                            data-focus={"login:" + s.id}
                            onClick={() => onLogin(s)}
                        >
                            Sign in another user on {s.name}
                        </button>
                        <button
                            data-focus={"remove-server:" + s.id}
                            onClick={(e) => onRemoveServer(s, e.currentTarget)}
                        >
                            Remove server {s.name}
                        </button>
                    </div>
                </section>
            ))}
            <div className="actions">
                <button onClick={onAdd}>Add a server</button>
                {onBack && <button onClick={onBack}>Back to Home</button>}
            </div>
        </section>
    );
}
export function Confirmation({
    question,
    busy,
    error,
    onCancel,
    onConfirm,
}: {
    question: string;
    busy: boolean;
    error: string;
    onCancel: () => void;
    onConfirm: () => void;
}) {
    const ref = useRef<HTMLDialogElement>(null);
    useEffect(() => {
        ref.current?.showModal();
        ref.current?.querySelector("button")?.focus();
        return () => ref.current?.close();
    }, []);
    const cancel = () => {
        if (!busy) {
            ref.current?.close();
            onCancel();
        }
    };
    return (
        <dialog
            ref={ref}
            aria-labelledby="confirm-title"
            onCancel={(e) => {
                e.preventDefault();
                if (!busy) cancel();
            }}
            onClick={(e) => {
                if (e.target === ref.current && !busy) cancel();
            }}
        >
            <h2 id="confirm-title">{question}</h2>
            <p>
                The server library and other browser installations are
                unchanged.
            </p>
            {error && <p role="alert">{error}</p>}
            <div className="actions">
                <button disabled={busy} onClick={cancel}>
                    Cancel
                </button>
                <button disabled={busy} onClick={onConfirm}>
                    {busy ? "Removing…" : "Remove"}
                </button>
            </div>
        </dialog>
    );
}
export function ServerForm({
    busy,
    remembered,
    onRemembered,
    onAccounts,
    onSubmit,
}: {
    busy: boolean;
    remembered: Server[];
    onRemembered: (s: Server) => void;
    onAccounts?: () => void;
    onSubmit: (s: string) => void;
}) {
    const [address, setAddress] = useState("");
    return (
        <section className="welcome">
            <h1>Connect to Jellyfin</h1>
            <p>Your library, on your server.</p>
            {remembered.length > 0 && (
                <section>
                    <h2>Remembered servers</h2>
                    {remembered.map((s) => (
                        <button key={s.id} onClick={() => onRemembered(s)}>
                            {s.name}
                        </button>
                    ))}
                </section>
            )}
            <form
                onSubmit={(e) => {
                    e.preventDefault();
                    if (!busy) onSubmit(address);
                }}
            >
                <label htmlFor="server">Server address</label>
                <input
                    data-autofocus
                    id="server"
                    inputMode="url"
                    autoComplete="url"
                    autoCapitalize="none"
                    spellCheck={false}
                    placeholder="https://jellyfin.example.com"
                    required
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    aria-describedby="server-help"
                />
                <p id="server-help" className="muted">
                    Include your server's base path if it has one. Web browsers
                    cannot broadcast Jellyfin LAN discovery; enter an address
                    {remembered.length
                        ? " or choose a remembered server."
                        : "."}
                </p>
                <button className="primary" disabled={busy}>
                    {busy ? "Connecting…" : "Connect to server →"}
                </button>
            </form>
            {onAccounts && (
                <button className="secondary" onClick={onAccounts}>
                    Remembered accounts
                </button>
            )}
        </section>
    );
}
export function SignIn({
    client,
    initialUsername = "",
    onAccount,
    onError,
    onBack,
}: {
    client: Jellyfin;
    initialUsername?: string;
    onAccount: (a: Account) => void;
    onError: (e: unknown) => void;
    onBack: () => void;
}) {
    const [username, setUsername] = useState(initialUsername),
        [password, setPassword] = useState(""),
        [shown, setShown] = useState(false),
        [busy, setBusy] = useState(false),
        [users, setUsers] = useState<{ Id: string; Name: string }[]>([]),
        [quick, setQuick] = useState(() => {
            try {
                return (
                    sessionStorage.getItem("pictureshow.quick-open") ===
                    client.server.id
                );
            } catch {
                return false;
            }
        }),
        [code, setCode] = useState(""),
        [announcement, setAnnouncement] = useState(""),
        [enabled, setEnabled] = useState<boolean>(),
        [quickError, setQuickError] = useState(""),
        [retry, setRetry] = useState(0),
        [renewed, setRenewed] = useState(false);
    const alive = useRef(true),
        inflight = useRef(false),
        seen = useRef(
            (() => {
                try {
                    return (
                        sessionStorage.getItem(
                            "pictureshow.quick-announced." + client.server.id,
                        ) ?? ""
                    );
                } catch {
                    return "";
                }
            })(),
        );
    const key = "pictureshow.quick." + client.server.id;
    useEffect(() => {
        alive.current = true;
        Promise.allSettled([
            client.request<boolean>("QuickConnect/Enabled"),
            client.request<{ Id: string; Name: string }[]>("Users/Public"),
        ]).then(([q, u]) => {
            if (!alive.current) return;
            setEnabled(q.status === "fulfilled" ? q.value : false);
            if (u.status === "fulfilled") setUsers(u.value);
        });
        return () => {
            alive.current = false;
        };
    }, [client.server.id]);
    useEffect(() => {
        const back = (e: KeyboardEvent) => {
            if (
                e.key === "Escape" ||
                (e.key === "Backspace" &&
                    !(e.target as HTMLElement).matches("input"))
            ) {
                e.preventDefault();
                e.stopImmediatePropagation();
                if (quick) {
                    setQuick(false);
                    sessionStorage.removeItem("pictureshow.quick-open");
                } else {
                    sessionStorage.removeItem(key);
                    onBack();
                }
            }
        };
        window.addEventListener("keydown", back, true);
        return () => window.removeEventListener("keydown", back, true);
    }, [quick, onBack]);
    useEffect(() => {
        if (!quick) return;
        let cancelled = false,
            timer: ReturnType<typeof setTimeout>;
        sessionStorage.setItem("pictureshow.quick-open", client.server.id);
        async function poll() {
            try {
                let pending = JSON.parse(sessionStorage.getItem(key) ?? "null");
                if (!pending || pending.deviceId !== client.deviceId) {
                    pending = {
                        ...(await client.request<{
                            Code: string;
                            Secret: string;
                        }>("QuickConnect/Initiate", {})),
                        deviceId: client.deviceId,
                    };
                    if (!/^\d{6}$/.test(pending.Code) || !pending.Secret)
                        throw Error(
                            "The server returned an invalid Quick Connect code.",
                        );
                    sessionStorage.setItem(key, JSON.stringify(pending));
                }
                if (cancelled) return;
                setCode(pending.Code);
                if (seen.current !== pending.Code) {
                    seen.current = pending.Code;
                    setAnnouncement(pending.Code.split("").join(" "));
                    try {
                        sessionStorage.setItem(
                            "pictureshow.quick-announced." + client.server.id,
                            pending.Code,
                        );
                    } catch {}
                }
                const response = await client.request<{
                    Authenticated: boolean;
                }>("QuickConnect/Connect?secret=" + encode(pending.Secret));
                if (cancelled) return;
                if (response.Authenticated) {
                    const dto = await client.request<any>(
                        "Users/AuthenticateWithQuickConnect",
                        { Secret: pending.Secret },
                    );
                    if (cancelled) return;
                    sessionStorage.removeItem(key);
                    sessionStorage.removeItem("pictureshow.quick-open");
                    onAccount(client.accept(dto));
                    return;
                }
                timer = setTimeout(poll, 5000);
            } catch (e) {
                if (cancelled) return;
                if (
                    e instanceof ClientError &&
                    ["EXPIRED", "NOT_FOUND"].includes(e.kind)
                ) {
                    sessionStorage.removeItem(key);
                    setRenewed(true);
                    timer = setTimeout(poll, 100);
                } else
                    setQuickError(
                        e instanceof Error
                            ? e.message
                            : "Quick Connect could not connect.",
                    );
            }
        }
        void poll();
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [quick, retry, client.server.id]);
    const spoken = announcement;
    return (
        <section className="signin">
            <p className="eyebrow">{client.server.name}</p>
            <h1>Welcome back.</h1>
            {quick ? (
                <>
                    <h2>Quick Connect</h2>
                    <p>
                        On an already signed-in Jellyfin app, open Settings →
                        Quick Connect and enter:
                    </p>
                    <output
                        aria-live="off"
                        className="code"
                        aria-label={
                            code ? code.split("").join(" ") : "Getting a code"
                        }
                    >
                        {code || "Getting a code…"}
                    </output>
                    <p className="sr-only" role="status">
                        {spoken
                            ? `${renewed ? "New code. The old code expired. " : ""}${spoken}`
                            : ""}
                    </p>
                    <p>
                        {renewed
                            ? "The previous code expired. Use the new code."
                            : "Waiting for approval."}
                    </p>
                    {quickError && (
                        <div role="alert">
                            <p>{quickError}</p>
                            <button
                                onClick={() => {
                                    setQuickError("");
                                    setRetry((x) => x + 1);
                                }}
                            >
                                Retry Quick Connect
                            </button>
                        </div>
                    )}
                    <button
                        data-autofocus
                        onClick={() => {
                            setQuick(false);
                            sessionStorage.removeItem("pictureshow.quick-open");
                        }}
                    >
                        Back to password
                    </button>
                </>
            ) : (
                <>
                    {users.length > 0 && (
                        <section>
                            <h2>Choose a user</h2>
                            <div className="actions">
                                {users.map((u) => (
                                    <button
                                        key={u.Id}
                                        onClick={() => {
                                            setUsername(u.Name);
                                            document
                                                .getElementById("password")
                                                ?.focus();
                                        }}
                                    >
                                        {u.Name}
                                    </button>
                                ))}
                            </div>
                        </section>
                    )}
                    <form
                        onSubmit={async (e) => {
                            e.preventDefault();
                            if (inflight.current) return;
                            inflight.current = true;
                            setBusy(true);
                            try {
                                const a = await client.authenticate(
                                    username,
                                    password,
                                );
                                if (alive.current) {
                                    onAccount(a);
                                    setPassword("");
                                    sessionStorage.removeItem(key);
                                    sessionStorage.removeItem(
                                        "pictureshow.quick-open",
                                    );
                                }
                            } catch (e) {
                                if (alive.current) onError(e);
                            } finally {
                                inflight.current = false;
                                if (alive.current) setBusy(false);
                            }
                        }}
                    >
                        <label htmlFor="username">Username</label>
                        <input
                            data-autofocus
                            id="username"
                            autoComplete="username"
                            autoCapitalize="none"
                            enterKeyHint="next"
                            required
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === "Enter") {
                                    e.preventDefault();
                                    document
                                        .getElementById("password")
                                        ?.focus();
                                }
                            }}
                        />
                        <label htmlFor="password">Password</label>
                        <div className="password-field">
                            <input
                                id="password"
                                type={shown ? "text" : "password"}
                                autoComplete="current-password"
                                enterKeyHint="go"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                            <button
                                type="button"
                                aria-pressed={shown}
                                aria-label={
                                    shown ? "Hide password" : "Show password"
                                }
                                onClick={() => setShown(!shown)}
                            >
                                {shown ? "Hide" : "Show"}
                            </button>
                        </div>
                        <button className="primary" disabled={busy}>
                            {busy ? "Signing in…" : "Sign in"}
                        </button>
                    </form>
                    {enabled ? (
                        <button
                            className="secondary primary"
                            disabled={busy}
                            onClick={() => {
                                setQuick(true);
                                setQuickError("");
                            }}
                        >
                            Use Quick Connect
                        </button>
                    ) : (
                        <p className="muted">
                            {enabled === undefined
                                ? "Checking Quick Connect…"
                                : "Quick Connect is unavailable. Sign in with your username and password."}
                        </p>
                    )}
                </>
            )}
        </section>
    );
}
