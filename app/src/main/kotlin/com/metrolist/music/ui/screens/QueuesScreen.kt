/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The "parallel queues" tab: lists every saved queue (OuterTune's queue-sheet UX, adapted -
 * see FLETCHTUNES-QUEUES.md). Lets you preview a queue's songs, switch playback to it, or
 * delete it; archived (auto-hidden after a week untouched) queues live in a collapsed section
 * and can be restored.
 */

package com.metrolist.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.viewmodels.QueuesViewModel
import kotlinx.coroutines.launch

@Composable
fun QueuesScreen(
    viewModel: QueuesViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val activeQueues by viewModel.activeQueues.collectAsState()
    val archivedQueues by viewModel.archivedQueues.collectAsState()
    val activeQueueId by viewModel.activeQueueId.collectAsState()
    val previewSongs by viewModel.previewSongs.collectAsState()
    val scope = rememberCoroutineScope()

    var queuePendingDelete by remember { mutableStateOf<QueueEntity?>(null) }
    var showArchived by remember { mutableStateOf(false) }

    fun switchTo(queue: QueueEntity) {
        scope.launch {
            val songs = viewModel.queueBoard.loadSongs(queue.id)
            if (songs.isEmpty()) return@launch
            viewModel.queueBoard.switchTo(queue.id)
            playerConnection?.playQueue(
                ListQueue(
                    title = queue.title,
                    items = songs.map { it.toMediaItem() },
                    startIndex = queue.currentIndex.coerceIn(0, songs.size - 1),
                    position = queue.positionMs,
                    sourceKey = queue.sourceKey,
                ),
            )
        }
    }

    if (activeQueues.isEmpty() && archivedQueues.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.no_active_queues),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(items = activeQueues, key = { it.id }) { queue ->
                QueueCard(
                    queue = queue,
                    isActive = queue.id == activeQueueId,
                    previewSongs = previewSongs[queue.id].orEmpty(),
                    onPlay = { switchTo(queue) },
                    onDelete = { queuePendingDelete = queue },
                    onExpand = { viewModel.loadPreview(queue) },
                )
            }

            if (archivedQueues.isNotEmpty()) {
                item(key = "archived_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showArchived = !showArchived }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.archived_queues),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.queue_archived_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showArchived) {
                    items(items = archivedQueues, key = { "archived:${it.id}" }) { queue ->
                        QueueCard(
                            queue = queue,
                            isActive = false,
                            archived = true,
                            previewSongs = previewSongs[queue.id].orEmpty(),
                            onPlay = { viewModel.unarchive(queue) },
                            onDelete = { queuePendingDelete = queue },
                            onExpand = { viewModel.loadPreview(queue) },
                        )
                    }
                }
            }
        }
    }

    queuePendingDelete?.let { queue ->
        AlertDialog(
            onDismissRequest = { queuePendingDelete = null },
            title = { Text(stringResource(R.string.delete_queue)) },
            text = { Text(stringResource(R.string.delete_queue_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(queue)
                    queuePendingDelete = null
                }) { Text(stringResource(R.string.delete_queue)) }
            },
            dismissButton = {
                TextButton(onClick = { queuePendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun QueueCard(
    queue: QueueEntity,
    isActive: Boolean,
    archived: Boolean = false,
    previewSongs: List<MediaMetadata>,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onExpand: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable {
                expanded = !expanded
                if (expanded) onExpand()
            },
        colors = if (isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = queue.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.queue_song_count, queue.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isActive) {
                    Text(
                        text = stringResource(R.string.queue_now_playing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onPlay) {
                Icon(
                    painter = painterResource(if (archived) R.drawable.restore else R.drawable.play),
                    contentDescription = stringResource(
                        if (archived) R.string.unarchive_queue else R.string.switch_to_queue,
                    ),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_queue),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 44.dp, end = 12.dp, bottom = 12.dp)) {
                previewSongs.take(10).forEach { song ->
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (previewSongs.size > 10) {
                    Text(
                        text = "+${previewSongs.size - 10}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
