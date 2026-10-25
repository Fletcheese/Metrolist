/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from OuterTune's `MultiQueueObject` (`app/src/main/java/com/dd3boh/outertune/models/MultiQueueObject.kt`,
 * GPL-3.0, https://github.com/OuterTune/OuterTune) for FletchTunes' parallel-queue feature.
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One parallel, independently-persisted playback queue ("QueueBoard" entry in OuterTune's
 * terms). Unlike OuterTune there is no hard cap on the number of rows here (Fletcher opted
 * out of the 1-30 cap-with-oldest-dropped default) — instead, a queue that hasn't been played
 * from in [ARCHIVE_AFTER_MS] is flipped to [archived] by a periodic sweep
 * (see `playback/queue/QueueBoard.kt`), which hides it from the main list without deleting it.
 *
 * Room entities have no equivalent of a Java `serialVersionUID`; the closest analogue is
 * keeping this shape change-additive-only and bumping `MusicDatabase`'s `version` + adding a
 * migration whenever a column changes, which is what actually keeps old rows readable.
 */
@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val id: String,
    val title: String,
    /**
     * Opaque key identifying the *source* of this queue (e.g. a playlist id, a radio/watch
     * endpoint, or an "ad hoc" marker). Used so re-starting the same source reuses its queue
     * instead of creating a duplicate, matching OuterTune's "replaying a source reuses its
     * queue" behavior. Null for one-off/ad hoc queues (e.g. "Add to queue" results).
     */
    val sourceKey: String? = null,
    val shuffled: Boolean = false,
    val currentIndex: Int = 0,
    val currentSongId: String? = null,
    val positionMs: Long = 0L,
    /** Cached count of songs in this queue, maintained by QueueBoard, for cheap list display. */
    val songCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last time this queue was switched to / played from. Drives the archive sweep. */
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
) {
    companion object {
        /** A queue untouched for this long is auto-archived. See QueueBoard's sweep. */
        const val ARCHIVE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000 // 1 week
    }
}
