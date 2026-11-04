/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app graph needs a target host. One that is never bound opens no socket and changes nothing a test observes. */
internal fun inertRemoteTarget() = RemoteTargetHost(
    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    { _, _, _ -> error("An inert remote target never connects") },
    register = { _, _ -> 0 },
    authorization = { "" },
    nowMs = { 0L },
    trace = {}
)
