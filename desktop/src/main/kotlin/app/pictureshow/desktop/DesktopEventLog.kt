/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.io.File
import java.time.Instant

/**
 * Playback outcomes in the data directory's `app.log`: start, end, stop report, watched mark and cleanup.
 * Item IDs, result kinds and stage names only; never a URL, token, path, title or server message, because the
 * viewer may attach this file to a bug report. The file is appended with one write per line (a launcher that
 * redirects the app's own output to the same file interleaves whole lines) and rotates to `app.1.log`.
 */
internal class DesktopEventLog(private val file: File, private val maxBytes: Long = 512L * 1024) {
    @Synchronized fun log(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) rotateDesktopLog(file, keep = 1)
            java.io.FileOutputStream(file, true).use { it.write("${Instant.now()} $line\n".toByteArray()) }
        }
    }
}

/** Keeps [keep] older copies (`name.1.ext` newest) and moves [file] to `.1`, so a new run never overwrites the last. */
internal fun rotateDesktopLog(file: File, keep: Int) {
    if (!file.exists() || keep < 1) return
    val base = file.name.substringBeforeLast('.')
    val ext = file.name.substringAfterLast('.', "")
    fun numbered(n: Int) = File(file.parentFile, if (ext.isEmpty()) "$base.$n" else "$base.$n.$ext")
    numbered(keep).delete()
    for (n in keep - 1 downTo 1) numbered(n).takeIf { it.exists() }?.renameTo(numbered(n + 1))
    file.renameTo(numbered(1))
}
