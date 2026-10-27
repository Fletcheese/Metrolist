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
 */

package com.metrolist.music.playback.queue

import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.daos.QueueDao
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.db.entities.QueueSongMapEntity
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueBoard @Inject constructor(
    private val queueDao: QueueDao,
    private val databaseDao: DatabaseDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private val _activeQueueId = MutableStateFlow<String?>(null)
    val activeQueueId = _activeQueueId.asStateFlow()

    val activeQueues: Flow<List<QueueEntity>> = queueDao.getActiveQueues()
    val archivedQueues: Flow<List<QueueEntity>> = queueDao.getArchivedQueues()

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
    ) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureSongsExist(items)
                queueDao.replaceSongMaps(id, items.toSongMaps(id))
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
            }
        }

    /**
     * Where a queue is up to, including the position within the current song. Deliberately
     * leaves lastPlayedAt alone: this also saves the *outgoing* queue on a switch, and touching
     * it could make that queue the "most recent" one on cold start.
     */
    suspend fun recordPlaybackState(id: String, index: Int, songId: String?, positionMs: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock { queueDao.updatePlaybackPosition(id, index, songId, positionMs) }
        }

    suspend fun setShuffled(id: String, shuffled: Boolean) =
        withContext(Dispatchers.IO) { queueDao.updateShuffled(id, shuffled) }

    suspend fun rename(id: String, title: String) =
        withContext(Dispatchers.IO) { queueDao.renameQueue(id, title) }

    suspend fun switchTo(id: String) = withContext(Dispatchers.IO) {
        queueDao.touch(id, System.currentTimeMillis())
        _activeQueueId.value = id
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        queueDao.deleteQueue(id)
        if (_activeQueueId.value == id) _activeQueueId.value = null
    }

    suspend fun unarchive(id: String) = withContext(Dispatchers.IO) {
        queueDao.unarchiveQueue(id, System.currentTimeMillis())
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
}
