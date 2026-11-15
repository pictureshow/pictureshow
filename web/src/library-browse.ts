/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// Library browse rules shared with Android core (spec/features/home-browse.md,
// BROWSE-01 to BROWSE-06; cases in spec/vectors/home-browse.json).

export type Resolution = "ANY" | "SD" | "HD" | "FULL_HD" | "UHD";
export const RESOLUTIONS: readonly {
    value: Resolution;
    summary: string;
    range?: string;
    minWidth?: number;
    maxWidth?: number;
}[] = [
    { value: "ANY", summary: "Any" },
    { value: "SD", summary: "SD", range: "up to 719 px wide", maxWidth: 719 },
    {
        value: "HD",
        summary: "HD",
        range: "720–1919 px wide",
        minWidth: 720,
        maxWidth: 1919,
    },
    {
        value: "FULL_HD",
        summary: "Full HD",
        range: "1920–3839 px wide",
        minWidth: 1920,
        maxWidth: 3839,
    },
    {
        value: "UHD",
        summary: "4K and above",
        range: "3840+ px wide",
        minWidth: 3840,
    },
];

/** Different facets intersect; each holds one value; blank means no constraint. */
export type LibraryFilters = {
    genre?: string | null;
    year?: number | null;
    officialRating?: string | null;
    unwatched?: boolean;
    favorite?: boolean;
    resolution?: Resolution;
    personId?: string | null;
    studio?: string | null;
    /** Display only: never a query parameter or a constraint of its own. */
    personName?: string | null;
};
export type ConstraintKey =
    | "genre"
    | "year"
    | "rating"
    | "unwatched"
    | "favorite"
    | "resolution"
    | "person"
    | "studio";

const nonBlank = (value?: string | null) =>
    value != null && value.trim() !== "" ? value : undefined;
const resolution = (value?: Resolution) =>
    RESOLUTIONS.find((r) => r.value === (value ?? "ANY")) ?? RESOLUTIONS[0];

/** The exact Jellyfin `/Items` parameters; omitted keys carry no constraint. */
export function filterParameters(f: LibraryFilters): Record<string, string> {
    const r = resolution(f.resolution),
        out: Record<string, string | undefined> = {
            genres: nonBlank(f.genre),
            years: f.year != null ? String(f.year) : undefined,
            officialRatings: nonBlank(f.officialRating),
            isPlayed: f.unwatched ? "false" : undefined,
            isFavorite: f.favorite ? "true" : undefined,
            minWidth: r.minWidth != null ? String(r.minWidth) : undefined,
            maxWidth: r.maxWidth != null ? String(r.maxWidth) : undefined,
            personIds: nonBlank(f.personId),
            studios: nonBlank(f.studio),
        };
    return Object.fromEntries(
        Object.entries(out).filter(([, v]) => v !== undefined),
    ) as Record<string, string>;
}

/** Each independent constraint counts once, a width range included. */
export function constraintKeys(f: LibraryFilters): ConstraintKey[] {
    const keys: ConstraintKey[] = [];
    if (nonBlank(f.genre)) keys.push("genre");
    if (f.year != null) keys.push("year");
    if (nonBlank(f.officialRating)) keys.push("rating");
    if (f.unwatched) keys.push("unwatched");
    if (f.favorite) keys.push("favorite");
    if ((f.resolution ?? "ANY") !== "ANY") keys.push("resolution");
    if (nonBlank(f.personId)) keys.push("person");
    if (nonBlank(f.studio)) keys.push("studio");
    return keys;
}

/** Keeps only set values, so a cleared facet leaves nothing behind. */
export function normalizeFilters(f: LibraryFilters): LibraryFilters {
    const out: LibraryFilters = {};
    if (f.genre != null) out.genre = f.genre;
    if (f.year != null) out.year = f.year;
    if (f.officialRating != null) out.officialRating = f.officialRating;
    if (f.unwatched) out.unwatched = true;
    if (f.favorite) out.favorite = true;
    if ((f.resolution ?? "ANY") !== "ANY") out.resolution = f.resolution;
    if (f.personId != null) out.personId = f.personId;
    if (f.studio != null) out.studio = f.studio;
    if (f.personName != null) out.personName = f.personName;
    return out;
}

/** Removing one chip clears that facet only; a person takes its name along. */
export function withoutConstraint(
    f: LibraryFilters,
    key: string,
): LibraryFilters {
    const next = { ...f };
    switch (key) {
        case "genre":
            next.genre = null;
            break;
        case "year":
            next.year = null;
            break;
        case "rating":
            next.officialRating = null;
            break;
        case "unwatched":
            next.unwatched = false;
            break;
        case "favorite":
            next.favorite = false;
            break;
        case "resolution":
            next.resolution = "ANY";
            break;
        case "person":
            next.personId = null;
            next.personName = null;
            break;
        case "studio":
            next.studio = null;
            break;
    }
    return normalizeFilters(next);
}

