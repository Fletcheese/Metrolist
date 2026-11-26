/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queue

/*
 * FletchTunes (row 201): a queue's original order, the order its songs were in when it was made,
 * so "Restore original order" can put it back after shuffling, dragging and Play Next.
 *
 * It is a list of song ids. Songs added later go on its end, in the order they sit in the queue,
 * wherever they were put in the queue itself; songs taken out of the queue drop out of it; drags and
 * shuffling leave it alone. A song in a queue more than once is matched occurrence by occurrence.
 *
 * Pure functions, so the rules are unit-tested on their own (QueueOriginalOrderTest).
 */

/**
 * A queue's songs in their real order (the order shuffle off plays), from its saved play order
 * [playOrder] and, when [shuffled], its shuffle order [shuffleOrder] (for each song in play order,
 * its place in the real order). An unusable shuffle order leaves the play order as it is.
 */
fun realOrderOf(playOrder: List<String>, shuffled: Boolean, shuffleOrder: List<Int>?): List<String> {
    if (!shuffled || shuffleOrder == null || shuffleOrder.size != playOrder.size) return playOrder
    if (shuffleOrder.sorted() != playOrder.indices.toList()) return playOrder
    val real = arrayOfNulls<String>(playOrder.size)
    shuffleOrder.forEachIndexed { playPos, realPos -> real[realPos] = playOrder[playPos] }
    return real.map { it!! }
}

/**
 * Brings original order [previous] up to date with the queue's songs [realOrder] (in real order):
 * songs still there keep their place, songs gone drop out, and songs new to the queue go on the
 * end in their real order. Null [previous] (a queue with no original order yet) takes [realOrder]
 * as it is. The result always holds exactly the queue's songs.
 */
fun reconcileOriginalOrder(previous: List<String>?, realOrder: List<String>): List<String> {
    if (previous == null) return realOrder
    val left = realOrder.groupingBy { it }.eachCount().toMutableMap()
    val kept = previous.filter { id ->
        val n = left[id] ?: 0
        if (n > 0) left[id] = n - 1
        n > 0
    }
    if (kept.size == realOrder.size) return kept
    // The new copies of a song are taken as its last ones in the queue.
    val added = ArrayList<String>(realOrder.size - kept.size)
    for (id in realOrder.asReversed()) {
        val n = left[id] ?: 0
        if (n > 0) {
            added += id
            left[id] = n - 1
        }
    }
    return kept + added.asReversed()
}

/**
 * What the content hash and the sync count of [original]: nothing while it is the same as the
 * queue's real order, so a queue whose original order was only just recorded (or was restored)
 * hashes as it did before row 201, and no device sees an edit, or a conflict, from recording it.
 */
fun originalOrderForHash(original: List<String>?, realOrder: List<String>): List<String>? =
    original?.takeIf { it != realOrder }

/**
 * One step towards putting [current] (song ids) in the order of [target] (the same songs): the
 * first place where they differ and where the song that belongs there is now, as (from, to) for
 * one `moveMediaItem(from, to)`; null once they match or if they are not the same songs. Taking
 * one step at a time lets the caller re-read the player between moves.
 */
fun nextReorderMove(current: List<String>, target: List<String>): Pair<Int, Int>? {
    if (current.size != target.size) return null
    val to = current.indices.firstOrNull { current[it] != target[it] } ?: return null
    val from = (to + 1 until current.size).firstOrNull { current[it] == target[to] } ?: return null
    return from to to
}

/**
 * For each song id in [order], its position in [current] (a song there twice is matched
 * occurrence by occurrence): the player's media indices for a play order kept as song ids, used
 * by Undo to give a queue back its shuffle order. Null if they are not the same songs.
 */
fun positionsInOrder(current: List<String>, order: List<String>): List<Int>? {
    if (current.size != order.size) return null
    val free = HashMap<String, ArrayDeque<Int>>()
    current.forEachIndexed { i, id -> free.getOrPut(id) { ArrayDeque() }.addLast(i) }
    return order.map { id -> free[id]?.removeFirstOrNull() ?: return null }
}

/** All the moves [nextReorderMove] makes to turn [current] into [target], or null if it cannot. */
fun reorderMoves(current: List<String>, target: List<String>): List<Pair<Int, Int>>? {
    if (current.size != target.size || current.sorted() != target.sorted()) return null
    val list = current.toMutableList()
    val moves = ArrayList<Pair<Int, Int>>()
    while (true) {
        val (from, to) = nextReorderMove(list, target) ?: break
        list.add(to, list.removeAt(from))
        moves += from to to
    }
    return moves.takeIf { list == target }
}

/**
 * How queue [queueId] stood just before "Restore original order", for its Undo: its songs in real
 * order and in play order (both song ids, without the waiting Autoplay suggestion) and whether it
 * was shuffled.
 */
data class QueueOrderSnapshot(
    val queueId: String,
    val realOrder: List<String>,
    val playOrder: List<String>,
    val shuffled: Boolean,
)

/** What "Restore original order" did. */
sealed interface RestoreOrderResult {
    /** Done; [before] is what Undo puts back. */
    data class Restored(val before: QueueOrderSnapshot) : RestoreOrderResult

    /** The queue was already unshuffled and in its original order, so nothing changed. */
    data object AlreadyInOrder : RestoreOrderResult

    /** No saved queue is loaded, or the queue changed while it was being reordered. */
    data object NotDone : RestoreOrderResult
}
