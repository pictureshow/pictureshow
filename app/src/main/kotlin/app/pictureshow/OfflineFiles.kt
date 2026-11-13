/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.offlineEntryDir
import app.pictureshow.core.offline.offlineSafePath
import app.pictureshow.core.offline.offlineSha256Hex
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Every viewer's private bytes live under one directory named by a hash, in storage excluded from backup. */
internal class OfflineFiles(private val base: File) {
    val root: File get() = File(base, "offline")
    val trash: File get() = File(root, "trash")

    fun accountDir(accountKey: String) = File(root, "a-" + offlineSha256Hex(accountKey.encodeToByteArray()).take(32))
    fun accountDirs(): List<File> = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("a-") }.orEmpty()
    fun mediaDir(accountKey: String) = File(accountDir(accountKey), "media")
    fun manifest(accountKey: String) = File(accountDir(accountKey), "manifest.json")
    fun progress(accountKey: String) = File(accountDir(accountKey), "progress.json")
    fun sessions() = File(root, "sessions.json")

    fun resource(accountKey: String, entryId: String, path: String): File {
        require(offlineSafePath(path)) { "Unsafe offline path" }
        return File(File(mediaDir(accountKey), offlineEntryDir(entryId)), path)
    }

    /** Relative path -> size, as the manifest reconcile expects it. */
    fun inventory(accountKey: String): Map<String, Long> {
        val media = mediaDir(accountKey)
        return media.walkTopDown().filter(File::isFile).associate { it.relativeTo(media).invariantSeparatorsPath to it.length() }
    }

    /** A rename makes the viewer's data unreachable first; the slow delete follows and is retried at next start. */
    fun moveToTrash(dir: File): Boolean {
        if (!dir.exists()) return true
        trash.mkdirs()
        return dir.renameTo(File(trash, dir.name + "-" + System.nanoTime()))
    }

    fun emptyTrash() {
        trash.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** A crash leaves the old file or the new one, never a torn one: sync, then rename over. */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.tmp")
    FileOutputStream(temp).use { out ->
        out.write(text.encodeToByteArray())
        out.fd.sync()
    }
    check(temp.renameTo(this)) { "Could not commit $name" }
}

internal fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
