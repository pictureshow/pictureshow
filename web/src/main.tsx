/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
    Jellyfin,
    ClientError,
    episodeLabel,
    progress,
    resumeLabel,
    runtimeLabel,
    mediaUrl,
    fetchMedia,
    encode,
    resumeStartMs,
    type Account,
    type Server,
    type Item,
    type Row,
    type Page,
} from "@pictureshow/core";
import { spatialKey, focusable } from "./navigation";
import { Player } from "./player";
import "./style.css";
function deviceId() {
    const fresh = () =>
        globalThis.crypto?.randomUUID?.() ??
        "web-" + Date.now() + "-" + Math.random().toString(36).slice(2);
    try {
        let id = localStorage.getItem("pictureshow.device");
        if (!id) {
            id = fresh();
            localStorage.setItem("pictureshow.device", id);
        }
        return id;
    } catch {
        return fresh();
    }
}
function savedAccount(): Account | undefined {
    try {
        const a = JSON.parse(
            sessionStorage.getItem("pictureshow.account") ?? "null",
        );
        return a?.accessToken && a?.server?.url && a?.user?.Id ? a : undefined;
    } catch {
        return undefined;
    }
}
type Route =
    | { kind: "home" }
    | { kind: "detail"; id: string }
    | { kind: "player"; item: Item; start: number };
