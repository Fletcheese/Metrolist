/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata

class ListQueue(
    val title: String? = null,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val position: Long = 0L,
    /**
     * Stable id of whatever this list came from (a local playlist id, an album id, ...), when the
     * caller has one. Not every call site threads one through yet (see FLETCHTUNES-QUEUES.md) —
     * falling back to a title-based key below is a best-effort heuristic, not a guarantee.
     */
    val sourceKey: String? = null,
    /**
     * FletchTunes: the user tapped one song in a list that is not an album or playlist (Library
     * songs, History, local search results, an artist's library songs). Only that song goes
     * into the queue; the songs after it go to the Autoplay list.
     */
    val songOnly: Boolean = false,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override val queueSourceKey: String? =
        if (songOnly) null else sourceKey ?: title?.let { "list:$it" }

    override suspend fun getInitialStatus() =
        if (songOnly && startIndex in items.indices) {
            Queue.Status(title, listOf(items[startIndex]), 0, position, suggestions = items.drop(startIndex + 1))
        } else {
            Queue.Status(title, items, startIndex, position)
        }

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()
}
