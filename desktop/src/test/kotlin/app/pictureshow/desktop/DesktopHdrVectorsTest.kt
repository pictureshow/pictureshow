/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Run the actual leg-136 analyzer and its clipping/scale/gamut negative controls inside the JVM gate. */
class DesktopHdrVectorsTest {
    @Test fun numericHdrContractAndNegativeControls() {
        val repository = File(requireNotNull(System.getProperty("pictureshow.repository")))
        val python = System.getenv("PYTHON") ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val log = File.createTempFile("pictureshow-hdr-vectors-", ".log")
        try {
            val process = ProcessBuilder(python, "-m", "unittest", "-v", "test_analyze")
                .directory(File(repository, "desktop/tools/hdr"))
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error("HDR vector analyzer exceeded 60 seconds: ${log.readText()}")
            }
            assertEquals(0, process.exitValue(), log.readText())
            assertTrue(log.readText().contains("test_desktop_numeric_fixture"), "Desktop numeric fixture was not executed")
        } finally {
            log.delete()
        }
    }
}