type Entry = { route: Route; focus?: string };
function App() {
    const [account, setAccount] = useState(savedAccount),
        [server, setServer] = useState<Server | undefined>(account?.server),
        [stack, setStack] = useState<Entry[]>([{ route: { kind: "home" } }]),
        [error, setError] = useState(""),
        [busy, setBusy] = useState(false),
        [restoring, setRestoring] = useState(!!account);
    const id = useRef(deviceId()).current;
    const client = new Jellyfin(
        server ?? { id: "", name: "", url: "", version: "" },
        account?.deviceId ?? id,
        account,
    );
    const route = stack.at(-1)!.route;
    function signIn(a: Account) {
        try {
            sessionStorage.setItem("pictureshow.account", JSON.stringify(a));
            setAccount(a);
            setError("");
            setStack([{ route: { kind: "home" } }]);
        } catch {
            setError(
                "Browser storage is unavailable. Enable session storage to sign in.",
            );
        }
    }
    function fail(e: unknown) {
        setError(
            e instanceof Error ? e.message : "The request failed. Try again.",
        );
        if (e instanceof ClientError && e.kind === "EXPIRED") {
            sessionStorage.removeItem("pictureshow.account");
            setAccount(undefined);
        }
    }
    useEffect(() => {
        if (!account) return;
        let live = true;
        client
            .request("Users/Me")
            .then(() => {
                if (live) setRestoring(false);
            })
            .catch((e) => {
                if (live) {
                    setRestoring(false);
                    fail(e);
                }
            });
        return () => {
            live = false;
        };
    }, [account?.accessToken]);
    function navigate(next: Route) {
        const current = document.activeElement as HTMLElement;
        setStack((s) => [
            ...s.slice(0, -1),
            { ...s.at(-1)!, focus: current?.dataset.focus },
            { route: next },
        ]);
        setError("");
    }
    function rawBack() {
        if (stack.length > 1) {
            setStack((s) => s.slice(0, -1));
            setError("");
        } else if (!account) {
            setServer(undefined);
            setError("");
        }
    }
    function back() {
        if (route.kind === "player") {
            window.dispatchEvent(new Event("pictureshow-stop"));
            return;
        }
        rawBack();
    }
    useEffect(() => {
        const handler = (e: KeyboardEvent) => spatialKey(e, back);
        window.addEventListener("keydown", handler);
        return () => window.removeEventListener("keydown", handler);
    }, [stack.length, account]);
    useEffect(() => {
        const key = stack.at(-1)?.focus;
        let done = false;
        const restore = () => {
            if (done) return;
            const target = key
                ? [
                      ...document.querySelectorAll<HTMLElement>("[data-focus]"),
                  ].find((e) => e.dataset.focus === key)
                : document.querySelector<HTMLElement>("[data-autofocus]");
            if (target) {
                target.focus();
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
    }, [stack, account, server, restoring]);
    async function logout() {
        setBusy(true);
        try {
            await client.request("Sessions/Logout", {}, "POST");
            sessionStorage.removeItem("pictureshow.account");
            setAccount(undefined);
            setStack([{ route: { kind: "home" } }]);
        } catch (e) {
            fail(e);
        } finally {
            setBusy(false);
        }
    }
    return (
        <>
            <a className="skip" href="#main">
                Skip to content
            </a>
            <header
                className={
                    account && route.kind === "player"
                        ? "theater-hidden"
                        : undefined
                }
            >
                <span className="wordmark">
                    pictureshow<span aria-hidden="true">.</span>
                </span>
                <span className="connection">
                    {server?.name ?? "Your library, everywhere."}
                </span>
                {account && (
                    <button onClick={logout} disabled={busy}>
                        Sign out {account.user.Name}
                    </button>
                )}
            </header>
            <main
                id="main"
                className={
                    account && route.kind === "player" ? "theater" : undefined
                }
            >
                <div className="topline">
                    {(stack.length > 1 || (!account && server)) && (
                        <button className="back" onClick={back}>
                            ← Back
                        </button>
                    )}
                    <span className="keyhint">
                        Arrows to explore · Enter to choose · Esc to go back
                    </span>
                </div>
                {error && (
                    <div role="alert" className="error">
                        {error}
                        <button onClick={() => setError("")}>Dismiss</button>
                    </div>
                )}
                {!server ? (
                    <ServerForm
                        busy={busy}
                        onSubmit={async (address) => {
                            setBusy(true);
                            setError("");
                            try {
                                setServer(await Jellyfin.probe(address, id));
                            } catch (e) {
                                fail(e);
                            } finally {
                                setBusy(false);
                            }
                        }}
                    />
                ) : !account ? (
                    <SignIn client={client} onAccount={signIn} onError={fail} />
                ) : restoring ? (
                    <p role="status">Checking your sign-in…</p>
                ) : route.kind === "home" ? (
                    <Home client={client} navigate={navigate} onError={fail} />
                ) : route.kind === "detail" ? (
                    <Detail
                        client={client}
                        id={route.id}
                        navigate={navigate}
                        onError={fail}
                    />
                ) : (
                    <Player
                        client={client}
                        item={route.item}
                        start={route.start}
                        onBack={rawBack}
                        onExpired={fail}
                    />
                )}
            </main>
            <footer
                className={
                    account && route.kind === "player"
                        ? "theater-hidden"
                        : undefined
                }
            >
                Made for your own Jellyfin server. No Pictureshow account
                needed.
            </footer>
        </>
    );
}
function ServerForm({
    onSubmit,
    busy,
}: {
    onSubmit: (s: string) => void;
    busy: boolean;
}) {
    const [address, setAddress] = useState("");
    return (
        <section className="welcome">
            <p className="eyebrow">THE GOOD PART STARTS HERE</p>
            <h1>
                Your collection.
                <br />
                Your kind of cinema.
            </h1>
            <p>
                Connect to your Jellyfin server and settle into something good.
            </p>
            <form
                onSubmit={(e) => {
                    e.preventDefault();
                    onSubmit(address);
                }}
            >
                <label htmlFor="server">Server address</label>
                <input
                    data-autofocus
                    id="server"
                    type="text"
                    inputMode="url"
                    autoComplete="url"
                    placeholder="https://jellyfin.example.com"
                    required
                    value={address}
                    onChange={(e) => setAddress(e.target.value)}
                    aria-describedby="server-help"
                />
                <p id="server-help" className="muted">
                    Use your server’s HTTPS address, VPN address, or local
                    network address. Include its base path if it has one.
                </p>
                <button className="primary" disabled={busy}>
                    {busy ? "Connecting…" : "Connect to server →"}
                </button>
            </form>
        </section>
    );
}
function SignIn({
    client,
    onAccount,
    onError,
}: {
    client: Jellyfin;
    onAccount: (a: Account) => void;
    onError: (e: unknown) => void;
}) {
    const [username, setUsername] = useState(""),
        [password, setPassword] = useState(""),
        [busy, setBusy] = useState(false),
        [quick, setQuick] = useState(false),
        [code, setCode] = useState(""),
        [enabled, setEnabled] = useState(false);
    useEffect(() => {
        let live = true;
        client
            .request<boolean>("QuickConnect/Enabled")
            .then((v) => {
                if (live) setEnabled(v);
            })
            .catch(() => {});
        return () => {
            live = false;
        };
    }, [client.server.url]);
    useEffect(() => {
        if (!quick) return;
        let cancelled = false,
            timer: ReturnType<typeof setTimeout>;
        const key = "pictureshow.quick." + client.server.id;
        async function run() {
            try {
                let pending:
                    | { Code: string; Secret: string; deviceId: string }
                    | undefined;
                try {
                    pending =
                        JSON.parse(sessionStorage.getItem(key) ?? "null") ??
                        undefined;
                } catch {}
                if (!pending || pending.deviceId !== client.deviceId) {
                    pending = {
                        ...(await client.request<{
                            Code: string;
                            Secret: string;
                        }>("QuickConnect/Initiate", {})),
                        deviceId: client.deviceId,
                    };
                    if (!/^\d{6}$/.test(pending.Code) || !pending.Secret)
                        throw new Error(
                            "The server returned an invalid Quick Connect code.",
                        );
                    sessionStorage.setItem(key, JSON.stringify(pending));
                }
                if (cancelled) return;
                setCode(pending.Code);
                try {
                    const result = await client.request<{
                        Authenticated: boolean;
                    }>("QuickConnect/Connect?secret=" + encode(pending.Secret));
                    if (cancelled) return;
                    if (result.Authenticated) {
                        const a = client.accept(
                            await client.request(
                                "Users/AuthenticateWithQuickConnect",
                                { Secret: pending.Secret },
                            ),
                        );
                        if (cancelled) return;
                        sessionStorage.removeItem(key);
                        onAccount(a);
                        return;
                    }
                } catch (e) {
                    if (
                        e instanceof ClientError &&
                        ["EXPIRED", "NOT_FOUND"].includes(e.kind)
                    ) {
                        sessionStorage.removeItem(key);
                        if (!cancelled) timer = setTimeout(run, 100);
                        return;
                    }
                    throw e;
                }
                if (!cancelled) timer = setTimeout(run, 5000);
            } catch (e) {
                if (!cancelled) {
                    setQuick(false);
                    onError(e);
                }
            }
        }
        void run();
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [quick, client.server.id]);
    return (
        <section className="signin">
            <p className="eyebrow">{client.server.name}</p>
            <h1>Welcome back.</h1>
            {quick ? (
                <>
                    <h2>Quick Connect</h2>
                    <p>
                        On an already signed-in Jellyfin app, open Quick Connect
                        and enter:
                    </p>
                    <output className="code" aria-live="polite">
                        {code || "Getting a code…"}
                    </output>
                    <p>
                        Waiting for approval. Expired codes renew automatically.
                    </p>
                    <button
                        data-autofocus
                        onClick={() => {
                            sessionStorage.removeItem(
                                "pictureshow.quick." + client.server.id,
                            );
                            setQuick(false);
                        }}
                    >
                        Cancel Quick Connect
                    </button>
                </>
            ) : (
                <>
                    <form
                        onSubmit={async (e) => {
                            e.preventDefault();
                            setBusy(true);
                            try {
                                onAccount(
                                    await client.authenticate(
                                        username,
                                        password,
                                    ),
                                );
                                setPassword("");
                            } catch (e) {
                                onError(e);
                            } finally {
                                setBusy(false);
                            }
                        }}
                    >
                        <label htmlFor="username">Username</label>
                        <input
                            data-autofocus
                            id="username"
                            autoComplete="username"
                            required
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                        />
                        <label htmlFor="password">Password</label>
                        <input
                            id="password"
                            type="password"
                            autoComplete="current-password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                        />
                        <button className="primary" disabled={busy}>
                            {busy ? "Signing in…" : "Sign in"}
                        </button>
                    </form>
                    {enabled && (
                        <button
                            className="secondary"
                            disabled={busy}
                            onClick={() => setQuick(true)}
                        >
                            Use Quick Connect
                        </button>
                    )}
                </>
            )}
        </section>
    );
}
function useLoad<T>(
    load: () => Promise<T>,
    key: string,
    onError: (e: unknown) => void,
) {
    const [data, setData] = useState<T>(),
        [error, setError] = useState(""),
        [attempt, setAttempt] = useState(0);
    useEffect(() => {
        let live = true;
        setData(undefined);
        setError("");
        load()
            .then((v) => {
                if (live) setData(v);
            })
            .catch((e) => {
                if (live) {
                    setError(
                        e instanceof Error
                            ? e.message
                            : "Could not load this page.",
                    );
                    if (e instanceof ClientError && e.kind === "EXPIRED")
                        onError(e);
                }
            });
        return () => {
            live = false;
        };
    }, [key, attempt]);
    return { data, error, retry: () => setAttempt((a) => a + 1) };
}
function LoadState({ error, retry }: { error: string; retry: () => void }) {
    return error ? (
        <div role="alert">
            <p>{error}</p>
            <button data-autofocus onClick={retry}>
                Try again
            </button>
        </div>
    ) : (
        <p role="status">Loading your library…</p>
    );
}
function PrivateImage({
    client,
    path,
    className,
}: {
    client: Jellyfin;
    path: string;
    className?: string;
}) {
    const [url, setUrl] = useState<string>();
    useEffect(() => {
        let live = true,
            object: string | undefined;
        setUrl(undefined);
        fetchMedia(client.account!, path)
            .then((r) => r.blob())
            .then((blob) => {
                if (live) {
                    object = URL.createObjectURL(blob);
                    setUrl(object);
                }
            })
            .catch(() => {});
        return () => {
            live = false;
            if (object) URL.revokeObjectURL(object);
        };
    }, [client.account!.accessToken, path]);
    return url ? (
        <img className={className} alt="" src={url} />
    ) : (
        <span
            className={[className, "image-placeholder"]
                .filter(Boolean)
                .join(" ")}
            aria-hidden="true"
        >
            ▶
        </span>
    );
}
function Cards({
    row,
    client,
    navigate,
}: {
    row: Row;
    client: Jellyfin;
    navigate: (r: Route) => void;
}) {
    return (
        <section aria-labelledby={"row-" + row.key}>
            <h2 id={"row-" + row.key}>{row.title}</h2>
            <ul className="cards">
                {row.items.map((item) => (
                    <li key={item.Id}>
                        <button
                            data-autofocus={
                                row.items[0] === item ? true : undefined
                            }
                            data-focus={row.key + ":" + item.Id}
                            className="card"
                            onClick={() =>
                                navigate({ kind: "detail", id: item.Id })
                            }
                        >
                            <div
                                className={
                                    "art " +
                                    (item.Type === "Episode" ? "wide" : "")
                                }
                            >
                                {item.ImageTags?.Primary ? (
                                    <PrivateImage
                                        client={client}
                                        path={`Items/${encode(item.Id)}/Images/Primary?maxWidth=400&quality=80&tag=${encode(item.ImageTags.Primary)}`}
                                    />
                                ) : (
                                    <span aria-hidden="true">
                                        {item.Type === "CollectionFolder"
                                            ? "▤"
                                            : "▶"}
                                    </span>
                                )}
                                {progress(item) > 0 && (
                                    <progress
                                        aria-label={
                                            "Playback progress for " + item.Name
                                        }
                                        value={progress(item)}
                                        max="1"
                                    />
                                )}
                            </div>
                            <span className="card-title">{item.Name}</span>
                            <span className="muted">
                                {item.Type === "Episode"
                                    ? episodeLabel(item)
                                    : (item.ProductionYear ?? item.Type)}
                            </span>
                        </button>
                    </li>
                ))}
            </ul>
        </section>
    );
}
function Home({
    client,
    navigate,
    onError,
}: {
    client: Jellyfin;
    navigate: (r: Route) => void;
    onError: (e: unknown) => void;
}) {
    const { data, error, retry } = useLoad(
        () => client.home(),
        client.account!.accessToken,
        onError,
    );
    return (
        <>
            <div className="intro">
                <p className="eyebrow">A LITTLE ESCAPE</p>
                <h1>Make yourself at home.</h1>
                <p className="muted">
                    A familiar favorite. A new discovery. All yours.
                </p>
            </div>
            {!data ? (
                <LoadState error={error} retry={retry} />
            ) : data.length ? (
                data.map((row) => (
                    <Cards
                        key={row.key}
                        row={row}
                        client={client}
                        navigate={navigate}
                    />
                ))
            ) : (
                <p>
                    Your library is empty. Add media to your Jellyfin server to
                    get started.
                </p>
            )}
        </>
    );
}
function Detail({
    client,
    id,
    navigate,
    onError,
}: {
    client: Jellyfin;
    id: string;
    navigate: (r: Route) => void;
    onError: (e: unknown) => void;
}) {
    const { data, error, retry } = useLoad(
        async () => {
            const item = await client.detail(id);
            let children: Item[] = [];
            if (item.Type === "Series")
                children = (
                    await client.catalog<Page>(`Shows/${encode(id)}/Seasons`)
                ).Items;
            else if (item.Type === "Season")
                children = (
                    await client.catalog<Page>(
                        `Shows/${encode(item.SeriesId!)}/Episodes?seasonId=${encode(id)}`,
                    )
                ).Items;
            else if (
                ["CollectionFolder", "Folder", "BoxSet", "Playlist"].includes(
                    item.Type,
                )
            )
                children = (
                    await client.catalog<Page>(
                        `Items?parentId=${encode(id)}&limit=200&sortBy=SortName`,
                    )
                ).Items;
            return { item, children };
        },
        id,
        onError,
    );
    if (!data) return <LoadState error={error} retry={retry} />;
    const { item, children } = data,
        playable = ["Movie", "Episode", "Video"].includes(item.Type),
        resume = item.UserData?.PlaybackPositionTicks ?? 0;
    return (
        <article>
            <section className="detail">
                <div className="detail-copy">
                    <p className="eyebrow">
                        {item.Type === "Episode"
                            ? `${item.SeriesName ?? ""} · ${episodeLabel(item)}`
                            : item.Type}
                    </p>
                    <h1>{item.Name}</h1>
                    <p className="muted">
                        {[item.ProductionYear, runtimeLabel(item.RunTimeTicks)]
                            .filter(Boolean)
                            .join(" · ")}
                    </p>
                    <p className="overview">
                        {item.Overview || "Your next watch is waiting."}
                    </p>
                    {playable && (
                        <div className="actions">
                            <button
                                className="primary"
                                data-focus="play"
                                data-autofocus
                                onClick={() =>
                                    navigate({
                                        kind: "player",
                                        item,
                                        start: resumeStartMs(resume) / 1000,
                                    })
                                }
                            >
                                {resume > 0 ? resumeLabel(resume) : "▶ Play"}
                            </button>
                            {resume > 0 && (
                                <button
                                    data-focus="restart"
                                    onClick={() =>
                                        navigate({
                                            kind: "player",
                                            item,
                                            start: 0,
                                        })
                                    }
                                >
                                    Play from beginning
                                </button>
                            )}
                        </div>
                    )}
                </div>
                {item.ImageTags?.Primary && (
                    <PrivateImage
                        className="poster"
                        client={client}
                        path={`Items/${encode(id)}/Images/Primary?maxWidth=600`}
                    />
                )}
            </section>
            {children.length > 0 ? (
                <Cards
                    row={{
                        key: "children",
                        title:
                            item.Type === "Series"
                                ? "Seasons"
                                : item.Type === "Season"
                                  ? "Episodes"
                                  : "Browse library",
                        items: children,
                    }}
                    client={client}
                    navigate={navigate}
                />
            ) : (
                !playable && <p>No items are available in this collection.</p>
            )}
        </article>
    );
}
createRoot(document.getElementById("root")!).render(<App />);
