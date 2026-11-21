/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import java.util.concurrent.Executor

@Suppress("UNUSED_PARAMETER")
internal fun storeSupporterBilling(context: Context): SupporterBilling? = null

@Suppress("UNUSED_PARAMETER")
internal fun castServicesState(context: Context): String = "missing"

@Suppress("UNUSED_PARAMETER")
internal fun createPlatformCastLink(context: Context, mainExecutor: Executor): CastLink =
    error("Google Cast is unavailable on Fire TV")
