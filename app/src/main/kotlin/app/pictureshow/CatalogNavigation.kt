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

private fun animateCatalogArrival(accessible: Boolean, source: String?, destination: String?): Boolean =
    catalogArrivalUsesAnimation(accessible, source?.startsWith("player?") == true, destination?.startsWith("item?") == true)

internal fun catalogEnterTransition(accessible: Boolean, source: String?, destination: String?): EnterTransition =
    if (animateCatalogArrival(accessible, source, destination)) fadeIn(tween(700)) else EnterTransition.None

internal fun catalogExitTransition(accessible: Boolean, source: String?, destination: String?): ExitTransition =
    if (animateCatalogArrival(accessible, source, destination)) fadeOut(tween(700)) else ExitTransition.None
