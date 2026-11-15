/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.sync.SyncStayReminder
import org.koin.compose.koinInject

/** SYN-08 reminder routes: Home, Libraries and title pages. */
internal fun syncStayRoute(route: String?): Boolean =
    route == "home" || route == "libraries" || route?.startsWith("item?") == true

/** One-line hook for the phone shell: shows nothing outside a group or off its routes. */
@Composable
internal fun TouchSyncStayReminder(route: String?) {
    if (!syncStayRoute(route)) return
    val sync: SyncPlayHost = koinInject()
    val reminder = sync.stayReminder ?: return
    TouchSyncStayBar(reminder, onWatch = sync::rejoinPlayback, onLeave = sync::leave)
}

/** One-line hook for the TV shell, placed with the bottom bars so it never covers a focused row. */
@Composable
internal fun TvSyncStayReminder(route: String?) {
    if (!syncStayRoute(route)) return
    val sync: SyncPlayHost = koinInject()
    val reminder = sync.stayReminder ?: return
    TvSyncStayBar(reminder, onWatch = sync::rejoinPlayback, onLeave = sync::leave)
}

/** True when the TV bottom area should reserve room for the reminder. */
@Composable
internal fun tvShowsSyncStay(route: String?): Boolean {
    if (!syncStayRoute(route)) return false
    val sync: SyncPlayHost = koinInject()
    return sync.stayReminder != null
}

@Composable
private fun syncStayLine(reminder: SyncStayReminder): String = when {
    reminder.leaveUnconfirmed -> reminder.groupName?.let { stringResource(R.string.sync_stay_leave_unconfirmed, it) }
        ?: stringResource(R.string.sync_stay_leave_unconfirmed_unnamed)
    else -> reminder.groupName?.let { stringResource(R.string.sync_stay_still_in, it) }
        ?: stringResource(R.string.sync_stay_still_in_unnamed)
}

@Composable
private fun syncStayGroup(reminder: SyncStayReminder): String =
    reminder.groupName ?: stringResource(R.string.sync_departure_group)

@Composable
internal fun TouchSyncStayBar(reminder: SyncStayReminder, onWatch: () -> Unit, onLeave: () -> Unit) {
    val group = syncStayGroup(reminder)
    val watchDescription = stringResource(R.string.sync_stay_watch_description, group)
    val leaveDescription = stringResource(R.string.sync_stay_leave_description, group)
    Row(
        Modifier.fillMaxWidth()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .testTag("sync:stay"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.material3.Icon(
            syncIcon("group"),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.material3.Text(
            syncStayLine(reminder),
            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("sync:stay:line"),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        if (reminder.rejoin) {
            androidx.compose.material3.TextButton(
                onClick = onWatch,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = watchDescription }.testTag("sync:stay:watch")
            ) { androidx.compose.material3.Text(stringResource(R.string.sync_stay_watch)) }
        }
        androidx.compose.material3.TextButton(
            onClick = onLeave,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = leaveDescription }.testTag("sync:stay:leave"),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        ) { androidx.compose.material3.Text(stringResource(R.string.sync_stay_leave)) }
    }
}

@Composable
internal fun TvSyncStayBar(reminder: SyncStayReminder, onWatch: () -> Unit, onLeave: () -> Unit) {
    val group = syncStayGroup(reminder)
    val watchDescription = stringResource(R.string.sync_stay_watch_description, group)
    val leaveDescription = stringResource(R.string.sync_stay_leave_description, group)
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1B2130)).padding(horizontal = 48.dp, vertical = 8.dp).testTag("sync:stay"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.tv.material3.Icon(syncIcon("group"), contentDescription = null, modifier = Modifier.size(24.dp))
        androidx.tv.material3.Text(
            syncStayLine(reminder),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("sync:stay:line"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (reminder.rejoin) {
            androidx.tv.material3.Button(
                onClick = onWatch,
                modifier = Modifier.semantics { contentDescription = watchDescription }.testTag("sync:stay:watch")
            ) { androidx.tv.material3.Text(stringResource(R.string.sync_stay_watch)) }
        }
        androidx.tv.material3.OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.semantics { contentDescription = leaveDescription }.testTag("sync:stay:leave")
        ) { androidx.tv.material3.Text(stringResource(R.string.sync_stay_leave)) }
    }
}
