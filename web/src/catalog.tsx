/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import React, {
    useCallback,
    useEffect,
    useLayoutEffect,
    useMemo,
    useRef,
    useState,
} from "react";
import {
    Jellyfin,
    ClientError,
    encode,
    fetchMedia,
    progress,
    runtimeLabel,
    resumeLabel,
    resumeStartMs,
    episodeLabel,
    type Item,
    type Row,
} from "@pictureshow/core";
import "./catalog.css";
import {
    projectCompletion,
    completionReadVersion,
    settleCompletionRead,
    subscribeCompletion,
} from "./completion-intents";

function useCompletionPresentation(client: Jellyfin) {
    const [, changed] = useState(0);
    useEffect(
        () => subscribeCompletion(client, () => changed((value) => value + 1)),
        [client],
    );
}
import {
    beginDetailRead,
    publishDetailRead,
    detailReadConfirmedCompletion,
    catalogAccountRevision,
    invalidateCatalogAccount,
} from "./detail-reads";

export type CatalogRoute =
    | { kind: "home" }
    | { kind: "libraries" }
    | { kind: "detail"; id: string }
    | { kind: "search" };
export type CatalogDestination =
    CatalogRoute | { kind: "player"; item: Item; start: number };
export type CatalogItem = Item & {
    Genres?: string[];
    OfficialRating?: string;
    CommunityRating?: number;
    IsFolder?: boolean;
    Role?: string;
    SeasonId?: string;
    ChildCount?: number;
    RecursiveItemCount?: number;
    PremiereDate?: string;
    BackdropImageTags?: string[];
    ParentBackdropItemId?: string;
    ParentBackdropImageTags?: string[];
    ParentThumbItemId?: string;
    ParentThumbImageTag?: string;
    People?: {
        Id: string;
        Name: string;
        Type?: string;
        IsFolder?: boolean;
        Role?: string;
        PrimaryImageTag?: string;
    }[];
    UserData?: NonNullable<Item["UserData"]> & {
        IsFavorite?: boolean;
        UnplayedItemCount?: number;
        PlayCount?: number;
    };
};
type ItemPage = { Items: CatalogItem[]; TotalRecordCount?: number };
type Props = {
    client: Jellyfin;
    navigate: (route: CatalogDestination) => void;
    onError: (error: unknown) => void;
};
const CARD_FIELDS =
    "Overview,Genres,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount";
const SEARCH_FIELDS =
    "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount";
const containers = new Set([
    "CollectionFolder",
    "UserView",
    "Folder",
    "BoxSet",
    "Playlist",
]);
const playable = (item: Item) =>
    ["Movie", "Episode", "Video"].includes(item.Type);
const accountKey = (client: Jellyfin) =>
    `${client.server.id}:${client.account!.user.Id}`;
const reportFailureMessage =
    "Playback progress could not be saved. Retry saving progress to continue.";
const errorText = (error: unknown) =>
    error instanceof Error
        ? error.message
        : "The server could not complete the request. Try again.";
const userPath = (client: Jellyfin, path: string) =>
    path +
    (path.includes("?") ? "&" : "?") +
    "userId=" +
    encode(client.account!.user.Id);
const query = (values: Record<string, string | number | boolean | undefined>) =>
    new URLSearchParams(
        Object.entries(values)
            .filter(([, v]) => v !== undefined)
            .map(([k, v]) => [k, String(v)]),
    ).toString();
function catalog<T>(client: Jellyfin, path: string) {
    return client.catalog<T>(path);
}
function notifyExpired(
    client: Jellyfin,
    error: unknown,
    onError: Props["onError"],
) {
    if (error instanceof ClientError && error.kind === "EXPIRED")
        onError(error);
}
function unique(items: CatalogItem[]) {
    return [...new Map(items.map((item) => [item.Id, item])).values()];
}
export function libraryLabel(item: Item) {
    if (["CollectionFolder", "UserView"].includes(item.Type))
        return (
            (
                {
                    movies: "Movies",
                    tvshows: "Shows",
                    boxsets: "Collections",
                    playlists: "Playlists",
                    homevideos: "Videos",
                    mixed: "Library",
                } as Record<string, string>
            )[item.CollectionType ?? ""] ?? "Library"
        );
    return (
        (
            {
                BoxSet: "Collection",
                Folder: "Folder",
                Playlist: "Playlist",
                Series: "Series",
                Season: "Season",
                Movie: "Movie",
                Episode: "Episode",
                Video: "Video",
                Person: "Person",
            } as Record<string, string>
        )[item.Type] ?? "Title"
    );
}
export function isEmptyContainer(item: CatalogItem) {
    if (
        !item.IsFolder &&
        ![...containers, "Series", "Season"].includes(item.Type)
    )
        return false;
    if ((item.ChildCount ?? 0) > 0 || (item.RecursiveItemCount ?? 0) > 0)
        return false;
    return item.ChildCount === 0 || item.RecursiveItemCount === 0;
}
function watched(item: CatalogItem) {
    return !isEmptyContainer(item) && item.UserData?.Played === true;
}
function itemState(item: CatalogItem) {
    const parts: string[] = [];
    if (watched(item)) parts.push("Watched");
    if (item.UserData?.IsFavorite) parts.push("Favorite");
    if ((item.UserData?.PlaybackPositionTicks ?? 0) > 0)
        parts.push(resumeLabel(item.UserData!.PlaybackPositionTicks!));
    const unplayed = item.UserData?.UnplayedItemCount;
    if (unplayed !== undefined && !playable(item) && unplayed > 0)
        parts.push(`${unplayed} unplayed`);
    return parts;
}
function imagePath(item: CatalogItem, width: number, wide = false) {
    const tags = item.ImageTags ?? {};
    if ((wide || item.Type === "Episode") && tags.Thumb)
        return `Items/${encode(item.Id)}/Images/Thumb?tag=${encode(tags.Thumb)}&maxWidth=${width}&quality=85`;
    if ((wide || item.Type === "Episode") && item.BackdropImageTags?.[0])
        return `Items/${encode(item.Id)}/Images/Backdrop?tag=${encode(item.BackdropImageTags[0])}&maxWidth=${width}&quality=85`;
    if (tags.Primary)
        return `Items/${encode(item.Id)}/Images/Primary?tag=${encode(tags.Primary)}&maxWidth=${width}&quality=85`;
    if (item.ParentThumbItemId && item.ParentThumbImageTag)
        return `Items/${encode(item.ParentThumbItemId)}/Images/Thumb?tag=${encode(item.ParentThumbImageTag)}&maxWidth=${width}&quality=85`;
    if (item.ParentBackdropItemId && item.ParentBackdropImageTags?.[0])
        return `Items/${encode(item.ParentBackdropItemId)}/Images/Backdrop?tag=${encode(item.ParentBackdropImageTags[0])}&maxWidth=${width}&quality=85`;
    return undefined;
}
export function PrivateImage({
    client,
    path,
    className,
    alt = "",
}: {
    client: Jellyfin;
    path: string;
    className?: string;
    alt?: string;
}) {
    const [url, setUrl] = useState<string>(),
        [failed, setFailed] = useState(false);
    useEffect(() => {
        let live = true,
            object: string | undefined;
        setUrl(undefined);
        setFailed(false);
        fetchMedia(client.account!, path)
            .then((response) => response.blob())
            .then((blob) => {
                if (live) {
                    object = URL.createObjectURL(blob);
                    setUrl(object);
                }
            })
            .catch(() => {
                if (live) setFailed(true);
            });
        return () => {
            live = false;
            if (object) URL.revokeObjectURL(object);
        };
    }, [client.account!.accessToken, path]);
    return url && !failed ? (
        <img
            className={className}
            alt={alt}
            src={url}
            onError={() => setFailed(true)}
        />
    ) : (
        <span
            className={[className, "catalog-placeholder"]
                .filter(Boolean)
                .join(" ")}
            aria-hidden="true"
        >
            ▧
        </span>
    );
}
const viewPositions = new Map<
    string,
    { y: number; shelves: Record<string, number> }
