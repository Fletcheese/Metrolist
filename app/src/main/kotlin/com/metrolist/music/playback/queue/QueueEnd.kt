/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue

import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * FletchTunes (row 198, 2026-11-25): when a queue runs out (its last song in play order finished
 * and repeat is off), it goes back to its first song, paused at 0:00, so the next Play starts the
 * queue again instead of replaying the finished last song. "First" is the first song in play
 * order, so a shuffled queue starts its shuffle order again.
 *
 * Before this, the player sat at the end of the last song: the play buttons replayed media index 0
 * (not the first in shuffle order) only while the player was still in its ended state, and the
 * media session, a reopened app and the other devices all resumed the finished last song, because
 * QueueBoard saved and the queue sync sent the last index with its position at the end
 * ("Bass Persuades", 23 songs, synced at index 22 at 231.2 s of 232 s).
 *
 * The pause and the seek each run in their own later main-thread turn through [postToPlayer], so
 * no two player changes reach the analytics listeners as one batch (the row 194 crash). Either step
 * is skipped if the player has moved on meanwhile (Play pressed, a new queue started). After the
 * seek, [onRestarted] runs on the main thread: MusicService saves the new position to QueueBoard
 * there, which the queue sync then sends.
 *
 * Returns true if the restart was scheduled. Repeat one and repeat all are never touched.
 */
fun restartQueueAtEnd(
    player: Player,
    postToPlayer: (Runnable) -> Unit,
    onRestarted: () -> Unit = {},
): Boolean {
    if (!queueHasEnded(player)) return false
    postToPlayer(
        Runnable {
            if (!queueHasEnded(player)) return@Runnable
            player.playWhenReady = false
            postToPlayer(
                Runnable {
                    if (!queueHasEnded(player) || player.playWhenReady) return@Runnable
                    val first = firstInPlayOrder(player) ?: return@Runnable
                    player.seekTo(first, 0L)
                    onRestarted()
                },
            )
        },
    )
    return true
}

/** True when [player] has played to the end of its queue with repeat off. */
fun queueHasEnded(player: Player): Boolean =
    player.playbackState == Player.STATE_ENDED &&
        player.repeatMode == Player.REPEAT_MODE_OFF &&
        player.mediaItemCount > 0

/** Media index of the first song in [player]'s play order (the shuffle order when shuffled). */
fun firstInPlayOrder(player: Player): Int? {
    val timeline = player.currentTimeline
    if (timeline.isEmpty) return null
    return timeline.getFirstWindowIndex(player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
}
