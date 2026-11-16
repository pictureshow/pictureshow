/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.CatalogService
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistentIdentityServiceTest {
    /**
     * CatalogService gives optional reads (trailers, extras, More like this, photos, screensaver art) an empty default.
     * The app's service must forward each one to the real client; a missed override would quietly show nothing.
     */
    @Test
    fun forwardsEveryCatalogRead() {
        val declared = PersistentIdentityService::class.java.declaredMethods.map { it.name to it.parameterCount }.toSet()
        val missing = CatalogService::class.java.methods
            .filter { it.declaringClass == CatalogService::class.java && !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name to it.parameterCount }
            .filterNot { it in declared }
        assertEquals(emptyList(), missing)
    }
}
