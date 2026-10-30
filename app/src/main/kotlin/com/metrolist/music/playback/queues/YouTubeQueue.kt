/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.pages.NextResult
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    /**
     * FletchTunes: true only for the explicit "Start radio" actions, which are meant to fill the
     * queue with the radio. Every other start keeps radio and automix songs out of the queue.
     */
    private val isStartRadio: Boolean = false,
) : Queue {
    private var continuation: String? = null
    private var retryCount = 0
    private val maxRetries = 3

    override val queueSourceKey: String? =
        "yt_watch:${endpoint.playlistId ?: endpoint.videoId ?: "unknown"}"

    /**
     * FletchTunes: true when this queue was started from a single song (a bare videoId, or that
     * song's RDAMVM radio). Everything YouTube returns after that song is a suggestion, so only
     * the song goes into the queue and the rest go to the Autoplay list. Read from the endpoint
     * as passed in, before getInitialStatus() rewrites it.
     */
    private val isSongRadio: Boolean =
        !isStartRadio &&
            endpoint.videoId != null &&
            (endpoint.playlistId == null || endpoint.playlistId?.startsWith("RDAMVM") == true)

    // Set once the pages have moved past what the user started (song radio, or a playlist or
    // album whose panel ran into YouTube's automix).
    private var pagesAreSuggestions = isSongRadio

    override fun nextPagesAreSuggestions(): Boolean = pagesAreSuggestions

    private class EmptyRadioQueueException : IllegalStateException()

    override suspend fun getInitialStatus(): Queue.Status {
        return withContext(IO) {
            var lastException: Throwable? = null

            if (endpoint.videoId != null && endpoint.playlistId == null) {
                endpoint = WatchEndpoint(
                    videoId = endpoint.videoId,
                    playlistId = "RDAMVM${endpoint.videoId}"
                )
            }

            val isRadioRequest =
                endpoint.playlistId?.startsWith("RDAMVM") == true ||
                (endpoint.videoId != null && endpoint.playlistId == null)

            for (attempt in 0..maxRetries) {
                try {
                    val nextResult = YouTube.next(endpoint, continuation).getOrThrow()

                    var items = nextResult.items
                    val relEndpoint = nextResult.relatedEndpoint

                    if (isRadioRequest && continuation == null && items.size <= 1) {
                        if (endpoint.playlistId?.startsWith("RDAMVM") == true) {
                            throw EmptyRadioQueueException()
                        } else if (relEndpoint != null) {
                            val relatedPage = YouTube.related(relEndpoint).getOrNull()
                            if (relatedPage != null && relatedPage.songs.isNotEmpty()) {
                                val relatedSongs = relatedPage.songs.filter { it.id != endpoint.videoId }
                                items = items + relatedSongs
                            }
                        }
                    }

                    endpoint = nextResult.endpoint
                    continuation = nextResult.continuation
                    retryCount = 0
                    val mediaItems = items.map { it.toMediaItem() }
                    val currentIndex = nextResult.currentIndex ?: 0
                    // How many leading songs belong to what the user started; the rest are
                    // suggestions. A song radio keeps just the song; a playlist or album keeps
                    // everything before YouTube's automix.
                    val keep =
                        when {
                            isStartRadio -> mediaItems.size
                            isSongRadio -> (currentIndex.coerceAtLeast(0) + 1).coerceAtMost(mediaItems.size)
                            else -> nextResult.automixBoundary(currentIndex) ?: mediaItems.size
                        }
                    if (keep < mediaItems.size) pagesAreSuggestions = true
                    return@withContext Queue.Status(
                        title = nextResult.title,
                        items = mediaItems.take(keep),
                        mediaItemIndex = currentIndex,
                        suggestions = mediaItems.drop(keep),
                    )
                } catch (e: Exception) {
                    lastException = e
                    if (
                        e is EmptyRadioQueueException &&
                        endpoint.playlistId?.startsWith("RDAMVM") == true &&
                        endpoint.videoId != null
                    ) {
                        endpoint = WatchEndpoint(videoId = endpoint.videoId)
                        // It will loop again and try with just videoId
                    }
                }
            }
            throw lastException ?: Exception("Failed to get initial status")
        }
    }

    // Only a boundary after the selected song counts; otherwise keep the whole page.
    private fun NextResult.automixBoundary(currentIndex: Int): Int? =
        automixStartIndex?.takeIf { !isStartRadio && it > currentIndex }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> = fetchNextPage().items.map { it.toMediaItem() }

    override suspend fun nextPageWithSuggestions(): Queue.Page {
        if (pagesAreSuggestions) return Queue.Page(emptyList(), nextPage())
        val nextResult = fetchNextPage()
        val mediaItems = nextResult.items.map { it.toMediaItem() }
        val boundary = nextResult.automixBoundary(-1) ?: return Queue.Page(mediaItems)
        pagesAreSuggestions = true
        return Queue.Page(mediaItems.take(boundary), mediaItems.drop(boundary))
    }

    private suspend fun fetchNextPage(): NextResult {
        return withContext(IO) {
            var lastException: Throwable? = null

            for (attempt in 0..maxRetries) {
                try {
                    val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
                    endpoint = nextResult.endpoint
                    continuation = nextResult.continuation
                    retryCount = 0
                    return@withContext nextResult
                } catch (e: Exception) {
                    lastException = e
                    retryCount++
                    if (retryCount >= maxRetries) {
                        continuation = null // Stop trying to load more
                    }
                }
            }
            throw lastException ?: Exception("Failed to get next page")
        }
    }

    companion object {
        /**
         * Creates a radio queue based on a song.
         * Explicitly requests the RDAMVM playlist to trigger automotive/radio mixing.
         * [isStartRadio] is true only for the explicit "Start radio" actions, which fill the queue.
         */
        fun radio(song: MediaMetadata, isStartRadio: Boolean = false): YouTubeQueue {
            return YouTubeQueue(
                WatchEndpoint(
                    videoId = song.id,
                    playlistId = "RDAMVM${song.id}"
                ),
                song,
                isStartRadio,
            )
        }
    }
}
