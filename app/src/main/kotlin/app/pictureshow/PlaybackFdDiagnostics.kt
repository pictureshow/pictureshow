/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.system.Os
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One opening sample per viewing; reopens reuse the output and do not enumerate again. */
internal class PlaybackFdDiagnostics(
    private val capture: () -> PlaybackFdSnapshot = { PlaybackFdSnapshot.capture() },
    private val write: (String) -> Unit = PlaybackDiagnostics::write
) {
    private var sampled = false

    fun opened(previousReleasePending: () -> Boolean) {
        if (sampled) return
        sampled = true
        worker.launch {
            val started = System.nanoTime()
            val fields = try {
                val pending = previousReleasePending()
                "previousReleasePending=$pending ${capture().fields()}"
            } catch (_: Exception) {
                // Diagnostics cannot interrupt playback, even if platform inspection fails unexpectedly.
                "available=false"
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            try {
                write("fd-snapshot phase=open $fields elapsedMs=$elapsedMs")
            } catch (_: Exception) {
                // Logging is best effort too; never forward private exception details.
            }
        }
    }

    private companion object {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Targets exist only while counting. Neither paths, descriptor numbers nor exception messages escape. */
internal data class PlaybackFdSnapshot(val counts: Map<String, Int>?, val unreadable: Int = 0) {
    fun fields(): String {
        val values = counts ?: return "available=false"
        return "available=true total=${values.values.sum()} " +
            kinds.joinToString(" ") { "$it=${values.getValue(it)}" } + " unreadable=$unreadable"
    }

    companion object {
        private val kinds = listOf("dmabuf", "ion", "gralloc", "sockets", "pipes", "anon_inode", "other")

        fun capture(directory: File = File("/proc/self/fd"), readlink: (String) -> String? = Os::readlink): PlaybackFdSnapshot {
            val entries = try {
                directory.list()
            } catch (_: SecurityException) {
                null
            } ?: return PlaybackFdSnapshot(null)
            val counts = kinds.associateWith { 0 }.toMutableMap()
            var unreadable = 0
            for (entry in entries) {
                val target = try {
                    readlink(File(directory, entry).path)
                } catch (_: Exception) {
                    // An FD can close between enumeration and readlink; include it in the total.
                    null
                }
                if (target == null) unreadable++
                val kind = kind(target.orEmpty())
                counts[kind] = counts.getValue(kind) + 1
            }
            return PlaybackFdSnapshot(counts, unreadable)
        }

        private fun kind(target: String): String = when {
            (target.startsWith("anon_inode:") && (target.contains("dmabuf") || target.contains("dma_buf") || target.contains("dma-buf"))) ||
                target.startsWith("/dmabuf") ||
                target.startsWith("dmabuf:") ||
                target.startsWith("/dev/dma_heap/") -> "dmabuf"
            target == "/dev/ion" || target.startsWith("/dev/ion/") -> "ion"
            target.startsWith("/dev/gralloc") ||
                target.startsWith("/dev/mali") ||
                target.startsWith("/dev/kgsl") ||
                target.startsWith("/dev/dri/") ||
                target.startsWith("/dev/goldfish_address_space") ||
                target.startsWith("/dev/goldfish_pipe") -> "gralloc"
            target.startsWith("socket:") -> "sockets"
            target.startsWith("pipe:") -> "pipes"
            target.startsWith("anon_inode:") -> "anon_inode"
            else -> "other"
        }
    }
}
