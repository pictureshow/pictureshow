/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import app.pictureshow.core.catalogArrivalUsesAnimation

private fun animateCatalogArrival(accessible: Boolean, source: String?, destination: String?, returning: Boolean): Boolean =
    catalogArrivalUsesAnimation(accessible, source?.startsWith("player?") == true, destination?.startsWith("item?") == true, returning)

internal fun catalogEnterTransition(
    accessible: Boolean,
    source: String?,
    destination: String?,
    returning: Boolean = false
): EnterTransition = if (animateCatalogArrival(accessible, source, destination, returning)) fadeIn(tween(700)) else EnterTransition.None

internal fun catalogExitTransition(accessible: Boolean, source: String?, destination: String?, returning: Boolean = false): ExitTransition =
    if (animateCatalogArrival(accessible, source, destination, returning)) fadeOut(tween(700)) else ExitTransition.None
