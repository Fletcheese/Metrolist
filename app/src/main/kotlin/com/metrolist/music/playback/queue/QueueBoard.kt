/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted (not ported verbatim: package names, `MediaMetadata` shape and song foreign keys
 * all differ) from OuterTune's `playback/QueueBoard.kt` (GPL-3.0,
 * https://github.com/OuterTune/OuterTune), for FletchTunes' parallel-queue feature.
 *
 * Differences from OuterTune's defaults (see FLETCHTUNES-QUEUES.md for the full writeup):
 *  - No cap on the number of queues (OuterTune caps at 1-30 with oldest-dropped).
 *  - Instead, a queue untouched for [QueueEntity.ARCHIVE_AFTER_MS] (one week) is archived
 *    (hidden, not deleted) by [sweepStaleQueues], which callers should invoke periodically.
 *  - Every mutation here goes straight to Room inside a transaction (`QueueDao.replaceSongMaps`
 *    / `upsertQueue`), so there's no OuterTune-style 5s debounce-behind-a-mutex: SQLite's WAL
 *    mode + Room's transactions already give atomic, durable writes per call. This is the
 *    direct fix for the single-queue "forgotten on close" bug (FLETCHTUNES-QUEUES.md
 *    hypothesis 3): callers are expected to call [recordQueueEdit] on every queue mutation,
 *    not rely on a timer.
 *
 * FletchTunes queue sync: every mutation also updates the queue's `queue_sync` row (when and
 * where its content or playback last changed), and the `apply*` functions take the server's
 * copy. The sync itself lives in `sync/QueueSyncRepository.kt`.
 */

package com.metrolist.music.playback.queue

