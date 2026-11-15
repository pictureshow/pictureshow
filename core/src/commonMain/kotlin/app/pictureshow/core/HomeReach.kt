/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** HOME-09: what Home or Libraries says about the server, above whatever the page still shows. */
enum class HomeNoticeKind { None, Error, DownloadsFirst, ShowingEarlier }

data class HomeNotice(
    val kind: HomeNoticeKind,
    val text: String? = null,
    /** Watch downloads comes first when it's offered, Retry second (offline.md, "Saved films come first"). */
    val watchDownloads: Boolean = false,
    val retry: Boolean = false
)

const val HOME_UNREACHABLE_DOWNLOADS_TEXT = "Could not reach the server. Your downloads still play."

/**
 * A server out of reach (no answer at all, not a refusal) with something still on the page keeps that page and says
 * so in one line, naming the server, with the way to downloads when this viewer has ready ones. With nothing left on
 * the page, ready downloads lead. Anything else is the read's own error.
 */
fun homeNotice(unreachable: Boolean, error: String?, hasContent: Boolean, hasDownloads: Boolean, serverName: String?): HomeNotice =
    when {
        unreachable && hasContent -> HomeNotice(
            HomeNoticeKind.ShowingEarlier,
            "Can't reach ${serverName?.trim()?.takeIf(String::isNotEmpty) ?: "the server"}. Showing what was loaded earlier.",
            watchDownloads = hasDownloads,
            retry = true
        )
        unreachable && hasDownloads ->
            HomeNotice(HomeNoticeKind.DownloadsFirst, HOME_UNREACHABLE_DOWNLOADS_TEXT, watchDownloads = true, retry = true)
        error != null -> HomeNotice(HomeNoticeKind.Error, error, retry = true)
        else -> HomeNotice(HomeNoticeKind.None)
    }

/**
 * HOME-10: a Home row whose heading leads somewhere. The Libraries row opens the Libraries root and a library's Latest
 * row opens that library; every other row's heading is a plain heading, so nothing looks tappable that isn't.
 * Returns `libraries`, `library:<id>` or null.
 */
fun homeRowDestination(rowKey: String): String? = when {
    rowKey == "libraries" -> "libraries"
    rowKey.startsWith("latest:") && rowKey.length > "latest:".length -> "library:" + rowKey.removePrefix("latest:")
    else -> null
}
