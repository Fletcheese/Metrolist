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
import org.junit.Assert.assertTrue
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
    private class RecordingPlayer(val ids: List<String>, val current: Int, val playWhenReady: Boolean = true) {
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
                    "getPlayWhenReady" -> playWhenReady
                    "getPlaybackState" -> Player.STATE_READY
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

    private fun RecordingPlayer.writes() =
        calls.filterNot { it.startsWith("get") || it.startsWith("has") || it.startsWith("is") }

    @Test
    fun removingThePausedCurrentSongPlaysTheNextOneInALaterTurn() {
        // Row 195: Fletcher swiped away the current song while paused (tablet, 11-24 09:03:07);
        // the next song loaded but stayed paused. Removing it is a skip, like the Next button.
        val rec = RecordingPlayer(listOf("A", "B", "C"), current = 1, playWhenReady = false)
        val posted = mutableListOf<Runnable>()
        assertEquals("B", removeQueueSong(rec.player, 1, "B") { posted += it }!!.mediaItem.mediaId)
        // The removal's own turn holds only the removal (the row 194 crash came from batching).
        assertEquals(listOf("removeMediaItem(1)"), rec.writes())
        assertEquals(1, posted.size)
        posted.single().run()
        assertEquals(listOf("removeMediaItem(1)", "play"), rec.writes())
    }

    @Test
    fun removingThePlayingSongLeavesPlaybackToExoPlayer() {
        val rec = RecordingPlayer(listOf("A", "B", "C"), current = 1, playWhenReady = true)
        val posted = mutableListOf<Runnable>()
        removeQueueSong(rec.player, 1, "B") { posted += it }
        assertTrue(posted.isEmpty())
        assertEquals(listOf("removeMediaItem(1)"), rec.writes())
    }

    @Test
    fun removingTheLastSongOrAnotherSongDoesNotStartPlayback() {
        val last = RecordingPlayer(listOf("A", "B", "C"), current = 2, playWhenReady = false)
        val posted = mutableListOf<Runnable>()
        removeQueueSong(last.player, 2, "C") { posted += it }
        val other = RecordingPlayer(listOf("A", "B", "C"), current = 0, playWhenReady = false)
        removeQueueSong(other.player, 2, "C") { posted += it }
        assertTrue(posted.isEmpty())
    }

    @Test
    fun removedPositionsFindsSongsTakenOutAndNothingElse() {
        assertEquals(listOf(1, 3), removedPositions(listOf("A", "B", "C", "D"), listOf("A", "C")))
        // A song in the queue twice: either copy may go, the result is the same list.
        assertEquals(listOf(2), removedPositions(listOf("A", "B", "A"), listOf("A", "B")))
        assertEquals(listOf(0), removedPositions(listOf("A", "B", "A"), listOf("B", "A")))
        assertNull(removedPositions(listOf("A", "B", "C"), listOf("C", "A")))
        assertNull(removedPositions(listOf("A", "B"), listOf("A", "B")))
        assertNull(removedPositions(listOf("A", "B"), listOf("A", "B", "C")))
        assertNull(removedPositions(listOf("A", "B", "C"), listOf("A", "X")))
    }

    @Test
    fun aRemovalElsewhereAppliesLiveUnlessItTakesThePlayingSong() {
        val local = listOf("A", "B", "C", "D", "E")
        // Playing C (index 2); the other device took out A and E: C is now index 1.
        assertEquals(1, liveRemovalIndex(local, false, null, 2, listOf("B", "C", "D"), false, null))
        // A rename only: same songs, nothing to take out.
        assertEquals(2, liveRemovalIndex(local, false, null, 2, local, false, null))
        // The playing song itself was removed there: wait until paused.
        assertNull(liveRemovalIndex(local, false, null, 2, listOf("A", "B", "D", "E"), false, null))
        // Anything but removals (an added or moved song, shuffle switched) waits too.
        assertNull(liveRemovalIndex(local, false, null, 2, local + "F", false, null))
        assertNull(liveRemovalIndex(local, false, null, 2, listOf("B", "A", "C", "D", "E"), false, null))
        assertNull(liveRemovalIndex(local, false, null, 2, local, true, listOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun aShuffledRemovalAppliesLiveWhenTheShuffleOrdersAgree() {
        // In play order D A E B C (real positions 3 0 4 1 2); playing E; B removed elsewhere.
        val local = listOf("D", "A", "E", "B", "C")
        val order = listOf(3, 0, 4, 1, 2)
        val remoteOrder = realPositionsOf(DefaultShuffleOrder(order.toIntArray(), 1L).cloneAndRemove(1, 2).toList())
        assertEquals(listOf(2, 0, 3, 1), remoteOrder)
        assertEquals(2, liveRemovalIndex(local, true, order, 2, listOf("D", "A", "E", "C"), true, remoteOrder))
        assertNull(liveRemovalIndex(local, true, order, 2, listOf("D", "A", "E", "C"), true, listOf(0, 1, 2, 3)))
        assertNull(liveRemovalIndex(local, true, null, 2, listOf("D", "A", "E", "C"), true, remoteOrder))
    }

    @Test
    fun realPositionsSkipAGapLeftByTheAutoplaySuggestion() {
        // The waiting Autoplay suggestion (media index 2 here) is left out of the saved order.
        assertEquals(listOf(2, 0, 1), realPositionsOf(listOf(3, 0, 1)))
    }
}
