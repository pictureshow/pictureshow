/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.TextPersistence
import app.pictureshow.core.playback.PlaybackPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Commit a sibling file before replacing the account snapshot. A failed read never resets sign-ins. */
class DesktopTextPersistence(private val file: File) : TextPersistence {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (file.exists()) file.readText() else null
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        atomicPrivateWrite(file, value.toByteArray(Charsets.UTF_8))
    }
}

internal fun atomicPrivateWrite(file: File, bytes: ByteArray) {
    Files.createDirectories(file.parentFile.toPath())
    runCatching { Files.setPosixFilePermissions(file.parentFile.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) }
    val temporary = Files.createTempFile(file.parentFile.toPath(), ".pictureshow-", ".tmp")
    try {
        runCatching { Files.setPosixFilePermissions(temporary, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        java.io.FileOutputStream(temporary.toFile()).use { stream -> stream.write(bytes); stream.fd.sync() }
        Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

class DesktopSettingsStore(private val file: File) {
    fun read(): DesktopSettings {
        if (!file.exists()) return DesktopSettings()
        val p = Properties().apply { file.inputStream().use(::load) }
        return DesktopSettings(
            bitrateMbps = PlaybackPreferences.decode(bitrateMbps = p.getProperty("bitrate")?.toIntOrNull()).bitrateMbps,
            conversionNotice = p.getProperty("conversionNotice")?.toBooleanStrictOrNull() ?: true,
            autoplayNext = p.getProperty("autoplayNext")?.toBooleanStrictOrNull() ?: true,
            volume = (p.getProperty("volume")?.toIntOrNull() ?: 80).coerceIn(0, 100),
            largeInterface = p.getProperty("largeInterface")?.toBooleanStrictOrNull() ?: false
        )
    }
    fun write(settings: DesktopSettings) {
        PlaybackPreferences.validateBitrate(settings.bitrateMbps)
        require(settings.volume in 0..100)
        val p = Properties().apply {
            setProperty("bitrate", settings.bitrateMbps?.toString().orEmpty())
            setProperty("conversionNotice", settings.conversionNotice.toString())
            setProperty("autoplayNext", settings.autoplayNext.toString())
            setProperty("volume", settings.volume.toString())
            setProperty("largeInterface", settings.largeInterface.toString())
        }
        val bytes = java.io.ByteArrayOutputStream().use { out -> p.store(out, "Pictureshow desktop preferences"); out.toByteArray() }
        atomicPrivateWrite(file, bytes)
    }
}

fun desktopDataDirectory(): File {
    System.getenv("PICTURESHOW_DATA_DIR")?.let { return File(it) }
    val os = System.getProperty("os.name")
    val base = when {
        os.startsWith("Windows") -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
        os.startsWith("Mac") -> File(System.getProperty("user.home"), "Library/Application Support")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(System.getProperty("user.home"), ".local/share").path)
    }
    return File(base, "Pictureshow")
}