>();
function useScrollMemory(key: string, ready: boolean) {
    const root = useRef<HTMLDivElement>(null),
        restored = useRef(false);
    const storageKey = `pictureshow.catalog-scroll:${key}`;
    useLayoutEffect(() => {
        if (!ready || restored.current) return;
        restored.current = true;
        let previous = viewPositions.get(key);
        if (!previous) {
            try {
                previous = JSON.parse(
                    sessionStorage.getItem(storageKey) ?? "null",
                );
            } catch {}
        }
        if (!previous || typeof previous.y !== "number" || !previous.shelves)
            return;
        root.current
            ?.querySelectorAll<HTMLElement>("[data-shelf]")
            .forEach((el) => {
                el.scrollLeft = previous!.shelves[el.dataset.shelf!] ?? 0;
            });
        window.scrollTo(0, previous.y);
    }, [key, ready]);
    useEffect(() => {
        const node = root.current;
        const save = () => {
            const shelves: Record<string, number> = {};
            node?.querySelectorAll<HTMLElement>("[data-shelf]").forEach(
                (el) => {
                    shelves[el.dataset.shelf!] = el.scrollLeft;
                },
            );
            const value = { y: window.scrollY, shelves };
            viewPositions.set(key, value);
            try {
                sessionStorage.setItem(storageKey, JSON.stringify(value));
            } catch {}
        };
        window.addEventListener("scroll", save, { passive: true });
        node?.addEventListener("scroll", save, {
            passive: true,
            capture: true,
        });
        window.addEventListener("pagehide", save);
        return () => {
            save();
            window.removeEventListener("scroll", save);
            node?.removeEventListener("scroll", save, true);
            window.removeEventListener("pagehide", save);
        };
    }, [key]);
    return root;
}
export function Cards({
    row,
    client,
    navigate,
    grid = false,
    onOpen,
}: Props & { row: Row; grid?: boolean; onOpen?: () => void }) {
    useCompletionPresentation(client);
    return (
        <section
            className={`catalog-shelf${["resume", "next"].includes(row.key) ? " catalog-resume-shelf" : ""}`}
            aria-labelledby={`catalog-${row.key}`}
        >
            <h2 id={`catalog-${row.key}`}>{row.title}</h2>
            <ul
                className={grid ? "catalog-grid" : "catalog-cards"}
                data-shelf={row.key}
            >
                {(row.items as CatalogItem[]).map((raw, index) => {
                    const item = projectCompletion(client, raw);
                    const art = imagePath(item, 480, item.Type === "Episode"),
                        state = itemState(item);
                    const subtitle =
                        item.Type === "Episode"
                            ? `${item.SeriesName ?? ""} ${episodeLabel(item)}`.trim()
                            : item.Type === "Person"
                              ? (item.Role ?? "Person")
                              : (item.ProductionYear ?? libraryLabel(item));
                    return (
                        <li key={item.Id}>
                            <button
                                type="button"
                                className="catalog-card"
                                data-autofocus={index === 0 || undefined}
                                data-focus={`${row.key}:${item.Id}`}
                                aria-label={[
                                    [item.Name, subtitle]
                                        .filter(Boolean)
                                        .join(" "),
                                    ...state,
                                ]
                                    .filter(Boolean)
                                    .join(", ")}
                                onKeyDown={(event) => {
                                    if (
                                        row.key.startsWith("search:") &&
                                        index === 0 &&
                                        event.key === "ArrowUp"
                                    ) {
                                        event.preventDefault();
                                        event.stopPropagation();
                                        document
                                            .getElementById(
                                                "catalog-search-field",
                                            )
                                            ?.focus();
                                    }
                                }}
                                onClick={() => {
                                    onOpen?.();
                                    navigate({ kind: "detail", id: item.Id });
                                }}
                            >
                                <span
                                    className={`catalog-art ${item.Type === "Episode" ? "catalog-wide" : ""}`}
                                >
                                    {art ? (
                                        <PrivateImage
                                            client={client}
                                            path={art}
                                        />
                                    ) : (
                                        <span aria-hidden="true">
                                            {containers.has(item.Type)
                                                ? "▤"
                                                : item.Type === "Person"
                                                  ? "♙"
                                                  : "▶"}
                                        </span>
                                    )}
                                    {progress(item) > 0 && (
                                        <progress
                                            aria-hidden="true"
                                            max={1}
                                            value={progress(item)}
                                        />
                                    )}
                                    {(watched(item) ||
                                        item.UserData?.IsFavorite) && (
                                        <span
                                            className="catalog-badges"
                                            aria-hidden="true"
                                        >
                                            {watched(item) ? "✓" : ""}
                                            {item.UserData?.IsFavorite
                                                ? " ♥"
                                                : ""}
                                        </span>
                                    )}
                                </span>
                                <span className="catalog-card-title">
                                    {item.Name}
                                </span>{" "}
                                <span className="catalog-muted">
                                    {subtitle}
                                </span>
                                {state.length > 0 && (
                                    <span
                                        className="catalog-card-state"
                                        aria-hidden="true"
                                    >
                                        {state.join(" · ")}
                                    </span>
                                )}
                            </button>
                        </li>
                    );
                })}
            </ul>
        </section>
    );
}
function RetryNotice({
    error,
    retry,
    busy = false,
    label = "Retry",
    announce = true,
}: {
    error: string;
    retry: () => void;
    busy?: boolean;
    label?: string;
    announce?: boolean;
}) {
    return (
        <div className="catalog-error" role={announce ? "alert" : undefined}>
            <p>{error}</p>
            <button type="button" onClick={retry} disabled={busy}>
                {busy ? "Retrying…" : label}
            </button>
        </div>
    );
}
async function loadHome(client: Jellyfin) {
    const errors: string[] = [];
    const safe = async <T,>(
        path: string,
        fallback: T,
        name: string,
    ): Promise<T> => {
        try {
            return await catalog<T>(client, path);
        } catch (error) {
            if (error instanceof ClientError && error.kind === "EXPIRED")
                throw error;
            errors.push(`${name}: ${errorText(error)}`);
            return fallback;
        }
    };
    const [views, resume, next] = await Promise.all([
        safe<ItemPage>(
            `UserViews?fields=${encode(CARD_FIELDS)}`,
            { Items: [] },
            "Libraries",
        ),
        safe<ItemPage>(
            `UserItems/Resume?mediaTypes=Video&limit=24&fields=${encode(CARD_FIELDS)}`,
            { Items: [] },
            "Continue watching",
        ),
        safe<ItemPage>(
            `Shows/NextUp?limit=24&fields=${encode(CARD_FIELDS)}`,
            { Items: [] },
            "Next up",
        ),
    ]);
    const latest = await Promise.all(
        views.Items.filter((item) =>
            ["movies", "tvshows"].includes(item.CollectionType ?? ""),
        ).map(async (item) => ({
            key: `latest:${item.Id}`,
            title: `Latest in ${item.Name}`,
            items: await safe<CatalogItem[]>(
                `Items/Latest?parentId=${encode(item.Id)}&limit=24&fields=${encode(CARD_FIELDS)}`,
                [],
                `Latest in ${item.Name}`,
            ),
        })),
    );
    return {
        rows: [
            { key: "resume", title: "Continue watching", items: resume.Items },
            { key: "next", title: "Next up", items: next.Items },
            { key: "libraries", title: "Libraries", items: views.Items },
            ...latest,
        ].filter((row) => row.items.length),
        errors,
    };
}
const homeCache = new Map<string, Awaited<ReturnType<typeof loadHome>>>();
const homeRevisions = new Map<string, number>();
export function Home({
    client,
    navigate,
    onError,
    onSearch,
    onSettings,
    onSwitchUser,
    onSignOut,
}: Props & {
    onSearch?: () => void;
    onSettings?: () => void;
    onSwitchUser?: () => void;
    onSignOut?: () => void;
}) {
    const key = accountKey(client),
        [data, setData] = useState(homeCache.get(key)),
        [busy, setBusy] = useState(true),
        [error, setError] = useState(""),
        [attempt, setAttempt] = useState(0);
    useEffect(() => {
        let live = true;
        if (
            attempt === 0 &&
            homeCache.has(key) &&
            homeRevisions.get(key) === catalogAccountRevision(client)
        ) {
            setData(homeCache.get(key));
            setBusy(false);
            return;
        }
        const revision = catalogAccountRevision(client);
        setBusy(true);
        setError("");
        loadHome(client)
            .then((value) => {
                if (live) {
                    homeCache.set(key, value);
                    homeRevisions.set(key, revision);
                    setData(value);
                }
            })
            .catch((error) => {
                if (live) {
                    setError(errorText(error));
                    notifyExpired(client, error, onError);
                }
            })
            .finally(() => {
                if (live) setBusy(false);
            });
        return () => {
            live = false;
        };
    }, [key, attempt, client.account!.accessToken]);
    const ref = useScrollMemory(`home:${key}`, !!data);
    return (
        <div className="catalog-page" ref={ref}>
            <div className="catalog-home-heading">
                <div>
                    <p className="catalog-eyebrow">{client.server.name}</p>
                    <h1>{client.account!.user.Name}</h1>
                </div>
                <nav aria-label="Home actions">
                    <button
                        data-focus="home-search"
                        onClick={
                            onSearch ?? (() => navigate({ kind: "search" }))
                        }
                    >
                        Search
                    </button>
                    {onSettings && (
                        <button data-focus="home-settings" onClick={onSettings}>
                            Settings
                        </button>
                    )}
                </nav>
            </div>
            {!data && busy && <p role="status">Loading your library…</p>}
            {error && (
                <RetryNotice
                    error={error}
                    retry={() => setAttempt((value) => value + 1)}
                    busy={busy}
                />
            )}
            {data?.rows.map((row) => (
                <Cards
                    key={row.key}
                    row={row}
                    client={client}
                    navigate={navigate}
                    onError={onError}
                />
            ))}
            {data && !data.rows.length && (
                <div className="catalog-empty">
                    <h2>Your library is empty.</h2>
                    <p>Add media to your Jellyfin server, then refresh.</p>
                    <button
                        onClick={() => setAttempt((value) => value + 1)}
                        disabled={busy}
                    >
                        Refresh
                    </button>
                </div>
            )}
            {!!data?.errors.length && (
                <RetryNotice
                    error={`Some rows could not load. ${data.errors.join(" ")}`}
                    retry={() => setAttempt((value) => value + 1)}
                    busy={busy}
                />
            )}
            <div className="catalog-account-actions">
                {onSwitchUser && (
                    <button data-focus="switch-user" onClick={onSwitchUser}>
                        Switch user
                    </button>
                )}
                {onSignOut && (
                    <button data-focus="sign-out" onClick={onSignOut}>
                        Sign out
                    </button>
                )}
            </div>
        </div>
    );
}
export function Libraries({ client, navigate, onError }: Props) {
    const [items, setItems] = useState<CatalogItem[]>(),
        [error, setError] = useState(""),
        [attempt, setAttempt] = useState(0);
    useEffect(() => {
        let live = true;
        setError("");
        catalog<ItemPage>(client, `UserViews?fields=${encode(CARD_FIELDS)}`)
            .then((page) => {
                if (live) setItems(page.Items);
            })
            .catch((error) => {
                if (live) {
                    setError(errorText(error));
                    notifyExpired(client, error, onError);
                }
            });
        return () => {
            live = false;
        };
    }, [accountKey(client), client.account!.accessToken, attempt]);
    const ref = useScrollMemory(`libraries:${accountKey(client)}`, !!items);
    return (
        <div className="catalog-page" ref={ref}>
            <h1>Libraries</h1>
            {!items && !error && <p role="status">Loading libraries…</p>}
            {error && (
                <RetryNotice
                    error={error}
                    retry={() => setAttempt((value) => value + 1)}
                />
            )}
            {items?.length ? (
                <Cards
                    row={{ key: "libraries", title: "Your libraries", items }}
                    grid
                    client={client}
                    navigate={navigate}
                    onError={onError}
                />
            ) : (
                items && (
                    <div className="catalog-empty">
                        <p>No libraries are available.</p>
                        <button
                            onClick={() => setAttempt((value) => value + 1)}
                        >
                            Refresh
                        </button>
                    </div>
                )
            )}
        </div>
    );
}

type DetailData = {
    item: CatalogItem;
    seasons: CatalogItem[];
    episodes: CatalogItem[];
    next?: CatalogItem;
    completionRead?: number;
};
const detailCache = new Map<string, DetailData>();
const mutations = new Map<string, number>();
function freshBudget<T>(
    operation: Promise<T>,
    signal: AbortSignal,
): Promise<T> {
    return new Promise((resolve, reject) => {
        const timer = window.setTimeout(
            () =>
                reject(
                    new Error(
                        "The server took too long. Retry to load fresh item information.",
                    ),
                ),
            30000,
        );
        const cancel = () => {
            window.clearTimeout(timer);
            reject(new DOMException("Aborted", "AbortError"));
        };
        signal.addEventListener("abort", cancel, { once: true });
        operation.then(resolve, reject).finally(() => {
            clearTimeout(timer);
            signal.removeEventListener("abort", cancel);
        });
    });
}
async function detailData(client: Jellyfin, id: string): Promise<DetailData> {
    const completionRead = completionReadVersion();
    const item = (await client.detail(id)) as CatalogItem;
    let seasons: CatalogItem[] = [],
        episodes: CatalogItem[] = [],
        next: CatalogItem | undefined;
    if (item.Type === "Series") {
        const [seasonPage, episodePage, nextPage] = await Promise.all([
            catalog<ItemPage>(
                client,
                `Shows/${encode(id)}/Seasons?fields=${encode(CARD_FIELDS)}`,
            ),
            catalog<ItemPage>(
                client,
                `Shows/${encode(id)}/Episodes?fields=${encode(CARD_FIELDS)}`,
            ),
            catalog<ItemPage>(
                client,
                `Shows/NextUp?seriesId=${encode(id)}&limit=24&fields=${encode(CARD_FIELDS)}`,
            ),
        ]);
        seasons = seasonPage.Items;
        episodes = episodePage.Items;
        next = nextPage.Items.find(playable);
    } else if (item.Type === "Season")
        episodes = (
            await catalog<ItemPage>(
                client,
                `Shows/${encode(item.SeriesId!)}/Episodes?seasonId=${encode(id)}&fields=${encode(CARD_FIELDS)}`,
            )
        ).Items;
    return { item, seasons, episodes, next, completionRead };
}
function detailPlay(data: DetailData): CatalogItem | undefined {
    if (playable(data.item)) return data.item;
    if (!["Series", "Season"].includes(data.item.Type)) return;
    return (
        data.next ??
        data.episodes.find((item) => playable(item) && !watched(item)) ??
        data.episodes.find(playable)
    );
}
type PendingAction =
    | { kind: "play" }
    | { kind: "restart" }
    | { kind: "watched" | "favorite"; desired: boolean };
