/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    /**
     * Stable key identifying the *source* this queue plays from (a playlist id, a radio watch
     * endpoint, an album, ...). `QueueBoard` (`playback/queue/QueueBoard.kt`) uses this to decide
     * whether starting this queue should reuse an existing saved queue for the same source
     * (OuterTune's "replaying a source reuses its queue") or create a new one. Null means
     * "always create a new, ad hoc queue" (e.g. a bare song tap or "Add to queue").
     */
    val queueSourceKey: String? get() = null

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    /**
     * FletchTunes: true when this queue's further pages are suggestions (a radio, YouTube's
     * automix) rather than more of what the user started. MusicService then feeds them to the
     * Autoplay list, never to the user's queue. May turn true after a page crosses into automix.
     */
    fun nextPagesAreSuggestions(): Boolean = false

    /** The next page, split into more of the source ([Page.items]) and suggestions. */
    suspend fun nextPageWithSuggestions(): Page =
        if (nextPagesAreSuggestions()) Page(emptyList(), nextPage()) else Page(nextPage())

    data class Page(
        val items: List<MediaItem>,
        val suggestions: List<MediaItem> = emptyList(),
    )

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
        /** Songs the source offered beyond what the user started: they go to Autoplay. */
        val suggestions: List<MediaItem> = emptyList(),
    ) {
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) filterItems { it.metadata?.explicit != true } else this

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) filterItems { it.metadata?.isVideoSong != true } else this

        // Keeps mediaItemIndex on the same song; if that song is removed, starts at the next kept one.
        private fun filterItems(keep: (MediaItem) -> Boolean): Status {
            val filtered = items.filter(keep)
            val keptSuggestions = suggestions.filter(keep)
            if (filtered.size == items.size) return copy(suggestions = keptSuggestions)
            val start = items.getOrNull(mediaItemIndex)
            val keptBefore = items.take(mediaItemIndex.coerceAtLeast(0)).count(keep)
            return copy(
                items = filtered,
                mediaItemIndex = keptBefore.coerceAtMost((filtered.size - 1).coerceAtLeast(0)),
                position = if (start != null && keep(start)) position else 0L,
                suggestions = keptSuggestions,
            )
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }
