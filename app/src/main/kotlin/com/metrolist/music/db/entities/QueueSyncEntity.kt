/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * FletchTunes queue sync: what this device knows about one queue's state on the sync server.
 * A sidecar table, not columns on `queue`, so a row outlives its queue: after a delete it is the
 * tombstone that still has to reach the server, and afterwards it stops a stale copy from
 * coming back. See `playback/queue/sync/`.
 *
 * Content (songs, order, title, shuffle order) and playback (current song, position, last
 * played) are tracked apart: only content edits can conflict between devices.
 */
@Entity(tableName = "queue_sync")
data class QueueSyncEntity(
    @PrimaryKey val queueId: String,
    /** The server's content revision this device's copy was last equal to. 0 = never synced. */
    val baseRev: Long = 0L,
    /** Content hash at [baseRev]. Local content that hashes differently has unsynced edits. */
    val syncedHash: String? = null,
    /** Content hash when last seen locally; a change in it is what dates a local edit. */
    val localHash: String? = null,
    /** When and on which device the content was last edited (locally, or as received). */
    val editedAt: Long = 0L,
    val editedBy: String? = null,
    /** When the playback state last changed here, and the newest playback time the server has. */
    val playbackAt: Long = 0L,
    val playbackSyncedAt: Long = 0L,
    /** Deleted on this device or on the server. The queue row itself is gone. */
    val deleted: Boolean = false,
)