function sameAction(a: PendingAction, b: PendingAction) {
    return (
        a.kind === b.kind &&
        (!("desired" in a) || ("desired" in b && a.desired === b.desired))
    );
}
export function Detail({
    client,
    id,
    navigate,
    onError,
    reportBarrier,
    retryReport,
    onBack,
}: Props & {
    id: string;
    reportBarrier?: Promise<void>;
    retryReport?: () => Promise<void>;
    onBack?: () => void;
}) {
    useCompletionPresentation(client);
    const cacheKey = `${accountKey(client)}:${id}`,
        initial = detailCache.get(cacheKey);
    const [data, setData] = useState<DetailData | undefined>(initial),
        [header, setHeader] = useState<CatalogItem | undefined>(initial?.item),
        [loading, setLoading] = useState(true),
        [readError, setReadError] = useState(""),
        [writeError, setWriteError] = useState(""),
        [reportError, setReportError] = useState(""),
        [savingReport, setSavingReport] = useState(!!reportBarrier),
        [attempt, setAttempt] = useState(0),
        [writing, setWriting] = useState(false),
        [pendingMessage, setPendingMessage] = useState(""),
        [season, setSeason] = useState<string>(),
        [feedback, setFeedback] = useState(""),
        [focusedAction, setFocusedAction] = useState<string>(),
        [graceElapsed, setGraceElapsed] = useState(false);
    const dataRef = useRef(data),
        readyRef = useRef(false),
        queue = useRef<{ action: PendingAction; expires: number }[]>([]),
        alive = useRef(true),
        readGen = useRef(0),
        writePending = useRef<PendingAction | undefined>(undefined),
        processing = useRef(false),
        openingPlayback = useRef(false),
        retryRef = useRef<HTMLButtonElement>(null),
        reportRetryRef = useRef<HTMLButtonElement>(null),
        primaryRef = useRef<HTMLButtonElement>(null),
        favoriteRef = useRef<HTMLButtonElement>(null),
        statusRef = useRef<HTMLDivElement>(null),
        lastWrite = useRef<PendingAction | undefined>(undefined),
        externalPlay = useRef<() => void>(() => {}),
        heldFocus = useRef<string | undefined>(undefined),
        heldElement = useRef<HTMLElement | null>(null),
        requestedCue = useRef<string | undefined>(undefined),
        presentedCompletion = useRef(false),
        latestRead = useRef(0),
        requestExpired = useRef(false),
        cueTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined),
        cuePending = useRef<
            | {
                  text: string;
                  expires: number;
                  kind: "ready" | "request" | "failure";
              }
            | undefined
        >(undefined);
    dataRef.current = data;
    const reportReady = useRef(!reportBarrier),
        retryReportRef = useRef(retryReport),
        reportRetryRunning = useRef(false),
        barrierIdentity = useRef(reportBarrier),
        clearReportErrorOnRead = useRef(false);
    retryReportRef.current = retryReport;
    barrierIdentity.current = reportBarrier;
    const notify = useCallback(
        (error: unknown) => notifyExpired(client, error, onError),
        [client.account!.accessToken],
    );
    useEffect(() => {
        alive.current = true;
        return () => {
            alive.current = false;
            queue.current = [];
            heldFocus.current = undefined;
            heldElement.current = null;
            cuePending.current = undefined;
            clearTimeout(cueTimer.current);
            readGen.current++;
        };
    }, [cacheKey]);
    function flushCue() {
        clearTimeout(cueTimer.current);
        const pending = cuePending.current;
        if (!pending || !alive.current) return;
        if (
            Date.now() >= pending.expires ||
            document.visibilityState !== "visible"
        ) {
            cuePending.current = undefined;
            return;
        }
        setFeedback(pending.text);
        cuePending.current = undefined;
    }
    function cue(text: string, kind: "ready" | "request" | "failure") {
        clearTimeout(cueTimer.current);
        setFeedback("");
        cuePending.current = { text, kind, expires: Date.now() + 30000 };
        cueTimer.current = setTimeout(flushCue, 180);
    }
    function cancelReady() {
        heldFocus.current = undefined;
        heldElement.current = null;
        if (cuePending.current?.kind === "ready") {
            cuePending.current = undefined;
            clearTimeout(cueTimer.current);
        }
        setFeedback("");
    }
    useEffect(() => {
        const clearReaderVisit = () => {
            if (document.visibilityState === "visible") return;
            heldFocus.current = undefined;
            heldElement.current = null;
            cuePending.current = undefined;
            clearTimeout(cueTimer.current);
            setFeedback("");
        };
        document.addEventListener("visibilitychange", clearReaderVisit);
        return () =>
            document.removeEventListener("visibilitychange", clearReaderVisit);
    }, [cacheKey]);
    function publishReportOutcome(error?: unknown) {
        // Pre-ack detail data cannot authorize a resume/next target. Invalidate
        // synchronously before scheduling the authoritative post-report read.
        readyRef.current = false;
        readGen.current++;
        reportReady.current = error === undefined || !retryReportRef.current;
        setSavingReport(false);
        if (
            error === undefined &&
            detailReadConfirmedCompletion(client, id, latestRead.current)
        ) {
            setReportError("");
            setLoading(false);
            return;
        }
        setLoading(true);
        clearReportErrorOnRead.current = error === undefined;
        if (error !== undefined) {
            cancelReady();
            cue(reportFailureMessage, "failure");
            setReportError(reportFailureMessage);
        }
        setAttempt((value) => value + 1);
    }
    async function retrySavingProgress() {
        const retry = retryReportRef.current;
        if (
            !retry ||
            reportRetryRunning.current ||
            clearReportErrorOnRead.current
        )
            return;
        const owner = barrierIdentity.current;
        reportRetryRunning.current = true;
        readyRef.current = false;
        reportReady.current = false;
        setSavingReport(true);
        try {
            await retry();
            if (alive.current && barrierIdentity.current === owner)
                publishReportOutcome();
        } catch (error) {
            if (alive.current && barrierIdentity.current === owner)
                publishReportOutcome(error);
        } finally {
            reportRetryRunning.current = false;
        }
    }
    useEffect(() => {
        reportReady.current = !reportBarrier;
        clearReportErrorOnRead.current = false;
        if (reportBarrier) readyRef.current = false;
        setSavingReport(!!reportBarrier);
        setReportError("");
        let live = true;
        reportBarrier
            ?.then(() => {
                if (live) publishReportOutcome();
            })
            .catch((error) => {
                // A report failure is separate from a watched/favorite write. Read
                // current server data now, but guard commands until explicit report
                // retry succeeds when the player supplied a snapshot retry callback.
                if (live) publishReportOutcome(error);
            });
        return () => {
            live = false;
        };
    }, [reportBarrier]);
    useEffect(() => {
        const gen = ++readGen.current,
            ticket = beginDetailRead(),
            controller = new AbortController(),
            revision = mutations.get(cacheKey) ?? 0;
        let live = true;
        readyRef.current = false;
        setLoading(true);
        setGraceElapsed(false);
        cancelReady();
        const grace = setTimeout(() => {
            if (live) setGraceElapsed(true);
        }, 1500);
        freshBudget(detailData(client, id), controller.signal)
            .then((value) => {
                if (!live || gen !== readGen.current) return;
                if ((mutations.get(cacheKey) ?? 0) !== revision) {
                    setAttempt((v) => v + 1);
                    return;
                }
                for (const raw of [
                    value.item,
                    ...value.seasons,
                    ...value.episodes,
                    ...(value.next ? [value.next] : []),
                ])
                    settleCompletionRead(client, raw, value.completionRead);
                detailCache.set(cacheKey, value);
                dataRef.current = value;
                setData(value);
                setHeader((old) => old ?? value.item);
                setSeason((old) =>
                    old && value.seasons.some((s) => s.Id === old)
                        ? old
                        : value.seasons[0]?.Id,
                );
                const hadRetryFocus =
                    document.activeElement === retryRef.current ||
                    document.activeElement === reportRetryRef.current;
                if (clearReportErrorOnRead.current) {
                    setReportError("");
                    clearReportErrorOnRead.current = false;
                }
                setReadError("");
                setLoading(false);
                latestRead.current = ticket;
                publishDetailRead(client, value.item, ticket);
                if (hadRetryFocus)
                    requestAnimationFrame(() => {
                        const active = document.activeElement;
                        if (
                            alive.current &&
                            (active === document.body ||
                                active === retryRef.current ||
                                active === reportRetryRef.current)
                        )
                            (detailPlay(value)
                                ? primaryRef.current
                                : favoriteRef.current
                            )?.focus();
                    });
            })
            .catch((error) => {
                if (
                    !live ||
                    gen !== readGen.current ||
                    error?.name === "AbortError"
                )
                    return;
                setLoading(false);
                cancelReady();
                requestExpired.current ||= queue.current.length > 0;
                queue.current = [];
                requestedCue.current = undefined;
                setPendingMessage("");
                const reason = errorText(error);
                setReadError(reason);
                cue(
                    `Loading failed. ${reason} Retry is available.${requestExpired.current ? " The previous request expired and will not run." : ""}`,
                    "failure",
                );
                notify(error);
            });
        return () => {
            live = false;
            clearTimeout(grace);
            controller.abort();
        };
    }, [cacheKey, client.account!.accessToken, attempt]);
    const ready =
        !!data &&
        !loading &&
        !readError &&
        !savingReport &&
        reportReady.current;
    readyRef.current = ready;
    const selected = focusedAction;
    useLayoutEffect(() => {
        let active = document.activeElement as HTMLElement | null;
        if (
            ready &&
            heldFocus.current &&
            heldElement.current &&
            !heldElement.current.isConnected &&
            active === document.body
        ) {
            const replacement = document.querySelector<HTMLElement>(
                `.catalog-page [data-focus="${heldFocus.current}"]`,
            );
            replacement?.focus();
            active = document.activeElement as HTMLElement | null;
        }
        const action = active?.dataset.focus;
        const sameHeld = action && action === heldFocus.current;
        const usable =
            active?.getAttribute("aria-disabled") === "false" &&
            !active?.hasAttribute("disabled");
        if (
            !ready &&
            graceElapsed &&
            !readError &&
            queue.current.length === 0 &&
            action &&
            ["play", "watched", "favorite", "restart"].includes(action)
        ) {
            heldFocus.current = action;
            heldElement.current = active;
        } else if (
            ready &&
            sameHeld &&
            usable &&
            document.visibilityState === "visible"
        ) {
            heldFocus.current = undefined;
            cue("Ready", "ready");
        } else if (action !== heldFocus.current) heldFocus.current = undefined;
    }, [ready, selected, graceElapsed, readError]);
    function detailFocus(event: React.FocusEvent) {
        const action = (event.target as HTMLElement).dataset.focus;
        if (action !== focusedAction) {
            // Ordinary focus owns the next speech. A failure/acceptance waits
            // behind it; a Ready for the previous action is discarded.
            if (cuePending.current?.kind === "ready") cancelReady();
            else {
                setFeedback("");
                if (cuePending.current) {
                    clearTimeout(cueTimer.current);
                    cueTimer.current = setTimeout(flushCue, 180);
                }
            }
            requestedCue.current = undefined;
            setFocusedAction(action);
        }
    }
    function retryDetail() {
        if (loading) return;
        requestedCue.current = undefined;
        requestExpired.current = false;
        cuePending.current = undefined;
        clearTimeout(cueTimer.current);
        setFeedback("");
        setAttempt((value) => value + 1);
    }
    const process = useCallback(async () => {
        if (processing.current || !readyRef.current || !alive.current) return;
        processing.current = true;
        try {
            while (queue.current.length && readyRef.current && alive.current) {
                const pending = queue.current.shift()!;
                if (pending.expires < Date.now()) {
                    requestExpired.current = true;
                    setPendingMessage(
                        "The previous request expired and will not run. Choose it again.",
                    );
                    continue;
                }
                const current = dataRef.current;
                if (!current) break;
                const action = pending.action;
                requestedCue.current = undefined;
                setFeedback("");
                setPendingMessage("");
                if (action.kind === "play" || action.kind === "restart") {
                    const target = detailPlay(current);
                    if (!target) {
                        setPendingMessage(
                            ["Series", "Season"].includes(current.item.Type)
                                ? "No episodes are available."
                                : current.item.Type === "Person"
                                  ? "Choose a movie or show from this person's filmography to play."
                                  : "Choose a title from this collection to play.",
                        );
                        requestAnimationFrame(() => {
                            if (alive.current)
                                (
                                    favoriteRef.current ??
                                    document.querySelector<HTMLElement>(
                                        ".catalog-detail-page .catalog-card",
                                    )
                                )?.focus();
                        });
                        continue;
                    }
                    openingPlayback.current = true;
                    readyRef.current = false;
                    queue.current = [];
                    navigate({
                        kind: "player",
                        item: target,
                        start:
                            action.kind === "restart"
                                ? 0
                                : resumeStartMs(
                                      target.UserData?.PlaybackPositionTicks ??
                                          0,
                                  ) / 1000,
                    });
                    return;
                }
                if (
                    current.item.Type === "Person" ||
                    (action.kind === "watched" &&
                        (isEmptyContainer(current.item) ||
                            (["Series", "Season"].includes(current.item.Type) &&
                                !current.episodes.length)))
                ) {
                    setPendingMessage(
                        current.item.Type === "Person"
                            ? "That action is unavailable for a person. Choose a movie or show from the filmography."
                            : "An empty collection cannot be marked watched.",
                    );
                    requestAnimationFrame(() => {
                        if (alive.current)
                            (
                                favoriteRef.current ??
                                document.querySelector<HTMLElement>(
                                    ".catalog-detail-page .catalog-card",
                                )
                            )?.focus();
                    });
                    continue;
                }
                const accepted =
                    action.kind === "watched"
                        ? watched(current.item)
                        : !!current.item.UserData?.IsFavorite;
                if (accepted === action.desired) continue;
                lastWrite.current = action;
                writePending.current = action;
                setWriting(true);
                setWriteError("");
                try {
                    const response = await client.request<
                        CatalogItem["UserData"]
                    >(
                        userPath(
                            client,
                            `${action.kind === "watched" ? "UserPlayedItems" : "UserFavoriteItems"}/${encode(id)}`,
                        ),
                        undefined,
                        action.desired ? "POST" : "DELETE",
                    );
                    if (!alive.current) return;
                    mutations.set(cacheKey, (mutations.get(cacheKey) ?? 0) + 1);
                    const next = {
                        ...current,
                        item: {
                            ...current.item,
                            UserData: { ...current.item.UserData, ...response },
                        },
                    };
                    // Accept the returned authoritative state, never invent parent counts.
                    detailCache.set(cacheKey, next);
                    dataRef.current = next;
                    setData(next);
                    invalidateCatalogAccount(client);
                    writePending.current = undefined;
                    lastWrite.current = undefined;
                    // Fresh dependent reads can alter resume/Next up; commands wait for them.
                    readyRef.current = false;
                    setAttempt((value) => value + 1);
                } catch (error) {
                    if (!alive.current) return;
                    queue.current = [];
                    setWriteError(errorText(error));
                    cancelReady();
                    cue(
                        `Change failed. ${errorText(error)} Retry saving is available.`,
                        "failure",
                    );
                    notify(error);
                } finally {
                    if (alive.current) {
                        setWriting(false);
                        writePending.current = undefined;
                    }
                }
            }
        } finally {
            processing.current = false;
        }
    }, [cacheKey, id, client.account!.accessToken, navigate]);
    useEffect(() => {
        if (ready) void process();
    }, [ready, data, process]);
    useEffect(() => {
        if (!queue.current.length) return;
        const timer = window.setInterval(() => {
            const before = queue.current.length;
            queue.current = queue.current.filter((p) => p.expires > Date.now());
            if (before !== queue.current.length) {
                requestedCue.current = undefined;
                requestExpired.current = true;
                cancelReady();
                setPendingMessage(
                    "The previous request expired and will not run. Choose it again.",
                );
            }
        }, 1000);
        return () => clearInterval(timer);
    }, [pendingMessage, loading, savingReport]);
    function offer(action: PendingAction) {
        if (!alive.current || openingPlayback.current || writing) return;
        if (queue.current.some((p) => sameAction(p.action, action))) return;
        if (queue.current.length >= 32) {
            setPendingMessage(
                "Too many actions are waiting. Wait for this item, or go back.",
            );
            return;
        }
        queue.current.push({ action, expires: Date.now() + 30000 });
        if (!readyRef.current) {
            cancelReady();
            requestedCue.current = action.kind;
            cue(
                action.kind === "play"
                    ? "Play requested"
                    : action.kind === "restart"
                      ? "Start requested"
                      : `${action.kind === "watched" ? "Watched" : "Favorite"} change requested`,
                "request",
            );
            setPendingMessage(
                action.kind === "play"
                    ? "Play requested. Waiting for fresh item information…"
                    : action.kind === "restart"
                      ? "Play from start requested. Waiting for fresh item information…"
                      : `${action.kind === "watched" ? "Watched" : "Favorite"} change requested. Waiting for fresh item information…`,
            );
        }
        void process();
    }
    function queuedActionName(kind: PendingAction["kind"]) {
        if (!queue.current.some((p) => p.action.kind === kind))
            return undefined;
        const noun =
            kind === "play"
                ? "Play"
                : kind === "restart"
                  ? "Start"
                  : kind === "watched"
                    ? "Watched"
                    : "Favorite";
        return `${noun} already requested`;
    }
    function actionLabel(kind: PendingAction["kind"], normal: string) {
        const requested = queuedActionName(kind);
        if (requested)
            return (
                <span className="catalog-requested-label">
                    {requested.slice(0, -" already requested".length)}{" "}
                    <small>already requested</small>
                </span>
            );
        return !readyRef.current && graceElapsed && !readError ? (
            <span className="catalog-requested-label">
                {normal} <small>when ready</small>
            </span>
        ) : (
            normal
        );
    }
    const readRecovery = readError ? (
        <div className="catalog-error" aria-live="off">
            <p>
                {loading ? (
                    <>
                        Loading item. Please wait.
                        <small className="catalog-previous-reason">
                            Previous attempt: {readError}
                        </small>
                    </>
                ) : (
                    readError
                )}
            </p>
            <button
                data-focus="detail-retry"
                aria-description={
                    requestExpired.current
                        ? "The previous request expired and will not run"
                        : undefined
                }
                ref={retryRef}
                aria-disabled={loading}
                onClick={retryDetail}
            >
                {loading ? "Retrying…" : "Retry"}
            </button>
        </div>
    ) : null;
    const reportRecovery = reportError ? (
        <div className="catalog-error" aria-live="off">
            <p>{reportError}</p>
            {retryReport ? (
                <button
                    ref={reportRetryRef}
                    onClick={() => void retrySavingProgress()}
                    aria-disabled={
                        savingReport || clearReportErrorOnRead.current
                    }
                >
                    {savingReport
                        ? "Saving progress…"
                        : clearReportErrorOnRead.current
                          ? "Loading item…"
                          : "Retry saving progress"}
                </button>
            ) : (
                <button
                    onClick={() => {
                        if (!loading) {
                            readyRef.current = false;
                            setLoading(true);
                            setAttempt((value) => value + 1);
                        }
                    }}
                    aria-disabled={loading}
                >
                    Reload item information
                </button>
            )}
        </div>
    ) : null;
    const feedbackNode = (
        <div
            className="catalog-reader-feedback"
            data-detail-feedback
            aria-live="polite"
            aria-atomic="true"
        >
            {feedback}
        </div>
    );
    externalPlay.current = () => offer({ kind: "play" });
    useEffect(() => {
        const session = navigator.mediaSession;
        if (!session) return;
        try {
            session.setActionHandler("play", () => externalPlay.current());
        } catch {}
        return () => {
            try {
                session.setActionHandler("play", null);
            } catch {}
        };
    }, [cacheKey]);
    function detailInput(event: React.KeyboardEvent) {
        if (["MediaPlay", "MediaPlayPause"].includes(event.key)) {
            event.preventDefault();
            event.stopPropagation();
            if (!event.repeat) offer({ kind: "play" });
        } else if (
            ["Escape", "Backspace"].includes(event.key) &&
            !(event.target instanceof HTMLInputElement)
        )
            queue.current = [];
    }
    function actionKey(event: React.KeyboardEvent) {
        if (event.repeat && ["Enter", " "].includes(event.key)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }
    const root = useScrollMemory(`detail:${cacheKey}`, !!data),
        shown = header ?? data?.item;
    if (!shown)
        return (
            <div
                className="catalog-page catalog-loading-detail"
                ref={root}
                onKeyDown={detailInput}
                onFocusCapture={detailFocus}
            >
                {feedbackNode}
                {readRecovery}
                {reportRecovery}
                <div className="catalog-loading-slots">
                    <h1>
                        {readError && !loading
                            ? "Item unavailable"
                            : "Loading item"}
                    </h1>
                    <div
                        className="catalog-detail-actions"
                        onKeyDown={actionKey}
                    >
                        <button
                            ref={primaryRef}
                            className="primary"
                            data-focus="play"
                            aria-label={queuedActionName("play")}
                            data-autofocus
                            aria-disabled="true"
                            onClick={() => offer({ kind: "play" })}
                        >
                            {actionLabel("play", "Play")}
                        </button>
                        <button
                            data-focus="watched"
                            aria-label={queuedActionName("watched")}
                            aria-disabled="true"
                            onClick={() =>
                                offer({ kind: "watched", desired: true })
                            }
                        >
                            {actionLabel("watched", "Watched")}
                        </button>
                        <button
                            ref={favoriteRef}
                            data-focus="favorite"
                            aria-label={queuedActionName("favorite")}
                            aria-disabled="true"
                            onClick={() =>
                                offer({ kind: "favorite", desired: true })
                            }
                        >
                            {actionLabel("favorite", "Favorite")}
                        </button>
                        <button
                            data-focus="restart"
                            aria-label={queuedActionName("restart")}
                            aria-disabled="true"
                            onClick={() => offer({ kind: "restart" })}
                        >
                            {actionLabel("restart", "Play from start")}
                        </button>
                    </div>
                </div>
                {!readError && !reportError && !writeError && (
                    <p aria-live="off">
                        {pendingMessage ||
                            (savingReport
                                ? "Saving progress…"
                                : "Loading item…")}
                    </p>
                )}
            </div>
        );
    const current = projectCompletion(client, data?.item ?? shown),
        target = data ? detailPlay(data) : undefined,
        isContainer = ["Series", "Season"].includes(shown.Type),
        isLibrary = ["CollectionFolder", "Folder", "UserView"].includes(
            shown.Type,
        ),
        empty =
            isEmptyContainer(current) ||
            (isContainer && data && !data.episodes.length),
        canMark = shown.Type !== "Person" && !empty,
        art = imagePath(shown, 900, shown.Type === "Episode"),
        primary = target
            ? (target.UserData?.PlaybackPositionTicks ?? 0) > 0
                ? resumeLabel(target.UserData!.PlaybackPositionTicks!)
                : "▶ Play"
            : "Play",
        seriesContext =
            shown.SeriesName ??
            (shown.Type === "Season"
                ? data?.episodes.find((e) => e.SeriesName)?.SeriesName
                : undefined),
        acceptedWatched = watched(current),
        acceptedFavorite = !!current.UserData?.IsFavorite;
    // A failed completion restores its own known raw value even while report
    // recovery holds commands. Unrelated cached controls retain neutral state
    // until their current read succeeds.
    if (current !== (data?.item ?? shown)) presentedCompletion.current = true;
    const episodes =
        data?.episodes.filter(
            (item) =>
                !season ||
                shown.Type !== "Series" ||
                item.SeasonId === season ||
                data.seasons.find((s) => s.Id === season)?.IndexNumber ==
                    null ||
                item.ParentIndexNumber ===
                    data.seasons.find((s) => s.Id === season)?.IndexNumber,
        ) ?? [];
    const detailStatus = !readError &&
        !reportError &&
        !writeError &&
        (!ready || pendingMessage) && (
            <div
                className="catalog-detail-status"
                aria-live="off"
                ref={statusRef}
            >
                {pendingMessage ||
                    (savingReport
                        ? loading
                            ? "Saving progress…"
                            : "Item loaded. Saving progress…"
                        : loading
                          ? isContainer
                              ? "Loading episodes…"
                              : "Loading item…"
                          : "")}
            </div>
        );
    return (
        <div
            className={`catalog-page catalog-detail-page${isLibrary ? " catalog-library-page" : ""}${shown.Type === "Person" ? " catalog-person-page" : ""}${shown.Type === "Episode" ? " catalog-episode-page" : ""}`}
            ref={root}
            onKeyDown={detailInput}
            onFocusCapture={detailFocus}
        >
            {feedbackNode}
            {readRecovery}
            {reportRecovery}
            {detailStatus}
            <article aria-label={shown.Name}>
                <section className="catalog-detail-hero">
                    <div className="catalog-detail-copy">
                        <p className="catalog-eyebrow">
                            {shown.Type === "Episode"
                                ? `${shown.SeriesName ?? ""} · ${episodeLabel(shown)}`
                                : shown.Type === "Season" && seriesContext
                                  ? `${seriesContext} · Season`
                                  : libraryLabel(shown)}
                        </p>
                        <h1>{shown.Name}</h1>
                        <p className="catalog-metadata">
                            {[
                                shown.ProductionYear,
                                shown.OfficialRating,
                                runtimeLabel(shown.RunTimeTicks),
                                typeof shown.CommunityRating === "number"
                                    ? `★ ${shown.CommunityRating.toFixed(1)}`
                                    : undefined,
                            ]
                                .filter(Boolean)
                                .join(" · ")}
                        </p>
                        <p className="catalog-overview">{shown.Overview}</p>
                        {shown.Genres?.length ? (
                            <p className="catalog-genres">
                                {shown.Genres.join(" · ")}
                            </p>
                        ) : null}
                        {shown.Type !== "Person" && !isLibrary && (
                            <div
                                className="catalog-detail-actions"
                                onKeyDown={actionKey}
                            >
                                {(playable(shown) || isContainer) && (
                                    <button
                                        ref={primaryRef}
                                        className="primary"
                                        data-focus="play"
                                        aria-label={queuedActionName("play")}
                                        data-autofocus
                                        aria-disabled={!ready || !target}
                                        disabled={!target && !loading}
                                        onClick={() => offer({ kind: "play" })}
                                    >
                                        {ready
                                            ? primary
                                            : actionLabel("play", "Play")}
                                    </button>
                                )}
                                {(playable(shown) || isContainer) &&
                                    (target?.UserData?.PlaybackPositionTicks ??
                                        0) > 0 &&
                                    !initial && (
                                        <button
                                            data-focus="restart"
                                            aria-label={queuedActionName(
                                                "restart",
                                            )}
                                            aria-disabled={!ready}
                                            onClick={() =>
                                                offer({ kind: "restart" })
                                            }
                                        >
                                            {ready
                                                ? "Play from start"
                                                : actionLabel(
                                                      "restart",
                                                      "Play from start",
                                                  )}
                                        </button>
                                    )}
                                <button
                                    data-focus="watched"
                                    aria-label={queuedActionName("watched")}
                                    aria-pressed={
                                        ready ||
                                        (presentedCompletion.current &&
                                            typeof current.UserData?.Played ===
                                                "boolean")
                                            ? acceptedWatched
                                            : undefined
                                    }
                                    aria-disabled={!ready || !canMark}
                                    disabled={!canMark}
                                    onClick={() =>
                                        offer({
                                            kind: "watched",
                                            desired: !acceptedWatched,
                                        })
                                    }
                                >
                                    {writing &&
                                    writePending.current?.kind === "watched"
                                        ? "Saving watched…"
                                        : ready
                                          ? acceptedWatched
                                              ? "Mark unwatched"
                                              : "Mark watched"
                                          : actionLabel("watched", "Watched")}
                                </button>
                                <button
                                    ref={favoriteRef}
                                    data-focus="favorite"
                                    aria-label={queuedActionName("favorite")}
                                    aria-pressed={
                                        ready ? acceptedFavorite : undefined
                                    }
                                    aria-disabled={!ready}
                                    onClick={() =>
                                        offer({
                                            kind: "favorite",
                                            desired: !acceptedFavorite,
                                        })
                                    }
                                >
                                    {writing &&
                                    writePending.current?.kind === "favorite"
                                        ? "Saving favorite…"
                                        : ready
                                          ? acceptedFavorite
                                              ? "Remove favorite"
                                              : "Add favorite"
                                          : actionLabel("favorite", "Favorite")}
                                </button>
                                {(playable(shown) || isContainer) &&
                                    initial && (
                                        <button
                                            data-focus="restart"
                                            aria-label={queuedActionName(
                                                "restart",
                                            )}
                                            aria-disabled={!ready || !target}
                                            disabled={!target && !loading}
                                            onClick={() =>
                                                offer({ kind: "restart" })
                                            }
                                        >
                                            {ready
                                                ? "Play from start"
                                                : actionLabel(
                                                      "restart",
                                                      "Play from start",
                                                  )}
                                        </button>
                                    )}
                            </div>
                        )}
                        {empty && (
                            <p className="catalog-empty">
                                {isContainer
                                    ? "No episodes are available."
                                    : "This collection is empty."}
                            </p>
                        )}
                    </div>
                    {!isLibrary &&
                        (art ? (
                            <PrivateImage
                                className="catalog-detail-art"
                                client={client}
                                path={art}
                            />
                        ) : (
                            <div
                                className="catalog-detail-art catalog-placeholder"
                                aria-hidden="true"
                            >
                                {shown.Type === "Person" ? "♙" : "▶"}
                            </div>
                        ))}
                </section>
                {writeError && (
                    <RetryNotice
                        announce={false}
                        error={`Change failed. ${writeError}`}
                        retry={() => {
                            const retry = lastWrite.current;
                            setWriteError("");
                            if (retry) offer(retry);
                        }}
                        busy={writing}
                        label="Retry saving"
                    />
                )}
                {data &&
                    shown.Overview !== data.item.Overview &&
                    data.item.Overview && (
                        <section>
                            <h2>More details</h2>
                            <p>{data.item.Overview}</p>
                        </section>
                    )}
                {data?.next && (
                    <Cards
                        row={{
                            key: `next:${id}`,
                            title: "Next up",
                            items: [data.next],
                        }}
                        client={client}
                        navigate={navigate}
                        onError={onError}
                    />
                )}
                {!!data?.seasons.length && (
                    <section aria-label="Seasons">
                        <h2>Seasons</h2>
                        <div className="catalog-season-chips">
                            {data.seasons.map((s) => (
                                <button
                                    key={s.Id}
                                    data-focus={`season:${s.Id}`}
                                    aria-pressed={
                                        ready ? season === s.Id : undefined
                                    }
                                    aria-disabled={!ready}
                                    onKeyDown={actionKey}
                                    onClick={() => {
                                        if (readyRef.current) setSeason(s.Id);
                                    }}
                                >
                                    Show episodes from {s.Name}
                                </button>
                            ))}
                        </div>
                    </section>
                )}
                {!!data?.seasons.length && (
                    <Cards
                        row={{
                            key: `seasons:${id}`,
                            title: "Browse seasons",
                            items: data.seasons,
                        }}
                        client={client}
                        navigate={navigate}
                        onError={onError}
                    />
                )}
                {!!data?.episodes.length && (
                    <Cards
                        row={{
                            key: `episodes:${id}:${season ?? "all"}`,
                            title: "Episodes",
                            items: episodes,
                        }}
                        client={client}
                        navigate={navigate}
                        onError={onError}
                    />
                )}
                {containers.has(shown.Type) && (
                    <BrowseGrid
                        client={client}
                        parent={current}
                        navigate={navigate}
                        onError={onError}
                    />
                )}
                {shown.Type === "Person" && (
                    <BrowseGrid
                        client={client}
                        parent={current}
                        navigate={navigate}
                        onError={onError}
                        person
                    />
                )}
                {!!shown.People?.length && (
                    <Cards
                        row={{
                            key: `people:${id}`,
                            title: "Cast and crew",
                            items: shown.People.map((person) => ({
                                Id: person.Id,
                                Name: person.Name,
                                Type: "Person",
                                Overview: person.Role ?? person.Type,
                                Role: person.Role ?? person.Type,
                                ImageTags: person.PrimaryImageTag
                                    ? { Primary: person.PrimaryImageTag }
                                    : undefined,
                            })),
                        }}
                        client={client}
                        navigate={navigate}
                        onError={onError}
                    />
                )}
            </article>
        </div>
    );
}

