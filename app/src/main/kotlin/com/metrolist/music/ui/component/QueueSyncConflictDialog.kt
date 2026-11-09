/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.text.format.DateUtils
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.playback.queue.sync.QueueSyncRepository

/**
 * FletchTunes queue sync: asks which copy wins when a queue was changed both here and on another
 * device since they last synced. One conflict at a time; dismissing it leaves it for next time.
 */
@Composable
fun QueueSyncConflictDialog(repository: QueueSyncRepository) {
    val conflicts by repository.conflicts.collectAsStateWithLifecycle()
    val conflict = conflicts.firstOrNull() ?: return
    val context = LocalContext.current
    val other = conflict.remote.editedBy?.takeIf { it.isNotBlank() } ?: stringResource(R.string.queue_sync_other_device)
    val time = DateUtils.formatDateTime(
        context,
        conflict.remote.editedAt,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val message = when {
        conflict.remote.deleted -> stringResource(R.string.queue_sync_conflict_remote_deleted, other, time)
        conflict.localDeleted -> stringResource(R.string.queue_sync_conflict_local_deleted, other, time)
        else -> stringResource(R.string.queue_sync_conflict_message, other, time)
    }
    AlertDialog(
        // Not settled: the next sync asks again.
        onDismissRequest = {},
        title = { Text(conflict.title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { repository.keepLocal(conflict) }) {
                Text(stringResource(R.string.queue_sync_keep_local))
            }
        },
        dismissButton = {
            TextButton(onClick = { repository.keepRemote(conflict) }) {
                Text(other)
            }
        },
    )
}
