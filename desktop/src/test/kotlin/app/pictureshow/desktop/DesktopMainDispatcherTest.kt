/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/** The app's hosts run on Dispatchers.Main; on desktop only kotlinx-coroutines-swing provides it (coroutines-test's
 *  stand-in is test-only), and without it the shipped app stops at launch. */
class DesktopMainDispatcherTest {
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test fun theShippedClasspathProvidesTheSwingMainDispatcher() {
        val factories = ServiceLoader.load(MainDispatcherFactory::class.java, MainDispatcherFactory::class.java.classLoader).map { it.javaClass.name }
        assertTrue("kotlinx.coroutines.swing.SwingDispatcherFactory" in factories, "Main dispatcher factories: $factories")
    }
}