const PAGE_SIZE = 60;
type BrowseState = {
    items: CatalogItem[];
    start: number;
    total?: number;
    done: boolean;
    sort: string;
    descending: boolean;
};
const browseCache = new Map<string, BrowseState>();
function rememberedBrowse(key: string): BrowseState | undefined {
    const cached = browseCache.get(key);
    if (cached) return cached;
    try {
        const value = JSON.parse(
            sessionStorage.getItem(`pictureshow.browse:${key}`) ?? "null",
        );
        if (
            value &&
            ["SortName", "DateCreated", "PremiereDate"].includes(value.sort) &&
            typeof value.descending === "boolean" &&
            Number.isSafeInteger(value.start) &&
            value.start >= 0
        )
            return {
                items: [],
                done: false,
                sort: value.sort,
                descending: value.descending,
                start: value.start,
            };
    } catch {}
    return;
}
function BrowseGrid({
    client,
    parent,
    navigate,
    onError,
    person = false,
}: Props & { parent: CatalogItem; person?: boolean }) {
    const key = `${accountKey(client)}:${parent.Id}`,
        remembered = rememberedBrowse(key),
        [sort, setSort] = useState(remembered?.sort ?? "SortName"),
        [descending, setDescending] = useState(
            remembered?.descending ?? person,
        ),
        [state, setState] = useState<BrowseState>(
            remembered ?? {
                items: [],
                start: 0,
                done: false,
                sort,
                descending,
            },
        ),
        [busy, setBusy] = useState(false),
        [error, setError] = useState(""),
        [retry, setRetry] = useState(0);
    const generation = useRef(0),
        busyRef = useRef(false),
        stateRef = useRef(state),
        sentinel = useRef<HTMLDivElement>(null),
        alive = useRef(true),
        pendingSort = useRef(false),
        firstLoad = useRef(true);
    stateRef.current = state;
    useEffect(() => {
        alive.current = true;
        return () => {
            alive.current = false;
            generation.current++;
        };
    }, [key]);
    const load = useCallback(
        async (reset = false, restoreCount = 0) => {
            if (busyRef.current && !reset) return;
            const gen = reset ? ++generation.current : generation.current,
                previous = reset
                    ? { items: [], start: 0, done: false, sort, descending }
                    : stateRef.current;
            if (previous.done && !reset) return;
            busyRef.current = true;
            setBusy(true);
            setError("");
            const path =
                "Items?" +
                query(
                    person
                        ? {
                              personIds: parent.Id,
                              recursive: true,
                              includeItemTypes: "Movie,Series",
                              sortBy: "ProductionYear,SortName",
                              sortOrder: "Descending",
                              startIndex: previous.start,
                              limit: PAGE_SIZE,
                              enableTotalRecordCount: true,
                              fields: SEARCH_FIELDS,
                          }
                        : {
                              parentId: parent.Id,
                              recursive: false,
                              sortBy: sort,
                              sortOrder: descending
                                  ? "Descending"
                                  : "Ascending",
                              startIndex: previous.start,
                              limit: PAGE_SIZE,
                              enableTotalRecordCount: true,
                              fields: CARD_FIELDS,
                          },
                );
            try {
                let page = await catalog<ItemPage>(client, path);
                let received = page.Items.length;
                const restored = [...page.Items];
                while (
                    reset &&
                    received < restoreCount &&
                    page.Items.length > 0 &&
                    (page.TotalRecordCount === undefined ||
                        received < page.TotalRecordCount)
                ) {
                    if (!alive.current || gen !== generation.current) return;
                    const nextPath = new URLSearchParams(path.slice(6));
                    nextPath.set("startIndex", String(received));
                    page = await catalog<ItemPage>(
                        client,
                        "Items?" + nextPath.toString(),
                    );
                    received += page.Items.length;
                    restored.push(...page.Items);
                }
                if (!alive.current || gen !== generation.current) return;
                const value = {
                    items: unique([...previous.items, ...restored]),
                    start: previous.start + received,
                    total: page.TotalRecordCount,
                    done:
                        page.TotalRecordCount !== undefined
                            ? previous.start + received >= page.TotalRecordCount
                            : page.Items.length < PAGE_SIZE,
                    sort,
                    descending,
                };
                browseCache.set(key, value);
                try {
                    sessionStorage.setItem(
                        `pictureshow.browse:${key}`,
                        JSON.stringify({
                            sort,
                            descending,
                            start: value.start,
                        }),
                    );
                } catch {}
                stateRef.current = value;
                setState(value);
            } catch (error) {
                if (alive.current && gen === generation.current) {
                    setError(errorText(error));
                    notifyExpired(client, error, onError);
                }
            } finally {
                if (alive.current && gen === generation.current) {
                    busyRef.current = false;
                    setBusy(false);
                }
            }
        },
        [key, sort, descending, client.account!.accessToken],
    );
    useEffect(() => {
        const count =
            firstLoad.current && !pendingSort.current
                ? (remembered?.start ?? 0)
                : 0;
        firstLoad.current = false;
        pendingSort.current = false;
        void load(true, count);
    }, [load]);
    useEffect(() => {
        if (retry) void load();
    }, [retry]);
    useEffect(() => {
        const el = sentinel.current;
        if (!el || !globalThis.IntersectionObserver) return;
        const observer = new IntersectionObserver(
            (entries) => {
                if (
                    entries.some((entry) => entry.isIntersecting) &&
                    !stateRef.current.done &&
                    !busyRef.current &&
                    !error
                )
                    void load();
            },
            { rootMargin: "500px" },
        );
        observer.observe(el);
        return () => observer.disconnect();
    }, [load, state.items.length, error]);
    function updateSort(next: string, direction = descending) {
        pendingSort.current = true;
        setSort(next);
        setDescending(direction);
        setState({
            items: [],
            start: 0,
            done: false,
            sort: next,
            descending: direction,
        });
    }
    return (
        <section className="catalog-browser">
            <div className="catalog-browse-heading">
                {!person && <h2>Browse library</h2>}
                {!person && (
                    <div className="catalog-sort">
                        <label htmlFor={`sort-${parent.Id}`}>Sort by</label>
                        <select
                            id={`sort-${parent.Id}`}
                            data-autofocus
                            data-focus="library-sort"
                            value={sort}
                            onChange={(event) => updateSort(event.target.value)}
                        >
                            <option value="SortName">Title</option>
                            <option value="DateCreated">Date added</option>
                            <option value="PremiereDate">Release date</option>
                        </select>
                        <button
                            aria-label={`Sort ${descending ? "descending" : "ascending"}. Change direction`}
                            aria-pressed={descending}
                            onClick={() => updateSort(sort, !descending)}
                        >
                            {descending ? "Descending ↓" : "Ascending ↑"}
                        </button>
                    </div>
                )}
            </div>
            {state.items.length > 0 && (
                <Cards
                    row={{
                        key: `grid:${parent.Id}`,
                        title: person
                            ? "Movies and shows"
                            : `${state.total ?? state.items.length}${state.total === undefined && !state.done ? "+" : ""} items`,
                        items: state.items,
                    }}
                    grid
                    client={client}
                    navigate={navigate}
                    onError={onError}
                />
            )}
            {busy && (
                <p role="status">
                    Loading {state.items.length ? "more titles" : "titles"}…
                </p>
            )}
            {!busy && !error && state.done && !state.items.length && (
                <div className="catalog-empty">
                    <p>
                        {person
                            ? "No linked movies or shows are available."
                            : parent.Type === "CollectionFolder" ||
                                parent.Type === "UserView"
                              ? "This library is empty."
                              : `This ${libraryLabel(parent).toLowerCase()} is empty.`}
                    </p>
                    <button onClick={() => void load(true)}>Refresh</button>
                </div>
            )}
            {error && (
                <RetryNotice
                    error={error}
                    retry={() => setRetry((value) => value + 1)}
                    busy={busy}
                />
            )}
            <div ref={sentinel}>
                {!state.done && !error && (
                    <button
                        className="catalog-load-more"
                        disabled={busy}
                        onClick={() => void load()}
                    >
                        Load more titles
                    </button>
                )}
            </div>
        </section>
    );
}

