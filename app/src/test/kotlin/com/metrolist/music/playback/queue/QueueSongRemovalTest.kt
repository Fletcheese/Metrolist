package com.metrolist.music.playback.queue

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.metrolist.music.playback.queue.sync.contentHash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Removing a song from a queue (row 194): what QueueBoard then saves, and so what the queue sync
 * sends. The player is ExoPlayer, whose playlist takes a song out of its shuffle order with
 * [ShuffleOrder.cloneAndRemove]; MusicService saves the songs in play order plus, when shuffled,
 * [realPositionsOf] that order, and QueueBoard hashes them with [contentHash].
 */
class QueueSongRemovalTest {
    private val songs = listOf("A", "B", "C", "D", "E")

    private fun ShuffleOrder.toList(): List<Int> {
        val out = mutableListOf<Int>()
        var i = firstIndex
        while (i != androidx.media3.common.C.INDEX_UNSET) {
            out += i
            i = getNextIndex(i)
        }
        return out
    }

    /** What QueueBoard saves for a shuffled queue: songs in play order, and the shuffle order. */
    private fun saved(songIds: List<String>, playOrder: List<Int>) =
        playOrder.map { songIds[it] } to realPositionsOf(playOrder)

    private fun hash(songIds: List<String>, playOrder: List<Int>): String {
        val (inPlayOrder, order) = saved(songIds, playOrder)
        return contentHash("FT test", null, true, inPlayOrder, order)
    }

    @Test
    fun removingAShuffledSongDropsItFromTheSongsAndTheShuffleOrder() {
        val playOrder = DefaultShuffleOrder(intArrayOf(3, 0, 4, 1, 2), 1L)
        assertEquals(listOf("D", "A", "E", "B", "C") to listOf(3, 0, 4, 1, 2), saved(songs, playOrder.toList()))

        // Remove B (media index 1), as player.removeMediaItem(1) does.
        val after = playOrder.cloneAndRemove(1, 2).toList()
        val remaining = songs - "B"
        assertEquals(listOf(2, 0, 3, 1), after)

        val (inPlayOrder, order) = saved(remaining, after)
        assertEquals(listOf("D", "A", "E", "C"), inPlayOrder)
        assertFalse("B" in inPlayOrder)
        // The other songs keep their relative shuffle order, and it is a valid order for 4 songs.
        assertEquals(listOf(2, 0, 3, 1), order)
        assertEquals((0 until 4).toList(), order.sorted())

        // A content change, so markContentChanged() records an edit and the sync pushes it.
        assertNotEquals(hash(songs, playOrder.toList()), hash(remaining, after))
    }

    @Test
    fun removingFromAnUnshuffledQueueIsAContentChangeToo() {
        val before = contentHash("FT test", null, false, songs, null)
        val after = contentHash("FT test", null, false, songs - "C", null)
        assertNotEquals(before, after)
    }

    @Test
    fun undoRestoresTheExactQueueAndItsHash() {
        val original = listOf(3, 0, 4, 1, 2)
        val afterRemove = DefaultShuffleOrder(original.toIntArray(), 1L).cloneAndRemove(1, 2)
        // Undo re-inserts B at media index 1; ExoPlayer puts it somewhere random in the shuffle,
        // then undoRemoveFromQueue() hands back the order from before the removal.
        val reinserted = afterRemove.cloneAndInsert(1, 1)
        assertEquals(5, reinserted.length)
        val restored = playOrderToRestore(original, reinserted.length)!!
        assertArrayEquals(original.toIntArray(), restored)
        assertEquals(hash(songs, original), hash(songs, DefaultShuffleOrder(restored, 2L).toList()))
    }

    @Test
    fun undoLeavesTheOrderAloneIfTheQueueChangedMeanwhile() {
        assertNull(playOrderToRestore(listOf(3, 0, 4, 1, 2), 6))
        assertNull(playOrderToRestore(listOf(3, 0, 4, 1, 1), 5))
    }

    /**
     * A Player that records every call. Reads answer from [ids] (the playlist) with [current]
     * playing; any other call is recorded and does nothing.
     */
    private class RecordingPlayer(val ids: List<String>, val current: Int) {
        val calls = mutableListOf<String>()
        val player: Player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
                when (method.name) {
                    "getMediaItemCount" -> ids.size
                    "getMediaItemAt" -> MediaItem.Builder().setMediaId(ids[args!![0] as Int]).build()
                    "getCurrentMediaItemIndex" -> current
                    "getShuffleModeEnabled" -> false
                    "getCurrentTimeline" -> Timeline.EMPTY
                    "hasNextMediaItem" -> current < ids.size - 1
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as Player
    }

    @Test
    fun removingThePlayingSongIsOneRemoveCallWithNoSeek() {
        // Row 194 crash: a seekToNextMediaItem() before removeMediaItem() in the same turn made
        // media3's PlaybackStatsListener throw. ExoPlayer moves on to the next song by itself.
        val rec = RecordingPlayer(listOf("A", "B", "C"), current = 1)
        val removed = removeQueueSong(rec.player, 1, "B")
        assertEquals(1, removed!!.mediaIndex)
        assertEquals("B", removed.mediaItem.mediaId)
        val writes = rec.calls.filterNot { it.startsWith("get") || it.startsWith("has") || it.startsWith("is") }
        assertEquals(listOf("removeMediaItem(1)"), writes)
        assertFalse(rec.calls.any { it.startsWith("seek") })
    }

    @Test
    fun nothingIsRemovedWhenTheRowNoLongerMatchesThePlayer() {
        val rec = RecordingPlayer(listOf("A", "B", "C"), current = 0)
        assertNull(removeQueueSong(rec.player, 1, "C"))
        assertNull(removeQueueSong(rec.player, 3, "C"))
        assertFalse(rec.calls.any { it.startsWith("remove") })
    }

    @Test
    fun realPositionsSkipAGapLeftByTheAutoplaySuggestion() {
        // The waiting Autoplay suggestion (media index 2 here) is left out of the saved order.
        assertEquals(listOf(2, 0, 1), realPositionsOf(listOf(3, 0, 1)))
    }
}
