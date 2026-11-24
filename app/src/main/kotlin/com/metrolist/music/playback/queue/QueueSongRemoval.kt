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
 *
 * Removing the current song is a skip, as the Next button is (row 195, 2026-11-24): if there is a
 * next song and the player was paused, [postToPlayer] is handed a `play()` to run in a later
 * main-thread turn, so it never reaches the analytics listeners in the same batch as the removal.
 * While playing, ExoPlayer already carries on with the next song. The last song stops as before.
 */
fun removeQueueSong(
    player: Player,
    index: Int,
    expectedMediaId: String? = null,
    postToPlayer: ((Runnable) -> Unit)? = null,
): RemovedQueueSong? {
    if (index !in 0 until player.mediaItemCount) return null
    val mediaItem = player.getMediaItemAt(index)
    if (expectedMediaId != null && mediaItem.mediaId != expectedMediaId) return null
    val removed = RemovedQueueSong(index, mediaItem, player.shuffleModeEnabled, playOrderOf(player))
    val skipsToNext = index == player.currentMediaItemIndex && player.hasNextMediaItem()
    val resume = skipsToNext && !player.playWhenReady
    player.removeMediaItem(index)
    if (resume && postToPlayer != null) postToPlayer(Runnable { playAfterRemoval(player) })
    return removed
}

/** Starts the song that took a removed current song's place (see [removeQueueSong]). */
fun playAfterRemoval(player: Player) {
    if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) return
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.play()
}

/**
 * The positions in [local] that are missing from [remote], if [remote] is [local] with only some
 * songs taken out (the rest in the same order); null if anything else changed or nothing was
 * removed. Both lists are song ids in play order; a song in a queue twice is handled.
 */
fun removedPositions(local: List<String>, remote: List<String>): List<Int>? {
    if (remote.size >= local.size) return null
    val removed = ArrayList<Int>(local.size - remote.size)
    var r = 0
    for ((i, id) in local.withIndex()) {
        if (r < remote.size && remote[r] == id) r++ else removed += i
    }
    return removed.takeIf { r == remote.size }
}

/**
 * Queue sync (row 195): whether a synced change to the queue playing here can be applied without
 * stopping the song, and if so the new play-order index of that song. It can when the other device
 * only took songs out (or changed nothing in the song list, a rename say), the song playing here is
 * not one of them, and the shuffle flag and order agree. [localOrder] and [remoteOrder] are each
 * queue's real-order positions (QueueBoard's shuffle order), used only when shuffled.
 */
fun liveRemovalIndex(
    localSongs: List<String>,
    localShuffled: Boolean,
    localOrder: List<Int>?,
    currentIndex: Int,
    remoteSongs: List<String>,
    remoteShuffled: Boolean,
    remoteOrder: List<Int>?,
): Int? {
    if (localShuffled != remoteShuffled || currentIndex !in localSongs.indices) return null
    val removed = if (remoteSongs == localSongs) emptyList() else removedPositions(localSongs, remoteSongs) ?: return null
    if (currentIndex in removed) return null
    if (localShuffled) {
        if (localOrder == null || remoteOrder == null || localOrder.size != localSongs.size) return null
        val keptOrder = localOrder.filterIndexed { i, _ -> i !in removed }
        if (realPositionsOf(keptOrder) != remoteOrder) return null
    }
    return currentIndex - removed.count { it < currentIndex }
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