const kinds = [
    { kind: "MOVIES", type: "Movie", title: "Movies" },
    { kind: "SHOWS", type: "Series", title: "Shows" },
    { kind: "EPISODES", type: "Episode", title: "Episodes" },
    { kind: "COLLECTIONS", type: "BoxSet", title: "Collections" },
    { kind: "PEOPLE", type: "Person", title: "People" },
] as const;
type SearchState = { query: string; rows: Row[]; errors: string[] };
const searchCache = new Map<string, SearchState>();
function normalizeSearch(value: string) {
    return value.trim().slice(0, 200).trimEnd();
}
export function Search({ client, navigate, onError }: Props) {
    const key = accountKey(client),
        saved = searchCache.get(key),
        [text, setText] = useState(() => {
            if (saved) return saved.query;
            try {
                return (
                    sessionStorage.getItem(`pictureshow.search-draft:${key}`) ??
                    ""
                );
            } catch {
                return "";
            }
        }),
        [data, setData] = useState<SearchState | undefined>(saved),
        [busy, setBusy] = useState(false),
        [attempt, setAttempt] = useState(0),
        [recents, setRecents] = useState<string[]>([]),
        [storageError, setStorageError] = useState("");
    const generation = useRef(0),
        field = useRef<HTMLInputElement>(null),
        queryText = normalizeSearch(text),
        recentKey = `pictureshow.search:${key}`;
    useEffect(() => {
        try {
            sessionStorage.setItem(`pictureshow.search-draft:${key}`, text);
        } catch {
            setStorageError(
                "The search query could not be restored after reload. Search still works.",
            );
        }
    }, [key, text]);
    useEffect(() => {
        try {
            const value = JSON.parse(localStorage.getItem(recentKey) ?? "[]");
            setRecents(
                Array.isArray(value)
                    ? value.filter((x) => typeof x === "string").slice(0, 12)
                    : [],
            );
        } catch {
            setStorageError(
                "Recent searches could not be loaded. Search still works.",
            );
        }
    }, [recentKey]);
    function saveRecent(value: string) {
        const term = normalizeSearch(value);
        if (term.length < 2) return;
        const next = [term, ...recents.filter((item) => item !== term)].slice(
            0,
            12,
        );
        setRecents(next);
        try {
            localStorage.setItem(recentKey, JSON.stringify(next));
            setStorageError("");
        } catch {
            setStorageError(
                "Recent searches could not be saved. Search still works.",
            );
        }
    }
    useEffect(() => {
        const gen = ++generation.current;
        let live = true;
        if (queryText.length < 2) {
            setData(undefined);
            setBusy(false);
            return;
        }
        setBusy(true);
        const timer = window.setTimeout(async () => {
            const results = await Promise.allSettled(
                kinds.map(async (kind) => {
                    const path =
                        (kind.kind === "PEOPLE" ? "Persons?" : "Items?") +
                        query({
                            searchTerm: queryText,
                            limit: 24,
                            recursive:
                                kind.kind === "PEOPLE" ? undefined : true,
                            includeItemTypes:
                                kind.kind === "PEOPLE" ? undefined : kind.type,
                            enableTotalRecordCount:
                                kind.kind === "PEOPLE" ? undefined : true,
                            fields:
                                kind.kind === "PEOPLE"
                                    ? undefined
                                    : SEARCH_FIELDS,
                        });
                    return {
                        key: `search:${kind.kind}`,
                        title: kind.title,
                        items: (await catalog<ItemPage>(client, path)).Items,
                    };
                }),
            );
            if (!live || gen !== generation.current) return;
            const errors: string[] = [],
                rows: Row[] = [];
            results.forEach((result, index) => {
                if (result.status === "fulfilled") {
                    if (result.value.items.length) rows.push(result.value);
                } else {
                    errors.push(
                        `${kinds[index].title}: ${errorText(result.reason)}`,
                    );
                    notifyExpired(client, result.reason, onError);
                }
            });
            const value = { query: queryText, rows, errors };
            searchCache.set(key, value);
            setData(value);
            setBusy(false);
        }, 300);
        return () => {
            live = false;
            clearTimeout(timer);
        };
    }, [queryText, attempt, key, client.account!.accessToken]);
    const ref = useScrollMemory(
        `search:${key}`,
        !!data || queryText.length < 2,
    );
    const firstDown = useRef(false);
    return (
        <div className="catalog-page catalog-search" ref={ref}>
            <h1>Search</h1>
            <form
                role="search"
                onSubmit={(event) => {
                    event.preventDefault();
                    saveRecent(text);
                    field.current?.blur();
                    document
                        .querySelector<HTMLElement>(
                            ".catalog-search .catalog-card",
                        )
                        ?.focus();
                }}
            >
                <label htmlFor="catalog-search-field">
                    Search movies, shows, episodes, collections and people
                </label>
                <div className="catalog-search-entry">
                    <input
                        ref={field}
                        id="catalog-search-field"
                        data-focus="search-field"
                        data-autofocus
                        type="search"
                        enterKeyHint="search"
                        autoComplete="off"
                        maxLength={200}
                        value={text}
                        onChange={(event) => setText(event.target.value)}
                        onKeyDown={(event) => {
                            if (event.key === "ArrowDown") {
                                event.preventDefault();
                                event.stopPropagation();
                                if (!event.repeat) firstDown.current = true;
                            }
                        }}
                        onKeyUp={(event) => {
                            if (event.key === "ArrowDown") {
                                event.preventDefault();
                                event.stopPropagation();
                                if (firstDown.current) {
                                    firstDown.current = false;
                                    document
                                        .querySelector<HTMLElement>(
                                            ".catalog-search .catalog-card, .catalog-search .catalog-recent button",
                                        )
                                        ?.focus();
                                }
                            }
                        }}
                    />
                    <button
                        type="button"
                        onClick={() => {
                            setText("");
                            field.current?.focus();
                        }}
                    >
                        Clear
                    </button>
                    <button type="submit">Search</button>
                </div>
            </form>
            {storageError && <p role="alert">{storageError}</p>}
            {!!recents.length && (
                <section
                    className="catalog-recent"
                    aria-label="Recent searches"
                >
                    <h2>Recent searches</h2>
                    <div className="catalog-season-chips">
                        {recents.map((term) => (
                            <button
                                key={term}
                                onClick={() => {
                                    setText(term);
                                    field.current?.focus();
                                }}
                            >
                                {term}
                            </button>
                        ))}
                        <button
                            onClick={() => {
                                try {
                                    localStorage.removeItem(recentKey);
                                    setRecents([]);
                                    setStorageError("");
                                } catch {
                                    setStorageError(
                                        "Recent searches could not be cleared. Try again.",
                                    );
                                }
                            }}
                        >
                            Clear recent searches
                        </button>
                    </div>
                </section>
            )}
            {queryText.length < 2 ? (
                <p className="catalog-empty">
                    Enter at least two characters to search your library.
                </p>
            ) : (
                <>
                    {busy && <p role="status">Searching…</p>}
                    {data?.query === queryText &&
                        data.rows.map((row) => (
                            <Cards
                                key={row.key}
                                row={row}
                                client={client}
                                navigate={navigate}
                                onError={onError}
                                onOpen={() => saveRecent(text)}
                            />
                        ))}
                    {!busy &&
                        data?.query === queryText &&
                        !data.rows.length &&
                        !data.errors.length && (
                            <p role="status">
                                No results for “{queryText}”. Try another title
                                or person.
                            </p>
                        )}
                    {data?.query === queryText && !!data.errors.length && (
                        <RetryNotice
                            error={`${data.errors.length === kinds.length ? "Search could not finish." : "Some search groups could not load."} ${data.errors.join(" ")}`}
                            retry={() => setAttempt((value) => value + 1)}
                            busy={busy}
                        />
                    )}
                </>
            )}
        </div>
    );
}
