/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * FletchTunes: a song taken out of the queue in the player, kept so the Undo snackbar can put it
 * back where it was. [mediaIndex] is its place in the queue's real order, and [playOrder] is the
 * player's play order (media indices, shuffle order when [shuffled]) from just before it went.
 */
data class RemovedQueueSong(
    val mediaIndex: Int,
    val mediaItem: MediaItem,
    val shuffled: Boolean,
    val playOrder: List<Int>,
)

/**
 * Takes the song at media index [index] out of [player]'s playlist, if it is still [expectedMediaId]
 * (when given), and returns what an Undo needs; null if nothing was removed.
 *
 * This is one `removeMediaItem` call and nothing else. If the song is the one playing, ExoPlayer
 * moves on to the next song in play order (shuffle order when shuffled) by itself. Skipping to the
 * next song first, in the same main-thread turn, crashed the app (row 194, 2026-11-23): the seek
 * and the removal reach the analytics listeners as one batch carrying two different event times,
 * and media3's PlaybackStatsListener (YouTubeHistoryReporter's) throws IllegalArgumentException
 * from `PlaybackStatsTracker.updatePlaybackState`. Upstream Metrolist's swipe also only removed.
 */
fun removeQueueSong(player: Player, index: Int, expectedMediaId: String? = null): RemovedQueueSong? {
    if (index !in 0 until player.mediaItemCount) return null
    val mediaItem = player.getMediaItemAt(index)
    if (expectedMediaId != null && mediaItem.mediaId != expectedMediaId) return null
    val removed = RemovedQueueSong(index, mediaItem, player.shuffleModeEnabled, playOrderOf(player))
    player.removeMediaItem(index)
    return removed
}

/** [player]'s songs as media indices in the order they play (the shuffle order when shuffled). */
fun playOrderOf(player: Player): List<Int> {
    val timeline = player.currentTimeline
    val shuffle = player.shuffleModeEnabled
    val order = ArrayList<Int>(timeline.windowCount)
    var index = if (timeline.isEmpty) C.INDEX_UNSET else timeline.getFirstWindowIndex(shuffle)
    while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
    }
    return order
}

/**
 * For a queue's play order (media indices in the order they play), each song's place in the
 * queue's real order. QueueBoard saves this as a shuffled queue's shuffle order.
 */
fun realPositionsOf(playOrder: List<Int>): List<Int> {
    val rank = playOrder.sorted().withIndex().associate { (rank, mediaIndex) -> mediaIndex to rank }
    return playOrder.map { rank.getValue(it) }
}

/**
 * The play order to give back to the player after an Undo re-inserts a removed song: the order
 * from before the removal, if it still describes exactly [mediaItemCount] songs, else null (the
 * queue changed some other way meanwhile, so the player's own order stands).
 */
fun playOrderToRestore(saved: List<Int>, mediaItemCount: Int): IntArray? =
    saved.takeIf { it.size == mediaItemCount && it.sorted() == (0 until mediaItemCount).toList() }?.toIntArray()
