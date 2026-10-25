/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from OuterTune's `QueueDao.kt` (GPL-3.0, https://github.com/OuterTune/OuterTune)
 * for FletchTunes' parallel-queue feature.
 */

package com.metrolist.music.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.metrolist.music.db.entities.QueueEntity
import com.metrolist.music.db.entities.QueueSongMapEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue WHERE archived = 0 ORDER BY lastPlayedAt DESC")
    fun getActiveQueues(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queue WHERE archived = 1 ORDER BY lastPlayedAt DESC")
    fun getArchivedQueues(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queue WHERE id = :id")
    fun getQueue(id: String): Flow<QueueEntity?>

    @Query("SELECT * FROM queue WHERE id = :id")
    suspend fun getQueueOnce(id: String): QueueEntity?

    @Query("SELECT * FROM queue WHERE sourceKey = :sourceKey AND archived = 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun findActiveBySourceKey(sourceKey: String): QueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueue(queue: QueueEntity)

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun deleteQueue(id: String)

    @Query("UPDATE queue SET archived = 1 WHERE id = :id")
    suspend fun archiveQueue(id: String)

    @Query("UPDATE queue SET archived = 0, lastPlayedAt = :now WHERE id = :id")
    suspend fun unarchiveQueue(id: String, now: Long)

    /** The auto-archive sweep: anything untouched for ARCHIVE_AFTER_MS gets hidden, not deleted. */
    @Query("UPDATE queue SET archived = 1 WHERE archived = 0 AND lastPlayedAt < :cutoff")
    suspend fun archiveStaleQueues(cutoff: Long)

    @Query("UPDATE queue SET lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: String, timestamp: Long)

    @Query(
        "UPDATE queue SET currentIndex = :index, currentSongId = :songId, positionMs = :positionMs, " +
            "lastPlayedAt = :timestamp WHERE id = :id",
    )
    suspend fun updatePlaybackState(
        id: String,
        index: Int,
        songId: String?,
        positionMs: Long,
        timestamp: Long,
    )

    @Query("UPDATE queue SET shuffled = :shuffled WHERE id = :id")
    suspend fun updateShuffled(id: String, shuffled: Boolean)

    @Query("UPDATE queue SET title = :title WHERE id = :id")
    suspend fun renameQueue(id: String, title: String)

    @Query("UPDATE queue SET songCount = :count WHERE id = :id")
    suspend fun updateSongCount(id: String, count: Int)

    @Query("SELECT * FROM queue_song_map WHERE queueId = :queueId ORDER BY position ASC")
    fun getSongMaps(queueId: String): Flow<List<QueueSongMapEntity>>

    @Query("SELECT * FROM queue_song_map WHERE queueId = :queueId ORDER BY position ASC")
    suspend fun getSongMapsOnce(queueId: String): List<QueueSongMapEntity>

    @Query("DELETE FROM queue_song_map WHERE queueId = :queueId")
    suspend fun clearSongMaps(queueId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongMaps(maps: List<QueueSongMapEntity>)

    /**
     * Atomically replaces a queue's full song list. Room wraps this in a single transaction
     * (and the underlying DB is WAL-mode), so this is safe against a process kill mid-write —
     * either the whole replace lands or none of it does, unlike the old `ObjectOutputStream`
     * truncate-and-rewrite file format this is replacing.
     */
    @Transaction
    suspend fun replaceSongMaps(queueId: String, maps: List<QueueSongMapEntity>) {
        clearSongMaps(queueId)
        if (maps.isNotEmpty()) insertSongMaps(maps)
        updateSongCount(queueId, maps.size)
    }
}
