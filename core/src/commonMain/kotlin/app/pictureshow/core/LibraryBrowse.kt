/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** BROWSE-04: how a library lists its titles. */
data class LibraryBrowseScope(val recursive: Boolean, val includeItemTypes: String? = null)

/**
 * Movies, Shows and Collections libraries list their titles by item type through every folder
 * level, so folders never sit among the titles. Other libraries keep the folder view.
 */
fun libraryBrowseScope(collectionType: String?): LibraryBrowseScope = when (collectionType) {
    "movies" -> LibraryBrowseScope(true, "Movie")
    "tvshows" -> LibraryBrowseScope(true, "Series")
    "boxsets" -> LibraryBrowseScope(true, "BoxSet")
    else -> LibraryBrowseScope(false)
}

/** BROWSE-05: the letter rail's 27 entries, "#" for anything not A–Z. */
val JUMP_LETTERS: List<String> = listOf("#") + ('A'..'Z').map(Char::toString)

private val LETTER_FOLD: Map<Char, Char> = mapOf(
    'A' to "ÀÁÂÃÄÅĀĂĄ",
    'C' to "ÇĆĈĊČ",
    'D' to "ĎĐ",
    'E' to "ÈÉÊËĒĔĖĘĚ",
    'G' to "ĜĞĠĢ",
    'H' to "ĤĦ",
    'I' to "ÌÍÎÏĨĪĬĮİ",
    'J' to "Ĵ",
    'K' to "Ķ",
    'L' to "ĹĻĽĿŁ",
    'N' to "ÑŃŅŇ",
    'O' to "ÒÓÔÕÖØŌŎŐ",
    'R' to "ŔŖŘ",
    'S' to "ŚŜŞŠ",
    'T' to "ŢŤŦ",
    'U' to "ÙÚÛÜŨŪŬŮŰŲ",
    'W' to "Ŵ",
    'Y' to "ÝŸŶ",
    'Z' to "ŹŻŽ"
).flatMap { (base, marked) -> marked.map { it to base } }.toMap()

/** The rail letter a title files under, from its server sort name. */
fun jumpLetter(sortName: String): String {
    val first = sortName.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
    val base = LETTER_FOLD[first] ?: first
    return if (base in 'A'..'Z') base.toString() else "#"
}

data class LetterJump(val index: Int, val available: List<String>)

/** The first title filed under [letter] in the order given (either direction), or -1. */
fun letterJump(sortNames: List<String>, letter: String): LetterJump {
    val letters = sortNames.map(::jumpLetter)
    return LetterJump(letters.indexOf(letter), JUMP_LETTERS.filter { it in letters })
}

/** BROWSE-06: most titles a Play or Shuffle queue holds. */
const val QUEUE_LIMIT = 300

data class CollectionPlayScope(val includeItemTypes: String, val recursive: Boolean, val filtered: Boolean)

/** BROWSE-06: a collection queues its movies, videos and episodes; a Movies library its movies under the filters. */
fun collectionPlayScope(type: String, collectionType: String?): CollectionPlayScope? = when {
    type == "BoxSet" -> CollectionPlayScope("Movie,Episode,Video", recursive = true, filtered = false)
    type in setOf("CollectionFolder", "UserView") && collectionType == "movies" ->
        CollectionPlayScope("Movie", recursive = true, filtered = true)
    else -> null
}
