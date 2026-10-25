/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from OuterTune's `queue_song_map` table (`QueueDao.kt`, GPL-3.0,
 * https://github.com/OuterTune/OuterTune) for FletchTunes' parallel-queue feature.
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One (queue, position) -> song mapping. Deliberately has no foreign key to `song` (unlike the
 * `queueId` -> `queue` cascade): a song can be removed from the library while still sitting in a
 * saved queue, exactly as it can remain in a playlist, and we don't want that to cascade-delete
 * queue rows.
 */
@Entity(
    tableName = "queue_song_map",
    primaryKeys = ["queueId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = QueueEntity::class,
            parentColumns = ["id"],
            childColumns = ["queueId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("queueId"), Index("songId")],
)
data class QueueSongMapEntity(
    val queueId: String,
    val songId: String,
    val position: Int,
)
