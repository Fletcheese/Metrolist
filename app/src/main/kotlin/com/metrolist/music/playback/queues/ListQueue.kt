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
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override val queueSourceKey: String? = sourceKey ?: title?.let { "list:$it" }

    override suspend fun getInitialStatus() = Queue.Status(title, items, startIndex, position)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()
}
