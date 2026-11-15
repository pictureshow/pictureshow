/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
// BROWSE-01/02: the library Filters dialog. Choices come from the viewer's own
// library (ACCESS-01: the parent is read first, so a denied library offers none).
import React, { useEffect, useId, useRef, useState } from "react";
import { encode, type Jellyfin } from "@pictureshow/core";
import {
    RESOLUTIONS,
    normalizeFilters,
    type LibraryFilters,
    type Resolution,
} from "./library-browse";

type Facets = {
    Genres?: string[];
    Years?: number[];
    OfficialRatings?: string[];
};
type Person = { Id: string; Name: string };

const message = (error: unknown) =>
    error instanceof Error
        ? error.message
        : "The server could not complete the request. Try again.";

export function FilterDialog({
    client,
    parentId,
    value,
    onApply,
    onClose,
}: {
    client: Jellyfin;
    parentId: string;
    value: LibraryFilters;
    onApply: (filters: LibraryFilters) => void;
    onClose: () => void;
}) {
    const ref = useRef<HTMLDialogElement>(null),
        id = useId(),
        [draft, setDraft] = useState<LibraryFilters>(value),
        [facets, setFacets] = useState<Facets>(),
        [error, setError] = useState(""),
        [attempt, setAttempt] = useState(0),
        [term, setTerm] = useState(""),
        [people, setPeople] = useState<Person[]>(),
        [peopleError, setPeopleError] = useState("");
    useEffect(() => {
        const dialog = ref.current;
        if (dialog && !dialog.open) dialog.showModal();
    }, []);
    useEffect(() => {
        let live = true;
        setFacets(undefined);
        setError("");
        (async () => {
            // Metadata routes can list a denied parent; the item read authorizes it.
            await client.catalog(`Items/${encode(parentId)}`);
            return client.catalog<Facets>(
                `Items/Filters?parentId=${encode(parentId)}`,
            );
        })()
            .then((value) => live && setFacets(value))
            .catch((e) => live && setError(message(e)));
        return () => {
            live = false;
        };
    }, [parentId, attempt]);
    useEffect(() => {
        let live = true;
        setPeopleError("");
        const timer = setTimeout(
            () => {
                (async () => {
                    await client.catalog(`Items/${encode(parentId)}`);
                    const query = new URLSearchParams({
                        parentId,
                        limit: "60",
                        ...(term.trim() ? { searchTerm: term.trim() } : {}),
                    });
                    return client.catalog<{ Items: Person[] }>(
                        `Persons?${query}`,
                    );
                })()
                    .then((page) => live && setPeople(page.Items))
                    .catch((e) => live && setPeopleError(message(e)));
            },
            term ? 300 : 0,
        );
        return () => {
            live = false;
            clearTimeout(timer);
        };
    }, [parentId, term]);
    const set = (patch: LibraryFilters) =>
        setDraft((current) => ({ ...current, ...patch }));
    const years = [...new Set(facets?.Years ?? [])].sort((a, b) => b - a),
        decades = [...new Set(years.map((y) => Math.floor(y / 10) * 10))];
    const disabled = !facets;
    // A chosen person outside the current results stays listed and checked.
    const personChoices: Person[] = [
        ...(draft.personId && !people?.some((p) => p.Id === draft.personId)
            ? [
                  {
                      Id: draft.personId,
                      Name: draft.personName ?? "Selected person",
                  },
              ]
            : []),
        ...(people ?? []),
    ];
    return (
        <dialog
            ref={ref}
            className="library-filters"
            aria-labelledby={`${id}-title`}
            onClose={onClose}
            onClick={(e) => {
                if (e.target === ref.current) ref.current?.close();
            }}
        >
            <form
                method="dialog"
                onSubmit={(e) => {
                    e.preventDefault();
                    onApply(normalizeFilters(draft));
                    ref.current?.close();
                }}
            >
                <h2 id={`${id}-title`}>Filters</h2>
                <div className="library-filter-switches">
                    <label>
                        <input
                            type="checkbox"
                            autoFocus
                            checked={!!draft.unwatched}
                            onChange={(e) =>
                                set({ unwatched: e.target.checked })
                            }
                        />{" "}
                        Unwatched
                    </label>
                    <label>
                        <input
                            type="checkbox"
                            checked={!!draft.favorite}
                            onChange={(e) =>
                                set({ favorite: e.target.checked })
                            }
                        />{" "}
                        Favorites
                    </label>
                </div>
                <div className="library-filter-fields">
                    <label htmlFor={`${id}-genre`}>Genre</label>
                    <select
                        id={`${id}-genre`}
                        disabled={disabled}
                        value={draft.genre ?? ""}
                        onChange={(e) => set({ genre: e.target.value || null })}
                    >
                        <option value="">Any</option>
                        {[...new Set(facets?.Genres ?? [])].map((g) => (
                            <option key={g} value={g}>
                                {g}
                            </option>
                        ))}
                        {draft.genre &&
                            facets &&
                            !facets.Genres?.includes(draft.genre) && (
                                <option value={draft.genre}>
                                    {draft.genre}
                                </option>
                            )}
                    </select>
                    <label htmlFor={`${id}-year`}>Year</label>
                    <select
                        id={`${id}-year`}
                        disabled={disabled}
                        value={draft.year != null ? String(draft.year) : ""}
                        onChange={(e) =>
                            set({
                                year: e.target.value
                                    ? Number(e.target.value)
                                    : null,
                            })
                        }
                    >
                        <option value="">Any</option>
                        {decades.map((decade) => (
                            <optgroup key={decade} label={`${decade}s`}>
                                {years
                                    .filter(
                                        (y) =>
                                            Math.floor(y / 10) * 10 === decade,
                                    )
                                    .map((y) => (
                                        <option key={y} value={y}>
                                            {y}
                                        </option>
                                    ))}
                            </optgroup>
                        ))}
                        {draft.year != null &&
                            facets &&
                            !years.includes(draft.year) && (
                                <option value={draft.year}>{draft.year}</option>
                            )}
                    </select>
                    <label htmlFor={`${id}-rating`}>Rating</label>
                    <select
                        id={`${id}-rating`}
                        disabled={disabled}
                        value={draft.officialRating ?? ""}
                        onChange={(e) =>
                            set({ officialRating: e.target.value || null })
                        }
                    >
                        <option value="">Any</option>
                        {[...new Set(facets?.OfficialRatings ?? [])].map(
                            (r) => (
                                <option key={r} value={r}>
                                    {r}
                                </option>
                            ),
                        )}
                        {draft.officialRating &&
                            facets &&
                            !facets.OfficialRatings?.includes(
                                draft.officialRating,
                            ) && (
                                <option value={draft.officialRating}>
                                    {draft.officialRating}
                                </option>
                            )}
                    </select>
                    <label htmlFor={`${id}-resolution`}>Resolution</label>
                    <select
                        id={`${id}-resolution`}
                        value={draft.resolution ?? "ANY"}
                        onChange={(e) =>
                            set({ resolution: e.target.value as Resolution })
                        }
                    >
                        {RESOLUTIONS.map((r) => (
                            <option key={r.value} value={r.value}>
                                {r.range ? `${r.summary} (${r.range})` : "Any"}
                            </option>
                        ))}
                    </select>
                </div>
                {!facets && !error && <p role="status">Loading choices…</p>}
                {error && (
                    <div className="library-filter-error" role="alert">
                        <p>Choices couldn't load. {error}</p>
                        <button
                            type="button"
                            onClick={() => setAttempt((a) => a + 1)}
                        >
                            Retry
                        </button>
                    </div>
                )}
                <fieldset className="library-filter-people">
                    <legend>Person</legend>
                    <label htmlFor={`${id}-person-search`}>Find a person</label>
                    <input
                        id={`${id}-person-search`}
                        type="search"
                        value={term}
                        onChange={(e) => setTerm(e.target.value)}
                        onKeyDown={(e) => {
                            // Enter searches here; it never applies the form.
                            if (e.key === "Enter") e.preventDefault();
                        }}
                    />
                    <div
                        className="library-filter-person-list"
                        role="radiogroup"
                        aria-label="Person"
                    >
                        <label>
                            <input
                                type="radio"
                                name={`${id}-person`}
                                checked={!draft.personId}
                                onChange={() =>
                                    set({ personId: null, personName: null })
                                }
                            />{" "}
                            Any
                        </label>
                        {personChoices.map((p) => (
                            <label key={p.Id}>
                                <input
                                    type="radio"
                                    name={`${id}-person`}
                                    checked={draft.personId === p.Id}
                                    onChange={() =>
                                        set({
                                            personId: p.Id,
                                            personName: p.Name,
                                        })
                                    }
                                />{" "}
                                {p.Name}
                            </label>
                        ))}
                    </div>
                    {people && !people.length && term.trim() && (
                        <p className="catalog-muted">
                            No people match “{term.trim()}”.
                        </p>
                    )}
                    {peopleError && (
                        <p role="alert">People couldn't load. {peopleError}</p>
                    )}
                </fieldset>
                <div className="library-filter-actions action-row">
                    <button type="submit" className="primary">
                        Apply
                    </button>
                    <button type="button" onClick={() => setDraft({})}>
                        Clear all
                    </button>
                    <button type="button" onClick={() => ref.current?.close()}>
                        Cancel
                    </button>
                </div>
            </form>
        </dialog>
    );
}