import android.content.Context
import com.metrolist.music.db.DatabaseDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import com.metrolist.music.db.daos.QueueDao
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.db.entities.QueueSongMapEntity
import com.metrolist.music.db.entities.QueueSyncEntity
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queue.sync.PlaybackState
import com.metrolist.music.playback.queue.sync.QueueContent
import com.metrolist.music.playback.queue.sync.RemoteQueue
import com.metrolist.music.playback.queue.sync.TOMBSTONE_HASH
import com.metrolist.music.playback.queue.sync.contentHash
import com.metrolist.music.playback.queue.sync.hash
import com.metrolist.music.playback.queue.sync.syncDeviceName
import com.metrolist.music.playback.queue.sync.toMediaMetadata
import com.metrolist.music.playback.queue.sync.toSyncSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueBoard @Inject constructor(
    private val queueDao: QueueDao,
    private val databaseDao: DatabaseDao,
    @ApplicationScope private val scope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    private val _activeQueueId = MutableStateFlow<String?>(null)
    val activeQueueId = _activeQueueId.asStateFlow()

    val activeQueues: Flow<List<QueueEntity>> = queueDao.getActiveQueues()
    val archivedQueues: Flow<List<QueueEntity>> = queueDao.getArchivedQueues()

    /** Queue sync: what MusicService has loaded. Set by MusicService; read by the sync. */
    @Volatile var playerAttached = false
    @Volatile var playerPlaying = false

    /** True if [id] is the queue in the player right now, so changing it means reloading the player. */
    fun isLoadedInPlayer(id: String) = playerAttached && _activeQueueId.value == id

    enum class SyncNudge { CONTENT, FLUSH }

    private val _syncNudges = MutableSharedFlow<SyncNudge>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Tells the sync that something changed: CONTENT (an edit) or FLUSH (a pause or switch). */
    val syncNudges: SharedFlow<SyncNudge> = _syncNudges.asSharedFlow()

    fun nudgeSync(nudge: SyncNudge) {
        _syncNudges.tryEmit(nudge)
    }

    private val _reloadRequests = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ids of queues the sync just changed under the player; MusicService reloads (or detaches) them. */
    val reloadRequests: SharedFlow<String> = _reloadRequests.asSharedFlow()

    /** Queue id -> until when the player's stale copy of it must not be saved over the synced one. */
    private val reloadPending = ConcurrentHashMap<String, Long>()

    private fun awaitingReload(id: String): Boolean {
        val until = reloadPending[id] ?: return false
        if (System.currentTimeMillis() < until) return true
        reloadPending.remove(id)
        return false
    }

    /** MusicService has reloaded [id] from the database, so its saves are current again. */
    fun reloadDone(id: String) {
        reloadPending.remove(id)
    }

    fun queueFlow(id: String): Flow<QueueEntity?> = queueDao.getQueue(id)

    /**
     * Get-or-create a queue for [sourceKey] (a stable key per *source*: a playlist id, a radio
     * watch endpoint, an album id, etc). If an active queue already exists for that source its
     * song list is replaced with [items] and it's touched (so replaying a playlist reuses its
     * queue, OuterTune-style); otherwise a fresh queue row is created. [sourceKey] null means
     * always create a new ad hoc queue (used for "Add to queue").
     *
     * Returns the queue id, which becomes [activeQueueId].
     */
    suspend fun startQueue(
        sourceKey: String?,
        title: String,
        items: List<MediaMetadata>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = sourceKey?.let { queueDao.findActiveBySourceKey(it) }
            val id = existing?.id ?: UUID.randomUUID().toString()

            ensureSongsExist(items)

            queueDao.upsertQueue(
                QueueEntity(
                    id = id,
                    title = title,
                    sourceKey = sourceKey,
                    shuffled = shuffled,
                    currentIndex = startIndex,
                    currentSongId = items.getOrNull(startIndex)?.id,
                    positionMs = 0L,
                    songCount = items.size,
                    createdAt = existing?.createdAt ?: now,
                    lastPlayedAt = now,
                    archived = false,
                ),
            )
            queueDao.replaceSongMaps(id, items.toSongMaps(id))
            _activeQueueId.value = id
            markContentChanged(id)
            markPlaybackChanged(id)
            nudgeSync(SyncNudge.FLUSH)
            id
        }
    }

    /** "Add to queue": always a distinct, titled queue rather than mutating whatever's active. */
    suspend fun startAdHocQueue(title: String, items: List<MediaMetadata>): String =
        startQueue(sourceKey = null, title = title, items = items)

    /** Call on every queue mutation (add/remove/move/play-next) — not on a timer. */
    suspend fun recordQueueEdit(
        id: String,
        items: List<MediaMetadata>,
        currentIndex: Int? = null,
        positionMs: Long? = null,
        // FletchTunes: whether the queue plays shuffled, and for each song (in play order) its
        // place in the queue's real order. Null leaves both as they were.
        shuffled: Boolean? = null,
        realPositions: List<Int>? = null,
    ) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // The sync has just replaced this queue; the player's copy is the old one.
                if (awaitingReload(id)) return@withLock
                ensureSongsExist(items)
                queueDao.replaceSongMaps(id, items.toSongMaps(id))
                if (shuffled != null) {
                    queueDao.updateShuffled(id, shuffled)
                    val file = shuffleOrderFile(id)
                    if (shuffled && realPositions != null && realPositions.size == items.size) {
                        writeShuffleOrder(id, realPositions)
                    } else {
                        file.delete()
                    }
                }
                if (currentIndex != null) {
                    queueDao.updatePlaybackState(
                        id = id,
                        index = currentIndex,
                        songId = items.getOrNull(currentIndex)?.id,
                        positionMs = positionMs ?: queueDao.getQueueOnce(id)?.positionMs ?: 0L,
                        timestamp = System.currentTimeMillis(),
                    )
                } else {
                    queueDao.touch(id, System.currentTimeMillis())
                }
                if (markContentChanged(id)) nudgeSync(SyncNudge.CONTENT)
                markPlaybackChanged(id)
            }
        }

    /**
     * Where a queue is up to, including the position within the current song. Deliberately
     * leaves lastPlayedAt alone: this also saves the *outgoing* queue on a switch, and touching
     * it could make that queue the "most recent" one on cold start.
     */
    suspend fun recordPlaybackState(id: String, index: Int, songId: String?, positionMs: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (awaitingReload(id)) return@withLock
                val before = queueDao.getQueueOnce(id) ?: return@withLock
                queueDao.updatePlaybackPosition(id, index, songId, positionMs)
                if (before.currentIndex != index || before.currentSongId != songId || before.positionMs != positionMs) {
                    markPlaybackChanged(id)
                }
            }
        }

    suspend fun setShuffled(id: String, shuffled: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queueDao.updateShuffled(id, shuffled)
                if (markContentChanged(id)) nudgeSync(SyncNudge.CONTENT)
            }
        }

    suspend fun rename(id: String, title: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queueDao.renameQueue(id, title)
                if (markContentChanged(id)) nudgeSync(SyncNudge.CONTENT)
            }
        }

    suspend fun switchTo(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            queueDao.touch(id, System.currentTimeMillis())
            markPlaybackChanged(id)
        }
        _activeQueueId.value = id
        nudgeSync(SyncNudge.FLUSH)
    }

    /**
     * For a queue saved while shuffled: for each song in its saved (play) order, that song's place
     * in the queue's real order. Null when the queue was not shuffled, or the saved order does not
     * fit its [size] songs, in which case the queue resumes in its saved play order, unshuffled.
     *
     * Kept in a small file per queue rather than a new column, so the Room schema is unchanged.
     */
    suspend fun savedShuffleOrder(id: String, size: Int): IntArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (queueDao.getQueueOnce(id)?.shuffled != true) return@withLock null
            val order = readShuffleOrder(id)?.toIntArray() ?: return@withLock null
            val isPermutation = order.size == size && order.sorted() == (0 until size).toList()
            order.takeIf { isPermutation }
        }
    }

    private fun shuffleOrderFile(id: String) = File(File(context.filesDir, "queue_shuffle_orders"), id)

    private fun readShuffleOrder(id: String): List<Int>? =
        runCatching { shuffleOrderFile(id).readText().split(',').map { it.trim().toInt() } }.getOrNull()

    private fun writeShuffleOrder(id: String, order: List<Int>) {
        val file = shuffleOrderFile(id)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(order.joinToString(","))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            shuffleOrderFile(id).delete()
            queueDao.deleteQueue(id)
            // The sync row stays behind as the tombstone that tells the other devices.
            val state = queueDao.getSyncState(id) ?: QueueSyncEntity(queueId = id)
            queueDao.upsertSyncState(
                state.copy(deleted = true, editedAt = System.currentTimeMillis(), editedBy = syncDeviceName(context)),
            )
        }
        if (_activeQueueId.value == id) _activeQueueId.value = null
        nudgeSync(SyncNudge.CONTENT)
    }

    /**
     * "Merge into": the songs of [sourceId] to append to another queue, in the source queue's
     * saved order, leaving out any song already in [alreadyThere] (the target's songs) and any
     * repeat within the source itself, so a merge never doubles a song up.
     */
    suspend fun songsToMerge(sourceId: String, alreadyThere: Set<String>): List<MediaMetadata> {
        val seen = alreadyThere.toMutableSet()
        return loadSongs(sourceId).filter { seen.add(it.id) }
    }

    /**
     * Appends [songs] to the end of queue [id]'s saved song list, for a queue that is not the one
     * playing (the playing queue is appended to through the player instead, which saves itself).
     * A shuffled queue keeps its saved shuffle order, with the new songs at the end of both its
     * real order and its play order, so it still resumes shuffled.
     */
    suspend fun appendSongs(id: String, songs: List<MediaMetadata>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        mutex.withLock {
            ensureSongsExist(songs)
            val existingIds = queueDao.getSongMapsOnce(id).map { it.songId }
            val oldSize = existingIds.size
            val newIds = existingIds + songs.map { it.id }
            queueDao.replaceSongMaps(id, newIds.mapIndexed { index, songId -> QueueSongMapEntity(id, songId, index) })
            if (shuffleOrderFile(id).exists()) {
                val order = readShuffleOrder(id)
                if (order != null && order.size == oldSize) writeShuffleOrder(id, order + (oldSize until newIds.size))
            }
            queueDao.touch(id, System.currentTimeMillis())
            if (markContentChanged(id)) nudgeSync(SyncNudge.CONTENT)
            markPlaybackChanged(id)
        }
    }

    suspend fun unarchive(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            queueDao.unarchiveQueue(id, System.currentTimeMillis())
            markPlaybackChanged(id)
        }
    }

    /** Most recently played, still-active queue + its songs. Used to restore on cold start. */
    suspend fun mostRecentQueue(): Pair<QueueEntity, List<MediaMetadata>>? = withContext(Dispatchers.IO) {
        val queue = queueDao.getActiveQueues().first().firstOrNull() ?: return@withContext null
        queue to loadSongs(queue.id)
    }

    suspend fun loadSongs(id: String): List<MediaMetadata> = withContext(Dispatchers.IO) {
        queueDao.getSongMapsOnce(id).mapNotNull { map -> databaseDao.song(map.songId).first()?.toMediaMetadata() }
    }

    /** Hides (never deletes) any queue untouched for a week. Call this periodically, not on every action. */
    suspend fun sweepStaleQueues() = withContext(Dispatchers.IO) {
        queueDao.archiveStaleQueues(System.currentTimeMillis() - QueueEntity.ARCHIVE_AFTER_MS)
    }

    private fun ensureSongsExist(items: List<MediaMetadata>) {
        items.forEach { databaseDao.insert(it) }
    }

    private fun List<MediaMetadata>.toSongMaps(queueId: String) =
        mapIndexed { index, song -> QueueSongMapEntity(queueId, song.id, index) }

    // ---- Queue sync ------------------------------------------------------------------------

    /** Every queue id this device knows of, live or deleted, with its sync state. */
    suspend fun syncSnapshot(): Pair<Set<String>, Map<String, QueueSyncEntity>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            queueDao.allQueuesOnce().map { it.id }.toSet() to queueDao.allSyncStates().associateBy { it.queueId }
        }
    }

    suspend fun syncState(id: String): QueueSyncEntity? = withContext(Dispatchers.IO) { queueDao.getSyncState(id) }

    /** This device's content hash for queue [id], or null if there is no such queue. */
    suspend fun localContentHash(id: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { currentHash(id) }
    }

    private suspend fun currentHash(id: String): String? {
        val queue = queueDao.getQueueOnce(id) ?: return null
        val songIds = queueDao.getSongMapsOnce(id).map { it.songId }
        val order = if (queue.shuffled) readShuffleOrder(id) else null
        return contentHash(queue.title, queue.sourceKey, queue.shuffled, songIds, order)
    }

    /** Records a local content edit if [id]'s content really changed. True if it did. */
    private suspend fun markContentChanged(id: String): Boolean {
        val hash = currentHash(id) ?: return false
        val state = queueDao.getSyncState(id) ?: QueueSyncEntity(queueId = id)
        if (state.localHash == hash && !state.deleted) return false
        queueDao.upsertSyncState(
            state.copy(
                localHash = hash,
                editedAt = System.currentTimeMillis(),
                editedBy = syncDeviceName(context),
                deleted = false,
            ),
        )
        return true
    }

    private suspend fun markPlaybackChanged(id: String) {
        if (queueDao.getQueueOnce(id) == null) return
        val state = queueDao.getSyncState(id) ?: QueueSyncEntity(queueId = id)
        queueDao.upsertSyncState(state.copy(playbackAt = System.currentTimeMillis()))
    }

    /** What to send for queue [id]: its content and hash, and its playback state. */
    suspend fun exportForSync(id: String): Triple<QueueContent, String, PlaybackState>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val queue = queueDao.getQueueOnce(id) ?: return@withLock null
            val maps = queueDao.getSongMapsOnce(id)
            val songs = maps.mapNotNull { map -> databaseDao.song(map.songId).first()?.toMediaMetadata()?.toSyncSong() }
            if (songs.size != maps.size) return@withLock null
            val content = QueueContent(
                title = queue.title,
                sourceKey = queue.sourceKey,
                shuffled = queue.shuffled,
                songs = songs,
                shuffleOrder = if (queue.shuffled) readShuffleOrder(id) else null,
                createdAt = queue.createdAt,
            )
            Triple(content, content.hash(), queue.toPlaybackState())
        }
    }

    suspend fun exportPlayback(id: String): PlaybackState? = withContext(Dispatchers.IO) {
        queueDao.getQueueOnce(id)?.toPlaybackState()
    }

    private fun QueueEntity.toPlaybackState() = PlaybackState(currentIndex, currentSongId, positionMs, lastPlayedAt)

    /** Updates [id]'s sync state in place (after the server accepted a push, for example). */
    suspend fun updateSyncState(id: String, transform: (QueueSyncEntity) -> QueueSyncEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            queueDao.upsertSyncState(transform(queueDao.getSyncState(id) ?: QueueSyncEntity(queueId = id)))
        }
    }

    /**
     * Makes the local queue match the server's [remote] copy: creates or overwrites it, or deletes
     * it for a tombstone. Unless [force] (the user chose the server's copy), this backs off and
     * returns false if the queue was edited here since the sync decided, which the next sync sees
     * as a conflict. A queue loaded in the player is reloaded from the database afterwards.
     */
    suspend fun applyRemote(remote: RemoteQueue, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val applied = mutex.withLock {
            val id = remote.id
            val state = queueDao.getSyncState(id)
            if (!force && state != null && !state.deleted && currentHash(id) != state.syncedHash) return@withLock false
            val content = remote.content
            if (remote.deleted || content == null) {
                shuffleOrderFile(id).delete()
                queueDao.deleteQueue(id)
                queueDao.upsertSyncState(
                    (state ?: QueueSyncEntity(queueId = id)).copy(
                        baseRev = remote.rev,
                        syncedHash = TOMBSTONE_HASH,
                        localHash = null,
                        editedAt = remote.editedAt,
                        editedBy = remote.editedBy,
                        deleted = true,
                    ),
                )
                return@withLock true
            }
            val existing = queueDao.getQueueOnce(id)
            val songs = content.songs.map { it.toMediaMetadata() }
            ensureSongsExist(songs)
            val takePlayback = remote.playback != null &&
                remote.playbackAt > maxOf(state?.playbackAt ?: 0L, state?.playbackSyncedAt ?: 0L)
            val playback = if (takePlayback) remote.playback else null
            val lastPlayedAt = maxOf(existing?.lastPlayedAt ?: 0L, playback?.lastPlayedAt ?: 0L, if (existing == null) remote.editedAt else 0L)
            queueDao.upsertQueue(
                QueueEntity(
                    id = id,
                    title = content.title,
                    sourceKey = content.sourceKey,
                    shuffled = content.shuffled,
                    currentIndex = playback?.currentIndex ?: existing?.currentIndex ?: 0,
                    currentSongId = playback?.currentSongId ?: existing?.currentSongId ?: songs.firstOrNull()?.id,
                    positionMs = playback?.positionMs ?: existing?.positionMs ?: 0L,
                    songCount = songs.size,
                    createdAt = existing?.createdAt ?: content.createdAt.takeIf { it > 0 } ?: remote.editedAt,
                    lastPlayedAt = lastPlayedAt,
                    archived = (existing?.archived ?: false) && !playedRecently(lastPlayedAt),
                ),
            )
            queueDao.replaceSongMaps(id, songs.toSongMaps(id))
            val order = content.shuffleOrder
            if (content.shuffled && order != null && order.size == songs.size) writeShuffleOrder(id, order) else shuffleOrderFile(id).delete()
            val hash = content.hash()
            queueDao.upsertSyncState(
                (state ?: QueueSyncEntity(queueId = id)).copy(
                    baseRev = remote.rev,
                    syncedHash = hash,
                    localHash = hash,
                    editedAt = remote.editedAt,
                    editedBy = remote.editedBy,
                    playbackAt = if (takePlayback) remote.playbackAt else state?.playbackAt ?: 0L,
                    playbackSyncedAt = maxOf(state?.playbackSyncedAt ?: 0L, remote.playbackAt),
                    deleted = false,
                ),
            )
            true
        }
        if (applied) afterRemoteChange(remote.id)
        applied
    }

    /** Takes the server's newer playback state for [remote]'s queue (never a conflict). */
    suspend fun applyRemotePlayback(remote: RemoteQueue) = withContext(Dispatchers.IO) {
        val playback = remote.playback ?: return@withContext
        val applied = mutex.withLock {
            val existing = queueDao.getQueueOnce(remote.id) ?: return@withLock false
            val lastPlayedAt = maxOf(existing.lastPlayedAt, playback.lastPlayedAt)
            val index = playback.currentIndex.coerceIn(0, maxOf(existing.songCount - 1, 0))
            queueDao.updatePlaybackState(
                id = remote.id,
                index = index,
                songId = playback.currentSongId,
                positionMs = playback.positionMs,
                timestamp = lastPlayedAt,
            )
            if (existing.archived && playedRecently(lastPlayedAt)) queueDao.unarchiveQueue(remote.id, lastPlayedAt)
            val state = queueDao.getSyncState(remote.id) ?: QueueSyncEntity(queueId = remote.id)
            queueDao.upsertSyncState(state.copy(playbackAt = remote.playbackAt, playbackSyncedAt = remote.playbackAt))
            // Only a real move reloads the player. Reloading marks the queue played here, which
            // the other device receives as newer; if that also reloaded, the two would echo forever.
            existing.currentIndex != index || existing.currentSongId != playback.currentSongId ||
                existing.positionMs != playback.positionMs
        }
        if (applied) afterRemoteChange(remote.id)
    }

    private fun playedRecently(lastPlayedAt: Long) =
        lastPlayedAt > System.currentTimeMillis() - QueueEntity.ARCHIVE_AFTER_MS

    private fun afterRemoteChange(id: String) {
        if (!isLoadedInPlayer(id)) return
        reloadPending[id] = System.currentTimeMillis() + RELOAD_GRACE_MS
        _reloadRequests.tryEmit(id)
    }

    private companion object {
        /** How long a stale player copy is kept from saving over a synced queue, at most. */
        const val RELOAD_GRACE_MS = 5_000L
    }
}
