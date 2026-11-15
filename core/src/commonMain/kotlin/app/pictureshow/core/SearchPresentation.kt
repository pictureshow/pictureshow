/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** Fixed CAT-14 order and bounds, shared by native search adapters. */
fun searchRows(results: Map<SearchKind, ClientResult<ItemPage>>): List<HomeRow> = SearchKind.entries.mapNotNull { kind ->
    val items = (results[kind] as? ClientResult.Success)?.value?.items.orEmpty().take(24)
    items.takeIf { it.isNotEmpty() }?.let { HomeRow(kind.name, kind.title, it, wide = kind == SearchKind.EPISODES) }
}

/** Distinct copies of a title/year need their library, even if their artwork is identical. */
fun searchDuplicateIds(items: List<MediaItem>): Set<String> = items
    .groupBy { Triple(it.type, it.name.trim().lowercase(), it.productionYear) }
    .values.filter { group -> group.map { it.id }.distinct().size > 1 }
    .flatten().map { it.id }.toSet()

/** Resolve only a real viewer-visible library, never a filesystem path or a guessed parent name. */
fun searchLibraryName(ancestors: List<MediaItem>, libraries: List<MediaItem>): String? =
    ancestors.firstNotNullOfOrNull { ancestor -> libraries.firstOrNull { it.id == ancestor.id }?.name }
        ?: ancestors.firstOrNull { it.type == "CollectionFolder" }?.name
