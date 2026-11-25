/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

/** FletchTunes (row 198): where the queue page's jump arrow takes the list. */
enum class QueueJump { ToTop, ToBottom }

/**
 * Which way the queue page's jump arrow points, or null to hide it.
 *
 * - The whole queue fits on screen ([atTop] and [atEnd] both true): hidden.
 * - At the very top: down arrow, to the bottom. At the bottom: up arrow, to the top.
 * - In between: the arrow goes to the far end from where you are. If the middle of the screen
 *   ([centreSong], a song's place in the queue) is in the top half of the queue's [songCount]
 *   songs, it points down; from the halfway song on, it points up.
 *
 * "Bottom" is the queue's last song, not the Autoplay suggestions listed under it: [atEnd] means
 * the last song is fully on screen (or the list cannot scroll further).
 */
fun queueJumpDirection(
    atTop: Boolean,
    atEnd: Boolean,
    centreSong: Int,
    songCount: Int,
): QueueJump? =
    when {
        songCount <= 0 -> null
        atTop && atEnd -> null
        atTop -> QueueJump.ToBottom
        atEnd -> QueueJump.ToTop
        centreSong * 2 < songCount -> QueueJump.ToBottom
        else -> QueueJump.ToTop
    }
