/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.io.File

/** Installed app entry point. The separate Gradle launcher also owns the graphics probes. */
fun main(args: Array<String>) {
    // Skiko extracts its signed native library here. The Store build needs a
    // cache inside its container even when the JVM reports the login home.
    System.setProperty("skiko.data.path", File(desktopDataDirectory(), "graphics-cache").path)
    // Start listening for verified App Store transaction updates at launch.
    packagedStoreKit
    val smoke = args.firstOrNull() == "app-smoke" && System.getenv("PICTURESHOW_SMOKE") == "1"
    desktopMain(if (smoke) args else emptyArray())
}
