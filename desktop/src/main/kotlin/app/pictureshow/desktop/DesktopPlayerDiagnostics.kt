/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

/** Failure location without exception messages: native messages may contain
 * authenticated stream URLs, paths or request headers. No media data is logged. */
internal fun recordDesktopPlayerFailure(stage: String, error: Throwable) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    while (cause != null && seen.add(cause) && seen.size <= 8) {
        val current = cause
        System.err.println("DESKTOP_PLAYER_FAILURE stage=$stage type=${current.javaClass.name}")
        current.stackTrace.filter { it.className.startsWith("app.pictureshow.desktop.") }.take(12)
            .forEach { System.err.println("DESKTOP_PLAYER_FRAME ${it.className}.${it.methodName}:${it.lineNumber}") }
        cause = current.cause
    }
}
