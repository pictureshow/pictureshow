/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** CAT-14 groups and bounds, in [searchGroupOrder] for [query], shared by native search adapters. */
fun searchRows(results: Map<SearchKind, ClientResult<ItemPage>>, query: String = ""): List<HomeRow> {
    val groups = SearchKind.entries.mapNotNull { kind ->
        val items = (results[kind] as? ClientResult.Success)?.value?.items.orEmpty().take(24)
        items.takeIf { it.isNotEmpty() }?.let { kind to it }
    }
    val order = searchGroupOrder(query, groups.map { (kind, items) -> SearchGroup(kind.itemType, items.map(MediaItem::name)) })
    return order.map { type ->
        val (kind, items) = groups.first { it.first.itemType == type }
        HomeRow(kind.name, kind.title, items, wide = kind == SearchKind.EPISODES)
    }
}

/** One kind's results as [searchGroupOrder] reads them: its Jellyfin item type and its titles. */
data class SearchGroup(val type: String, val names: List<String>)

/**
 * CAT-14b: the kinds of search results, in the order their rows show. A kind holding a title that is the query (tier 0)
 * comes first, then one holding a title that begins with it (tier 1), then the rest; within a tier the fixed CAT-14 order
 * holds (Movies, Shows, Episodes, Collections, People, then music). Titles and query compare in lower case with
 * punctuation as spaces, and a leading "the", "a" or "an" may be dropped from either, so "book of life" is The Book of
 * Life itself. Only what the groups hold decides, never which answer arrived first. Types outside the fixed order go last.
 */
fun searchGroupOrder(query: String, groups: List<SearchGroup>): List<String> {
    val forms = searchMatchForms(query)
    fun tier(group: SearchGroup): Int = if (forms.isEmpty()) {
        2
    } else {
        group.names.minOfOrNull { name ->
            val titles = searchMatchForms(name)
            when {
                titles.any { it in forms } -> 0
                titles.any { title -> forms.any { title.startsWith(it) } } -> 1
                else -> 2
            }
        } ?: 2
    }
    fun fixed(type: String): Int = SearchKind.entries.indexOfFirst { it.itemType == type }.let { if (it < 0) Int.MAX_VALUE else it }
    return groups.distinctBy { it.type }.sortedWith(compareBy<SearchGroup>({ tier(it) }, { fixed(it.type) })).map { it.type }
}

private val SEARCH_ARTICLES = listOf("the ", "a ", "an ")

/** A text as search compares it: lower case, punctuation as spaces, with and without a leading article. */
private fun searchMatchForms(text: String): Set<String> {
    val plain = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(' ').filter(String::isNotEmpty).joinToString(" ")
    if (plain.isEmpty()) return emptySet()
    val stripped = SEARCH_ARTICLES.firstOrNull { plain.startsWith(it) && plain.length > it.length }?.let { plain.removePrefix(it) }
    return setOfNotNull(plain, stripped)
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
