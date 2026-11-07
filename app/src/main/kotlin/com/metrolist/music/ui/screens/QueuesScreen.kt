/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * The "parallel queues" tab: lists every saved queue (OuterTune's queue-sheet UX, adapted -
 * see FLETCHTUNES-QUEUES.md). Tapping a row switches playback to it and brings the player's
 * Queue page up (not just a silent switch); a chevron lets you preview its songs inline
 * without committing to that switch. Long-press (or the overflow icon) opens a small,
 * growable per-row menu: rename, merge into another queue, delete. Archived (auto-hidden after a week untouched)
 * queues live in a collapsed section and can be restored.
 */

package com.metrolist.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.viewmodels.QueuesViewModel
import kotlinx.coroutines.launch

@Composable
fun QueuesScreen(
    viewModel: QueuesViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val activeQueues by viewModel.activeQueues.collectAsStateWithLifecycle()
    val archivedQueues by viewModel.archivedQueues.collectAsStateWithLifecycle()
    val activeQueueId by viewModel.activeQueueId.collectAsStateWithLifecycle()
    val previewSongs by viewModel.previewSongs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var queuePendingDelete by remember { mutableStateOf<QueueEntity?>(null) }
    var queuePendingRename by remember { mutableStateOf<QueueEntity?>(null) }
    var queuePendingMerge by remember { mutableStateOf<QueueEntity?>(null) }
    val context = LocalContext.current
    var showArchived by remember { mutableStateOf(false) }

    // Tapping a queue row should actually open it: switch MusicService's active queue to this
    // one *and* bring the player up to its real Queue page, not just silently flip which queue
    // is active underneath. resumeBoardQueueId tells playQueue() to reuse this exact row rather
    // than re-deriving (and possibly duplicating) it via sourceKey matching.
    fun switchTo(queue: QueueEntity) {
        // Already playing this queue: just show it. Replaying it would jump back to the last
        // saved position, which can be a few seconds behind the live one.
        if (queue.id == activeQueueId && (playerConnection?.player?.mediaItemCount ?: 0) > 0) {
            playerConnection?.requestOpenQueueSheet()
            return
        }
        scope.launch {
            val songs = viewModel.queueBoard.loadSongs(queue.id)
            if (songs.isEmpty()) return@launch
            playerConnection?.playQueue(
                ListQueue(
                    title = queue.title,
                    items = songs.map { it.toMediaItem() },
                    startIndex = queue.currentIndex.coerceIn(0, songs.size - 1),
                    position = queue.positionMs,
                    sourceKey = queue.sourceKey,
                ),
                resumeBoardQueueId = queue.id,
            )
            playerConnection?.requestOpenQueueSheet()
        }
    }

    // "Merge into": append [source]'s songs to the end of [target] (skipping songs already in
    // [target]), then delete [source]. Three cases, by which queue (if either) is playing:
    //  - target playing: the songs go into the player through addToQueue(), which keeps them
    //    ahead of any Autoplay suggestion and saves the queue itself.
    //  - source playing: the merged target takes over playback at the same song and position,
    //    playing or paused as before, so the music carries on from the queue it now lives in.
    //  - neither: a straight database append to the target.
    fun merge(source: QueueEntity, target: QueueEntity) {
        scope.launch {
            val board = viewModel.queueBoard
            val player = playerConnection?.player
            val playerHasQueue = (player?.mediaItemCount ?: 0) > 0
            val added: Int
            if (target.id == activeQueueId && player != null && playerHasQueue) {
                val inPlayer = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val songs = board.songsToMerge(source.id, inPlayer)
                if (songs.isNotEmpty()) playerConnection?.addToQueue(songs.map { it.toMediaItem() })
                added = songs.size
            } else {
                val targetSongs = board.loadSongs(target.id)
                val songs = board.songsToMerge(source.id, targetSongs.map { it.id }.toSet())
                board.appendSongs(target.id, songs)
                added = songs.size
                val merged = targetSongs + songs
                if (source.id == activeQueueId && player != null && playerHasQueue && merged.isNotEmpty()) {
                    val currentId = player.currentMediaItem?.mediaId
                    val nowAt = merged.indexOfFirst { it.id == currentId }
                    playerConnection?.playQueue(
                        ListQueue(
                            title = target.title,
                            items = merged.map { it.toMediaItem() },
                            startIndex = if (nowAt >= 0) nowAt else target.currentIndex.coerceIn(0, merged.size - 1),
                            position = if (nowAt >= 0) player.currentPosition.coerceAtLeast(0L) else target.positionMs,
                            sourceKey = target.sourceKey,
                        ),
                        resumeBoardQueueId = target.id,
                        playWhenReady = player.playWhenReady,
                    )
                }
            }
            board.delete(source.id)
            Toast.makeText(
                context,
                context.getString(
                    R.string.queue_merged,
                    target.title,
                    added,
                    (source.songCount - added).coerceAtLeast(0),
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    if (activeQueues == null || archivedQueues == null) {
        ShimmerHost(modifier = Modifier.fillMaxSize()) {
            repeat(6) {
                ListItemPlaceHolder()
            }
        }
    } else if (activeQueues.orEmpty().isEmpty() && archivedQueues.orEmpty().isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.no_active_queues),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        val resolvedActiveQueues = activeQueues.orEmpty()
        val resolvedArchivedQueues = archivedQueues.orEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
        ) {
            items(items = resolvedActiveQueues, key = { it.id }) { queue ->
                QueueCard(
                    queue = queue,
                    isActive = queue.id == activeQueueId,
                    previewSongs = previewSongs[queue.id].orEmpty(),
                    onPlay = { switchTo(queue) },
                    onDelete = { queuePendingDelete = queue },
                    onRename = { queuePendingRename = queue },
                    onMerge = { queuePendingMerge = queue },
                    onExpand = { viewModel.loadPreview(queue) },
                )
            }

            if (resolvedArchivedQueues.isNotEmpty()) {
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
                    items(items = resolvedArchivedQueues, key = { "archived:${it.id}" }) { queue ->
                        QueueCard(
                            queue = queue,
                            isActive = false,
                            archived = true,
                            previewSongs = previewSongs[queue.id].orEmpty(),
                            onPlay = { viewModel.unarchive(queue) },
                            onDelete = { queuePendingDelete = queue },
                            onRename = { queuePendingRename = queue },
                            onMerge = { queuePendingMerge = queue },
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

    queuePendingMerge?.let { source ->
        val targets = activeQueues.orEmpty().filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { queuePendingMerge = null },
            title = { Text(stringResource(R.string.merge_queue_title, source.title)) },
            text = {
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.merge_queue_no_targets))
                } else {
                    Column {
                        Text(
                            text = stringResource(R.string.merge_queue_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(items = targets, key = { it.id }) { target ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            queuePendingMerge = null
                                            merge(source, target)
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.queue_music),
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = target.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.queue_position,
                                                (target.currentIndex + 1).coerceIn(1, target.songCount.coerceAtLeast(1)),
                                                target.songCount,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (target.id == activeQueueId) {
                                            Text(
                                                text = stringResource(R.string.queue_now_playing),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { queuePendingMerge = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    queuePendingRename?.let { queue ->
        TextFieldDialog(
            icon = { Icon(painter = painterResource(R.drawable.edit), contentDescription = null) },
            title = { Text(stringResource(R.string.rename_queue)) },
            onDismiss = { queuePendingRename = null },
            initialTextFieldValue = TextFieldValue(queue.title, TextRange(queue.title.length)),
            placeholder = { Text(stringResource(R.string.queue_name)) },
            onDone = { name ->
                queuePendingRename = null
                if (name.isNotBlank()) viewModel.rename(queue, name)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueCard(
    queue: QueueEntity,
    isActive: Boolean,
    archived: Boolean = false,
    previewSongs: List<MediaMetadata>,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onExpand: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // Tapping the row is the primary action: open/resume this queue (or restore it, if
            // archived) - not just toggle the inline preview, which used to be the only thing a
            // tap did. Long-press opens the (growable) per-row action menu.
            .combinedClickable(
                onClick = onPlay,
                onLongClick = { showMenu = true },
            ),
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
                    text = stringResource(
                        R.string.queue_position,
                        (queue.currentIndex + 1).coerceIn(1, queue.songCount.coerceAtLeast(1)),
                        queue.songCount,
                    ),
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
            if (archived) {
                IconButton(onClick = onPlay) {
                    Icon(
                        painter = painterResource(R.drawable.restore),
                        contentDescription = stringResource(R.string.unarchive_queue),
                    )
                }
            }
            IconButton(onClick = {
                expanded = !expanded
                if (expanded) onExpand()
            }) {
                Icon(
                    painter = painterResource(if (expanded) R.drawable.expand_less else R.drawable.expand_more),
                    contentDescription = null,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = stringResource(R.string.queue_actions),
                    )
                }
                // Deliberately a plain two-item menu, not a plugin system - rename is the only
                // action Fletcher asked for today, but this is where delete/archive/pin-style
                // actions get added next, per FLETCHTUNES-QUEUES.md.
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_queue)) },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.edit), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.merge_queue)) },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.playlist_add), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onMerge()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_queue)) },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.delete), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
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