export const resolutionSummary = (value?: Resolution) =>
    resolution(value).summary;

export function constraintLabel(f: LibraryFilters, key: string): string {
    switch (key) {
        case "genre":
            return f.genre ?? "Genre";
        case "year":
            return f.year != null ? String(f.year) : "Year";
        case "rating":
            return f.officialRating != null
                ? `Rated ${f.officialRating}`
                : "Rating";
        case "unwatched":
            return "Unwatched";
        case "favorite":
            return "Favorites";
        case "resolution":
            return resolutionSummary(f.resolution);
        case "person":
            return nonBlank(f.personId) && nonBlank(f.personName)
                ? f.personName!
                : "Selected person";
        case "studio":
            return f.studio ?? "Studio";
    }
    return "Filter";
}

export function filteredEmptyLabel(collectionType?: string | null) {
    const noun =
        collectionType === "movies"
            ? "movies"
            : collectionType === "tvshows"
              ? "shows"
              : collectionType === "music"
                ? "music"
                : "titles";
    return `No ${noun} match these filters`;
}

export const titleCountLabel = (count: number, exact = true) =>
    count === 1 && exact ? "1 title" : `${count}${exact ? "" : "+"} titles`;
export const sortDirectionLabel = (descending: boolean) =>
    descending ? "Descending" : "Ascending";

/**
 * BROWSE-04: Movies, Shows and Collections libraries list their titles by item
 * type through every folder level, so folders never sit among the titles.
 * Other libraries (home videos, mixed, folders) keep the folder view.
 */
export function libraryBrowseScope(collectionType?: string | null): {
    recursive: boolean;
    includeItemTypes?: string;
} {
    const type =
        collectionType === "movies"
            ? "Movie"
            : collectionType === "tvshows"
              ? "Series"
              : collectionType === "boxsets"
                ? "BoxSet"
                : undefined;
    return type
        ? { recursive: true, includeItemTypes: type }
        : { recursive: false };
}

/** BROWSE-05: the letter rail's 27 entries, "#" for anything not A–Z. */
export const JUMP_LETTERS = ["#", ..."ABCDEFGHIJKLMNOPQRSTUVWXYZ"] as const;
// Latin letters with marks file under their base letter ("Éclair" under E).
const FOLD: Record<string, string> = {};
for (const [base, marked] of Object.entries({
    A: "ÀÁÂÃÄÅĀĂĄ",
    C: "ÇĆĈĊČ",
    D: "ĎĐ",
    E: "ÈÉÊËĒĔĖĘĚ",
    G: "ĜĞĠĢ",
    H: "ĤĦ",
    I: "ÌÍÎÏĨĪĬĮİ",
    J: "Ĵ",
    K: "Ķ",
    L: "ĹĻĽĿŁ",
    N: "ÑŃŅŇ",
    O: "ÒÓÔÕÖØŌŎŐ",
    R: "ŔŖŘ",
    S: "ŚŜŞŠ",
    T: "ŢŤŦ",
    U: "ÙÚÛÜŨŪŬŮŰŲ",
    W: "Ŵ",
    Y: "ÝŸŶ",
    Z: "ŹŻŽ",
}))
    for (const c of marked) FOLD[c] = base;

/** The rail letter a title files under, from its server sort name. */
export function jumpLetter(sortName: string): string {
    const first = [...sortName.trimStart()][0]?.toUpperCase() ?? "";
    const base = FOLD[first] ?? first;
    return base >= "A" && base <= "Z" && base.length === 1 ? base : "#";
}

/**
 * Where a letter lands in the list as the server sorted it: the first title
 * filed under that letter, or -1 when none is (its rail entry is disabled).
 * Works for either direction because it reads the order it is given.
 */
export function letterJump(
    sortNames: readonly string[],
    letter: string,
): { index: number; available: string[] } {
    const letters = sortNames.map(jumpLetter);
    return {
        index: letters.indexOf(letter),
        available: JUMP_LETTERS.filter((l) => letters.includes(l)),
    };
}

/** BROWSE-06: most titles a Play or Shuffle queue holds. */
export const QUEUE_LIMIT = 300;

/**
 * BROWSE-06: which pages offer Play and Shuffle, and what they queue. A
 * collection (box set) queues its movies, videos and the episodes of any show
 * in it; a Movies library queues its movies under the current filters. Other
 * libraries offer neither yet.
 */
export function collectionPlayScope(
    type: string,
    collectionType?: string | null,
): { includeItemTypes: string; recursive: true; filtered: boolean } | null {
    if (type === "BoxSet")
        return {
            includeItemTypes: "Movie,Episode,Video",
            recursive: true,
            filtered: false,
        };
    if (
        ["CollectionFolder", "UserView"].includes(type) &&
        collectionType === "movies"
    )
        return { includeItemTypes: "Movie", recursive: true, filtered: true };
    return null;
}
